import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createArticleCache, pruneServerPurged } from "../src/toolbox/cache.js";
import { parseHashRoute, sidebarItems, adjacentSidebarPath } from "../src/lib/hashRoute.mjs";
import { createReadObserver } from "../src/lib/readObserver.mjs";
import { debounce } from "../src/lib/debounce.mjs";
import { toggleStarAcknowledged } from "../src/toolbox/star-toggle.mjs";
import { initializePreferences, setPreference, flushPreferences } from "../src/toolbox/preferences.js";
import { decodeSettings, settingsState, updateSettings } from "../src/stores/settingsStore.js";
import { aiSummaries, setSummaryLoading, appendSummaryChunk, finishCancelledSummary } from "../src/stores/aiStore.js";

const ACCOUNT = { serverUrl: "https://miniflux.example", userId: "7" };
const FEED = { id: 7, title: "订阅", url: "https://example.test/feed", site_url: "https://example.test", categoryId: 2 };
const CATEGORY = { id: 2, title: "分类" };
const AT = "2026-09-26T03:30:00.000Z";
const digest = async (value) => createHash("sha256").update(JSON.stringify(value)).digest("hex");

function memoryStorage() {
  const values = new Map();
  return {
    values,
    async getMany(keys) { return keys.map((key) => values.has(key) ? structuredClone(values.get(key)) : null); },
    async apply(change = {}) {
      for (const item of change.set || []) values.set(item.key, structuredClone(item.value));
      for (const key of change.remove || []) values.delete(key);
    },
    async keys() { return [...values.keys()]; },
  };
}

const entry = (id, overrides = {}) => ({
  id, feedId: 7, title: `文章 ${id}`, author: "", url: `https://example.test/${id}`, content: `<p>正文 ${id}</p>`,
  status: "read", starred: 0, published_at: AT, created_at: AT, reading_time: 1, enclosures: [], changed_at: AT, ...overrides,
});

async function syncedCache(rows) {
  const cache = createArticleCache(memoryStorage(), { autoGc: false, tokenPrefix: `prune${Math.random()}`, digest });
  await cache.initialize(ACCOUNT);
  await cache.updateCatalog({ feeds: [FEED], categories: [CATEGORY] });
  const token = await cache.prepareSync({ account: ACCOUNT, syncedAt: AT });
  await cache.applySyncBatch(token, rows);
  await cache.commitSync(token, { feeds: [FEED], categories: [CATEGORY] });
  return cache;
}

async function resync(cache, oldestServerReadId) {
  const token = await cache.prepareSync({ account: ACCOUNT, syncedAt: AT });
  await cache.commitSync(token, { feeds: [FEED], categories: [CATEGORY], oldestServerReadId });
  return (await cache.selectIds({})).sort((a, b) => a - b);
}

test("sync drops read, unstarred entries the server has purged and keeps everything else", async () => {
  const cache = await syncedCache([
    entry(1), entry(2), entry(3, { starred: 1 }), entry(4, { status: "unread" }), entry(5), entry(6),
  ]);
  // The server's oldest surviving read, unstarred entry is 5: 1 and 2 are gone.
  assert.deepEqual(await resync(cache, 5), [3, 4, 5, 6]);
  // No information (request gave nothing usable) removes nothing.
  assert.deepEqual(await resync(cache, null), [3, 4, 5, 6]);
  // The server has no read, unstarred entries at all.
  assert.deepEqual(await resync(cache, Infinity), [3, 4]);
});

test("purge reconciliation never removes an entry the user changed during the sync", () => {
  const rows = new Map([[1, { id: 1, status: "read", starred: 0 }], [2, { id: 2, status: "read", starred: 0 }]]);
  assert.equal(pruneServerPurged(rows, 10, new Map([[2, { status: "read" }]])), 1);
  assert.deepEqual([...rows.keys()], [2]);
  assert.equal(pruneServerPurged(rows, "10"), 0, "a malformed boundary is ignored");
});

test("hotkey routes are read from the hash on demand", () => {
  assert.deepEqual(parseHashRoute("#/feed/12/article/34?x=1"), { basePath: "/feed/12", feedId: "12", categoryId: undefined, articleId: "34" });
  assert.deepEqual(parseHashRoute("#/article/9"), { basePath: "", feedId: undefined, categoryId: undefined, articleId: "9" });
  assert.deepEqual(parseHashRoute("#/category/3"), { basePath: "/category/3", feedId: undefined, categoryId: "3", articleId: undefined });
  assert.deepEqual(parseHashRoute(""), { basePath: "", feedId: undefined, categoryId: undefined, articleId: undefined });

  const categories = [{ id: "1", feeds: [{ id: 10 }, { id: 11 }] }, { id: "2", feeds: [{ id: 20 }] }];
  const items = sidebarItems(categories, { 1: true }, false);
  assert.deepEqual(items.map((item) => item.path), ["/", "/category/1", "/feed/10", "/feed/11", "/category/2"]);
  assert.equal(adjacentSidebarPath(items, parseHashRoute("#/feed/10"), 1), "/feed/11");
  assert.equal(adjacentSidebarPath(items, parseHashRoute("#/feed/10"), -1), "/category/1");
  assert.equal(adjacentSidebarPath(items, parseHashRoute("#/"), -1), null);
  // A collapsed category follows the default-expanded setting.
  assert.equal(sidebarItems(categories, {}, true).length, 6);
});

