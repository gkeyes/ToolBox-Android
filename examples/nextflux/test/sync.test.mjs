import test from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const fakeApi = {};
const data = new Map();
const secrets = new Map();
const writes = [];
const CACHE_ROOT = "nextflux.cache.v3.root";
let rejectRoot = false;
let beforeApply;
const storage = {
  get: async (key) => structuredClone(data.get(key) ?? null),
  getMany: async (keys) => {
    assert.ok(keys.length <= 256, "ordinary storage reads remain bounded");
    return keys.map((key) => structuredClone(data.get(key) ?? null));
  },
  async apply({ set = [], remove = [] } = {}) {
    const keys = [...set.map(({ key }) => key), ...remove];
    assert.ok(keys.length <= 256, "ordinary storage batches remain bounded");
    assert.equal(new Set(keys).size, keys.length, "a transaction cannot write or remove a key twice");
    await beforeApply?.({ set, remove });
    if (rejectRoot && set.some(({ key }) => key === CACHE_ROOT)) throw new Error("cache write failed");
    const next = new Map(data);
    for (const { key, value } of set) next.set(key, structuredClone(value));
    for (const key of remove) next.delete(key);
    // No await between the map swap's writes: readers see one complete batch.
    data.clear();
    for (const [key, value] of next) data.set(key, value);
    writes.push(...set.map(({ key }) => key));
  },
  async set(key, value) {
    if (rejectRoot && key === CACHE_ROOT) throw new Error("cache write failed");
    writes.push(key);
    data.set(key, structuredClone(value));
  },
  remove: async (key) => { data.delete(key); },
  keys: async () => [...data.keys()],
  secure: {
    get: async (key) => secrets.get(key) ?? null,
    set: async (key, value) => { secrets.set(key, value); },
    remove: async (key) => { secrets.delete(key); },
  },
};
Object.defineProperty(globalThis, "navigator", { configurable: true, value: { onLine: true } });
globalThis.window = { addEventListener() {}, removeEventListener() {}, ToolBox: { storage } };
globalThis.ToolBox = window.ToolBox;
globalThis.__nextfluxSyncTestApi = fakeApi;
globalThis.__nextfluxCacheResponseHook = null;
const bundle = await build({
  absWorkingDir: root,
  stdin: { contents: `
    export * as sync from './src/stores/syncStore.js';
    export * as articles from './src/stores/articlesStore.js';
    export * as auth from './src/stores/authStore.js';
    export * as db from './src/db/storage.js';
    export * as feeds from './src/stores/feedsStore.js';
    export * as settings from './src/stores/settingsStore.js';
  `, resolveDir: root },
  bundle: true, write: false, format: "esm", platform: "node",
  alias: { "@": `${root}src` },
  plugins: [{ name: "mock-network-and-worker-boundary", setup(plugin) {
    // Run the production cache engine in Node. The hook pauses its response at
    // the worker boundary; production still requires the dedicated Worker.
    plugin.onResolve({ filter: /(?:^|\/)toolbox\/cache-client\.js$/ }, () => ({ path: "cache-client", namespace: "test-cache-client" }));
    plugin.onLoad({ filter: /.*/, namespace: "test-cache-client" }, () => ({
      resolveDir: root,
      contents: `
        import { createArticleCache } from './src/toolbox/cache.js';
        export function createWorkerArticleCache(storage) {
          const cache = createArticleCache(storage);
          return new Proxy(cache, { get(target, property) {
            const method = Reflect.get(target, property);
            if (typeof method !== 'function') return method;
            return async (...args) => {
              const result = await method.apply(target, args);
              await globalThis.__nextfluxCacheResponseHook?.(property, args, result);
              return result;
            };
          } });
        }
      `,
    }));
    plugin.onResolve({ filter: /(?:^|\/)api\/miniflux(?:\.js)?$/ }, () => ({ path: "miniflux", namespace: "mock-api" }));
    plugin.onLoad({ filter: /.*/, namespace: "mock-api" }, () => ({ contents: "export default globalThis.__nextfluxSyncTestApi;" }));
    plugin.onResolve({ filter: /^sonner$/ }, () => ({ path: "sonner", namespace: "mock-toast" }));
    plugin.onLoad({ filter: /.*/, namespace: "mock-toast" }, () => ({ contents: "export const toast = { error() {} };" }));
  } }],
});
const { sync, articles, auth, db, feeds, settings } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString("base64")}`);
await db.initializeArticleCache();
const entry = (id, status = "unread") => ({ id, feed: { id: 1 }, title: `Article ${id}`, content: "正文", status, starred: false, published_at: new Date(Date.UTC(2026, 8, 9, 0, 0, id)).toISOString() });
const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function assertLoggedOutCache() {
  assert.deepEqual([...data.keys()].filter((key) => key.startsWith("nextflux.cache.")), [CACHE_ROOT],
    "logout removes article shards and legacy entrypoints, retaining only the empty v3 marker");
  const marker = await storage.get(CACHE_ROOT);
  assert.equal(marker.version, 3);
  assert.equal(marker.cleared, true);
  assert.equal(marker.account, null);
  assert.equal(marker.lastSyncTime, null);
  assert.ok(Object.values(marker.tables).every((value) => value === null));
}
async function reset() {
  rejectRoot = false;
  beforeApply = undefined;
  globalThis.__nextfluxCacheResponseHook = null;
  await sync.cancelAccountOperations();
  await db.clearArticleCache();
  sync.isOnline.set(true);
  navigator.onLine = true;
  auth.authState.set({ serverUrl: "https://miniflux.xiaochen.win", userId: 1, token: "fixture", authType: "token" });
  articles.resetArticleState();
  articles.filter.set("all");
  articles.pageSize.set(30);
  settings.settingsState.set({ ...settings.settingsState.get(), sortDirection: "asc", sortField: "published_at", showHiddenFeeds: false });
  feeds.unreadCounts.set({});
  feeds.starredCounts.set({});
  writes.length = 0;
  for (const key of Object.keys(fakeApi)) delete fakeApi[key];
  Object.assign(fakeApi, {
    getFeeds: async () => [{ id: 1, title: "Source", category: { id: 10 } }],
    getCategories: async () => [{ id: 10, title: "Category" }],
    getUnreadEntriesByPage: async () => ({ total: 1, entries: [entry(1)] }),
    getAllStarredEntries: async () => [],
    getChangedEntries: async () => [],
    getNewEntries: async () => [],
    updateEntryStarred: async () => {},
    updateEntriesStatus: async () => {},
    markAllAsRead: async () => {},
  });
}

// The scenarios use real production stores and cache persistence. Network/toast
// delivery and the Worker transport boundary are substituted for controlled races.
test("sync commits pages once, preserves checkpoint on failure, and cancels in-flight logout", async () => {
  await reset();
  const calls = [];
  fakeApi.getUnreadEntriesByPage = async (offset) => {
    calls.push(offset);
    return { total: 3, entries: [entry(offset + 1)] };
  };
  const first = sync.sync();
  assert.equal(sync.sync(), first, "overlapping refreshes join the same work");
  await first;
  assert.deepEqual(calls, [0, 1, 2]);
  assert.equal(writes.filter((key) => key === CACHE_ROOT).length, 1, "all pages plus checkpoint commit once");
  const previous = db.getLastSyncTime().toISOString();
  const previousRoot = structuredClone(data.get(CACHE_ROOT));
  assert.equal(await db.getArticlesCount([1]), 3);
  fakeApi.getChangedEntries = async () => [entry(4)];
  fakeApi.getNewEntries = async () => { throw new Error("page failed"); };
  await assert.rejects(sync.sync(), /page failed/);
  assert.equal(db.getLastSyncTime().toISOString(), previous);
  assert.deepEqual(data.get(CACHE_ROOT), previousRoot);
  assert.equal(await db.getArticleById(4), null);
  fakeApi.getNewEntries = async () => [];
  rejectRoot = true;
  await assert.rejects(sync.sync(), /cache write failed/);
  assert.equal(db.getLastSyncTime().toISOString(), previous);
  assert.deepEqual(data.get(CACHE_ROOT), previousRoot);
  assert.equal(await db.getArticleById(4), null);
  rejectRoot = false;
  const gate = deferred();
  fakeApi.getChangedEntries = () => gate.promise;
  const running = sync.sync();
  const runningFailure = assert.rejects(running, /登录状态已改变/);
  await tick();
  const loggingOut = auth.logout();
  await tick();
  gate.resolve([entry(5)]);
  await runningFailure;
  await loggingOut;
  assert.equal(auth.authState.get().userId, "");
  assert.equal(db.getLastSyncTime(), null);
  assert.equal(await db.getArticlesCount([1]), 0);
  await assertLoggedOutCache();
});

test("read/bookmark operations never write cache on offline or rejected API and keep counts ordered", async () => {
  await reset();
  await sync.sync();
  const original = await db.getArticleById(1);
  articles.filteredArticles.set([original]);
  articles.activeArticle.set(original);
  const beforeRejectedOperations = writes.length;
  navigator.onLine = false;
  await assert.rejects(articles.updateArticleStatus(original), /离线/);
  assert.equal((await db.getArticleById(1)).status, "unread");
  assert.equal(writes.length, beforeRejectedOperations);
  navigator.onLine = true;
  fakeApi.updateEntryStarred = async () => { throw new Error("API rejected"); };
  await assert.rejects(articles.updateArticleStarred(original), /API rejected/);
  assert.equal((await db.getArticleById(1)).starred, 0);
  assert.equal(articles.filteredArticles.get()[0].starred, 0);
  fakeApi.updateEntriesStatus = async () => { throw new Error("status rejected"); };
  await assert.rejects(articles.updateArticleStatus(original), /status rejected/);
  assert.equal((await db.getArticleById(1)).status, "unread");
  assert.equal(writes.length, beforeRejectedOperations, "offline and rejected API operations cannot write storage");
  const gate = deferred();
  fakeApi.updateEntriesStatus = () => gate.promise;
  const writesBeforeAcknowledgement = writes.length;
  const changing = articles.updateArticleStatus(original);
  await tick();
  assert.equal((await db.getArticleById(1)).status, "unread", "no write before acknowledgement");
  assert.equal(writes.length, writesBeforeAcknowledgement, "no storage write before acknowledgement");
  gate.resolve();
  await changing;
  assert.equal((await db.getArticleById(1)).status, "read");
  assert.equal(feeds.unreadCounts.get()[1], 0);
  assert.equal(articles.activeArticle.get().status, "read");
  fakeApi.updateEntriesStatus = async () => {};
  rejectRoot = true;
  await assert.rejects(articles.updateArticleStatus(original), /服务器已保存.*缓存保存失败/);
  assert.equal((await db.getArticleById(1)).status, "read");
  rejectRoot = false;
});

test("mark all includes offscreen cached articles and range mutation uses one server batch", async () => {
  await reset();
  fakeApi.getUnreadEntriesByPage = async () => ({ total: 3, entries: [entry(1), entry(2), entry(3)] });
  await sync.sync();
  const all = (await db.getCachedArticleMetadata()).sort((left, right) => left.id - right.id);
  articles.filteredArticles.set(all.slice(0, 1));
  await articles.markAllAsRead("feed", 1);
  assert.equal(await db.getUnreadCount(1), 0);
  assert.equal((await db.getArticleById(3)).status, "read");
  await db.patchArticleState(all.map(({ id }) => ({ id, status: "unread" })));
  articles.filteredArticles.set(all);
  const batches = [];
  fakeApi.updateEntriesStatus = async (ids, status) => { batches.push({ ids, status }); };
  await articles.markAboveAsRead(2);
  assert.deepEqual(batches, [{ ids: [1, 2], status: "read" }]);
  assert.equal(await db.getUnreadCount(1), 1);
  assert.equal((await db.getArticleById(3)).status, "unread");
});

test("category, unsubscribe, and icon responses cannot mutate cache or stores after logout", async () => {
  const cases = [
    { method: "createCategory", run: () => sync.createCachedCategory("new"), result: { id: 2, title: "new" } },
    { method: "updateCategory", run: () => sync.renameCachedCategory(10, "renamed"), result: undefined },
    { method: "deleteFeed", run: () => sync.removeCachedFeed(1), result: undefined },
    { method: "getIconByFeedId", run: () => sync.loadAccountFeedIcon(1), result: { data: "image/png;base64,aGVsbG8=" } },
  ];
  for (const scenario of cases) {
    await reset();
    await sync.sync();
    feeds.feeds.set(await db.getFeeds());
    feeds.categories.set(await db.getCategories());
    const gate = deferred();
    let calls = 0;
    fakeApi[scenario.method] = () => { calls += 1; return gate.promise; };
    const pending = scenario.run();
    const cancelled = assert.rejects(pending, (failure) => failure.code === "ACCOUNT_CHANGED");
    await tick();
    assert.equal(calls, 1, `${scenario.method} really reached its pending API call`);
    const loggingOut = auth.logout();
    await tick();
    gate.resolve(scenario.result);
    await cancelled;
    await loggingOut;
    assert.deepEqual(await db.getFeeds(), []);
    assert.deepEqual(await db.getCategories(), []);
    assert.equal(await db.getFeedIcon(1), null);
    assert.deepEqual(feeds.feeds.get(), []);
    assert.deepEqual(feeds.categories.get(), []);
    await assertLoggedOutCache();
  }
});

test("offline icons use cache and unsubscribe commits feed, articles, and icon atomically", async () => {
  await reset();
  await sync.sync();
  await db.setFeedIcon({ feedId: 1, data: "image/png;base64,aGVsbG8=" });
  navigator.onLine = false;
  fakeApi.getIconByFeedId = async () => { throw new Error("offline must not request an icon"); };
  assert.equal((await sync.loadAccountFeedIcon(1)).data, "image/png;base64,aGVsbG8=");
  navigator.onLine = true;
  fakeApi.deleteFeed = async () => {};
  rejectRoot = true;
  await assert.rejects(sync.removeCachedFeed(1), /cache write failed/);
  assert.equal((await db.getFeeds()).length, 1);
  assert.equal(await db.getArticlesCount([1]), 1);
  assert.ok(await db.getFeedIcon(1));
  rejectRoot = false;
  writes.length = 0;
  await sync.removeCachedFeed(1);
  assert.equal(writes.filter((key) => key === CACHE_ROOT).length, 1);
  assert.deepEqual(await db.getFeeds(), []);
  assert.equal(await db.getArticlesCount([1]), 0);
  assert.equal(await db.getFeedIcon(1), null);
});


test("sync overlaps independent requests and drains failed work before releasing the account queue", async () => {
  await reset();
  const started = [];
  const gates = [deferred(), deferred(), deferred(), deferred()];
  fakeApi.getFeeds = () => { started.push("feeds"); return gates[0].promise; };
  fakeApi.getCategories = () => { started.push("categories"); return gates[1].promise; };
  fakeApi.getUnreadEntriesByPage = () => { started.push("unread"); return gates[2].promise; };
  fakeApi.getAllStarredEntries = () => { started.push("starred"); return gates[3].promise; };
  const initial = sync.sync();
  await tick();
  assert.deepEqual(new Set(started), new Set(["feeds", "categories", "unread", "starred"]),
    "all four requests start without waiting for an earlier response");
  assert.equal(db.getLastSyncTime(), null);
  gates[3].resolve([entry(2, "read")]);
  gates[2].resolve({ total: 1, entries: [entry(1)] });
  gates[1].resolve([{ id: 10, title: "Category" }]);
  gates[0].resolve([{ id: 1, title: "Source", category: { id: 10 } }]);
  await initial;
  assert.equal(await db.getArticlesCount([1]), 2);
  const checkpoint = db.getLastSyncTime().toISOString();
  const changedGate = deferred();
  const newGate = deferred();
  const incrementalStarted = [];
  fakeApi.getChangedEntries = () => { incrementalStarted.push("changed"); return changedGate.promise; };
  fakeApi.getNewEntries = () => { incrementalStarted.push("new"); return newGate.promise; };
  const incremental = sync.sync();
  const failure = assert.rejects(incremental, /page failed/);
  let nextOperationStarted = false;
  const queued = sync.runAccountOperation(() => { nextOperationStarted = true; });
  await tick();
  assert.deepEqual(incrementalStarted, ["changed", "new"]);
  newGate.reject(new Error("page failed"));
  await tick();
  assert.equal(nextOperationStarted, false, "the sibling request still owns the account operation");
  changedGate.resolve([entry(3)]);
  await failure;
  await queued;
  assert.equal(nextOperationStarted, true);
  assert.equal(db.getLastSyncTime().toISOString(), checkpoint);
  assert.equal(await db.getArticleById(3), null);
});


test("overlapping article streams keep newer states and never resurrect a newer removal", async () => {
  await reset();
  await sync.sync();
  const old = "2026-09-09T01:00:00Z";
  const recent = "2026-09-09T01:01:00Z";
  const changedGate = deferred();
  const newGate = deferred();
  fakeApi.getChangedEntries = () => changedGate.promise;
  fakeApi.getNewEntries = () => newGate.promise;
  const running = sync.sync();
  await tick();
  newGate.resolve([
    { ...entry(1), changed_at: old },
    { ...entry(2), changed_at: old },
    { ...entry(3, "read"), starred: true, changed_at: recent },
  ]);
  changedGate.resolve([
    { ...entry(1, "read"), starred: true, changed_at: recent },
    { ...entry(2, "removed"), changed_at: recent },
    { ...entry(3), changed_at: old },
  ]);
  await running;
  assert.equal((await db.getArticleById(1)).status, "read");
  assert.equal((await db.getArticleById(1)).starred, 1);
  assert.equal(await db.getArticleById(2), null);
  assert.equal((await db.getArticleById(3)).status, "read");
  assert.equal((await db.getArticleById(3)).starred, 1);
});


test("first sync retains all 18,518 articles beyond the former 6 MiB cap and exposes progress", async () => {
  await reset();
  const total = 18518;
  const pageSizes = [];
  const statuses = [];
  const unlisten = sync.syncProgress.listen((value) => statuses.push(value));
  fakeApi.getUnreadEntriesByPage = async (offset, size) => {
    pageSizes.push(size);
    return { total, entries: Array.from({ length: Math.min(size, total - offset) }, (_, index) => ({
      ...entry(offset + index + 1), published_at: "2026-09-09T00:00:00Z", content: "完整正文📰".repeat(50),
    })) };
  };
  await sync.sync();
  unlisten();
  assert.equal(pageSizes.length, 19);
  assert.ok(pageSizes.every((size) => size === 1000));
  assert.equal(await db.getArticlesCount([1]), total);
  assert.equal((await db.getArticleById(1)).content, "完整正文📰".repeat(50));
  assert.equal((await db.getArticleById(total)).content, "完整正文📰".repeat(50));
  assert.ok(statuses.includes("正在同步文章 · 18518 / 18518"));
  assert.equal(sync.syncProgress.get(), "");
  assert.equal(sync.isSyncing.get(), false);
  assert.ok(db.getLastSyncTime());
});

test("queued feed icons yield to sync and remain invalidated by logout", async () => {
  await reset();
  const gate = deferred();
  const order = [];
  fakeApi.getIconByFeedId = async (id) => {
    order.push(`icon-${id}`);
    if (id === 1) await gate.promise;
    return null;
  };
  const icons = [1, 2, 3].map((id) => sync.loadAccountFeedIcon(id));
  await tick();
  fakeApi.getUnreadEntriesByPage = async () => {
    order.push("sync");
    return { total: 1, entries: [entry(1)] };
  };
  const refresh = sync.sync();
  gate.resolve();
  await refresh;
  await Promise.all(icons);
  assert.deepEqual(order, ["icon-1", "sync", "icon-2", "icon-3"]);

  const pendingIcon = deferred();
  let count = 0;
  fakeApi.getIconByFeedId = async () => { count += 1; return pendingIcon.promise; };
  const first = sync.loadAccountFeedIcon(4);
  const second = sync.loadAccountFeedIcon(5);
  const firstFailure = assert.rejects(first, /登录状态已改变/);
  const secondFailure = assert.rejects(second, /登录状态已改变/);
  await tick();
  const loggingOut = auth.logout();
  await tick();
  pendingIcon.resolve({ mime_type: "image/png", data: "fixture" });
  await Promise.all([firstFailure, secondFailure, loggingOut]);
  assert.equal(count, 1, "queued icon never dispatches after logout");
  assert.equal(await db.getFeedIcon(4), null);
});

test("sync readers keep the committed snapshot while the next root is blocked", { timeout: 10000 }, async (t) => {
  await reset();
  await sync.sync();
  const oldCheckpoint = db.getLastSyncTime().toISOString();
  const oldRoot = structuredClone(data.get(CACHE_ROOT));
  const reachedCommit = deferred();
  const releaseCommit = deferred();
  t.after(() => { beforeApply = undefined; releaseCommit.resolve(); });
  beforeApply = async ({ set }) => {
    if (set.some(({ key }) => key === CACHE_ROOT)) {
      reachedCommit.resolve();
      await releaseCommit.promise;
    }
  };
  fakeApi.getChangedEntries = async (_since, check, onPage) => {
    check();
    await onPage([{ ...entry(1, "read"), content: "更新后的完整正文" }, entry(2)]);
    return undefined;
  };
  const refreshing = sync.sync();
  await reachedCommit.promise;
  try {
    assert.deepEqual(data.get(CACHE_ROOT), oldRoot);
    assert.equal(db.getLastSyncTime().toISOString(), oldCheckpoint);
    assert.equal((await db.getArticleById(1)).content, "正文");
    assert.equal((await db.getArticleById(1)).status, "unread");
    assert.equal(await db.getArticleById(2), null);
    assert.equal(await db.getArticlesCount([1]), 1);
    const visible = await articles.loadArticles(1);
    assert.deepEqual(visible.articles.map(({ id }) => id), [1], "pagination does not wait for the writer");
  } finally {
    beforeApply = undefined;
    releaseCommit.resolve();
  }
  await refreshing;
  assert.equal((await db.getArticleById(1)).content, "更新后的完整正文");
  assert.equal((await db.getArticleById(1)).status, "read");
  assert.equal(await db.getArticlesCount([1]), 2);
});

test("unread pagination keeps its ID sequence when visible and future rows become read", async () => {
  await reset();
  fakeApi.getUnreadEntriesByPage = async () => ({ total: 65, entries: Array.from({ length: 65 }, (_, index) => entry(index + 1)) });
  await sync.sync();
  articles.filter.set("unread");
  const first = await articles.loadArticles(1);
  assert.deepEqual(first.articles.map(({ id }) => id), Array.from({ length: 30 }, (_, index) => index + 1));
  await articles.updateArticleStatus(first.articles[0], "read");
  await articles.updateArticleStatus(await db.getArticleMetadata(31), "read");
  await articles.loadArticles(1, "feed", 2, true);
  await articles.loadArticles(1, "feed", 3, true);
  const listed = articles.filteredArticles.get();
  assert.deepEqual(listed.map(({ id }) => id), Array.from({ length: 65 }, (_, index) => index + 1));
  assert.equal(new Set(listed.map(({ id }) => id)).size, 65);
  assert.equal(listed[0].status, "read");
  assert.equal(listed[30].status, "read", "a later page receives the latest acknowledged state");
  assert.equal(feeds.unreadCounts.get()[1], 63);
  assert.equal(articles.hasMore.get(), false);
});

test("a query skips deleted rows without reordering its remaining pages", async () => {
  await reset();
  fakeApi.getUnreadEntriesByPage = async () => ({ total: 65, entries: Array.from({ length: 65 }, (_, index) => entry(index + 1)) });
  await sync.sync();
  await articles.loadArticles(1);
  await db.patchArticleState([{ id: 31, status: "removed" }]);
  await articles.loadArticles(1, "feed", 2, true);
  await articles.loadArticles(1, "feed", 3, true);
  assert.deepEqual(articles.filteredArticles.get().map(({ id }) => id),
    Array.from({ length: 65 }, (_, index) => index + 1).filter((id) => id !== 31));
  assert.equal(articles.hasMore.get(), false);
});

test("an old filter response cannot replace or append to the new query", { timeout: 10000 }, async (t) => {
  await reset();
  fakeApi.getAllStarredEntries = async () => [{ ...entry(2, "read"), starred: true }];
  await sync.sync();
  const responseReady = deferred();
  const releaseResponse = deferred();
  t.after(() => { globalThis.__nextfluxCacheResponseHook = null; releaseResponse.resolve(); });
  let paused = false;
  globalThis.__nextfluxCacheResponseHook = async (method) => {
    if (method !== "readQueryPage" || paused) return;
    paused = true;
    responseReady.resolve();
    await releaseResponse.promise;
  };
  articles.filter.set("unread");
  const oldQuery = articles.loadArticles(1);
  await responseReady.promise;
  articles.filter.set("starred");
  const newQuery = await articles.loadArticles(1);
  assert.deepEqual(newQuery.articles.map(({ id }) => id), [2]);
  releaseResponse.resolve();
  assert.equal(await oldQuery, null);
  assert.deepEqual(articles.filteredArticles.get().map(({ id }) => id), [2]);
  assert.equal(articles.currentPage.get(), 1);
  assert.equal(articles.hasMore.get(), false);
});

test("automatic read requests target read explicitly and do not toggle it back", async () => {
  await reset();
  await sync.sync();
  const original = await db.getArticleMetadata(1);
  const targets = [];
  fakeApi.updateEntriesStatus = async (ids, status) => { targets.push({ ids, status }); };
  await Promise.all([
    articles.updateArticleStatus(original, "read"),
    articles.updateArticleStatus(original, "read"),
  ]);
  await articles.updateArticleStatus(original, "read");
  assert.deepEqual(targets, [{ ids: [1], status: "read" }]);
  assert.equal((await db.getArticleById(1)).status, "read");
  assert.equal(feeds.unreadCounts.get()[1], 0);
});

test("an article response arriving after logout cannot refill the active article", { timeout: 10000 }, async (t) => {
  await reset();
  await sync.sync();
  articles.activeArticle.set(await db.getArticleMetadata(1));
  const responseReady = deferred();
  const releaseResponse = deferred();
  t.after(() => { globalThis.__nextfluxCacheResponseHook = null; releaseResponse.resolve(); });
  globalThis.__nextfluxCacheResponseHook = async (method) => {
    if (method !== "readArticle") return;
    responseReady.resolve();
    await releaseResponse.promise;
  };
  const reading = db.getArticleById(1).then((article) => articles.activeArticle.set(article));
  const cancelled = assert.rejects(reading, { code: "ACCOUNT_CHANGED" });
  await responseReady.promise;
  await auth.logout();
  assert.equal(articles.activeArticle.get(), null);
  releaseResponse.resolve();
  await cancelled;
  assert.equal(articles.activeArticle.get(), null);
  assert.deepEqual(articles.filteredArticles.get(), []);
  await assertLoggedOutCache();
});
