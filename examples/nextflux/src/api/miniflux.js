import axios from "axios";
import { authState, logout } from "../stores/authStore.js";
import { toast } from "sonner";
import { SERVER_URL, basicAuth, toolboxAxiosAdapter } from "../toolbox/network.js";

// 创建 axios 实例
const createApiClient = () => {
  const auth = authState.get();

  const client = axios.create({
    baseURL: SERVER_URL,
    adapter: toolboxAxiosAdapter,
    headers:
      auth?.authType === "token"
        ? {
            "X-Auth-Token": auth.token,
          }
        : auth?.username && auth?.password
          ? {
              Authorization:
                basicAuth(auth.username, auth.password),
            }
          : {},
  });

  // 添加响应拦截器
  client.interceptors.response.use(
    (response) => response,
    (error) => {
      // 如果响应状态码是 401,执行登出操作
      if (error.response?.status === 401) {
        logout().catch(() => toast.error("登录已失效；请重试退出以清理本地缓存。"));
      }
      const errorMessage = error.response?.data?.error_message;
      if (errorMessage && error.response?.status !== 404) {
        toast.error(errorMessage);
      }
      return Promise.reject(error);
    },
  );

  return client;
};

// 创建 API 客户端实例
let apiClient = createApiClient();

// 监听认证状态变化
authState.listen((newAuth) => {
  apiClient.defaults.baseURL = SERVER_URL;
  if (newAuth?.authType === "token") {
    apiClient.defaults.headers["X-Auth-Token"] = newAuth.token;
    delete apiClient.defaults.headers["Authorization"];
  } else {
    apiClient.defaults.headers["Authorization"] =
      newAuth?.username && newAuth?.password
        ? basicAuth(newAuth.username, newAuth.password)
        : "";
    delete apiClient.defaults.headers["X-Auth-Token"];
  }
});

// 获取所有订阅源
export const getFeeds = async () => {
  try {
    const response = await apiClient.get("/v1/feeds");
    return response.data;
  } catch (error) {
    console.error("获取订阅源失败:", error);
    throw error;
  }
};

// 获取指定订阅源的文章
export const getFeedEntries = async (feedId, params = {}) => {
  try {
    const response = await apiClient.get("/v1/feeds/" + feedId + "/entries", {
      params: { direction: "desc", limit: 50, ...params },
    });
    return response.data.entries;
  } catch (error) {
    console.error("获取文章失败:", error);
    throw error;
  }
};

export const updateEntriesStatus = async (entryIds, status) => {
  await apiClient.put("/v1/entries", { entry_ids: entryIds, status });
};

// 更新文章阅读状态
export const updateEntryStatus = async (entry) => {
  try {
    const status = entry.status === "read" ? "unread" : "read";
    await apiClient.put("/v1/entries", {
      entry_ids: [entry.id],
      status,
    });
  } catch (error) {
    console.error(
      `标记文章${entry.status === "read" ? "已读" : "未读"}失败:`,
      error,
    );
    throw error;
  }
};

// 更新文章星标状态
export const updateEntryStarred = async (entry) => {
  try {
    await apiClient.put(`/v1/entries/${entry.id}/bookmark`);
  } catch (error) {
    console.error("更新文章星标状态失败:", error);
    throw error;
  }
};

// Fewer round trips for ordinary articles; large bodies still shrink the page
// against ToolBox's existing response limit without advancing the offset.
// Keep the upstream synchronization batch size. The adaptive fallback below
// only handles a single response exceeding ToolBox's transport budget.
const SYNC_PAGE_SIZE = 1000;
async function waitForNetworkWindow(check) {
  // ToolBox's rolling request window is 60 seconds. Check cancellation while
  // yielding so logout does not have to wait for this entire backoff.
  const until = Date.now() + 60000;
  while (Date.now() < until) {
    check();
    await new Promise((resolve) => setTimeout(resolve, Math.min(1000, until - Date.now())));
  }
  check();
}
async function fetchEntryPage(endpoint, filters, offset, requestedSize, check = () => {}) {
  let pageSize = requestedSize;
  let retriedRateLimit = false;
  while (true) {
    check();
    try {
      const { data } = await apiClient.get(endpoint, {
        params: { ...filters, offset, limit: pageSize },
        toolboxMaxResponseBytes: 4 * 1024 * 1024,
      });
      check();
      if (!Array.isArray(data.entries)) throw new Error("服务器返回的文章列表无效。");
      return { data, pageSize };
    } catch (error) {
      check();
      if (error.code === "RATE_LIMITED" && !retriedRateLimit) {
        retriedRateLimit = true;
        await waitForNetworkWindow(check);
        continue;
      }
      if (error.code !== "QUOTA_EXCEEDED" || pageSize <= 1) throw error;
      pageSize = Math.max(1, Math.floor(pageSize / 2));
    }
  }
}

