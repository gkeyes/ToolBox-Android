import { readFileSync } from "node:fs";
import test from "node:test";
import assert from "node:assert/strict";
import { register } from "node:module";

// boardStore with its app modules replaced by small stubs driven from here
// (storage, settings, account, sync state and the AI stream).
const stubs = {
  "@/db/storage.js": `export const getFeeds = async () => [{ id: 1, title: "源" }];
    export const getCachedArticleMetadata = async () => globalThis.__board.rows();`,
  "@/stores/settingsStore.js": `import { atom } from "nanostores"; export const settingsState = atom({ aiApiKey: "k" });`,
  "@/stores/authStore.js": `import { atom } from "nanostores"; export const authState = atom({ userId: 7 });`,
  "@/stores/syncStore.js": `import { atom } from "nanostores"; export const syncCommitted = atom(null); globalThis.__board.syncCommitted = syncCommitted;`,
  "@/stores/articlesStore.js": `export const markArticlesAsRead = (ids) => globalThis.__board.markRead(ids);`,
  "@/api/openai.js": `export const streamChat = (messages, options) => globalThis.__board.stream(messages, options);`,
  "./historyStore.js": `import { atom } from "nanostores";
    export const reportHistory = atom({reports: []});
    export const initializeBoardHistory = async () => null;
    export const readBoardReport = (id) => globalThis.__board.readReport(id);
    export const saveBoardSnapshot = (state, text) => globalThis.__board.saveSnapshot(state, text);
    export const saveBoardDigest = (id, text) => globalThis.__board.saveDigest(id, text);`,
};
const hooks = `
const stubs = ${JSON.stringify(stubs)};
export async function resolve(specifier, context, next) {
  if (specifier in stubs) return { url: "data:text/javascript," + encodeURIComponent(stubs[specifier]), shortCircuit: true };
  if (specifier === "nanostores" && context.parentURL?.startsWith("data:")) return next(specifier, { ...context, parentURL: ${JSON.stringify(import.meta.url)} });
  if (specifier === "./board.mjs") return next(specifier, context);
  return next(specifier, context);
}`;
register("data:text/javascript," + encodeURIComponent(hooks));

const now = new Date();
let rows = [];
const article = (id) => ({ id, feedId: 1, status: "unread", title: `文章 ${id}`, published_at: now.toISOString(), created_at: now.toISOString() });
const calls = [];
let next = null;
globalThis.localStorage = { getItem: () => "1", setItem() {} };
globalThis.__board = {
  // onRead, when set, runs right after the articles are read.
  // onRead, when set, runs right after the articles are read; readDelay
  // (ms), when set, holds the read back that long. reads counts them.
  reports: new Map(),
  readReport: async (id) => globalThis.__board.reports.get(id),
  saveSnapshot: async (state, text) => {
    const report = { ...state, id: state.reportId, completedAt: state.at, expiresAt: state.at + 172800000, digest: text };
    globalThis.__board.reports.set(report.id, report);
    return report;
  },
  saveDigest: async (id, text) => {
    const report = globalThis.__board.reports.get(id);
    if (report) globalThis.__board.reports.set(id, { ...report, digest: text });
  },
  reads: 0,
  readDelay: 0,
  rows: () => {
    globalThis.__board.reads++;
    const read = rows; const after = globalThis.__board.onRead; globalThis.__board.onRead = null; after?.();
    const delay = globalThis.__board.readDelay;
    return delay ? new Promise((resolve) => setTimeout(() => resolve(read), delay)) : read;
  },
  // Each AI call waits for the test to answer it.
  stream: (messages, { onChunk, onThink, signal }) => new Promise((resolve, reject) => {
    const call = { messages, kind: messages[0].content.includes("文章") && messages[1].content.startsWith("主题") ? "digest" : "topics", onChunk, onThink, resolve, reject, signal };
    signal?.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })));
    calls.push(call);
    next?.(call);
  }),
};
const waitCall = () => new Promise((resolve) => { next = (call) => { next = null; resolve(call); }; });
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
const { settingsState } = await import("@/stores/settingsStore.js");
const { authState } = await import("@/stores/authStore.js");
const { board, boardOpen, boardStream, boardReadState, digest, flushBoardWork, markBoardAsRead, openBoard, refreshBoard, showBoardReport, showTodayBoard, writeDigest } = await import("../src/toolbox/board/boardStore.js");
const { PROMPT_BUDGET, boardArticleIds, startOfDay } = await import("../src/toolbox/board/board.mjs");
const emitSync = () => globalThis.__board.syncCommitted.set({ outcome: "committed", newEntryIds: rows.map((row) => row.id), syncedAt: new Date().toISOString() });
const sync = async () => { emitSync(); await tick(); await tick(); };
const TOPICS = JSON.stringify({ topics: [{ title: "主题", gist: "重点", summary: "摘要", articles: [1, 2] }] });

test("syncs never retry a failed manual board, even when new articles arrive", async () => {
  rows = [article(3)];
  let call = waitCall();
  refreshBoard();
  (await call).reject(new Error("HTTP 500"));
  await tick(); await tick();
  assert.equal(board.get().status, "error");
  const before = calls.length;
  await sync(); await sync();
  rows = [article(3), article(4)];
  await sync();
  assert.equal(calls.length, before);
  assert.equal(board.get().newAvailableCount, 1);
  call = waitCall();
  refreshBoard();
  const retry = await call;
  retry.onChunk(TOPICS); retry.resolve();
  await tick(); await tick();
  assert.equal(board.get().status, "done");
});

test("a sync while the digest is written leaves it running and it finishes", async () => {
  rows = [article(1), article(2)];
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  assert.equal(board.get().cut, false);

  call = waitCall();
  writeDigest();
  const written = await call;
  assert.equal(written.kind, "digest");
  written.onChunk("## 主题\n第一句 [1]");
  await sync();
  assert.equal(written.signal.aborted, false, "the sync left the digest stream running");
  written.onChunk("第二句 [2]"); written.resolve();
  await tick(); await tick();
  assert.equal(digest.get().status, "done");
  assert.match(digest.get().text, /第二句/);
});

// While the board is made the page can show what was sent and what streams
// back; when the answer can't be read, the AI's own words stay on the board.
test("the live stream is published and an unreadable answer is kept", async () => {
  rows = [article(5), article(6)];
  const call = waitCall();
  refreshBoard();
  const live = await call;
  assert.deepEqual(boardStream.get().sent.map((item) => item.title).sort(), ["文章 5", "文章 6"]);
  live.onThink("先看看");
  live.onChunk("今天没有");
  await new Promise((resolve) => setTimeout(resolve, 120));
  assert.equal(boardStream.get().thinking, "先看看");
  assert.equal(boardStream.get().text, "今天没有");
  live.onChunk("主题。");
  live.resolve();
  await tick(); await tick();
  // (Today's earlier board stays on screen with the failure under it.)
  assert.equal(board.get().updating, false);
  assert.match(board.get().error, /无法识别/);
  assert.deepEqual(board.get().raw, { thinking: "先看看", text: "今天没有主题。" });
  assert.equal(boardStream.get(), null);
});

