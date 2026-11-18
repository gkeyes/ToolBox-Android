import { isGpt62Model } from "./ai-context.mjs";

export function assertAiUrl(baseUrl) {
  let url;
  try { url = new URL(baseUrl); } catch { throw new Error("请输入有效的 AI HTTPS 服务地址。"); }
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) {
    throw new Error("请使用不含账号、查询参数或片段的 HTTPS 服务地址。");
  }
  url.pathname = `${url.pathname.replace(/\/+$/, "")}/chat/completions`;
  return url.href;
}

const SAFE_PARAMETERS = new Set([
  "model", "messages", "temperature", "top_p", "logprobs", "top_logprobs",
  "max_tokens", "max_completion_tokens", "reasoning_effort", "response_format", "stream", "stream_options",
]);

function safeError(code, status, parameter) {
  const param = SAFE_PARAMETERS.has(parameter) ? parameter : "请求";
  const messages = {
    CANCELLED: "AI 请求已停止。",
    PERMISSION_DENIED: "请在小工具权限中开启网络访问。",
    NETWORK_TIMEOUT: "AI 服务响应超时，请稍后重试。",
    QUOTA_EXCEEDED: "可用资源不足，无法继续接收 AI 响应，请稍后重试。",
    INVALID_RESPONSE: "AI 服务返回的数据格式无效。",
    INCOMPLETE_STREAM: "AI 响应意外中断，请重新生成。",
    NETWORK_BLOCKED: "AI 服务连接失败，请检查 HTTPS 地址。",
    CONTEXT_LENGTH_EXCEEDED: "本次输入超过 AI 服务的上下文容量，请调低模型上下文设置后重试。",
    UNSUPPORTED_PARAMETER: `当前模型不支持 ${param} 参数，请检查 AI 模型配置或更新应用。`,
    UNSUPPORTED_VALUE: `当前模型不接受 ${param} 的参数值，请检查 AI 模型配置或更新应用。`,
    MODEL_NOT_FOUND: "模型不存在或当前 API Key 无权使用，请检查模型名称和账号权限。",
  };
  const classified = ["CONTEXT_LENGTH_EXCEEDED", "UNSUPPORTED_PARAMETER", "UNSUPPORTED_VALUE", "MODEL_NOT_FOUND"].includes(code);
  const httpMessage = status === 401 ? "AI API Key 无效，请检查设置。"
    : status === 400 || status === 422 ? `AI 服务未接受请求（HTTP ${status}），请检查模型名称、请求参数和接口兼容性。`
    : status === 404 ? "AI 服务地址或模型不可用（HTTP 404），请检查服务地址和模型设置。"
    : status === 429 ? "AI 服务限流或可用额度不足（HTTP 429），请检查额度或稍后重试。"
    : `AI 服务请求失败（HTTP ${status}），请稍后重试。`;
  const error = new Error(status && !classified ? httpMessage :
    messages[code] || "无法连接 AI 服务，请检查网络和设置后重试。");
  error.code = status && !classified ? "HTTP_ERROR" : (Object.hasOwn(messages, code) ? code : "NETWORK_UNAVAILABLE");
  if (status) error.status = status;
  return error;
}

