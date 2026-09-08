import test from "node:test";
import assert from "node:assert/strict";
import { CACHE_LIMITS, CACHE_PREFIX, cacheBytes, createArticleCache, jsonBytes } from "../src/toolbox/cache.js";

function fakeStorage() {
  const data = new Map();
  const secure = new Map();
  let fail = () => false;
  let peak = 0;
  const api = {
    get: async (key) => structuredClone(data.get(key) ?? null),
    async set(key, value) {
      if (fail(key)) throw new Error("simulated write failure");
      assert.ok(jsonBytes({ key, value }) < 8 * 1024 * 1024, "storage RPC fits the declared bridge payload");
      const next = new Map(data).set(key, value);
      const size = [...next.values()].reduce((sum, item) => sum + jsonBytes(item), 0);
      assert.ok(size < 20 * 1024 * 1024, "staging plus active cache stays within the manifest quota");
      peak = Math.max(peak, size);
      data.set(key, structuredClone(value));
    },
    remove: async (key) => { data.delete(key); },
    keys: async () => [...data.keys()],
    secure: {
      get: async (key) => structuredClone(secure.get(key) ?? null),
      set: async (key, value) => { secure.set(key, structuredClone(value)); },
      remove: async (key) => { secure.delete(key); },
    },
  };
  return { api, data, secure, failWhen: (fn) => { fail = fn; }, peak: () => peak };
}

test("cache survives reopen and preserves UTF-16 boundary characters without browser storage", async () => {
  const store = fakeStorage();
  const cache = createArticleCache(store.api);
  const article = { id: 1, feedId: 2, content: "中文📰\\\"".repeat(70000), status: "unread" };
  await cache.transact(["articles", "feeds", "meta"], (s) => {
    s.articles = [article];
    s.feeds = [{ id: 2, title: "测试" }];
    s.meta.lastSyncTime = "2026-09-08T00:00:00.000Z";
  });
  const restored = await createArticleCache(store.api).read();
  assert.deepEqual(restored.articles, [article]);
  assert.equal(restored.feeds[0].id, 2);
  assert.equal(restored.meta.lastSyncTime, "2026-09-08T00:00:00.000Z");
  assert.ok([...store.data.keys()].filter((v) => v.includes("articles.")).length > 1);
});

test("failed staged write retains prior committed cache and a later mutation recovers", async () => {
  const store = fakeStorage();
  const cache = createArticleCache(store.api);
  await cache.transact(["articles"], (s) => { s.articles = [{ id: 1, content: "previous" }]; });
  store.failWhen((key) => key.endsWith("manifest"));
  await assert.rejects(cache.transact(["articles"], (s) => { s.articles = [{ id: 2, content: "uncommitted" }]; }));
  assert.equal((await cache.read()).articles[0].id, 1);
  const reopened = createArticleCache(store.api);
  assert.equal((await reopened.read()).articles[0].id, 1);
  store.failWhen(() => false);
  await reopened.transact(["articles"], (s) => { s.articles.push({ id: 3 }); });
  assert.deepEqual((await reopened.read()).articles.map((v) => v.id), [1, 3]);
  assert.equal([...store.data.keys()].some((v) => v.includes("uncommitted")), false);
});

test("bounded cache evicts oldest articles and keeps both persisted generations inside quota", async () => {
  const store = fakeStorage();
  let evicted = 0;
  const cache = createArticleCache(store.api, (count) => { evicted += count; });
  const articles = Array.from({ length: 130 }, (_, id) => ({
    id, content: "<p>📰\"中文\\</p>".repeat(2000), published_at: String(id).padStart(4, "0"),
  }));
  await cache.transact(["articles"], (s) => { s.articles = articles; });
  const first = (await cache.read()).articles;
  assert.ok(evicted > 0);
  assert.equal(first[0].id, 129);
  assert.ok(cacheBytes(first) <= CACHE_LIMITS.articles);
  await cache.transact(["articles"], (s) => { s.articles[0].status = "read"; });
  assert.ok(store.peak() > CACHE_LIMITS.articles);
  assert.ok(store.peak() < 20 * 1024 * 1024);
  assert.equal((await createArticleCache(store.api).read()).articles[0].status, "read");
});

