import assert from "node:assert/strict";
import { createArticleCache, ROOT_KEY, jsonBytes } from "../src/toolbox/cache.js";
import { decodeCacheValue } from "../src/toolbox/cache-legacy.js";

export function fakeStorage() {
  const data = new Map();
  const secure = new Map();
  const reads = [];
  const writes = [];
  let beforeApply;
  let afterApply;
  let beforeGet;
  let beforeKeys;
  let failure = () => false;
  const api = {
    get: async (key) => structuredClone(data.get(key) ?? null),
    async getMany(keys) {
      assert.ok(keys.length <= 256);
      reads.push([...keys]);
      await beforeGet?.(keys);
      const values = keys.map((key) => structuredClone(data.get(key) ?? null));
      if (jsonBytes(values) > 8 * 1024 * 1024 - 512) throw Object.assign(new Error("response transport limit"), { code: "QUOTA_EXCEEDED" });
      return values;
    },
    async apply({ set = [], remove = [] } = {}) {
      assert.ok(set.length + remove.length <= 256);
      assert.equal(new Set([...set.map(({ key }) => key), ...remove]).size, set.length + remove.length);
      assert.ok(jsonBytes({ set, remove }) < 8 * 1024 * 1024);
      await beforeApply?.({ set, remove });
      if (failure({ set, remove })) throw Object.assign(new Error("simulated write failure"), { code: "INTERNAL_ERROR" });
      // The map update has no await: it models one native transaction.
      for (const key of remove) data.delete(key);
      for (const { key, value } of set) data.set(key, structuredClone(value));
      writes.push({ set: structuredClone(set), remove: [...remove] });
      await afterApply?.({ set, remove });
    },
    async set(key, value) {
      if (failure({ set: [{ key, value }], remove: [] })) throw new Error("simulated write failure");
      data.set(key, structuredClone(value));
    },
    remove: async (key) => { data.delete(key); },
    async keys() { await beforeKeys?.(); return [...data.keys()]; },
    secure: {
      get: async (key) => structuredClone(secure.get(key) ?? null),
      set: async (key, value) => { secure.set(key, structuredClone(value)); },
      remove: async (key) => { secure.delete(key); },
    },
  };
  return { api, data, secure, reads, writes,
    failWhen: (value) => { failure = value; },
    beforeApply: (value) => { beforeApply = value; },
    afterApply: (value) => { afterApply = value; },
    beforeGet: (value) => { beforeGet = value; },
    beforeKeys: (value) => { beforeKeys = value; },
    resetCalls: () => { reads.length = 0; writes.length = 0; },
  };
}

export const engine = (store, options = {}) => createArticleCache(store.api, { autoGc: false, ...options });
export const article = (id, extra = {}) => ({ id, feedId: 1, title: `Article ${id}`, content: `<p>文章 ${id}</p>`,
  status: "unread", starred: 0, published_at: String(id).padStart(16, "0"), enclosures: [], ...extra });
export async function seed(cache, rows, { feeds = [{ id: 1, title: "Feed" }], categories = [], syncedAt = "2026-09-09T00:00:00.000Z", account } = {}) {
  const token = await cache.prepareSync({ full: false, syncedAt, account });
  try {
    await cache.applySyncBatch(token, rows);
    return await cache.commitSync(token, { feeds, categories });
  } catch (error) { await cache.abortSync(token); throw error; }
}
export const rootWrite = ({ set }) => set.some(({ key }) => key === ROOT_KEY);
export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
export const tick = () => new Promise((resolve) => setImmediate(resolve));
export async function document(store, ref) {
  const header = store.data.get(ref);
  const text = header.text ?? Array.from({ length: header.parts }, (_, index) => store.data.get(`${ref}.p${index}`)).join("");
  return decodeCacheValue(header.codec, text);
}
export async function storedArticles(store) {
  const result = [];
  const root = store.data.get(ROOT_KEY);
  async function visit(ref) {
    const value = await document(store, ref);
    if (value.kind === "leaf") result.push(...value.rows);
    else for (const child of Object.values(value.entries)) await visit(child);
  }
  if (root.tables.articles) await visit(root.tables.articles.key);
  return result;
}
