import test, { after, beforeEach, mock } from "node:test";
import assert from "node:assert/strict";
import { register } from "node:module";
import { toast } from "sonner";

// Keep syncStore, the API/native adapter, db/storage, cache core and article /
// feed stores real. Only replace the Web Worker transport with an in-process
// instance of the same core; Node cannot construct the browser Worker.
const sourceRoot = new URL("../src/", import.meta.url).href;
const coreUrl = new URL("../src/toolbox/cache.js", import.meta.url).href;
const bridge = `
import { createArticleCache } from ${JSON.stringify(coreUrl)};
export function createWorkerArticleCache(storage) {
  const boundary = globalThis.__recentSyncBoundary;
  const core = createArticleCache(storage, { autoGc: false, tokenPrefix: "recent.integration" });
  boundary.core = core;
  return {
    ...core,
    async prepareSyncCommit(token, catalog) {
      boundary.preparations.push(structuredClone(catalog));
      const result = await core.prepareSyncCommit(token, catalog);
      await boundary.afterPreparation?.();
      return result;
    },
    async commitSync(token, catalog) {
      boundary.commits.push(structuredClone(catalog));
      return core.commitSync(token, catalog);
    },
  };
}`;
const loader = `
const root = ${JSON.stringify(sourceRoot)};
const bridge = ${JSON.stringify("data:text/javascript," + encodeURIComponent(bridge))};
export async function resolve(specifier, context, next) {
  if (context.parentURL?.endsWith("/db/storage.js") && specifier === "../toolbox/cache-client.js") {
    return { url: bridge, shortCircuit: true };
  }
  // Match Vite's existing @ alias and extensionless JS imports, without
  // substituting any application logic at those boundaries.
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
const storage = {
  async get(key) { return clone(values.get(key) ?? null); },
  async getMany(keys) { return keys.map((key) => clone(values.get(key) ?? null)); },
  async apply(change) {
    for (const { key, value } of change.set || []) values.set(key, clone(value));
    for (const key of change.remove || []) values.delete(key);
  },
  async keys() { return [...values.keys()]; },
};
const boundary = globalThis.__recentSyncBoundary = {
  preparations: [], commits: [], afterPreparation: null,
};
let serverRows = [];
let requests = [];
let interceptRequest = null;
const CATEGORY = { id: 2, title: "集成测试分类" };
const FEED = { id: 7, title: "集成测试订阅", feed_url: "https://example.test/feed", site_url: "https://example.test", category: CATEGORY };
const OLD_TIME = "2020-01-01T00:00:00.000Z";
const jsonResponse = (body) => ({ status: 200, headers: {}, body: JSON.stringify(body) });

async function request(payload) {
  assert.equal(payload.method, "GET", "cache sync does not mutate server read or star state");
  const url = new URL(payload.url);
  requests.push(url);
  const intercepted = await interceptRequest?.(url);
  if (intercepted !== undefined) return intercepted;
  if (url.pathname === "/v1/feeds") return jsonResponse([FEED]);
  if (url.pathname === "/v1/categories") return jsonResponse([CATEGORY]);
  assert.equal(url.pathname, "/v1/entries");
  const statuses = url.searchParams.getAll("status");
  const starred = url.searchParams.get("starred");
  const changedAfter = Number(url.searchParams.get("changed_after")) || 0;
  const before = Number(url.searchParams.get("before_entry_id")) || Infinity;
  const afterId = Number(url.searchParams.get("after_entry_id")) || 0;
  const direction = url.searchParams.get("direction") === "asc" ? 1 : -1;
  const selected = serverRows.filter((entry) =>
    (!statuses.length || statuses.includes(entry.status)) &&
    (starred === null || entry.starred === (starred === "true")) &&
    (!changedAfter || Date.parse(entry.changed_at) / 1000 >= changedAfter) &&
    entry.id < before && entry.id > afterId)
    .sort((left, right) => direction * (left.id - right.id));
  return jsonResponse({ total: selected.length, entries: selected.slice(0, Number(url.searchParams.get("limit"))) });
}

globalThis.window = Object.assign(new EventTarget(), {
  ToolBox: { storage, network: { request } }, location: { hash: "#/" },
});
Object.defineProperty(globalThis.navigator, "onLine", { value: true, configurable: true });
const toastErrors = mock.method(toast, "error", () => {});
const { sync, cancelAccountOperations, isOnline, isSyncing, lastSync } = await import("../src/stores/syncStore.js");
const { authState } = await import("../src/stores/authStore.js");
const { settingsState } = await import("../src/stores/settingsStore.js");
const { feeds, categories, unreadCounts, starredCounts } = await import("../src/stores/feedsStore.js");
const { activeArticle, articleContentRevision, resetArticleState } = await import("../src/stores/articlesStore.js");
const { clearArticleCache, initializeArticleCache, getArticleById, getArticleIds, getLastSyncTime } = await import("../src/db/storage.js");
const { ROOT_KEY } = await import("../src/toolbox/cache.js");
const { SERVER_URL } = await import("../src/toolbox/server.mjs");
const defaults = settingsState.get();
const account = { serverUrl: SERVER_URL, userId: "7" };

function entries(count) {
  return Array.from({ length: count }, (_, index) => {
    const id = index + 1;
    return {
      id, feed: FEED, title: `集成文章 ${id}`, author: "测试作者", url: `https://example.test/article/${id}`,
      content: `<p>本地可读正文 ${id}</p>`, enclosures: [], reading_time: 1,
      status: id === 2 ? "unread" : "read", starred: id === 3,
      created_at: OLD_TIME, published_at: OLD_TIME, changed_at: OLD_TIME,
    };
  });
}
const recentRequests = () => requests.filter((url) => url.searchParams.getAll("status").length === 2);
const cachedIds = async () => (await getArticleIds()).sort((left, right) => left - right);
const expectedIds = (count, limit, protectedIds = []) => [...new Set([
  2, 3, ...Array.from({ length: Math.min(count, limit) }, (_, index) => count - index), ...protectedIds,
])].sort((left, right) => left - right);

