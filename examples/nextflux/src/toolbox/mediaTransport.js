import { assertServerUrl } from "./network.js";

// Matches the host's existing network ceiling. This is a shared retained payload
// budget, not a per-image/video quota or a claim about total decoded WebView RAM.
export const MEDIA_RESOURCE_BYTES = 64 * 1024 * 1024;
const messages = {
  CANCELLED: "媒体加载已取消。",
  PERMISSION_DENIED: "请在小工具权限中开启网络访问。",
  NOT_DECLARED: "此版本未声明网络权限。",
  UNSUPPORTED: "当前宿主不支持媒体分块传输，请升级 ToolBox。",
  NETWORK_UNAVAILABLE: "媒体加载失败，请检查网络后重试。",
  NETWORK_TIMEOUT: "媒体加载超时，请重试。",
  INVALID_MEDIA: "媒体内容无效。",
  INVALID_MIME: "服务器未返回受支持的图片格式或音视频格式。",
  QUOTA_EXCEEDED: "媒体超过宿主传输预算，无法在当前页面完整加载。",
  RESOURCE_PRESSURE: "当前媒体占用的缓存较多，请关闭其他媒体后重试。",
};
const failure = code => Object.assign(new Error(messages[code] || messages.NETWORK_UNAVAILABLE), { code });

export function createMediaTransport({
  network = () => globalThis.window?.ToolBox?.network,
  maxBytes = MEDIA_RESOURCE_BYTES,
  maxConcurrent = 3,
  onPressure = () => {},
} = {}) {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1 || !Number.isInteger(maxConcurrent) || maxConcurrent < 1) throw new TypeError("Invalid media resource budget");
  const owners = new WeakMap();
  const queue = [];
  let usedBytes = 0;
  let running = 0;

  function acquire(signal) {
    if (signal?.aborted) return Promise.reject(failure("CANCELLED"));
    if (running < maxConcurrent) { running += 1; return Promise.resolve(); }
    return new Promise((resolve, reject) => {
      const waiter = { resolve, signal, abort: null };
      waiter.abort = () => {
        const index = queue.indexOf(waiter);
        if (index >= 0) queue.splice(index, 1);
        reject(failure("CANCELLED"));
      };
      queue.push(waiter);
      signal?.addEventListener("abort", waiter.abort, { once: true });
    });
  }
  function unlock() {
    running -= 1;
    const waiter = queue.shift();
    if (waiter) {
      waiter.signal?.removeEventListener("abort", waiter.abort);
      running += 1;
      waiter.resolve();
    }
  }
  function release(blob) {
    const bytes = owners.get(blob);
    if (bytes === undefined) return;
    owners.delete(blob);
    usedBytes -= bytes;
  }

  async function load(value, { accept, mime, signal, check = () => {} }) {
    const url = assertServerUrl(value);
    const verify = () => { if (signal?.aborted) throw failure("CANCELLED"); check(); };
    await acquire(signal);
    let api;
    let streamId;
    let completed = false;
    let reserved = 0;
    const chunks = [];
    const abort = () => { if (streamId) Promise.resolve().then(() => api.cancelStream(streamId)).catch(() => {}); };
    try {
      verify();
      api = network();
      if (!api?.openStream || !api.readStream || !api.cancelStream) throw failure("UNSUPPORTED");
      const response = await api.openStream({
        url: url.href, method: "GET", headers: { Accept: accept },
        timeoutMs: 60000, maxResponseBytes: maxBytes,
      }, { signal });
      streamId = response.streamId;
      if (typeof streamId !== "string" || !streamId) throw failure("INVALID_MEDIA");
      signal?.addEventListener("abort", abort, { once: true });
      verify();
      if (response.status < 200 || response.status >= 300) throw failure("NETWORK_UNAVAILABLE");
      const type = Object.entries(response.headers || {}).find(([key]) => key.toLowerCase() === "content-type")?.[1]?.split(";")[0]?.trim()?.toLowerCase();
      if (!type || !mime.test(type)) throw failure("INVALID_MIME");
      while (true) {
        verify();
        const chunk = await api.readStream(streamId);
        verify();
        if (!(chunk.data instanceof Uint8Array) || typeof chunk.done !== "boolean") throw failure("INVALID_MEDIA");
        const bytes = chunk.data.byteLength;
        if (bytes > maxBytes - usedBytes) onPressure();
        if (bytes > maxBytes - usedBytes) throw failure("RESOURCE_PRESSURE");
        if (bytes) { usedBytes += bytes; reserved += bytes; chunks.push(chunk.data); }
        if (chunk.done) { completed = true; break; }
      }
      if (!reserved) throw failure("INVALID_MEDIA");
      const blob = new Blob(chunks, { type });
      owners.set(blob, reserved);
      reserved = 0; // The Blob lease now owns the payload reservation.
      return blob;
    } catch (error) {
      if (signal?.aborted || error?.name === "AbortError" || error?.code === "CANCELLED") throw failure("CANCELLED");
      throw failure(Object.hasOwn(messages, error?.code) ? error.code : "NETWORK_UNAVAILABLE");
    } finally {
      signal?.removeEventListener("abort", abort);
      chunks.length = 0;
      usedBytes -= reserved;
      try { if (streamId && !completed) await api.cancelStream(streamId); } catch { /* Native session may already be gone. */ }
      unlock();
    }
  }
  return { load, release, get retainedBytes() { return usedBytes; } };
}
