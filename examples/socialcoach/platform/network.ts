import { host, permissionMessage } from './bridge';

const active = new Set<AbortController>();
const settling = new Set<Promise<unknown>>();
export const abortRequests = () => { for (const c of active) c.abort(); };
export function trackAbortableTask<T>(task: Promise<T>): Promise<T> {
  settling.add(task);
  void task.finally(() => settling.delete(task)).catch(() => {});
  return task;
}
export async function abortAndSettleRequests(): Promise<void> {
  abortRequests();
  await Promise.allSettled([...settling]);
}
export function abortError() { return new DOMException('请求已取消', 'AbortError'); }
export function timeoutError() { return new DOMException('模型服务长时间没有返回数据，请求已超时。请检查网络或模型服务后重试。', 'TimeoutError'); }
/** The reason a signal stopped: a distinct timeout, or a plain cancel. */
export function stopReason(signal?: AbortSignal | null): DOMException {
  const reason = signal?.reason;
  return reason instanceof DOMException && reason.name === 'TimeoutError' ? reason : abortError();
}

/**
 * Idle, not total: the clock restarts on every byte received, so long but
 * progressing generations are never cut off. Only silence this long stops a
 * request (including one that never answers at all).
 */
let idleMs = 180000;
export function setIdleTimeout(ms: number) { idleMs = ms; }
const activity = new WeakMap<AbortSignal, () => void>();
/** Report progress on a request signal; resets its scope's idle clock and every parent scope's. */
export function touch(signal?: AbortSignal | null) { if (signal) activity.get(signal)?.(); }

/** Validate before attaching credentials. HTTPS only; never accept credentials/query/fragment in a base URL. */
export function endpoint(base: string, path: string): string {
  let url: URL;
  try { url = new URL(base); } catch { throw new Error('API 地址格式不正确，请填写完整的 HTTPS 地址。'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) {
    throw new Error('API 地址必须使用 HTTPS，且不能包含用户名、密码、查询参数或锚点。');
  }
  return url.href.replace(/\/+$/, '') + '/' + path.replace(/^\/+/, '');
}

/** Native HTTPS -> Fetch Response; backpressure, byte-safe decoding and cancel propagate to the host. */
export async function request(url: string, init: RequestInit = {}): Promise<Response> {
  const api = host();
  const signal = init.signal;
  if (!api) {
    // Browser preview: the same idle clock, fed by the fetch body.
    const res = await fetch(url, init).catch((e) => { if (signal?.aborted) throw stopReason(signal); throw e; });
    touch(signal);
    if (!res.body || [204, 205, 304].includes(res.status)) return res;
    // Body errors after the headers report why the signal stopped (timeout vs cancel), not the raw AbortError.
    const reader = res.body.getReader();
    let fetchController: ReadableStreamDefaultController<Uint8Array> | undefined;
    const onFetchAbort = () => { try { fetchController?.error(stopReason(signal)); } catch {} void reader.cancel().catch(() => {}); };
    const body = new ReadableStream<Uint8Array>({
      start(c) { fetchController = c; if (signal?.aborted) onFetchAbort(); else signal?.addEventListener('abort', onFetchAbort, {once: true}); },
      async pull(c) {
        try {
          const part = await reader.read();
          if (part.done) { signal?.removeEventListener('abort', onFetchAbort); c.close(); return; }
          touch(signal); c.enqueue(part.value);
        } catch (e) { signal?.removeEventListener('abort', onFetchAbort); c.error(signal?.aborted ? stopReason(signal) : e); }
      },
      cancel(reason) { signal?.removeEventListener('abort', onFetchAbort); return reader.cancel(reason); },
    });
    return new Response(body, {status: res.status, statusText: res.statusText, headers: res.headers});
  }
  if (signal?.aborted) throw stopReason(signal);
  const headers = Object.fromEntries(new Headers(init.headers).entries());
  let opened;
  try {
    opened = await api.network.openStream({url, method: init.method || 'GET', headers,
      ...(typeof init.body === 'string' ? {body: init.body} : {}), timeoutMs: 0}, {signal: signal ?? undefined});
  } catch (e) {
    if (signal?.aborted) throw stopReason(signal);
    throw new Error(permissionMessage(e));
  }
  let finished = false;
  let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
  const release = async () => {
    if (finished) return;
    finished = true;
    signal?.removeEventListener('abort', onAbort);
    await api.network.cancelStream(opened.streamId).catch(() => {});
  };
  const onAbort = () => { if (!finished) { controller?.error(stopReason(signal)); void release(); } };
  touch(signal);
  if (signal?.aborted) { await release(); throw stopReason(signal); }
  const body = new ReadableStream<Uint8Array>({
    start(c) { controller = c; signal?.addEventListener('abort', onAbort, {once: true}); },
    async pull(c) {
      if (finished) return;
      try {
        const chunk = await api.network.readStream(opened.streamId);
        if (finished) return;
        if (chunk.data.byteLength) { touch(signal); c.enqueue(new Uint8Array(chunk.data)); }
        if (chunk.done) { c.close(); await release(); }
      } catch (e) {
        if (!finished) { c.error(signal?.aborted ? stopReason(signal) : new Error(permissionMessage(e))); await release(); }
      }
    },
    cancel: release,
  });
  if ([204, 205, 304].includes(opened.status)) { await body.cancel(); return new Response(null, {status: opened.status, headers: opened.headers}); }
  return new Response(body, {status: opened.status, headers: opened.headers});
}

/** One scope per task, not per frame. A scene transition aborts the whole task, including JSON repair calls. */
export function requestScope(parent?: AbortSignal) {
  const controller = new AbortController();
  const stop = () => controller.abort(parent?.reason);
  if (parent?.aborted) stop(); else parent?.addEventListener('abort', stop, {once: true});
  active.add(controller);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const arm = () => { clearTimeout(timer); timer = setTimeout(() => controller.abort(timeoutError()), idleMs); };
  activity.set(controller.signal, () => { arm(); touch(parent); });
  arm();
  return {signal: controller.signal, dispose() {
    active.delete(controller); parent?.removeEventListener('abort', stop); clearTimeout(timer); activity.delete(controller.signal);
  }};
}

/** Parse SSE incrementally. Handles CRLF split across chunks, multi-line data and UTF-8 boundaries. */
export async function* sse(response: Response): AsyncGenerator<string> {
  if (!response.body) throw new Error('模型没有返回响应正文。');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '', data: string[] = [];
  try {
    while (true) {
      const part = await reader.read();
      buffer += part.done ? decoder.decode() : decoder.decode(part.value, {stream: true});
      let idx: number;
      while ((idx = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, idx).replace(/\r$/, ''); buffer = buffer.slice(idx + 1);
        if (!line) { if (data.length) { yield data.join('\n'); data = []; } }
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (part.done) break;
    }
    if (buffer.startsWith('data:')) data.push(buffer.slice(5).replace(/^ /, '').replace(/\r$/, ''));
    if (data.length) yield data.join('\n');
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
