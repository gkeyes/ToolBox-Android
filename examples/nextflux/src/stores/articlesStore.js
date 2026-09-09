import { atom, map } from "nanostores";
import { runAccountOperation, runAccountScopeOperation, registerAccountRecovery, onAccountInvalidated } from "./syncStore.js";
import { createAutoReadQueue } from "../toolbox/auto-read.js";
import {
  getFeeds, getArticleMetadata, getCachedArticleMetadata, getArticleIds,
  getArticleCounts, openArticleQuery, readArticleQueryPage, closeArticleQuery,
  patchArticleState,
} from "../db/storage.js";
import minifluxAPI from "../api/miniflux.js";
import { starredCounts, unreadCounts } from "./feedsStore.js";
import { settingsState } from "./settingsStore.js";

export const filteredArticles = atom([]);
export const activeArticle = atom(null);
export const articleContentRevision = map({});
export const loading = atom(false);
export const loadingMore = atom(false);
export const loadingOriginContent = atom(false);
export const error = atom(null);
export const filter = atom("all");
export const imageGalleryActive = atom(false);
export const hasMore = atom(true);
export const currentPage = atom(1);
export const pageSize = atom(30);
export const visibleRange = atom({ startIndex: 0, endIndex: 0 });

let queryGeneration = 0;
let querySession = null;
const stateOverrides = new Map();
export const getArticleQueryGeneration = () => queryGeneration;
const autoReadQueue = createAutoReadQueue({
  run: runAccountOperation,
  selectIds: (ids) => getArticleIds({ ids, statusNot: "read" }),
  send: (ids, check) => minifluxAPI.updateEntriesStatus(ids, "read", check),
  save: saveAcknowledged,
});
onAccountInvalidated(() => {
  const failure = new Error("登录状态已改变，此次操作已停止。");
  failure.code = "ACCOUNT_CHANGED";
  autoReadQueue.cancelAll(failure);
});

export const queueArticleRead = (article) => autoReadQueue.enqueue(article.id);

export function resetArticleQuery() {
  queryGeneration += 1;
  const old = querySession;
  querySession = null;
  loadingMore.set(false);
  if (old?.queryId) void closeArticleQuery(old.queryId).catch(() => {});
  return queryGeneration;
}

export function resetArticleState() {
  resetArticleQuery();
  stateOverrides.clear();
  filteredArticles.set([]);
  activeArticle.set(null);
  articleContentRevision.set({});
  imageGalleryActive.set(false);
  hasMore.set(true);
  currentPage.set(1);
  loading.set(false);
  loadingMore.set(false);
}

function queryOptions(sourceId, type) {
  const settings = settingsState.get();
  return {
    sourceId: sourceId ? Number(sourceId) : null,
    type: sourceId ? (type === "category" ? "category" : "feed") : "all",
    filter: filter.get(), pageSize: pageSize.get(),
    direction: settings.sortDirection, field: settings.sortField,
    showHiddenFeeds: settings.showHiddenFeeds,
  };
}
const signature = (options) => JSON.stringify(options);
const isCurrent = (session, sourceId, type) => querySession === session &&
  session.generation === queryGeneration && session.signature === signature(queryOptions(sourceId, type));