test("a reply cut off mid-way keeps what had arrived", async () => {
  rows = [article(7), article(8)];
  const call = waitCall();
  refreshBoard();
  const live = await call;
  live.onThink("想");
  live.onChunk('{"topics":[');
  live.reject(Object.assign(new Error("AI 响应意外中断，请重新生成。"), { code: "INCOMPLETE_STREAM" }));
  await tick(); await tick();
  assert.match(board.get().error, /意外中断/);
  assert.doesNotMatch(board.get().error, /摘要/);
  assert.deepEqual(board.get().raw, { thinking: "想", text: '{"topics":[' });
});

test("a reply stopped at the token limit is marked cut", async () => {
  rows = [article(7), article(8)];
  const call = waitCall();
  refreshBoard();
  const live = await call;
  live.onChunk('{"topics":[{"title":"甲","gist":"g","summary":"s","articles":[1]},{"title":"乙","articles":[2');
  live.resolve({ finishReason: "length" });
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  assert.deepEqual(board.get().topics.map((topic) => topic.title), ["甲"]);
  assert.equal(board.get().cut, true);
});

test("a whole reply that ends at the token limit is not marked cut", async () => {
  rows = [article(7), article(8)];
  const call = waitCall();
  refreshBoard();
  const live = await call;
  live.onChunk('{"topics":[{"title":"甲","gist":"g","summary":"s","articles":[1]},{"title":"乙","gist":"g","summary":"s","articles":[2]}]}');
  live.resolve({ finishReason: "length" });
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  assert.equal(board.get().topics.length, 2);
  assert.equal(board.get().cut, false);
});

test("a reply stopped at the token limit before one whole topic asks once more for a shorter one, then names the cut", async () => {
  rows = [article(7), article(8)];
  let call = waitCall();
  refreshBoard();
  const first = await call;
  assert.doesNotMatch(first.messages[0].content, /截断/);
  assert.equal(boardStream.get().first, null);
  call = waitCall();
  first.onChunk('{"topics":[{"title":"甲","articles":[1],"summary":"写到一半');
  first.resolve({ finishReason: "length" });
  const second = await call;
  assert.equal(second.kind, "topics");
  assert.match(second.messages[0].content, /被截断了。这次回答要短/);
  // The first, cut reply stays on screen above the second.
  assert.deepEqual(boardStream.get().first, { text: '{"topics":[{"title":"甲","articles":[1],"summary":"写到一半', thinking: "" });
  assert.equal(boardStream.get().text, "");
  // The shorter answer fits: a board.
  second.onChunk('{"topics":[{"title":"甲","gist":"g","summary":"s","articles":[1]}]}');
  second.resolve({ finishReason: "stop" });
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  assert.equal(board.get().cut, false);

  // Cut again on the second try: the error says why, and the AI's words stay.
  rows = [article(9), article(10)];
  call = waitCall();
  refreshBoard();
  const again = await call;
  call = waitCall();
  again.onChunk('{"topics":[{"title":"甲","articles":[1],"summary":"写');
  again.resolve({ finishReason: "length" });
  const last = await call;
  last.onChunk('{"topics":[{"title":"乙","articles":[2],"summ');
  last.resolve({ finishReason: "length" });
  await tick(); await tick();
  const before = calls.length;
  assert.match(board.get().error, /太长，在写完第一个主题之前就被截断/);
  assert.doesNotMatch(board.get().error, /无法识别/);
  // Both replies stay: the first beside the second.
  assert.deepEqual(board.get().raw, { thinking: "", text: '{"topics":[{"title":"乙","articles":[2],"summ', first: { thinking: "", text: '{"topics":[{"title":"甲","articles":[1],"summary":"写' } });
  await tick();
  assert.equal(calls.length, before, "asked again only once");
});

test("a first reply that only thought keeps its thinking on the first card", async () => {
  rows = [article(11), article(12)];
  let call = waitCall();
  refreshBoard();
  const first = await call;
  call = waitCall();
  first.onThink("想了很久");
  first.resolve({ finishReason: "length" });
  const second = await call;
  assert.deepEqual(boardStream.get().first, { text: "", thinking: "想了很久" });
  second.onChunk('{"topics":[{"title":"甲","gist":"g","summary":"s","articles":[1]}]}');
  second.resolve({ finishReason: "stop" });
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  const page = readFileSync(new URL("../src/components/Board/BoardPage.jsx", import.meta.url), "utf8");
  assert.match(page, /stream\.first\.thinking && <LiveText/);
  assert.match(page, /raw\.first && \["第一次", raw\.first\]/);
});

test("a reply cut off after whole topics still makes a board from them", async () => {
  rows = [article(7), article(8)];
  const call = waitCall();
  refreshBoard();
  const live = await call;
  live.onChunk('{"topics":[{"title":"甲","gist":"g","summary":"s","articles":[1]},{"title":"乙","articles":[2');
  live.reject(Object.assign(new Error("AI 响应意外中断，请重新生成。"), { code: "INCOMPLETE_STREAM" }));
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  assert.deepEqual(board.get().topics.map((topic) => topic.title), ["甲"]);
  assert.equal(board.get().cut, true);
});


test("changing 查重方式 makes today's board out of date", async () => {
  rows = [article(1), article(2)];
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  // Reopening under the same settings keeps the board.
  const before = calls.length;
  openBoard(true); openBoard(false);
  await tick();
  assert.equal(calls.length, before, "same settings: no new board");
  settingsState.set({ ...settingsState.get(), duplicateMatch: "url" });
  call = waitCall();
  openBoard(true);
  const again = await call;
  assert.equal(again.kind, "topics", "a new board is made for the new match mode");
  again.onChunk(TOPICS); again.resolve();
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  openBoard(false);
  assert.equal(boardOpen.get(), false);
});

test("the open board follows 查重方式 once it settles; a closed one waits to be opened, and a change undone costs nothing", async () => {
  rows = [article(1), article(2)];
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  // Open: a new board right away.
  openBoard(true);
  call = waitCall();
  settingsState.set({ ...settingsState.get(), duplicateMatch: "title" });
  const now = await call;
  assert.equal(now.kind, "topics");
  now.onChunk(TOPICS); now.resolve();
  await tick(); await tick();
  openBoard(false);
  // Closed: changed, synced and changed back, no AI call.
  const before = calls.length;
  settingsState.set({ ...settingsState.get(), duplicateMatch: "url" });
  await sync();
  settingsState.set({ ...settingsState.get(), duplicateMatch: "title" });
  openBoard(true);
  await tick(); await tick();
  assert.equal(calls.length, before, "no AI call for a change undone");
  openBoard(false);
  // Other settings changing leave the board alone.
  settingsState.set({ ...settingsState.get(), aiApiKey: "k2" });
  await tick();
  assert.equal(calls.length, before);
});

