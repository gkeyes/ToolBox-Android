const READ_METHODS = new Set([
  "meta", "getCatalog", "getCatalogItem", "readArticle", "readMetadata", "selectIds",
  "selectMetadata", "counts", "openQuery", "readQueryPage",
]);
const STORAGE_METHODS = new Set(["get", "getMany", "apply", "keys"]);

function failure(code, message, cause) {
  const error = new Error(message);
  error.code = code;
  if (cause !== undefined) error.cause = cause;
  return error;
}

// Error objects do not consistently retain custom fields across WebViews.
// Only copy error fields, never arbitrary storage or article payloads.
function serializeError(error, depth = 0) {
  const result = {
    name: typeof error?.name === "string" ? error.name : "Error",
    message: typeof error?.message === "string" ? error.message : String(error ?? "未知错误"),
  };
  if (typeof error?.code === "string") result.code = error.code;
  if (Number.isInteger(error?.retryAfterMs) && error.retryAfterMs >= 0) result.retryAfterMs = error.retryAfterMs;
  if (error?.cause !== undefined && depth < 4) {
    const cause = error.cause;
    result.cause = cause !== null && typeof cause === "object"
      ? serializeError(cause, depth + 1)
      : typeof cause === "string" || typeof cause === "number" || typeof cause === "boolean" || cause === null
        ? cause : String(cause);
  }
  return result;
}

function restoreError(value, depth = 0) {
  const error = new Error(typeof value?.message === "string" ? value.message : "阅读缓存操作失败，请重试。");
  if (typeof value?.name === "string") error.name = value.name;
  if (typeof value?.code === "string") error.code = value.code;
  if (Number.isInteger(value?.retryAfterMs) && value.retryAfterMs >= 0) error.retryAfterMs = value.retryAfterMs;
  if (value?.cause !== undefined && depth < 4) {
    error.cause = value.cause !== null && typeof value.cause === "object"
      ? restoreError(value.cause, depth + 1) : value.cause;
  }
  return error;
}

export function createWorkerArticleCache(storage) {
  if (typeof globalThis.Worker !== "function") {
    throw failure("UNSUPPORTED", "当前 WebView 不支持阅读缓存处理，请更新 Android System WebView 后重试。");
  }
  let worker;
  try {
    worker = new Worker(new URL("./cache-worker.js", import.meta.url), { type: "module" });
  } catch (cause) {
    throw failure("CACHE_WORKER_ERROR", "无法启动阅读缓存，请重新打开工具后重试。", cause);
  }
  let sequence = 0;
  let terminalError;
  const pending = new Map();

  function stop(error) {
    if (terminalError) return;
    terminalError = error;
    worker.removeEventListener("message", onMessage);
    worker.removeEventListener("error", onError);
    worker.removeEventListener("messageerror", onMessageError);
    worker.terminate();
    for (const request of pending.values()) request.reject(error);
    pending.clear();
  }

  function onError(event) {
    event.preventDefault();
    stop(failure("CACHE_WORKER_ERROR", "阅读缓存处理失败，请重新打开工具后重试。", event.error ?? event.message));
  }

  function onMessageError() {
    stop(failure("CACHE_WORKER_MESSAGE_ERROR", "无法读取缓存处理结果，请重新打开工具后重试。"));
  }

  async function handleStorageRequest(message) {
    if (terminalError) return;
    try {
      if (!STORAGE_METHODS.has(message.method) || !Array.isArray(message.args)) {
        throw failure("INVALID_REQUEST", "阅读缓存存储请求无效，请重新打开工具后重试。");
      }
      if (typeof storage?.[message.method] !== "function") {
        throw failure("UNSUPPORTED", "当前 ToolBox 不支持所需的缓存存储接口，请更新 ToolBox 后重试。");
      }
      // Forward bounded engine requests without keeping a window-side cache.
      const value = await storage[message.method](...message.args);
      if (terminalError) return;
      worker.postMessage({ type: "storage:result", id: message.id, ok: true, value });
    } catch (error) {
      if (terminalError) return;
      try {
        worker.postMessage({ type: "storage:result", id: message.id, ok: false, error: serializeError(error) });
      } catch (cause) {
        stop(failure("CACHE_WORKER_MESSAGE_ERROR", "无法传递缓存存储结果，请重新打开工具后重试。", cause));
      }
    }
  }

  function onMessage({ data }) {
    if (terminalError || !data || typeof data !== "object") return;
    if (data.type === "cache:fatal") {
      stop(restoreError(data.error));
      return;
    }
    if (typeof data.id !== "string") return;
    if (data.type === "storage:request") {
      void handleStorageRequest(data);
      return;
    }
    if (data.type !== "cache:result") return;
    const request = pending.get(data.id);
    if (!request) return;
    pending.delete(data.id);
    if (data.ok === true) request.resolve(data.value);
    else if (data.ok === false) request.reject(restoreError(data.error));
    else request.reject(failure("INVALID_REQUEST", "阅读缓存处理结果无效，请重新打开工具后重试。"));
  }

  worker.addEventListener("message", onMessage);
  worker.addEventListener("error", onError);
  worker.addEventListener("messageerror", onMessageError);

  function request(method, args = []) {
    if (terminalError) return Promise.reject(terminalError);
    return new Promise((resolve, reject) => {
      const id = `cache:${++sequence}`;
      pending.set(id, { method, resolve, reject });
      try {
        worker.postMessage({ type: "cache:request", id, method, args });
      } catch (cause) {
        pending.delete(id);
        reject(failure("CACHE_WORKER_MESSAGE_ERROR", "无法发送阅读缓存请求，请重试。", cause));
      }
    });
  }

  return {
    initialize: (account) => request("initialize", [account]),
    meta: () => request("meta"),
    getCatalog: (name) => request("getCatalog", [name]),
    getCatalogItem: (name, id) => request("getCatalogItem", [name, id]),
    updateCatalog: (change) => request("updateCatalog", [change]),
    readArticle: (id) => request("readArticle", [id]),
    readMetadata: (id) => request("readMetadata", [id]),
    selectIds: (query) => request("selectIds", [query]),
    selectMetadata: (criteria) => request("selectMetadata", [criteria]),
    counts: (feedIds) => request("counts", [feedIds]),
    openQuery: (query) => request("openQuery", [query]),
    readQueryPage: (queryId, page = 1) => request("readQueryPage", [queryId, page]),
    closeQuery: (queryId) => request("closeQuery", [queryId]),
    patchState: (patches) => request("patchState", [patches]),
    prepareSync: (options) => request("prepareSync", [options]),
    applySyncBatch: (token, articles) => request("applySyncBatch", [token, articles]),
    prepareSyncCommit: (token, catalog) => request("prepareSyncCommit", [token, catalog]),
    commitSync: (token, catalog) => request("commitSync", [token, catalog]),
    abortSync: (token) => request("abortSync", [token]),
    clear() {
      // Invalidate only old readers. Mutations and their storage replies must
      // finish so the engine can clear safely behind its transaction queue.
      const error = failure("ACCOUNT_CHANGED", "账号已切换，本次读取已取消。");
      for (const [id, operation] of pending) {
        if (!READ_METHODS.has(operation.method)) continue;
        pending.delete(id);
        operation.reject(error);
      }
      return request("clear");
    },
    dispose() {
      stop(failure("CACHE_WORKER_TERMINATED", "阅读缓存已关闭，请重新打开工具后重试。"));
    },
  };
}