// A query owns a stable ID sequence. State changes never recalculate an offset
// against a newly shortened unread list, and old routes cannot publish pages.
export async function loadArticles(sourceId = null, type = "feed", page = 1, append = false) {
  const options = queryOptions(sourceId, type);
  if (!append) {
    if (querySession) resetArticleQuery();
    const session = { generation: queryGeneration, signature: signature(options), queryId: null, nextPage: 1, pending: null };
    querySession = session;
    filteredArticles.set([]);
    error.set(null);
    session.opening = (async () => {
      const feeds = await getFeeds();
      if (!isCurrent(session, sourceId, type)) return null;
      const feedIds = feeds.filter((feed) =>
        (options.showHiddenFeeds || !feed.hide_globally) &&
        (options.type === "all" || (options.type === "category" ? feed.categoryId === options.sourceId : feed.id === options.sourceId)))
        .map((feed) => feed.id);
      const query = await openArticleQuery({ ...options, feedIds });
      if (!isCurrent(session, sourceId, type)) {
        await closeArticleQuery(query.queryId);
        return null;
      }
      session.queryId = query.queryId;
      return query;
    })();
  }
  const session = querySession;
  if (!session || !isCurrent(session, sourceId, type) || page !== session.nextPage) return null;
  if (session.pending) return session.pending;
  const operation = (async () => {
    try {
      if (!(await session.opening) || !isCurrent(session, sourceId, type)) return null;
      const result = await readArticleQueryPage(session.queryId, page);
      if (!isCurrent(session, sourceId, type)) return null;
      const articles = result.items.map((article) => {
        const state = stateOverrides.get(article.id);
        return state && state.revision > result.revision ? { ...article, status: state.status, starred: state.starred } : article;
      });
      if (append) {
        const existing = filteredArticles.get();
        const seen = new Set(existing.map((article) => article.id));
        filteredArticles.set([...existing, ...articles.filter((article) => !seen.has(article.id))]);
      } else filteredArticles.set(articles);
      hasMore.set(result.hasMore);
      currentPage.set(page);
      session.nextPage = page + 1;
      return { articles, total: result.total, isMore: result.hasMore };
    } catch (failure) {
      if (!isCurrent(session, sourceId, type) || failure.code === "ACCOUNT_CHANGED") return null;
      error.set(failure.message || "加载文章失败，请重试。");
      throw failure;
    } finally {
      if (querySession === session) session.pending = null;
    }
  })();
  session.pending = operation;
  return operation;
}

function publishCounts(counts) {
  unreadCounts.set({ ...unreadCounts.get(), ...counts.unread });
  starredCounts.set({ ...starredCounts.get(), ...counts.starred });
}

function publishUpdates(result, check) {
  check(false);
  const updates = new Map(result.articles.map((article) => [article.id, article]));
  for (const article of result.articles) stateOverrides.set(article.id, {
    status: article.status, starred: article.starred, revision: result.revision,
  });
  filteredArticles.set(filteredArticles.get().map((article) => {
    const update = updates.get(article.id);
    return update ? { ...article, status: update.status, starred: update.starred } : article;
  }));
  const active = activeArticle.get();
  const update = active && updates.get(active.id);
  if (update) activeArticle.set({ ...active, status: update.status, starred: update.starred });
  publishCounts(result.counts);
}

// Acknowledgement and durable local commit both precede the visible state update.
async function saveAcknowledged(patches, check) {
  check(false);
  let result;
  try { result = await patchArticleState(patches); }
  catch (failure) {
    check(false);
    // The server result is known. Recovery only replays the local field patch;
    // retrying a bookmark toggle on the server could undo the user's action.
    registerAccountRecovery(async (recoveryCheck) => {
      recoveryCheck(false);
      const recovered = await patchArticleState(patches);
      // A lost commit reply can make replay a no-op after the worker confirms
      // its original root. Read the acknowledged rows even if articles is [].
      const articles = await getCachedArticleMetadata({ ids: patches.map((patch) => patch.id) });
      publishUpdates({ ...recovered, articles }, recoveryCheck);
    });
    const recoveryFailure = new Error("服务器已保存，但本地缓存保存失败；下次操作会先重试本地保存。", { cause: failure });
    recoveryFailure.code = "LOCAL_STATE_PENDING";
    throw recoveryFailure;
  }
  publishUpdates(result, check);
}

export function updateArticleStatus(article, desiredStatus) {
  autoReadQueue.cancelUnsent(article.id);
  return runAccountOperation(async (check) => {
    const current = await getArticleMetadata(article.id);
    check();
    if (!current) throw new Error("该文章已不在本地缓存，请刷新后重试。");
    const status = desiredStatus ?? (current.status === "read" ? "unread" : "read");
    if (current.status === status) return;
    await minifluxAPI.updateEntriesStatus([current.id], status, check);
    await saveAcknowledged([{ id: current.id, status }], check);
  }, { priority: "manual" });
}

