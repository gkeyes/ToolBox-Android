const MANIFEST_BYTES = 1024 * 1024;
const SEGMENT_BYTES = 32 * 1024 * 1024;
const SAFE_HEADERS = new Set(["accept", "accept-language", "if-range", "if-none-match", "if-modified-since", "cache-control", "pragma"]);
const messages = {
  PERMISSION_DENIED: "请在小工具权限中开启网络访问。",
  NOT_DECLARED: "此版本未声明网络权限。",
  UNSUPPORTED: "当前运行环境不支持媒体分块传输。",
  INVALID_URL: "流媒体地址或重定向地址无效。",
  INVALID_RANGE: "媒体范围请求无效。",
  INVALID_RESPONSE: "流媒体响应无效。",
  INCOMPLETE_RESPONSE: "流媒体响应不完整，请重试。",
  QUOTA_EXCEEDED: "媒体清单或分片超过传输上限。",
  NETWORK_TIMEOUT: "媒体请求超时，请重试。",
  NETWORK_UNAVAILABLE: "无法读取流媒体，请检查网络后重试。",
};
const failure = code => Object.assign(new Error(messages[code] || messages.NETWORK_UNAVAILABLE), { code });
const clock = () => globalThis.performance?.now?.() ?? Date.now();
const freshStats = () => ({
  aborted: false, loaded: 0, retry: 0, total: 0, chunkCount: 0, bwEstimate: 0,
  loading: { start: 0, first: 0, end: 0 }, parsing: { start: 0, end: 0 }, buffering: { start: 0, first: 0, end: 0 },
});
function mediaUrl(value) {
  let url;
  try { url = new URL(value); } catch { throw failure("INVALID_URL"); }
  if (url.protocol !== "https:" || url.username || url.password) throw failure("INVALID_URL");
  return url.href;
}
function rangeFor(context) {
  const { rangeStart, rangeEnd } = context;
  // hls.js uses zero offsets for a resource without an EXT-X-BYTERANGE.
  if ((rangeStart === undefined && rangeEnd === undefined) || (rangeStart === 0 && rangeEnd === 0)) return null;
  const start = rangeStart ?? 0;
  if (!Number.isSafeInteger(start) || start < 0 || (rangeEnd !== undefined && (!Number.isSafeInteger(rangeEnd) || rangeEnd <= start))) throw failure("INVALID_RANGE");
  return { start, end: rangeEnd, header: `bytes=${start}-${rangeEnd === undefined ? "" : rangeEnd - 1}` };
}
function headersFor(context, range) {
  const headers = { Accept: context.responseType === "arraybuffer" ? "*/*" : "application/vnd.apple.mpegurl, application/x-mpegURL, text/plain;q=0.9, */*;q=0.5" };
  for (const [name, value] of Object.entries(context.headers || {})) {
    if (SAFE_HEADERS.has(name.toLowerCase()) && typeof value === "string" && !/[\r\n]/.test(value)) {
      if (name.toLowerCase() === "accept") headers.Accept = value;
      else headers[name] = value;
    }
  }
  if (range) headers.Range = range.header;
  return headers;
}
function finiteTimeout(value, fallback) {
  return Number.isFinite(value) && value > 0 ? Math.min(0x7fffffff, Math.max(1, Math.ceil(value))) : fallback;
}
function joined(chunks, size) {
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return bytes;
}
function responseLength(headers, range, status) {
  const contentRange = headers["content-range"];
  const match = typeof contentRange === "string" && /^bytes (\d+)-(\d+)\/(?:\d+|\*)$/i.exec(contentRange);
  if (range) {
    if (status !== 206 || !match || Number(match[1]) !== range.start || (range.end !== undefined && Number(match[2]) !== range.end - 1)) throw failure("INVALID_RANGE");
  }
  if (match) {
    const size = Number(match[2]) - Number(match[1]) + 1;
    if (!Number.isSafeInteger(size) || size <= 0) throw failure("INVALID_RESPONSE");
    return size;
  }
  // A decoded compressed response need not have the wire Content-Length.
  if (headers["content-encoding"] && headers["content-encoding"].toLowerCase() !== "identity") return null;
  const value = headers["content-length"];
  if (value === undefined) return null;
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value))) throw failure("INVALID_RESPONSE");
  return Number(value);
}

