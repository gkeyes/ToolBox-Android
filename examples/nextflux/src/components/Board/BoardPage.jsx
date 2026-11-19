import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useStore } from "@nanostores/react";
import { useNavigate, useParams } from "react-router";
import { Button, Label, ListBox, Select, Tabs, cn } from "@heroui/react";
import { ChevronDown, RefreshCw } from "lucide-react";
import { SidebarTrigger } from "@/components/ui/sidebar.jsx";
import ArticleView from "@/components/ArticleView/ArticleView.jsx";
import CustomModal from "@/components/ui/CustomModal.jsx";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible.jsx";
import { getArticleById } from "@/db/storage.js";
import { settingsState } from "@/stores/settingsStore.js";
import { board, boardReadState, boardStream, digest, markBoardAsRead, openBoard, refreshBoard, showBoardReport, showTodayBoard, writeDigest } from "@/toolbox/board/boardStore.js";
import { generatePendingReport, initializeBoardHistory, markBoardReportSeen, reportHistory, retryPendingReport } from "@/toolbox/board/historyStore.js";
import { boardArticleIds, digestArticleNumbers, parseDigest } from "@/toolbox/board/board.mjs";
import { runtimeForeground } from "@/toolbox/foreground.js";
import { openInBrowser } from "@/toolbox/actions.js";
import { registerBackHandler } from "@/toolbox/systemBack.js";
import "@/toolbox/board/board.css";

// 今日看板 as a page of its own (#/board), opened from the sidebar. Its
// articles open over it at #/board/article/:id and Back returns here.
const clock = (at) => new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
const epoch = (value) => typeof value === "number" ? value : Date.parse(value || "");
const reportDate = (value) => Number.isFinite(epoch(value))
  ? new Date(epoch(value)).toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false })
  : "--";
const periodLabel = (report) => `${reportDate(report.periodStart)} — ${reportDate(report.periodEnd)}`;
// Presentation only: saved titles, source references and speech stay intact.
const titleWithoutOrdinal = (title) => String(title || "")
  .replace(/^\s*(?:(?:\d\uFE0F?\u20E3)+|🔟)\s*/u, "")
  .replace(/^\s*\d{1,2}(?:[.．]\s+|[、)）]\s*)/u, "")
  .trim();

function Spinner() {
  return <span className="nf-board-spinner" aria-hidden="true" />;
}

function speechAvailable() {
  return typeof window !== "undefined" && "speechSynthesis" in window && typeof window.SpeechSynthesisUtterance === "function";
}

function ArticleChips({ ids, articles, onOpen }) {
  const [all, setAll] = useState(false);
  const shown = all ? ids : ids.slice(0, 2);
  return (
    <div className="nf-board-chips">
      {shown.map((n) => {
        const article = articles[n - 1];
        if (!article) return null;
        return <a key={n} className="nf-board-chip" href={`#/board/article/${article.id}`} onClick={(event) => onOpen(event, article)}>
          {article.feedTitle ? `${article.feedTitle} · ` : ""}{article.title || "（无标题）"}
        </a>;
      })}
      {!all && ids.length > 2 && <button type="button" className="nf-board-chip is-more" onClick={() => setAll(true)}>还有 {ids.length - 2} 篇</button>}
    </div>
  );
}

function Topics({ state, onOpen }) {
  const fresh = new Set(state.freshIds || []);
  return state.topics.map((topic, index) => (
    <section key={index} className="nf-board-card">
      <div className="nf-board-card-head">
        <span className="nf-board-rank" data-rank={index + 1}>{index + 1}</span>
        <h3>{titleWithoutOrdinal(topic.title)}</h3>
        {fresh.size > 0 && topic.articles.some((n) => fresh.has(state.articles[n - 1]?.id)) && <span className="nf-board-new" aria-label="有新文章">新</span>}
        <span className="nf-board-row-count">{topic.articles.length} 篇</span>
      </div>
      {(topic.summary || topic.gist) && <p>{topic.summary || topic.gist}</p>}
      <ArticleChips ids={topic.articles} articles={state.articles} onOpen={onOpen} />
    </section>
  ));
}

