// Duplicate entries (often the same story fetched twice by Miniflux). Two
// entries are duplicates when the title the list shows is exactly the same, or
// the original article URL is exactly the same, after trimming surrounding
// whitespace. An empty title or URL never matches anything.
//
// The first entry in the given order is kept and later copies are hidden. Each
// entry is compared with the entries kept so far, so no two shown entries share
// a title or a URL. This only decides what is shown: nothing is marked read,
// sent to the server or removed from the cache.

const text = (value) => (typeof value === "string" ? value.trim() : "");

// Placeholder titles that many different posts share (a photo post titled
// 图片, an Untitled entry, a line of hashtags) never count as the same title;
// such posts are still matched by URL.
const GENERIC_TITLES = new Set(["图片", "图片分享", "视频", "无标题", "（无标题）", "(无标题)", "untitled", "no title", "(no title)", "photo", "image", "video"]);
// Only hashtags, optionally after an emoji or symbol: "#系统资讯", "🖼 #系统资讯",
// full-width "＃系统资讯", "# 系统".
const HASHTAGS_ONLY = /^[\p{Extended_Pictographic}\p{S}\uFE0F\u200D\s]*(?:[#＃]\s?[^\s#＃]+\s*)+$/u;
const generic = (title) => GENERIC_TITLES.has(title.toLowerCase()) || HASHTAGS_ONLY.test(title);
export const duplicateTitle = (article) => {
  const title = text(article?.titleText || article?.title);
  return title && !generic(title) ? title : "";
};
export const duplicateUrl = (article) => text(article?.url);

// Returns the input array itself when disabled or when nothing is hidden.
export function dedupeArticles(articles, { enabled = true } = {}) {
  if (!enabled || !Array.isArray(articles) || articles.length < 2) return articles;
  const titles = new Set();
  const urls = new Set();
  const kept = [];
  for (const article of articles) {
    const title = duplicateTitle(article);
    const url = duplicateUrl(article);
    if ((title && titles.has(title)) || (url && urls.has(url))) continue;
    if (title) titles.add(title);
    if (url) urls.add(url);
    kept.push(article);
  }
  return kept.length === articles.length ? articles : kept;
}

// The ids of the later copies in `rows` (already in list order), by the same
// rule as dedupeArticles.
export function duplicateIds(rows) {
  const kept = dedupeArticles(rows, { enabled: true });
  if (kept === rows) return new Set();
  const shown = new Set(kept.map((row) => row.id));
  return new Set(rows.filter((row) => !shown.has(row.id)).map((row) => row.id));
}