export function createNativeHlsLoader({
  network = () => globalThis.window?.ToolBox?.network,
  manifestLimit = MANIFEST_BYTES,
  segmentLimit = SEGMENT_BYTES,
  timeoutMs = 30000,
  now = clock,
  setTimer = (callback, delay) => setTimeout(callback, delay),
  clearTimer = timer => clearTimeout(timer),
} = {}) {
  if (![manifestLimit, segmentLimit, timeoutMs].every(value => Number.isSafeInteger(value) && value > 0)) throw new TypeError("Invalid native HLS resource limits");
  return class NativeHlsLoader {
    constructor() {
      this.context = null;
      this.stats = freshStats();
      this._operation = null;
      this._destroyed = false;
      this._used = false;
      this._headers = {};
    }
    load(context, config, callbacks) {
      if (this._destroyed) return;
      if (this._used) throw new Error("Loader can only be used once.");
      this._used = true;
      this.context = context;
      this.stats.loading.start = now();
      const operation = this._operation = {
        context, config, callbacks, stats: this.stats, controller: new AbortController(),
        api: null, streamId: null, cancelledStream: false, completed: false, stopped: false,
        timer: null, chunks: [], buffered: 0, networkDetails: null,
      };
      void this._load(operation);
    }
    _active(operation) { return !this._destroyed && this._operation === operation && !operation.stopped; }
    _cancelStream(operation) {
      if (!operation.streamId || operation.completed || operation.cancelledStream) return;
      operation.cancelledStream = true;
      Promise.resolve().then(() => operation.api.cancelStream(operation.streamId)).catch(() => {});
    }
    _stop(operation) {
      operation.stopped = true;
      if (operation.timer !== null) clearTimer(operation.timer);
      operation.timer = null;
      if (!operation.completed) { operation.controller.abort(); this._cancelStream(operation); }
      operation.chunks.length = 0;
      operation.buffered = 0;
    }
    _deadline(operation, delay) {
      if (operation.timer !== null) clearTimer(operation.timer);
      operation.timer = setTimer(() => {
        if (this._active(operation)) this._timeout(operation);
      }, Math.max(0, delay));
    }
    _timeout(operation) {
      const callback = operation.callbacks?.onTimeout;
      operation.stats.aborted = true;
      operation.stats.loading.end = Math.max(now(), operation.stats.loading.start);
      operation.networkDetails = { ...(operation.networkDetails || {}), nativeCode: "NETWORK_TIMEOUT" };
      this._stop(operation);
      operation.callbacks = null;
      callback?.(operation.stats, operation.context, operation.networkDetails);
    }
    _error(operation, error, status = 0) {
      const nativeCode = Object.hasOwn(messages, error?.code) ? error.code : "NETWORK_UNAVAILABLE";
      const callback = operation.callbacks?.onError;
      operation.stats.loading.end = Math.max(now(), operation.stats.loading.first, operation.stats.loading.start);
      operation.networkDetails = { ...(operation.networkDetails || {}), nativeCode };
      this._stop(operation);
      operation.callbacks = null;
      callback?.({ code: status, text: status ? `媒体服务器返回 HTTP ${status}。` : messages[nativeCode] }, operation.context, operation.networkDetails, operation.stats);
    }
    _flush(operation) {
      if (!operation.buffered) return;
      const data = joined(operation.chunks, operation.buffered).buffer;
      operation.chunks.length = 0;
      operation.buffered = 0;
      operation.callbacks?.onProgress?.(operation.stats, operation.context, data, operation.networkDetails);
    }
    async _load(operation) {
      let status = 0;
      try {
        const { context, config, stats } = operation;
        const url = mediaUrl(context.url), range = rangeFor(context);
        operation.api = typeof network === "function" ? network() : network;
        if (!["openStream", "readStream", "cancelStream"].every(name => typeof operation.api?.[name] === "function")) throw failure("UNSUPPORTED");
        const arrayBuffer = context.responseType === "arraybuffer";
        const limit = arrayBuffer && context.type !== "key" ? segmentLimit : manifestLimit;
        const totalTimeout = finiteTimeout(config?.loadPolicy?.maxLoadTimeMs, finiteTimeout(config?.timeout, timeoutMs));
        const firstTimeout = Math.min(totalTimeout, finiteTimeout(config?.loadPolicy?.maxTimeToFirstByteMs, totalTimeout));
        this._deadline(operation, firstTimeout);
        const response = await operation.api.openStream({
          url, method: "GET", headers: headersFor(context, range), timeoutMs: totalTimeout, maxResponseBytes: limit,
        }, { signal: operation.controller.signal });
        operation.streamId = response?.streamId;
        if (!this._active(operation)) { this._cancelStream(operation); return; }
        if (typeof operation.streamId !== "string" || !operation.streamId || !Number.isInteger(response?.status)) throw failure("INVALID_RESPONSE");
        status = response.status;
        operation.networkDetails = { status };
        this._headers = Object.fromEntries(Object.entries(response.headers || {}).filter(([, value]) => typeof value === "string").map(([name, value]) => [name.toLowerCase(), value]));
        const finalHeader = Object.hasOwn(response.headers || {}, "x-toolbox-final-url") ? response.headers["x-toolbox-final-url"] : this._headers["x-toolbox-final-url"];
        const finalUrl = finalHeader === undefined ? url : mediaUrl(finalHeader);
        if (status < 200 || status >= 300) { this._error(operation, failure("NETWORK_UNAVAILABLE"), status); return; }
        stats.loading.first = Math.max(now(), stats.loading.start);
        this._deadline(operation, totalTimeout - (stats.loading.first - stats.loading.start));
        const expected = responseLength(this._headers, range, status);
        if (expected !== null && expected > limit) throw failure("QUOTA_EXCEEDED");
        stats.total = expected ?? 0;
        const progressive = arrayBuffer && typeof operation.callbacks?.onProgress === "function" && Number.isFinite(config?.highWaterMark);
        const highWaterMark = Math.max(1, config?.highWaterMark || 0);
        while (this._active(operation)) {
          const chunk = await operation.api.readStream(operation.streamId);
          if (!this._active(operation)) return;
          if (!(chunk?.data instanceof Uint8Array) || typeof chunk.done !== "boolean" || (!chunk.done && !chunk.data.byteLength)) throw failure("INVALID_RESPONSE");
          if (chunk.done) operation.completed = true;
          if (chunk.data.byteLength) {
            stats.loaded += chunk.data.byteLength;
            if (stats.loaded > limit) throw failure("QUOTA_EXCEEDED");
            if (expected !== null && stats.loaded > expected) throw failure("INCOMPLETE_RESPONSE");
            operation.chunks.push(chunk.data.slice());
            operation.buffered += chunk.data.byteLength;
            if (progressive && operation.buffered >= highWaterMark) this._flush(operation);
          }
          if (!this._active(operation)) return;
          if (chunk.done) break;
        }
        if (!this._active(operation)) return;
        if (!operation.completed || !stats.loaded || (expected !== null && stats.loaded !== expected)) throw failure("INCOMPLETE_RESPONSE");
        stats.loading.end = Math.max(now(), stats.loading.first);
        if (!stats.total) stats.total = stats.loaded;
        let data;
        if (progressive) { this._flush(operation); data = new ArrayBuffer(0); }
        else {
          const bytes = joined(operation.chunks, operation.buffered);
          data = arrayBuffer ? bytes.buffer : new TextDecoder().decode(bytes);
          operation.callbacks?.onProgress?.(stats, context, data, operation.networkDetails);
        }
        if (!this._active(operation)) return;
        const callback = operation.callbacks?.onSuccess;
        this._stop(operation);
        operation.callbacks = null;
        callback?.({ url: finalUrl, data, code: status }, stats, context, operation.networkDetails);
      } catch (error) {
        if (!this._active(operation)) return;
        if (error?.code === "NETWORK_TIMEOUT") this._timeout(operation);
        else this._error(operation, error);
      }
    }
    abort() {
      const operation = this._operation;
      if (!operation || !this._active(operation)) return;
      const callback = operation.callbacks?.onAbort;
      operation.stats.aborted = true;
      operation.stats.loading.end = Math.max(now(), operation.stats.loading.first, operation.stats.loading.start);
      this._stop(operation);
      operation.callbacks = null;
      callback?.(operation.stats, operation.context, operation.networkDetails);
    }
    destroy() {
      if (this._destroyed) return;
      this._destroyed = true;
      const operation = this._operation;
      if (operation) {
        if (!operation.stopped) operation.stats.aborted = true;
        operation.callbacks = null;
        this._stop(operation);
      }
      this.context = null;
      this._headers = {};
    }
    getResponseHeader(name) { return this._headers[String(name).toLowerCase()] ?? null; }
    getCacheAge() {
      const value = this.getResponseHeader("age");
      if (value === null || !/^\d+(?:\.\d+)?$/.test(value)) return null;
      const age = Number(value);
      return Number.isFinite(age) ? age : null;
    }
  };
}

export const NativeHlsLoader = createNativeHlsLoader();
export default NativeHlsLoader;
