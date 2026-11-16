import { atom } from "nanostores";
import { getCachedArticleMetadata, getFeeds } from "@/db/storage.js";
import { settingsState } from "@/stores/settingsStore.js";
import { authState } from "@/stores/authStore.js";
import { isSyncing } from "@/stores/syncStore.js";
import { markArticlesAsRead } from "@/stores/articlesStore.js";
import { streamChat } from "@/api/openai.js";
import {
  batchArticles, batchTopics, boardArticleIds, digestMessages, expandTopics,
  mapBatchTopics, mergeTopicsMessages, parseTopics, readTopics, startOfDay,
  todaysUnread, topicsMessages,
} from "./board.mjs";

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
// An explicit action on the sources of one displayed board, never all of
// the articles that were sent to the AI for consideration.
const READ_EMPTY = { status: "idle", boardAt: null, count: 0, error: "" };
export const boardReadState = atom({ ...READ_EMPTY });

let run = null;
// The article settings the running board is made under.
let runFilter = null;
// A pending settings change.
let settle = null;
// A sync finished that the board has not read yet (it was being made, its
// settings were settling, or it was closed and out of date).
let unsynced = false;
let digestRun = null;
let readRun = null;

const aiReady = () => Boolean(settingsState.get().aiApiKey);

// The settings that decide which articles a board is made from. A board made
// under other values is out of date, like one from another day.
const articleFilter = () => {
  const settings = settingsState.get();
  return `${Boolean(settings.showHiddenFeeds)}|${settings.hideDuplicateArticles !== false}|${settings.duplicateMatch || "either"}`;
};

