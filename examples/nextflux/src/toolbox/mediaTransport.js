import { SERVER_URL } from "./network.js";
import { MEDIA_HEADER_BYTES, isGenericBinaryMime, mediaMimeCandidates } from "./mediaMime.js";

const messages = {
  CANCELLED: "媒体加载已取消。",
  PERMISSION_DENIED: "请在小工具权限中开启网络访问。",
  NOT_DECLARED: "此版本未声明网络权限。",
  UNSUPPORTED: "当前运行环境不支持媒体分块传输。",
  NETWORK_UNAVAILABLE: "媒体加载失败，请检查网络后重试。",
  NETWORK_TIMEOUT: "媒体加载超时，请重试。",
  INVALID_MEDIA: "媒体内容无效。",
  INVALID_MIME: "服务器未返回受支持的图片格式或音视频格式。",
  QUOTA_EXCEEDED: "媒体超过宿主传输预算，无法在当前页面完整加载。",
};
const failure = code => Object.assign(new Error(messages[code] || messages.NETWORK_UNAVAILABLE), { code });

export function createMediaTransport({
  network = () => globalThis.window?.ToolBox?.network,
  maxConcurrent = Math.max(1, Math.floor(Number(globalThis.navigator?.hardwareConcurrency) || 1)),
} = {}) {
  if (!Number.isInteger(maxConcurrent) || maxConcurrent < 1) throw new TypeError("Invalid media resource budget");
  const queue = [];
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
  async function load(value, { accept, mime, signal, check = () => {} }) {
    const url = new URL(value, `${SERVER_URL}/`);
    if (url.protocol !== "https:" || url.username || url.password) throw failure("INVALID_MEDIA");
    const verify = () => { if (signal?.aborted) throw failure("CANCELLED"); check(); };
    await acquire(signal);
    let api;
    let streamId;
    let completed = false;
    const chunks = [];
    const abort = () => { if (streamId) Promise.resolve().then(() => api.cancelStream(streamId)).catch(() => {}); };
    try {
      verify();
      api = network();
      if (!api?.openStream || !api.readStream || !api.cancelStream) throw failure("UNSUPPORTED");
      const response = await api.openStream({
        url: url.href, method: "GET", headers: { Accept: accept },
      }, { signal });
      streamId = response.streamId;
      if (typeof streamId !== "string" || !streamId) throw failure("INVALID_MEDIA");
      signal?.addEventListener("abort", abort, { once: true });
      verify();
      if (response.status < 200 || response.status >= 300) throw failure("NETWORK_UNAVAILABLE");
      let type = Object.entries(response.headers || {}).find(([key]) => key.toLowerCase() === "content-type")?.[1]?.split(";")[0]?.trim()?.toLowerCase();
      const needsSignature = !type || isGenericBinaryMime(type);
      if (!needsSignature && !mime.test(type)) throw failure("INVALID_MIME");
      let header = needsSignature ? new Uint8Array(MEDIA_HEADER_BYTES) : null;
      let headerBytes = 0;
      while (true) {
        verify();
        const chunk = await api.readStream(streamId);
        verify();
        if (!(chunk.data instanceof Uint8Array) || typeof chunk.done !== "boolean") throw failure("INVALID_MEDIA");
        const bytes = chunk.data.byteLength;
        if (bytes) chunks.push(chunk.data);
        if (chunk.done) completed = true;
        if (header) {
          const count = Math.min(bytes, MEDIA_HEADER_BYTES - headerBytes);
          header.set(chunk.data.subarray(0, count), headerBytes);
          headerBytes += count;
          type = mediaMimeCandidates(header.subarray(0, headerBytes)).find(candidate => mime.test(candidate));
          if (type) header = null;
          else if (headerBytes === MEDIA_HEADER_BYTES || chunk.done) throw failure("INVALID_MIME");
        }
        if (chunk.done) break;
      }
      if (!chunks.length) throw failure("INVALID_MEDIA");
      const blob = new Blob(chunks, { type });
      return blob;
    } catch (error) {
      if (signal?.aborted || error?.name === "AbortError" || error?.code === "CANCELLED") throw failure("CANCELLED");
      throw failure(Object.hasOwn(messages, error?.code) ? error.code : "NETWORK_UNAVAILABLE");
    } finally {
      signal?.removeEventListener("abort", abort);
      chunks.length = 0;
      try { if (streamId && !completed) await api.cancelStream(streamId); } catch { /* Native session may already be gone. */ }
      unlock();
    }
  }
  return { load };
}