// Stable ID order avoids the publication-time reordering of the visual list.
export async function getEntriesInBatches(endpoint, params = {}, check = () => {}) {
  const initialOffset = params.offset || 0;
  const filters = { order: "id", direction: "asc", ...params };
  delete filters.limit;
  delete filters.offset;
  const entries = [];
  const seen = new Set();
  let offset = initialOffset;
  let pageSize = SYNC_PAGE_SIZE;
  while (true) {
    const result = await fetchEntryPage(endpoint, filters, offset, pageSize, check);
    pageSize = result.pageSize;
    const { entries: batch, total } = result.data;
    if (!batch.length && Number.isFinite(total) && offset < total) {
      throw new Error("同步结果不完整，请重试。");
    }
    const previousCount = entries.length;
    for (const entry of batch) {
      if (!seen.has(entry.id)) { seen.add(entry.id); entries.push(entry); }
    }
    if (batch.length && entries.length === previousCount) {
      throw new Error("服务器重复返回同一页文章，同步结果不完整，请重试。");
    }
    offset += batch.length;
    if (!batch.length || (Number.isFinite(total) ? offset >= total : batch.length < pageSize)) return entries;
  }
}

// 获取变更文章
export const getChangedEntries = async (lastSyncTime, check) => {
  const timestamp = Math.floor(new Date(lastSyncTime).getTime() / 1000);
  return getEntriesInBatches("/v1/entries", { changed_after: timestamp }, check);
};

// 获取新文章
export const getNewEntries = async (lastSyncTime, check) => {
  const timestamp = Math.floor(new Date(lastSyncTime).getTime() / 1000);
  return getEntriesInBatches("/v1/entries", { after: timestamp }, check);
};

// 标记全部已读
export const markAllAsRead = async (type, id = null) => {
  try {
    let endpoint = "/v1/entries";

    // 如果是用户级别的标记已读，先获取用户信息
    if (type === "all") {
      const response = await apiClient.get("/v1/me");
      const userId = response.data.id;
      endpoint = `/v1/users/${userId}/mark-all-as-read`;
    } else if (type === "feed" && id) {
      endpoint = `/v1/feeds/${id}/mark-all-as-read`;
    } else if (type === "category" && id) {
      endpoint = `/v1/categories/${id}/mark-all-as-read`;
    }

    await apiClient.put(endpoint);
  } catch (error) {
    console.error("标记全部已读失败:", error);
    throw error;
  }
};

// Unread starred entries are included in unread sync; retrieve read bookmarks here.
export const getAllStarredEntries = (check) => getEntriesInBatches("/v1/entries", {
  starred: true,
  status: "read",
}, check);

// 获取文章原始内容
export const fetchEntryContent = async (entryId) => {
  try {
    const response = await apiClient.get(
      `/v1/entries/${entryId}/fetch-content`,
    );
    return response.data.content;
  } catch (error) {
    console.error("获取文章原始内容失败:", error);
    throw error;
  }
};

// 删除订阅源
export const deleteFeed = async (feedId) => {
  try {
    await apiClient.delete(`/v1/feeds/${feedId}`);
  } catch (error) {
    console.error("删除订阅源失败:", error);
    throw error;
  }
};

// 更新订阅源
export const updateFeed = async (feedId, data) => {
  try {
    const response = await apiClient.put(`/v1/feeds/${feedId}`, data);
    return response.data;
  } catch (error) {
    console.error("更新订阅源失败:", error);
    throw error;
  }
};

// 创建订阅源
export const createFeed = async (feedUrl, categoryId, params) => {
  try {
    const response = await apiClient.post("/v1/feeds", {
      feed_url: feedUrl,
      category_id: categoryId,
      ...params,
    });
    return response.data;
  } catch (error) {
    console.error("创建订阅源失败:", error);
    throw error;
  }
};

