import test from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { CACHE_PREFIX, ROOT_KEY, jsonBytes } from "../src/toolbox/cache.js";
import { fakeStorage, engine, seed, article, storedArticles, rootWrite, deferred, tick } from "./cache-fixtures.mjs";

test("startup and page queries restore metadata without reading the body library", async () => {
  const store = fakeStorage();
  await seed(engine(store), [article(1), article(2, { starred: 1 }), article(300)]);
  const bodyRefs = (await storedArticles(store)).map((row) => row.bodyRef);
  store.resetCalls();
  const builds = [];
  const cache = engine(store, { onIndexBuild: (event) => builds.push(event) });
  await cache.initialize();
  const query = await cache.openQuery({ feedIds: [1], filter: "unread", pageSize: 1 });
  assert.deepEqual((await cache.readQueryPage(query.queryId, 1)).items.map((row) => row.id), [300]);
  assert.deepEqual((await cache.readQueryPage(query.queryId, 2)).items.map((row) => row.id), [2]);
  const item = (await cache.readQueryPage(query.queryId, 3)).items[0];
  assert.equal("content" in item, false);
  assert.equal("bodyRef" in item, false);
  assert.equal(item.titleText, "Article 1");
  assert.equal(item.previewText, "文章 1");
  assert.ok(item.bodyDigest);
  await cache.patchState([{ id: 1, status: "read" }]);
  assert.equal((await cache.readQueryPage(query.queryId, 3)).items[0].status, "read");
  assert.equal(builds.filter(({ kind }) => kind === "query").length, 1);
  assert.equal(store.reads.flat().some((key) => bodyRefs.some((ref) => key === ref || key.startsWith(`${ref}.`))), false);
  await cache.closeQuery(query.queryId);
  const nextQuery = await cache.openQuery({ feedIds: [1], filter: "unread" });
  assert.equal(builds.filter(({ kind }) => kind === "query").length, 2);
  await cache.closeQuery(nextQuery.queryId);
  assert.equal((await cache.readArticle(2)).content, article(2).content);
});

test("state patches touch no bodies and update counts without a full scan", async () => {
  const store = fakeStorage(); const scans = [];
  const cache = engine(store, { onIndexBuild: (event) => scans.push(event) });
  await seed(cache, [article(1), article(2, { starred: 1 }), article(256)]);
  const bodies = (await storedArticles(store)).map((row) => row.bodyRef);
  const bodyValues = bodies.map((ref) => structuredClone(store.data.get(ref)));
  const built = scans.length;
  store.resetCalls();
  const result = await cache.patchState([{ id: 1, status: "read", starred: 1 }, { id: 2, starred: 0 },
    { id: 256, status: "read" }, { id: 256, status: "unread" }]);
  assert.deepEqual(result.articles.map((row) => row.id), [1, 2]);
  assert.deepEqual(result.counts, { unread: { 1: 2 }, starred: { 1: 1 } });
  assert.deepEqual(await cache.counts([1]), result.counts);
  assert.equal(scans.length, built);
  assert.equal(store.reads.flat().some((key) => bodies.some((ref) => key.startsWith(ref))), false);
  assert.equal(store.writes.flatMap(({ set }) => set).some(({ key }) => bodies.some((ref) => key.startsWith(ref))), false);
  bodies.forEach((ref, index) => assert.deepEqual(store.data.get(ref), bodyValues[index]));
  const restored = engine(store);
  assert.equal((await restored.readArticle(1)).content, article(1).content);
  assert.equal((await restored.readMetadata(1)).status, "read");
  assert.deepEqual(await restored.counts(), result.counts);
});

