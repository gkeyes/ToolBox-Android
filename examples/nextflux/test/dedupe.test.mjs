import test from "node:test";
import assert from "node:assert/strict";
import { dedupeArticles } from "../src/toolbox/dedupe.mjs";
import { createArticleCache } from "../src/toolbox/cache.js";

const ids = (rows) => rows.map((row) => row.id);

test("same title with a different URL is a duplicate", () => {
  const rows = [
    { id: 1, title: "同一篇文章", url: "https://a.example/1" },
    { id: 2, title: "  同一篇文章 ", url: "https://b.example/2" },
    { id: 3, title: "另一篇", url: "https://a.example/3" },
  ];
  assert.deepEqual(ids(dedupeArticles(rows)), [1, 3]);
});

test("the shown title text is compared before the raw title", () => {
  const rows = [
    { id: 1, title: "A &amp; B", titleText: "A & B", url: "" },
    { id: 2, title: "A & B", url: "" },
    { id: 3, title: "A &amp; B", titleText: "A & B!", url: "" },
  ];
  assert.deepEqual(ids(dedupeArticles(rows)), [1, 3]);
});

test("same URL with a different title is a duplicate", () => {
  const rows = [
    { id: 1, title: "标题一", url: "https://a.example/post" },
    { id: 2, title: "标题二", url: " https://a.example/post " },
    { id: 3, title: "标题三", url: "https://a.example/post?x=1" },
  ];
  assert.deepEqual(ids(dedupeArticles(rows)), [1, 3]);
});

test("titles and URLs are compared exactly, not loosely", () => {
  const rows = [
    { id: 1, title: "Hello", url: "https://a.example/x" },
    { id: 2, title: "hello", url: "https://a.example/X" },
  ];
  assert.deepEqual(ids(dedupeArticles(rows)), [1, 2]);
});

test("empty titles and empty URLs never match each other", () => {
  const rows = [
    { id: 1, title: "", url: "" },
    { id: 2, title: "   ", url: "  " },
    { id: 3, title: undefined, titleText: "", url: undefined },
    { id: 4, title: "", url: "https://a.example/1" },
    { id: 5, title: "有标题", url: "" },
  ];
  assert.deepEqual(ids(dedupeArticles(rows)), [1, 2, 3, 4, 5]);
});

test("keeps the first entry in the given order", () => {
  const rows = [
    { id: 9, title: "重复", url: "https://a.example/9" },
    { id: 3, title: "其他", url: "https://a.example/3" },
    { id: 5, title: "重复", url: "https://a.example/5" },
    { id: 1, title: "又一篇", url: "https://a.example/9" },
  ];
  assert.deepEqual(ids(dedupeArticles(rows)), [9, 3]);
  assert.deepEqual(ids(dedupeArticles([...rows].reverse())), [1, 5, 3]);
});

test("only kept entries hide later ones", () => {
  // 2 is hidden by 1 (same URL); 3 shares only 2's title, so it stays.
  const rows = [
    { id: 1, title: "甲", url: "https://a.example/1" },
    { id: 2, title: "乙", url: "https://a.example/1" },
    { id: 3, title: "乙", url: "https://a.example/3" },
  ];
  assert.deepEqual(ids(dedupeArticles(rows)), [1, 3]);
});

test("setting off, or nothing to hide, returns the input unchanged", () => {
  const rows = [
    { id: 1, title: "重复", url: "https://a.example/1" },
    { id: 2, title: "重复", url: "https://a.example/1" },
  ];
  assert.equal(dedupeArticles(rows, { enabled: false }), rows);
  assert.deepEqual(ids(dedupeArticles(rows, { enabled: false })), [1, 2]);
  const unique = [rows[0], { id: 3, title: "不同", url: "https://a.example/3" }];
  assert.equal(dedupeArticles(unique), unique);
  assert.deepEqual(dedupeArticles([]), []);
});

function memoryStorage() {
  const values = new Map();
  return {
    async getMany(keys) { return keys.map((key) => structuredClone(values.get(key) ?? null)); },
    async apply(change) { for (const { key, value } of change.set || []) values.set(key, structuredClone(value)); for (const key of change.remove || []) values.delete(key); },
    async keys() { return [...values.keys()]; },
  };
}

test("a list query hides later copies from its pages and total, and keeps them in the cache", async () => {
  const account = { serverUrl: "https://miniflux.example", userId: "7" };
  const syncedAt = "2026-10-16T03:30:00.000Z";
  const at = (minute) => `2026-10-16T03:${String(minute).padStart(2, "0")}:00.000Z`;
  const entry = (id, title, url, minute) => ({ id, feedId: 1, title, url, content: "<p>正文</p>", enclosures: [],
    status: "unread", starred: 0, published_at: at(minute), changed_at: syncedAt });
  const cache = createArticleCache(memoryStorage(), { autoGc: false, tokenPrefix: "dedupe" });
  const token = await cache.prepareSync({ account, syncedAt });
  await cache.applySyncBatch(token, [
    entry(1, "重复的标题", "https://a.example/1", 10),
    entry(2, "重复的标题", "https://a.example/2", 9),
    entry(3, "不同的标题", "https://a.example/1", 8),
    entry(4, "第四篇", "https://a.example/4", 7),
    entry(5, "第五篇", "https://a.example/5", 6),
  ]);
  await cache.commitSync(token, { feeds: [{ id: 1, title: "源", categoryId: 1 }], categories: [] });

  const query = await cache.openQuery({ pageSize: 2, filter: "unread", hideDuplicates: true });
  assert.equal(query.total, 3);
  const first = await cache.readQueryPage(query.queryId, 1);
  const second = await cache.readQueryPage(query.queryId, 2);
  assert.deepEqual([...ids(first.items), ...ids(second.items)], [1, 4, 5]);
  assert.equal(second.hasMore, false);

  const plain = await cache.openQuery({ pageSize: 30, filter: "unread" });
  assert.equal(plain.total, 5);
  assert.deepEqual(ids((await cache.readQueryPage(plain.queryId, 1)).items), [1, 2, 3, 4, 5]);
  assert.equal((await cache.readMetadata(2)).status, "unread");
});
