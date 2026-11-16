import test, { after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { register } from "node:module";

// Exercise the real account queue, native HTTP adapter, durable cache and UI
// stores. Only the Web Worker transport is replaced by its same cache core.
const sourceRoot = new URL("../src/", import.meta.url).href;
const coreUrl = new URL("../src/toolbox/cache.js", import.meta.url).href;
const bridge = `
import { createArticleCache } from ${JSON.stringify(coreUrl)};
export function createWorkerArticleCache(storage) {
  return createArticleCache(storage, { autoGc: false, tokenPrefix: "board.read.integration" });
}`;
const loader = `
const root = ${JSON.stringify(sourceRoot)};
const bridge = ${JSON.stringify("data:text/javascript," + encodeURIComponent(bridge))};
export async function resolve(specifier, context, next) {
  if (context.parentURL?.endsWith("/db/storage.js") && specifier === "../toolbox/cache-client.js") {
    return { url: bridge, shortCircuit: true };
  }
  let resolved;
  if (specifier.startsWith("@/")) resolved = new URL(specifier.slice(2), root);
  else if (context.parentURL?.startsWith(root) && specifier.startsWith(".")) resolved = new URL(specifier, context.parentURL);
  if (resolved) {
    if (!resolved.pathname.split("/").at(-1).includes(".")) resolved.pathname += ".js";
    return next(resolved.href, context);
  }
  return next(specifier, context);
}`;
register("data:text/javascript," + encodeURIComponent(loader));

const values = new Map();
const clone = (value) => value === undefined ? undefined : structuredClone(value);
let failNextRoot = false;
const storage = {
  async get(key) { return clone(values.get(key) ?? null); },
  async getMany(keys) { return keys.map((key) => clone(values.get(key) ?? null)); },
  async apply(change) {
    if (failNextRoot && change.set?.some(({ key }) => key === ROOT_KEY)) {
      failNextRoot = false;
      throw new Error("simulated durable write failure");
    }
    for (const { key, value } of change.set || []) values.set(key, clone(value));
    for (const key of change.remove || []) values.delete(key);
  },
  async keys() { return [...values.keys()]; },
};
let serverRows = new Map();
let requests = [];
let interceptRequest = null;
async function request(payload) {
  assert.equal(payload.method, "PUT");
  assert.equal(new URL(payload.url).pathname, "/v1/entries", "never use a feed, category or all-read endpoint");
  const body = JSON.parse(payload.body);
  assert.deepEqual(Object.keys(body).sort(), ["entry_ids", "status"]);
  const call = { ids: [...body.entry_ids], status: body.status, headers: { ...payload.headers } };
  requests.push(call);
  const intercepted = await interceptRequest?.(call, requests.length);
  if (intercepted !== undefined) return intercepted;
  for (const id of call.ids) if (serverRows.has(id)) serverRows.get(id).status = call.status;
  return { status: 204, headers: {}, body: "" };
}

globalThis.window = Object.assign(new EventTarget(), {
  ToolBox: { storage, network: { request } }, location: { hash: "#/" },
});
Object.defineProperty(globalThis.navigator, "onLine", { value: true, configurable: true });
const { runAccountOperation, cancelAccountOperations, isOnline } = await import("../src/stores/syncStore.js");
const { authState } = await import("../src/stores/authStore.js");
const { settingsState } = await import("../src/stores/settingsStore.js");
const { feeds, categories, unreadCounts, starredCounts } = await import("../src/stores/feedsStore.js");
const {
  markArticlesAsRead, queueArticleRead, updateArticleStatus,
  activeArticle, filteredArticles, resetArticleState,
} = await import("../src/stores/articlesStore.js");
const {
  clearArticleCache, initializeArticleCache, addArticles, addFeeds,
  getArticleById, getArticleIds, getArticleCounts,
} = await import("../src/db/storage.js");
const { ROOT_KEY, createArticleCache } = await import("../src/toolbox/cache.js");
const { SERVER_URL } = await import("../src/toolbox/server.mjs");
const defaults = settingsState.get();
const account = { serverUrl: SERVER_URL, userId: "7" };
const FEED = { id: 7, title: "看板测试订阅", url: "https://example.test/feed", categoryId: 2 };
const TIME = "2026-11-16T01:00:00.000Z";

const ids = (count, start = 1) => Array.from({ length: count }, (_, index) => start + index);
function entries(count) {
  return ids(count).map((id) => ({
    id, feedId: FEED.id, title: `看板文章 ${id}`, author: "测试作者", url: `https://example.test/article/${id}`,
    content: `<p>文章正文 ${id}</p>`, enclosures: [], reading_time: 1,
    status: "unread", starred: 0, created_at: TIME, published_at: TIME, changed_at: TIME,
  }));
}
async function seed(rows) {
  await addArticles(rows);
  serverRows = new Map(rows.map((row) => [row.id, clone(row)]));
  filteredArticles.set(clone(rows));
  const counts = await getArticleCounts();
  unreadCounts.set(counts.unread);
  starredCounts.set(counts.starred);
}
const unreadIds = async () => (await getArticleIds({ filter: "unread" })).sort((left, right) => left - right);
function deferred() {
  let resolve;
  const promise = new Promise((yes) => { resolve = yes; });
  return { promise, resolve };
}

beforeEach(async () => {
  failNextRoot = false;
  interceptRequest = null;
  await cancelAccountOperations();
  await clearArticleCache();
  resetArticleState();
  feeds.set([FEED]);
  categories.set([]);
  unreadCounts.set({});
  starredCounts.set({});
  authState.set({ ...authState.get(), serverUrl: SERVER_URL, userId: 7, authType: "token", token: "board-test-token" });
  settingsState.set({ ...defaults, syncInterval: "0", hideDuplicateArticles: false });
  isOnline.set(true);
  await initializeArticleCache(account);
  await addFeeds([FEED]);
  serverRows = new Map();
  requests = [];
});
after(async () => { await cancelAccountOperations(); });

test("only distinct selected unread articles change, including durable state and visible counts", async () => {
  const rows = entries(5);
  rows[1].status = "read";
  rows[2].starred = 1;
  // A copy omitted from the board must stay unread, even with duplicate hiding.
  rows[3].title = rows[0].title;
  rows[3].url = rows[0].url;
  await seed(rows);
  activeArticle.set(clone(rows[2]));
  settingsState.set({ ...settingsState.get(), hideDuplicateArticles: true });
  assert.equal(await markArticlesAsRead([1, 2, 3, 1, 999]), 2);
  assert.deepEqual(requests.map(({ ids, status }) => ({ ids, status })), [{ ids: [1, 3], status: "read" }]);
  assert.deepEqual(await unreadIds(), [4, 5]);
  assert.equal(serverRows.get(4).status, "unread");
  assert.equal(serverRows.get(5).status, "unread");
  assert.equal(activeArticle.get().status, "read");
  assert.equal(activeArticle.get().starred, 1);
  assert.equal(filteredArticles.get().find(({ id }) => id === 4).status, "unread");
  assert.equal(starredCounts.get()[7], 1);
  // A fresh reader proves the operation persisted the result, not just atoms.
  const reopened = createArticleCache(storage, { autoGc: false, tokenPrefix: "board.reopened" });
  await reopened.initialize(account);
  assert.equal((await reopened.readMetadata(1)).status, "read");
  assert.equal((await reopened.readMetadata(4)).status, "unread");
  assert.equal(unreadCounts.get()[7], (await reopened.counts(undefined, {
    hideDuplicates: true, field: settingsState.get().sortField, direction: settingsState.get().sortDirection,
    duplicateMatch: settingsState.get().duplicateMatch,
  })).unread[7]);
  assert.equal(await markArticlesAsRead([1, 2, 3]), 0);
  assert.equal(requests.length, 1);
});

test("empty and invalid selections cannot mutate state, and callers cannot enlarge a queued selection", async () => {
  await seed(entries(3));
  assert.equal(await markArticlesAsRead([]), 0);
  for (const invalid of [null, {}, "1", [0], [-1], [1.5], ["1"], [true], [NaN], new Array(1)]) {
    await assert.rejects(markArticlesAsRead(invalid), /文章编号无效/);
  }
  assert.equal(requests.length, 0);
  const selection = [1];
  const marking = markArticlesAsRead(selection);
  selection.push(2, 3);
  assert.equal(await marking, 1);
  assert.deepEqual(requests.map(({ ids }) => ids), [[1]]);
  assert.deepEqual(await unreadIds(), [2, 3]);
});

test("overlapping invocations reselect unread IDs inside the existing account queue", async () => {
  await seed(entries(4));
  assert.deepEqual(await Promise.all([markArticlesAsRead([1, 2]), markArticlesAsRead([2, 3])]), [2, 1]);
  assert.deepEqual(requests.map(({ ids }) => ids), [[1, 2], [3]]);
  assert.deepEqual(await unreadIds(), [4]);
});

test("large selections use bounded requests while retaining IDs outside the selection", async () => {
  await seed(entries(601));
  assert.equal(await markArticlesAsRead(ids(600)), 600);
  assert.deepEqual(requests.map(({ ids }) => ids.length), [256, 256, 88]);
  assert.deepEqual(requests.flatMap(({ ids }) => ids), ids(600));
  assert.deepEqual(await unreadIds(), [601]);
  assert.equal(unreadCounts.get()[7], 1);
});

test("a later batch failure reports only committed progress and retry skips acknowledged articles", async () => {
  await seed(entries(601));
  interceptRequest = (_call, number) => number === 2 ? { status: 500, headers: {}, body: "" } : undefined;
  await assert.rejects(markArticlesAsRead(ids(600)), (failure) => {
    assert.equal(failure.code, "HTTP_ERROR");
    assert.equal(failure.markedCount, 256);
    assert.match(failure.message, /已确认 256 篇/);
    return true;
  });
  assert.deepEqual(await unreadIds(), ids(345, 257));
  assert.equal(serverRows.get(257).status, "unread");
  assert.equal(unreadCounts.get()[7], 345);
  interceptRequest = null;
  requests = [];
  assert.equal(await markArticlesAsRead(ids(600)), 344);
  assert.deepEqual(requests.map(({ ids }) => ids.length), [256, 88]);
  assert.deepEqual(requests.flatMap(({ ids }) => ids), ids(344, 257));
  assert.deepEqual(await unreadIds(), [601]);
});

test("a failed first request keeps local unread state and reports no confirmed progress", async () => {
  await seed(entries(3));
  interceptRequest = () => { throw Object.assign(new Error("transport down"), { code: "NETWORK_UNAVAILABLE" }); };
  await assert.rejects(markArticlesAsRead([1, 2]), (failure) => {
    assert.equal(failure.code, "NETWORK_UNAVAILABLE");
    assert.equal(failure.markedCount, 0);
    return true;
  });
  assert.deepEqual(await unreadIds(), [1, 2, 3]);
  assert.equal(serverRows.get(1).status, "unread");
  assert.equal(unreadCounts.get()[7], 3);
});

test("a server acknowledgement survives local failure and recovery precedes a subsequent selection", async () => {
  await seed(entries(3));
  activeArticle.set(clone(serverRows.get(1)));
  failNextRoot = true;
  await assert.rejects(markArticlesAsRead([1]), (failure) => {
    assert.equal(failure.code, "LOCAL_STATE_PENDING");
    assert.equal(failure.markedCount, 0);
    return true;
  });
  assert.equal(serverRows.get(1).status, "read");
  assert.equal((await getArticleById(1)).status, "unread");
  assert.equal(activeArticle.get().status, "unread");
  assert.equal(await markArticlesAsRead([1, 2]), 1);
  assert.deepEqual(requests.map(({ ids }) => ids), [[1], [2]], "recovery must not repeat the acknowledged server request");
  assert.deepEqual(await unreadIds(), [3]);
  assert.equal(activeArticle.get().status, "read");
  assert.equal(unreadCounts.get()[7], 1);
});

test("account invalidation cancels a queued selection before dispatch", async () => {
  await seed(entries(3));
  const entered = deferred();
  const release = deferred();
  const blocker = runAccountOperation(async () => { entered.resolve(); await release.promise; });
  await entered.promise;
  const marking = markArticlesAsRead([1, 2]);
  const rejected = assert.rejects(marking, { code: "ACCOUNT_CHANGED" });
  const draining = cancelAccountOperations();
  release.resolve();
  await Promise.all([blocker, draining, rejected]);
  assert.equal(requests.length, 0);
  assert.deepEqual(await unreadIds(), [1, 2, 3]);
});

test("account changes during a request cannot publish its reply or send later batches", async () => {
  await seed(entries(301));
  let draining;
  interceptRequest = () => {
    draining = cancelAccountOperations();
    authState.set({ ...authState.get(), userId: 8, token: "another-test-account" });
  };
  await assert.rejects(markArticlesAsRead(ids(300)), (failure) => {
    assert.equal(failure.code, "ACCOUNT_CHANGED");
    assert.equal(failure.markedCount, 0);
    return true;
  });
  await draining;
  assert.equal(requests.length, 1);
  assert.equal(requests[0].headers["X-Auth-Token"], "board-test-token");
  assert.deepEqual(await unreadIds(), ids(301));
  assert.equal(unreadCounts.get()[7], 301);
});

test("manual selection absorbs selected automatic reads and a later unread action still wins", async () => {
  await seed(entries(4));
  const automatic = queueArticleRead({ id: 1 });
  const marking = markArticlesAsRead([1, 2]);
  const keepUnread = updateArticleStatus({ id: 2 }, "unread");
  assert.equal(await marking, 2);
  await Promise.all([automatic, keepUnread]);
  assert.deepEqual(requests.map(({ ids, status }) => ({ ids, status })), [
    { ids: [1, 2], status: "read" }, { ids: [2], status: "unread" },
  ]);
  assert.deepEqual(await unreadIds(), [2, 3, 4]);
});
