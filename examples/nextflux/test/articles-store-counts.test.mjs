import test from "node:test";
import assert from "node:assert/strict";
import { register } from "node:module";
import { createArticleCache } from "../src/toolbox/cache.js";

// articlesStore with its app modules replaced by small stubs, so reading or
// starring goes through the store's own publishUpdates into the sidebar counts.
const stubs = {
  "./syncStore.js": `export const runAccountOperation = (fn) => fn(() => {});
    export const runAccountScopeOperation = (fn) => fn(() => {});
    export const registerAccountRecovery = () => {}; export const onAccountInvalidated = () => {};`,
  "../toolbox/auto-read.js": `export const createAutoReadQueue = () => ({ enqueue() {}, cancelUnsent() {}, cancelAll() {} });`,
  "../db/storage.js": `const c = () => globalThis.__counts;
    export const getArticleMetadata = async (id) => c().cache.readMetadata ? (await c().cache.readMetadata([id]))[0] ?? { id, status: "unread", starred: 0 } : { id, status: "unread", starred: 0 };
    export const patchArticleState = (patches, view) => c().cache.patchState(patches, view);
    export const getFeeds = async () => []; export const getCachedArticleMetadata = async () => [];
    export const getArticleIds = async () => []; export const getArticleCounts = async () => ({ unread: {}, starred: {} });
    export const openArticleQuery = async () => ({}); export const readArticleQueryPage = async () => ({}); export const closeArticleQuery = async () => {};`,
  "../api/miniflux.js": `export default { updateEntriesStatus: async () => {}, updateEntryStarred: async (entry) => ({ ...entry, starred: !entry.starred }), getEntry: async () => null };`,
  "./feedsStore.js": `import { atom } from "nanostores";
    export const feeds = atom([]); export const unreadCounts = atom({}); export const starredCounts = atom({});
    export const duplicateView = () => globalThis.__counts.view;
    globalThis.__counts.stores = { feeds, unreadCounts, starredCounts };`,
  "./settingsStore.js": `import { atom } from "nanostores"; export const settingsState = atom({ showHiddenFeeds: false });
    globalThis.__counts.settings = settingsState;`,
  "./aiStore.js": `export const clearAllSummaries = () => {};`,
};
const hooks = `
const stubs = ${JSON.stringify(stubs)};
export async function resolve(specifier, context, next) {
  if (context.parentURL?.endsWith("/stores/articlesStore.js") && specifier in stubs) return { url: "data:text/javascript," + encodeURIComponent(stubs[specifier]), shortCircuit: true };
  if (specifier === "nanostores" && context.parentURL?.startsWith("data:")) return next(specifier, { ...context, parentURL: ${JSON.stringify(import.meta.url)} });
  return next(specifier, context);
}`;
register("data:text/javascript," + encodeURIComponent(hooks));

function memoryStorage() {
  const values = new Map();
  return {
    async getMany(keys) { return keys.map((key) => structuredClone(values.get(key) ?? null)); },
    async apply(change) { for (const { key, value } of change.set || []) values.set(key, structuredClone(value)); for (const key of change.remove || []) values.delete(key); },
    async keys() { return [...values.keys()]; },
  };
}

test("reading through the store keeps a globally hidden feed out of the sidebar counts", async () => {
  globalThis.__counts = {};
  const account = { serverUrl: "https://miniflux.example", userId: "7" };
  const syncedAt = "2026-10-16T03:30:00.000Z";
  const at = (minute) => `2026-10-16T03:${String(minute).padStart(2, "0")}:00.000Z`;
  const entry = (id, feedId, minute) => ({ id, feedId, title: `第 ${id} 篇`, url: `https://a.example/${id}`, content: "<p>正文</p>", enclosures: [],
    status: "unread", starred: 0, published_at: at(minute), changed_at: syncedAt });
  const cache = createArticleCache(memoryStorage(), { autoGc: false, tokenPrefix: "store-counts" });
  const token = await cache.prepareSync({ account, syncedAt });
  await cache.applySyncBatch(token, [entry(1, 1, 10), entry(2, 1, 9), entry(3, 2, 8), entry(4, 2, 7)]);
  const feeds = [{ id: 1, title: "甲" }, { id: 2, title: "乙", hide_globally: true }];
  await cache.commitSync(token, { feeds, categories: [] });
  globalThis.__counts.cache = cache;
  globalThis.__counts.view = { hideDuplicates: true, field: "published_at", direction: "desc", feedIds: [1] };
  const { updateArticleStatus } = await import("../src/stores/articlesStore.js");
  const { feeds: feedList, unreadCounts } = globalThis.__counts.stores;
  feedList.set(feeds);
  unreadCounts.set({ 1: 2 });
  await updateArticleStatus({ id: 1 }, "read");
  // The cache returns counts for every feed; the hidden feed 乙 stays out.
  assert.deepEqual(unreadCounts.get(), { 1: 1 });
  // With 显示隐藏订阅 on, it counts again.
  globalThis.__counts.settings.set({ showHiddenFeeds: true });
  await updateArticleStatus({ id: 3 }, "read");
  assert.deepEqual(unreadCounts.get(), { 1: 1, 2: 1 });
});
