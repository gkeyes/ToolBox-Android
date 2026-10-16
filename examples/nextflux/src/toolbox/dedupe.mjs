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

export const duplicateTitle = (article) => text(article?.titleText || article?.title);
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
