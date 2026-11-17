import { atom } from "nanostores";
import { getCachedArticleMetadata, getFeeds } from "@/db/storage.js";
import { settingsState } from "@/stores/settingsStore.js";
import { authState } from "@/stores/authStore.js";
import { syncCommitted } from "@/stores/syncStore.js";
import { markArticlesAsRead } from "@/stores/articlesStore.js";
import { streamChat } from "@/api/openai.js";
import { boardArticleIds, digestMessages, startOfDay, todaysUnread } from "./board.mjs";
import { generateBoard } from "./generate-board.js";
import { runtimeClosing } from "../foreground.js";
import { initializeBoardHistory, readBoardReport, reportHistory, saveBoardDigest, saveBoardSnapshot } from "./historyStore.js";

// The board for today, kept while the app runs.
// status: idle | loading | done | empty | error. While a newer board is being
// made, the previous one stays on screen with updating = true.
const EMPTY = {
  status: "idle", topics: [], articles: [], skipped: 0, total: 0, at: null, day: 0,
  ids: [], newCount: 0, freshIds: [], updating: false, unseen: false, error: "",
  kind: "today", historical: false, reportId: null, expiresAt: null,
  periodStart: null, periodEnd: null, newAvailableCount: 0,
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
let selection = 0;
let todaySnapshot = null;
let todayDigest = null;
let snapshotSave = null;
const reportWrites = new Set();

function trackReportWrite(promise) {
  reportWrites.add(promise);
  void promise.catch(() => {}).finally(() => reportWrites.delete(promise));
  return promise;
}

const aiReady = () => Boolean(settingsState.get().aiApiKey);
const accountKey = () => `${authState.get().serverUrl || ""}|${authState.get().userId || ""}`;

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
    url: row.url || "", published_at: row.published_at, created_at: row.created_at,
  }));
}

// Builds today's board. With `onlyIfNew`, it does nothing unless today's
// unread articles include some the current board has not seen.
export async function refreshBoard({ onlyIfNew = false } = {}) {
  if (runtimeClosing.get()) return;
  const previous = board.get().historical ? todaySnapshot || { ...EMPTY } : board.get();
  if (onlyIfNew && run) return;
  selection += 1;
  // This run reads today's articles afresh.
  unsynced = false;
  run?.abort();
  const controller = new AbortController();
  run = controller;
  const account = accountKey();
  const live = () => run === controller && !controller.signal.aborted && accountKey() === account;
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
      todaySnapshot = board.get();
      if (!readRun) boardReadState.set({ ...READ_EMPTY });
      return;
    }
    // The written digest belongs to the board on screen: it keeps streaming
    // while a newer board is made and stops only when that board replaces it.
    if (!keep) stopDigest();
    board.set(keep ? { ...previous, updating: true, error: "" } : { ...EMPTY, status: "loading", day: today, filter });
    const { articles, topics, cut } = await generateBoard({
      articles: all, signal: controller.signal,
      onProgress: (progress) => { if (live()) boardStream.set(progress); },
    });
    if (!live()) return;
    boardStream.set(null);
    stopDigest();
    const at = Math.max(Date.now(), (previous.at || 0) + 1);
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
      at, reportId: `today-${at}`, periodStart: today, periodEnd: at,
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
    todaySnapshot = board.get();
    todayDigest = digest.get();
    // Publishing a successful board does not depend on history storage. A
    // storage failure is visible, while this session's result stays usable.
    try {
      const pending = trackReportWrite(saveBoardSnapshot(todaySnapshot));
      snapshotSave = { at, promise: pending };
      const saved = await pending;
      if (live() && saved) {
        board.set({ ...board.get(), expiresAt: saved.expiresAt });
        todaySnapshot = board.get();
      }
    } catch (error) {
      if (live()) board.set({ ...board.get(), error: `看板已生成，历史保存失败：${error.message || "请重试"}` });
    }
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

