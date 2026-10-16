import { atom } from "nanostores";
import { getCachedArticleMetadata, getFeeds } from "@/db/storage.js";
import { settingsState } from "@/stores/settingsStore.js";
import { authState } from "@/stores/authStore.js";
import { isSyncing } from "@/stores/syncStore.js";
import { streamChat } from "@/api/openai.js";
import { digestMessages, fitArticles, parseTopics, startOfDay, todaysUnread, topicsMessages } from "./board.mjs";

// The board for today, kept while the app runs.
// status: idle | loading | done | empty | error. While a newer board is being
// made, the previous one stays on screen with updating = true.
const EMPTY = {
  status: "idle", topics: [], articles: [], skipped: 0, total: 0, at: null, day: 0,
  ids: [], newCount: 0, freshIds: [], updating: false, unseen: false, error: "",
};
export const board = atom({ ...EMPTY });
// The written digest of the current board. status: idle | streaming | done | error.
export const digest = atom({ status: "idle", text: "", error: "", boardAt: null });
export const boardOpen = atom(false);
// What the board run sent and what the AI is sending back, live, for the
// page to show while it waits: { sent: [{ title, feedTitle }], skipped,
// thinking, text }. Cleared when a board is made.
export const boardStream = atom(null);

let run = null;
let digestRun = null;

const aiReady = () => Boolean(settingsState.get().aiApiKey);

async function todaysArticles() {
  const settings = settingsState.get();
  const feeds = new Map((await getFeeds())
    .filter((feed) => settings.showHiddenFeeds || !feed.hide_globally)
    .map((feed) => [feed.id, feed]));
  const rows = todaysUnread(await getCachedArticleMetadata({ filter: "unread" }), {
    isVisibleFeed: (id) => feeds.has(id),
    hideDuplicates: settings.hideDuplicateArticles !== false,
  });
  return rows.map((row) => ({
    id: row.id,
    title: row.titleText || row.title || "",
    feedTitle: feeds.get(row.feedId)?.title || "",
    text: row.previewText || "",
  }));
}

// Builds today's board. With `onlyIfNew`, it does nothing unless today's
// unread articles include some the current board has not seen.
export async function refreshBoard({ onlyIfNew = false } = {}) {
  const previous = board.get();
  if (onlyIfNew && run) return;
  run?.abort();
  const controller = new AbortController();
  run = controller;
  const account = authState.get().userId;
  const live = () => run === controller && !controller.signal.aborted && authState.get().userId === account;
  const today = startOfDay().getTime();
  const keep = previous.status === "done" && previous.day === today;
  // A failed board remembers the articles it tried, so a sync that brings
  // nothing new does not ask the AI again.
  const failed = previous.status === "error" && previous.day === today;
  let all = [];
  try {
    if (!aiReady()) throw new Error("先在 NextFlux 设置里填好 AI 服务，才能生成今日看板。");
    all = await todaysArticles();
    if (!live()) return;
    const known = new Set(keep || failed ? previous.ids : []);
    const fresh = all.filter((article) => !known.has(article.id));
    if (onlyIfNew && (keep || failed) && !fresh.length) { if (run === controller) run = null; return; }
    if (!all.length) {
      stopDigest();
      board.set({ ...EMPTY, status: "empty", at: Date.now(), day: today });
      return;
    }
    // The written digest belongs to the board on screen: it keeps streaming
    // while a newer board is made and stops only when that board replaces it.
    if (!keep) stopDigest();
    board.set(keep ? { ...previous, updating: true, error: "" } : { ...EMPTY, status: "loading", day: today });
    const { articles, skipped } = fitArticles(all);
    let text = "";
    let thinking = "";
    let frame = null;
    const publish = () => {
      frame = null;
      if (live()) boardStream.set({ ...boardStream.get(), thinking, text });
    };
    const later = () => { frame ??= setTimeout(publish, 80); };
    boardStream.set({ sent: articles.map(({ title, feedTitle }) => ({ title, feedTitle })), skipped, thinking: "", text: "" });
    let topics;
    try {
      await streamChat(topicsMessages(articles), {
        signal: controller.signal,
        onChunk: (chunk) => { text += chunk; later(); },
        onThink: (chunk) => { thinking += chunk; later(); },
      });
    } catch (error) {
      // A reply cut off mid-way still makes a board from the topics that
      // arrived whole; when none did, what had arrived stays on screen.
      if (error?.code === "INCOMPLETE_STREAM" && text) {
        try { topics = parseTopics(text, articles.length); } catch { /* nothing whole arrived */ }
      }
      if (!topics) {
        if ((text || thinking) && error && typeof error === "object") error.raw ??= { thinking, text };
        throw error;
      }
    } finally {
      clearTimeout(frame);
      publish();
    }
    if (!live()) return;
    if (!topics) {
      try { topics = parseTopics(text, articles.length); } catch (error) {
        // Keep what the AI said on screen so the reader can see why.
        error.raw = { thinking, text };
        throw error;
      }
    }
    boardStream.set(null);
    stopDigest();
    board.set({
      ...EMPTY,
      status: "done",
      topics,
      articles: articles.map(({ id, title, feedTitle }) => ({ id, title, feedTitle })),
      skipped,
      total: all.length,
      at: Date.now(),
      day: today,
      ids: all.map((article) => article.id),
      newCount: keep ? fresh.length : 0,
      // Articles new since the previous board, for the topic 新 marks.
      freshIds: keep ? fresh.map((article) => article.id) : [],
      unseen: !boardOpen.get(),
    });
    digest.set({ status: "idle", text: "", error: "", boardAt: null });
  } catch (error) {
    if (!live()) return;
    const message = error?.message || "生成今日看板失败。";
    board.set(keep
      ? { ...board.get(), updating: false, error: message, raw: error?.raw ?? null }
      : { ...EMPTY, status: "error", error: message, raw: error?.raw ?? null, day: today, ids: all.map((article) => article.id) });
    boardStream.set(null);
  } finally {
    if (run === controller) run = null;
  }
}