// 创建分类
export const createCategory = async (title) => {
  try {
    const response = await apiClient.post("/v1/categories", {
      title,
    });
    return response.data;
  } catch (error) {
    console.error("创建分类失败:", error);
    throw error;
  }
};

// 删除分类
export const deleteCategory = async (categoryId) => {
  try {
    await apiClient.delete(`/v1/categories/${categoryId}`);
  } catch (error) {
    console.error("删除分类失败:", error);
    throw error;
  }
};

// 更新分类
export const updateCategory = async (categoryId, title) => {
  try {
    const response = await apiClient.put(`/v1/categories/${categoryId}`, {
      title,
    });
    return response.data;
  } catch (error) {
    console.error("更新分类失败:", error);
    throw error;
  }
};

// 获取所有分类
export const getCategories = async () => {
  try {
    const response = await apiClient.get("/v1/categories");
    return response.data;
  } catch (error) {
    console.error("获取分类列表失败:", error);
    throw error;
  }
};

// 导入 OPML
export const importOPML = async (file) => {
  try {
    const xml = typeof file === "string" ? file : await file.text();
    const response = await apiClient.post("/v1/import", xml, {
      headers: {
        "Content-Type": "application/xml",
      },
    });
    return response.data;
  } catch (error) {
    console.error("导入OPML失败:", error);
    throw error;
  }
};

// 分页获取未读文章
export const getUnreadEntriesByPage = async (offset = 0, limit = SYNC_PAGE_SIZE, check) => {
  const { data } = await fetchEntryPage("/v1/entries", { status: "unread", direction: "desc" }, offset,
    Math.max(1, Math.min(Number(limit) || SYNC_PAGE_SIZE, SYNC_PAGE_SIZE)), check);
  return data;
};

// 刷新订阅源
export const refreshFeed = async (feedId) => {
  try {
    await apiClient.put(`/v1/feeds/${feedId}/refresh`);
  } catch (error) {
    console.error("刷新订阅源失败:", error);
    throw error;
  }
};

// 刷新所有订阅源
export const refreshAllFeeds = async () => {
  try {
    await apiClient.put("/v1/feeds/refresh");
  } catch (error) {
    console.error("刷新所有订阅源失败:", error);
    throw error;
  }
};

// 检查是否启用了第三方集成
export const checkIntegrations = async () => {
  try {
    const response = await apiClient.get("/v1/integrations/status");
    return response.data.has_integrations;
  } catch (error) {
    console.error("检查第三方集成状态失败:", error);
    throw error;
  }
};

// 保存文章到第三方服务
export const saveToThirdParty = async (entryId) => {
  try {
    await apiClient.post(`/v1/entries/${entryId}/save`);
  } catch (error) {
    console.error("保存到第三方服务失败:", error);
    throw error;
  }
};

// 发现订阅源
export const discoverFeeds = async (url) => {
  try {
    const response = await apiClient.post("/v1/discover", {
      url: url,
    });
    return response.data;
  } catch (error) {
    console.error("发现订阅源失败:", error);
    throw error;
  }
};

// 获取订阅源图标
export const getIconByFeedId = async (feedId) => {
  try {
    const response = await apiClient.get(`/v1/feeds/${feedId}/icon`);
    return response.data;
  } catch (error) {
    if (error.response?.status === 404) {
      return null;
    }
    console.error("获取订阅源图标失败:", error);
  }
};

// 为了兼容现有代码，提供一个默认导出对象
const minifluxApi = {
  getFeeds,
  getFeedEntries,
  updateEntryStatus,
  updateEntriesStatus,
  updateEntryStarred,
  getChangedEntries,
  getNewEntries,
  markAllAsRead,
  getAllStarredEntries,
  fetchEntryContent,
  deleteFeed,
  updateFeed,
  createFeed,
  createCategory,
  deleteCategory,
  updateCategory,
  getCategories,
  importOPML,
  getUnreadEntriesByPage,
  refreshFeed,
  refreshAllFeeds,
  checkIntegrations,
  saveToThirdParty,
  discoverFeeds,
  getIconByFeedId,
};

export default minifluxApi;