test("flipping 查重方式 on the open board asks the AI once, and flipping back asks nothing", async () => {
  const settled = () => new Promise((resolve) => setTimeout(resolve, 700));
  rows = [article(1), article(2)];
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  await settled();
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  openBoard(true);
  // Through all three options quickly: one call, for the last.
  let before = calls.length;
  for (const match of ["title", "url", "title"]) settingsState.set({ ...settingsState.get(), duplicateMatch: match });
  call = waitCall();
  await settled();
  const one = await call;
  assert.equal(calls.length, before + 1, "one AI call for a quick run of changes");
  // Changed back while that board is still being made: no second call.
  settingsState.set({ ...settingsState.get(), duplicateMatch: "url" });
  settingsState.set({ ...settingsState.get(), duplicateMatch: "title" });
  await settled();
  assert.equal(calls.length, before + 1, "changed and changed back: nothing more");
  assert.equal(one.signal.aborted, false);
  assert.equal(board.get().filter, "false|true|title");
  one.onChunk(TOPICS); one.resolve();
  await tick(); await tick();
  assert.equal(board.get().status, "done");
  // Changed and changed back once the board is made: nothing.
  before = calls.length;
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  settingsState.set({ ...settingsState.get(), duplicateMatch: "title" });
  await settled();
  assert.equal(calls.length, before);
  openBoard(false);
});

test("a sync while settings settle only updates the new-article hint when the filter returns", async () => {
  const settled = () => new Promise((resolve) => setTimeout(resolve, 700));
  rows = [article(1), article(2)];
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  await settled();
  let call = waitCall(); refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick(); openBoard(true);
  const before = calls.length;
  settingsState.set({ ...settingsState.get(), duplicateMatch: "url" });
  rows = [article(1), article(2), article(3)];
  await sync();
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  await settled();
  assert.equal(calls.length, before);
  assert.equal(board.get().newAvailableCount, 1);
  assert.ok(!board.get().ids.includes(3));
  call = waitCall(); refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  assert.equal(board.get().newCount, 1);
  assert.equal(board.get().newAvailableCount, 0);
  openBoard(false);
});

test("a board from yesterday is made again even when the settings end where they began", async () => {
  const settled = () => new Promise((resolve) => setTimeout(resolve, 700));
  rows = [article(1), article(2)];
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  board.set({ ...board.get(), day: board.get().day - 86400000 });
  call = waitCall();
  openBoard(true);
  // (Opening a stale board makes it again; answer that first.)
  const reopened = await call;
  reopened.onChunk(TOPICS); reopened.resolve();
  await tick(); await tick();
  board.set({ ...board.get(), day: board.get().day - 86400000 });
  const before = calls.length;
  settingsState.set({ ...settingsState.get(), duplicateMatch: "title" });
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  call = waitCall();
  await settled();
  const again = await call;
  assert.equal(calls.length, before + 1);
  again.onChunk(TOPICS); again.resolve();
  await tick(); await tick();
  openBoard(false);
});

test("syncs while the page closes or a manual board runs preserve the snapshot and update its hint", async () => {
  rows = [article(1), article(2)];
  let call = waitCall(); refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  openBoard(false);
  const before = calls.length;
  rows = [article(1), article(2), article(3)];
  await sync();
  assert.equal(calls.length, before);
  assert.equal(board.get().newAvailableCount, 1);
  call = waitCall(); refreshBoard();
  const running = await call;
  rows = [...rows, article(4)];
  await sync();
  running.onChunk(TOPICS); running.resolve();
  await tick(); await tick();
  assert.equal(calls.length, before + 1);
  assert.ok(board.get().ids.includes(3));
  assert.ok(!board.get().ids.includes(4));
  assert.equal(board.get().newAvailableCount, 1);
});

test("failed and status-only sync events cannot replace a manual board", async () => {
  rows = [article(1), article(2)];
  let call = waitCall(); refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  const before = calls.length;
  const snapshot = board.get().articles;
  rows = [...rows, article(5)];
  globalThis.__board.syncCommitted.set({ outcome: "failed" });
  await tick();
  assert.equal(board.get().newAvailableCount, 0);
  await sync(); await sync();
  assert.equal(calls.length, before);
  assert.equal(board.get().articles, snapshot);
  assert.equal(board.get().newAvailableCount, 1);
});

test("a sync landing while a no-op manual refresh reads is reflected without starting an AI request", async () => {
  rows = [article(1), article(2)];
  let call = waitCall(); refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  const before = calls.length;
  globalThis.__board.onRead = () => { rows = [...rows, article(7)]; emitSync(); };
  await refreshBoard({ onlyIfNew: true });
  await tick(); await tick();
  assert.equal(calls.length, before);
  assert.ok(!board.get().ids.includes(7));
  assert.equal(board.get().newAvailableCount, 1);
});

test("settings changed back while a slow read builds the board under others: the board is made again under the ones in place", async () => {
  const settled = () => new Promise((resolve) => setTimeout(resolve, 700));
  rows = [article(1), article(2)];
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  await settled();
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  openBoard(true);
  // Changed: the board is made again, but reading today's articles takes
  // longer than the settle wait, so the board on screen still shows the
  // old settings when they are changed back.
  globalThis.__board.readDelay = 1500;
  settingsState.set({ ...settingsState.get(), duplicateMatch: "url" });
  await settled();
  assert.equal(board.get().filter.endsWith("|either"), true, "still the old board while the read runs");
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  globalThis.__board.readDelay = 0;
  call = waitCall();
  await settled();
  const again = await call;
  again.onChunk(TOPICS); again.resolve();
  await new Promise((resolve) => setTimeout(resolve, 1000));
  assert.ok(board.get().filter.endsWith("|either"), "the board follows the settings in place, not the run's");
  assert.equal(board.get().status, "done");
  openBoard(false);
});

test("the new-article hint works with no AI key and reopening never consumes it automatically", async () => {
  rows = [article(1), article(2)];
  let call = waitCall(); refreshBoard();
  const running = await call;
  rows = [...rows, article(8)]; await sync();
  const key = settingsState.get().aiApiKey;
  settingsState.set({ ...settingsState.get(), aiApiKey: "" });
  const before = calls.length;
  running.onChunk(TOPICS); running.resolve();
  await tick(); await tick();
  assert.equal(board.get().newAvailableCount, 1);
  settingsState.set({ ...settingsState.get(), aiApiKey: key });
  await openBoard(true);
  assert.equal(calls.length, before);
  assert.ok(!board.get().ids.includes(8));
  openBoard(false);
});

test("an empty board keeps the settings it was made under, so opening it again reads nothing", async () => {
  rows = [];
  refreshBoard();
  await tick(); await tick();
  assert.equal(board.get().status, "empty");
  const reads = globalThis.__board.reads;
  openBoard(true);
  await tick(); await tick();
  assert.equal(globalThis.__board.reads, reads, "not made again");
  openBoard(false);
});