function Digest({ state, onOpen }) {
  const written = useStore(digest);
  const [speaking, setSpeaking] = useState(false);
  const included = useMemo(() => new Set(digestArticleNumbers(state.topics, state.articles)), [state.topics, state.articles]);
  const sections = parseDigest(written.text, state.articles.length, included);
  const writing = written.status === "streaming";
  let ordinal = 0;
  // Opening or reopening the disclosure only reads. Generating prose is an
  // explicit action, including for today's report and after an earlier error.
  useEffect(() => () => { if (speechAvailable()) window.speechSynthesis.cancel(); }, []);
  const speak = () => {
    const synth = window.speechSynthesis;
    if (speaking) { synth.cancel(); setSpeaking(false); return; }
    const text = sections.map((section) => [section.title, section.parts.map((part) => part.text || "").join("")].join("。")).join("\n");
    const utterance = new window.SpeechSynthesisUtterance(text);
    utterance.lang = "zh-CN";
    utterance.onend = () => setSpeaking(false);
    utterance.onerror = () => setSpeaking(false);
    synth.cancel();
    synth.speak(utterance);
    setSpeaking(true);
  };
  const minutes = Math.max(1, Math.round(written.text.replace(/\s+/g, "").length / 400));
  return <>
    {writing && !sections.length && <p className="nf-board-status" role="status"><Spinner />正在整理完整报告…</p>}
    {sections.length > 0 && <article className="nf-board-card nf-board-digest">
      <p className="nf-board-digest-meta">根据看板收录的 {boardArticleIds(state.topics, state.articles).length} 篇文章与主题摘要写成{written.status === "done" ? ` · 约 ${minutes} 分钟读完` : ""}</p>
      {sections.map((section, index) => {
        const number = section.title ? ++ordinal : 0;
        return (
          <section key={index}>
            {section.title && <h4><span className="nf-board-rank" data-rank={number}>{number}</span><span>{titleWithoutOrdinal(section.title)}</span></h4>}
            <p>{section.parts.map((part, at) => {
              if ("text" in part) return part.text;
              const article = state.articles[part.cite - 1];
              return <a key={at} className="nf-board-cite" href={`#/board/article/${article.id}`} title={article.title} onClick={(event) => onOpen(event, article)}>
                {article.feedTitle || article.title || `#${part.cite}`}
              </a>;
            })}</p>
          </section>
        );
      })}
    </article>}
    {written.error && <p className="nf-board-inline-error" role="alert">{written.error}</p>}
    {!writing && !written.text && written.status !== "error" && <p className="nf-board-status">主题卡片已包含本期重点。如需连贯阅读，可按需生成完整报告。</p>}
    <div className="nf-board-actions">
      {speechAvailable() && written.status === "done" && <Button variant="secondary" fullWidth onPress={speak}>{speaking ? "停止朗读" : "朗读"}</Button>}
      {/* While it writes, the same button starts it over: a stream that never
          finishes is not a dead end. */}
      <Button variant="secondary" fullWidth isDisabled={state.updating} onPress={() => writeDigest({ again: true })}>{writing ? "重新开始写" : written.text ? "重新生成完整报告" : "生成完整报告"}</Button>
    </div>
  </>;
}

function CompleteReport({ state, onOpen }) {
  const [expanded, setExpanded] = useState(false);
  return <Collapsible className="nf-board-complete" open={expanded} onOpenChange={setExpanded}>
    <CollapsibleTrigger asChild>
      <Button variant="secondary" fullWidth className="nf-board-complete-toggle">
        完整报告<ChevronDown className={cn("size-4 shrink-0", expanded && "rotate-180")} aria-hidden="true" />
      </Button>
    </CollapsibleTrigger>
    <CollapsibleContent>
      {expanded && <div className="nf-board-complete-body"><Digest state={state} onOpen={onOpen} /></div>}
    </CollapsibleContent>
  </Collapsible>;
}