test("fixed query IDs avoid paging gaps while changed status is current and removed rows disappear", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [1, 2, 3, 4, 5].map((id) => article(id)));
  const query = await cache.openQuery({ feedIds: [1], filter: "unread", pageSize: 2, direction: "asc" });
  assert.deepEqual((await cache.readQueryPage(query.queryId, 1)).items.map((row) => row.id), [1, 2]);
  await cache.patchState([{ id: 1, status: "read" }, { id: 3, status: "read" }, { id: 4, status: "removed" }]);
  const second = await cache.readQueryPage(query.queryId, 2);
  assert.deepEqual(second.items.map((row) => [row.id, row.status]), [[3, "read"]]);
  assert.equal(second.hasMore, true);
  assert.deepEqual((await cache.readQueryPage(query.queryId, 3)).items.map((row) => row.id), [5]);
  await cache.updateCatalog({ removeFeedIds: [1] });
  assert.deepEqual((await cache.readQueryPage(query.queryId, 1)).items, []);
});

test("reads do not join a blocked root commit or garbage collection", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1)]);
  const query = await cache.openQuery({ feedIds: [1] });
  const entered = deferred(); const release = deferred();
  store.beforeApply(async (change) => { if (rootWrite(change)) { entered.resolve(); await release.promise; } });
  const changing = cache.patchState([{ id: 1, status: "read" }]);
  await entered.promise;
  assert.equal((await cache.readMetadata(1)).status, "unread");
  assert.equal((await cache.readQueryPage(query.queryId)).items[0].status, "unread");
  assert.equal((await cache.readArticle(1)).content, article(1).content);
  release.resolve(); await changing; store.beforeApply(undefined);
  const gcEntered = deferred(); const gcRelease = deferred();
  store.beforeKeys(async () => { gcEntered.resolve(); await gcRelease.promise; });
  const gc = cache.collectGarbage(); await gcEntered.promise;
  assert.equal((await cache.readMetadata(1)).status, "read");
  assert.equal((await cache.readArticle(1)).content, article(1).content);
  gcRelease.resolve(); await gc;
});

test("a body reader pins its old version across sync publication and GC", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1, { content: "old body" })]);
  const [{ bodyRef }] = await storedArticles(store);
  const entered = deferred(); const release = deferred(); let paused = false;
  store.beforeGet(async (keys) => { if (!paused && keys.includes(bodyRef)) { paused = true; entered.resolve(); await release.promise; } });
  const oldReading = cache.readArticle(1); await entered.promise;
  await seed(cache, [article(1, { content: "new body" })]);
  await cache.collectGarbage(); assert.equal(store.data.has(bodyRef), true);
  release.resolve(); assert.equal((await oldReading).content, "old body");
  assert.equal((await cache.readArticle(1)).content, "new body");
  await cache.collectGarbage(); assert.equal(store.data.has(bodyRef), false);
});

test("normal state commits reclaim known old pages without another full keys scan", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1), article(256)]);
  let scans = 0;
  store.beforeKeys(() => { scans += 1; });
  await cache.collectGarbage();
  assert.equal(scans, 1);
  await cache.patchState([{ id: 1, status: "read" }]);
  await cache.collectGarbage();
  assert.equal(scans, 1);
  assert.equal((await cache.readArticle(1)).content, article(1).content);
  await cache.clear();
  assert.equal(scans, 2, "logout performs a full sweep of all cache generations");
  assert.deepEqual([...store.data.keys()], [ROOT_KEY]);
});

test("failed root write retains complete articles and checkpoint and a later sync recovers", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1)]);
  const previous = structuredClone(store.data.get(ROOT_KEY));
  store.failWhen(rootWrite);
  await assert.rejects(seed(cache, [article(2)], { syncedAt: "2026-09-10T00:00:00.000Z" }), /simulated write failure/);
  assert.deepEqual(store.data.get(ROOT_KEY), previous);
  assert.deepEqual(await cache.selectIds(), [1]);
  assert.deepEqual(await engine(store).selectIds(), [1]);
  assert.equal((await cache.meta()).lastSyncTime, previous.lastSyncTime);
  store.failWhen(() => false); await seed(cache, [article(3)]);
  assert.deepEqual(await engine(store).selectIds(), [1, 3]);
});

test("invalid body values reject before publishing an unreadable root", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1)]);
  const root = structuredClone(store.data.get(ROOT_KEY));
  for (const bad of [{ content: {} }, { enclosures: "invalid" }]) {
    await assert.rejects(seed(cache, [article(2, bad)]), { code: "CACHE_INVALID" });
    assert.deepEqual(store.data.get(ROOT_KEY), root);
    assert.deepEqual(await cache.selectIds(), [1]);
  }
});

