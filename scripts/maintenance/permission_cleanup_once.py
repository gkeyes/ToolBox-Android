from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path.cwd()
def read(path):
    return (ROOT / path).read_text(encoding='utf-8')
def write(path, text):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8')
def replace(path, old, new, expected=1):
    text = read(path)
    if text.count(old) != expected:
        raise RuntimeError(f'{path}: source pattern changed: {old[:80]}')
    write(path, text.replace(old, new))
for path, expected in {
    'examples/nextflux/src/toolbox/media.js': 'bb19068e12a8d98a3ef3b040f7927f8b1e084d88',
    'examples/nextflux/src/toolbox/imageCache.js': '7725d097eb3824ac9c047dd7becb11ed57db28df',
    'examples/nextflux/test/media.test.mjs': '070539047a179a81c34faf97ff6acdfb4fd8135b',
}.items():
    data = (ROOT / path).read_bytes()
    if hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() != expected:
        raise RuntimeError(f'Reviewed media source changed: {path}')

write('examples/nextflux/src/toolbox/mediaTransport.js', '''import { assertServerUrl } from "./network.js";

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
''')

cache = 'examples/nextflux/src/toolbox/imageCache.js'
replace(cache, '  maxActive = 24,', '  maxActive = Infinity,')
replace(cache, '  load,\n', '  load,\n  dispose = () => {},\n')
replace(cache, '    entry.invalidated = true;\n    if (bySource', '    entry.invalidated = true;\n    entry.controller?.abort();\n    if (entry.blob) { dispose(entry.blob); entry.blob = null; }\n    if (bySource')
replace(cache, '''    try {
      const blob = await load(entry.source, check);
      check();
      entry.blobUrl = createUrl(blob);''', '''    let blob;
    try {
      blob = await load(entry.source, check, entry.controller.signal);
      check();
      entry.blob = blob;
      entry.blobUrl = createUrl(blob);''')
replace(cache, '''    } catch (error) {
      forget(entry, error);
    } finally {''', '''    } catch (error) {
      if (blob && entry.blob !== blob) dispose(blob);
      forget(entry, error);
    } finally {''')
replace(cache, 'entry = { source, epoch, references: 0, blobUrl: null, bytes: 0, settled: false, invalidated: false };', 'entry = { source, epoch, references: 0, blob: null, blobUrl: null, bytes: 0, settled: false, invalidated: false, controller: new AbortController() };')
replace(cache, '    clear() {\n', '    evictIdle() {\n      for (const entry of idle.keys()) forget(entry);\n      scheduleExpiry();\n    },\n    clear() {\n')

media = 'examples/nextflux/src/toolbox/media.js'
text = read(media)
start = text.index('export function approvedImageSource')
text = '''import { useCallback, useEffect, useRef, useState } from "react";
import { SERVER_URL } from "./network.js";
import { createImageCache } from "./imageCache.js";
import { createMediaTransport, MEDIA_RESOURCE_BYTES } from "./mediaTransport.js";

const MAX_INLINE_IMAGE_CHARS = Math.ceil(MEDIA_RESOURCE_BYTES / 3) * 4 + 64;
const RASTER_MIME = /^image\\/(?:png|jpeg|gif|webp|avif|bmp|x-icon|vnd\\.microsoft\\.icon)$/i;
const transport = createMediaTransport({ onPressure: () => images.evictIdle() });
const images = createImageCache({
  load: (url, check, signal) => transport.load(url, {
    accept: "image/png,image/jpeg,image/webp,image/gif,image/avif,image/bmp",
    mime: RASTER_MIME, check, signal,
  }),
  dispose: blob => transport.release(blob),
});

''' + text[start:]
text = text.replace('value.length > MAX_IMAGE_BYTES * 1.4', 'value.length > MAX_INLINE_IMAGE_CHARS')
start = text.index('const MAX_MEDIA_BYTES =')
text = text[:start] + '''const activeMedia = new Set();
let mediaEpoch = 0;

export function clearMediaCache() {
  images.clear();
  mediaEpoch += 1;
  for (const media of activeMedia) media.release();
}

export async function loadProxyMedia(value, kind) {
  const approved = approvedImageSource(value);
  if (approved?.kind !== "proxy" || !["audio", "video"].includes(kind)) throw new Error("该媒体未通过 Miniflux 代理，请复制或分享链接后播放。");
  const controller = new AbortController();
  const epoch = mediaEpoch;
  let blob = null;
  let url = null;
  let released = false;
  const media = {
    release() {
      if (released) return;
      released = true;
      controller.abort();
      if (url) URL.revokeObjectURL(url);
      if (blob) { transport.release(blob); blob = null; }
      activeMedia.delete(media);
    },
  };
  activeMedia.add(media);
  try {
    blob = await transport.load(approved.url, {
      accept: kind === "audio" ? "audio/*" : "video/*",
      mime: kind === "audio" ? /^audio\\/(mpeg|mp4|ogg|wav|x-wav|aac|webm|flac)$/ : /^video\\/(mp4|webm|ogg|quicktime)$/,
      signal: controller.signal,
    });
    if (released || epoch !== mediaEpoch) {
      transport.release(blob);
      blob = null;
      throw Object.assign(new Error("媒体加载已取消。"), { code: "CANCELLED" });
    }
    url = URL.createObjectURL(blob);
    return { url, release: media.release };
  } catch (error) {
    media.release();
    throw error;
  }
}
'''
write(media, text)
manifest = 'examples/nextflux/manifest.json'
replace(manifest, '"maxResponseBytes": 4194304', '"maxResponseBytes": 67108864')