beforeEach(async () => {
  await cancelAccountOperations();
  boundary.afterPreparation = null;
  await clearArticleCache();
  resetArticleState();
  window.location.hash = "#/";
  feeds.set([]);
  categories.set([]);
  unreadCounts.set({});
  starredCounts.set({});
  authState.set({ ...authState.get(), serverUrl: SERVER_URL, userId: 7, authType: "token", token: "integration-test-token" });
  settingsState.set({ ...defaults, recentCacheLimit: "100", syncInterval: "0", hideDuplicateArticles: false });
  isOnline.set(true);
  lastSync.set(null);
  await initializeArticleCache(account);
  serverRows = entries(120);
  requests = [];
  interceptRequest = null;
  boundary.preparations.length = 0;
  boundary.commits.length = 0;
  toastErrors.mock.resetCalls();
});
after(async () => { await cancelAccountOperations(); });

test("initial sync supplements unread and bookmarks with recent read bodies that can be opened offline", async () => {
  const result = await sync();
  assert.equal(result.outcome, "committed");
  assert.equal(result.initialSync, true);
  assert.equal(result.recentCacheLimit, 100);
  assert.equal(result.recentCachedCount, 100);
  assert.deepEqual(await cachedIds(), expectedIds(120, 100));
  assert.deepEqual(requests.slice(0, 3).map((url) => url.searchParams.getAll("status")),
    [["unread"], ["read"], ["read", "unread"]]);
  assert.equal(requests[1].searchParams.get("starred"), "true");
  assert.equal((await getArticleById(30)).status, "read");
  assert.equal((await getArticleById(2)).status, "unread");
  assert.equal((await getArticleById(3)).starred, 1);
  assert.deepEqual(unreadCounts.get(), { 7: 1 });
  assert.deepEqual(starredCounts.get(), { 7: 1 });
  const requestCount = requests.length;
  isOnline.set(false);
  interceptRequest = () => assert.fail("reading persisted bodies must not request the server");
  assert.equal((await getArticleById(30)).content, "<p>本地可读正文 30</p>");
  assert.equal(requests.length, requestCount);
  assert.equal(getLastSyncTime().toISOString(), result.syncedAt);
});

test("a later 100 to 50 sync prunes old read history while preserving unread and starred articles", async () => {
  await sync();
  settingsState.set({ ...settingsState.get(), recentCacheLimit: "50" });
  requests = [];
  const result = await sync();
  assert.equal(result.initialSync, false);
  assert.equal(result.recentCacheLimit, 50);
  assert.equal(result.recentCachedCount, 50);
  assert.deepEqual(await cachedIds(), expectedIds(120, 50));
  assert.equal(await getArticleById(30), null);
  assert.equal((await getArticleById(71)).content, "<p>本地可读正文 71</p>");
  assert.equal((await getArticleById(2)).status, "unread");
  assert.equal((await getArticleById(3)).starred, 1);
  assert.equal(recentRequests().length, 1);
  assert.equal(recentRequests()[0].searchParams.get("limit"), "50");
  assert.equal(isSyncing.get(), false);
});

test("turning the recent window off preserves existing history and skips its network phase", async () => {
  await sync();
  const before = await cachedIds();
  settingsState.set({ ...settingsState.get(), recentCacheLimit: "0" });
  requests = [];
  const result = await sync();
  assert.equal(result.recentCacheLimit, 0);
  assert.equal(result.recentCachedCount, 0);
  assert.equal(recentRequests().length, 0);
  assert.deepEqual(await cachedIds(), before);
  assert.equal((await getArticleById(30)).content, "<p>本地可读正文 30</p>");
  assert.equal(boundary.preparations.at(-1).recentEntryIds, undefined);
  assert.equal(boundary.commits.at(-1).recentEntryIds, undefined);
});

