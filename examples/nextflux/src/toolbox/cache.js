// Ordinary ToolBox KV storage works in both dedicated and stateless WebViews.
// Keep the namespace so upgrades can read v1 snapshots and logout removes both formats.
export const CACHE_PREFIX = "nextflux.cache.v1.";
const MANIFEST_KEY = `${CACHE_PREFIX}manifest`;
const TABLES = ["articles", "feeds", "categories", "feedIcons", "meta"];
// Transport shard sizes, never article/table size or retention limits. Base64
// contains only ASCII; larger shards reduce native method calls for full archives.
// Plain JSON keeps the v1 bound, including its worst-case bridge JSON escaping.
const JSON_CHUNK_CHARS = 256 * 1024;
const GZIP_CHUNK_CHARS = 1024 * 1024;
const chunkChars = (codec) => codec === "gzip-base64" ? GZIP_CHUNK_CHARS : JSON_CHUNK_CHARS;
const empty = () => ({ articles: [], feeds: [], categories: [], feedIcons: [], meta: {} });
export const jsonBytes = (value) => new TextEncoder().encode(JSON.stringify(value)).length;
const copy = (value) => JSON.parse(JSON.stringify(value));
const record = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const invalid = (detail, cause) => new Error(`阅读缓存${detail}，请退出登录后重新同步。`, { cause });

function validateTable(table, value) {
  if (table === "meta") {
    if (!record(value) || (value.lastSyncTime !== undefined &&
        (typeof value.lastSyncTime !== "string" || !Number.isFinite(Date.parse(value.lastSyncTime))))) {
      throw invalid("同步记录格式无效");
    }
  } else if (!Array.isArray(value) || value.some((row) => !record(row))) {
    throw invalid("数据表格式无效");
  }
}