test("a sync during the first snapshot read waits for the next manual update", async () => {
  board.set({ ...board.get(), status: "idle", ids: [], filter: null, day: null });
  rows = [article(1), article(2)];
  globalThis.__board.onRead = () => { rows = [...rows, article(9)]; emitSync(); };
  const before = calls.length;
  const call = waitCall(); refreshBoard();
  const first = await call;
  first.onChunk(TOPICS); first.resolve();
  await tick(); await tick();
  assert.equal(calls.length, before + 1);
  assert.ok(!board.get().ids.includes(9));
  assert.equal(board.get().newAvailableCount, 1);
  const css = readFileSync(new URL("../src/design/ios27.css", import.meta.url), "utf8");
  assert.match(css, /\.article-list-title > \.article-list-caption \{ max-inline-size: 100%; \}/);
});

function boardFixture(t, count, reply, { contextWindow, previewRepeat = 1 } = {}) {
  const previousStream = globalThis.__board.stream;
  const previousMark = globalThis.__board.markRead;
  const previousSettings = settingsState.get();
  if (contextWindow) settingsState.set({ ...previousSettings, aiContextWindow: contextWindow });
  authState.set({ userId: authState.get().userId + 1 });
  rows = Array.from({ length: count }, (_, index) => ({
    ...article(index + 1), previewText: `保存的原文 ${index + 1}，用于看板与摘要。`.repeat(previewRepeat),
  }));
  globalThis.__board.stream = reply;
  globalThis.__board.markRead = async (ids) => {
    let count = 0;
    rows = rows.map((row) => {
      if (!ids.includes(row.id) || row.status !== "unread") return row;
      count += 1;
      return { ...row, status: "read" };
    });
    return count;
  };
  t.after(() => {
    globalThis.__board.stream = previousStream;
    globalThis.__board.markRead = previousMark;
    if (contextWindow) settingsState.set(previousSettings);
  });
}

function answerTopics(options, articles, { title = "主题", summary = "摘要" } = {}) {
  options.onChunk(JSON.stringify({ topics: [{ title, gist: "重点", summary, articles }] }));
  return { finishReason: "stop" };
}

test("a 1M daily overview analyzes 1000 sources in one request and capacity changes do not regenerate it", async (t) => {
  let requests = 0;
  boardFixture(t, 1000, async (messages, options) => {
    requests += 1;
    assert.equal(boardStream.get().stage, "articles");
    assert.equal(boardStream.get().batchCount, 1);
    const numbers = [...messages[1].content.matchAll(/^\[(\d+)\] /gm)].map((match) => Number(match[1]));
    assert.equal(numbers.length, 1000);
    return answerTopics(options, [numbers.at(-1)]);
  }, { contextWindow: "1000000", previewRepeat: 8 });
  await refreshBoard();
  assert.equal(board.get().status, "done", board.get().error);
  assert.equal(board.get().total, 1000);
  assert.equal(requests, 1);
  assert.deepEqual(boardArticleIds(board.get().topics, board.get().articles), [1]);
  const at = board.get().at;
  settingsState.set({ ...settingsState.get(), aiContextWindow: "32000" });
  await openBoard();
  await tick();
  assert.equal(board.get().at, at);
  assert.equal(requests, 1, "a context preference is not a request to regenerate today's saved result");
});

test("saving a different AI service during analysis leaves every remaining batch on its captured configuration", async (t) => {
  let requests = 0;
  let captured;
  boardFixture(t, 241, async (_messages, options) => {
    captured ??= options.configuration;
    assert.deepEqual(options.configuration, captured);
    if (++requests === 1) settingsState.set({ ...settingsState.get(), aiModel: "new-model", aiBaseUrl: "https://new.example/v1", aiApiKey: "new-key", aiContextWindow: "1000000" });
    return answerTopics(options, [1]);
  }, { contextWindow: "32000", previewRepeat: 4 });
  await refreshBoard();
  assert.equal(board.get().status, "done", board.get().error);
  assert.ok(requests >= 3, "both analysis batches and the merge use the same provider");
  assert.equal(captured.aiContextWindow, "32000");
  assert.notEqual(captured.aiApiKey, "new-key");
});

test("every article on a 1201-article day is analyzed, including through multiple bounded merge levels", async (t) => {
  const sent = [];
  const expectedSources = new Set();
  const rounds = new Set();
  boardFixture(t, 1201, async (messages, options) => {
    assert.ok(messages.reduce((total, message) => total + message.content.length, 0) <= PROMPT_BUDGET);
    const progress = boardStream.get();
    if (progress.stage === "articles") {
      assert.equal(progress.total, 1201);
      assert.equal(progress.processed, sent.length);
      const ids = [...messages[1].content.matchAll(/^\[\d+\] 源 · 文章 (\d+)$/gm)].map((match) => Number(match[1]));
      assert.equal(ids.length, progress.sent.length);
      sent.push(...ids);
      const refs = [...new Set([1, 2, 3, 4, 5, 6, ids.length].filter((n) => n <= ids.length))];
      refs.forEach((number) => expectedSources.add(ids[number - 1]));
      options.onChunk(JSON.stringify({ topics: refs.map((number, index) => ({
        title: `候选 ${progress.batchIndex}-${index}`, gist: "要点".repeat(40),
        summary: "摘要".repeat(200), articles: [number],
      })) }));
      return { finishReason: "stop" };
    }
    assert.equal(progress.stage, "merge");
    assert.equal(progress.processed, 1201);
    rounds.add(progress.round);
    return answerTopics(options, progress.sent.map((_, index) => index + 1));
  }, { contextWindow: "32000", previewRepeat: 4 });
  await refreshBoard();
  assert.equal(board.get().status, "done", board.get().error);
  assert.equal(board.get().total, 1201);
  assert.equal(board.get().skipped, 0);
  assert.equal(board.get().articles.length, 1201);
  assert.deepEqual(sent, Array.from({ length: 1201 }, (_, index) => 1201 - index));
  assert.ok(rounds.size >= 2, "large candidate summaries also require more than one merge level");
  assert.deepEqual(new Set(boardArticleIds(board.get().topics, board.get().articles)), expectedSources);
  assert.ok(expectedSources.has(1), "the day's last article survives all reference mappings");
  assert.equal(boardStream.get(), null);
});

