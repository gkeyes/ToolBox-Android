
export function assertAiUrl(baseUrl) {
  let url;
  try { url = new URL(baseUrl); } catch { throw new Error("请输入有效的 AI HTTPS 服务地址。"); }
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) {
    throw new Error("请使用不含账号、查询参数或片段的 HTTPS 服务地址。");
  }
  url.pathname = `${url.pathname.replace(/\/+$/, "")}/chat/completions`;
  return url.href;
}

function safeError(code, status) {
  const messages = {
    CANCELLED: "AI 请求已停止。",
    PERMISSION_DENIED: "请在小工具权限中开启网络访问。",
    NETWORK_TIMEOUT: "AI 服务响应超时，请稍后重试。",
    QUOTA_EXCEEDED: "可用资源不足，无法继续接收 AI 响应，请稍后重试。",
    INVALID_RESPONSE: "AI 服务返回的数据格式无效。",
    INCOMPLETE_STREAM: "AI 响应意外中断，请重新生成摘要。",
    NETWORK_BLOCKED: "AI 服务连接失败，请检查 HTTPS 地址。",
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
    body: JSON.stringify(body) };
}

function bridge() {
  const network = globalThis.window?.ToolBox?.network;
  if (!network) throw new Error("请在 ToolBox 中使用 AI 功能。");
  return network;
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

function textFragments(value, depth = 0) {
  if (depth > 4 || value == null) return [];
  if (typeof value === "string") return value ? [value] : [];
  if (Array.isArray(value)) return value.flatMap((item) => textFragments(item, depth + 1));
  if (typeof value !== "object") return [];
  // OpenAI-compatible providers use several shapes for reasoning blocks.
  // Read only known textual keys so IDs/signatures/metadata are never shown as prose.
  for (const key of ["text", "content", "reasoning_content", "reasoning", "summary"]) {
    if (value[key] != null) {
      const parts = textFragments(value[key], depth + 1);
      if (parts.length) return parts;
    }
  }
  return [];
}

export function extractReasoningText(choice) {
  const delta = choice?.delta;
  const message = choice?.message;
  const values = delta
    ? [delta.reasoning_content, delta.reasoning_details]
    : [message?.reasoning_content, message?.reasoning_details];
  return values.flatMap((value) => textFragments(value)).join("");
}

export function extractContentText(choice) {
  const delta = choice?.delta;
  const message = choice?.message;
  const value = delta ? delta.content : message?.content;
  return textFragments(value).join("");
}

// Decode complete SSE events, preserving multibyte characters across native chunks.
// onReasoning receives only provider-exposed reasoning fields; it never reconstructs
// or invents hidden reasoning when the provider does not send them.
export async function streamChatCompletion({ baseUrl, apiKey, body, signal, onDelta, onReasoning }) {
  if (signal?.aborted) throw safeError("CANCELLED");
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

    let complete = false;
    let finishedChoice = false;

    const dispatch = () => {
      if (!eventLines.length) return;
      const event = eventLines.join("\n");
      eventLines = [];

      if (event.trim() === "[DONE]") { complete = true; return; }
      let json;
      try { json = JSON.parse(event); } catch { throw safeError("INVALID_RESPONSE"); }
      if (json.error) throw safeError("INVALID_RESPONSE");
      const choice = json.choices?.[0];
      const reasoning = extractReasoningText(choice);
      if (reasoning && typeof onReasoning === "function") onReasoning(reasoning);
      const content = extractContentText(choice);
      if (content && typeof onDelta === "function") onDelta(content);
      if (choice?.finish_reason) finishedChoice = true;
    };
    const processLine = (line) => {
      if (!line) { dispatch(); return; }
      if (line.startsWith("data:")) {
        const value = line.slice(5).replace(/^ /, "");


        eventLines.push(value);
      }
    };
    while (!complete) {
      let chunk;
      try { chunk = await waitForRead(network.readStream(streamId), signal); }
      catch (error) { throw safeError(signal?.aborted ? "CANCELLED" : error?.code); }
      if (signal?.aborted) throw safeError("CANCELLED");


      try { pending += decoder.decode(chunk.data || new Uint8Array(), { stream: !chunk.done }); }
      catch { throw safeError("INVALID_RESPONSE"); }
      let index;
      while (!complete && (index = pending.indexOf("\n")) !== -1) {
        processLine(pending.slice(0, index).replace(/\r$/, ""));
        pending = pending.slice(index + 1);
      }

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