function ReadAction({ state, working, count }) {
  const read = useStore(boardReadState);
  const saving = read.status === "saving";
  const current = read.boardAt === state.at;
  return <div className="nf-board-read-action">
    <Button variant="secondary" fullWidth isDisabled={working || saving || count === 0} onPress={() => markBoardAsRead()}>
      {saving && <Spinner />}{saving ? "正在标为已读…" : "看板文章一键已读"}
    </Button>
    <p className="nf-board-read-help">仅处理看板收录的 {count} 篇，其余未读保留。</p>
    {current && read.status === "done" && <p className="nf-board-read-result" role="status">
      {read.count > 0 ? `已将 ${read.count} 篇看板文章标为已读。` : "看板收录的文章已经是已读。"}
    </p>}
    {current && read.status === "error" && <p className="nf-board-inline-error" role="alert">
      {read.count > 0 ? `已完成 ${read.count} 篇。` : ""}{read.error}
    </p>}
  </div>;
}

function automaticProgress(job) {
  const progress = job?.progress || {};
  const stage = progress.stage || job?.stage;
  if (job?.status === "error") return "这期报告尚未完成，已保存进度。";
  if (job?.status === "paused") return "这期报告已暂停，已保存进度。";
  if (stage === "digest") return "主题已整理，正在写成本期报告…";
  if (stage === "merge") return `正在汇总主题 · 第 ${progress.batchIndex || 1} / ${progress.batchCount || 1} 批`;
  if (stage === "articles") return `已分析 ${progress.processed || 0} / ${progress.total || 0} 篇 · 第 ${progress.batchIndex || 1} / ${progress.batchCount || 1} 批`;
  return "正在准备本期报告…";
}

function AutomaticReport({ history, enabled, aiReady, detailed, busy, onGenerate, onRetry }) {
  const job = history.job;
  const running = job?.status === "running";
  return <section className="nf-board-auto" aria-label="自动时段报告">
    {job && <p className="nf-board-auto-status" role="status">{running && <Spinner />}{automaticProgress(job)}</p>}
    <p className="nf-board-auto-count">
      {job ? "下一期待分析" : "已累计新增未读"} {history.pendingCount || 0} 篇
      {enabled ? " · 超过 150 篇自动生成" : " · 自动生成已关闭"}
    </p>
    {!aiReady && <p className="nf-board-auto-help">在 AI 设置中配置服务后可生成报告。</p>}
    {job?.error && <p className="nf-board-inline-error" role="alert">{job.error}</p>}
    {job && !running && <Button size="sm" variant="secondary" isDisabled={busy || !aiReady} onPress={onRetry}>
      {job.status === "error" ? "重试生成" : "继续生成"}
    </Button>}
    {detailed && !job && <Button size="sm" variant="secondary" isDisabled={busy || !aiReady || history.status !== "ready" || !history.pendingCount} onPress={onGenerate}>
      立即生成本时段
    </Button>}
  </section>;
}

function ReportPicker({ reports, selected, busy, onChoose }) {
  return <section className="nf-board-history" aria-label="报告历史">
    <Select fullWidth variant="secondary" value={selected || null} isDisabled={busy || !reports.length}
      placeholder={reports.length ? "选择一份报告" : "还没有已生成的报告"}
      onChange={(value) => { if (value) onChoose(String(value)); }}>
      <Label>报告时间</Label>
      <Select.Trigger id="nf-board-report-picker"><Select.Value /><Select.Indicator /></Select.Trigger>
      <Select.Popover className="nf-board-report-popover">
        <ListBox aria-label="历史报告">
          {reports.map((report) => {
            const label = `${report.kind === "today" ? "今日总览 · " : ""}${periodLabel(report)} · ${report.total || 0} 篇`;
            return <ListBox.Item key={report.id} id={report.id} textValue={report.unseen ? `未读 · ${label}` : label}>
              <span className="nf-board-report-option">
                {report.unseen && <span className="nf-board-unread ds-tone-info">未读</span>}
                {label}
              </span><ListBox.ItemIndicator />
            </ListBox.Item>;
          })}
        </ListBox>
      </Select.Popover>
    </Select>
    <p>报告自生成完成起保留 48 小时。</p>
  </section>;
}

