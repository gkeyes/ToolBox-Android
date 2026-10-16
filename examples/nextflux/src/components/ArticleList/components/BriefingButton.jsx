import { useState } from "react";
import { useStore } from "@nanostores/react";
import { useNavigate } from "react-router";
import { Button } from "@heroui/react";
import { AISummaryIcon } from "@/components/ui/icons.jsx";
import CustomModal from "@/components/ui/CustomModal.jsx";
import { briefing, generateBriefing, isStale } from "@/toolbox/briefing/briefingStore.js";
import { parseBriefing } from "@/toolbox/briefing/briefing.mjs";
import "@/toolbox/briefing/briefing.css";

const TITLE = "今日简报";
const clock = (at) => new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });

// Today's briefing: the AI condenses today's unread articles into a few
// themed sections; each cites its articles, which open on tap.
export default function BriefingButton() {
  const state = useStore(briefing);
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const working = state.status === "loading" || state.status === "streaming";
  const sections = parseBriefing(state.text, state.articles.length);

  const show = () => {
    setOpen(true);
    if (!working && (state.status === "idle" || state.status === "error" || isStale(state))) generateBriefing();
  };
  const openArticle = (event, article) => {
    event.preventDefault();
    setOpen(false);
    navigate(`/article/${article.id}`);
  };

  const footer = <>
    <Button variant="tertiary" fullWidth onPress={() => setOpen(false)}>关闭</Button>
    <Button fullWidth onPress={generateBriefing} isPending={working}>重新生成</Button>
  </>;

  return <>
    <Button className="nextflux-toolbar-button" aria-label={TITLE} title={TITLE} size="sm" radius="full" variant="ghost" isIconOnly onPress={show}>
      <AISummaryIcon className="size-4 text-muted" />
    </Button>
    <CustomModal open={open} onOpenChange={(next) => { if (!next) setOpen(false); }} title={TITLE} footer={footer}>
      <div className="nf-briefing">
        {state.articles.length > 0 && <p className="nf-briefing-note">
          今天 {state.articles.length} 篇未读{state.status === "done" && state.at ? ` · ${clock(state.at)} 生成` : ""}。点文章名可打开原文。
        </p>}
        {(state.status === "loading" || (state.status === "streaming" && !sections.length)) && <div className="nf-briefing-status" role="status">
          <span className="nf-briefing-spinner" aria-hidden="true" />
          <span>{state.status === "loading" ? "正在读取今天的未读文章…" : "正在生成简报…"}</span>
        </div>}
        {state.status === "empty" && <p className="nf-briefing-status" role="status">今天还没有未读文章。</p>}
        {sections.map((section, index) => <section key={index} className="nf-briefing-section">
          {section.title && <h3>{section.title}</h3>}
          <p>{section.parts.map((part, at) => {
            if (part.text) return part.text;
            const article = state.articles[part.cite - 1];
            return <a key={at} className="nf-briefing-cite" href={`#/article/${article.id}`} title={article.title} onClick={(event) => openArticle(event, article)}>{article.title || article.feedTitle || `#${part.cite}`}</a>;
          })}</p>
        </section>)}
        {state.status === "error" && <p className="nf-briefing-error" role="alert">{state.error}</p>}
      </div>
    </CustomModal>
  </>;
}