# Preserve existing security, cache sharing, and teardown assertions, adapting
# only their native transport fixtures and assertions for the retired size caps.
tests = 'examples/nextflux/test/media.test.mjs'
text = read(tests)
text = text.replace('import assert from "node:assert/strict";', 'import assert from "node:assert/strict";\nimport { MEDIA_RESOURCE_BYTES } from "../src/toolbox/mediaTransport.js";')
helper = '''
function streamMock(request) {
  let sequence = 0;
  const responses = new Map();
  return {
    async openStream(payload) {
      const response = await request(payload);
      const streamId = `test-${++sequence}`;
      responses.set(streamId, response);
      return { streamId, status: response.status, headers: response.headers };
    },
    async readStream(streamId) {
      const response = responses.get(streamId);
      responses.delete(streamId);
      const data = response.bodyEncoding === "base64"
        ? Uint8Array.from(atob(response.body), c => c.charCodeAt(0))
        : new TextEncoder().encode(response.body || "");
      return { data, done: true, receivedBytes: data.byteLength };
    },
    async cancelStream(streamId) { responses.delete(streamId); },
  };
}
const settleMedia = () => new Promise(resolve => setImmediate(resolve));
'''
text = text.replace('const origin =', helper + '\nconst origin =', 1)
if text.count('network: { request:') != 6:
    raise RuntimeError('Media network fixture count changed')
text = text.replace('network: { request:', 'network: streamMock(')
text = text.replace('} } } };', '}) } };')
text = text.replace(')) } } };', '))) } };')
text = text.replace('calls[0].maxResponseBytes, 2 * 1024 * 1024', 'calls[0].maxResponseBytes, MEDIA_RESOURCE_BYTES')
text = text.replace('calls[0].maxResponseBytes, 4 * 1024 * 1024', 'calls[0].maxResponseBytes, MEDIA_RESOURCE_BYTES')
text = text.replace('    assert.equal(pending.length, 3);', '    await settleMedia();\n    assert.equal(pending.length, 3);')
text = text.replace('    assert.throws(() => acquireImage(`${origin}/proxy/bounded/overflow`), /图片较多/);', '''    const extra = acquireImage(`${origin}/proxy/bounded/overflow`);
    extra.promise.catch(() => {});
    handles.push(extra); // More than 24 small images queue instead of being rejected.''')
text = text.replace('    clearMediaCache();\n    pending[0]', '    await settleMedia();\n    clearMediaCache();\n    pending[0]')
text += '''

test("more than two small media leases are allowed and cleared together", async () => {
  globalThis.window = { ToolBox: { network: streamMock(async () => ({ status: 200, headers: { "content-type": "audio/mpeg" }, bodyEncoding: "base64", body: "aGVsbG8=" })) } };
  try {
    const media = await Promise.all(Array.from({ length: 4 }, () => loadProxyMedia(proxy, "audio")));
    assert.equal(media.length, 4);
    clearMediaCache();
    media.forEach(handle => handle.release());
  } finally { clearMediaCache(); delete globalThis.window; }
});
'''
write(tests, text)

