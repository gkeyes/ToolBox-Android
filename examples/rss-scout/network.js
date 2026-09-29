(function (root) {
  'use strict';
  const C = root.RSSScoutCore;
  function cancelled() { return new DOMException('已停止', 'AbortError'); }
  function charset(headers, bytes) {
    const type = C.header(headers, 'content-type');
    const explicit = type.match(/charset\s*=\s*["']?([^\s;"']+)/i);
    if (explicit) return explicit[1];
    if (bytes[0] === 0xff && bytes[1] === 0xfe) return 'utf-16le';
    if (bytes[0] === 0xfe && bytes[1] === 0xff) return 'utf-16be';
    const head = new TextDecoder().decode(bytes.slice(0, 1024));
    const xml = head.match(/<\?xml[^>]+encoding\s*=\s*["']([^"']+)/i);
    const meta = head.match(/<meta[^>]+charset\s*=\s*["']?([^\s"'/>;]+)/i);
    return (xml && xml[1]) || (meta && meta[1]) || 'utf-8';
  }
  class Network {
    constructor(api, timeout) { this.api = api; this.timeout = timeout || (() => 20000); }
    async request(url, options = {}) {
      url = C.urlOf(url);
      if (!url.startsWith('https://')) throw new Error('该源为 HTTP：ToolBox 原生网络不能直接验证，可用 Miniflux 补查或尝试订阅');
      const {signal} = options;
      if (signal && signal.aborted) throw cancelled();
      const timeoutMs = Number.isSafeInteger(options.timeoutMs) && options.timeoutMs >= 0 ? options.timeoutMs : this.timeout();
      const request = {url, method:options.method || 'GET', headers:options.headers || {}, timeoutMs};
      if (options.body !== undefined) request.body = JSON.stringify(options.body);
      const response = await this.api.network.openStream(request, signal ? {signal} : undefined);
      const chunks = []; let total = 0, complete = false;
      try {
        while (true) {
          if (signal && signal.aborted) throw cancelled();
          const part = await this.api.network.readStream(response.streamId);
          if (part.data && part.data.length) { const bytes = new Uint8Array(part.data); chunks.push(bytes); total += bytes.length; }
          if (part.done) { complete = true; break; }
        }
        if (signal && signal.aborted) throw cancelled();
        const bytes = new Uint8Array(total); let offset = 0;
        for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
        let decoder; try { decoder = new TextDecoder(charset(response.headers, bytes)); } catch (_) { decoder = new TextDecoder('utf-8'); }
        const finalUrl = C.header(response.headers, 'x-toolbox-final-url');
        if (!finalUrl) throw new Error('宿主未返回最终响应网址，无法安全处理响应');
        return {status:response.status, headers:response.headers, url:C.urlOf(finalUrl), text:decoder.decode(bytes)};
      } finally {
        if (!complete) { try { await this.api.network.cancelStream(response.streamId); } catch (_) {} }
      }
    }
  }
  root.RSSScoutNetwork = Network;
})(window);