function validateManifest(saved) {
  if (!record(saved) || !Number.isSafeInteger(saved.generation) || saved.generation < 0 ||
      !record(saved.tables) || (saved.version !== undefined && saved.version !== 2) ||
      Object.keys(saved.tables).some((table) => !TABLES.includes(table))) {
    throw invalid("格式无效");
  }
  for (const [table, entry] of Object.entries(saved.tables)) {
    if (saved.version === 2 && (!record(entry) || !["json", "gzip-base64"].includes(entry.codec))) {
      throw invalid("编码索引无效");
    }
    const keys = saved.version === 2 ? entry.keys : entry;
    if (!Array.isArray(keys) || keys.length === 0) throw invalid("分片索引无效");
    let tableGeneration;
    for (const [index, key] of keys.entries()) {
      if (typeof key !== "string" || !key.startsWith(`${CACHE_PREFIX}${table}.`)) {
        throw invalid("分片索引无效");
      }
      const suffix = key.slice(`${CACHE_PREFIX}${table}.`.length);
      const match = /^(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(suffix);
      const generation = Number(match?.[1]);
      if (!match || !Number.isSafeInteger(generation) || generation > saved.generation ||
          Number(match[2]) !== index || (tableGeneration !== undefined && generation !== tableGeneration)) {
        throw invalid("分片索引无效");
      }
      tableGeneration = generation;
    }
  }
}

async function encode(value) {
  const text = JSON.stringify(value);
  // Do not silently fall back after a compression failure: reject the transaction.
  if (typeof globalThis.CompressionStream !== "function" ||
      typeof globalThis.DecompressionStream !== "function") return { codec: "json", text };
  const stream = new Blob([text]).stream().pipeThrough(new CompressionStream("gzip"));
  const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
  const parts = [];
  for (let offset = 0; offset < bytes.length; offset += 32768) {
    parts.push(String.fromCharCode(...bytes.subarray(offset, offset + 32768)));
  }
  return { codec: "gzip-base64", text: btoa(parts.join("")) };
}

async function decode(codec, text) {
  try {
    if (codec === "gzip-base64") {
      if (typeof globalThis.DecompressionStream !== "function") {
        throw new Error("当前 WebView 不支持读取压缩缓存，请更新 Android System WebView 后重试。");
      }
      const padding = text.endsWith("==") ? 2 : text.endsWith("=") ? 1 : 0;
      if (text.length === 0 || text.length % 4 !== 0 ||
          /[^A-Za-z0-9+/]/.test(text.slice(0, text.length - padding))) {
        throw invalid("压缩分片损坏");
      }
      const binary = atob(text);
      const bytes = new Uint8Array(binary.length);
      for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
      const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
      const decoded = await new Response(stream).arrayBuffer();
      text = new TextDecoder("utf-8", { fatal: true }).decode(decoded);
    }
    return JSON.parse(text);
  } catch (cause) {
    if (codec === "gzip-base64" && typeof globalThis.DecompressionStream !== "function") throw cause;
    throw invalid("内容损坏", cause);
  }
}

export function createArticleCache(storage) {
  let state = empty();
  let manifest = { version: 2, generation: 0, tables: {} };
  let loaded;
  let pending = Promise.resolve();

  async function call(method, ...args) {
    try {
      return await storage[method](...args);
    } catch (error) {
      if (error?.code !== "RATE_LIMITED") throw error;
      // Native admission rejects the call before any mutation. Wait out its
      // 60-second method window and retry the same idempotent storage operation.
      await new Promise((resolve) => setTimeout(resolve, 60000));
      return storage[method](...args);
    }
  }

  async function cleanup() {
    const active = new Set(Object.values(manifest.tables).flatMap((entry) =>
      manifest.version === 2 ? entry.keys : entry));
    for (const key of await call("keys")) {
      if (key.startsWith(CACHE_PREFIX) && key !== MANIFEST_KEY && !active.has(key)) {
        await call("remove", key);
      }
    }
  }

  function initialize() {
    if (!loaded) loaded = (async () => {
      const saved = await call("get", MANIFEST_KEY);
      if (saved !== null) {
        validateManifest(saved);
        const restored = empty();
        for (const table of TABLES) {
          const entry = saved.tables[table];
          if (entry === undefined) continue;
          const keys = saved.version === 2 ? entry.keys : entry;
          const codec = saved.version === 2 ? entry.codec : "json";
          const parts = [];
          for (const key of keys) {
            const part = await call("get", key);
            if (typeof part !== "string" || part.length === 0 || part.length > chunkChars(codec)) {
              throw invalid("分片不完整");
            }
            parts.push(part);
          }
          const value = await decode(codec, parts.join(""));
          validateTable(table, value);
          restored[table] = value;
        }
        // v1 could have evicted articles while advancing the checkpoint. Retain its
        // readable rows, but require a complete server sync before trusting a checkpoint.
        if (saved.version !== 2) delete restored.meta.lastSyncTime;
        state = restored;
        manifest = saved;
      }
      // A legacy snapshot stays untouched until the first successful v2 commit.
      if (manifest.version === 2) await cleanup();
    })();
    return loaded;
  }

  function transact(tables, update) {
    const operation = pending.then(async () => {
      await initialize();
      if (!Array.isArray(tables) || tables.some((table) => !TABLES.includes(table))) {
        throw new Error("阅读缓存更新的数据表无效。");
      }
      const changed = [...new Set(tables)];
      const next = { ...state };
      for (const table of changed) next[table] = copy(state[table]);
      update(next);
      // Rewrite every legacy table so v2 never preserves an ambiguous old checkpoint.
      const written = manifest.version === 2 ? changed : TABLES;
      for (const table of written) validateTable(table, next[table]);
      const generation = manifest.generation + 1;
      if (!Number.isSafeInteger(generation)) throw invalid("代次无效");
      const nextManifest = { version: 2, generation, tables: { ...manifest.tables } };
      await cleanup();
      try {
        for (const table of written) {
          const encoded = await encode(next[table]);
          const keys = [];
          for (let offset = 0; offset < encoded.text.length;) {
            let end = Math.min(offset + chunkChars(encoded.codec), encoded.text.length);
            // Plain JSON may contain astral characters. Keep surrogate pairs in one
            // shard so native UTF-8 persistence cannot replace a split surrogate.
            if (end < encoded.text.length && /[\uD800-\uDBFF]/.test(encoded.text[end - 1]) &&
                /[\uDC00-\uDFFF]/.test(encoded.text[end])) end -= 1;
            const key = `${CACHE_PREFIX}${table}.${generation}.${keys.length}`;
            await call("set", key, encoded.text.slice(offset, end));
            keys.push(key);
            offset = end;
          }
          nextManifest.tables[table] = { codec: encoded.codec, keys };
        }
        // Only switch generations after all table shards have been persisted.
        await call("set", MANIFEST_KEY, nextManifest);
      } catch (cause) {
        if (cause?.code === "QUOTA_EXCEEDED") {
          const error = new Error("ToolBox 未能保存本次缓存；原有文章和同步进度已保留，请稍后重试。", { cause });
          error.code = cause.code;
          throw error;
        }
        throw cause;
      }
      manifest = nextManifest;
      state = next;
      // Interrupted staging is reclaimed on reopen / before the next commit.
      await cleanup().catch(() => {});
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
        await call("remove", MANIFEST_KEY);
        state = empty();
        manifest = { version: 2, generation: 0, tables: {} };
        loaded = Promise.resolve();
        await cleanup();
      });
      pending = operation.catch(() => {});
      return operation;
    },
  };
}
