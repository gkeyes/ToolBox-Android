import { toast } from "sonner";
import { atom } from "nanostores";
import minifluxAPI from "../api/miniflux.js";
import {
  getLastSyncTime, prepareArticleSync, stageArticleSync, prepareArticleSyncCommit, commitArticleSync,
  abortArticleSync, invalidateArticleReads, addCategory, updateCategory,
  deleteFeedWithArticles, getFeedIcon, setFeedIcon, addFeeds,
} from "../db/storage.js";
import { settingsState } from "./settingsStore.js";
import { authState } from "./authStore.js";
import { runPrioritizedArticleSync } from "../toolbox/sync-priority.mjs";

export const isOnline = atom(navigator.onLine);
export const isSyncing = atom(false);
export const syncProgress = atom("");
export const lastSync = atom(null);
export const error = atom(null);
let syncInterval = null;
let accountEpoch = 0;
const pendingManual = [];
const pendingAutomatic = [];
const accountInvalidators = new Set();
const recoveries = [];
let activeOperation = null;
let pumpScheduled = false;
let iconQueue = Promise.resolve();
let currentSync = null;
let currentSyncMode = null;
let backgroundPreemptRequested = false;

function cancellation() {
  const failure = new Error("登录状态已改变，此次操作已停止。");
  failure.code = "ACCOUNT_CHANGED";
  return failure;
}

function accountCheck(epoch, requireOnline = true, networkPriority = "foreground") {
  const check = (needsNetwork = requireOnline) => {
    if (epoch !== accountEpoch || !authState.get().userId) throw cancellation();
    if (needsNetwork && (!isOnline.get() || navigator.onLine === false)) throw new Error("当前离线，请联网后重试；操作尚未执行。");
  };
  check.networkPriority = networkPriority;
  return check;
}

function syncPreempted() {
  const failure = new Error("后台同步已让位给前台操作。");
  failure.code = "SYNC_PREEMPTED";
  return failure;
}

export function requestForegroundPriority() {
  if (currentSync && currentSyncMode === "background") backgroundPreemptRequested = true;
}

export function onAccountInvalidated(listener) {
  accountInvalidators.add(listener);
  return () => accountInvalidators.delete(listener);
}

// A server acknowledgement is retained until its local durable patch succeeds.
// Following actions, including the final sync commit, first drain this barrier.
export function registerAccountRecovery(recovery) {
  recoveries.push({ epoch: accountEpoch, recovery });
}

function pumpAccountQueue() {
  if (activeOperation || pumpScheduled) return;
  pumpScheduled = true;
  queueMicrotask(() => {
    pumpScheduled = false;
    const item = pendingManual.shift() ?? pendingAutomatic.shift();
    if (!item) return;
    const check = accountCheck(item.epoch, item.requireOnline, item.priority === "auto" ? "background" : "foreground");
    const operation = (async () => {
      check(false);
      while (recoveries.length) {
        const pending = recoveries[0];
        if (pending.epoch !== accountEpoch) { recoveries.shift(); continue; }
        await pending.recovery(accountCheck(pending.epoch, false));
        check(false);
        if (recoveries[0] === pending) recoveries.shift();
      }
      check();
      return item.task(check);
    })();
    activeOperation = operation;
    operation.then(item.resolve, item.reject).finally(() => {
      if (activeOperation === operation) activeOperation = null;
      pumpAccountQueue();
    });
  });
}

// Automatic batches yield to pending manual intent; an already dispatched
// operation keeps ownership through server acknowledgement and local commit.
export function runAccountOperation(task, { requireOnline = true, priority = "manual" } = {}) {
  const operation = new Promise((resolve, reject) => {
    const queue = priority === "auto" ? pendingAutomatic : pendingManual;
    queue.push({ task, requireOnline, priority, epoch: accountEpoch, resolve, reject });
  });
  pumpAccountQueue();
  return operation;
}

export function cancelAccountOperations() {
  accountEpoch += 1;
  for (const listener of accountInvalidators) listener();
  recoveries.length = 0;
  for (const item of [...pendingManual.splice(0), ...pendingAutomatic.splice(0)]) item.reject(cancellation());
  invalidateArticleReads();
  stopAutoSync();
  return Promise.allSettled([activeOperation, iconQueue, currentSync]);
}

