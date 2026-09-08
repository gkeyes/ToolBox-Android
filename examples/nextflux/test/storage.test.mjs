import test from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { CACHE_PREFIX, createArticleCache, jsonBytes } from "../src/toolbox/cache.js";

function fakeStorage() {
  const data = new Map();
  const secure = new Map();
  let fail = () => false;
  let peak = 0;
  let used = 0;
  const sizes = new Map();
  const api = {
    get: async (key) => structuredClone(data.get(key) ?? null),
    async set(key, value) {
      if (fail(key)) throw new Error("simulated write failure");
      assert.ok(jsonBytes({ key, value }) < 8 * 1024 * 1024, "storage RPC fits the declared bridge payload");
      const itemBytes = jsonBytes(value);
      const size = used - (sizes.get(key) || 0) + itemBytes;
      peak = Math.max(peak, size);
      used = size;
      sizes.set(key, itemBytes);
      data.set(key, structuredClone(value));
    },
    remove: async (key) => {
      used -= sizes.get(key) || 0;
      sizes.delete(key);
      data.delete(key);
    },
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
  assert.equal(store.data.get(`${CACHE_PREFIX}manifest`).tables.articles.codec, "gzip-base64");
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

test("all 18518 articles exceeding 6 MiB survive compression, update and reopen without truncation", async () => {
  const store = fakeStorage();
  const cache = createArticleCache(store.api);
  const articles = Array.from({ length: 18518 }, (_, id) => ({
    id, title: `完整文章 ${id}`, feedId: 1, status: "unread",
    content: `<p>📰\"中文\\ ${createHash("sha256").update(String(id)).digest("hex")}</p>`.repeat(12),
    published_at: String(id).padStart(5, "0"),
  }));
  assert.ok(jsonBytes(articles) > 6 * 1024 * 1024);
  await cache.transact(["articles", "meta"], (s) => {
    s.articles = articles;
    s.meta.lastSyncTime = "2026-09-09T00:00:00.000Z";
  });
  assert.deepEqual((await createArticleCache(store.api).read()).articles, articles);
  await cache.transact(["articles"], (s) => { s.articles[0].status = "read"; });
  const expected = structuredClone(articles);
  expected[0].status = "read";
  const reopened = await createArticleCache(store.api).read();
  assert.deepEqual(reopened.articles, expected);
  assert.equal(reopened.meta.lastSyncTime, "2026-09-09T00:00:00.000Z");
});

test("less-compressible archive survives two generations exceeding the old 50 MiB quota", async () => {
  const store = fakeStorage();
  const cache = createArticleCache(store.api);
  // 20 MiB of independent binary payload models high-entropy article contents.
  // It remains above 2 MiB after gzip, with two base64 generations above 50 MiB.
  const articles = Array.from({ length: 128 }, (_, id) => ({
    id, feedId: 1, status: "unread", content: randomBytes(160 * 1024).toString("base64"),
  }));
  await cache.transact(["articles"], (s) => { s.articles = articles; });
  const keys = store.data.get(`${CACHE_PREFIX}manifest`).tables.articles.keys;
  const encodedLength = keys.reduce((total, key) => total + store.data.get(key).length, 0);
  assert.ok(encodedLength * 3 / 4 - 2 > 2 * 1024 * 1024);
  assert.ok(keys.length > 2);
  assert.equal(store.data.get(keys[0]).length, 1024 * 1024);
  assert.ok(keys.every((key) => store.data.get(key).length <= 1024 * 1024));
  await cache.transact(["articles"], (s) => { s.articles[0].status = "read"; });
  assert.ok(store.peak() > 50 * 1024 * 1024);
  const restored = await createArticleCache(store.api).read();
  assert.equal(restored.articles.length, articles.length);
  for (const [index, article] of restored.articles.entries()) {
    assert.equal(article.id, articles[index].id);
    assert.equal(article.content, articles[index].content);
    assert.equal(article.status, index === 0 ? "read" : "unread");
  }
});

test("native storage rate rejection waits one method window and retries the same operation", async () => {
  const store = fakeStorage();
  const limited = new Set(["get", "set", "remove", "keys"]);
  const api = Object.fromEntries([...limited].map((method) => [method, async (...args) => {
    if (limited.delete(method)) throw Object.assign(new Error("method rate limited"), { code: "RATE_LIMITED" });
    return store.api[method](...args);
  }]));
  const originalTimeout = globalThis.setTimeout;
  const waits = [];
  try {
    globalThis.setTimeout = (callback, duration) => {
      waits.push(duration);
      queueMicrotask(callback);
      return 0;
    };
    const cache = createArticleCache(api);
    await cache.transact(["articles"], (s) => { s.articles = [{ id: 1 }]; });
    assert.deepEqual((await createArticleCache(api).read()).articles, [{ id: 1 }]);
    await cache.clear();
    assert.equal(store.data.size, 0);
    assert.deepEqual(waits, [60000, 60000, 60000, 60000]);
    assert.equal(limited.size, 0);
  } finally {
    globalThis.setTimeout = originalTimeout;
  }
});

test("failed compressed shard write preserves articles and checkpoint through reopen", async () => {
  const store = fakeStorage();
  const cache = createArticleCache(store.api);
  await cache.transact(["articles", "meta"], (s) => {
    s.articles = [{ id: 1, content: "original 中文📰" }];
    s.meta.lastSyncTime = "2026-09-08T00:00:00.000Z";
  });
  const original = await cache.read();
  const committedManifest = structuredClone(store.data.get(`${CACHE_PREFIX}manifest`));
  const content = randomBytes(1600000).toString("base64");
  store.failWhen((key) => key === `${CACHE_PREFIX}articles.2.1`);
  await assert.rejects(cache.transact(["articles", "meta"], (s) => {
    s.articles = [{ id: 2, content }];
    s.meta.lastSyncTime = "2026-09-09T00:00:00.000Z";
  }), /simulated write failure/);
  assert.equal(typeof store.data.get(`${CACHE_PREFIX}articles.2.0`), "string");
  assert.deepEqual(store.data.get(`${CACHE_PREFIX}manifest`), committedManifest);
  assert.deepEqual(await cache.read(), original);
  const reopened = createArticleCache(store.api);
  assert.deepEqual(await reopened.read(), original);
  assert.equal(store.data.has(`${CACHE_PREFIX}articles.2.0`), false);
  store.failWhen(() => false);
  await reopened.transact(["articles"], (s) => { s.articles.push({ id: 3, content }); });
  assert.deepEqual((await createArticleCache(store.api).read()).articles, [...original.articles, { id: 3, content }]);
});

test("feeds, categories, icons and metadata have no artificial per-table retention caps", async () => {
  const store = fakeStorage();
  const expected = {
    feeds: [{ id: 1, title: "订阅".repeat(100000) }],
    categories: [{ id: 2, title: "分类".repeat(30000) }],
    feedIcons: [{ feedId: 1, data: "x".repeat(600000) }],
    meta: { lastSyncTime: "2026-09-09T00:00:00.000Z", note: "x".repeat(2048) },
  };
  await createArticleCache(store.api).transact(Object.keys(expected), (s) => { Object.assign(s, expected); });
  const actual = await createArticleCache(store.api).read();
  for (const table of Object.keys(expected)) assert.deepEqual(actual[table], expected[table]);
});

test("legacy v1 remains readable, resets checkpoint, and migrates only after a successful commit", async () => {
  const store = fakeStorage();
  const articles = [{ id: 7, content: "legacy 中文📰" }];
  const articleKey = `${CACHE_PREFIX}articles.4.0`;
  const metaKey = `${CACHE_PREFIX}meta.4.0`;
  const legacy = { generation: 4, tables: { articles: [articleKey], meta: [metaKey] } };
  await store.api.set(articleKey, JSON.stringify(articles));
  await store.api.set(metaKey, JSON.stringify({ lastSyncTime: "2026-09-08T00:00:00.000Z" }));
  await store.api.set(`${CACHE_PREFIX}manifest`, legacy);
  const cache = createArticleCache(store.api);
  const restored = await cache.read();
  assert.deepEqual(restored.articles, articles);
  assert.equal(restored.meta.lastSyncTime, undefined);
  assert.deepEqual(store.data.get(`${CACHE_PREFIX}manifest`), legacy);
  assert.equal(store.data.has(articleKey), true);
  store.failWhen((key) => key === `${CACHE_PREFIX}manifest`);
  await assert.rejects(cache.transact(["feeds"], (s) => { s.feeds = [{ id: 1 }]; }));
  assert.equal(store.data.has(articleKey), true);
  assert.equal(store.data.has(metaKey), true);
  assert.deepEqual((await createArticleCache(store.api).read()).articles, articles);
  store.failWhen(() => false);
  await cache.transact(["feeds"], (s) => { s.feeds = [{ id: 1 }]; });
  assert.equal(store.data.get(`${CACHE_PREFIX}manifest`).version, 2);
  assert.equal(store.data.has(articleKey), false);
  assert.equal(store.data.has(metaKey), false);
  const migrated = await createArticleCache(store.api).read();
  assert.deepEqual(migrated.articles, articles);
  assert.equal(migrated.meta.lastSyncTime, undefined);
  assert.deepEqual(migrated.feeds, [{ id: 1 }]);
});

test("host disk write failure preserves committed rows and their checkpoint", async () => {
  const store = fakeStorage();
  const diskError = Object.assign(new Error("simulated host disk write failure"), { code: "INTERNAL_ERROR" });
  const api = {
    ...store.api,
    async set(key, value) {
      // Fail after the replacement article shard has reached storage, before
      // the new checkpoint can be written or the manifest can switch snapshots.
      if (key === `${CACHE_PREFIX}meta.2.0`) throw diskError;
      return store.api.set(key, value);
    },
  };
  const cache = createArticleCache(api);
  await cache.transact(["articles", "meta"], (s) => {
    s.articles = [{ id: 1, content: "saved" }];
    s.meta.lastSyncTime = "2026-09-08T00:00:00.000Z";
  });
  const before = await cache.read();
  await assert.rejects(cache.transact(["articles", "meta"], (s) => {
    s.articles.push({ id: 2, content: randomBytes(10000).toString("base64") });
    s.meta.lastSyncTime = "2026-09-09T00:00:00.000Z";
  }), (error) => error === diskError);
  assert.equal(store.data.has(`${CACHE_PREFIX}articles.2.0`), true);
  assert.deepEqual(await cache.read(), before);
  assert.deepEqual(await createArticleCache(store.api).read(), before);
});

test("plain JSON fallback preserves Unicode across shard boundaries without compression APIs", async () => {
  const compression = globalThis.CompressionStream;
  const decompression = globalThis.DecompressionStream;
  try {
    globalThis.CompressionStream = undefined;
    globalThis.DecompressionStream = undefined;
    const store = fakeStorage();
    const cache = createArticleCache(store.api);
    const article = { id: 1, content: "中📰\\\"\ud800".repeat(70000) };
    await cache.transact(["articles"], (s) => { s.articles = [article]; });
    const entry = store.data.get(`${CACHE_PREFIX}manifest`).tables.articles;
    assert.equal(entry.codec, "json");
    assert.ok(entry.keys.length > 1);
    for (const key of entry.keys) {
      const shard = store.data.get(key);
      assert.equal(/^[\uDC00-\uDFFF]|[\uD800-\uDBFF]$/.test(shard), false);
    }
    assert.deepEqual((await createArticleCache(store.api).read()).articles, [article]);
  } finally {
    globalThis.CompressionStream = compression;
    globalThis.DecompressionStream = decompression;
  }
});

test("malformed compression and invalid manifest references reject without deleting committed data", async () => {
  for (const corrupt of [
    (data, entry) => { data.set(entry.keys[0], "not base64!"); },
    (data, entry) => { data.set(entry.keys[0], btoa("not a gzip stream")); },
    (data, entry) => { data.set(entry.keys[0], data.get(entry.keys[0]).slice(0, -4)); },
    (_data, entry) => { entry.codec = "unknown"; },
    (_data, entry) => { entry.keys = [`${CACHE_PREFIX}feeds.1.0`]; },
    (_data, entry) => { entry.keys = [`${CACHE_PREFIX}articles.2.0`]; },
    (_data, entry) => { entry.keys.push(entry.keys[0]); },
    (_data, entry) => { entry.keys = "invalid"; },
    (data, entry) => { data.delete(entry.keys[0]); },
    (data, entry) => { entry.codec = "json"; data.set(entry.keys[0], "{}"); },
    (_data, _entry, saved) => { saved.version = "2"; },
    (_data, _entry, saved) => { saved.generation = -1; },
    (_data, _entry, saved) => { saved.tables = []; },
  ]) {
    const store = fakeStorage();
    await createArticleCache(store.api).transact(["articles"], (s) => { s.articles = [{ id: 1 }]; });
    const saved = store.data.get(`${CACHE_PREFIX}manifest`);
    corrupt(store.data, saved.tables.articles, saved);
    const before = structuredClone(store.data);
    await assert.rejects(createArticleCache(store.api).initialize(), /阅读缓存.*请退出登录后重新同步/);
    assert.deepEqual(store.data, before);
  }
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