write('examples/nextflux/test/mediaTransport.test.mjs', '''import test from "node:test";
import assert from "node:assert/strict";
import { createMediaTransport } from "../src/toolbox/mediaTransport.js";

const MiB = 1024 * 1024;
const url = "https://miniflux.xiaochen.win/proxy/signature/media";
const options = { accept: "video/*", mime: /^video\\/mp4$/ };
function nativeStream(size, type = "video/mp4") {
  let sequence = 0;
  const pending = new Map();
  const cancelled = [];
  return {
    cancelled,
    async openStream() {
      const streamId = `stream-${++sequence}`;
      pending.set(streamId, size);
      return { streamId, status: 200, headers: { "content-type": type } };
    },
    async readStream(streamId) {
      const remaining = pending.get(streamId);
      assert.notEqual(remaining, undefined);
      const bytes = Math.min(remaining, MiB);
      const next = remaining - bytes;
      if (next) pending.set(streamId, next); else pending.delete(streamId);
      return { data: new Uint8Array(bytes), done: next === 0, receivedBytes: size - next };
    },
    async cancelStream(streamId) { cancelled.push(streamId); pending.delete(streamId); },
  };
}

test("streamed media exceeds old 2 MiB and 4 MiB ceilings without network.request", async () => {
  let api = nativeStream(3 * MiB, "image/png");
  const transport = createMediaTransport({ network: () => api, maxBytes: 16 * MiB });
  const image = await transport.load(url, { accept: "image/*", mime: /^image\\/png$/ });
  api = nativeStream(5 * MiB);
  const video = await transport.load(url, options);
  assert.equal(image.size, 3 * MiB);
  assert.equal(video.size, 5 * MiB);
  assert.equal(transport.retainedBytes, 8 * MiB);
  transport.release(image); transport.release(image); transport.release(video);
  assert.equal(transport.retainedBytes, 0);
});

test("shared retained bytes are bounded, failed reads release reservations, and leases release capacity", async () => {
  let api = nativeStream(6 * MiB);
  const transport = createMediaTransport({ network: () => api, maxBytes: 8 * MiB });
  const first = await transport.load(url, options);
  api = nativeStream(3 * MiB);
  await assert.rejects(transport.load(url, options), { code: "RESOURCE_PRESSURE" });
  assert.equal(transport.retainedBytes, 6 * MiB);
  assert.equal(api.cancelled.length, 1);
  transport.release(first);
  const next = await transport.load(url, options);
  assert.equal(next.size, 3 * MiB);
  transport.release(next);
  assert.equal(transport.retainedBytes, 0);
});

test("cancelled queued work never opens a native stream", async () => {
  const api = nativeStream(MiB);
  let opened = 0;
  let continueHeaders;
  const original = api.openStream.bind(api);
  api.openStream = async () => { opened++; await new Promise(resolve => { continueHeaders = resolve; }); return original(); };
  const transport = createMediaTransport({ network: () => api, maxConcurrent: 1 });
  const first = transport.load(url, options);
  await new Promise(resolve => setImmediate(resolve));
  const controller = new AbortController();
  const queued = transport.load(url, { ...options, signal: controller.signal });
  controller.abort();
  await assert.rejects(queued, { code: "CANCELLED" });
  assert.equal(opened, 1);
  continueHeaders();
  transport.release(await first);
  assert.equal(transport.retainedBytes, 0);
});

test("invalid MIME cancels the native stream before accumulating a body", async () => {
  const api = nativeStream(MiB, "text/html");
  const transport = createMediaTransport({ network: () => api });
  await assert.rejects(transport.load(url, options), { code: "INVALID_MIME" });
  assert.equal(api.cancelled.length, 1);
  assert.equal(transport.retainedBytes, 0);
});
''')

tech = 'docs/ToolBox_Android_技术方案.md'
text = read(tech) + '''

NextFlux 原文阅读媒体使用宿主既有 openStream/readStream/cancelStream 分块接口，不再将整个媒体以单个 Base64 响应复制。移除单图 2 MiB、单音视频 4 MiB、音视频最多两个及默认活动图片最多 24 张的业务门槛；可见资源按需加载，网络读取最多同时三个。

图片和音视频共用 64 MiB 已保留压缩载荷预算（与宿主当前单流上限相同），包括正在读取的分块及已加载 Blob；闲置图片可先释放，关闭/退出时取消未完成请求并归还预算。这不是总堆内存上限、无限大文件支持或经过真机测得的最优值。DOM 中的内联 data 图片仍由输入尺寸检查保护，解码像素内存由 WebView 另行管理。常规 JSON 同步仍保留独立的响应预算，不混同媒体流。
'''
write(tech, text)

# Report remaining historical mentions and active constants for review rather
# than deleting whole docs or changing unrelated UI/system requirements.
subprocess.run(['git', 'grep', '-n', '-E', 'CONFIRMED_ONE_SHOT|每分钟.{0,12}(10|20|30|120)|2 MiB|4 MiB|1 MiB|DESIGN.md|AGENTS.md|toolbox_ui_wireframe|\\.impeccable', '--', 'sdk', 'docs', 'scripts', 'app', 'tool-api', 'tool-runtime', 'examples/nextflux'], check=False)
print('Media source changes prepared. Standard Android/TBX CI still must validate behavior.')
