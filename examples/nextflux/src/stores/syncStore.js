import { toast } from "sonner";
import { atom } from "nanostores";
import minifluxAPI from "../api/miniflux.js";
import {
  getCachedArticles, getLastSyncTime, commitSyncSnapshot, addCategory,
  updateCategory, deleteFeedWithArticles, getFeedIcon, setFeedIcon,
} from "../db/storage.js";
import { boundArticles } from "../toolbox/cache.js";
import { settingsState } from "./settingsStore.js";
import { authState } from "./authStore.js";

export const isOnline = atom(navigator.onLine);
export const isSyncing = atom(false);
export const lastSync = atom(null);
export const error = atom(null);
let syncInterval = null;
let accountEpoch = 0;
let accountQueue = Promise.resolve();
let currentSync = null;

function cancellation() {
  const failure = new Error("登录状态已改变，此次操作已停止。");
  failure.code = "ACCOUNT_CHANGED";
  return failure;
}

// Sync and article mutations share this queue so a stale sync cannot overwrite a
// just-completed read/bookmark action. Logout invalidates even queued requests.
export function runAccountOperation(task, { requireOnline = true } = {}) {
  const epoch = accountEpoch;
  const check = (needsNetwork = requireOnline) => {
    if (epoch !== accountEpoch || !authState.get().userId) throw cancellation();
    if (needsNetwork && (!isOnline.get() || navigator.onLine === false)) throw new Error("当前离线，请联网后重试；操作尚未执行。");
  };
  const operation = accountQueue.then(async () => {
    check();
    return task(check);
  });
  accountQueue = operation.catch(() => {});
  return operation;
}

export function cancelAccountOperations() {
  accountEpoch += 1;
  stopAutoSync();
  return accountQueue;
}

export function createCachedCategory(title) {
  return runAccountOperation(async (check) => {
    const created = await minifluxAPI.createCategory(title);
    check(false);
    const category = { id: created.id, title: created.title };
    await addCategory(category);
    check(false);
    const { categories } = await import("./feedsStore.js");
    check(false);
    categories.set([...categories.get().filter((value) => value.id !== category.id), category]);
    return category;
  });
}

export function renameCachedCategory(categoryId, title) {
  return runAccountOperation(async (check) => {
    await minifluxAPI.updateCategory(categoryId, title);
    check(false);
    await updateCategory(Number(categoryId), title);
    check(false);
    const { categories } = await import("./feedsStore.js");
    check(false);
    categories.set(categories.get().map((value) => value.id === Number(categoryId) ? { ...value, title } : value));
  });
}

export function removeCachedFeed(feedId) {
  return runAccountOperation(async (check) => {
    await minifluxAPI.deleteFeed(feedId);
    check(false);
    const id = Number(feedId);
    await deleteFeedWithArticles(id);
    check(false);
    const { feeds, unreadCounts, starredCounts } = await import("./feedsStore.js");
    check(false);
    feeds.set(feeds.get().filter((feed) => feed.id !== id));
    unreadCounts.set({ ...unreadCounts.get(), [id]: 0 });
    starredCounts.set({ ...starredCounts.get(), [id]: 0 });
  });
}

export function loadAccountFeedIcon(feedId) {
  return runAccountOperation(async (check) => {
    let icon = await getFeedIcon(feedId);
    check(false);
    if ((!icon || Date.now() - Date.parse(icon.updated_at) > 7 * 86400000) &&
        isOnline.get() && navigator.onLine !== false) {
      check(true);
      const fetched = await minifluxAPI.getIconByFeedId(feedId);
      check(false);
      if (fetched) {
        await setFeedIcon({ feedId, mime_type: fetched.mime_type, data: fetched.data });
        check(false);
        icon = fetched;
      }
    }
    return icon;
  }, { requireOnline: false });
}

if (typeof window !== "undefined") {
  window.addEventListener("online", () => isOnline.set(true));
  window.addEventListener("offline", () => isOnline.set(false));
}

const mapEntryToArticle = (entry) => ({
  id: entry.id, feedId: entry.feed?.id, title: entry.title, author: entry.author,
  url: entry.url, content: entry.content, status: entry.status,
  starred: entry.starred ? 1 : 0, published_at: entry.published_at,
  created_at: entry.created_at, reading_time: entry.reading_time,
  enclosures: entry.enclosures || [],
});

// Drain sibling requests on failure before releasing the account operation
// queue, so logout or a following mutation never races an unfinished sync.
async function joinSyncRequests(tasks, check) {
  const results = await Promise.allSettled(tasks);
  check();
  const failure = results.find((result) => result.status === "rejected");
  if (failure) throw failure.reason;
  return results.map((result) => result.value);
}