test("concurrent mutations serialize and logout clears only this cache namespace", async () => {
  const store = fakeStorage();
  await store.api.set("nextflux.preferences.v1", { language: "zh-CN" });
  await store.api.secure.set("auth", "credential");
  const cache = createArticleCache(store.api);
  await Promise.all([1, 2, 3].map((id) => cache.transact(["articles"], (s) => { s.articles.push({ id }); })));
  assert.deepEqual((await cache.read()).articles.map((v) => v.id), [1, 2, 3]);
  await cache.clear();
  assert.deepEqual((await cache.read()).articles, []);
  assert.equal([...store.data.keys()].some((v) => v.startsWith(CACHE_PREFIX)), false);
  assert.deepEqual(await store.api.get("nextflux.preferences.v1"), { language: "zh-CN" });
  assert.equal(await store.api.secure.get("auth"), "credential");
});

test("preferences hydrate in stateless mode and AI secrets only enter secure storage", async () => {
  Object.defineProperty(globalThis, "localStorage", { configurable: true, get() { throw new Error("localStorage disabled"); } });
  Object.defineProperty(globalThis, "indexedDB", { configurable: true, get() { throw new Error("IndexedDB disabled"); } });
  const store = fakeStorage();
  await store.api.set("nextflux.preferences.v1", { settings: JSON.stringify({ fontSize: 19 }), language: "zh-CN" });
  await store.api.secure.set("nextflux.ai-key.v1", "test-private-ai-key");
  const prefs = await import("../src/toolbox/preferences.js");
  await prefs.initializePreferences({ storage: store.api });
  const { settingsState, updateSettings } = await import("../src/stores/settingsStore.js");
  assert.equal(settingsState.get().fontSize, 19);
  assert.equal(settingsState.get().aiApiKey, "test-private-ai-key");
  updateSettings({ fontSize: 22, aiApiKey: "replacement-private-ai-key" });
  await prefs.flushPreferences();
  const raw = await store.api.get("nextflux.preferences.v1");
  assert.equal(JSON.parse(raw.settings).fontSize, 22);
  assert.equal("aiApiKey" in JSON.parse(raw.settings), false);
  assert.equal(JSON.stringify([...store.data.values()]).includes("private-ai-key"), false);
  assert.equal(await store.api.secure.get("nextflux.ai-key.v1"), "replacement-private-ai-key");
  store.failWhen((key) => key === "nextflux.preferences.v1");
  updateSettings({ fontSize: 24 });
  await assert.rejects(prefs.flushPreferences(), /simulated write failure/);
  delete globalThis.localStorage;
  delete globalThis.indexedDB;
});

test("storage queries reflect read/starred mutations and exclude removed or hidden feed articles", async () => {
  const store = fakeStorage();
  globalThis.ToolBox = { storage: store.api };
  const db = await import("../src/db/storage.js");
  await db.initializeArticleCache();
  await db.addFeeds([{ id: 1, title: "visible" }, { id: 2, hide_globally: true }]);
  await db.addArticles([
    { id: 1, feedId: 1, title: "Match older", status: "unread", starred: 0, published_at: "2026-09-07" },
    { id: 2, feedId: 1, title: "Match newer", status: "unread", starred: 1, published_at: "2026-09-08" },
    { id: 3, feedId: 2, title: "Match hidden", status: "read", starred: 0, published_at: "2026-09-09" },
  ]);
  assert.equal(await db.getUnreadCount(1), 2);
  assert.equal(await db.getStarredCount(1), 1);
  assert.deepEqual((await db.getArticlesByPage([1], "all", 1, 1)).map((v) => v.id), [2]);
  assert.deepEqual((await db.searchArticles("match")).map((v) => v.id), [2, 1]);
  assert.equal((await db.getArticleById(2)).feed.title, "visible");
  const article = await db.getArticleById(2);
  await db.addArticles([{ ...article, status: "read", starred: 0 }, { id: 1, status: "removed" }]);
  assert.equal(await db.getUnreadCount(1), 0);
  assert.equal(await db.getStarredCount(1), 0);
  assert.equal(await db.getArticlesCount([1]), 1);
  await db.setLastSyncTime(new Date("2026-09-08T00:00:00.000Z"));
  assert.equal(db.getLastSyncTime().toISOString(), "2026-09-08T00:00:00.000Z");
  await db.clearArticleCache();
  assert.equal(db.getLastSyncTime(), null);
  delete globalThis.ToolBox;
});
