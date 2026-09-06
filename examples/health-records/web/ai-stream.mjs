import { HealthError } from "./model.mjs";

export const aiCompletionError = (reason) => reason === "length"
  ? new HealthError("MiniMax 已达到本次生成额度，思考或结果被截断；原记录未修改", "AI_OUTPUT_LIMIT")
  : new HealthError("MiniMax 未返回完整结果或正常结束标记；原记录未修改", "AI_INCOMPLETE_RESPONSE");
const incomplete = () => aiCompletionError();
export const emptyAiResult = () => new HealthError("MiniMax 已结束，但没有返回结果正文；这不等于没有修改建议，原记录未修改", "AI_EMPTY_RESULT");
const invalid = () => new HealthError("AI 流式响应格式无效，请重试；原记录未修改", "AI_INVALID_STREAM");
const cancelled = () => new HealthError("已取消本次 AI 请求，原记录未修改", "CANCELLED");

// MiniMax sends incremental deltas; reasoning must never become final JSON content.
function eventReader(onReasoning, expectedFunction) {
  let pending = "", lines = [], content = "", thinking = "", truncated = false, stopped = false, done = false, serviceError = null;
  let functionName = "", argumentsText = "", callId = "", hasCall = false;
  const event = () => {
    if (!lines.length) return;
    const data = lines.join("\n"); lines = [];
    if (data === "[DONE]") { if (!stopped && !serviceError) throw incomplete(); done = true; return; }
    if (done) throw invalid();
    let value;
    try { value = JSON.parse(data); } catch { throw invalid(); }
    if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid();
    if (value.error || value.base_resp?.status_code && value.base_resp.status_code !== 0) { serviceError = value; return; }
    if (serviceError) throw invalid();
    if (!Array.isArray(value.choices)) { if (value.usage || value.base_resp?.status_code === 0) return; throw invalid(); }
    if (!value.choices.length) return;
    if (value.choices.length !== 1) throw invalid();
    const choice = value.choices[0], delta = choice?.delta;
    if (!choice || choice.index != null && choice.index !== 0 || !delta || typeof delta !== "object" || Array.isArray(delta)) throw invalid();
    if (delta.function_call || delta.refusal) throw incomplete();
    if (delta.tool_calls != null) {
      if (!Array.isArray(delta.tool_calls)) throw invalid();
      if (delta.tool_calls.length) {
        if (!expectedFunction) throw incomplete();
        if (stopped || delta.tool_calls.length !== 1) throw invalid();
        const call = delta.tool_calls[0], fn = call?.function;
        if (!call || call.index != null && call.index !== 0 || call.type != null && call.type !== "function" && !(hasCall && call.type === "") || !fn || typeof fn !== "object" || Array.isArray(fn)) throw invalid();
        if (call.id != null) {
          // MiniMax uses empty id/type/name placeholders on continuation deltas.
          if (typeof call.id !== "string" || call.id.length > 200 || !call.id && !hasCall || call.id && callId && callId !== call.id) throw invalid();
          if (call.id) callId = call.id;
        }
        if (fn.name != null) {
          if (typeof fn.name !== "string") throw invalid();
          functionName += fn.name;
          if (!expectedFunction.startsWith(functionName)) throw invalid();
        }
        if (fn.arguments != null) {
          if (typeof fn.arguments !== "string") throw invalid();
          argumentsText += fn.arguments;
          if (argumentsText.length > 150000) throw new HealthError("AI 输出过长，未处理结果");
        }
        hasCall = true;
      }
    }
    if (delta.content != null && typeof delta.content !== "string") throw invalid();
    let reasoning = "";
    if (delta.reasoning_details != null) {
      if (!Array.isArray(delta.reasoning_details)) throw invalid();
      for (const detail of delta.reasoning_details) {
        if (detail?.text != null && typeof detail.text !== "string") throw invalid();
        if (typeof detail?.text === "string") reasoning += detail.text;
      }
    }
    if (!reasoning && delta.reasoning_content != null) {
      if (typeof delta.reasoning_content !== "string") throw invalid();
      reasoning = delta.reasoning_content;
    }
    if (stopped && (delta.content || reasoning)) throw invalid();
    content += delta.content || "";
    if (content.length > 150000) throw new HealthError("AI 输出过长，未处理结果");
    if (reasoning) {
      const remaining = 24000 - thinking.length;
      thinking += reasoning.slice(0, remaining);
      truncated ||= reasoning.length > remaining;
      onReasoning({ text: thinking, truncated });
    }
    if (choice.finish_reason != null && choice.finish_reason !== "") {
      if (choice.finish_reason !== (hasCall ? "tool_calls" : "stop")) throw aiCompletionError(choice.finish_reason);
      if (hasCall && (!expectedFunction || functionName !== expectedFunction || !argumentsText.trim())) throw invalid();
      stopped = true;
    }
  };
  const line = (text) => {
    if (!text) { event(); return; }
    if (text.startsWith(":")) return;
    const colon = text.indexOf(":"), field = colon < 0 ? text : text.slice(0, colon);
    if (field === "data") lines.push(colon < 0 ? "" : text.slice(colon + 1).replace(/^ /, ""));
  };
  return {
    push(text) {
      pending += text;
      let end;
      while ((end = pending.search(/[\r\n]/)) !== -1) {
        if (pending[end] === "\r" && end === pending.length - 1) break;
        line(pending.slice(0, end));
        pending = pending.slice(end + (pending.slice(end, end + 2) === "\r\n" ? 2 : 1));
      }
    },
    finish() {
      if (pending || lines.length) throw incomplete();
      if (serviceError) return serviceError;
      if (!stopped) throw incomplete();
      if (hasCall) return { choices: [{ finish_reason: "tool_calls", message: { content: "", tool_calls: [{ type: "function", function: { name: functionName, arguments: argumentsText } }] } }] };
      if (!content.trim()) throw emptyAiResult();
      return { choices: [{ finish_reason: "stop", message: { content } }] };
    },
  };
}

