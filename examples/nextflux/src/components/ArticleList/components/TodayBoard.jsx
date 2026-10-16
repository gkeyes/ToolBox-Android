import { useEffect, useState } from "react";
import { useStore } from "@nanostores/react";
import { useNavigate } from "react-router";
import { Button } from "@heroui/react";
import { AISummaryIcon } from "@/components/ui/icons.jsx";
import CustomModal from "@/components/ui/CustomModal.jsx";
import { Segmented } from "@/components/ui/settingItem.jsx";
import { board, boardOpen, digest, openBoard, refreshBoard, writeDigest } from "@/toolbox/board/boardStore.js";
import { parseDigest } from "@/toolbox/board/board.mjs";
import "@/toolbox/board/board.css";

const clock = (at) => new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
const PANEL_TOPICS = 3;

function Spinner() {
  return <span className="nf-board-spinner" aria-hidden="true" />;
}

// The 看板 button beside the filter tabs. A dot marks a board updated since
// it was last opened; a ring while it is being made.
export function BoardButton() {
  const open = useStore(boardOpen);
  const state = useStore(board);
  const working = state.status === "loading" || state.updating;
  return (
    <Button
      className="article-board-button"
      variant={open ? "primary" : "secondary"}
      aria-label="今日看板"
      aria-expanded={open}
      onPress={() => openBoard()}
    >
      <AISummaryIcon className="size-4" />
      <span className="article-board-button-label">看板</span>
      {working ? <span className="article-board-badge is-working" aria-hidden="true" />
        : state.unseen && !open ? <span className="article-board-badge" aria-hidden="true" /> : null}
    </Button>
  );
}

function TopicRow({ topic, rank, onPress }) {
  return (
    <button type="button" className="nf-board-row" onClick={onPress}>
      <span className="nf-board-rank" data-rank={rank}>{rank}</span>
      <span className="nf-board-row-text">
        <span className="nf-board-row-title">{topic.title}</span>
        {topic.gist && <span className="nf-board-row-gist">{topic.gist}</span>}
      </span>
      <span className="nf-board-row-count">{topic.articles.length} 篇</span>
    </button>
  );
}

function statusLine(state) {
  if (state.updating) return <span className="nf-board-when is-working"><Spinner />正在更新</span>;
  if (state.at && state.status === "done") return <span className="nf-board-when">{clock(state.at)} 更新</span>;
  return null;
}

// Today's board, unfolded under the top bar.
export function BoardPanel() {
  const open = useStore(boardOpen);
  const state = useStore(board);
  const [sheet, setSheet] = useState(null);
  if (!open && !sheet) return null;
  const topics = state.topics;
  const read = state.total - state.skipped;
  return <>
    {open && <section className="nf-board-panel" aria-label="今日看板">
      <div className="nf-board-head">
        <AISummaryIcon className="nf-board-icon" />
        <h2>今日看板</h2>
        {state.newCount > 0 && !state.updating && <span className="nf-board-new">+{state.newCount} 新</span>}
        {statusLine(state)}
        <button type="button" className="nf-board-collapse" aria-label="收起看板" onClick={() => openBoard(false)}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M18 15l-6-6-6 6" /></svg>
        </button>
      </div>
      {state.status === "loading" && <p className="nf-board-status" role="status"><Spinner />正在读取今天的未读文章并归类…</p>}
      {state.status === "empty" && <p className="nf-board-status" role="status">今天还没有未读文章。</p>}
      {state.status === "error" && <div className="nf-board-error" role="alert">
        <p>{state.error}</p>
        <Button size="sm" variant="secondary" onPress={() => refreshBoard()}>重试</Button>
      </div>}
      {state.status === "done" && <>
        <p className="nf-board-summary">
          今天 {state.total} 篇未读，AI 归成 {topics.length} 个主题，按重要程度排列
          {state.skipped > 0 ? `（只看了最新的 ${read} 篇）` : ""}
        </p>
        {state.error && <p className="nf-board-inline-error" role="alert">更新失败：{state.error}</p>}
        <div className={state.updating ? "nf-board-rows is-stale" : "nf-board-rows"}>
          {topics.slice(0, PANEL_TOPICS).map((topic, index) => (
            <TopicRow key={index} topic={topic} rank={index + 1} onPress={() => setSheet({ tab: "topics", focus: index })} />
          ))}
        </div>
        <div className="nf-board-actions">
          <Button variant="secondary" fullWidth onPress={() => setSheet({ tab: "topics", focus: null })}>全部 {topics.length} 个主题</Button>
          <Button variant="primary" fullWidth onPress={() => setSheet({ tab: "digest", focus: null })}>读成文章</Button>
        </div>
      </>}
    </section>}
    {sheet && <BoardSheet initial={sheet} onClose={() => setSheet(null)} />}
  </>;
}

