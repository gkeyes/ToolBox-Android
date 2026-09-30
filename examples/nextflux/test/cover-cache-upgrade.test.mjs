import test from "node:test";
import assert from "node:assert/strict";
import { createArticleCache } from "../src/toolbox/cache.js";
import { deriveArticleMetadata } from "../src/toolbox/cache-metadata.js";

const account = { serverUrl: "https://miniflux.example", userId: "7" };
const feed = { id: 7, title: "DW", categoryId: 2 };
const syncedAt = "2026-09-30T03:30:00.000Z";
const cover = "https://miniflux.xiaochen.win/media/v1/store/picture/sig";
const article = { id: 101, feedId: 7, title: "DW", url: "https://www.dw.com/zh/story/a-79487894", content: `<img src="https://www.dw.com/zh/story/a-79487894" srcset="${cover} 1200w">`, enclosures: [], status: "read", starred: 1, published_at: syncedAt, changed_at: syncedAt };

function memoryStorage() {
  const values = new Map();
  return {
    values, reads: 0,
    async getMany(keys) { this.reads += 1; return keys.map((key) => structuredClone(values.get(key) ?? null)); },
    async apply(change) { for (const { key, value } of change.set || []) values.set(key, structuredClone(value)); for (const key of change.remove || []) values.delete(key); },
    async keys() { return [...values.keys()]; },
  };
}

async function oldCache(storage) {
  const cache = createArticleCache(storage, { autoGc: false, tokenPrefix: "oldcover", deriveMetadata(value) {
    return { titleText: value.title, previewText: "", coverUrl: value.url, metadataVersion: 0 };
  } });
  const token = await cache.prepareSync({ account, syncedAt });
  await cache.applySyncBatch(token, [article]);
  await cache.commitSync(token, { feeds: [feed], categories: [] });
}

test("cached covers upgrade on the first visible page without resyncing or losing reading state", async () => {
  const storage = memoryStorage();
  await oldCache(storage);
  const cache = createArticleCache(storage, { autoGc: false, tokenPrefix: "newcover" });
  await cache.initialize(account);
  const query = await cache.openQuery({ pageSize: 30 });
  const first = await cache.readQueryPage(query.queryId);
  assert.deepEqual(first.items[0].coverSources, [cover, article.url]);
  assert.equal(first.items[0].status, "read");
  assert.equal(first.items[0].starred, 1);
  assert.equal((await cache.meta()).lastSyncTime, syncedAt);
  const reads = storage.reads;
  await cache.readQueryPage(query.queryId);
  assert.equal(storage.reads, reads, "upgraded page does not reread article bodies");
  const restored = createArticleCache(storage, { autoGc: false, tokenPrefix: "restoredcover" });
  assert.deepEqual((await restored.readMetadata(article.id)).coverSources, first.items[0].coverSources);
  assert.equal((await restored.readArticle(article.id)).content, article.content);
});

test("unchanged synchronized bodies keep current cover candidates", async () => {
  const storage = memoryStorage();
  const cache = createArticleCache(storage, { autoGc: false, tokenPrefix: "samecover" });
  for (let index = 0; index < 2; index += 1) {
    const token = await cache.prepareSync({ account, syncedAt });
    await cache.applySyncBatch(token, [article]);
    await cache.commitSync(token, { feeds: [feed], categories: [] });
  }
  assert.deepEqual((await cache.readMetadata(article.id)).coverSources, deriveArticleMetadata(article).coverSources);
  assert.ok(Array.isArray((await cache.readMetadata(article.id)).coverSources));
});