test("a failed later batch keeps the previous board without claiming partial input was a complete day", async (t) => {
  boardFixture(t, 2, async (_messages, options) => answerTopics(options, [1]), { contextWindow: "32000" });
  await refreshBoard();
  const previous = board.get();
  rows = Array.from({ length: 320 }, (_, index) => ({ ...article(index + 1), previewText: "足够长的原文预览".repeat(40) }));
  const stages = [];
  globalThis.__board.stream = async (_messages, options) => {
    const progress = boardStream.get();
    stages.push(`${progress.stage}:${progress.batchIndex}`);
    if (progress.batchIndex === 2) throw new Error("第二批连接失败");
    return answerTopics(options, [1]);
  };
  await refreshBoard();
  assert.deepEqual(stages, ["articles:1", "articles:2"]);
  assert.equal(board.get().at, previous.at);
  assert.equal(board.get().articles, previous.articles);
  assert.equal(board.get().total, 2);
  assert.equal(board.get().updating, false);
  assert.match(board.get().error, /第二批连接失败/);
  assert.equal(boardStream.get(), null);
});

test("a later batch gets its own one-time short retry without losing the day's tail", async (t) => {
  let retried = false;
  let retryCount = 0;
  boardFixture(t, 241, async (messages, options) => {
    const progress = boardStream.get();
    assert.ok(messages.reduce((total, message) => total + message.content.length, 0) <= PROMPT_BUDGET);
    if (progress.stage === "articles" && progress.batchIndex === 2 && !retried) {
      retried = true;
      options.onChunk('{"topics":[{"title":"未写完');
      return { finishReason: "length" };
    }
    if (progress.first) {
      retryCount += 1;
      assert.equal(progress.batchIndex, 2);
      assert.match(messages[0].content, /这次回答要短/);
    }
    return answerTopics(options, [progress.sent.length]);
  }, { contextWindow: "32000", previewRepeat: 4 });
  await refreshBoard();
  assert.equal(board.get().status, "done", board.get().error);
  assert.equal(retryCount, 1);
  assert.deepEqual(boardArticleIds(board.get().topics, board.get().articles), [1]);
  assert.equal(board.get().cut, false);
});

test("changing accounts during a later batch cancels publication and never starts subsequent batches", async (t) => {
  let release;
  let entered;
  const pending = new Promise((resolve) => { entered = resolve; });
  let requestCount = 0;
  boardFixture(t, 450, async (_messages, options) => {
    requestCount += 1;
    if (boardStream.get().batchIndex === 2) {
      entered();
      await new Promise((resolve) => { release = resolve; });
    }
    return answerTopics(options, [1]);
  }, { contextWindow: "32000", previewRepeat: 4 });
  const running = refreshBoard();
  await pending;
  authState.set({ userId: authState.get().userId + 1 });
  release();
  await running;
  assert.equal(requestCount, 2);
  assert.equal(board.get().status, "idle");
  assert.equal(boardStream.get(), null);
});

test("one-click read uses only final topic sources and the later digest keeps their original excerpts and numbering", async (t) => {
  boardFixture(t, 4, async (_messages, options) => answerTopics(options, [1, 3]));
  await refreshBoard();
  const requested = [];
  const apply = globalThis.__board.markRead;
  globalThis.__board.markRead = (ids) => { requested.push(ids); return apply(ids); };
  assert.deepEqual(board.get().ids, [4, 3, 2, 1], "all candidates are tracked separately");
  assert.equal(await markBoardAsRead(), 2);
  assert.deepEqual(requested, [[4, 2]]);
  assert.deepEqual(rows.filter((row) => row.status === "unread").map((row) => row.id), [1, 3]);
  assert.equal(boardReadState.get().status, "done");
  assert.equal(boardReadState.get().count, 2);
  const reads = globalThis.__board.reads;
  globalThis.__board.stream = async (messages, { onChunk }) => {
    assert.match(messages[1].content, /\[1\] 源 · 文章 4\n保存的原文 4/);
    assert.match(messages[1].content, /\[3\] 源 · 文章 2\n保存的原文 2/);
    assert.doesNotMatch(messages[1].content, /保存的原文 [13]，/);
    onChunk("## 主题\n来自保存的快照 [1][3]");
  };
  await writeDigest();
  assert.equal(digest.get().status, "done", digest.get().error);
  assert.equal(globalThis.__board.reads, reads, "a digest does not re-read only the now-unread rows");
  assert.equal(await markBoardAsRead(), 0, "repeat actions are harmless");
  assert.equal(boardReadState.get().status, "done");
  assert.deepEqual(rows.filter((row) => row.status === "unread").map((row) => row.id), [1, 3]);
});

test("read actions coalesce repeated clicks and stay scoped to the board at the click during regeneration", async (t) => {
  boardFixture(t, 4, async (_messages, options) => answerTopics(options, [1]));
  await refreshBoard();
  const at = board.get().at;
  let release;
  const requested = [];
  globalThis.__board.markRead = (ids) => {
    requested.push(ids);
    return new Promise((resolve) => { release = resolve; });
  };
  const first = markBoardAsRead();
  assert.equal(markBoardAsRead(), first);
  assert.equal(boardReadState.get().status, "saving");
  rows = [article(20)];
  await refreshBoard();
  assert.notEqual(board.get().at, at);
  assert.equal(boardReadState.get().status, "saving");
  assert.deepEqual(requested, [[4]]);
  release(1);
  assert.equal(await first, 1);
  assert.equal(boardReadState.get().status, "idle", "old completion cannot claim the new board is read");
  assert.deepEqual(boardArticleIds(board.get().topics, board.get().articles), [20]);
});

test("read errors report confirmed progress and account changes suppress late action results", async (t) => {
  boardFixture(t, 4, async (_messages, options) => answerTopics(options, [1, 2, 3]));
  await refreshBoard();
  globalThis.__board.markRead = async () => { throw Object.assign(new Error("还有一批未完成"), { markedCount: 2 }); };
  assert.equal(await markBoardAsRead(), 2);
  assert.equal(boardReadState.get().status, "error");
  assert.equal(boardReadState.get().count, 2);
  assert.match(boardReadState.get().error, /还有一批/);
  let release;
  globalThis.__board.markRead = () => new Promise((resolve) => { release = resolve; });
  const pending = markBoardAsRead();
  authState.set({ userId: authState.get().userId + 1 });
  release(3);
  await pending;
  assert.equal(boardReadState.get().status, "idle");
  assert.equal(boardReadState.get().count, 0);
  assert.equal(board.get().status, "idle");
});

test("opening saved reports reuses frozen topics and digest while syncs and filters leave the selection alone", async (t) => {
  boardFixture(t, 4, async (_messages, options) => answerTopics(options, [1, 3]));
  await refreshBoard();
  const today = board.get();
  const report = {
    id: "interval-saved", kind: "interval", completedAt: Date.now() - 3600000,
    periodStart: Date.now() - 7200000, periodEnd: Date.now() - 3700000,
    expiresAt: Date.now() + 3600000, total: 4,
    articles: today.articles, topics: today.topics, digest: "## 已保存的报告\n依据 [1][3]",
  };
  globalThis.__board.reports.set(report.id, report);
  let generated = 0;
  globalThis.__board.stream = async () => { generated++; throw new Error("should not call AI"); };
  await showBoardReport(report.id);
  assert.equal(board.get().historical, true);
  assert.equal(board.get().reportId, report.id);
  assert.equal(digest.get().text, report.digest);
  const marked = await markBoardAsRead();
  assert.equal(marked, 2);
  assert.equal(rows.filter((row) => row.status === "unread").length, 2);
  await openBoard(true);
  rows = [...rows, article(5)]; await sync();
  const originalSettings = settingsState.get();
  settingsState.set({ ...originalSettings, duplicateMatch: "url" });
  await new Promise((resolve) => setTimeout(resolve, 650));
  assert.equal(board.get().reportId, report.id);
  assert.equal(board.get().total, 4);
  assert.equal(generated, 0);
  settingsState.set(originalSettings);
  await showTodayBoard();
  assert.equal(board.get().historical, false);
  assert.equal(board.get().at, today.at);
  assert.equal(generated, 0);
  openBoard(false);
});

