// The recent window is additional to unread/bookmarked articles. Zero keeps
// the existing sync policy without prefetching or pruning a recent window.
export const RECENT_CACHE_LIMITS = Object.freeze([50, 100, 200, 500, 1000]);
export const DEFAULT_RECENT_CACHE_LIMIT = 200;

export function normalizeRecentCacheLimit(value) {
  const candidate = typeof value === "number" || (typeof value === "string" && value.trim() !== "")
    ? Number(value) : Number.NaN;
  return candidate === 0 || RECENT_CACHE_LIMITS.includes(candidate) ? candidate : DEFAULT_RECENT_CACHE_LIMIT;
}
