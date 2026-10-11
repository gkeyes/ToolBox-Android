const ENDPOINTS = Object.freeze({
  global: "https://api.minimax.io/v1/t2a_v2",
  cn: "https://api.minimaxi.com/v1/t2a_v2",
});

const OFFICIAL_HOSTS = new Set(["api.minimax.io", "api.minimaxi.com", "api.minimax.cn"]);
const MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
const MAX_ERROR_TEXT = 180;

const failure = (message, code = "SPEECH_ERROR") => Object.assign(new Error(message), { code });

export function isOfficialMiniMaxBase(baseUrl) {
  try {
    const url = new URL(String(baseUrl || ""));
    return url.protocol === "https:" && !url.username && !url.password && OFFICIAL_HOSTS.has(url.hostname.toLowerCase());
  } catch {
    return false;
  }
}

export function resolveSpeechApiKey(settings) {
  const dedicated = String(settings?.speechApiKey || "").trim();
  if (dedicated) return dedicated;
  const shared = String(settings?.aiApiKey || "").trim();
  if (shared && isOfficialMiniMaxBase(settings?.aiBaseUrl)) return shared;
  throw failure("请先在“设置 → 语音朗读”填写 MiniMax API Key。", "MISSING_KEY");
}

export function speechEndpoint(region) {
  const endpoint = ENDPOINTS[String(region || "global")];
  if (!endpoint) throw failure("MiniMax 语音服务区域设置无效。", "INVALID_REGION");
  return endpoint;
}

// MiniMax returns the whole MP3 as one hex string (a 3-minute segment is
// ~6 MB of hex). The former slice()+parseInt() per byte allocated millions of
// strings and blocked the WebView main thread for seconds per segment, and a
// prefetch decodes the next segment while the current one plays, so taps
// (including "stop") went unanswered. Decode through a lookup table instead,
// and let the async variant yield between slices so stop is never starved.
const HEX_VALUES = (() => {
  const table = new Uint8Array(128).fill(255);
  for (let i = 0; i < 10; i += 1) table[48 + i] = i;
  for (let i = 0; i < 6; i += 1) { table[65 + i] = 10 + i; table[97 + i] = 10 + i; }
  return table;
})();
const invalidAudio = () => failure("MiniMax 返回的音频数据无效。", "INVALID_AUDIO");

function hexSource(hex) {
  const value = String(hex || "").trim();
  if (!value || value.length % 2 !== 0) throw invalidAudio();
  return value;
}

function decodeHexRange(value, bytes, from, to) {
  for (let j = from, i = from * 2; j < to; j += 1, i += 2) {
    const high = value.charCodeAt(i);
    const low = value.charCodeAt(i + 1);
    const a = high < 128 ? HEX_VALUES[high] : 255;
    const b = low < 128 ? HEX_VALUES[low] : 255;
    if (a === 255 || b === 255) throw invalidAudio();
    bytes[j] = (a << 4) | b;
  }
}

export function hexToBytes(hex) {
  const value = hexSource(hex);
  const bytes = new Uint8Array(value.length / 2);
  decodeHexRange(value, bytes, 0, bytes.length);
  return bytes;
}

const yieldToEventLoop = () => new Promise((resolve) => setTimeout(resolve, 0));

export async function hexToBytesAsync(hex, { signal, sliceBytes = 64 * 1024, yieldControl = yieldToEventLoop } = {}) {
  const value = hexSource(hex);
  const bytes = new Uint8Array(value.length / 2);
  for (let from = 0; from < bytes.length; from += sliceBytes) {
    if (signal?.aborted) throw failure("语音生成已停止。", "CANCELLED");
    decodeHexRange(value, bytes, from, Math.min(bytes.length, from + sliceBytes));
    if (from + sliceBytes < bytes.length) await yieldControl();
  }
  if (signal?.aborted) throw failure("语音生成已停止。", "CANCELLED");
  return bytes;
}

// A cancelled native stream may never answer its pending read. Settle the
// read as soon as the run is stopped so nothing keeps waiting on it.
function untilAborted(promise, signal) {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(failure("语音生成已停止。", "CANCELLED"));
  return new Promise((resolve, reject) => {
    const onAbort = () => reject(failure("语音生成已停止。", "CANCELLED"));
    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(
      (value) => { signal.removeEventListener("abort", onAbort); resolve(value); },
      (error) => { signal.removeEventListener("abort", onAbort); reject(error); },
    );
  });
}

function providerMessage(payload) {
  const message = String(payload?.base_resp?.status_msg || payload?.error?.message || "").trim();
  return message ? message.slice(0, MAX_ERROR_TEXT) : "";
}

async function readJsonResponse(response, network, signal) {
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let body = "";
  while (true) {
    if (signal?.aborted) throw failure("语音生成已停止。", "CANCELLED");
    const part = await untilAborted(network.readStream(response.streamId), signal);
    if (!(part?.data instanceof Uint8Array) || typeof part.done !== "boolean") throw failure("MiniMax 返回的数据格式无效。", "INVALID_RESPONSE");
    try { body += decoder.decode(part.data, { stream: !part.done }); }
    catch { throw failure("MiniMax 返回的数据编码无效。", "INVALID_RESPONSE"); }
    if (part.done) break;
  }
  return parseSpeechPayload(body);
}

