import { createArticleCache } from "./cache.js";

const CACHE_METHODS = new Set([
  "initialize", "meta", "getCatalog", "getCatalogItem", "updateCatalog", "readArticle",
  "readMetadata", "selectIds", "selectMetadata", "counts", "openQuery",
  "readQueryPage", "closeQuery", "patchState", "prepareSync", "applySyncBatch",
  "prepareSyncCommit", "commitSync", "abortSync", "clear",
]);
let storageSequence = 0;
let terminalError;
const pendingStorage = new Map();

function failure(code, message, cause) {
  const error = new Error(message);
  error.code = code;
  if (cause !== undefined) error.cause = cause;
  return error;
}

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
  const error = new Error(typeof value?.message === "string" ? value.message : "阅读缓存存储失败，请重试。");
  if (typeof value?.name === "string") error.name = value.name;
  if (typeof value?.code === "string") error.code = value.code;
  if (Number.isInteger(value?.retryAfterMs) && value.retryAfterMs >= 0) error.retryAfterMs = value.retryAfterMs;
  if (value?.cause !== undefined && depth < 4) {
    error.cause = value.cause !== null && typeof value.cause === "object"
      ? restoreError(value.cause, depth + 1) : value.cause;
  }
  return error;
}

function stop(error) {
  if (terminalError) return;
  terminalError = error;
  for (const operation of pendingStorage.values()) operation.reject(error);
  pendingStorage.clear();
  try {
    self.postMessage({ type: "cache:fatal", error: serializeError(error) });
  } finally {
    self.close();
  }
}

function requestStorage(method, args = []) {
  if (terminalError) return Promise.reject(terminalError);
  return new Promise((resolve, reject) => {
    const id = `storage:${++storageSequence}`;
    pendingStorage.set(id, { resolve, reject });
    try {
      self.postMessage({ type: "storage:request", id, method, args });
    } catch (cause) {
      pendingStorage.delete(id);
      reject(failure("CACHE_WORKER_MESSAGE_ERROR", "无法发送缓存存储请求，请重试。", cause));
    }
  });
}

// The window owns the native bridge; all article state and cache work live here.
const cache = createArticleCache({
  get: (key) => requestStorage("get", [key]),
  getMany: (keys) => requestStorage("getMany", [keys]),
  apply: (change) => requestStorage("apply", [change]),
  keys: () => requestStorage("keys"),
});

async function handleCacheRequest(message) {
  if (terminalError) return;
  try {
    if (!CACHE_METHODS.has(message.method) || !Array.isArray(message.args) ||
        typeof cache[message.method] !== "function") {
      throw failure("INVALID_REQUEST", "阅读缓存操作无效，请重新打开工具后重试。");
    }
    // Let the engine own ordering and account epochs. An outer queue would
    // delay clear() and leave reads from the previous account valid too long.
    const value = await cache[message.method](...message.args);
    if (!terminalError) self.postMessage({ type: "cache:result", id: message.id, ok: true, value });
  } catch (error) {
    if (terminalError) return;
    try {
      self.postMessage({ type: "cache:result", id: message.id, ok: false, error: serializeError(error) });
    } catch (cause) {
      stop(failure("CACHE_WORKER_MESSAGE_ERROR", "无法返回阅读缓存结果，请重新打开工具后重试。", cause));
    }
  }
}

self.addEventListener("message", ({ data }) => {
  if (terminalError || !data || typeof data !== "object" || typeof data.id !== "string") return;
  if (data.type === "cache:request") {
    void handleCacheRequest(data);
    return;
  }
  if (data.type !== "storage:result") return;
  const operation = pendingStorage.get(data.id);
  if (!operation) return;
  pendingStorage.delete(data.id);
  if (data.ok === true) operation.resolve(data.value);
  else if (data.ok === false) operation.reject(restoreError(data.error));
  else operation.reject(failure("INVALID_REQUEST", "缓存存储结果无效，请重新打开工具后重试。"));
});

self.addEventListener("messageerror", () => {
  stop(failure("CACHE_WORKER_MESSAGE_ERROR", "无法读取缓存处理请求，请重新打开工具后重试。"));
});
