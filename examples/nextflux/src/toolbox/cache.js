// Ordinary ToolBox KV storage works in both dedicated and stateless WebViews.
// A manifest switches to complete table generations only after all shards exist.
export const CACHE_PREFIX = "nextflux.cache.v1.";
const MANIFEST_KEY = `${CACHE_PREFIX}manifest`;
// The package declares an 8 MiB bridge payload; each UTF-16 shard is at most
// 1.5 MiB after JSON escaping, which also keeps RPC counts below small-shard levels.
const CHUNK_CHARS = 256 * 1024;
export const CACHE_LIMITS = Object.freeze({
  articles: 6 * 1024 * 1024,
  feeds: 512 * 1024,
  categories: 128 * 1024,
  feedIcons: 512 * 1024,
  meta: 1024,
});
const empty = () => ({ articles: [], feeds: [], categories: [], feedIcons: [], meta: {} });
export const jsonBytes = (value) => new TextEncoder().encode(JSON.stringify(value)).length;
export const cacheBytes = (value) => jsonBytes(JSON.stringify(value));
const copy = (value) => JSON.parse(JSON.stringify(value));

export function boundArticles(articles) {
  if (cacheBytes(articles) <= CACHE_LIMITS.articles) return { articles, evicted: 0 };
  const ordered = [...articles].sort((a, b) => String(b.published_at || b.created_at || "")
    .localeCompare(String(a.published_at || a.created_at || "")) || b.id - a.id);
  let bytes = 4;
  const retained = [];
  for (const article of ordered) {
    const size = cacheBytes(article) - 2 + (retained.length ? 1 : 0);
    if (bytes + size <= CACHE_LIMITS.articles) {
      retained.push(article);
      bytes += size;
    }
  }
  return { articles: retained, evicted: articles.length - retained.length };
}

export function createArticleCache(storage, onEviction = () => {}) {
  let state = empty();
  let manifest = { generation: 0, tables: {} };
  let loaded;
  let pending = Promise.resolve();

  async function cleanup() {
    const active = new Set(Object.values(manifest.tables).flat());
    for (const key of await storage.keys()) {
      if (key.startsWith(CACHE_PREFIX) && key !== MANIFEST_KEY && !active.has(key)) {
        await storage.remove(key);
      }
    }
  }

  function initialize() {
    if (!loaded) loaded = (async () => {
      const saved = await storage.get(MANIFEST_KEY);
      if (saved !== null) {
        if (!Number.isSafeInteger(saved.generation) || saved.generation < 0 ||
            !saved.tables || typeof saved.tables !== "object") {
          throw new Error("阅读缓存格式无效，请退出登录后重新同步。");
        }
        const restored = empty();
        for (const table of Object.keys(CACHE_LIMITS)) {
          const keys = saved.tables[table];
          if (keys === undefined) continue;
          if (!Array.isArray(keys) || keys.length > 256 || keys.some((key) =>
            typeof key !== "string" || !key.startsWith(`${CACHE_PREFIX}${table}.`))) {
            throw new Error("阅读缓存索引无效，请退出登录后重新同步。");
          }
          const parts = [];
          for (const key of keys) {
            const part = await storage.get(key);
            if (typeof part !== "string" || part.length > CHUNK_CHARS) {
              throw new Error("阅读缓存不完整，请退出登录后重新同步。");
            }
            parts.push(part);
          }
          const value = JSON.parse(parts.join(""));
          if ((table !== "meta" && !Array.isArray(value)) ||
              (table === "meta" && (!value || typeof value !== "object" || Array.isArray(value))) ||
              cacheBytes(value) > CACHE_LIMITS[table]) {
            throw new Error("阅读缓存超出上限，请退出登录后重新同步。");
          }
          restored[table] = value;
        }
        state = restored;
        manifest = saved;
      }
      await cleanup();
    })();
    return loaded;
  }

  function transact(tables, update) {
    const operation = pending.then(async () => {
      await initialize();
      const next = { ...state };
      for (const table of tables) next[table] = copy(state[table]);
      update(next);
      let evicted = 0;
      if (tables.includes("articles")) {
        const bounded = boundArticles(next.articles);
        next.articles = bounded.articles;
        evicted = bounded.evicted;
      }
      for (const table of tables) {
        if (cacheBytes(next[table]) > CACHE_LIMITS[table]) {
          throw new Error("订阅或图标缓存已达到容量上限，请减少订阅后重试。");
        }
      }
      const nextManifest = { generation: manifest.generation + 1, tables: { ...manifest.tables } };
      await cleanup();
      for (const table of tables) {
        const text = JSON.stringify(next[table]);
        const keys = [];
        for (let offset = 0; offset < text.length; offset += CHUNK_CHARS) {
          const key = `${CACHE_PREFIX}${table}.${nextManifest.generation}.${keys.length}`;
          await storage.set(key, text.slice(offset, offset + CHUNK_CHARS));
          keys.push(key);
        }
        nextManifest.tables[table] = keys;
      }
      await storage.set(MANIFEST_KEY, nextManifest);
      manifest = nextManifest;
      state = next;
      // Stale shards are also removed on initialization / before the next commit.
      await cleanup().catch(() => {});
      if (evicted) onEviction(evicted);
    });
    pending = operation.catch(() => {});
    return operation;
  }

  return {
    initialize,
    transact,
    peek: () => state,
    async read(select = (value) => value) {
      await initialize();
      await pending;
      const value = select(state);
      return value === undefined ? undefined : copy(value);
    },
    clear() {
      const operation = pending.then(async () => {
        // Removing the manifest first makes interruption equivalent to an empty cache.
        await storage.remove(MANIFEST_KEY);
        state = empty();
        manifest = { generation: 0, tables: {} };
        loaded = Promise.resolve();
        await cleanup();
      });
      pending = operation.catch(() => {});
      return operation;
    },
  };
}