async function todaysArticles() {
  const settings = settingsState.get();
  const feeds = new Map((await getFeeds())
    .filter((feed) => settings.showHiddenFeeds || !feed.hide_globally)
    .map((feed) => [feed.id, feed]));
  const rows = todaysUnread(await getCachedArticleMetadata({ filter: "unread" }), {
    isVisibleFeed: (id) => feeds.has(id),
    hideDuplicates: settings.hideDuplicateArticles !== false,
    duplicateMatch: settings.duplicateMatch,
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
  // This run reads today's articles afresh.
  unsynced = false;
  run?.abort();
  const controller = new AbortController();
  run = controller;
  const account = authState.get().userId;
  const live = () => run === controller && !controller.signal.aborted && authState.get().userId === account;
  const today = startOfDay().getTime();
  const filter = articleFilter();
  runFilter = filter;
  const keep = previous.status === "done" && previous.day === today && previous.filter === filter;
  // A failed board remembers the articles it tried, so a sync that brings
  // nothing new does not ask the AI again.
  const failed = previous.status === "error" && previous.day === today && previous.filter === filter;
  let all = [];
  try {
    if (!aiReady()) throw new Error("先在 NextFlux 设置里填好 AI 服务，才能生成今日看板。");
    all = await todaysArticles();
    if (!live()) return;
    const known = new Set(keep || failed ? previous.ids : []);
    const fresh = all.filter((article) => !known.has(article.id));
    // Nothing new: the finally below still reads a sync that landed meanwhile.
    if (onlyIfNew && (keep || failed) && !fresh.length) return;
    if (!all.length) {
      stopDigest();
      board.set({ ...EMPTY, status: "empty", at: Date.now(), day: today, filter });
      if (!readRun) boardReadState.set({ ...READ_EMPTY });
      return;
    }
    // The written digest belongs to the board on screen: it keeps streaming
    // while a newer board is made and stops only when that board replaces it.
    if (!keep) stopDigest();
    board.set(keep ? { ...previous, updating: true, error: "" } : { ...EMPTY, status: "loading", day: today, filter });
    // Every eligible article belongs to this immutable, numbered snapshot.
    // The request budget limits each batch, never the day's coverage.
    const { articles, batches } = batchArticles(all);
    let activeAsk = null;
    // Each batch reuses the streaming parser and its one shorter retry.
    // Merge calls reference candidate topics; only code expands those
    // references back to article numbers in the original snapshot.
    const ask = async (items, messages, progress, first = null) => {
      const request = {};
      activeAsk = request;
      const brief = Boolean(first);
      let text = "";
      let thinking = "";
      let frame = null;
      const publish = () => {
        frame = null;
        if (live() && activeAsk === request) boardStream.set({ ...boardStream.get(), thinking, text });
      };
      const later = () => { frame ??= setTimeout(publish, 80); };
      const sent = items.map(({ title, feedTitle }) => ({ title, feedTitle }));
      boardStream.set({ sent, skipped: 0, ...progress, thinking: "", text: "", first });
      let read = null;
      let length = false;
      try {
        const result = await streamChat(messages(items, { brief }), {
          signal: controller.signal,
          onChunk: (chunk) => { text += chunk; later(); },
          onThink: (chunk) => { thinking += chunk; later(); },
        });
        length = result?.finishReason === "length";
      } catch (error) {
        // A reply cut off mid-way still makes a board from the topics that
        // arrived whole; when none did, what had arrived stays on screen.
        if (error?.code === "INCOMPLETE_STREAM" && text) {
          try { read = { topics: parseTopics(text, items.length), cut: true }; } catch { /* nothing whole arrived */ }
        }
        if (!read) {
          if ((text || thinking) && error && typeof error === "object") error.raw ??= { thinking, text };
          throw error;
        }
      } finally {
        clearTimeout(frame);
        publish();
      }
      if (read || !live()) return read;
      try {
        const { topics, whole } = readTopics(text, items.length);
        // Stopped at the length limit: later topics may be missing, unless
        // the JSON had already closed.
        return { topics, cut: length && !whole };
      } catch (error) {
        // Keep what the AI said on screen so the reader can see why.
        error.raw = { thinking, text };
        if (length) {
          error.length = true;
          error.message = "AI 的回答太长，在写完第一个主题之前就被截断了。请重新生成；如果一直这样，可以在 AI 设置里换一个输出长度上限更高的模型。";
        }
        throw error;
      }
    };
    const readBatch = async (items, messages, progress) => {
      try {
        return await ask(items, messages, progress);
      } catch (error) {
        if (!error?.length || !live()) throw error;
        const first = { text: error.raw?.text || "", thinking: error.raw?.thinking || "" };
        try {
          return await ask(items, messages, progress, first);
        } catch (again) {
          // A second failure keeps the first reply next to its own.
          if (again && typeof again === "object") again.raw = { thinking: "", text: "", ...again.raw, first };
          throw again;
        }
      }
    };
    let topics = [];
    let cut = false;
    let processed = 0;
    for (const [index, batch] of batches.entries()) {
      if (!live()) return;
      const read = await readBatch(batch, topicsMessages, {
        stage: "articles", batchIndex: index + 1, batchCount: batches.length,
        processed, total: all.length,
      });
      if (!live()) return;
      topics.push(...mapBatchTopics(read.topics, batch));
      cut ||= read.cut;
      processed += batch.length;
    }
    // A busy day still ends in a few ranked topics. Candidate summaries
    // themselves are batched if needed, so no later batch is discarded to
    // squeeze a consolidation request into the context window.
    if (batches.length > 1) {
      let round = 1;
      while (topics.length > 1) {
        const groups = batchTopics(topics);
        const merged = [];
        for (const [index, candidates] of groups.entries()) {
          if (!live()) return;
          if (groups.length > 1 && candidates.length === 1) {
            merged.push(...candidates);
            continue;
          }
          const read = await readBatch(candidates, mergeTopicsMessages, {
            stage: "merge", batchIndex: index + 1, batchCount: groups.length,
            round, processed, total: all.length,
          });
          if (!live()) return;
          merged.push(...expandTopics(read.topics, candidates));
          cut ||= read.cut;
        }
        // Every full candidate batch holds more items than an answer can
        // return. Guard that invariant before attempting another level.
        if (groups.length > 1 && merged.length >= topics.length) throw new Error("AI 主题汇总未能收敛，请重新生成。");
        topics = merged;
        if (groups.length === 1) break;
        round += 1;
      }
    }
    if (!live()) return;
    boardStream.set(null);
    stopDigest();
    board.set({
      ...EMPTY,
      status: "done",
      topics,
      // The reply broke off, so later topics may be missing.
      cut,
      // Keep the original excerpts for the digest, including after a
      // reader marks these sources read or a later sync changes the cache.
      articles,
      skipped: 0,
      total: all.length,
      at: Math.max(Date.now(), (previous.at || 0) + 1),
      day: today,
      filter,
      ids: all.map((article) => article.id),
      newCount: keep ? fresh.length : 0,
      // Articles new since the previous board, for the topic 新 marks.
      freshIds: keep ? fresh.map((article) => article.id) : [],
      unseen: !boardOpen.get(),
    });
    digest.set({ status: "idle", text: "", error: "", boardAt: null });
    if (!readRun) boardReadState.set({ ...READ_EMPTY });
  } catch (error) {
    if (!live()) return;
    const message = error?.message || "生成今日看板失败。";
    board.set(keep
      ? { ...board.get(), updating: false, error: message, raw: error?.raw ?? null }
      : { ...EMPTY, status: "error", error: message, raw: error?.raw ?? null, day: today, filter, ids: all.map((article) => article.id) });
    boardStream.set(null);
  } finally {
    if (run === controller) {
      run = null;
      catchUp();
    }
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
  const stale = state.day !== startOfDay().getTime() || state.filter !== articleFilter();
  if (!run && (state.status === "idle" || state.status === "error" || stale)) refreshBoard();
  else catchUp();
}

// Reads a sync the board missed, once nothing else is under way: a board
// made under today's settings takes only what is new; one under other
// settings is made again if open, else when it is next opened.
function catchUp() {
  if (!unsynced || run || settle || !authState.get().userId || !aiReady()) return;
  const state = board.get();
  if (state.status === "idle") return;
  const current = state.filter === articleFilter() && state.day === startOfDay().getTime();
  if (current) refreshBoard({ onlyIfNew: true });
  else if (boardOpen.get()) refreshBoard();
}

// Freeze the sources of the displayed board at the click, not the set of
// candidates considered by the AI. Refreshing the board cannot widen an
// operation already in flight, and repeated clicks share the same action.
export function markBoardAsRead() {
  if (readRun) return readRun.promise;
  const state = board.get();
  if (state.status !== "done" || state.updating) return Promise.resolve(0);
  const ids = boardArticleIds(state.topics, state.articles);
  if (!ids.length) return Promise.resolve(0);
  const account = authState.get().userId;
  const operation = {};
  readRun = operation;
  const live = () => readRun === operation && authState.get().userId === account;
  const sameBoard = () => board.get().articles === state.articles && board.get().at === state.at;
  boardReadState.set({ status: "saving", boardAt: state.at, count: 0, error: "" });
  operation.promise = (async () => {
    try {
      const count = await markArticlesAsRead(ids);
      if (live()) boardReadState.set(sameBoard()
        ? { status: "done", boardAt: state.at, count, error: "" }
        : { ...READ_EMPTY });
      return count;
    } catch (error) {
      const count = error?.markedCount || 0;
      if (live()) boardReadState.set(sameBoard()
        ? { status: "error", boardAt: state.at, count, error: error?.message || "标为已读失败，请重试。" }
        : { ...READ_EMPTY });
      return count;
    } finally {
      if (readRun === operation) readRun = null;
    }
  })();
  return operation.promise;
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
    let text = "";
    await streamChat(digestMessages(state.topics, state.articles), {
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
  readRun = null;
  boardReadState.set({ ...READ_EMPTY });
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
  // A board not made yet takes the sync when it is made; one being made
  // for the first time reads it after.
  if (board.get().status === "idle" && !run) return;
  // Read now, or after the board being made, the settings settling, or
  // (closed and out of date) the next opening; settings changed and
  // changed back cost nothing.
  unsynced = true;
  catchUp();
});

// Settings changed while the board is open (from the settings sheet over
// it): the board on screen follows them once they settle, so flipping
// through the options asks the AI once, and flipping back asks nothing.
const SETTLE_MS = 600;
let lastFilter = articleFilter();
settingsState.listen(() => {
  const filter = articleFilter();
  if (filter === lastFilter) return;
  lastFilter = filter;
  clearTimeout(settle);
  settle = setTimeout(() => {
    settle = null;
    const state = board.get();
    if (state.status === "idle" || !authState.get().userId) return;
    const made = run ? runFilter : state.filter;
    // An open board follows at once; a closed one waits to be opened.
    if ((made !== articleFilter() || state.day !== startOfDay().getTime()) && boardOpen.get()) refreshBoard();
    // Back to the settings the board was made under: only what a sync
    // meanwhile brought in, after the board being made, if any.
    else catchUp();
  }, SETTLE_MS);
});