test("incremental sync reuses bodies, retains newer removal and atomically prunes unsubscribed feeds", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1), article(2, { feedId: 2 }), article(3)], { feeds: [{ id: 1 }, { id: 2 }] });
  const body = (await storedArticles(store)).find((row) => row.id === 1).bodyRef;
  store.resetCalls();
  const token = await cache.prepareSync({ syncedAt: "2026-09-10T00:00:00.000Z" });
  await cache.applySyncBatch(token, [article(1, { status: "read" }), { id: 3, status: "removed", changed_at: "2026-09-10T12:00:00Z" }]);
  await cache.applySyncBatch(token, [article(3, { changed_at: "2026-09-10T11:00:00Z" })]);
  await cache.commitSync(token, { feeds: [{ id: 1 }], categories: [] });
  assert.deepEqual(await cache.selectIds(), [1]);
  assert.equal((await storedArticles(store))[0].bodyRef, body);
  assert.equal(store.writes.flatMap(({ set }) => set).some(({ key }) => key.startsWith(body)), false);
});

test("clear rejects queued mutations, blocks new reads and preserves preferences and secure keys", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1)]);
  await store.api.set("nextflux.preferences.v1", { language: "zh-CN" });
  await store.api.secure.set("auth", "credential");
  const entered = deferred(); const release = deferred();
  store.beforeApply(async ({ set }) => { if (set.some(({ key, value }) => key === ROOT_KEY && value.cleared)) { entered.resolve(); await release.promise; } });
  const queued = cache.patchState([{ id: 1, status: "read" }]);
  const rejected = assert.rejects(queued, (error) => error.code === "ACCOUNT_CHANGED");
  const clearing = cache.clear(); await entered.promise;
  let readFinished = false;
  const reading = cache.readMetadata(1).then((value) => { readFinished = true; return value; });
  await tick(); assert.equal(readFinished, false);
  release.resolve(); await clearing; await rejected;
  assert.equal(await reading, null);
  assert.deepEqual([...store.data.keys()].filter((key) => key.startsWith("nextflux.cache.")), [ROOT_KEY]);
  assert.equal(store.data.get(ROOT_KEY).cleared, true);
  assert.equal(store.data.get(ROOT_KEY).lastSyncTime, null);
  assert.deepEqual(await store.api.get("nextflux.preferences.v1"), { language: "zh-CN" });
  assert.equal(await store.api.secure.get("auth"), "credential");
});

test("an empty cache binds login while a different account cannot display its data", async () => {
  const store = fakeStorage(); const cache = engine(store);
  const account = { serverUrl: "https://example.com", userId: "1" };
  await cache.initialize(null); await cache.initialize(account);
  await seed(cache, [article(1)], { account });
  await assert.rejects(cache.initialize({ ...account, userId: "2" }), (error) => error.code === "ACCOUNT_CHANGED");
  await assert.rejects(engine(store).initialize({ ...account, userId: "2" }), (error) => error.code === "ACCOUNT_CHANGED");
  await cache.clear(); await cache.initialize({ ...account, userId: "2" });
  assert.deepEqual(await cache.selectIds(), []);
});

test("aborted staged writes are reclaimed after the initial orphan sweep", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1)]);
  await cache.collectGarbage();
  const committed = new Map(store.data);
  const token = await cache.prepareSync({ syncedAt: "2026-09-10T00:00:00.000Z" });
  await cache.applySyncBatch(token, [article(2)]);
  assert.ok(store.data.size > committed.size);
  await cache.abortSync(token);
  await cache.collectGarbage();
  assert.deepEqual(store.data, committed);
});

