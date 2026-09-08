import { AI_ALLOWED_ORIGINS } from "./config.js";

const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
const MAX_EVENT_CHARS = 256 * 1024;

export function assertAiUrl(baseUrl) {
  let url;
  try { url = new URL(baseUrl); } catch { throw new Error("请输入有效的 AI HTTPS 服务地址。"); }
  const host = url.hostname.replace(/\.$/, "");
  const isIp = host.includes(":") || /^\d+\.\d+\.\d+\.\d+$/.test(host);
  const labels = host.split(".");
  const isDomain = labels.length >= 2 && host.length <= 253 && labels.every((label) =>
    /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label));
  if (url.protocol !== "https:" || url.username || url.password || isIp || !isDomain ||
      url.search || url.hash) {
    throw new Error("请使用不含账号、查询参数或片段的公网 HTTPS 域名地址，不支持 IP 地址。");
  }
  url.hostname = host;
  url.pathname = `${url.pathname.replace(/\/+$/, "")}/chat/completions`;
  return url.href;
}

// Only call this from the settings Save/Test click. The host owns consent UI.
export async function authorizeAiDomain(baseUrl) {
  const url = new URL(assertAiUrl(baseUrl));
  if (AI_ALLOWED_ORIGINS.includes(url.origin)) return;
  const network = bridge();
  if (typeof network.authorizeDomain !== "function") throw safeError("UNSUPPORTED");
  let approved;
  try { approved = await network.authorizeDomain(url.hostname); }
  catch (error) { throw safeError(error?.code); }
  if (approved !== true) throw safeError("DOMAIN_NOT_AUTHORIZED");
}

// Reading grants is silent; generating a summary must never prompt for consent.
async function requireAiDomain(baseUrl) {
  const url = new URL(assertAiUrl(baseUrl));
  if (AI_ALLOWED_ORIGINS.includes(url.origin)) return;
  const network = bridge();
  if (typeof network.listDomains !== "function") throw safeError("UNSUPPORTED");
  let domains;
  try { domains = await network.listDomains(); } catch (error) { throw safeError(error?.code); }
  if (!Array.isArray(domains) || !domains.includes(url.hostname)) throw safeError("DOMAIN_NOT_AUTHORIZED");
}

function safeError(code, status) {
  const messages = {
    CANCELLED: "AI 摘要已停止。",
    UNSUPPORTED: "自定义 AI 服务需要 ToolBox 0.3.12 或更新版本。",
    DOMAIN_NOT_AUTHORIZED: "尚未授权此 AI 服务，请在 AI 设置中点击保存并确认访问。",
    USER_GESTURE_REQUIRED: "请在 AI 设置中重新点击保存以确认服务访问。",
    PERMISSION_DENIED: "请在小工具权限中开启网络访问。",
    NETWORK_TIMEOUT: "AI 服务响应超时，请稍后重试。",
    QUOTA_EXCEEDED: "AI 响应超过大小限制，请缩短摘要后重试。",
    INVALID_RESPONSE: "AI 服务返回的数据格式无效。",
    INCOMPLETE_STREAM: "AI 响应意外中断，请重新生成摘要。",
    NETWORK_BLOCKED: "AI 服务连接被阻止，请检查地址和重定向。",
  };
  const error = new Error(status ?
    (status === 401 ? "AI API Key 无效，请检查设置。" : `AI 服务请求失败（HTTP ${status}），请稍后重试。`) :
    messages[code] || "无法连接 AI 服务，请检查网络和设置后重试。");
  error.code = status ? "HTTP_ERROR" : (Object.hasOwn(messages, code) ? code : "NETWORK_UNAVAILABLE");
  if (status) error.status = status;
  return error;
}

function payload({ baseUrl, apiKey, body }) {
  const url = assertAiUrl(baseUrl);
  if (typeof apiKey !== "string" || !apiKey.trim()) throw new Error("请先填写 AI API Key。");
  return { url, method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(body), timeoutMs: 60000, maxResponseBytes: MAX_RESPONSE_BYTES };
}

function bridge() {
  const network = globalThis.window?.ToolBox?.network;
  if (!network) throw new Error("请在 ToolBox 中使用 AI 摘要。");
  return network;
}