function ArticleChips({ ids, articles, onOpen }) {
  const [all, setAll] = useState(false);
  const shown = all ? ids : ids.slice(0, 2);
  return (
    <div className="nf-board-chips">
      {shown.map((n) => {
        const article = articles[n - 1];
        if (!article) return null;
        return <a key={n} className="nf-board-chip" href={`#/article/${article.id}`} onClick={(event) => onOpen(event, article)}>
          {article.feedTitle ? `${article.feedTitle} · ` : ""}{article.title || "（无标题）"}
        </a>;
      })}
      {!all && ids.length > 2 && <button type="button" className="nf-board-chip is-more" onClick={() => setAll(true)}>还有 {ids.length - 2} 篇</button>}
    </div>
  );
}

function speechAvailable() {
  return typeof window !== "undefined" && "speechSynthesis" in window && typeof window.SpeechSynthesisUtterance === "function";
}

// 全部主题 and 读成文章, as two tabs of one sheet.
function BoardSheet({ initial, onClose }) {
  const state = useStore(board);
  const written = useStore(digest);
  const navigate = useNavigate();
  const [tab, setTab] = useState(initial.tab);
  const [speaking, setSpeaking] = useState(false);
  const sections = parseDigest(written.text, state.articles.length);
  const writing = written.status === "streaming";

  useEffect(() => { if (tab === "digest") writeDigest(); }, [tab, state.at]);
  useEffect(() => {
    if (initial.focus == null) return;
    document.getElementById(`nf-board-topic-${initial.focus}`)?.scrollIntoView({ block: "start" });
  }, [initial.focus]);
  useEffect(() => () => { if (speechAvailable()) window.speechSynthesis.cancel(); }, []);

  const openArticle = (event, article) => {
    event.preventDefault();
    onClose();
    openBoard(false);
    navigate(`/article/${article.id}`);
  };
  const speak = () => {
    if (!speechAvailable()) return;
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

  const footer = tab === "topics" ? <>
    <Button variant="tertiary" fullWidth onPress={onClose}>关闭</Button>
    <Button fullWidth isPending={state.status === "loading" || state.updating} onPress={() => refreshBoard()}>重新生成</Button>
  </> : <>
    <Button variant="tertiary" fullWidth onPress={onClose}>关闭</Button>
    {speechAvailable() && written.status === "done"
      ? <Button fullWidth onPress={speak}>{speaking ? "停止朗读" : "朗读"}</Button>
      : <Button fullWidth isPending={writing} onPress={() => writeDigest({ again: true })}>重新生成</Button>}
  </>;

  return (
    <CustomModal open onOpenChange={(next) => { if (!next) onClose(); }} title="今日看板" footer={footer}>
      <div className="nf-board-sheet">
        <p className="nf-board-summary">
          {state.total} 篇未读{state.at ? ` · ${clock(state.at)} 更新` : ""}
        </p>
        <Segmented
          ariaLabel="看板形式"
          block
          selectedKey={tab}
          onSelectionChange={(key) => setTab(String(key))}
          options={[{ value: "topics", label: "主题" }, { value: "digest", label: "文章" }]}
        />
        {tab === "topics" && state.topics.map((topic, index) => (
          <section key={index} id={`nf-board-topic-${index}`} className="nf-board-card">
            <div className="nf-board-card-head">
              <span className="nf-board-rank" data-rank={index + 1}>{index + 1}</span>
              <h3>{topic.title}</h3>
              <span className="nf-board-row-count">{topic.articles.length} 篇</span>
            </div>
            {(topic.summary || topic.gist) && <p>{topic.summary || topic.gist}</p>}
            <ArticleChips ids={topic.articles} articles={state.articles} onOpen={openArticle} />
          </section>
        ))}
        {tab === "digest" && <>
          {writing && !sections.length && <p className="nf-board-status" role="status"><Spinner />正在把今天的主题写成文章…</p>}
          {sections.map((section, index) => (
            <section key={index} className="nf-board-card">
              {section.title && <h3>{section.title}</h3>}
              <p>{section.parts.map((part, at) => {
                if ("text" in part) return part.text;
                const article = state.articles[part.cite - 1];
                return <a key={at} className="nf-board-cite" href={`#/article/${article.id}`} title={article.title} onClick={(event) => openArticle(event, article)}>
                  {article.feedTitle || article.title || `#${part.cite}`}
                </a>;
              })}</p>
            </section>
          ))}
          {written.status === "error" && <p className="nf-board-inline-error" role="alert">{written.error}</p>}
        </>}
      </div>
    </CustomModal>
  );
}