function fakeObserverClass() {
  const instances = [];
  class FakeObserver {
    constructor(callback, options) { this.callback = callback; this.options = options; this.targets = new Set(); instances.push(this); }
    observe(element) { this.targets.add(element); }
    unobserve(element) { this.targets.delete(element); }
    disconnect() { this.targets.clear(); }
    fire(target, isIntersecting, top) {
      this.callback([{ target, isIntersecting, boundingClientRect: { top }, rootBounds: { top: 0 } }]);
    }
  }
  return { FakeObserver, instances };
}

test("one shared observer marks a card read only after it was seen and left through the top", () => {
  const { FakeObserver, instances } = fakeObserverClass();
  const read = [];
  const root = {};
  const observer = createReadObserver({ getRoot: () => root, onRead: (article) => read.push(article.id), Observer: FakeObserver });
  const a = {}; const b = {}; const c = {};
  observer.observe(a, { id: 1 });
  observer.observe(b, { id: 2 });
  const stopC = observer.observe(c, { id: 3 });
  assert.equal(instances.length, 1, "all cards share one IntersectionObserver");
  assert.equal(instances[0].options.root, root);
  const io = instances[0];
  io.fire(a, false, -50); // never seen: not read
  io.fire(b, true, 10);
  io.fire(b, false, 900); // left through the bottom: not read
  io.fire(b, false, -40); // left through the top: read
  io.fire(b, false, -80); // already unobserved
  assert.deepEqual(read, [2]);
  assert.equal(io.targets.has(b), false);
  stopC();
  assert.equal(io.targets.has(c), false);
  // A re-rendered card keeps the "seen" state of its article.
  io.fire(a, true, 10);
  observer.observe(a, { id: 1, status: "unread" });
  io.fire(a, false, -30);
  assert.deepEqual(read, [2, 1]);
  observer.dispose();
});

test("local debounce coalesces calls and supports cancel and flush", async () => {
  const calls = [];
  const fn = debounce((value) => calls.push(value), 5);
  fn(1); fn(2); fn(3);
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.deepEqual(calls, [3]);
  fn(4); fn.cancel();
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.deepEqual(calls, [3]);
  fn(5); fn.flush();
  assert.deepEqual(calls, [3, 5]);
});

test("settings decode falls back to defaults and slider commits share one write", async () => {
  const defaults = decodeSettings("{not json");
  assert.equal(defaults.fontSize, 16);
  assert.equal(decodeSettings("[1]").sortField, "published_at");
  assert.equal(decodeSettings(JSON.stringify({ fontSize: 20 })).fontSize, 20);

  const writes = [];
  await initializePreferences({ storage: {
    async get() { return null; },
    async set(_, value) { writes.push(value); },
    secure: { async get() { return null; }, async set() {}, async remove() {} },
  } });
  void updateSettings({ fontSize: 18 }, { immediate: false });
  void updateSettings({ fontSize: 22 }, { immediate: false });
  assert.equal(settingsState.get().fontSize, 22, "the store updates at once");
  await Promise.resolve();
  assert.equal(writes.length, 0, "deferred slider commits wait for the pause");
  await flushPreferences();
  assert.equal(writes.length, 1, "one write carries every deferred change");
  assert.equal(JSON.parse(writes[0].settings).fontSize, 22);
  setPreference("categoryExpanded", JSON.stringify({ 1: false }));
  await flushPreferences();
  assert.equal(writes.length, 2, "other preference changes still write immediately");
  assert.equal(writes[1].categoryExpanded, JSON.stringify({ 1: false }));
  await updateSettings({ fontSize: 20 });
  assert.equal(writes.length, 3, "updateSettings writes immediately by default");
  assert.equal(settingsState.get().fontSize, 20);
});

test("a cancelled AI summary is not an error and leaves no stale entry", () => {
  setSummaryLoading(1);
  finishCancelledSummary(1);
  assert.equal(aiSummaries.get()[1], undefined, "switching articles removes the unfinished summary");
  setSummaryLoading(2);
  appendSummaryChunk(2, "部分摘要");
  finishCancelledSummary(2, { keepPartial: true });
  assert.deepEqual(aiSummaries.get()[2], { loading: false, summary: "部分摘要", error: null });
  setSummaryLoading(3);
  finishCancelledSummary(3, { keepPartial: true });
  assert.equal(aiSummaries.get()[3], undefined, "a stop before any text leaves nothing behind");
});

test("an acknowledged star toggle is committed even when the confirming read fails", async () => {
  const run = async (fetchEntry, starred = 0) => {
    const saves = [];
    const result = await toggleStarAcknowledged({ current: { id: 5, starred }, toggle: async () => {}, fetchEntry, save: async (value) => saves.push(value) });
    return { result, saves };
  };
  assert.deepEqual(await run(async () => { throw new Error("offline"); }), { result: 1, saves: [1] });
  assert.deepEqual(await run(async () => ({ id: 5 })), { result: 1, saves: [1] }, "a malformed read keeps the expected state");
  assert.deepEqual(await run(async () => ({ starred: true })), { result: 1, saves: [1] });
  assert.deepEqual(await run(async () => ({ starred: true }), 1), { result: 1, saves: [0, 1] }, "the server's state corrects a stale cache");
  const saves = [];
  await assert.rejects(toggleStarAcknowledged({ current: { id: 5, starred: 0 }, toggle: async () => { throw new Error("PUT failed"); },
    fetchEntry: async () => ({ starred: true }), save: async (value) => saves.push(value) }), /PUT failed/);
  assert.deepEqual(saves, [], "nothing is committed when the toggle was not acknowledged");
});