test("logout reclaims a pinned old body after its late read is invalidated", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1)]);
  await cache.collectGarbage();
  const [{ bodyRef }] = await storedArticles(store);
  const entered = deferred(); const release = deferred();
  store.beforeGet(async (keys) => { if (keys.includes(bodyRef)) { entered.resolve(); await release.promise; } });
  const reading = cache.readArticle(1);
  const rejected = assert.rejects(reading, { code: "ACCOUNT_CHANGED" });
  await entered.promise;
  await cache.clear();
  assert.equal(store.data.has(bodyRef), true);
  release.resolve(); await rejected;
  store.beforeGet(undefined);
  await cache.collectGarbage();
  assert.deepEqual([...store.data.keys()], [ROOT_KEY]);
});

test("18,518 complete articles grow the COW directory and survive restore without retention limits", async () => {
  const store = fakeStorage(); const cache = engine(store);
  const rows = Array.from({ length: 18518 }, (_, id) => article(id, { content: `<p>中文📰 \\" ${id}</p>`.repeat(40) }));
  assert.ok(jsonBytes(rows) > 6 * 1024 * 1024);
  await seed(cache, rows);
  assert.ok(store.data.get(ROOT_KEY).tables.articles.level >= 1);
  const accesses = [];
  const reopened = engine(store, { onIndexAccess: (event) => accesses.push(event) });
  assert.equal((await reopened.selectIds()).length, rows.length);
  for (let offset = 0; offset < rows.length; offset += 128) {
    const expected = rows.slice(offset, offset + 128);
    const actual = await Promise.all(expected.map((row) => reopened.readArticle(row.id)));
    actual.forEach((row, index) => assert.equal(row.content, expected[index].content));
  }
  const bodies = new Set((await storedArticles(store)).map((row) => row.bodyRef));
  store.resetCalls(); accesses.length = 0;
  await reopened.patchState([{ id: 18517, status: "read" }]);
  assert.equal(accesses.filter(({ kind }) => kind === "row").length, 128, "one bucket, independent of library size");
  assert.ok(accesses.filter(({ kind }) => kind === "node").length <= 2, "only the two directory ancestors");
  assert.ok(accesses.every(({ table }) => table === "articles"));
  assert.equal(store.reads.length, 0, "the loaded metadata index needs no native body or index reads");
  assert.ok(store.writes.flatMap(({ set }) => set).every(({ key }) => !bodies.has(key.replace(/\.p\d+$/, ""))));
  assert.equal(store.writes.filter(rootWrite).length, 1);
  assert.equal((await engine(store).readMetadata(18517)).status, "read");
  assert.equal((await engine(store).selectIds()).length, rows.length);
});

test("high-entropy bodies and oversized catalog values retain exact contents", async () => {
  const store = fakeStorage(); const cache = engine(store);
  const rows = Array.from({ length: 128 }, (_, id) => article(id, { content: randomBytes(320 * 1024).toString("base64") }));
  const feeds = [{ id: 1, title: "订阅".repeat(100000) }];
  await seed(cache, rows, { feeds, categories: [{ id: 2, title: "分类".repeat(30000) }] });
  await cache.updateCatalog({ upsertFeedIcons: [{ feedId: 1, data: "x".repeat(600000) }] });
  assert.ok([...store.data.values()].reduce((total, value) => total + jsonBytes(value), 0) > 50 * 1024 * 1024);
  const reopened = engine(store);
  assert.deepEqual(await reopened.getCatalog("feeds"), feeds);
  assert.equal((await reopened.getCatalogItem("feedIcons", 1)).data.length, 600000);
  await reopened.patchState([{ id: 0, starred: 1 }]);
  const afterPatch = engine(store);
  for (const row of rows) assert.equal((await afterPatch.readArticle(row.id)).content, row.content);
});

