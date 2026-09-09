// All remote traffic stays behind the host's permission and domain checks.
export const SERVER_URL = "https://miniflux.xiaochen.win";
export const MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
const DEFAULT_RESPONSE_BYTES = 2 * 1024 * 1024;

export function assertServerUrl(value) {
  let url;
  try {
    url = new URL(value, `${SERVER_URL}/`);
  } catch {
    throw new Error("服务器地址无效。");
  }
  if (url.origin !== SERVER_URL || url.username || url.password) {
    throw new Error("此小工具仅连接 miniflux.xiaochen.win。");
  }
  return url;
}

export function basicAuth(username, password) {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  return "Basic " + btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(""));
}

function transportError(code, retryAfterMs) {
  const messages = {
    PERMISSION_DENIED: "请在小工具权限中开启网络访问。",
    NOT_DECLARED: "此版本未声明所需的网络权限。",
    NETWORK_BLOCKED: "服务器连接失败，请检查 HTTPS 地址。",
    NETWORK_TIMEOUT: "服务器响应超时，请稍后重试。",
    NETWORK_UNAVAILABLE: "无法连接服务器，请检查网络后重试。",
    QUOTA_EXCEEDED: "响应内容过大，请减少同步数量后重试。",
    RATE_LIMITED: "请求过于频繁，请稍后重试。",
    CANCELLED: "请求已取消。",
  };
  const error = new Error(messages[code] || "网络请求失败，请稍后重试。");
  error.code = Object.hasOwn(messages, code) ? code : "NETWORK_UNAVAILABLE";
  if (error.code === "RATE_LIMITED" && Number.isInteger(retryAfterMs) && retryAfterMs >= 0) {
    error.retryAfterMs = retryAfterMs;
  }
  return error;
}

export async function request(value, options = {}) {
  const url = assertServerUrl(value);
  const network = globalThis.window?.ToolBox?.network;
  if (!network?.request) throw new Error("请在 ToolBox 中打开此小工具。");
  if (options.signal?.aborted) throw transportError("CANCELLED");
  const payload = {
    url: url.href,
    method: (options.method || "GET").toUpperCase(),
    headers: options.headers || {},
    timeoutMs: options.timeoutMs || 60000,
    maxResponseBytes: Math.min(options.maxResponseBytes || DEFAULT_RESPONSE_BYTES, MAX_RESPONSE_BYTES),
  };
  if (options.body !== undefined && options.body !== null) payload.body = options.body;
  let response;
  try {
    response = await network.request(payload);
  } catch (error) {
    // Never propagate native errors or Axios configs containing credentials/bodies.
    throw transportError(error?.code, error?.retryAfterMs);
  }
  if (options.signal?.aborted) throw transportError("CANCELLED");
  return response;
}

export function responseText(response) {
  if (response.bodyEncoding !== "base64") return response.body || "";
  return new TextDecoder().decode(Uint8Array.from(atob(response.body), (char) => char.charCodeAt(0)));
}

export function httpError(status) {
  const message = status === 401
    ? "登录信息无效或已失效，请重新登录。"
    : status === 403
      ? "服务器拒绝访问，请检查账号权限。"
      : `服务器请求失败（HTTP ${status}），请稍后重试。`;
  const error = new Error(message);
  error.code = "HTTP_ERROR";
  // Only status and a locally generated message survive into UI/logged errors.
  error.response = { status, data: { error_message: message } };
  return error;
}

export async function toolboxAxiosAdapter(config) {
  const url = assertServerUrl(config.url || config.baseURL || SERVER_URL);
  const params = config.params instanceof URLSearchParams
    ? config.params
    : new URLSearchParams(Object.entries(config.params || {}).filter(([, value]) => value !== undefined && value !== null));
  params.forEach((value, key) => url.searchParams.append(key, value));
  const sourceHeaders = config.headers?.toJSON ? config.headers.toJSON() : config.headers || {};
  const headers = Object.fromEntries(Object.entries(sourceHeaders)
    .filter(([, value]) => value !== false && value !== undefined && value !== null)
    .map(([key, value]) => [key, String(value)]));
  const response = await request(url.href, {
    method: config.method,
    headers,
    body: config.data,
    signal: config.signal,
    timeoutMs: config.timeout || 60000,
    maxResponseBytes: config.toolboxMaxResponseBytes,
  });
  const validateStatus = config.validateStatus || ((status) => status >= 200 && status < 300);
  if (!validateStatus(response.status)) throw httpError(response.status);
  return {
    data: responseText(response),
    status: response.status,
    statusText: String(response.status),
    headers: response.headers,
    config,
  };
}
