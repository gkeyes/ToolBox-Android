import { createArticleCache } from "../toolbox/cache.js";

let cache;
function getCache() {
  if (!cache) {
    if (!globalThis.ToolBox?.storage) throw new Error("请在 ToolBox 中打开 NextFlux。");
    cache = createArticleCache(globalThis.ToolBox.storage, () => {
      globalThis.dispatchEvent?.(new CustomEvent("nextflux:cache-evicted"));
    });
  }
  return cache;
}

export const initializeArticleCache = () => getCache().initialize();
export const clearArticleCache = () => getCache().clear();
const read = (select) => getCache().read(select);
const change = (tables, update) => getCache().transact(tables, update);
const upsert = (rows, values, key = "id") => {
  const map = new Map(rows.map((row) => [row[key], row]));
  for (const value of values) map.set(value[key], { ...value });
  return [...map.values()];
};

export const getCachedArticles = () => read((s) => s.articles);
export const commitSyncSnapshot = ({ feeds, categories, articles, syncedAt }) =>
  change(["feeds", "categories", "articles", "feedIcons", "meta"], (s) => {
    const feedIds = new Set(feeds.map((feed) => feed.id));
    s.feeds = feeds;
    s.categories = categories;
    s.articles = articles.filter((article) => feedIds.has(article.feedId) && article.status !== "removed");
    s.feedIcons = s.feedIcons.filter((icon) => feedIds.has(icon.feedId));
    s.meta.lastSyncTime = syncedAt.toISOString();
  });

export const addFeeds = (feeds) => change(["feeds"], (s) => { s.feeds = upsert(s.feeds, feeds); });
export const getFeeds = () => read((s) => s.feeds);
export const deleteAllFeeds = () => change(["feeds"], (s) => { s.feeds = []; });
export const deleteFeed = (id) => change(["feeds"], (s) => { s.feeds = s.feeds.filter((v) => v.id !== id); });
export const deleteFeedWithArticles = (id) => change(["feeds", "articles", "feedIcons"], (s) => {
  s.feeds = s.feeds.filter((v) => v.id !== id);
  s.articles = s.articles.filter((v) => v.feedId !== id);
  s.feedIcons = s.feedIcons.filter((v) => v.feedId !== id);
});
export const deleteFeedIcon = (id) => change(["feedIcons"], (s) => { s.feedIcons = s.feedIcons.filter((v) => v.feedId !== id); });
export const addCategory = (category) => change(["categories"], (s) => { s.categories = upsert(s.categories, [category]); });
export const getCategories = () => read((s) => s.categories);
export const deleteAllCategory = () => change(["categories"], (s) => { s.categories = []; });
export const deleteCategory = (id) => change(["categories"], (s) => { s.categories = s.categories.filter((v) => v.id !== id); });
export const updateCategory = (id, title) => change(["categories"], (s) => {
  s.categories = s.categories.map((v) => v.id === id ? { ...v, title } : v);
});
export const setLastSyncTime = (time) => change(["meta"], (s) => { s.meta.lastSyncTime = time.toISOString(); });
export function getLastSyncTime() {
  const value = getCache().peek().meta.lastSyncTime;
  return value ? new Date(value) : null;
}
export const addArticles = (articles) => change(["articles"], (s) => {
  s.articles = upsert(s.articles, articles).filter((v) => v.status !== "removed");
});
export const deleteArticlesByFeedId = (id) => change(["articles"], (s) => {
  s.articles = s.articles.filter((v) => v.feedId !== id);
});
export const getUnreadCount = (id) => read((s) => s.articles.filter((v) => v.feedId === id && v.status === "unread").length);
export const getStarredCount = (id) => read((s) => s.articles.filter((v) => v.feedId === id && v.starred === 1).length);

function matching(articles, feedIds, filter) {
  const ids = new Set(feedIds);
  return articles.filter((v) => ids.has(v.feedId) &&
    (filter === "unread" ? v.status === "unread" : filter === "starred" ? v.starred === 1 : v.status !== "removed"));
}
function sorted(articles, field) {
  return articles.sort((a, b) => {
    const left = a[field] ?? "";
    const right = b[field] ?? "";
    return left < right ? -1 : left > right ? 1 : a.id - b.id;
  });
}
export const getArticlesCount = (ids, filter = "all") => read((s) => matching(s.articles, ids, filter).length);
export function getArticlesByPage(ids, filter = "all", page = 1, pageSize = 30, direction = "desc", field = "published_at") {
  return read((s) => {
    const rows = sorted(matching(s.articles, ids, filter), field);
    if (direction === "desc") rows.reverse();
    const offset = Math.max(0, page - 1) * pageSize;
    return rows.slice(offset, offset + pageSize);
  });
}
export function getArticleById(id) {
  return read((state) => {
    const article = state.articles.find((v) => v.id === parseInt(id, 10));
    return article ? { ...article, feed: state.feeds.find((v) => v.id === article.feedId) } : null;
  });
}
export function searchArticles(keyword, showHiddenFeeds = false, field = "published_at") {
  return read((state) => {
    const ids = new Set(state.feeds.filter((v) => showHiddenFeeds || !v.hide_globally).map((v) => v.id));
    return sorted(state.articles.filter((v) => ids.has(v.feedId) && v.title?.toLowerCase().includes(keyword.toLowerCase())), field).reverse();
  });
}
export const getFeedIcon = (id) => read((s) => s.feedIcons.find((v) => v.feedId === id));
export const setFeedIcon = (icon) => change(["feedIcons"], (s) => {
  s.feedIcons = upsert(s.feedIcons, [{ ...icon, updated_at: new Date().toISOString() }], "feedId");
});