test("a recent page failure aborts all staged additions and keeps the prior root and sync checkpoint", async () => {
  serverRows = entries(400);
  await sync();
  const beforeRoot = clone(values.get(ROOT_KEY));
  const beforeIds = await cachedIds();
  const beforeSync = getLastSyncTime().toISOString();
  const beforeLastSync = lastSync.get().toISOString();
  const commits = boundary.commits.length;
  settingsState.set({ ...settingsState.get(), recentCacheLimit: "500" });
  requests = [];
  interceptRequest = (url) => url.searchParams.getAll("status").length === 2 && url.searchParams.has("before_entry_id")
    ? { status: 500, headers: {}, body: "" } : undefined;
  await assert.rejects(sync(), /HTTP 500/);
  assert.equal(recentRequests().length, 2, "the first complete recent page was staged before the failure");
  assert.equal(boundary.commits.length, commits, "a failed collection does not reach cache commit");
  assert.deepEqual(values.get(ROOT_KEY), beforeRoot);
  assert.deepEqual(await cachedIds(), beforeIds);
  assert.equal(await getArticleById(250), null, "a newly staged older body did not leak into the readable snapshot");
  assert.equal((await getArticleById(350)).content, "<p>本地可读正文 350</p>");
  assert.equal(getLastSyncTime().toISOString(), beforeSync);
  assert.equal(lastSync.get().toISOString(), beforeLastSync);
  assert.equal(toastErrors.mock.callCount(), 1);
  assert.equal(isSyncing.get(), false);
  // Aborting also releases the staging token, so a later successful sync works.
  interceptRequest = null;
  const recovered = await sync();
  assert.equal(recovered.recentCachedCount, 400);
  assert.equal((await getArticleById(250)).content, "<p>本地可读正文 250</p>");
});

test("one sync captures its setting before requests and applies a mid-flight change only next time", async () => {
  serverRows = entries(700);
  settingsState.set({ ...settingsState.get(), recentCacheLimit: "500" });
  let changed = false;
  interceptRequest = () => {
    if (changed) return;
    changed = true;
    settingsState.set({ ...settingsState.get(), recentCacheLimit: "50" });
  };
  const first = await sync();
  assert.equal(first.recentCacheLimit, 500);
  assert.equal(first.recentCachedCount, 500);
  assert.equal(settingsState.get().recentCacheLimit, "50");
  assert.deepEqual(recentRequests().map((url) => Number(url.searchParams.get("limit"))), [200, 200, 100]);
  assert.deepEqual(await cachedIds(), expectedIds(700, 500));
  requests = [];
  interceptRequest = null;
  const second = await sync();
  assert.equal(second.recentCacheLimit, 50);
  assert.deepEqual(await cachedIds(), expectedIds(700, 50));
});

test("sync protects the active article at preparation and rechecks a newly opened article at commit", async () => {
  await sync();
  settingsState.set({ ...settingsState.get(), recentCacheLimit: "50" });
  activeArticle.set(await getArticleById(30));
  let switched = false;
  boundary.afterPreparation = async () => {
    if (switched) return;
    switched = true;
    activeArticle.set(await getArticleById(31));
  };
  const result = await sync("background");
  assert.equal(result.outcome, "committed");
  assert.deepEqual(boundary.preparations.at(-1).protectedArticleIds, [30]);
  assert.deepEqual(boundary.commits.at(-1).protectedArticleIds, [31]);
  assert.deepEqual(await cachedIds(), expectedIds(120, 50, [30, 31]));
  assert.equal((await getArticleById(30)).content, "<p>本地可读正文 30</p>");
  assert.equal((await getArticleById(31)).content, "<p>本地可读正文 31</p>");
  assert.equal(await getArticleById(32), null, "unprotected old history is still pruned");
  assert.equal(activeArticle.get().id, 31);
  assert.equal(articleContentRevision.get()[31], undefined, "cache retention does not invalidate the visible body");
});

test("an article route still loading is protected before its activeArticle body is ready", async () => {
  await sync();
  settingsState.set({ ...settingsState.get(), recentCacheLimit: "50" });
  activeArticle.set(await getArticleById(30));
  // Navigation has selected 31, but asynchronous loading still displays 30.
  window.location.hash = "#/all/article/31";
  boundary.afterPreparation = () => {
    // Another navigation lands between candidate preparation and final commit.
    window.location.hash = "#/all/article/32?from=offline-cache";
  };
  await sync("background");
  assert.deepEqual(boundary.preparations.at(-1).protectedArticleIds, [30, 31]);
  assert.deepEqual(boundary.commits.at(-1).protectedArticleIds, [30, 32]);
  assert.deepEqual(await cachedIds(), expectedIds(120, 50, [30, 31, 32]));
  assert.equal(activeArticle.get().id, 30, "the route is protected without waiting for activeArticle to change");
  assert.equal((await getArticleById(32)).content, "<p>本地可读正文 32</p>", "the pending destination can finish loading after commit");
  assert.equal(await getArticleById(33), null);
});