async function collectSnapshot(check) {
  // Start time (not completion time) leaves concurrent server changes eligible
  // for the next incremental sync, with the existing 24-hour overlap as well.
  const syncedAt = new Date();
  let articles = await getCachedArticles();
  check();
  let evicted = false;
  const entryChanges = new Map();
  const addEntries = (entries) => {
    const map = new Map(articles.map((article) => [article.id, article]));
    for (const entry of entries) {
      // Parallel endpoints can overlap. Keep the fresher server record even
      // when its response arrives first; retain deletion timestamps as well.
      const changedAt = Date.parse(entry.changed_at);
      const previousChange = entryChanges.get(entry.id);
      if (Number.isFinite(changedAt)) {
        if (previousChange !== undefined && changedAt <= previousChange) continue;
        entryChanges.set(entry.id, changedAt);
      }
      if (entry.status === "removed") map.delete(entry.id);
      else map.set(entry.id, mapEntryToArticle(entry));
    }
    const result = boundArticles([...map.values()]);
    articles = result.articles;
    evicted ||= result.evicted > 0;
  };
  const previous = getLastSyncTime();
  // Metadata and the two independent article streams use at most four requests
  // at once. Each stream retains sequential, adaptive, bounded pagination.
  const collectArticles = async () => {
    if (!previous) {
      articles = [];
      const unread = async () => {
        let offset = 0;
        let total = Infinity;
        let pageSize = 200;
        while (offset < total) {
          check();
          const page = await minifluxAPI.getUnreadEntriesByPage(offset, pageSize, check);
          check();
          if (!Array.isArray(page.entries) || !Number.isFinite(page.total) || page.total < 0) {
            throw new Error("同步返回的文章列表无效，请重试。");
          }
          total = page.total;
          if (!page.entries.length && offset < total) throw new Error("同步结果不完整，请重试。");
          addEntries(page.entries);
          offset += page.entries.length;
          // Remember quota/server reductions for the rest of this sync.
          if (page.entries.length) pageSize = Math.min(pageSize, page.entries.length);
        }
      };
      const [, starred] = await joinSyncRequests([
        unread(), minifluxAPI.getAllStarredEntries(check),
      ], check);
      addEntries(starred);
    } else {
      const since = new Date(previous.getTime() - 24 * 60 * 60 * 1000);
      const [changed, created] = await joinSyncRequests([
        minifluxAPI.getChangedEntries(since, check), minifluxAPI.getNewEntries(since, check),
      ], check);
      // Merge by server change time; older servers without it retain the
      // previous deterministic changed-then-created fallback.
      addEntries(changed);
      addEntries(created);
    }
  };
  const [serverFeeds, serverCategories] = await joinSyncRequests([
    minifluxAPI.getFeeds(), minifluxAPI.getCategories(), collectArticles(),
  ], check);
  return {
    feeds: serverFeeds.map((feed) => ({
      id: feed.id, title: feed.title, url: feed.feed_url, site_url: feed.site_url,
      crawler: feed.crawler, hide_globally: feed.hide_globally,
      categoryId: feed.category?.id, parsing_error_count: feed.parsing_error_count,
      scraper_rules: feed.scraper_rules, keeplist_rules: feed.keeplist_rules,
      blocklist_rules: feed.blocklist_rules, rewrite_rules: feed.rewrite_rules,
    })),
    categories: serverCategories.map((category) => ({ id: category.id, title: category.title })),
    articles, syncedAt, evicted,
  };
}

export function sync() {
  if (currentSync) return currentSync;
  isSyncing.set(true);
  error.set(null);
  const operation = runAccountOperation(async (check) => {
    const snapshot = await collectSnapshot(check);
    check();
    await commitSyncSnapshot(snapshot);
    check(false);
    lastSync.set(snapshot.syncedAt);
    if (snapshot.evicted) globalThis.dispatchEvent?.(new CustomEvent("nextflux:cache-evicted"));
  });
  currentSync = operation.catch((failure) => {
    if (failure.code !== "ACCOUNT_CHANGED") {
      error.set(failure);
      toast.error(failure.message || "同步失败，请检查网络权限后重试。");
    }
    throw failure;
  }).finally(() => {
    currentSync = null;
    isSyncing.set(false);
  });
  return currentSync;
}

function resetSyncInterval() {
  if (syncInterval) clearInterval(syncInterval);
  syncInterval = null;
  const minutes = parseInt(settingsState.get().syncInterval, 10);
  if (minutes > 0) syncInterval = setInterval(performSync, minutes * 60 * 1000);
}

export function startAutoSync() {
  if (typeof window === "undefined") return;
  performSync();
  resetSyncInterval();
  window.addEventListener("beforeunload", stopAutoSync);
}

export function stopAutoSync() {
  if (syncInterval) clearInterval(syncInterval);
  syncInterval = null;
  globalThis.window?.removeEventListener("beforeunload", stopAutoSync);
}

async function performSync() {
  if (!isOnline.get() || isSyncing.get() || !authState.get().userId) return;
  const minutes = parseInt(settingsState.get().syncInterval, 10);
  if (!(minutes > 0)) return;
  const previous = getLastSyncTime();
  if (!previous || Date.now() - previous.getTime() > minutes * 60 * 1000) {
    try { await sync(); } catch { /* sync already reports a user-visible error */ }
  }
}

export const forceSync = () => sync();
