import { filterError, FILTER_FIELD } from "./title-filter-rules.mjs";

/** Same trusted server and native network bridge as the original reader.
 * Credentials stay in memory; never log request headers, URLs with secrets or bodies.
 */
export function createTitleFilterApi({ feedId, authState, auth, serverUrl, isCurrent, network = () => globalThis.window?.ToolBox?.network, deadlineMs = 20000 }) {
  const id = Number(feedId);
  if (!Number.isSafeInteger(id) || id <= 0) throw filterError("INVALID_FEED", "订阅编号无效。");
  const origin = new URL(serverUrl);
  if (origin.protocol !== "https:" || origin.origin !== serverUrl || origin.username || origin.password || auth?.serverUrl !== serverUrl) {
    throw filterError("INVALID_SERVER", "服务器身份不匹配，未发送请求。");
  }
  const current = () => isCurrent() && authState.get() === auth && Boolean(auth?.userId);
  const ensure = () => { if (!current()) throw filterError("ACCOUNT_CHANGED", "页面或登录状态已改变，请重新打开。"); };
  function headers() {
    if (auth.authType === "token" && typeof auth.token === "string" && auth.token) return { "X-Auth-Token": auth.token };
    if (auth.authType === "basic" && auth.username && auth.password) {
      const bytes = new TextEncoder().encode(`${auth.username}:${auth.password}`);
      return { Authorization: "Basic " + btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join("")) };
    }
    throw filterError("AUTH_REQUIRED", "请先登录 Miniflux。");
  }
  async function request(method, patch) {
    ensure();
    const bridge = network();
    if (!bridge?.request) throw filterError("BRIDGE_REQUIRED", "请在 ToolBox 内打开此小工具。");
    const payload = { url: `${serverUrl}/v1/feeds/${id}`, method, timeoutMs: 15000, headers: { Accept: "application/json", ...headers() } };
    if (patch) {
      if (Object.keys(patch).length !== 1 || typeof patch[FILTER_FIELD] !== "string") throw filterError("INVALID_PATCH", "过滤规则数据无效。");
      payload.headers["Content-Type"] = "application/json";
      payload.body = JSON.stringify(patch);
    }
    let timer;
    let response;
    try {
      response = await Promise.race([
        Promise.resolve().then(() => { ensure(); return bridge.request(payload); }),
        new Promise((_, reject) => { timer = setTimeout(() => reject({ code: "NETWORK_TIMEOUT" }), deadlineMs); }),
      ]);
    } catch (error) {
      ensure();
      const messages = {
        PERMISSION_DENIED: "请在 ToolBox 的小工具权限中开启网络访问。",
        NOT_DECLARED: "当前小工具没有网络权限。",
        RATE_LIMITED: "请求过于频繁，请稍后重试。",
        NETWORK_TIMEOUT: method === "PUT" ? "保存响应超时，结果尚未确认。请重新载入核对，不要连续提交。" : "读取超时，请重试。",
        NETWORK_UNAVAILABLE: "网络连接失败，请检查网络后重试。",
        CANCELLED: "请求已取消，请重新打开。",
      };
      const refusedBeforeSend = ["PERMISSION_DENIED", "NOT_DECLARED", "RATE_LIMITED"].includes(error?.code);
      const uncertain = method === "PUT" && !refusedBeforeSend;
      throw filterError(uncertain ? "UNCONFIRMED" : "NETWORK_ERROR", uncertain ? "保存响应未确认，请重新载入核对，不要连续提交。" : messages[error?.code] || "网络请求未完成，请稍后重试。");
    } finally { clearTimeout(timer); }
    ensure();
    const status = response?.status;
    if (!Number.isInteger(status) || status < 200 || status >= 300) {
      const message = status === 401 ? "登录已失效，请重新登录。" : status === 403 ? "服务器拒绝访问，请检查账号权限。" : status === 404 ? "这条订阅已不存在，请返回列表。" : status === 429 ? "服务器限流，请稍后重试。" : "服务器未接受请求，请稍后重试。";
      throw filterError("HTTP_ERROR", message);
    }
    try {
      const text = response.bodyEncoding === "base64"
        ? new TextDecoder().decode(Uint8Array.from(atob(response.body), char => char.charCodeAt(0)))
        : response.body;
      const data = JSON.parse(text);
      if (!data || Number(data.id) !== id) throw new Error("identity");
      return data;
    } catch {
      throw filterError(method === "PUT" ? "UNCONFIRMED" : "INVALID_RESPONSE", method === "PUT" ? "无法确认服务器保存结果，请重新载入核对。" : "服务器返回的数据无效，请重试。");
    }
  }
  return { read: () => request("GET"), write: patch => request("PUT", patch) };
}
