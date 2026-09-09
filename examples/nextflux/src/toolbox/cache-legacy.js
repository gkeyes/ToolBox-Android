// Read-only compatibility decoder. A v3 commit is the only operation allowed to
// retire these keys; an unsuccessful migration leaves the old snapshot intact.
export const LEGACY_PREFIX = "nextflux.cache.v1.";
export const LEGACY_MANIFEST_KEY = `${LEGACY_PREFIX}manifest`;
const TABLES = ["articles", "feeds", "categories", "feedIcons", "meta"];
const record = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const invalid = () => Object.assign(new Error("阅读缓存格式或内容损坏，请退出登录后重新同步。"), { code: "CACHE_INVALID" });

export async function decodeCacheValue(codec, text) {
  if (codec === "gzip-base64") {
    if (typeof globalThis.DecompressionStream !== "function") {
      throw Object.assign(new Error("当前 WebView 不支持读取压缩缓存，请更新 Android System WebView 后重试。"), { code: "UNSUPPORTED" });
    }
    const padding = text.endsWith("==") ? 2 : text.endsWith("=") ? 1 : 0;
    if (!text.length || text.length % 4 || /[^A-Za-z0-9+/]/.test(text.slice(0, text.length - padding))) throw invalid();
    const binary = atob(text);
    const bytes = Uint8Array.from(binary, (value) => value.charCodeAt(0));
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
    text = new TextDecoder("utf-8", { fatal: true }).decode(await new Response(stream).arrayBuffer());
  } else if (codec !== "json") throw invalid();
  return JSON.parse(text);
}

export async function loadLegacyCache(readMany) {
  const [saved] = await readMany([LEGACY_MANIFEST_KEY]);
  if (saved === null) return null;
  if (!record(saved) || !Number.isSafeInteger(saved.generation) || saved.generation < 0 ||
      !record(saved.tables) || (saved.version !== undefined && saved.version !== 2) ||
      Object.keys(saved.tables).some((name) => !TABLES.includes(name))) throw invalid();
  const restored = { articles: [], feeds: [], categories: [], feedIcons: [], meta: {} };
  for (const table of TABLES) {
    const entry = saved.tables[table];
    if (entry === undefined) continue;
    const codec = saved.version === 2 ? entry?.codec : "json";
    const keys = saved.version === 2 ? entry?.keys : entry;
    if (!Array.isArray(keys) || !keys.length || !["json", "gzip-base64"].includes(codec)) throw invalid();
    let tableGeneration;
    keys.forEach((key, index) => {
      if (typeof key !== "string" || !key.startsWith(`${LEGACY_PREFIX}${table}.`)) throw invalid();
      const match = /^(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(key.slice(`${LEGACY_PREFIX}${table}.`.length));
      const generation = Number(match?.[1]);
      if (!match || !Number.isSafeInteger(generation) || generation > saved.generation || Number(match[2]) !== index ||
          (tableGeneration !== undefined && tableGeneration !== generation)) throw invalid();
      tableGeneration = generation;
    });
    const parts = await readMany(keys);
    const bound = codec === "json" ? 256 * 1024 : 1024 * 1024;
    if (parts.some((part) => typeof part !== "string" || !part.length || part.length > bound)) throw invalid();
    let value;
    try { value = await decodeCacheValue(codec, parts.join("")); }
    catch (cause) {
      if (cause?.code === "UNSUPPORTED") throw cause;
      throw invalid();
    }
    if (table === "meta") {
      if (!record(value) || (value.lastSyncTime !== undefined &&
          (typeof value.lastSyncTime !== "string" || !Number.isFinite(Date.parse(value.lastSyncTime))))) throw invalid();
    } else if (!Array.isArray(value) || value.some((row) => !record(row))) throw invalid();
    restored[table] = value;
  }
  if (saved.version !== 2) delete restored.meta.lastSyncTime;
  return restored;
}
