import { toast } from "sonner";
import { atom } from "nanostores";
import minifluxAPI from "../api/miniflux.js";
import {
  getLastSyncTime, prepareArticleSync, stageArticleSync, commitArticleSync,
  abortArticleSync, invalidateArticleReads, addCategory, updateCategory,
  deleteFeedWithArticles, getFeedIcon, setFeedIcon,
} from "../db/storage.js";
import { settingsState } from "./settingsStore.js";
import { authState } from "./authStore.js";

export const isOnline = atom(navigator.onLine);
export const isSyncing = atom(false);
export const syncProgress = atom("");
export const lastSync = atom(null);
export const error = atom(null);
let syncInterval = null;
let accountEpoch = 0;
let accountQueue = Promise.resolve();
let iconQueue = Promise.resolve();
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
  invalidateArticleReads();
  stopAutoSync();
  return Promise.allSettled([accountQueue, iconQueue]);
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
  const epoch = accountEpoch;
  // Only an active icon joins the account queue. Optional queued icons yield
  // to a refresh, while logout still drains and invalidates all of them.
  const operation = iconQueue.then(async () => {
    if (epoch !== accountEpoch) throw cancellation();
    if (currentSync) await currentSync.catch(() => {});
    if (epoch !== accountEpoch) throw cancellation();
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
  });
  iconQueue = operation.catch(() => {});
  return operation;
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
  enclosures: entry.enclosures || [], changed_at: entry.changed_at,
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

async function collectSnapshot(accountCheck) {
  let requestFailure;
  const check = () => {
    accountCheck();
    if (requestFailure) throw requestFailure;
  };
  const join = (tasks) => joinSyncRequests(tasks.map((task) => task.catch((failure) => {
    requestFailure ||= failure;
    throw failure;
  })), check);
  // Starting before network requests preserves the existing overlap semantics.
  const syncedAt = new Date();
  const previous = getLastSyncTime();
  const auth = authState.get();
  const token = await prepareArticleSync({
    full: !previous, syncedAt: syncedAt.toISOString(),
    account: { serverUrl: auth.serverUrl, userId: String(auth.userId) },
  });
  const entryChanges = new Map();
  let staging = Promise.resolve();
  const addEntries = (entries, priority) => {
    const operation = staging.then(async () => {
      check();
      const changed = [];
      for (const entry of entries) {
        const timestamp = Date.parse(entry.changed_at);
        const old = entryChanges.get(entry.id);
        if (old) {
          if (Number.isFinite(timestamp) && Number.isFinite(old.timestamp)) {
            // Equal server versions keep changed/unread before created/starred,
            // independent of which parallel response happens to arrive first.
            if (timestamp < old.timestamp || (timestamp === old.timestamp && priority >= old.priority)) continue;
          } else if (priority <= old.priority) continue;
        }
        entryChanges.set(entry.id, { timestamp, priority });
        changed.push(mapEntryToArticle(entry));
      }
      if (changed.length) await stageArticleSync(token, changed);
      check();
    });
    staging = operation.catch((failure) => { requestFailure ||= failure; });
    return operation;
  };
  const consume = async (method, args, priority) => {
    const collected = await method(...args, (batch) => addEntries(batch, priority));
    // Keeps the collection API usable for existing consumers and injected tests.
    if (Array.isArray(collected)) await addEntries(collected, priority);
  };
  syncProgress.set("正在读取订阅和文章…");
  try {
    const collectArticles = async () => {
      if (!previous) {
        const unread = async () => {
          let offset = 0;
          let total = Infinity;
          let pageSize = 1000;
          const seen = new Set();
          while (offset < total) {
            check();
            const page = await minifluxAPI.getUnreadEntriesByPage(offset, pageSize, check);
            check();
            if (!Array.isArray(page.entries) || !Number.isFinite(page.total) || page.total < 0) {
              throw new Error("同步返回的文章列表无效，请重试。");
            }
            total = page.total;
            if (!page.entries.length && offset < total) throw new Error("同步结果不完整，请重试。");
            const fresh = page.entries.filter((entry) => {
              if (seen.has(entry.id)) return false;
              seen.add(entry.id);
              return true;
            });
            if (page.entries.length && !fresh.length) throw new Error("服务器重复返回同一页文章，同步结果不完整，请重试。");
            await addEntries(fresh, 0);
            offset += page.entries.length;
            syncProgress.set(`正在同步文章 · ${offset} / ${total}`);
            if (page.entries.length) pageSize = Math.min(pageSize, page.entries.length);
          }
        };
        await join([unread(), consume(minifluxAPI.getAllStarredEntries, [check], 1)]);
      } else {
        const since = new Date(previous.getTime() - 24 * 60 * 60 * 1000);
        await join([
          consume(minifluxAPI.getChangedEntries, [since, check], 0),
          consume(minifluxAPI.getNewEntries, [since, check], 1),
        ]);
      }
    };
    // Four requests at most; each article stream awaits its bounded staging
    // batch. The window never accumulates the archive's complete article bodies.
    const [serverFeeds, serverCategories] = await join([
      minifluxAPI.getFeeds(), minifluxAPI.getCategories(), collectArticles(),
    ]);
    await staging;
    check();
    return {
      token, syncedAt,
      feeds: serverFeeds.map((feed) => ({
        id: feed.id, title: feed.title, url: feed.feed_url, site_url: feed.site_url,
        crawler: feed.crawler, hide_globally: feed.hide_globally,
        categoryId: feed.category?.id, parsing_error_count: feed.parsing_error_count,
        scraper_rules: feed.scraper_rules, keeplist_rules: feed.keeplist_rules,
        blocklist_rules: feed.blocklist_rules, rewrite_rules: feed.rewrite_rules,
      })),
      categories: serverCategories.map((category) => ({ id: category.id, title: category.title })),
    };
  } catch (failure) {
    await staging;
    await abortArticleSync(token).catch(() => {});
    throw failure;
  }
}

export function sync() {
  if (currentSync) return currentSync;
  isSyncing.set(true);
  syncProgress.set("正在准备同步…");
  error.set(null);
  const operation = runAccountOperation(async (check) => {
    const snapshot = await collectSnapshot(check);
    try {
      check();
      syncProgress.set("正在保存完整阅读数据…");
      await commitArticleSync(snapshot.token, { feeds: snapshot.feeds, categories: snapshot.categories });
      check(false);
      const { refreshArticleStateAfterSync } = await import("./articlesStore.js");
      await refreshArticleStateAfterSync(check);
      check(false);
      lastSync.set(snapshot.syncedAt);
    } catch (failure) {
      await abortArticleSync(snapshot.token).catch(() => {});
      throw failure;
    }
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
    syncProgress.set("");
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