export async function testAiConnection({ baseUrl, apiKey, model }) {
  if (typeof apiKey !== "string" || !apiKey.trim()) throw new Error("请先填写 AI API Key。");
  await authorizeAiDomain(baseUrl);
  const data = payload({ baseUrl, apiKey, body: { model, messages: [{ role: "user", content: "hi" }], max_tokens: 1 } });
  let response;
  try { response = await bridge().request(data); } catch (error) { throw safeError(error?.code); }
  if (response.status < 200 || response.status >= 300) throw safeError(null, response.status);
  // The test needs only status; never expose provider response bodies in errors.
  return true;
}

function waitForRead(promise, signal) {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(safeError("CANCELLED"));
  return new Promise((resolve, reject) => {
    const abort = () => reject(safeError("CANCELLED"));
    signal.addEventListener("abort", abort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener("abort", abort));
  });
}

// Decode complete SSE events, preserving multibyte characters across native chunks.
export async function streamChatCompletion({ baseUrl, apiKey, body, signal, onDelta }) {
  if (signal?.aborted) throw safeError("CANCELLED");
  await requireAiDomain(baseUrl);
  const data = payload({ baseUrl, apiKey, body: { ...body, stream: true } });
  const network = bridge();
  if (signal?.aborted) throw safeError("CANCELLED");
  let streamId;
  let cancelled = false;
  const cancel = () => {
    if (streamId && !cancelled) {
      cancelled = true;
      return network.cancelStream(streamId).catch(() => {});
    }
  };
  signal?.addEventListener("abort", cancel, { once: true });
  try {
    let response;
    try { response = await network.openStream(data, { signal }); }
    catch (error) { throw safeError(signal?.aborted ? "CANCELLED" : error?.code); }
    streamId = response.streamId;
    if (signal?.aborted) throw safeError("CANCELLED");
    if (response.status < 200 || response.status >= 300) throw safeError(null, response.status);
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let pending = "";
    let eventLines = [];
    let eventChars = 0;
    let complete = false;
    let finishedChoice = false;
    let receivedBytes = 0;
    const dispatch = () => {
      if (!eventLines.length) return;
      const event = eventLines.join("\n");
      eventLines = [];
      eventChars = 0;
      if (event.trim() === "[DONE]") { complete = true; return; }
      let json;
      try { json = JSON.parse(event); } catch { throw safeError("INVALID_RESPONSE"); }
      if (json.error) throw safeError("INVALID_RESPONSE");
      const choice = json.choices?.[0];
      if (typeof choice?.delta?.content === "string") onDelta(choice.delta.content);
      if (choice?.finish_reason) finishedChoice = true;
    };
    const processLine = (line) => {
      if (!line) { dispatch(); return; }
      if (line.startsWith("data:")) {
        const value = line.slice(5).replace(/^ /, "");
        eventChars += value.length;
        if (eventChars > MAX_EVENT_CHARS) throw safeError("QUOTA_EXCEEDED");
        eventLines.push(value);
      }
    };
    while (!complete) {
      let chunk;
      try { chunk = await waitForRead(network.readStream(streamId), signal); }
      catch (error) { throw safeError(signal?.aborted ? "CANCELLED" : error?.code); }
      if (signal?.aborted) throw safeError("CANCELLED");
      receivedBytes += chunk.data?.byteLength || 0;
      if (receivedBytes > MAX_RESPONSE_BYTES) throw safeError("QUOTA_EXCEEDED");
      try { pending += decoder.decode(chunk.data || new Uint8Array(), { stream: !chunk.done }); }
      catch { throw safeError("INVALID_RESPONSE"); }
      let index;
      while (!complete && (index = pending.indexOf("\n")) !== -1) {
        processLine(pending.slice(0, index).replace(/\r$/, ""));
        pending = pending.slice(index + 1);
      }
      if (pending.length > MAX_EVENT_CHARS) throw safeError("QUOTA_EXCEEDED");
      if (chunk.done) {
        if (!complete && pending) processLine(pending.replace(/\r$/, ""));
        if (!complete) dispatch();
        if (!complete && !finishedChoice) throw safeError("INCOMPLETE_STREAM");
        break;
      }
    }
  } finally {
    signal?.removeEventListener("abort", cancel);
    await cancel();
  }
}