export function updateArticleStarred(article) {
  return runAccountOperation(async (check) => {
    const current = await getArticleMetadata(article.id);
    check();
    if (!current) throw new Error("该文章已不在本地缓存，请刷新后重试。");
    await minifluxAPI.updateEntryStarred(current, check);
    await saveAcknowledged([{ id: current.id, starred: current.starred === 1 ? 0 : 1 }], check);
  }, { priority: "manual" });
}

export function markAllAsRead(type = "all", id = null) {
  // This server-wide scope can include articles absent from the current cache.
  // Wait outside the writer until the whole sync has published those articles.
  return runAccountScopeOperation(async (check) => {
    const feeds = await getFeeds();
    const feedIds = feeds.filter((feed) => type === "feed" ? feed.id === Number(id) :
      type === "category" ? feed.categoryId === Number(id) : true).map((feed) => feed.id);
    const ids = await getArticleIds({ feedIds, statusNot: "read" });
    check();
    await minifluxAPI.markAllAsRead(type, id, check);
    await saveAcknowledged(ids.map((id) => ({ id, status: "read" })), check);
  });
}

function markRangeAsRead(articleId, direction) {
  return runAccountOperation(async (check) => {
    const visible = filteredArticles.get();
    const index = visible.findIndex((article) => article.id === Number(articleId));
    if (index < 0) return;
    // The selected article remains part of both original range operations.
    const selected = (direction === "above" ? visible.slice(0, index + 1) : visible.slice(index)).map((article) => article.id);
    const ids = await getArticleIds({ ids: selected, statusNot: "read" });
    if (!ids.length) return;
    check();
    await minifluxAPI.updateEntriesStatus(ids, "read", check);
    await saveAcknowledged(ids.map((id) => ({ id, status: "read" })), check);
  }, { priority: "manual" });
}
export const markAboveAsRead = (id) => markRangeAsRead(id, "above");
export const markBelowAsRead = (id) => markRangeAsRead(id, "below");

const contentFields = ["bodyDigest", "title", "author", "url", "feedId", "published_at", "created_at", "reading_time"];
export async function refreshArticleStateAfterSync(check) {
  const active = activeArticle.get();
  const ids = [...new Set([...filteredArticles.get().map((article) => article.id), ...(active ? [active.id] : [])])];
  const [rows, counts, catalogs] = await Promise.all([getCachedArticleMetadata({ ids }), getArticleCounts(), getFeeds()]);
  check(false);
  const byId = new Map(rows.map((row) => [row.id, row]));
  const requested = new Set(ids);
  filteredArticles.set(filteredArticles.get().filter((article) => !requested.has(article.id) || byId.has(article.id))
    .map((article) => byId.get(article.id) ?? article));
  stateOverrides.clear();
  const visibleFeeds = new Set(catalogs.filter((feed) => settingsState.get().showHiddenFeeds || !feed.hide_globally).map((feed) => String(feed.id)));
  unreadCounts.set(Object.fromEntries(Object.entries(counts.unread).filter(([id]) => visibleFeeds.has(id))));
  starredCounts.set(Object.fromEntries(Object.entries(counts.starred).filter(([id]) => visibleFeeds.has(id))));
  const current = activeArticle.get();
  if (!current || current.id !== active?.id) return;
  const updated = byId.get(current.id);
  const updatedFeed = catalogs.find((feed) => feed.id === current.feedId);
  if (!updated || contentFields.some((field) => current[field] !== updated[field]) ||
      JSON.stringify(current.feed) !== JSON.stringify(updatedFeed)) {
    const id = String(current.id);
    articleContentRevision.setKey(id, (articleContentRevision.get()[id] ?? 0) + 1);
  } else if (current.status !== updated.status || current.starred !== updated.starred) {
    activeArticle.set({ ...current, status: updated.status, starred: updated.starred });
  }
}