test("the first opening selects saved history without another AI request", async (t) => {
  boardFixture(t, 2, async () => { throw new Error("must use saved history"); });
  const { reportHistory } = await import("./historyStore.js");
  const report = {
    id: "last-report", kind: "interval", completedAt: Date.now() - 10000,
    periodStart: Date.now() - 20000, periodEnd: Date.now() - 10000,
    expiresAt: Date.now() + 3600000, total: 1,
    articles: [{ id: 1, title: "保存的来源", number: 1 }],
    topics: [{ title: "保存的主题", gist: "重点", summary: "摘要", articles: [1] }], digest: "保存的正文 [1]",
  };
  globalThis.__board.reports.set(report.id, report);
  reportHistory.set({ reports: [report] });
  t.after(() => reportHistory.set({ reports: [] }));
  await openBoard(true);
  assert.equal(board.get().reportId, report.id);
  assert.equal(digest.get().status, "done");
  assert.equal(digest.get().text, report.digest);
  openBoard(false);
});

test("late history loads cannot replace a newer selection or a different account on the same user ID", async (t) => {
  boardFixture(t, 2, async (_messages, options) => answerTopics(options, [1]));
  await refreshBoard();
  const today = board.get();
  const saved = { ...today, id: "delayed", completedAt: today.at, expiresAt: Date.now() + 3600000 };
  const original = globalThis.__board.readReport;
  let release;
  globalThis.__board.readReport = () => new Promise((resolve) => { release = resolve; });
  t.after(() => { globalThis.__board.readReport = original; });
  let loading = showBoardReport(saved.id);
  await showTodayBoard();
  release(saved); await loading;
  assert.equal(board.get().historical, false);
  loading = showBoardReport(saved.id);
  authState.set({ ...authState.get(), serverUrl: "https://another.example" });
  release(saved); await loading;
  assert.equal(board.get().status, "idle");
  assert.equal(digest.get().text, "");
});

test("history persistence failure keeps a completed today board usable", async (t) => {
  boardFixture(t, 2, async (_messages, options) => answerTopics(options, [1]));
  const save = globalThis.__board.saveSnapshot;
  globalThis.__board.saveSnapshot = async () => { throw new Error("quota"); };
  t.after(() => { globalThis.__board.saveSnapshot = save; });
  await refreshBoard();
  assert.equal(board.get().status, "done");
  assert.match(board.get().error, /历史保存失败.*quota/);
  assert.equal(board.get().total, 2);
  assert.equal(await markBoardAsRead(), 1);
});

test("digest persistence waits for the initial report snapshot and keeps its original completion time", async (t) => {
  boardFixture(t, 2, async (messages, options) => {
    if (messages[1].content.startsWith("主题")) { options.onChunk("保存的完整文章 [1]"); return; }
    return answerTopics(options, [1]);
  });
  const save = globalThis.__board.saveSnapshot;
  let release;
  globalThis.__board.saveSnapshot = (state, text) => new Promise((resolve) => {
    release = async () => resolve(await save(state, text));
  });
  t.after(() => { globalThis.__board.saveSnapshot = save; });
  const generating = refreshBoard();
  while (!release) await tick();
  const state = board.get();
  const writing = writeDigest();
  await tick();
  assert.equal(digest.get().status, "done");
  assert.equal(globalThis.__board.reports.has(state.reportId), false);
  let flushed = false;
  const flushing = flushBoardWork().then(() => { flushed = true; });
  await tick(); assert.equal(flushed, false);
  await release(); await generating; await writing; await flushing;
  const report = globalThis.__board.reports.get(state.reportId);
  assert.equal(report.digest, "保存的完整文章 [1]");
  assert.equal(report.completedAt, state.at);
  assert.equal(report.expiresAt, state.at + 172800000);
});

test("opening while an automatic report runs shows its progress without starting today's AI", async (t) => {
  let requested = 0;
  boardFixture(t, 151, async () => { requested++; throw new Error("must use running report"); });
  const { reportHistory } = await import("./historyStore.js");
  reportHistory.set({ reports: [], pendingCount: 0, job: { id: "running-report", status: "running" } });
  t.after(() => reportHistory.set({ reports: [] }));
  assert.equal(await openBoard(true), "reports");
  assert.equal(requested, 0);
  assert.equal(board.get().status, "idle");
  openBoard(false);
});

test("a failed today board stays failed across navigation until an explicit retry", async (t) => {
  let requested = 0;
  boardFixture(t, 2, async () => { requested++; throw new Error("连接中断"); });
  await refreshBoard();
  assert.equal(board.get().status, "error");
  await openBoard(true);
  await showTodayBoard();
  openBoard(false);
  await openBoard(true);
  rows.push(article(3)); await sync();
  assert.equal(requested, 1);
  assert.equal(board.get().status, "error");
  assert.match(board.get().error, /连接中断/);
  globalThis.__board.stream = async (_messages, options) => {
    requested++;
    return answerTopics(options, [1]);
  };
  await refreshBoard();
  assert.equal(requested, 2, "only the explicit retry calls the provider again");
  assert.equal(board.get().status, "done");
  openBoard(false);
});

test("rapid refreshes during cache reads and generation share the same today operation", async (t) => {
  let releaseRead, releaseAI, readEntered, aiEntered;
  const reading = new Promise((resolve) => { readEntered = resolve; });
  const generating = new Promise((resolve) => { aiEntered = resolve; });
  const readGate = new Promise((resolve) => { releaseRead = resolve; });
  const aiGate = new Promise((resolve) => { releaseAI = resolve; });
  let requested = 0;
  let signal;
  boardFixture(t, 2, async (_messages, options) => {
    requested++; signal = options.signal; aiEntered();
    await aiGate;
    return answerTopics(options, [1]);
  });
  const read = globalThis.__board.rows;
  globalThis.__board.rows = async () => {
    const snapshot = read(); readEntered(); await readGate; return snapshot;
  };
  t.after(() => { globalThis.__board.rows = read; releaseRead(); releaseAI(); });
  const first = refreshBoard();
  await reading;
  assert.equal(board.get().status, "idle", "the loading UI has not yet disabled refresh");
  assert.equal(refreshBoard(), first);
  await openBoard(true);
  releaseRead();
  await generating;
  assert.equal(refreshBoard(), first);
  assert.equal(signal.aborted, false);
  assert.equal(requested, 1);
  releaseAI(); await first;
  assert.equal(board.get().status, "done");
  openBoard(false);
});

