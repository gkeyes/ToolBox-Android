import test from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const fakeApi = {};
const data = new Map();
const secrets = new Map();
const writes = [];
let rejectManifest = false;
const storage = {
  get: async (key) => structuredClone(data.get(key) ?? null),
  async set(key, value) {
    if (rejectManifest && key.endsWith("manifest")) throw new Error("cache write failed");
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
const bundle = await build({
  absWorkingDir: root,
  stdin: { contents: `
    export * as sync from './src/stores/syncStore.js';
    export * as articles from './src/stores/articlesStore.js';
    export * as auth from './src/stores/authStore.js';
    export * as db from './src/db/storage.js';
    export * as feeds from './src/stores/feedsStore.js';
  `, resolveDir: root },
  bundle: true, write: false, format: "esm", platform: "node",
  alias: { "@": `${root}src` },
  plugins: [{ name: "mock-network-only", setup(plugin) {
    plugin.onResolve({ filter: /(?:^|\/)api\/miniflux(?:\.js)?$/ }, () => ({ path: "miniflux", namespace: "mock-api" }));
    plugin.onLoad({ filter: /.*/, namespace: "mock-api" }, () => ({ contents: "export default globalThis.__nextfluxSyncTestApi;" }));
    plugin.onResolve({ filter: /^sonner$/ }, () => ({ path: "sonner", namespace: "mock-toast" }));
    plugin.onLoad({ filter: /.*/, namespace: "mock-toast" }, () => ({ contents: "export const toast = { error() {} };" }));
  } }],
});
const { sync, articles, auth, db, feeds } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString("base64")}`);
await db.initializeArticleCache();
const entry = (id, status = "unread") => ({ id, feed: { id: 1 }, title: `Article ${id}`, content: "正文", status, starred: false, published_at: `2026-09-${String(id).padStart(2, "0")}` });
const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function reset() {
  await sync.cancelAccountOperations();
  await db.clearArticleCache();
  sync.isOnline.set(true);
  navigator.onLine = true;
  auth.authState.set({ userId: 1, token: "fixture", authType: "token" });
  articles.filteredArticles.set([]);
  articles.activeArticle.set(null);
  feeds.unreadCounts.set({});
  feeds.starredCounts.set({});
  rejectManifest = false;
  writes.length = 0;
  for (const key of Object.keys(fakeApi)) delete fakeApi[key];
  Object.assign(fakeApi, {
    getFeeds: async () => [{ id: 1, title: "Source", category: { id: 10 } }],
    getCategories: async () => [{ id: 10, title: "Category" }],
    getUnreadEntriesByPage: async () => ({ total: 1, entries: [entry(1)] }),
    getAllStarredEntries: async () => [],
    getChangedEntries: async () => [],
    getNewEntries: async () => [],
    updateEntryStatus: async () => {},
    updateEntryStarred: async () => {},
    updateEntriesStatus: async () => {},
    markAllAsRead: async () => {},
  });
}

// One serial scenario uses real production stores and persistence; only the API
// and toast delivery are substituted so controlled races are reproducible.
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
  assert.equal(writes.filter((key) => key.endsWith("manifest")).length, 1, "all pages plus checkpoint commit once");
  const previous = db.getLastSyncTime().toISOString();
  assert.equal(await db.getArticlesCount([1]), 3);
  fakeApi.getChangedEntries = async () => [entry(4)];
  fakeApi.getNewEntries = async () => { throw new Error("page failed"); };
  await assert.rejects(sync.sync(), /page failed/);
  assert.equal(db.getLastSyncTime().toISOString(), previous);
  assert.equal(await db.getArticleById(4), null);
  fakeApi.getNewEntries = async () => [];
  rejectManifest = true;
  await assert.rejects(sync.sync(), /cache write failed/);
  assert.equal(db.getLastSyncTime().toISOString(), previous);
  rejectManifest = false;
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
  assert.equal([...data.keys()].some((key) => key.startsWith("nextflux.cache.")), false);
});

test("read/bookmark operations never write cache on offline or rejected API and keep counts ordered", async () => {
  await reset();
  await sync.sync();
  const original = await db.getArticleById(1);
  articles.filteredArticles.set([original]);
  articles.activeArticle.set(original);
  navigator.onLine = false;
  await assert.rejects(articles.updateArticleStatus(original), /离线/);
  assert.equal((await db.getArticleById(1)).status, "unread");
  navigator.onLine = true;
  fakeApi.updateEntryStarred = async () => { throw new Error("API rejected"); };
  await assert.rejects(articles.updateArticleStarred(original), /API rejected/);
  assert.equal((await db.getArticleById(1)).starred, 0);
  assert.equal(articles.filteredArticles.get()[0].starred, 0);
  const gate = deferred();
  fakeApi.updateEntryStatus = () => gate.promise;
  const changing = articles.updateArticleStatus(original);
  await tick();
  assert.equal((await db.getArticleById(1)).status, "unread", "no write before acknowledgement");
  gate.resolve();
  await changing;
  assert.equal((await db.getArticleById(1)).status, "read");
  assert.equal(feeds.unreadCounts.get()[1], 0);
  assert.equal(articles.activeArticle.get().status, "read");
  fakeApi.updateEntryStatus = async () => {};
  rejectManifest = true;
  await assert.rejects(articles.updateArticleStatus(original), /服务器已保存.*缓存保存失败/);
  assert.equal((await db.getArticleById(1)).status, "read");
  rejectManifest = false;
});

test("mark all includes offscreen cached articles and range mutation uses one server batch", async () => {
  await reset();
  fakeApi.getUnreadEntriesByPage = async () => ({ total: 3, entries: [entry(1), entry(2), entry(3)] });
  await sync.sync();
  const all = await db.getCachedArticles();
  articles.filteredArticles.set(all.slice(0, 1));
  await articles.markAllAsRead("feed", 1);
  assert.equal(await db.getUnreadCount(1), 0);
  assert.equal((await db.getArticleById(3)).status, "read");
  await db.addArticles(all);
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
    assert.equal(await db.getFeedIcon(1), undefined);
    assert.deepEqual(feeds.feeds.get(), []);
    assert.deepEqual(feeds.categories.get(), []);
    assert.equal([...data.keys()].some((key) => key.startsWith("nextflux.cache.")), false);
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
  rejectManifest = true;
  await assert.rejects(sync.removeCachedFeed(1), /cache write failed/);
  assert.equal((await db.getFeeds()).length, 1);
  assert.equal(await db.getArticlesCount([1]), 1);
  assert.ok(await db.getFeedIcon(1));
  rejectManifest = false;
  writes.length = 0;
  await sync.removeCachedFeed(1);
  assert.equal(writes.filter((key) => key.endsWith("manifest")).length, 1);
  assert.deepEqual(await db.getFeeds(), []);
  assert.equal(await db.getArticlesCount([1]), 0);
  assert.equal(await db.getFeedIcon(1), undefined);
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
