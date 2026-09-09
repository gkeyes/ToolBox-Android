import { createWorkerArticleCache } from "../toolbox/cache-client.js";

let cache;
let lastSyncTime = null;
let readEpoch = 0;

function getCache() {
  if (!cache) {
    const storage = globalThis.ToolBox?.storage ?? globalThis.window?.ToolBox?.storage;
    if (!storage) throw new Error("请在 ToolBox 中打开 NextFlux。");
    cache = createWorkerArticleCache(storage);
  }
  return cache;
}

function accountChanged() {
  const error = new Error("登录状态已改变，此次操作已停止。");
  error.code = "ACCOUNT_CHANGED";
  return error;
}

// Logout invalidates even responses already in transit from the worker.
export function invalidateArticleReads() { readEpoch += 1; }
const read = async (operation) => {
  const epoch = readEpoch;
  const result = await operation(getCache());
  if (epoch !== readEpoch) throw accountChanged();
  return result;
};

async function refreshMeta() {
  const meta = await getCache().meta();
  lastSyncTime = meta.lastSyncTime ?? null;
  return meta;
}

export async function initializeArticleCache(account) {
  await getCache().initialize(account);
  return refreshMeta();
}

export async function clearArticleCache() {
  invalidateArticleReads();
  lastSyncTime = null;
  await getCache().clear();
}

export const getLastSyncTime = () => lastSyncTime ? new Date(lastSyncTime) : null;
export const getArticleById = (id) => read((client) => client.readArticle(Number(id)));
export const getArticleMetadata = (id) => read((client) => client.readMetadata(Number(id)));
export const getCachedArticleMetadata = (criteria = {}) => read((client) => client.selectMetadata(criteria));
export const getArticleIds = (criteria = {}) => read((client) => client.selectIds(criteria));
export const getArticleCounts = (feedIds) => read((client) => client.counts(feedIds));
export const patchArticleState = (patches) => getCache().patchState(patches);

export const openArticleQuery = (options) => read((client) => client.openQuery(options));
export const readArticleQueryPage = (queryId, page = 1) => read((client) => client.readQueryPage(queryId, page));
export const closeArticleQuery = (queryId) => getCache().closeQuery(queryId);

export const prepareArticleSync = (options) => getCache().prepareSync(options);
export const stageArticleSync = (token, articles) => getCache().applySyncBatch(token, articles);
export const abortArticleSync = (token) => getCache().abortSync(token);
export async function commitArticleSync(token, catalogs) {
  const result = await getCache().commitSync(token, catalogs);
  await refreshMeta();
  return result;
}

export const getFeeds = () => read((client) => client.getCatalog("feeds"));
export const getCategories = () => read((client) => client.getCatalog("categories"));
export const getFeedIcon = (id) => read((client) => client.getCatalogItem("feedIcons", Number(id)));

const upsert = (rows, values, key = "id") => {
  const byId = new Map(rows.map((row) => [row[key], row]));
  for (const value of values) byId.set(value[key], value);
  return [...byId.values()];
};

export const addFeeds = async (feeds) => getCache().updateCatalog({ feeds: upsert(await getFeeds(), feeds) });
export const deleteAllFeeds = () => getCache().updateCatalog({ feeds: [] });
export const deleteFeed = async (id) => getCache().updateCatalog({ feeds: (await getFeeds()).filter((feed) => feed.id !== Number(id)) });
export const deleteFeedWithArticles = (id) => getCache().updateCatalog({ removeFeedIds: [Number(id)] });
export const deleteFeedIcon = (id) => getCache().updateCatalog({ removeIconIds: [Number(id)] });
export const addCategory = async (category) => getCache().updateCatalog({ categories: upsert(await getCategories(), [category]) });
export const deleteAllCategory = () => getCache().updateCatalog({ categories: [] });
export const deleteCategory = async (id) => getCache().updateCatalog({ categories: (await getCategories()).filter((category) => category.id !== Number(id)) });
export const updateCategory = async (id, title) => getCache().updateCatalog({
  categories: (await getCategories()).map((category) => category.id === Number(id) ? { ...category, title } : category),
});
export const setFeedIcon = async (icon) => getCache().updateCatalog({
  upsertFeedIcons: [{ ...icon, updated_at: new Date().toISOString() }],
});

// Full records are accepted for import/fixture seeding. Reading and marking
// articles use metadata/patch APIs and never reconstruct the archive.
export async function addArticles(articles) {
  const token = await prepareArticleSync({ full: false, syncedAt: getLastSyncTime()?.toISOString() ?? null });
  try {
    await stageArticleSync(token, articles);
    await commitArticleSync(token, { feeds: await getFeeds(), categories: await getCategories() });
  } catch (error) {
    await abortArticleSync(token).catch(() => {});
    throw error;
  }
}

export async function setLastSyncTime(time) {
  const token = await prepareArticleSync({ full: false, syncedAt: time.toISOString() });
  try {
    await commitArticleSync(token, { feeds: await getFeeds(), categories: await getCategories() });
  } catch (error) {
    await abortArticleSync(token).catch(() => {});
    throw error;
  }
}

export const getUnreadCount = async (id) => (await getArticleCounts([Number(id)])).unread[id] ?? 0;
export const getStarredCount = async (id) => (await getArticleCounts([Number(id)])).starred[id] ?? 0;
export const getArticlesCount = async (ids, filter = "all") => (await getArticleIds({ feedIds: ids, filter })).length;

export async function getArticlesByPage(ids, filter = "all", page = 1, pageSize = 30, direction = "desc", field = "published_at") {
  const query = await openArticleQuery({ feedIds: ids, filter, pageSize, direction, field });
  try { return (await readArticleQueryPage(query.queryId, page)).items; }
  finally { await closeArticleQuery(query.queryId); }
}

export async function searchArticles(keyword, showHiddenFeeds = false, field = "published_at") {
  const feedIds = (await getFeeds()).filter((feed) => showHiddenFeeds || !feed.hide_globally).map((feed) => feed.id);
  const query = await openArticleQuery({ feedIds, keyword, filter: "all", field, direction: "desc", pageSize: 256 });
  try {
    const articles = [];
    for (let page = 1; ; page += 1) {
      const result = await readArticleQueryPage(query.queryId, page);
      articles.push(...result.items);
      if (!result.hasMore) return articles;
    }
  } finally { await closeArticleQuery(query.queryId); }
}