for (const returnWhileRunning of [true, false]) {
  test(`today generation survives history navigation when returning ${returnWhileRunning ? "during" : "after"} the request`, async (t) => {
    let releaseToday, releaseDigest, todayStarted, digestStarted;
    const ready = new Promise((resolve) => { todayStarted = resolve; });
    const writingReady = new Promise((resolve) => { digestStarted = resolve; });
    const todayGate = new Promise((resolve) => { releaseToday = resolve; });
    const digestGate = new Promise((resolve) => { releaseDigest = resolve; });
    let todaySignal, historicalDigestSignal;
    let requested = 0;
    boardFixture(t, 3, async (messages, options) => {
      if (messages[1].content.startsWith("主题")) {
        historicalDigestSignal = options.signal;
        options.onChunk("## 历史长文改写\n历史的来源。[1]");
        digestStarted(); await digestGate; return;
      }
      requested++; todaySignal = options.signal; todayStarted();
      await todayGate;
      return answerTopics(options, [1, 2], { title: "后台完成的今日主题" });
    });
    t.after(() => { releaseToday(); releaseDigest(); });
    const at = Date.now() - 10000;
    const historical = { id: `background-history-${returnWhileRunning}`, kind: "interval",
      periodStart: at - 10000, periodEnd: at, completedAt: at, expiresAt: Date.now() + 172800000, total: 1,
      articles: [{ id: 1, title: "历史来源", number: 1 }],
      topics: [{ title: "历史主题", summary: "旧摘要", articles: [1] }], digest: "旧的历史正文 [1]" };
    globalThis.__board.reports.set(historical.id, historical);
    const running = refreshBoard();
    await ready;
    await showBoardReport(historical.id);
    assert.equal(todaySignal.aborted, false, "viewing history leaves the day's analysis running");
    assert.equal(board.get().reportId, historical.id);
    let historyWriting;
    let historicalReadState;
    if (returnWhileRunning) {
      await showTodayBoard();
      assert.equal(board.get().status, "loading");
      assert.equal(board.get().historical, false);
      assert.equal(boardStream.get().total, 3);
    } else {
      assert.equal(await markBoardAsRead(), 1);
      historicalReadState = boardReadState.get();
      historyWriting = writeDigest({ again: true });
      await writingReady;
    }
    releaseToday(); await running;
    if (!returnWhileRunning) {
      assert.equal(board.get().reportId, historical.id, "background completion cannot change the selected report");
      assert.equal(digest.get().status, "streaming");
      assert.match(digest.get().text, /历史长文改写/);
      assert.equal(historicalDigestSignal.aborted, false, "completing today cannot stop a historical digest");
      assert.deepEqual(boardReadState.get(), historicalReadState, "history's read result belongs to that history");
      releaseDigest(); await historyWriting;
      await showTodayBoard();
    }
    assert.equal(board.get().status, "done");
    assert.equal(board.get().kind, "today");
    assert.equal(board.get().total, 3);
    assert.equal(board.get().topics[0].title, "后台完成的今日主题");
    assert.equal(digest.get().status, "idle", "the new day's digest is separate from historical prose");
    const completed = board.get();
    assert.equal(globalThis.__board.reports.get(completed.reportId)?.topics[0].title, "后台完成的今日主题");
    await showBoardReport(historical.id); await showTodayBoard();
    assert.equal(board.get().at, completed.at);
    assert.equal(requested, 1, "navigating back never restarts the analysis");
  });
}

test("a today failure in the background keeps the selected history and waits for explicit retry", async (t) => {
  let release, entered;
  const started = new Promise((resolve) => { entered = resolve; });
  const gate = new Promise((resolve) => { release = resolve; });
  let requested = 0;
  boardFixture(t, 2, async () => { requested++; entered(); await gate; throw new Error("后台今日连接失败"); });
  t.after(() => release());
  const at = Date.now() - 10000;
  const historical = { id: "history-during-failure", kind: "interval", periodStart: at - 10000, periodEnd: at,
    completedAt: at, expiresAt: Date.now() + 172800000, total: 1,
    articles: [{ id: 1, number: 1 }], topics: [{ title: "保留的历史", articles: [1] }], digest: "保留的历史正文 [1]" };
  globalThis.__board.reports.set(historical.id, historical);
  const running = refreshBoard();
  await started; await showBoardReport(historical.id);
  release(); await running;
  assert.equal(board.get().reportId, historical.id);
  assert.equal(board.get().error, "");
  assert.equal(digest.get().text, historical.digest);
  await showTodayBoard();
  assert.equal(board.get().status, "error");
  assert.match(board.get().error, /后台今日连接失败/);
  await showTodayBoard();
  assert.equal(requested, 1);
});

test("changing settings for a background today run preserves a pending historical selection", async (t) => {
  let firstStarted, secondStarted;
  const firstReady = new Promise((resolve) => { firstStarted = resolve; });
  const secondReady = new Promise((resolve) => { secondStarted = resolve; });
  let requested = 0;
  let firstSignal;
  boardFixture(t, 2, async (_messages, options) => {
    requested++;
    if (requested === 1) {
      firstSignal = options.signal; firstStarted();
      await new Promise((_resolve, reject) => options.signal.addEventListener("abort",
        () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })), { once: true }));
    }
    secondStarted();
    return answerTopics(options, [1], { title: "新筛选下的今日" });
  });
  const at = Date.now() - 10000;
  const historical = { id: "settings-history-1", kind: "interval", periodStart: at - 10000, periodEnd: at,
    completedAt: at, expiresAt: Date.now() + 172800000, total: 1,
    articles: [{ id: 1, number: 1 }], topics: [{ title: "历史主题", articles: [1] }], digest: "历史 [1]" };
  const selected = { ...historical, id: "settings-history-2" };
  globalThis.__board.reports.set(historical.id, historical);
  const read = globalThis.__board.readReport;
  const settings = settingsState.get();
  let release;
  globalThis.__board.readReport = (id) => id === selected.id ? new Promise((resolve) => { release = resolve; }) : read(id);
  t.after(() => { globalThis.__board.readReport = read; openBoard(false); settingsState.set(settings); release?.(selected); });
  const running = refreshBoard();
  await firstReady; await openBoard(true); await showBoardReport(historical.id);
  const choosing = showBoardReport(selected.id);
  settingsState.set({ ...settings, showHiddenFeeds: !settings.showHiddenFeeds });
  await secondReady;
  assert.equal(firstSignal.aborted, true, "changed article settings replace the old analysis");
  release(selected); await choosing; await running; await tick();
  assert.equal(board.get().reportId, selected.id, "the pending history choice survives the background replacement");
  await showTodayBoard();
  assert.equal(board.get().topics[0].title, "新筛选下的今日");
  assert.equal(requested, 2);
});