function SourcePreview({ value, onClose }) {
  const article = value?.article;
  return <CustomModal open={Boolean(article)} onOpenChange={(open) => { if (!open) onClose(); }} title="报告来源预览"
    footer={article?.url ? <Button fullWidth variant="secondary" onPress={() => openInBrowser(article.url)}>打开原文链接</Button> : null}>
    {article && <div className="nf-board-source-preview">
      <p className="nf-board-source-note">{value.unavailable ? "原文暂时无法读取，下面是这份报告保存的预览。" : "原文缓存已清理，下面是这份报告保存的预览。"}</p>
      <h3>{article.title || "（无标题）"}</h3>
      {article.feedTitle && <p className="nf-board-source-feed">{article.feedTitle}</p>}
      <p className="nf-board-source-text">{article.excerpt || article.text || "这份报告没有保存正文预览。"}</p>
    </div>}
  </CustomModal>;
}

// While a stream runs only its newest part is drawn: redrawing a whole
// 200k-character thinking stream every 80ms held the page for up to 400ms
// at a time. The full text stays in the store (and in RawOutput on failure).
const LIVE_TAIL = 4000;

// A box of streamed text that stays scrolled to its newest line while the
// reader has not scrolled it back.
function LiveText({ text: full, className, tail = false }) {
  const cut = tail && full.length > LIVE_TAIL ? full.length - LIVE_TAIL : 0;
  const text = cut ? `……（前面 ${cut} 字略）\n${full.slice(cut)}` : full;
  const ref = useRef(null);
  const pinned = useRef(true);
  useEffect(() => {
    const box = ref.current;
    if (box && pinned.current) box.scrollTop = box.scrollHeight;
  }, [text]);
  return <pre
    ref={ref}
    className={cn("nf-board-live-text", className)}
    onScroll={(event) => {
      const box = event.currentTarget;
      pinned.current = box.scrollHeight - box.scrollTop - box.clientHeight < 24;
    }}
  >{text}</pre>;
}

// While a board is made: what was sent to the AI, then its thinking and its
// answer as they stream in.
function LiveRun({ stream }) {
  const [all, setAll] = useState(false);
  if (!stream) return <p className="nf-board-status" role="status"><Spinner />正在读取今天的未读文章…</p>;
  const shown = all ? stream.sent : stream.sent.slice(0, 3);
  const merging = stream.stage === "merge";
  const batch = `第 ${stream.batchIndex || 1} / ${stream.batchCount || 1} 批`;
  const phase = stream.text ? "AI 正在输出" : stream.thinking ? "AI 正在思考" : "等待 AI 回应";
  return <div className="nf-board-live" aria-live="off">
    <section className="nf-board-card nf-board-live-card">
      <div className="nf-board-live-head"><span className="nf-board-live-tag">{merging ? "汇总" : "分析"}</span><h3>{batch} · {stream.sent.length} {merging ? "个候选主题" : "篇未读"}</h3></div>
      <p className="nf-board-live-progress" role="status">{merging
        ? `今日 ${stream.total ?? stream.sent.length} 篇未读已分析，正在合并主题${stream.round > 1 ? `（第 ${stream.round} 轮）` : ""}。`
        : `今日共 ${stream.total ?? stream.sent.length} 篇未读，已分析 ${stream.processed || 0} 篇。`}</p>
      <ol className="nf-board-live-sent">
        {shown.map((article, index) => <li key={index}><span className="ds-tnum">[{index + 1}]</span>{article.feedTitle ? `${article.feedTitle} · ` : ""}{article.title || "（无标题）"}</li>)}
      </ol>
      {!all && stream.sent.length > 3 && <button type="button" className="nf-board-chip is-more" onClick={() => setAll(true)}>展开本批 {stream.sent.length} {merging ? "个主题" : "篇"}</button>}
    </section>
    {stream.first && <section className="nf-board-card nf-board-live-card">
      <div className="nf-board-live-head"><span className="nf-board-live-tag is-receive">第一次</span><h3>回答太长，被截在长度上限，没有一个完整的主题</h3></div>
      {stream.first.thinking && <LiveText className="is-thinking" text={stream.first.thinking} tail />}
      {stream.first.text && <LiveText text={stream.first.text} tail />}
    </section>}
    <section className="nf-board-card nf-board-live-card">
      <div className="nf-board-live-head"><span className="nf-board-live-tag is-receive">{stream.first ? "第二次" : "接收"}</span><h3>{stream.first ? `请 AI 回答得短一点 · ${phase}` : phase}</h3><Spinner /></div>
      {stream.thinking && <LiveText className="is-thinking" text={stream.thinking} tail />}
      {stream.text ? <LiveText text={stream.text} tail /> : null}
    </section>
  </div>;
}