function stopDigest() {
  digestRun?.abort();
  digestRun = null;
}

export function openBoard(open = !boardOpen.get()) {
  boardOpen.set(open);
  // Leaving the board clears its "N new articles" note: it has been seen.
  if (!open) { if (board.get().newCount) board.set({ ...board.get(), newCount: 0, freshIds: [] }); return; }
  const state = board.get();
  board.set({ ...state, unseen: false });
  const stale = state.day !== startOfDay().getTime();
  if (!run && (state.status === "idle" || state.status === "error" || stale)) refreshBoard();
}

// The digest is written once per board, on first view of the 文章 tab.
export async function writeDigest({ again = false } = {}) {
  const state = board.get();
  const current = digest.get();
  if (state.status !== "done") return;
  if (!again && current.boardAt === state.at && current.status !== "error") return;
  digestRun?.abort();
  const controller = new AbortController();
  digestRun = controller;
  const live = () => digestRun === controller && !controller.signal.aborted && board.get().at === state.at;
  digest.set({ status: "streaming", text: "", error: "", boardAt: state.at });
  try {
    const all = await todaysArticles();
    if (!live()) return;
    // Number the articles exactly as the board did.
    const byId = new Map(all.map((article) => [article.id, article]));
    const articles = fitArticles(state.articles.map((article) => byId.get(article.id) || { ...article, text: "" })).articles;
    let text = "";
    await streamChat(digestMessages(state.topics, articles), {
      signal: controller.signal,
      onChunk: (chunk) => { text += chunk; if (live()) digest.set({ ...digest.get(), text }); },
    });
    if (!live()) return;
    if (!text.trim()) throw new Error("AI 没有返回内容，请重新生成。");
    digest.set({ ...digest.get(), status: "done" });
  } catch (error) {
    if (!live()) return;
    digest.set({ ...digest.get(), status: "error", error: error?.message || "生成文章失败。" });
  } finally {
    if (digestRun === controller) digestRun = null;
  }
}

function reset() {
  run?.abort();
  run = null;
  stopDigest();
  board.set({ ...EMPTY });
  boardStream.set(null);
  digest.set({ status: "idle", text: "", error: "", boardAt: null });
  boardOpen.set(false);
}

// Another account (or none) must never see this account's board.
let account = authState.get().userId;
authState.listen((auth) => {
  if (auth.userId === account) return;
  account = auth.userId;
  reset();
});

// After each sync, bring a board made this session up to date with new
// articles. A launch makes none until the board page is opened.
let wasSyncing = isSyncing.get();
isSyncing.listen((syncing) => {
  const finished = wasSyncing && !syncing;
  wasSyncing = syncing;
  if (!finished || !authState.get().userId || !aiReady()) return;
  if (board.get().status === "idle") return;
  refreshBoard({ onlyIfNew: true });
});
