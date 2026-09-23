import { host, permissionMessage } from './bridge';

const active = new Set<AbortController>();
export const abortRequests = () => { for (const c of active) c.abort(); };
export function abortError() { return new DOMException('请求已取消', 'AbortError'); }

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
  if (!api) return fetch(url, init);
  const signal = init.signal;
  if (signal?.aborted) throw abortError();
  const headers = Object.fromEntries(new Headers(init.headers).entries());
  let opened;
  try {
    opened = await api.network.openStream({url, method: init.method || 'GET', headers,
      ...(typeof init.body === 'string' ? {body: init.body} : {}), timeoutMs: 180000}, {signal: signal ?? undefined});
  } catch (e) {
    if (signal?.aborted) throw abortError();
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
  const onAbort = () => { if (!finished) { controller?.error(abortError()); void release(); } };
  if (signal?.aborted) { await release(); throw abortError(); }
  const body = new ReadableStream<Uint8Array>({
    start(c) { controller = c; signal?.addEventListener('abort', onAbort, {once: true}); },
    async pull(c) {
      if (finished) return;
      try {
        const chunk = await api.network.readStream(opened.streamId);
        if (finished) return;
        if (chunk.data.byteLength) c.enqueue(new Uint8Array(chunk.data));
        if (chunk.done) { c.close(); await release(); }
      } catch (e) {
        if (!finished) { c.error(signal?.aborted ? abortError() : new Error(permissionMessage(e))); await release(); }
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
  const stop = () => controller.abort();
  if (parent?.aborted) stop(); else parent?.addEventListener('abort', stop, {once: true});
  active.add(controller);
  const timeout = setTimeout(stop, 180000);
  return {signal: controller.signal, dispose() {
    active.delete(controller); parent?.removeEventListener('abort', stop); clearTimeout(timeout);
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
