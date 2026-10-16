import { useEffect, useRef, useState } from "react";
import { useStore } from "@nanostores/react";
import { useNavigate, useParams } from "react-router";
import { Button, Tabs, cn } from "@heroui/react";
import { RefreshCw } from "lucide-react";
import { AISummaryIcon } from "@/components/ui/icons.jsx";
import { SidebarTrigger } from "@/components/ui/sidebar.jsx";
import ArticleView from "@/components/ArticleView/ArticleView.jsx";
import { settingsState } from "@/stores/settingsStore.js";
import { board, boardStream, digest, openBoard, refreshBoard, writeDigest } from "@/toolbox/board/boardStore.js";
import { parseDigest } from "@/toolbox/board/board.mjs";
import { registerBackHandler } from "@/toolbox/systemBack.js";
import "@/toolbox/board/board.css";

// 今日看板 as a page of its own (#/board), opened from the sidebar. Its
// articles open over it at #/board/article/:id and Back returns here.
const clock = (at) => new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });

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
        <h3>{topic.title}</h3>
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
  const sections = parseDigest(written.text, state.articles.length);
  const writing = written.status === "streaming";
  useEffect(() => { writeDigest(); }, [state.at]);
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
    {writing && !sections.length && <p className="nf-board-status" role="status"><Spinner />正在把今天的主题写成文章…</p>}
    {sections.length > 0 && <article className="nf-board-card nf-board-digest">
      <p className="nf-board-digest-meta">AI 根据 {state.total - state.skipped} 篇未读写成{written.status === "done" ? ` · 约 ${minutes} 分钟读完` : ""}</p>
      {sections.map((section, index) => (
        <section key={index}>
          {section.title && <h4>{section.title}</h4>}
          <p>{section.parts.map((part, at) => {
            if ("text" in part) return part.text;
            const article = state.articles[part.cite - 1];
            return <a key={at} className="nf-board-cite" href={`#/board/article/${article.id}`} title={article.title} onClick={(event) => onOpen(event, article)}>
              {article.feedTitle || article.title || `#${part.cite}`}
            </a>;
          })}</p>
        </section>
      ))}
    </article>}
    {written.status === "error" && <p className="nf-board-inline-error" role="alert">{written.error}</p>}
    <div className="nf-board-actions">
      {speechAvailable() && written.status === "done" && <Button variant="secondary" fullWidth onPress={speak}>{speaking ? "停止朗读" : "朗读"}</Button>}
      {/* While it writes, the same button starts it over: a stream that never
          finishes is not a dead end. */}
      <Button variant="secondary" fullWidth onPress={() => writeDigest({ again: true })}>{writing ? "重新开始写" : "重新写一篇"}</Button>
    </div>
  </>;
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
  const phase = stream.text ? "AI 正在输出" : stream.thinking ? "AI 正在思考" : "等待 AI 回应";
  return <div className="nf-board-live" aria-live="off">
    <section className="nf-board-card nf-board-live-card">
      <div className="nf-board-live-head"><span className="nf-board-live-tag">发送</span><h3>今天的 {stream.sent.length} 篇未读{stream.skipped > 0 ? `（另有 ${stream.skipped} 篇较早的没有发送）` : ""}</h3></div>
      <ol className="nf-board-live-sent">
        {shown.map((article, index) => <li key={index}><span className="ds-tnum">[{index + 1}]</span>{article.feedTitle ? `${article.feedTitle} · ` : ""}{article.title || "（无标题）"}</li>)}
      </ol>
      {!all && stream.sent.length > 3 && <button type="button" className="nf-board-chip is-more" onClick={() => setAll(true)}>展开全部 {stream.sent.length} 篇</button>}
    </section>
    {stream.first && <section className="nf-board-card nf-board-live-card">
      <div className="nf-board-live-head"><span className="nf-board-live-tag is-receive">第一次</span><h3>回答太长，被截在长度上限，没有一个完整的主题</h3></div>
      {stream.first.text && <LiveText text={stream.first.text} />}
    </section>}
    <section className="nf-board-card nf-board-live-card">
      <div className="nf-board-live-head"><span className="nf-board-live-tag is-receive">{stream.first ? "第二次" : "接收"}</span><h3>{stream.first ? `请 AI 回答得短一点 · ${phase}` : phase}</h3><Spinner /></div>
      {stream.thinking && <LiveText className="is-thinking" text={stream.thinking} tail />}
      {stream.text ? <LiveText text={stream.text} tail /> : null}
    </section>
  </div>;
}

// When the AI's answer could not be read: what it actually said.
function RawOutput({ raw }) {
  if (!raw || !(raw.text || raw.thinking)) return null;
  return <section className="nf-board-card nf-board-live-card">
    <div className="nf-board-live-head"><span className="nf-board-live-tag is-receive">AI 原文</span><h3>{raw.text ? `${raw.text.length} 字` : "只有思考，没有回答"}</h3></div>
    {raw.thinking && <LiveText className="is-thinking" text={raw.thinking} />}
    {raw.text && <LiveText text={raw.text} />}
  </section>;
}

function caption(state, stream) {
  if (state.status === "loading" || state.updating) {
    const phase = !stream ? "正在读取未读文章" : stream.first && !stream.text ? "第一次的回答被截断，正在请 AI 回答得短一点" : stream.text ? `AI 正在输出 · ${stream.text.length} 字` : stream.thinking ? "AI 正在思考" : `已发送 ${stream.sent.length} 篇，等待 AI`;
    return state.updating && state.at ? `${phase}，上面是 ${clock(state.at)} 的版本` : phase;
  }
  if (state.status === "done") return `${state.total} 篇未读 · ${clock(state.at)} 更新`;
  return "";
}

