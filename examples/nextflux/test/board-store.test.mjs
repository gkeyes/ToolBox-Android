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
  "@/stores/syncStore.js": `import { atom } from "nanostores"; export const isSyncing = atom(false); globalThis.__board.isSyncing = isSyncing;`,
  "@/api/openai.js": `export const streamChat = (messages, options) => globalThis.__board.stream(messages, options);`,
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
const { board, boardOpen, boardStream, digest, openBoard, refreshBoard, writeDigest } = await import("../src/toolbox/board/boardStore.js");
const sync = async () => { globalThis.__board.isSyncing.set(true); globalThis.__board.isSyncing.set(false); await tick(); await tick(); };
const TOPICS = JSON.stringify({ topics: [{ title: "主题", gist: "重点", summary: "摘要", articles: [1, 2] }] });

test("a failed board does not ask the AI again on a sync with nothing new", async () => {
  rows = [article(3)];
  let call = waitCall();
  refreshBoard();
  (await call).reject(new Error("HTTP 500"));
  await tick(); await tick();
  assert.equal(board.get().status, "error");
  const before = calls.length;
  await sync(); await sync();
  assert.equal(calls.length, before, "no AI call for a sync with nothing new");
  // Something new does try again.
  rows = [article(3), article(4)];
  call = waitCall();
  await sync();
  const retry = await call;
  assert.equal(retry.kind, "topics");
  retry.reject(new Error("HTTP 500"));
  await tick(); await tick();
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
  // While the second reply streams, the caption still says it is the second.
  assert.match(page, /stream\.text \? `\$\{stream\.first \? "第二次，" : ""\}AI 正在输出/);
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
  const page = readFileSync(new URL("../src/components/Board/BoardPage.jsx", import.meta.url), "utf8");
  assert.match(page, /state\.cut \? `AI 读了今天的 \$\{read\} 篇未读，回答中途断开，只列出完整收到的 \$\{state\.topics\.length\} 个主题`/);
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

test("a sync while settings settle waits for them; changed back, it still brings in new articles", async () => {
  const settled = () => new Promise((resolve) => setTimeout(resolve, 700));
  rows = [article(1), article(2)];
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  await settled();
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  openBoard(true);
  // A sync inside the window makes no call of its own.
  const before = calls.length;
  settingsState.set({ ...settingsState.get(), duplicateMatch: "url" });
  rows = [article(1), article(2), article(3)];
  await sync();
  assert.equal(calls.length, before, "the sync waits for the settings");
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  call = waitCall();
  await settled();
  // Back to the board's settings: one call, for the new article.
  const fresh = await call;
  assert.equal(calls.length, before + 1);
  fresh.onChunk(TOPICS); fresh.resolve();
  await tick(); await tick();
  assert.equal(board.get().newCount, 1);
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
  openBoard(true);
  // (Opening a stale board makes it again; answer that first.)
  const reopened = calls.at(-1);
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

test("a sync deferred by settling settings is kept when the board closes, and after a board still being made", async () => {
  const settled = () => new Promise((resolve) => setTimeout(resolve, 700));
  rows = [article(1), article(2)];
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  await settled();
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  // Closed before the settings settle: the sync's article still comes in.
  openBoard(true);
  let before = calls.length;
  settingsState.set({ ...settingsState.get(), duplicateMatch: "url" });
  rows = [article(1), article(2), article(3)];
  await sync();
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  openBoard(false);
  call = waitCall();
  await settled();
  const closed = await call;
  assert.equal(calls.length, before + 1, "one call for the synced article");
  closed.onChunk(TOPICS); closed.resolve();
  await tick(); await tick();
  assert.ok(board.get().ids.includes(3));

  // A board being made when the sync lands: brought up to date after it.
  call = waitCall();
  refreshBoard();
  const running = await call;
  before = calls.length;
  rows = [article(1), article(2), article(3), article(4)];
  await sync();
  assert.equal(calls.length, before, "nothing while the board is made");
  call = waitCall();
  running.onChunk(TOPICS); running.resolve();
  const after = await call;
  assert.equal(after.kind, "topics", "the synced article is asked about once the board is made");
  after.onChunk(TOPICS); after.resolve();
  await tick(); await tick();
  assert.ok(board.get().ids.includes(4));
});

test("a sync while a closed board is out of date is read once the settings return; a queued sync waits for settling settings", async () => {
  const settled = () => new Promise((resolve) => setTimeout(resolve, 700));
  rows = [article(1), article(2)];
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  await settled();
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  // Changed while open, closed, synced while out of date, then changed back.
  openBoard(true);
  let before = calls.length;
  settingsState.set({ ...settingsState.get(), duplicateMatch: "url" });
  openBoard(false);
  await settled();
  rows = [article(1), article(2), article(5)];
  await sync();
  assert.equal(calls.length, before, "closed and out of date: nothing yet");
  call = waitCall();
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  await settled();
  const back = await call;
  assert.equal(calls.length, before + 1, "the synced article is read");
  back.onChunk(TOPICS); back.resolve();
  await tick(); await tick();
  assert.ok(board.get().ids.includes(5));

  // A sync queued behind a board being made, then settings start changing
  // before it is done: nothing until they settle.
  openBoard(true);
  call = waitCall();
  refreshBoard();
  const running = await call;
  rows = [article(1), article(2), article(5), article(6)];
  await sync();
  settingsState.set({ ...settingsState.get(), duplicateMatch: "title" });
  before = calls.length;
  running.onChunk(TOPICS); running.resolve();
  await tick(); await tick();
  assert.equal(calls.length, before, "the queued sync waits for the settings");
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  call = waitCall();
  await settled();
  const last = await call;
  assert.equal(calls.length, before + 1, "one call once they settle");
  last.onChunk(TOPICS); last.resolve();
  await tick(); await tick();
  assert.ok(board.get().ids.includes(6));
  openBoard(false);
});

test("a sync landing while a refresh finds nothing new is read after it", async () => {
  const settled = () => new Promise((resolve) => setTimeout(resolve, 700));
  rows = [article(1), article(2)];
  settingsState.set({ ...settingsState.get(), duplicateMatch: "either" });
  await settled();
  let call = waitCall();
  refreshBoard();
  (await call).onChunk(TOPICS); calls.at(-1).resolve();
  await tick(); await tick();
  openBoard(true);
  const before = calls.length;
  // The refresh reads nothing new; a sync bringing article 7 finishes right after the read.
  globalThis.__board.onRead = () => {
    rows = [article(1), article(2), article(7)];
    globalThis.__board.isSyncing.set(true); globalThis.__board.isSyncing.set(false);
  };
  call = waitCall();
  refreshBoard({ onlyIfNew: true });
  const after = await call;
  assert.equal(calls.length, before + 1, "the sync's article is asked about");
  after.onChunk(TOPICS); after.resolve();
  await tick(); await tick();
  assert.ok(board.get().ids.includes(7));
  openBoard(false);
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

test("a sync left over when the AI was switched off mid-build is read when the board is next opened", async () => {
  rows = [article(1), article(2)];
  let call = waitCall();
  refreshBoard();
  const running = await call;
  // A sync lands while the board is made, then the AI key is cleared
  // before it is done: nothing can read the sync yet.
  rows = [article(1), article(2), article(8)];
  await sync();
  const key = settingsState.get().aiApiKey;
  settingsState.set({ ...settingsState.get(), aiApiKey: "" });
  const before = calls.length;
  running.onChunk(TOPICS); running.resolve();
  await tick(); await tick();
  assert.equal(calls.length, before, "no AI, nothing asked");
  // The key back and the board opened: the sync's article comes in.
  settingsState.set({ ...settingsState.get(), aiApiKey: key });
  call = waitCall();
  openBoard(true);
  const opened = await call;
  opened.onChunk(TOPICS); opened.resolve();
  await tick(); await tick();
  assert.ok(board.get().ids.includes(8));
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

test("a sync landing while the first board of the day is made is read after it", async () => {
  // A board not made yet, as at launch.
  board.set({ ...board.get(), status: "idle", ids: [], filter: null, day: null });
  rows = [article(1), article(2)];
  // The sync finishes as the articles are read, before the board shows as loading.
  globalThis.__board.onRead = () => {
    rows = [article(1), article(2), article(9)];
    globalThis.__board.isSyncing.set(true); globalThis.__board.isSyncing.set(false);
  };
  let call = waitCall();
  refreshBoard();
  const first = await call;
  call = waitCall();
  first.onChunk(TOPICS); first.resolve();
  const after = await call;
  after.onChunk(TOPICS); after.resolve();
  await tick(); await tick();
  assert.ok(board.get().ids.includes(9));
  // The status line under the title is cut at the title's width.
  const css = readFileSync(new URL("../src/design/ios27.css", import.meta.url), "utf8");
  assert.match(css, /\.article-list-title > \.article-list-caption \{ max-inline-size: 100%; \}/);
});