test("preferences hydrate in stateless mode and AI secrets only enter secure storage", async () => {
  Object.defineProperty(globalThis, "localStorage", { configurable: true, get() { throw new Error("localStorage disabled"); } });
  Object.defineProperty(globalThis, "indexedDB", { configurable: true, get() { throw new Error("IndexedDB disabled"); } });
  const store = fakeStorage();
  try {
    await store.api.set("nextflux.preferences.v1", { settings: JSON.stringify({ fontSize: 19 }), language: "zh-CN" });
    await store.api.secure.set("nextflux.ai-key.v1", "test-private-ai-key");
    const prefs = await import("../src/toolbox/preferences.js");
    await prefs.initializePreferences({ storage: store.api });
    const { settingsState, updateSettings } = await import("../src/stores/settingsStore.js");
    assert.equal(settingsState.get().fontSize, 19);
    updateSettings({ fontSize: 22, aiApiKey: "replacement-private-ai-key" }); await prefs.flushPreferences();
    const raw = await store.api.get("nextflux.preferences.v1");
    assert.equal(JSON.parse(raw.settings).fontSize, 22);
    assert.equal("aiApiKey" in JSON.parse(raw.settings), false);
    assert.equal(JSON.stringify([...store.data.values()]).includes("private-ai-key"), false);
    assert.equal(await store.api.secure.get("nextflux.ai-key.v1"), "replacement-private-ai-key");
    store.failWhen(({ set }) => set.some(({ key }) => key === "nextflux.preferences.v1"));
    updateSettings({ fontSize: 24 }); await assert.rejects(prefs.flushPreferences(), /simulated write failure/);
  } finally { delete globalThis.localStorage; delete globalThis.indexedDB; }
});