test("cold today navigation restores the newest compatible persisted day, including version 1.24 fingerprints", async (t) => {
  let requested = 0;
  boardFixture(t, 3, async () => { requested++; throw new Error("must restore saved today"); });
  const { reportHistory } = await import("./historyStore.js");
  const settings = settingsState.get();
  const filter = `${Boolean(settings.showHiddenFeeds)}|${settings.hideDuplicateArticles !== false}|${settings.duplicateMatch || "either"}`;
  const day = startOfDay().getTime();
  const at = Math.max(day, Date.now() - 5000);
  const saved = {
    id: "cold-today", kind: "today", periodStart: day, periodEnd: at,
    completedAt: at, expiresAt: Date.now() + 172800000, total: 2,
    articles: [{ id: 1, title: "保存的来源 1", number: 1 }, { id: 2, title: "保存的来源 2", number: 2 }],
    topics: [{ title: "保存的今日主题", gist: "重点", summary: "摘要", articles: [1] }],
    digest: "## 保存的今日长文\n原来已读过的报告。[1]",
    fingerprint: JSON.stringify(["board-v2", "https://old-ai.example", "old-model",
      Boolean(settings.showHiddenFeeds), settings.hideDuplicateArticles !== false, settings.duplicateMatch || "either"]),
  };
  const reports = [
    { ...saved, id: "newer-interval", kind: "interval", completedAt: at + 3 },
    { ...saved, id: "newer-incompatible", filter: `other-${filter}`, completedAt: at + 2 },
    saved,
    { ...saved, id: "older-compatible", filter, completedAt: at - 1 },
    { ...saved, id: "yesterday", periodStart: day - 86400000, completedAt: at + 1 },
  ];
  for (const report of reports) globalThis.__board.reports.set(report.id, report);
  reportHistory.set({ reports });
  t.after(() => reportHistory.set({ reports: [] }));
  // The app first shows its latest historical interval. Choosing today
  // must recover today's own saved snapshot rather than analyze it again.
  await openBoard(true);
  assert.equal(board.get().reportId, "newer-interval");
  await showTodayBoard(); await tick(); await tick();
  assert.equal(board.get().historical, false);
  assert.equal(board.get().reportId, saved.id);
  assert.equal(board.get().filter, filter);
  assert.equal(digest.get().text, saved.digest);
  assert.equal(board.get().newAvailableCount, 1);
  assert.equal(requested, 0);
  assert.equal(await markBoardAsRead(), 1);
  await sync();
  await showBoardReport("newer-interval");
  await showTodayBoard();
  openBoard(false); await openBoard(true);
  assert.equal(board.get().reportId, saved.id);
  assert.equal(board.get().total, 2, "read status changes cannot shrink the saved report");
  assert.equal(requested, 0);
  openBoard(false);
});

test("a failed history read cannot silently trigger another paid today generation", async (t) => {
  let requested = 0;
  boardFixture(t, 2, async (_messages, options) => { requested++; return answerTopics(options, [1]); });
  const { reportHistory } = await import("./historyStore.js");
  const read = globalThis.__board.readReport;
  globalThis.__board.readReport = async () => { throw new Error("读取超时"); };
  reportHistory.set({ reports: [{ id: "unreadable-today", kind: "today", periodStart: startOfDay().getTime(),
    completedAt: Date.now(), expiresAt: Date.now() + 172800000 }] });
  t.after(() => { globalThis.__board.readReport = read; reportHistory.set({ reports: [] }); });
  await showTodayBoard();
  assert.equal(board.get().status, "error");
  assert.match(board.get().error, /恢复失败.*读取超时/);
  await showTodayBoard();
  assert.equal(requested, 0);
  await refreshBoard();
  assert.equal(requested, 1);
  assert.equal(board.get().status, "done");
});

test("a delayed today restore cannot replace a newer history selection or another account", async (t) => {
  boardFixture(t, 2, async () => { throw new Error("must not generate"); });
  const { reportHistory } = await import("./historyStore.js");
  const read = globalThis.__board.readReport;
  const settings = settingsState.get();
  const saved = { id: "delayed-today", kind: "today", periodStart: startOfDay().getTime(),
    periodEnd: Date.now(), completedAt: Date.now(), expiresAt: Date.now() + 172800000,
    articles: [{ id: 1, number: 1 }], topics: [], total: 1,
    filter: `${Boolean(settings.showHiddenFeeds)}|${settings.hideDuplicateArticles !== false}|${settings.duplicateMatch || "either"}` };
  const interval = { ...saved, id: "chosen-history", kind: "interval" };
  globalThis.__board.reports.set(interval.id, interval);
  reportHistory.set({ reports: [saved] });
  let release;
  globalThis.__board.readReport = (id) => id === saved.id ? new Promise((resolve) => { release = resolve; }) : read(id);
  t.after(() => { globalThis.__board.readReport = read; reportHistory.set({ reports: [] }); });
  let pending = showTodayBoard();
  while (!release) await tick();
  await showBoardReport(interval.id);
  release(saved); await pending;
  assert.equal(board.get().reportId, interval.id);
  release = null;
  pending = showTodayBoard();
  while (!release) await tick();
  authState.set({ ...authState.get(), serverUrl: "https://replacement.example" });
  release(saved); await pending;
  assert.equal(board.get().status, "idle");
  assert.equal(digest.get().text, "");
});

test("runtime flush stops manual streams and waits for an already completed snapshot to become durable", async (t) => {
  boardFixture(t, 2, async (_messages, options) => answerTopics(options, [1]));
  const save = globalThis.__board.saveSnapshot;
  let release;
  globalThis.__board.saveSnapshot = (state, text) => new Promise((resolve) => {
    release = async () => resolve(await save(state, text));
  });
  t.after(() => { globalThis.__board.saveSnapshot = save; });
  const generating = refreshBoard();
  while (!release) await tick();
  const state = board.get();
  let flushed = false;
  const flushing = flushBoardWork().then(() => { flushed = true; });
  await tick(); assert.equal(flushed, false);
  await release(); await generating; await flushing;
  assert.equal(globalThis.__board.reports.get(state.reportId).completedAt, state.at);
  const callReady = waitCall();
  globalThis.__board.stream = (messages, options) => new Promise((resolve, reject) => {
    const call = { messages, ...options, resolve, reject };
    calls.push(call); next?.(call);
    options.signal.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })));
  });
  const updating = refreshBoard();
  const live = await callReady;
  await flushBoardWork();
  live.onChunk(TOPICS); live.resolve();
  await updating;
  assert.equal(live.signal.aborted, true);
  assert.equal(board.get().updating, false);
  assert.equal(board.get().at, state.at);
});