// Parse the envelope without pushing megabytes of hex through JSON.parse in
// one main-thread task: lift the data.audio string out first, parse the small
// remainder, then put the (lazily sliced) hex string back.
export function parseSpeechPayload(body) {
  const marker = '"audio":"';
  const start = body.indexOf(marker);
  const end = start < 0 ? -1 : body.indexOf('"', start + marker.length);
  if (start >= 0 && end > start) {
    const valueStart = start + marker.length;
    try {
      const payload = JSON.parse(body.slice(0, valueStart) + body.slice(end));
      if (payload?.data && payload.data.audio === "") {
        payload.data.audio = body.slice(valueStart, end);
        return payload;
      }
    } catch { /* fall back to a full parse below */ }
  }
  try { return JSON.parse(body); }
  catch { throw failure("MiniMax 返回的响应不是有效 JSON。", "INVALID_RESPONSE"); }
}

export async function synthesizeSpeechSegment(text, settings, { signal } = {}) {
  const cleanText = String(text || "").trim();
  if (!cleanText) throw failure("没有可朗读的正文。", "EMPTY_TEXT");
  if (cleanText.length > 9990) throw failure("当前朗读分段过长。", "TEXT_TOO_LONG");
  if (signal?.aborted) throw failure("语音生成已停止。", "CANCELLED");

  const network = globalThis.window?.ToolBox?.network;
  if (!network?.openStream || !network?.readStream || !network?.cancelStream) {
    throw failure("当前 ToolBox 版本不支持语音分块传输。", "UNSUPPORTED");
  }

  const apiKey = resolveSpeechApiKey(settings);
  const model = ["speech-2.8-turbo", "speech-2.8-hd"].includes(settings?.speechModel)
    ? settings.speechModel
    : "speech-2.8-turbo";
  const voiceId = String(settings?.speechVoiceId || "presenter_male").trim() || "presenter_male";
  const body = {
    model,
    text: cleanText,
    stream: false,
    voice_setting: { voice_id: voiceId, speed: 1, vol: 1, pitch: 0 },
    audio_setting: { sample_rate: 32000, bitrate: 128000, format: "mp3", channel: 1 },
    language_boost: "auto",
    output_format: "hex",
  };

  let streamId = "";
  let completed = false;
  const abort = () => {
    if (streamId) Promise.resolve(network.cancelStream(streamId)).catch(() => {});
  };
  signal?.addEventListener("abort", abort, { once: true });
  try {
    let response;
    try {
      response = await network.openStream({
        url: speechEndpoint(settings?.speechRegion),
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
        body: JSON.stringify(body),
        timeoutMs: 120000,
        maxResponseBytes: MAX_RESPONSE_BYTES,
      }, { signal });
    } catch (error) {
      if (signal?.aborted || error?.name === "AbortError") throw failure("语音生成已停止。", "CANCELLED");
      if (error?.code === "PERMISSION_DENIED") throw failure("请先开启 NextFlux 的网络权限。", "PERMISSION_DENIED");
      if (error?.code === "NETWORK_TIMEOUT") throw failure("MiniMax 语音生成超时，请重试。", "NETWORK_TIMEOUT");
      if (error?.code === "QUOTA_EXCEEDED") throw failure("语音响应超过 ToolBox 传输预算。", "QUOTA_EXCEEDED");
      throw failure("无法连接 MiniMax 语音服务，请检查网络。", error?.code || "NETWORK_ERROR");
    }
    streamId = response?.streamId || "";
    if (!streamId) throw failure("MiniMax 语音连接未建立。", "INVALID_RESPONSE");
    const payload = await readJsonResponse(response, network, signal);
    completed = true;
    const message = providerMessage(payload);
    if (response.status === 401 || response.status === 403) throw failure("MiniMax API Key 无效或没有 Speech 权限。", "AUTH_ERROR");
    if (response.status < 200 || response.status >= 300) throw failure(message || `MiniMax 语音服务返回 HTTP ${response.status}。`, "HTTP_ERROR");
    if (Number(payload?.base_resp?.status_code || 0) !== 0) throw failure(message || "MiniMax 语音生成失败。", "PROVIDER_ERROR");
    if (signal?.aborted) throw failure("语音生成已停止。", "CANCELLED");
    const bytes = await hexToBytesAsync(payload?.data?.audio, { signal });
    return {
      bytes,
      mimeType: "audio/mpeg",
      durationMs: Number(payload?.extra_info?.audio_length) || 0,
      characters: Number(payload?.extra_info?.usage_characters) || cleanText.length,
    };
  } finally {
    signal?.removeEventListener("abort", abort);
    // Never wait on the cancel: a stopped run must settle even if the native
    // side does not answer.
    if (streamId && !completed) Promise.resolve().then(() => network.cancelStream(streamId)).catch(() => {});
  }
}