function rememberToday() {
  const state = board.get();
  if (!state.historical && state.status !== "idle" && state.status !== "loading") {
    todaySnapshot = { ...state, updating: false };
    todayDigest = digest.get();
  }
}

export async function showBoardReport(id) {
  const token = ++selection;
  const account = accountKey();
  const saved = await readBoardReport(id);
  if (token !== selection || accountKey() !== account) return;
  if (!saved || saved.expiresAt <= Date.now()) throw new Error("这份看板报告已过期或已删除。");
  rememberToday();
  run?.abort(); run = null;
  stopDigest();
  boardStream.set(null);
  board.set({ ...EMPTY, status: "done", historical: true, kind: saved.kind,
    reportId: saved.id, at: saved.completedAt, day: startOfDay(new Date(saved.periodEnd)).getTime(),
    periodStart: saved.periodStart, periodEnd: saved.periodEnd, expiresAt: saved.expiresAt,
    topics: saved.topics, articles: saved.articles, ids: saved.articles.map((article) => article.id),
    total: saved.total, skipped: saved.skipped || 0, cut: Boolean(saved.cut),
  });
  digest.set({ status: saved.digest ? "done" : "idle", text: saved.digest || "", error: "", boardAt: saved.completedAt });
  if (!readRun) boardReadState.set({ ...READ_EMPTY });
  return saved;
}

export async function showTodayBoard() {
  selection += 1;
  if (board.get().historical) {
    stopDigest();
    board.set(todaySnapshot || { ...EMPTY });
    const saved = todayDigest;
    digest.set(saved && saved.status !== "streaming" ? saved : { status: "idle", text: "", error: "", boardAt: null });
    if (!readRun) boardReadState.set({ ...READ_EMPTY });
  }
  const state = board.get();
  const stale = state.day !== startOfDay().getTime() || state.filter !== articleFilter();
  if (!run && (state.status === "idle" || state.status === "error" || stale)) return refreshBoard();
  catchUp();
}

export async function openBoard(open = !boardOpen.get()) {
  boardOpen.set(open);
  // Closing only acknowledges the current view. Background interval work
  // owns its own queue and never changes this selection.
  if (!open) {
    selection += 1;
    if (board.get().newCount) board.set({ ...board.get(), newCount: 0, freshIds: [] });
    return;
  }
  board.set({ ...board.get(), unseen: false });
  if (board.get().historical) return "reports";
  if (board.get().status === "idle" && !run) {
    const token = ++selection;
    const account = accountKey();
    try { await initializeBoardHistory(); } catch { /* history shows its own storage error */ }
    if (!boardOpen.get() || selection !== token || accountKey() !== account) return;
    const history = reportHistory.get();
    const latest = history.reports[0];
    if (latest) {
      const expected = selection + 1;
      try { return await showBoardReport(latest.id) ? "reports" : undefined; }
      catch { /* an expired report can disappear during opening */ }
      if (!boardOpen.get() || accountKey() !== account || selection !== expected) return;
    }
    // Opening to check an automatic report's progress must not launch a
    // second analysis of today's articles before that report is ready.
    if (history.job || (history.pendingCount > 150 && settingsState.get().autoBoardEnabled !== false && aiReady())) return "reports";
  }
  return showTodayBoard();
}