test("db metadata queries preserve hidden feeds, counts and sorting through the Worker contract", async () => {
  const store = fakeStorage(); globalThis.ToolBox = { storage: store.api };
  const root = fileURLToPath(new URL("../", import.meta.url));
  try {
    const bundle = await build({ absWorkingDir: root, entryPoints: ["src/db/storage.js"], bundle: true, write: false,
      format: "esm", platform: "node", plugins: [{ name: "direct-cache-for-node-test", setup(plugin) {
        plugin.onResolve({ filter: /cache-client\.js$/ }, () => ({ path: "cache-client", namespace: "test-worker" }));
        plugin.onLoad({ filter: /.*/, namespace: "test-worker" }, () => ({ resolveDir: root,
          contents: "export { createArticleCache as createWorkerArticleCache } from './src/toolbox/cache.js';" }));
      } }] });
    const db = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString("base64")}`);
    await db.initializeArticleCache();
    await db.addFeeds([{ id: 1, title: "visible" }, { id: 2, hide_globally: true }]);
    await db.addArticles([article(1, { title: "Match older" }), article(2, { title: "Match newer", starred: 1 }),
      article(3, { feedId: 2, title: "Match hidden", status: "read" })]);
    assert.equal(await db.getUnreadCount(1), 2); assert.equal(await db.getStarredCount(1), 1);
    assert.deepEqual((await db.getArticlesByPage([1], "all", 1, 1)).map((row) => row.id), [2]);
    assert.deepEqual((await db.searchArticles("match")).map((row) => row.id), [2, 1]);
    assert.equal((await db.getCachedArticleMetadata()).some((row) => "content" in row), false);
    await db.patchArticleState([{ id: 2, status: "read", starred: 0 }, { id: 1, status: "removed" }]);
    assert.equal(await db.getUnreadCount(1), 0); assert.equal(await db.getArticlesCount([1]), 1);
    await db.setLastSyncTime(new Date("2026-09-08T00:00:00.000Z"));
    assert.equal(db.getLastSyncTime().toISOString(), "2026-09-08T00:00:00.000Z");
    await db.clearArticleCache(); assert.equal(db.getLastSyncTime(), null);
    assert.equal([...store.data.keys()].filter((key) => key.startsWith(CACHE_PREFIX)).length, 1);
  } finally { delete globalThis.ToolBox; }
});

test("staging bodies and index candidates yield to acknowledged state commits", async () => {
  const store = fakeStorage();
  const started = deferred(); const release = deferred();
  let pauseBody = false;
  const cache = engine(store, { digest: async (body) => {
    if (pauseBody && body.content === "new body") { started.resolve(); await release.promise; }
    return JSON.stringify(body);
  } });
  await seed(cache, [article(1)]);
  const oldCheckpoint = (await cache.meta()).lastSyncTime;
  const token = await cache.prepareSync({ syncedAt: "2026-09-10T00:00:00.000Z" });
  pauseBody = true;
  const staging = cache.applySyncBatch(token, [article(1, { content: "new body", starred: 1 })]);
  await started.promise;
  await cache.patchState([{ id: 1, status: "read" }]);
  assert.equal((await cache.readMetadata(1)).status, "read");
  assert.equal((await cache.readArticle(1)).content, article(1).content);
  assert.equal((await cache.meta()).lastSyncTime, oldCheckpoint);
  await assert.rejects(cache.prepareSync(), { code: "BUSY" });
  await assert.rejects(cache.updateCatalog({ feeds: [] }), { code: "BUSY" });
  release.resolve(); await staging;
  const prepared = deferred(); const finishPreparation = deferred();
  let paused = false;
  store.beforeApply(async (change) => {
    if (!paused && !rootWrite(change) && change.set.length) {
      paused = true; prepared.resolve(); await finishPreparation.promise;
    }
  });
  const building = cache.prepareSyncCommit(token, { feeds: [{ id: 1 }], categories: [] });
  await prepared.promise;
  await cache.patchState([{ id: 1, starred: 0 }]);
  assert.equal((await cache.readMetadata(1)).starred, 0);
  finishPreparation.resolve(); await building;
  store.beforeApply(undefined);
  store.resetCalls();
  await cache.commitSync(token, { feeds: [{ id: 1 }], categories: [] });
  assert.equal(store.writes.filter(rootWrite).length, 1);
  assert.equal((await cache.readArticle(1)).content, "new body");
  assert.equal((await cache.readMetadata(1)).status, "read");
  assert.equal((await cache.readMetadata(1)).starred, 0, "explicit no-op intent overrides stale incoming starred state");
  assert.equal((await cache.meta()).lastSyncTime, "2026-09-10T00:00:00.000Z");
  const restored = engine(store);
  assert.equal((await restored.readArticle(1)).status, "read");
  assert.equal((await restored.readArticle(1)).starred, 0);
});

test("a prepared sync rebases only confirmed fields and discards a deletion conflict without a checkpoint", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1), article(2)]);
  const token = await cache.prepareSync({ syncedAt: "2026-09-10T00:00:00.000Z" });
  await cache.applySyncBatch(token, [article(1, { content: "fresh body", title: "fresh title", starred: 1 }), article(2)]);
  await cache.prepareSyncCommit(token, { feeds: [{ id: 1 }], categories: [] });
  await cache.patchState([{ id: 1, status: "read" }]);
  await cache.commitSync(token, { feeds: [{ id: 1 }], categories: [] });
  const row = await cache.readArticle(1);
  assert.equal(row.status, "read"); assert.equal(row.starred, 1);
  assert.equal(row.content, "fresh body"); assert.equal(row.title, "fresh title");
  const checkpoint = await cache.meta();
  const conflicting = await cache.prepareSync({ syncedAt: "2026-09-11T00:00:00.000Z" });
  await cache.applySyncBatch(conflicting, [{ id: 2, status: "removed" }]);
  await cache.patchState([{ id: 2, starred: 1 }]);
  const committedRoot = structuredClone(store.data.get(ROOT_KEY));
  await assert.rejects(cache.commitSync(conflicting, { feeds: [{ id: 1 }], categories: [] }), { code: "SYNC_STATE_CONFLICT" });
  await cache.abortSync(conflicting);
  assert.deepEqual(store.data.get(ROOT_KEY), committedRoot);
  assert.equal((await cache.meta()).lastSyncTime, checkpoint.lastSyncTime);
  assert.equal((await engine(store).readMetadata(2)).starred, 1);
});

test("unchanged body title and URL reuse derived metadata while URL changes recompute it", async () => {
  const store = fakeStorage(); let parsed = 0;
  const cache = engine(store, { deriveMetadata: (row) => {
    parsed += 1;
    return { titleText: row.title, previewText: row.content, coverUrl: `${row.url}/cover` };
  } });
  const source = article(1, { url: "https://example.com/first" });
  await seed(cache, [source]);
  const [{ bodyRef }] = await storedArticles(store);
  assert.equal(parsed, 1);
  store.resetCalls();
  await seed(cache, [{ ...source, status: "read" }]);
  assert.equal(parsed, 1);
  assert.equal(store.reads.flat().some((key) => key.startsWith(bodyRef)), false);
  assert.equal(store.writes.flatMap(({ set }) => set).some(({ key }) => key.startsWith(bodyRef)), false);
  await seed(cache, [{ ...source, url: "https://example.com/second" }]);
  assert.equal(parsed, 2);
  assert.equal((await cache.readMetadata(1)).coverUrl, "https://example.com/second/cover");
});

test("GC coalesces retired references across documents within native key and byte limits", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, Array.from({ length: 256 }, (_, index) => article(index * 128)));
  await cache.collectGarbage();
  await cache.patchState(Array.from({ length: 256 }, (_, index) => ({ id: index * 128, status: "read" })));
  store.resetCalls();
  await cache.collectGarbage();
  const removals = store.writes.filter(({ remove }) => remove.length);
  assert.ok(removals.length >= 2, "more than 256 old physical keys are retired");
  assert.ok(removals.length <= 4, "retired keys share transactions instead of one transaction per reference");
  assert.equal(removals[0].remove.length, 256);
  for (const change of removals) {
    assert.ok(change.remove.length <= 256);
    assert.ok(jsonBytes(change) <= 1024 * 1024);
  }
  assert.equal((await engine(store).selectIds()).length, 256);
  assert.equal((await cache.readArticle(128)).status, "read");
});

test("aborting a throttled staging write cancels its retry before reclaiming staged references", async () => {
  const store = fakeStorage(); let now = 0;
  const sleeping = deferred(); const wake = deferred();
  const cache = engine(store, { rateLimit: { now: () => now, sleep: async (ms) => {
    sleeping.resolve(); await wake.promise; now += ms;
  } } });
  await seed(cache, [article(1)]);
  const committedRoot = structuredClone(store.data.get(ROOT_KEY));
  let attempts = 0;
  store.beforeApply((change) => {
    if (!rootWrite(change) && change.set.length) {
      attempts += 1;
      throw Object.assign(new Error("admission limited"), { code: "RATE_LIMITED", retryAfterMs: 30 });
    }
  });
  const token = await cache.prepareSync();
  const staging = cache.applySyncBatch(token, [article(2)]);
  const cancelled = assert.rejects(staging, { code: "ACCOUNT_CHANGED" });
  await sleeping.promise;
  const aborting = cache.abortSync(token);
  await tick(); wake.resolve();
  await Promise.all([aborting, cancelled]);
  assert.equal(attempts, 1, "cancelled stage never dispatches the delayed write");
  assert.deepEqual(store.data.get(ROOT_KEY), committedRoot);
  store.beforeApply(undefined);
  await cache.collectGarbage();
  assert.equal((await cache.readArticle(1)).content, article(1).content);
  assert.equal(await cache.readArticle(2), null);
});

test("clear drains index preparation before publishing and collecting the empty account root", async () => {
  const store = fakeStorage(); const cache = engine(store);
  await seed(cache, [article(1)]);
  const token = await cache.prepareSync();
  await cache.applySyncBatch(token, [article(2)]);
  const entered = deferred(); const release = deferred(); let paused = false;
  store.beforeApply(async (change) => {
    if (!paused && !rootWrite(change) && change.set.length) { paused = true; entered.resolve(); await release.promise; }
  });
  const preparing = cache.prepareSyncCommit(token, { feeds: [{ id: 1 }], categories: [] });
  const cancelled = assert.rejects(preparing, { code: "ACCOUNT_CHANGED" });
  await entered.promise;
  const clearing = cache.clear();
  await tick();
  assert.equal(store.data.get(ROOT_KEY).cleared, undefined, "empty root waits for in-flight native preparation");
  release.resolve(); await Promise.all([clearing, cancelled]);
  assert.deepEqual([...store.data.keys()], [ROOT_KEY]);
  assert.equal(store.data.get(ROOT_KEY).cleared, true);
  assert.equal(await cache.readArticle(2), null);
});