function payload({ baseUrl, apiKey, body }) {
  const url = assertAiUrl(baseUrl);
  if (typeof apiKey !== "string" || !apiKey.trim()) throw new Error("请先填写 AI API Key。");
  const request = { ...body };
  // GPT-6.2 Chat Completions rejects these options. Keep all AI entry points
  // compatible without changing a caller's frozen input or other models.
  if (isGpt62Model(request.model)) {
    for (const name of ["temperature", "top_p", "logprobs", "top_logprobs"]) delete request[name];
  }
  return { url, method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(request) };
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

// Keep provider errors private. Only this narrow category permits the board
// to split an input and try again; a generic 400, quota or rate-limit failure
// must never turn into more AI requests.
function isContextLimit(value) {
  const error = value?.error;
  if (!error || typeof error !== "object" || Array.isArray(error)) return false;
  if (error.code === "context_length_exceeded") return true;
  if (error.code != null && ![400, 413, 422, "invalid_request_error", "invalid_argument"].includes(error.code)) return false;
  if (error.type && error.type !== "invalid_request_error") return false;
  if (error.status && error.status !== "INVALID_ARGUMENT") return false;
  const message = typeof error.message === "string" ? error.message : "";
  const number = (text) => Number(text.replaceAll(",", ""));
  // OpenAI / compatible gateways: compare requested tokens to the stated
  // context length instead of matching the word "context" alone.
  const openai = message.match(/maximum context length (?:is|of) ([\d,]+) tokens\b[\s\S]{0,200}?(?:you requested(?: about)?|your messages resulted in) ([\d,]+) tokens\b/i);
  if (openai && number(openai[2]) > number(openai[1])) return true;
  // Gemini's INVALID_ARGUMENT response names both the input count and limit.
  const gemini = message.match(/input token count \(([\d,]+)\) exceeds the maximum number of tokens allowed \(([\d,]+)\)/i);
  return Boolean(gemini && number(gemini[1]) > number(gemini[2]));
}

function providerError(value, status) {
  const error = value?.error;
  if (!error || typeof error !== "object" || Array.isArray(error)) return null;
  // A 404 cannot be turned into a context retry by an unrelated response body.
  if (status !== 404 && isContextLimit(value)) return safeError("CONTEXT_LENGTH_EXCEEDED", status);
  if (error.code === "model_not_found") return safeError("MODEL_NOT_FOUND", status);
  if (SAFE_PARAMETERS.has(error.param)) {
    if (error.code === "unsupported_parameter") return safeError("UNSUPPORTED_PARAMETER", status, error.param);
    if (error.code === "unsupported_value") return safeError("UNSUPPORTED_VALUE", status, error.param);
  }
  return null;
}

const ERROR_BODY_BYTES = 16 * 1024;
const ERROR_BODY_TIMEOUT = 2000;
async function httpError(network, streamId, status, signal) {
  const fallback = safeError(null, status);
  if (![400, 404, 413, 422].includes(status) || !streamId) return fallback;
  let timer;
  let abort;
  let accepting = true;
  const stopped = new Promise((resolve, reject) => {
    timer = setTimeout(() => resolve(null), ERROR_BODY_TIMEOUT);
    abort = () => reject(safeError("CANCELLED"));
    signal?.addEventListener("abort", abort, { once: true });
    if (signal?.aborted) abort();
  });
  const inspect = async () => {
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let remaining = ERROR_BODY_BYTES;
    let text = "";
    // Bound immediate empty chunks too: a broken bridge must not starve the
    // deadline timer by resolving reads forever without yielding any bytes.
    for (let reads = 0; accepting && !signal?.aborted && remaining > 0 && reads < 64; reads += 1) {
      const chunk = await network.readStream(streamId, { expectedChunkBytes: remaining });
      if (!accepting || signal?.aborted) return null;
      const bytes = chunk?.data || new Uint8Array();
      if (!(bytes instanceof Uint8Array) || bytes.byteLength > remaining) return null;
      remaining -= bytes.byteLength;
      text += decoder.decode(bytes, { stream: !chunk.done });
      if (chunk.done) return providerError(JSON.parse(text), status);
    }
    return null;
  };
  try {
    const classified = await Promise.race([inspect().catch(() => null), stopped]);
    if (signal?.aborted) throw safeError("CANCELLED");
    return classified || fallback;
  } finally {
    accepting = false;
    clearTimeout(timer);
    signal?.removeEventListener("abort", abort);
  }
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
  const source = delta || message;
  // Providers name the field reasoning_content, reasoning or reasoning_details;
  // some send the same text under two names, which is shown once.
  const texts = [source?.reasoning_content, source?.reasoning, source?.reasoning_details]
    .map((value) => textFragments(value).join(""))
    .filter(Boolean);
  return [...new Set(texts)].join("");
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
    if (response.status < 200 || response.status >= 300) throw await httpError(network, streamId, response.status, signal);
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let pending = "";
    let eventLines = [];

    let complete = false;
    let finishedChoice = false;
    let finishReason = null;

    const dispatch = () => {
      if (!eventLines.length) return;
      const event = eventLines.join("\n");
      eventLines = [];

      if (event.trim() === "[DONE]") { complete = true; return; }
      let json;
      try { json = JSON.parse(event); } catch { throw safeError("INVALID_RESPONSE"); }
      if (json.error) throw providerError(json) || safeError("INVALID_RESPONSE");
      const choice = json.choices?.[0];
      const reasoning = extractReasoningText(choice);
      if (reasoning && typeof onReasoning === "function") onReasoning(reasoning);
      const content = extractContentText(choice);
      if (content && typeof onDelta === "function") onDelta(content);
      if (choice?.finish_reason) { finishedChoice = true; finishReason = choice.finish_reason; }
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
    // "length" means the provider cut the answer at its token limit.
    return { finishReason };
  } finally {
    signal?.removeEventListener("abort", cancel);
    await cancel();
  }
}
