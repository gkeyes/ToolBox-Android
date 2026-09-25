import { useStore } from "@nanostores/react";
import { Button, Spinner, Tooltip } from "@heroui/react";
import { WandSparkles } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { activeArticle } from "@/stores/articlesStore.js";
import { feeds } from "@/stores/feedsStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import minifluxAPI from "@/api/miniflux.js";
import CustomModal from "@/components/ui/CustomModal.jsx";
import { fetchSourceSnapshot, inspectSelector } from "@/toolbox/fulltext-adapter/fulltext-source.mjs";
import { suggestScraperRule } from "@/toolbox/fulltext-adapter/fulltext-ai.mjs";
import { applyScraperRule, contentMetrics, testScraperRule } from "@/toolbox/fulltext-adapter/fulltext-server.mjs";
import "@/toolbox/fulltext-adapter/fulltext-adapter.css";

export default function FullTextAdaptButton() {
  const article = useStore(activeArticle);
  const allFeeds = useStore(feeds);
  const settings = useStore(settingsState);
  const [open,setOpen]=useState(false), [phase,setPhase]=useState("idle"), [error,setError]=useState("");
  const [baseline,setBaseline]=useState(null), [snapshot,setSnapshot]=useState(null), [candidate,setCandidate]=useState(null), [tested,setTested]=useState(null);
  const controller=useRef(null);
  const feedId=Number(article?.feedId); const feed=allFeeds.find(f=>Number(f.id)===feedId);
  useEffect(()=>()=>controller.current?.abort(),[]);
  if (!Number.isSafeInteger(feedId)||feedId<=0) return null;
  const busy=["loading","ai","testing","applying"].includes(phase);
  const close=()=>{ if(!busy){controller.current?.abort();setOpen(false);} };
  async function start(previousTest=null){
    setOpen(true); setError(""); setTested(null); setCandidate(null); setPhase("loading");
    controller.current?.abort(); controller.current=new AbortController();
    try{
      if(!settings.aiApiKey) throw new Error("请先在 NextFlux 设置中配置 AI API Key。");
      const [remote,source]=await Promise.all([minifluxAPI.getFeed(feedId),fetchSourceSnapshot(article.url)]);
      if(Number(activeArticle.get()?.id)!==Number(article.id)) throw new Error("文章已切换，请重新打开全文适配。");
      setBaseline(remote); setSnapshot(source); setPhase("ai");
      const suggestion=await suggestScraperRule({article,feed:remote,structure:source.structure,settings,signal:controller.current.signal,previousTest});
      const local=inspectSelector(source.html,suggestion.rule);
      if(!local.matches) throw new Error("AI 规则在原网页中没有匹配到内容，请重新生成。");
      setCandidate({...suggestion,local}); setPhase("candidate");
    }catch(e){if(e?.code!=="CANCELLED"){setError(e.message||"全文适配未完成。");setPhase("error");}}
  }
  async function test(){
    if(!candidate||!baseline)return; setError("");setPhase("testing");
    try{
      const result=await testScraperRule({feedId,entryId:article.id,candidate:candidate.rule,baselineRule:baseline.scraper_rules||"",api:minifluxAPI});
      setTested(result);setPhase("tested");
    }catch(e){setError(e.message||"测试抓取失败。");setPhase("candidate");}
  }
  async function apply(){
    if(!candidate||!baseline||!tested)return; setError("");setPhase("applying");
    try{
      const saved=await applyScraperRule({feedId,candidate:candidate.rule,baselineRule:baseline.scraper_rules||"",api:minifluxAPI});
      feeds.set(feeds.get().map(f=>Number(f.id)===feedId?{...f,scraper_rules:saved.scraper_rules}:f));
      setPhase("done");
    }catch(e){setError(e.message||"保存规则失败。");setPhase("tested");}
  }
  const current=contentMetrics(article?.content||"");
  const footer=phase==="done"
    ? <Button fullWidth onPress={close}>完成</Button>
    : <><Button variant="tertiary" fullWidth onPress={close} isDisabled={busy}>取消</Button>
      {phase==="candidate"&&<Button fullWidth onPress={test}>测试全文抓取</Button>}
      {phase==="tested"&&<Button fullWidth onPress={apply}>应用此规则</Button>}</>;
  return <>
    <Tooltip delay={0}><Button className="nextflux-toolbar-button" aria-label="全文适配" title="全文适配" variant="ghost" isIconOnly size="sm" onPress={()=>start()}><WandSparkles className="size-4 text-muted"/></Button>
      <Tooltip.Content showArrow><Tooltip.Arrow/>全文适配</Tooltip.Content></Tooltip>
    <CustomModal open={open} onOpenChange={next=>{if(!next)close();}} title="AI 全文适配" footer={footer}>
      <div className="nf-fulltext-adapt">
        <p className="nf-fulltext-note">{feed?.title||baseline?.title||"当前订阅"} · AI 只接收压缩后的网页结构，不发送登录凭据。</p>
        {(phase==="loading"||phase==="ai")&&<div className="nf-fulltext-card"><Spinner size="sm"/><p className="nf-fulltext-note">{phase==="loading"?"正在读取服务器规则和原网页结构…":"正在让 AI 生成 Miniflux Scraper Rule…"}</p></div>}
        {baseline&&<div className="nf-fulltext-card"><strong>当前服务器采集规则</strong><code className="nf-fulltext-rule">{baseline.scraper_rules||"（未设置，使用 Miniflux Readability/预置规则）"}</code></div>}
        {candidate&&<div className="nf-fulltext-card"><strong>AI 候选规则</strong><code className="nf-fulltext-rule">{candidate.rule}</code><p className="nf-fulltext-note">{candidate.reason||"已根据网页结构生成。"}{candidate.confidence!=null?` · 置信度 ${Math.round(candidate.confidence*100)}%`:""}</p><p className="nf-fulltext-note">本地结构匹配 {candidate.local.matches} 个节点，约 {candidate.local.textChars} 字。</p></div>}
        {tested&&<div className="nf-fulltext-card"><strong>Miniflux 实测结果</strong><div className="nf-fulltext-metrics"><span className="nf-fulltext-metric"><b>{tested.metrics.textChars}</b>字</span><span className="nf-fulltext-metric"><b>{tested.metrics.paragraphs}</b>段</span><span className="nf-fulltext-metric"><b>{tested.metrics.images}</b>图</span></div><p className="nf-fulltext-note">当前正文约 {current.textChars} 字。测试时规则已临时写入 Miniflux 并安全回滚；只有点击“应用此规则”才永久保存。</p><div className="nf-fulltext-preview">{tested.metrics.sample}</div><Button variant="ghost" size="sm" onPress={()=>start({rule:candidate.rule,metrics:tested.metrics})}>让 AI 再优化</Button></div>}
        {phase==="done"&&<div className="nf-fulltext-card"><strong>规则已应用</strong><p className="nf-fulltext-note">以后点击正文栏现有的“全文”按钮，Miniflux 会直接使用这条 scraper_rules；新条目自动全文仍由订阅的 crawler 开关决定。</p></div>}
        {error&&<p role="alert" className="nf-fulltext-note" style={{color:"var(--danger)"}}>{error}</p>}
      </div>
    </CustomModal>
  </>;
}
