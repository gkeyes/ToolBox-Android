import axios from "axios";
import { authState, logout } from "../stores/authStore.js";
import { toast } from "sonner";
import { SERVER_URL, basicAuth, toolboxAxiosAdapter } from "../toolbox/network.js";
import { waitForRateLimit } from "../toolbox/rate-limit.js";
import { adaptivePageRequest, nextEntryCursor, SYNC_PAGE_SIZE, SYNC_MIN_PAGE_SIZE } from "../toolbox/sync-pagination.mjs";
import { createNetworkPriorityGate } from "../toolbox/foreground-gate.mjs";

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
let authGeneration = 0;
const networkPriority = createNetworkPriorityGate();

// 监听认证状态变化
authState.listen((newAuth) => {
  authGeneration += 1;
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

function cancelled() {
  return Object.assign(new Error("登录状态或操作意图已改变，此次操作已停止。"), { code: "CANCELLED" });
}

function checkAuthGeneration(generation) {
  if (generation !== authGeneration) {
    throw Object.assign(new Error("登录状态已改变，此次操作已停止。"), { code: "ACCOUNT_CHANGED" });
  }
}

// Every caller, including ordinary fetches without a supplied check, stays
// bound to the credentials with which it started during an admission wait.
function operationCheck(check) {
  const generation = authGeneration;
  const current = async () => {
    checkAuthGeneration(generation);
    if (await check?.() === false) throw cancelled();
    checkAuthGeneration(generation);
  };
  current.networkPriority = check?.networkPriority || "foreground";
  return current;
}

async function withAdmissionRetry(operation, check) {
  const generation = authGeneration;
  const current = operationCheck(check);
  const isBackground = current.networkPriority === "background";
  const releaseForeground = isBackground ? null : networkPriority.beginForeground();
  try {
    while (true) {
      await current();
      if (isBackground) await networkPriority.waitForForeground(current);
      // Optional mutation hooks distinguish a sent toggle from one the host
      // rejected before execution. Waiting checks never mark a batch as sent.
      if (await check?.beforeRequest?.() === false) throw cancelled();
      checkAuthGeneration(generation);
      try {
        const result = await operation();
        checkAuthGeneration(generation);
        return result;
      } catch (error) {
        if (error?.code !== "RATE_LIMITED" || error?.response) throw error;
        await check?.onRateLimited?.(error);
        if (!await waitForRateLimit(error, current)) throw cancelled();
      }
    }
  } finally {
    releaseForeground?.();
  }
}

// 获取所有订阅源
export const getFeeds = async (check) => {
  try {
    const response = await withAdmissionRetry(() => apiClient.get("/v1/feeds"), check);
    return response.data;
  } catch (error) {
    console.error("获取订阅源失败:", error);
    throw error;
  }
};

// 获取单个订阅源（用于并发安全的规则编辑）
export const getFeed = async (feedId, check) => {
  const id = Number(feedId);
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error("订阅编号无效。");
  const response = await withAdmissionRetry(() => apiClient.get(`/v1/feeds/${id}`), check);
  return response.data;
};

// 获取指定订阅源的文章
export const getFeedEntries = async (feedId, params = {}, check) => {
  try {
    const response = await withAdmissionRetry(() => apiClient.get("/v1/feeds/" + feedId + "/entries", {
      params: { direction: "desc", limit: 50, ...params },
    }), check);
    return response.data.entries;
  } catch (error) {
    console.error("获取文章失败:", error);
    throw error;
  }
};

export const updateEntriesStatus = async (entryIds, status, check) => {
  await withAdmissionRetry(() => {
    // A retry check may remove IDs superseded by a later manual intent.
    if (!entryIds.length) throw cancelled();
    return apiClient.put("/v1/entries", { entry_ids: [...entryIds], status });
  }, check);
};

// 更新文章阅读状态
export const updateEntryStatus = async (entry, check) => {
  try {
    const status = entry.status === "read" ? "unread" : "read";
    await updateEntriesStatus([entry.id], status, check);
  } catch (error) {
    console.error(
      `标记文章${entry.status === "read" ? "已读" : "未读"}失败:`,
      error,
    );
    throw error;
  }
};

// 更新文章星标状态
export const updateEntryStarred = async (entry, check) => {
  try {
    await withAdmissionRetry(() => apiClient.put(`/v1/entries/${entry.id}/bookmark`), check);
  } catch (error) {
    console.error("更新文章星标状态失败:", error);
    throw error;
  }
};

// Large Miniflux libraries are synchronized with bounded cursor pages instead of
// deep OFFSET scans. Transient gateway/transport failures shrink 200→100→50→25
// before retrying; the cursor advances only after a complete page is accepted.
async function fetchEntryPage(endpoint, filters, cursor, requestedSize, check) {
  const current = operationCheck(check);
  const direction = String(filters.direction || "desc").toLowerCase() === "asc" ? "asc" : "desc";
  const cursorParam = direction === "asc" ? "after_entry_id" : "before_entry_id";
  const baseFilters = { ...filters };
  delete baseFilters.after_entry_id;
  delete baseFilters.before_entry_id;
  delete baseFilters.offset;
  delete baseFilters.limit;

  return adaptivePageRequest({
    pageSize: Math.max(SYNC_MIN_PAGE_SIZE, Math.min(Number(requestedSize) || SYNC_PAGE_SIZE, SYNC_PAGE_SIZE)),
    minPageSize: SYNC_MIN_PAGE_SIZE,
    check: current,
    request: async (pageSize) => {
      const params = { ...baseFilters, limit: pageSize };
      if (cursor > 0) params[cursorParam] = cursor;
      const { data } = await withAdmissionRetry(() => apiClient.get(endpoint, { params }), current);
      await current();
      if (!Array.isArray(data.entries)) throw new Error("服务器返回的文章列表无效。");
      return data;
    },
  });
}

// Stable ID cursor pagination keeps database work bounded even when the archive
// contains tens of thousands of entries. New rows arriving during a descending
// walk are picked up by the existing changed_at overlap on the next sync.
export async function getEntriesInBatches(endpoint, params = {}, check, onPage) {
  const current = operationCheck(check);
  const filters = { order: "id", direction: "desc", ...params };
  delete filters.limit;
  delete filters.offset;
  const direction = String(filters.direction).toLowerCase() === "asc" ? "asc" : "desc";
  let cursor = Number(direction === "asc" ? filters.after_entry_id : filters.before_entry_id) || 0;
  delete filters.after_entry_id;
  delete filters.before_entry_id;

  const entries = [];
  let pageSize = SYNC_PAGE_SIZE;
  while (true) {
    const result = await fetchEntryPage(endpoint, filters, cursor, pageSize, current);
    pageSize = result.pageSize;
    const batch = result.data.entries;
    if (!batch.length) return onPage ? undefined : entries;

    const ids = new Set();
    for (const entry of batch) {
      if (ids.has(entry.id)) throw new Error("服务器同一页返回了重复文章，已停止同步。");
      ids.add(entry.id);
    }
    const nextCursor = nextEntryCursor(batch, cursor, direction);
    if (onPage) await onPage(batch);
    else entries.push(...batch);
    await current();
    cursor = nextCursor;
    if (batch.length < pageSize) return onPage ? undefined : entries;
  }
}

// 获取变更文章
export const getChangedEntries = async (lastSyncTime, check, onPage) => {
  const timestamp = Math.floor(new Date(lastSyncTime).getTime() / 1000);
  return getEntriesInBatches("/v1/entries", { changed_after: timestamp }, check, onPage);
};

// 获取新文章
export const getNewEntries = async (lastSyncTime, check, onPage) => {
  const timestamp = Math.floor(new Date(lastSyncTime).getTime() / 1000);
  return getEntriesInBatches("/v1/entries", { after: timestamp }, check, onPage);
};

// 优先同步自上次窗口以来仍为未读的文章；changed_after 也能覆盖
// “旧文章被重新标为未读”的情况，而不必每次重拉全部未读历史。
export const getUnreadChangedEntries = async (lastSyncTime, check, onPage) => {
  const timestamp = Math.floor(new Date(lastSyncTime).getTime() / 1000);
  return getEntriesInBatches("/v1/entries", {
    status: "unread",
    changed_after: timestamp,
  }, check, onPage);
};

export const getReadChangedEntries = async (lastSyncTime, check, onPage) => {
  const timestamp = Math.floor(new Date(lastSyncTime).getTime() / 1000);
  return getEntriesInBatches("/v1/entries", {
    status: "read",
    changed_after: timestamp,
  }, check, onPage);
};

export const getAllUnreadEntries = (check, onPage) => getEntriesInBatches("/v1/entries", {
  status: "unread",
}, check, onPage);

// 标记全部已读
export const markAllAsRead = async (type, id = null, check) => {
  const current = operationCheck(check);
  try {
    let endpoint = "/v1/entries";

    // 如果是用户级别的标记已读，先获取用户信息
    if (type === "all") {
      const response = await withAdmissionRetry(() => apiClient.get("/v1/me"), current);
      const userId = response.data.id;
      endpoint = `/v1/users/${userId}/mark-all-as-read`;
    } else if (type === "feed" && id) {
      endpoint = `/v1/feeds/${id}/mark-all-as-read`;
    } else if (type === "category" && id) {
      endpoint = `/v1/categories/${id}/mark-all-as-read`;
    }

    await current();
    await withAdmissionRetry(() => apiClient.put(endpoint), check);
  } catch (error) {
    console.error("标记全部已读失败:", error);
    throw error;
  }
};

// Unread starred entries are included in unread sync; retrieve read bookmarks here.
export const getAllStarredEntries = (check, onPage) => getEntriesInBatches("/v1/entries", {
  starred: true,
  status: "read",
}, check, onPage);

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
export const getCategories = async (check) => {
  try {
    const response = await withAdmissionRetry(() => apiClient.get("/v1/categories"), check);
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

// 兼容旧调用：offset=0 使用首个游标页；新同步链路使用 getAllUnreadEntries。
export const getUnreadEntriesByPage = async (offset = 0, limit = SYNC_PAGE_SIZE, check) => {
  if (Number(offset) !== 0) throw new Error("新版同步已停用深 OFFSET 分页，请重新开始同步。");
  const { data } = await fetchEntryPage("/v1/entries", { status: "unread", direction: "desc" }, 0,
    Math.max(SYNC_MIN_PAGE_SIZE, Math.min(Number(limit) || SYNC_PAGE_SIZE, SYNC_PAGE_SIZE)), check);
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
  getFeed,
  getFeedEntries,
  updateEntryStatus,
  updateEntriesStatus,
  updateEntryStarred,
  getChangedEntries,
  getNewEntries,
  getUnreadChangedEntries,
  getReadChangedEntries,
  getAllUnreadEntries,
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