// A successful sync only updates the manual today's-view hint. Automatic
// generation belongs exclusively to the interval report manager (>150).
let hintRun = null;
function catchUp() {
  if (!unsynced || run || settle || hintRun || !authState.get().userId) return;
  const state = board.get();
  if (state.status === "idle" || state.historical) return;
  const account = accountKey();
  unsynced = false;
  const pending = todaysArticles().then((articles) => {
    if (accountKey() !== account || board.get().at !== state.at || board.get().historical) return;
    const known = new Set(state.ids);
    board.set({ ...board.get(), newAvailableCount: articles.filter((article) => !known.has(article.id)).length });
    todaySnapshot = board.get();
  }).catch(() => { /* the next successful sync or manual refresh reads again */ }).finally(() => {
    if (hintRun === pending) hintRun = null;
    if (unsynced) catchUp();
  });
  hintRun = pending;
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
  const account = accountKey();
  const operation = {};
  readRun = operation;
  const live = () => readRun === operation && accountKey() === account;
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
  if (runtimeClosing.get()) return;
  const state = board.get();
  const current = digest.get();
  if (state.status !== "done") return;
  if (!again && current.boardAt === state.at && current.status !== "error") return;
  digestRun?.abort();
  const controller = new AbortController();
  digestRun = controller;
  const account = accountKey();
  const live = () => digestRun === controller && !controller.signal.aborted && accountKey() === account
    && board.get().articles === state.articles && board.get().at === state.at;
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
    if (!state.historical) todayDigest = digest.get();
    const initialSave = snapshotSave?.at === state.at ? snapshotSave.promise : null;
    // Once the text is complete, saving belongs to this report rather than
    // the visible tab or its stream controller. Closing or switching views
    // cannot discard it while the initial snapshot is still being written.
    const saving = trackReportWrite((async () => {
      await initialSave;
      if (accountKey() === account && state.reportId) await saveBoardDigest(state.reportId, text);
    })());
    try {
      await saving;
    }
    catch (error) {
      if (live()) digest.set({ ...digest.get(), error: `文章已生成，历史保存失败：${error.message || "请重试"}` });
    }
  } catch (error) {
    if (!live()) return;
    digest.set({ ...digest.get(), status: "error", error: error?.message || "生成文章失败。" });
  } finally {
    if (digestRun === controller) digestRun = null;
  }
}

// The runtime can close while a manual view is generating or persisting.
// Stop streams first, then drain already-started report writes so the host's
// flush acknowledgement never precedes these writes.
export async function flushBoardWork() {
  selection += 1;
  run?.abort(); run = null;
  stopDigest();
  boardStream.set(null);
  const state = board.get();
  if (state.status === "loading") board.set({ ...state, status: "error", error: "今日总览生成已暂停，请重新生成。" });
  else if (state.updating) board.set({ ...state, updating: false, error: "今日总览更新已暂停，请重试。" });
  if (digest.get().status === "streaming") digest.set({ ...digest.get(), status: "error", error: "文章生成已暂停，请重试。" });
  const results = await Promise.allSettled([...reportWrites]);
  const failed = results.find((result) => result.status === "rejected");
  if (failed) throw failed.reason;
}

function reset() {
  run?.abort();
  run = null;
  stopDigest();
  board.set({ ...EMPTY });
  boardStream.set(null);
  digest.set({ status: "idle", text: "", error: "", boardAt: null });
  readRun = null;
  selection += 1;
  todaySnapshot = null; todayDigest = null;
  snapshotSave = null;
  unsynced = false;
  clearTimeout(settle); settle = null;
  boardReadState.set({ ...READ_EMPTY });
  boardOpen.set(false);
}

// Another account (or none) must never see this account's board.
let account = accountKey();
authState.listen(() => {
  if (accountKey() === account) return;
  account = accountKey();
  reset();
});

// Only a committed cache snapshot can introduce new articles. A failed
// request or read-status refresh must not trigger either board generation.
syncCommitted.listen((event) => {
  if (event?.outcome !== "committed" || !authState.get().userId) return;
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
    if (state.status === "idle" || state.historical || !authState.get().userId) return;
    const made = run ? runFilter : state.filter;
    // An open board follows at once; a closed one waits to be opened.
    if ((made !== articleFilter() || state.day !== startOfDay().getTime()) && boardOpen.get()) refreshBoard();
    // Back to the settings the board was made under: only what a sync
    // meanwhile brought in, after the board being made, if any.
    else catchUp();
  }, SETTLE_MS);
});
