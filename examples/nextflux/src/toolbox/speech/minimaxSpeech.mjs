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

export function hexToBytes(hex) {
  const value = String(hex || "").trim();
  if (!value || value.length % 2 !== 0 || /[^0-9a-f]/i.test(value)) throw failure("MiniMax 返回的音频数据无效。", "INVALID_AUDIO");
  const bytes = new Uint8Array(value.length / 2);
  for (let i = 0, j = 0; i < value.length; i += 2, j += 1) bytes[j] = Number.parseInt(value.slice(i, i + 2), 16);
  return bytes;
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
    const part = await network.readStream(response.streamId);
    if (!(part?.data instanceof Uint8Array) || typeof part.done !== "boolean") throw failure("MiniMax 返回的数据格式无效。", "INVALID_RESPONSE");
    try { body += decoder.decode(part.data, { stream: !part.done }); }
    catch { throw failure("MiniMax 返回的数据编码无效。", "INVALID_RESPONSE"); }
    if (part.done) break;
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
  const voiceId = String(settings?.speechVoiceId || "male-qn-qingse").trim() || "male-qn-qingse";
  const body = {
    model,
    text: cleanText,
    stream: false,
    voice_setting: { voice_id: voiceId, speed: 1, vol: 1, pitch: 0 },
    audio_setting: { sample_rate: 32000, bitrate: 64000, format: "mp3", channel: 1 },
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
    const bytes = hexToBytes(payload?.data?.audio);
    return {
      bytes,
      mimeType: "audio/mpeg",
      durationMs: Number(payload?.extra_info?.audio_length) || 0,
      characters: Number(payload?.extra_info?.usage_characters) || cleanText.length,
    };
  } finally {
    signal?.removeEventListener("abort", abort);
    if (streamId && !completed) {
      try { await network.cancelStream(streamId); } catch { /* stream may already be closed */ }
    }
  }
}