export function supportsAiStream(api, config) {
  return config.provider === "minimax" && ["openStream", "readStream", "cancelStream"].every(name => typeof api.network?.[name] === "function");
}

export async function streamAiResponse(network, request, { signal, onReasoning = () => {}, onStage = () => {}, expectedFunction } = {}) {
  if (signal?.aborted) throw cancelled();
  const controller = new AbortController(), decoder = new TextDecoder("utf-8", { fatal: true });
  const reader = eventReader(onReasoning, expectedFunction);
  let streamId, cleaned = false, total = 0, body = "", failure;
  const cleanup = () => {
    if (!streamId || cleaned) return Promise.resolve();
    cleaned = true;
    return Promise.resolve(network.cancelStream(streamId)).catch(() => {});
  };
  let rejectAbort;
  const aborted = new Promise((_, reject) => { rejectAbort = reject; });
  const abort = (error) => { if (failure) return; failure = error; controller.abort(); void cleanup(); rejectAbort(error); };
  const externalAbort = () => abort(cancelled());
  signal?.addEventListener("abort", externalAbort, { once: true });
  const timer = setTimeout(() => abort(new HealthError("AI 响应超时，已停止接收；原记录未修改", "TIMEOUT")), request.timeoutMs);
  try {
    onReasoning({ text: "", truncated: false });
    const opening = network.openStream(request, { signal: controller.signal }).then(async response => {
      streamId = response?.streamId;
      if (failure) { await cleanup(); throw failure; }
      return response;
    });
    const response = await Promise.race([opening, aborted]);
    if (typeof streamId !== "string" || !streamId || !Number.isInteger(response?.status)) throw invalid();
    const contentType = Object.entries(response.headers || {}).find(([name]) => name.toLowerCase() === "content-type")?.[1];
    const plainJson = typeof contentType === "string" && contentType.toLowerCase().includes("application/json");
    const isError = response.status < 200 || response.status >= 300 || plainJson;
    onStage("接收实时响应");
    while (true) {
      const part = await Promise.race([network.readStream(streamId), aborted]);
      if (failure) throw failure;
      if (!(part?.data instanceof Uint8Array) || typeof part.done !== "boolean") throw invalid();
      total += part.data.byteLength;
      if (total > request.maxResponseBytes) throw new HealthError("AI 响应超过大小上限，已停止接收", "QUOTA_EXCEEDED");
      let text;
      try { text = decoder.decode(part.data, { stream: !part.done }); } catch { throw invalid(); }
      if (isError) body += text; else reader.push(text);
      if (part.done) break;
      if (!part.data.byteLength) throw invalid();
      // Backpressure coalesces small SSE events and stays below the host read quota.
      await Promise.race([new Promise(resolve => setTimeout(resolve, 100)), aborted]);
    }
    return { status: response.status, bodyEncoding: "text", body: isError ? body : JSON.stringify(reader.finish()) };
  } catch (error) { throw failure || error; }
  finally { clearTimeout(timer); signal?.removeEventListener("abort", externalAbort); await cleanup(); }
}
