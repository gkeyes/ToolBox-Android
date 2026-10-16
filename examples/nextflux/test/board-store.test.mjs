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
  rows: () => rows,
  // Each AI call waits for the test to answer it.
  stream: (messages, { onChunk, onThink, signal }) => new Promise((resolve, reject) => {
    const call = { kind: messages[0].content.includes("文章") && messages[1].content.startsWith("主题") ? "digest" : "topics", onChunk, onThink, resolve, reject, signal };
    signal?.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })));
    calls.push(call);
    next?.(call);
  }),
};
const waitCall = () => new Promise((resolve) => { next = (call) => { next = null; resolve(call); }; });
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
const { board, boardStream, digest, refreshBoard, writeDigest } = await import("../src/toolbox/board/boardStore.js");
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
