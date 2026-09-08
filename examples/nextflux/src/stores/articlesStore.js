import { atom } from "nanostores";
import { runAccountOperation } from "./syncStore.js";
import {
  getFeeds,
  getCachedArticles,
  getArticleById,
  getArticlesCount,
  getArticlesByPage,
  addArticles,
  getUnreadCount,
  getStarredCount,
} from "../db/storage.js";
import minifluxAPI from "../api/miniflux.js";
import { starredCounts, unreadCounts } from "./feedsStore.js";
import { settingsState } from "./settingsStore.js";

export const filteredArticles = atom([]);
export const activeArticle = atom(null);
export const loading = atom(false); // 加载文章列表
export const loadingMore = atom(false); // 加载更多文章
export const loadingOriginContent = atom(false);
export const error = atom(null);
export const filter = atom("all");
export const imageGalleryActive = atom(false);
export const hasMore = atom(true);
export const currentPage = atom(1);
export const pageSize = atom(30);
export const visibleRange = atom({
  startIndex: 0,
  endIndex: 0,
});

// 加载文章列表
export async function loadArticles(
  sourceId = null,
  type = "feed",
  page = 1,
  append = false,
) {
  error.set(null);

  try {
    const feeds = await getFeeds();
    const settings = settingsState.get();
    const showHiddenFeeds = settings.showHiddenFeeds;
    let targetFeeds;

    // 根据类型确定要加载的订阅源
    if (type === "category" && sourceId) {
      targetFeeds = feeds.filter(
        (feed) =>
          feed.categoryId === parseInt(sourceId) &&
          (showHiddenFeeds || !feed.hide_globally),
      );
    } else if (sourceId) {
      targetFeeds = feeds.filter(
        (feed) =>
          feed.id === parseInt(sourceId) &&
          (showHiddenFeeds || !feed.hide_globally),
      );
    } else {
      targetFeeds = showHiddenFeeds
        ? feeds
        : feeds.filter((feed) => !feed.hide_globally);
    }

    // 获取目标订阅源的文章总数
    const total = await getArticlesCount(
      targetFeeds.map((feed) => feed.id),
      filter.get(),
    );

    // 分页获取文章
    const articles = await getArticlesByPage(
      targetFeeds.map((feed) => feed.id),
      filter.get(),
      page,
      pageSize.get(),
      settings.sortDirection,
      settings.sortField,
    );

    // 计算分页状态
    const isMore = articles.length === pageSize.get();

    // 根据是否追加来更新文章列表
    if (append) {
      filteredArticles.set([...filteredArticles.get(), ...articles]);
      hasMore.set(isMore);
      currentPage.set(page);
    }

    return { articles: articles, total, isMore };
  } catch (err) {
    console.error("加载文章失败:", err);
    error.set("加载文章失败");
  }
}

// Server acknowledgement precedes cache/UI changes. Offline mutations are rejected
// by the shared account queue rather than appearing to succeed only on this device.
function cachedRecord(article) {
  const value = { ...article };
  delete value.feed;
  return value;
}

async function publishUpdates(articles, check) {
  check(false);
  const updates = new Map(articles.map((article) => [article.id, article]));
  filteredArticles.set(filteredArticles.get().map((article) => {
    const update = updates.get(article.id);
    return update ? { ...article, status: update.status, starred: update.starred } : article;
  }));
  const active = activeArticle.get();
  const update = active && updates.get(active.id);
  if (update) activeArticle.set({ ...active, status: update.status, starred: update.starred });
  for (const feedId of new Set(articles.map((article) => article.feedId))) {
    const [unread, starred] = await Promise.all([getUnreadCount(feedId), getStarredCount(feedId)]);
    check(false);
    unreadCounts.set({ ...unreadCounts.get(), [feedId]: unread });
    starredCounts.set({ ...starredCounts.get(), [feedId]: starred });
  }
}

async function saveAcknowledged(articles, check) {
  check();
  try {
    await addArticles(articles.map(cachedRecord));
  } catch {
    throw new Error("服务器已保存，但本地缓存保存失败；请刷新同步后再操作。");
  }
  await publishUpdates(articles, check);
}

export function updateArticleStatus(article) {
  return runAccountOperation(async (check) => {
    const current = await getArticleById(article.id);
    check();
    if (!current) throw new Error("该文章已不在本地缓存，请刷新后重试。");
    await minifluxAPI.updateEntryStatus(current);
    await saveAcknowledged([{ ...current, status: current.status === "read" ? "unread" : "read" }], check);
  });
}

export function updateArticleStarred(article) {
  return runAccountOperation(async (check) => {
    const current = await getArticleById(article.id);
    check();
    if (!current) throw new Error("该文章已不在本地缓存，请刷新后重试。");
    await minifluxAPI.updateEntryStarred(current);
    await saveAcknowledged([{ ...current, starred: current.starred === 1 ? 0 : 1 }], check);
  });
}

export function markAllAsRead(type = "all", id = null) {
  return runAccountOperation(async (check) => {
    const feeds = await getFeeds();
    const feedIds = new Set(feeds.filter((feed) => type === "feed" ? feed.id === Number(id) :
      type === "category" ? feed.categoryId === Number(id) : true).map((feed) => feed.id));
    const articles = (await getCachedArticles()).filter((article) => feedIds.has(article.feedId) && article.status !== "read");
    check();
    await minifluxAPI.markAllAsRead(type, id);
    await saveAcknowledged(articles.map((article) => ({ ...article, status: "read" })), check);
  });
}

function markRangeAsRead(articleId, direction) {
  return runAccountOperation(async (check) => {
    const visible = filteredArticles.get();
    const index = visible.findIndex((article) => article.id === articleId);
    if (index < 0) return;
    // Keep the upstream behavior: the selected article belongs to both ranges.
    const selected = new Set((direction === "above" ? visible.slice(0, index + 1) : visible.slice(index)).map((article) => article.id));
    const articles = (await getCachedArticles()).filter((article) => selected.has(article.id) && article.status !== "read");
    if (!articles.length) return;
    check();
    await minifluxAPI.updateEntriesStatus(articles.map((article) => article.id), "read");
    await saveAcknowledged(articles.map((article) => ({ ...article, status: "read" })), check);
  });
}

export const markAboveAsRead = (articleId) => markRangeAsRead(articleId, "above");
export const markBelowAsRead = (articleId) => markRangeAsRead(articleId, "below");