// When the AI's answer could not be read: what it actually said.
// After a retry the first reply is shown too, above the second.
function RawOutput({ raw }) {
  if (!raw) return null;
  const parts = [raw.first && ["第一次", raw.first], [raw.first ? "第二次" : "", raw]].filter((part) => part && (part[1].text || part[1].thinking));
  return parts.map(([label, reply]) => <section key={label} className="nf-board-card nf-board-live-card">
    <div className="nf-board-live-head"><span className="nf-board-live-tag is-receive">{label ? `AI 原文 · ${label}` : "AI 原文"}</span><h3>{reply.text ? `${reply.text.length} 字` : "只有思考，没有回答"}</h3></div>
    {reply.thinking && <LiveText className="is-thinking" text={reply.thinking} />}
    {reply.text && <LiveText text={reply.text} />}
  </section>);
}

function caption(state, stream) {
  if (state.status === "loading" || state.updating) {
    const batch = stream ? `${stream.stage === "merge" ? "汇总" : "分析"} ${stream.batchIndex || 1}/${stream.batchCount || 1} 批 · ` : "";
    const phase = !stream ? "正在读取今日全部未读文章" : `${batch}${stream.text ? `${stream.text.length} 字 · AI 正在输出` : stream.first ? "回答被截断，正在精简重试" : stream.thinking ? "AI 正在思考" : "等待 AI 回应"}`;
    return state.updating && state.at ? `${phase}，下面是 ${clock(state.at)} 的版本，进度在卡片下方` : phase;
  }
  if (state.status === "done") return `今日 ${state.total} 篇 · 收录 ${boardArticleIds(state.topics, state.articles).length} 篇 · ${clock(state.at)} 更新`;
  return "";
}