let lastTab = "topics";

export default function BoardPage() {
  const state = useStore(board);
  const stream = useStore(boardStream);
  const { articleListStyle } = useStore(settingsState);
  const { articleId } = useParams();
  const navigate = useNavigate();
  // The 主题/文章 choice is kept while the app runs, so coming back from the
  // list opens the board on the same view.
  const [tab, setTabState] = useState(() => lastTab);
  const setTab = (value) => { lastTab = value; setTabState(value); };
  const pageRef = useRef(null);
  const headRef = useRef(null);

  // Showing the page is opening the board: it is made (or brought up to
  // date) here, and its new-article mark clears once seen.
  useEffect(() => {
    openBoard(true);
    return () => openBoard(false);
  }, []);
  // Back from the board goes to the article list (an open article handles
  // Back itself first).
  useEffect(() => (articleId ? undefined : registerBackHandler(() => navigate("/"))), [articleId, navigate]);
  // The header floats; the content starts under it.
  useEffect(() => {
    const head = headRef.current;
    const page = pageRef.current;
    if (!head || !page) return undefined;
    const apply = () => page.style.setProperty("--list-header-h", `${head.offsetHeight}px`);
    apply();
    const observer = typeof ResizeObserver === "function" ? new ResizeObserver(apply) : null;
    observer?.observe(head, { box: "border-box" });
    return () => observer?.disconnect();
  }, []);

  const openArticle = (event, article) => {
    event.preventDefault();
    navigate(`/board/article/${article.id}`);
  };
  const working = state.status === "loading" || state.updating;
  const done = state.status === "done";
  const read = state.total - state.skipped;

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
              <h1 className="truncate">今日看板</h1>
              {caption(state, stream) && <span className={cn("article-list-caption ds-tnum truncate", working && "is-working")} role="status">{caption(state, stream)}</span>}
            </div>
            <div className="article-list-actions ml-auto flex shrink-0">
              <Button
                isIconOnly
                className="nextflux-toolbar-button"
                size="sm"
                radius="full"
                variant="ghost"
                aria-label="重新生成看板"
                onPress={() => refreshBoard()}
              >
                {working ? <Spinner /> : <RefreshCw className="size-5" />}
              </Button>
            </div>
          </div>
          {done && (
            <div className="article-list-scoped-tabs nf-board-switch" data-fit="full">
              <div className="article-list-tabs px-3">
                <Tabs selectedKey={tab} onSelectionChange={(key) => setTab(String(key))} aria-label="看板形式" variant="primary">
                  <Tabs.ListContainer>
                    <Tabs.List aria-label="看板形式" className="w-fit *:w-fit">
                      <Tabs.Tab id="topics"><Tabs.Indicator className="bg-accent" /><span>主题</span></Tabs.Tab>
                      <Tabs.Tab id="digest"><Tabs.Indicator className="bg-accent" /><span>文章</span></Tabs.Tab>
                    </Tabs.List>
                  </Tabs.ListContainer>
                </Tabs>
              </div>
            </div>
          )}
        </div>
        <div className="nf-board-scroll">
          {state.status === "idle" || state.status === "loading" ? <LiveRun stream={stream} /> : null}
          {state.status === "empty" && <p className="nf-board-status" role="status">今天还没有未读文章。</p>}
          {state.status === "error" && <div className="nf-board-error" role="alert">
            <p>{state.error}</p>
            <Button size="sm" variant="secondary" onPress={() => refreshBoard()}>重试</Button>
          </div>}
          {state.status === "error" && <RawOutput raw={state.raw} />}
          {done && <>
            <p className="nf-board-summary">
              {tab !== "topics" ? `把 ${state.topics.length} 个主题写成一篇文章`
                : state.cut ? `AI 读了今天的 ${read} 篇未读，回答中途断开，只列出完整收到的 ${state.topics.length} 个主题`
                  : `AI 把今天的 ${read} 篇未读归成 ${state.topics.length} 个主题，按重要程度排列`}
              {state.skipped > 0 ? `（只看了最新的 ${read} 篇）` : ""}
              {state.newCount > 0 && !state.updating ? `，比上一版多 ${state.newCount} 篇新文章` : ""}
            </p>
            {state.error && <p className="nf-board-inline-error" role="alert">更新失败：{state.error}</p>}
            {state.error && <RawOutput raw={state.raw} />}
            <div className={state.updating ? "nf-board-cards is-stale" : "nf-board-cards"}>
              {tab === "topics" ? <Topics state={state} onOpen={openArticle} /> : <Digest state={state} onOpen={openArticle} />}
            </div>
            {/* Under the cards: above them, its growing text pushed the board
                being read down by up to 600px. */}
            {state.updating && <LiveRun stream={stream} />}
          </>}
        </div>
      </div>
      <ArticleView />
    </div>
  );
}

// The sidebar row that opens the board: a dot when a board is ready but not
// yet seen, a ring while one is made.
export function BoardSidebarIcon() {
  const state = useStore(board);
  const working = state.status === "loading" || state.updating;
  return <span className="nf-board-side-icon">
    <AISummaryIcon />
    {working ? <span className="nf-board-side-badge is-working" aria-hidden="true" />
      : state.unseen ? <span className="nf-board-side-badge" aria-hidden="true" /> : null}
  </span>;
}