// Catalog and server-wide scope mutations exclude a complete sync. Waiting is outside the
// account writer, so they cannot block the sync's final commit behind themselves.
export function runAccountScopeOperation(task) {
  const check = accountCheck(accountEpoch);
  return (async () => {
    requestForegroundPriority();
    while (currentSync) { await currentSync.catch(() => {}); check(false); }
    check();
    return runAccountOperation(task);
  })();
}

export function createCachedCategory(title) {
  return runAccountScopeOperation(async (check) => {
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
  return runAccountScopeOperation(async (check) => {
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
  return runAccountScopeOperation(async (check) => {
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

const mapServerFeed = (feed) => ({
  id: feed.id, title: feed.title, url: feed.feed_url, site_url: feed.site_url,
  crawler: feed.crawler, hide_globally: feed.hide_globally,
  categoryId: feed.category?.id, parsing_error_count: feed.parsing_error_count,
  scraper_rules: feed.scraper_rules, keeplist_rules: feed.keeplist_rules,
  blocklist_rules: feed.blocklist_rules, rewrite_rules: feed.rewrite_rules,
  block_filter_entry_rules: feed.block_filter_entry_rules,
});

export function refreshSingleFeed(feedId) {
  return runAccountScopeOperation(async (check) => {
    const serverFeed = await minifluxAPI.getFeed(feedId, check);
    check(false);
    const localFeed = mapServerFeed(serverFeed);
    await addFeeds([localFeed]);
    check(false);
    const { feeds, unreadCounts, starredCounts } = await import("./feedsStore.js");
    feeds.set([...feeds.get().filter((item) => item.id !== localFeed.id), localFeed]);
    unreadCounts.set({ ...unreadCounts.get(), [localFeed.id]: unreadCounts.get()[localFeed.id] || 0 });
    starredCounts.set({ ...starredCounts.get(), [localFeed.id]: starredCounts.get()[localFeed.id] || 0 });
    return localFeed;
  });
}

const mapEntryToArticle = (entry) => ({
  id: entry.id, feedId: entry.feed?.id, title: entry.title, author: entry.author,
  url: entry.url, content: entry.content, status: entry.status,
  starred: entry.starred ? 1 : 0, published_at: entry.published_at,
  created_at: entry.created_at, reading_time: entry.reading_time,
  enclosures: entry.enclosures || [], changed_at: entry.changed_at,
});

// Drain sibling fetches before discarding their staging token. Interactive
// mutations remain independent, and logout waits for the outstanding fetches.
async function joinSyncRequests(tasks, check) {
  const results = await Promise.allSettled(tasks);
  check();
  const failure = results.find((result) => result.status === "rejected");
  if (failure) throw failure.reason;
  return results.map((result) => result.value);
}

async function collectSnapshot(baseCheck) {
  let requestFailure;
  const check = (needsNetwork = true) => {
    baseCheck(needsNetwork);
    if (currentSyncMode === "background" && backgroundPreemptRequested) throw syncPreempted();
    if (requestFailure) throw requestFailure;
  };
  check.networkPriority = "background";
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
  const consume = async (method, args, priority, progressLabel) => {
    let count = 0;
    const onPage = async (batch) => {
      await addEntries(batch, priority);
      count += batch.length;
      if (progressLabel) syncProgress.set(`${progressLabel} · ${count}`);
    };
    const collected = await method(...args, onPage);
    // Keeps the collection API usable for existing consumers and injected tests.
    if (Array.isArray(collected)) await onPage(collected);
  };
  syncProgress.set("正在准备未读文章同步…");
  try {
    const since = previous ? new Date(previous.getTime() - 24 * 60 * 60 * 1000) : null;
    await runPrioritizedArticleSync({
      initial: !previous,
      setProgress: (message) => syncProgress.set(message),
      unread: previous
        ? () => consume(minifluxAPI.getUnreadChangedEntries, [since, check], -1, "正在优先同步未读文章")
        : () => consume(minifluxAPI.getAllUnreadEntries, [check], -1, "正在优先同步未读文章"),
      starred: () => consume(minifluxAPI.getAllStarredEntries, [check], 1, "正在同步已读收藏"),
      readChanged: () => consume(minifluxAPI.getReadChangedEntries, [since, check], 0, "正在同步已读状态与收藏"),
    });
    await staging;
    check();

    // Catalog requests wait until unread and article-state work finishes. This
    // makes unread data the first server workload and avoids competing with it.
    syncProgress.set("正在同步订阅信息…");
    const [serverFeeds, serverCategories] = await join([
      minifluxAPI.getFeeds(check), minifluxAPI.getCategories(check),
    ]);
    await staging;
    check();
    return {
      token, syncedAt,
      feeds: serverFeeds.map(mapServerFeed),
      categories: serverCategories.map((category) => ({ id: category.id, title: category.title })),
    };
  } catch (failure) {
    await staging;
    await abortArticleSync(token).catch(() => {});
    throw failure;
  }
}

export function sync(mode = "foreground") {
  if (currentSync) {
    if (mode === "foreground" && currentSyncMode === "background") {
      requestForegroundPriority();
      return currentSync.catch(() => {}).then(() => sync("foreground"));
    }
    return currentSync;
  }
  currentSyncMode = mode;
  backgroundPreemptRequested = false;
  isSyncing.set(true);
  syncProgress.set("正在准备同步…");
  error.set(null);
  const check = accountCheck(accountEpoch, true, "background");
  const operation = (async () => {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      // Finish preceding catalog actions/recovery before reserving this sync.
      await runAccountOperation((queuedCheck) => queuedCheck());
      check();
      const collected = await collectSnapshot(check);
      try {
        check();
        syncProgress.set("正在保存完整阅读数据…");
        const catalogs = { feeds: collected.feeds, categories: collected.categories };
        await prepareArticleSyncCommit(collected.token, catalogs);
        check();
        await runAccountOperation(async (commitCheck) => {
          await commitArticleSync(collected.token, catalogs);
          commitCheck(false);
          const { refreshArticleStateAfterSync } = await import("./articlesStore.js");
          await refreshArticleStateAfterSync(commitCheck);
          commitCheck(false);
          lastSync.set(collected.syncedAt);
        });
        return;
      } catch (failure) {
        await abortArticleSync(collected.token).catch(() => {});
        check(false);
        if (failure.code !== "SYNC_STATE_CONFLICT" || attempt === 1) throw failure;
        syncProgress.set("文章状态已变化，正在重新读取同步结果…");
      }
    }
  })();
  currentSync = operation.catch((failure) => {
    if (failure.code === "SYNC_PREEMPTED") return;
    if (failure.code !== "ACCOUNT_CHANGED") {
      error.set(failure);
      toast.error(failure.message || "同步失败，请检查网络权限后重试。");
    }
    throw failure;
  }).finally(() => {
    currentSync = null;
    currentSyncMode = null;
    backgroundPreemptRequested = false;
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

const foregroundIntent = () => requestForegroundPriority();
const visibilityIntent = () => {
  if (document.visibilityState === "visible") requestForegroundPriority();
};

export function startAutoSync() {
  if (typeof window === "undefined") return;
  performSync();
  resetSyncInterval();
  window.addEventListener("beforeunload", stopAutoSync);
  window.addEventListener("pointerdown", foregroundIntent, true);
  window.addEventListener("keydown", foregroundIntent, true);
  document.addEventListener("visibilitychange", visibilityIntent);
}

export function stopAutoSync() {
  if (syncInterval) clearInterval(syncInterval);
  syncInterval = null;
  globalThis.window?.removeEventListener("beforeunload", stopAutoSync);
  globalThis.window?.removeEventListener("pointerdown", foregroundIntent, true);
  globalThis.window?.removeEventListener("keydown", foregroundIntent, true);
  globalThis.document?.removeEventListener("visibilitychange", visibilityIntent);
}

async function performSync() {
  if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
  if (!isOnline.get() || isSyncing.get() || !authState.get().userId) return;
  const minutes = parseInt(settingsState.get().syncInterval, 10);
  if (!(minutes > 0)) return;
  const previous = getLastSyncTime();
  if (!previous || Date.now() - previous.getTime() > minutes * 60 * 1000) {
    try { await sync("background"); } catch { /* sync already reports a user-visible error */ }
  }
}

export const forceSync = () => sync("foreground");
export const backgroundSync = () => sync("background");