export default function BoardPage() {
  const state = useStore(board);
  const stream = useStore(boardStream);
  const history = useStore(reportHistory);
  const foreground = useStore(runtimeForeground);
  const { articleListStyle, aiApiKey, autoBoardEnabled } = useStore(settingsState);
  const { articleId } = useParams();
  const navigate = useNavigate();
  const [scope, setScope] = useState(() => state.historical || state.kind === "interval" ? "reports" : "today");
  const [now, setNow] = useState(Date.now);
  const [choosing, setChoosing] = useState(false);
  const [actionError, setActionError] = useState("");
  const [sourcePreview, setSourcePreview] = useState(null);
  const pageRef = useRef(null);
  const headRef = useRef(null);
  const sourceRequest = useRef(0);
  const reportRequest = useRef(0);
  const reports = history.reports.filter((report) => epoch(report.expiresAt) > now);
  const selectedReport = reports.find((report) => report.id === state.reportId);
  const reportExpired = Number.isFinite(epoch(state.expiresAt)) && epoch(state.expiresAt) <= now;
  const isHistory = scope === "reports";
  const visible = !isHistory || (Boolean(selectedReport) && !reportExpired);
  const working = visible && (state.status === "loading" || state.updating);
  const done = visible && state.status === "done" && (isHistory || state.kind !== "interval");
  const includedCount = boardArticleIds(state.topics, state.articles).length;
  const unseenCount = reports.filter((report) => report.unseen).length;

  const visibleUnreadId = foreground && !articleId && !choosing && done && selectedReport?.unseen
    ? selectedReport.id : null;
  useEffect(() => {
    if (!visibleUnreadId) return;
    let current = true;
    markBoardReportSeen(visibleUnreadId).catch((error) => {
      if (current) setActionError(error.message || "报告已读状态暂时无法保存。");
    });
    return () => { current = false; };
  }, [visibleUnreadId]);

  // Reopening the board reuses its current report. Saved reports never start
  // another AI request, including when returning from their source articles.
  useEffect(() => {
    const request = ++reportRequest.current;
    Promise.resolve(openBoard(true)).then((openedScope) => {
      if (request === reportRequest.current && openedScope === "reports") setScope("reports");
    }).catch((error) => {
      if (request === reportRequest.current) setActionError(error.message || "看板暂时无法打开。");
    });
    initializeBoardHistory().catch((error) => setActionError(error.message || "报告历史暂时无法读取。"));
    return () => {
      sourceRequest.current += 1;
      reportRequest.current += 1;
      openBoard(false);
    };
  }, []);
  // Expiration applies to an already-open report too, without forcing a new
  // daily overview or replacing what the reader has selected.
  useEffect(() => {
    const deadlines = [...history.reports.map((report) => epoch(report.expiresAt)), epoch(state.expiresAt)].filter((at) => at > now);
    if (!deadlines.length) return undefined;
    const timer = setTimeout(() => {
      setNow(Date.now());
      initializeBoardHistory().catch((error) => setActionError(error.message || "报告历史暂时无法更新。"));
    }, Math.min(2_147_483_647, Math.max(1, Math.min(...deadlines) - Date.now() + 20)));
    return () => clearTimeout(timer);
  }, [history.reports, state.expiresAt, now]);
  useEffect(() => {
    sourceRequest.current += 1;
    setSourcePreview(null);
  }, [state.at, state.reportId, scope, reportExpired]);
  // The first visit can restore the latest saved report asynchronously. New
  // reports only update the history index; this follows an explicit selection.
  useEffect(() => {
    if (state.historical) setScope("reports");
  }, [state.historical, state.reportId]);
  // Back from the board goes to the article list (an open article handles
  // Back itself first).
  useEffect(() => (articleId ? undefined : registerBackHandler(() => navigate("/"))), [articleId, navigate]);
  // The header floats; the content starts under it.
  useLayoutEffect(() => {
    const head = headRef.current;
    const page = pageRef.current;
    if (!head || !page) return undefined;
    const apply = () => page.style.setProperty("--list-header-h", `${head.offsetHeight}px`);
    apply();
    const observer = typeof ResizeObserver === "function" ? new ResizeObserver(apply) : null;
    observer?.observe(head, { box: "border-box" });
    // Tabs inserts its collection after the header first mounts. Measure that
    // structural change immediately so content cannot use the shorter height
    // until ResizeObserver's next delivery. Text and style changes stay on RO.
    const children = typeof MutationObserver === "function" ? new MutationObserver(apply) : null;
    children?.observe(head, { childList: true, subtree: true });
    return () => { observer?.disconnect(); children?.disconnect(); };
  }, []);

  const openArticle = async (event, article) => {
    event.preventDefault();
    if (!isHistory) { navigate(`/board/article/${article.id}`); return; }
    const request = ++sourceRequest.current;
    const articles = state.articles;
    let cached;
    let unavailable = false;
    try { cached = await getArticleById(article.id); }
    catch { unavailable = true; }
    if (request !== sourceRequest.current || board.get().articles !== articles) return;
    if (cached) navigate(`/board/article/${article.id}`);
    else setSourcePreview({ article, unavailable });
  };
  const chooseReport = async (id) => {
    if (!id) return;
    const request = ++reportRequest.current;
    setChoosing(true);
    setActionError("");
    try { await showBoardReport(id); }
    catch (error) { if (request === reportRequest.current) setActionError(error.message || "这份报告暂时无法读取。"); }
    finally { if (request === reportRequest.current) setChoosing(false); }
  };
  const chooseScope = (value) => {
    if (value === scope) return;
    reportRequest.current += 1;
    setScope(value);
    setActionError("");
    if (value === "today") {
      setChoosing(false);
      Promise.resolve(showTodayBoard()).catch((error) => setActionError(error.message || "今日总览暂时无法更新。"));
    } else if (reports.length && (!selectedReport || !state.historical)) chooseReport(selectedReport?.id || reports[0].id);
  };
  const runPending = (retry = false) => {
    setActionError("");
    Promise.resolve(retry ? retryPendingReport() : generatePendingReport())
      .catch((error) => setActionError(error.message || "时段报告暂时无法生成。"));
  };
  const title = isHistory ? "历史报告" : "今日看板";
  const subheading = isHistory
    ? done ? `分析 ${state.total} 篇 · 收录 ${includedCount} 篇` : "查看最近 48 小时生成的报告"
    : caption(state, stream);
  const automatic = (isHistory || history.job || history.pendingCount > 0) && <AutomaticReport
    history={history} enabled={autoBoardEnabled !== false} aiReady={Boolean(aiApiKey?.trim())}
    detailed={isHistory} busy={choosing} onGenerate={() => runPending()} onRetry={() => runPending(true)}
  />;

  return (
    <div className="main-content flex">
      <div
        ref={pageRef}
        className="nextflux-article-list-page nf-board-page bg-background w-full relative max-w-screen md:w-84 md:max-w-[30%] md:min-w-[18rem] h-dvh flex flex-col"
        data-list-style={articleListStyle === "cards" ? "cards" : "lines"}
      >
        <div ref={headRef} className="article-list-header-wrap z-10 standalone:pt-safe" data-scoped="true">
          <div className="article-list-header ds-topbar w-full">
            <SidebarTrigger className="nextflux-toolbar-button" />
            <div className="ds-topbar-title article-list-title grid text-left">
              <h1 className="truncate">{title}</h1>
              {subheading && <span className={cn("article-list-caption ds-tnum truncate", working && "is-working")} role="status">{subheading}</span>}
            </div>
            {!isHistory && <div className="article-list-actions ml-auto flex shrink-0">
              <Button
                isIconOnly
                className="nextflux-toolbar-button"
                size="sm"
                radius="full"
                variant="ghost"
                aria-label="重新生成看板"
                isDisabled={working}
                onPress={() => refreshBoard()}
              >
                {working ? <Spinner /> : <RefreshCw className="size-5" />}
              </Button>
            </div>}
          </div>
          <div className="article-list-scoped-tabs nf-board-navigation" data-fit="full">
            <div className="article-list-tabs px-3">
              <Tabs selectedKey={scope} onSelectionChange={(key) => chooseScope(String(key))} aria-label="看板范围" variant="primary">
                <Tabs.ListContainer>
                  <Tabs.List aria-label="看板范围">
                    <Tabs.Tab id="today"><Tabs.Indicator className="bg-accent" /><span>今日</span></Tabs.Tab>
                    <Tabs.Tab id="reports" aria-label={unseenCount ? `历史报告，${unseenCount} 份未读` : "历史报告"}>
                      <Tabs.Indicator className="bg-accent" /><span>历史报告</span>
                      {unseenCount > 0 && <span className="nf-board-unread ds-tone-info" aria-hidden="true">{unseenCount}</span>}
                    </Tabs.Tab>
                  </Tabs.List>
                </Tabs.ListContainer>
              </Tabs>
            </div>
          </div>
        </div>
        <div className="nf-board-scroll">
          {isHistory && <ReportPicker reports={reports} selected={selectedReport?.id || ""} busy={choosing} onChoose={chooseReport} />}
          {history.job && automatic}
          {actionError && actionError !== history.job?.error && <p className="nf-board-inline-error" role="alert">{actionError}</p>}
          {isHistory && history.error && history.error !== actionError && history.error !== history.job?.error && <p className="nf-board-inline-error" role="alert">{history.error}</p>}
          {isHistory && history.status === "loading" && <p className="nf-board-status" role="status"><Spinner />正在读取报告历史…</p>}
          {isHistory && choosing && <p className="nf-board-status" role="status"><Spinner />正在打开报告…</p>}
          {isHistory && !choosing && !visible && history.status !== "loading" && <p className="nf-board-status" role="status">
            {reportExpired ? "这份报告已超过 48 小时。请选择其他报告，或返回今日。" : reports.length ? "选择一份报告，查看这个时段的重点。" : "还没有已生成的报告，新增未读会在这里累计。"}
          </p>}
          {!isHistory && (state.status === "idle" || state.status === "loading") ? <LiveRun stream={stream} /> : null}
          {!isHistory && state.newAvailableCount > 0 && <section className="nf-board-auto" aria-label="今日总览更新">
            <p className="nf-board-auto-count">又有 {state.newAvailableCount} 篇新增未读，可更新今日总览。</p>
            <Button size="sm" variant="secondary" isDisabled={working} onPress={() => refreshBoard()}>更新今日总览</Button>
          </section>}
          {!isHistory && state.status === "empty" && !state.newAvailableCount && <p className="nf-board-status" role="status">今天还没有未读文章。</p>}
          {!isHistory && state.status === "error" && <div className="nf-board-error" role="alert">
            <p>{state.error}</p>
            <Button size="sm" variant="secondary" onPress={() => refreshBoard()}>重试</Button>
          </div>}
          {!isHistory && state.status === "error" && <RawOutput raw={state.raw} />}
          {done && <>
            {isHistory && <div className="nf-board-period">
              <p>{reportDate(selectedReport.completedAt)} 生成 · {reportDate(state.expiresAt || selectedReport.expiresAt)} 到期</p>
            </div>}
            <p className="nf-board-summary">
              {`${isHistory && state.kind === "interval" ? "本时段" : "今日"} ${state.total} 篇未读已全部参与分析，${state.cut ? "AI 回答中途断开，先展示完整收到的" : "选出"} ${state.topics.length} 个主题，收录 ${includedCount} 篇文章`}
              {!isHistory && state.newCount > 0 && !state.updating ? `，比上一版多 ${state.newCount} 篇新文章` : ""}
            </p>
            <ReadAction state={state} working={working} count={includedCount} />
            {state.error && <p className="nf-board-inline-error" role="alert">更新失败：{state.error}</p>}
            {state.error && <RawOutput raw={state.raw} />}
            <div className={state.updating ? "nf-board-cards is-stale" : "nf-board-cards"}>
              <Topics state={state} onOpen={openArticle} />
              <CompleteReport key={state.reportId || state.at} state={state} onOpen={openArticle} />
            </div>
            {/* Under the cards: above them, its growing text pushed the board
                being read down by up to 600px. */}
            {state.updating && <LiveRun stream={stream} />}
          </>}
          {!history.job && automatic}
        </div>
      </div>
      <ArticleView />
      <SourcePreview value={sourcePreview} onClose={() => setSourcePreview(null)} />
    </div>
  );
}
