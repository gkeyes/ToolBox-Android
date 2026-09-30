import { useCallback, useEffect, useRef, useState } from "react";
import { SERVER_URL } from "./network.js";
import { createImageCache } from "./imageCache.js";
import { createMediaTransport } from "./mediaTransport.js";
import { imageDimensions } from "./imageDimensions.js";

const RASTER_MIME = /^image\//i;
const transport = createMediaTransport();
const images = createImageCache({
  load: (url, check, signal) => transport.load(url, {
    accept: "image/*",
    mime: RASTER_MIME, check, signal,
  }),
});

export function approvedImageSource(value, allowLocal = false) {
  if (typeof value !== "string" || !value) return null;
  if (/^data:image\/[a-z0-9.+-]+;base64,[a-z0-9+/=\s]+$/i.test(value)) return { kind: "local", url: value };
  if (images.hasBlob(value)) return { kind: "local", url: value };
  if (allowLocal && /^(?:\.\/|\/)?(?:assets\/)?[a-z0-9_./-]+\.(?:png|jpe?g|gif|webp|avif|svg)$/i.test(value) && !value.includes("..") && !value.startsWith("//")) return { kind: "local", url: value };
  try {
    const url = new URL(value, `${SERVER_URL}/`);
    if (url.protocol !== "https:" || url.username || url.password) return null;
    return { kind: "proxy", url: url.href };
  } catch { return null; }
}

// Remote media uses the native permission-gated proxy without account credentials.
export function acquireImage(value, allowLocal = false) {
  const approved = approvedImageSource(value, allowLocal);
  if (!approved) throw new Error("图片地址无效，请使用 HTTPS 图片地址。");
  if (approved.kind === "local" && !images.hasBlob(approved.url)) {
    return { url: approved.url, promise: Promise.resolve(approved.url), release() {} };
  }
  return images.acquire(approved.url);
}

export function useSafeImage(src, allowLocal = false, keepAlive = false) {
  // Compare the candidate identities, rather than the array allocated by a
  // portal render. The cache still shares each actual URL across all viewers.
  const sourceKey = JSON.stringify([...new Set((Array.isArray(src) ? src : [src]).filter((value) => typeof value === "string" && value))].slice(0, 32));
  const containerRef = useRef(null);
  const leaseRef = useRef(null);
  const decodeErrorRef = useRef(null);
  const [visible, setVisible] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState({ key: null, source: null, url: null, error: null });
  // Opening a gallery retains mounted images, without fetching unseen images.
  const active = visible || (keepAlive && leaseRef.current?.key === sourceKey);
  useEffect(() => {
    const element = containerRef.current;
    if (!element || typeof IntersectionObserver === "undefined") { setVisible(true); return; }
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), { rootMargin: "400px" });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    const sources = JSON.parse(sourceKey);
    if (!active || !sources.length) return;
    let mounted = true;
    const epoch = mediaEpoch;
    let current = null, cursor = 0;
    const release = () => {
      if (leaseRef.current === current) leaseRef.current = null;
      current?.handle.release();
      current = null;
    };
    const advance = async (failure = null) => {
      release();
      while (mounted && epoch === mediaEpoch && cursor < sources.length) {
        const source = sources[cursor++];
        let request;
        try {
          request = { key: sourceKey, source, handle: acquireImage(source, allowLocal) };
          current = request;
          leaseRef.current = request;
          request.url = request.handle.url;
          setState({ key: sourceKey, source, url: request.url, error: null });
          const url = await request.handle.promise;
          if (!mounted || current !== request) return;
          request.url = url;
          setState({ key: sourceKey, source, url, error: null });
          return;
        } catch (error) {
          if (!mounted || (request && current !== request)) return;
          failure = error;
          release();
          // Account teardown invalidates pending leases. It must not start a
          // new candidate under the next account's cache generation.
          if (error.code === "CANCELLED" || error.code === "ACCOUNT_CHANGED") break;
        }
      }
      if (mounted) setState({ key: sourceKey, source: null, url: null, error: failure?.message || "没有可安全显示的图片地址。" });
    };
    const onDecodeError = (url) => {
      if (!mounted || epoch !== mediaEpoch || !url || current?.url !== url) return;
      current.handle.invalidate?.();
      void advance(new Error("图片解码失败，暂时无法显示。"));
    };
    decodeErrorRef.current = onDecodeError;
    void advance();
    return () => {
      mounted = false;
      if (decodeErrorRef.current === onDecodeError) decodeErrorRef.current = null;
      release();
    };
  }, [sourceKey, active, allowLocal, attempt]);
  const retry = useCallback(() => {
    leaseRef.current?.handle.invalidate?.();
    setState({ key: sourceKey, source: null, url: null, error: null });
    setAttempt((value) => value + 1);
  }, [sourceKey]);
  const onError = useCallback((url) => decodeErrorRef.current?.(url), []);
  return {
    containerRef, retry, onError,
    source: state.key === sourceKey ? state.source : null,
    url: active && state.key === sourceKey && (!state.url?.startsWith("blob:") || images.hasBlob(state.url)) ? state.url : null,
    error: state.key === sourceKey ? state.error : null,
  };
}

const activeMedia = new Set();
let mediaEpoch = 0;

export function clearMediaCache() {
  imageDimensions.clear();
  images.clear();
  mediaEpoch += 1;
  for (const media of activeMedia) media.release();
}

export async function attachProxyHlsMedia(element, value, callbacks) {
  const approved = approvedImageSource(value);
  if (approved?.kind !== "proxy") throw new Error("流媒体地址无效，请使用 HTTPS 地址。");
  const epoch = mediaEpoch;
  let playback = null, released = false;
  const media = {
    release() {
      if (released) return;
      released = true;
      callbacks.signal?.removeEventListener("abort", abort);
      playback?.release();
      activeMedia.delete(media);
    },
  };
  const abort = () => media.release();
  activeMedia.add(media);
  if (callbacks.signal?.aborted) media.release();
  else callbacks.signal?.addEventListener("abort", abort, { once: true });
  try {
    const { attachHlsPlayback } = await import("./hlsPlayback.mjs");
    if (released || epoch !== mediaEpoch) throw Object.assign(new Error("媒体加载已取消。"), { code: "CANCELLED" });
    playback = attachHlsPlayback(element, approved.url, {
      onReady: () => { if (!released && epoch === mediaEpoch) callbacks.onReady?.(); },
      onError: (error) => { if (!released && epoch === mediaEpoch) callbacks.onError?.(error); },
    });
    return media;
  } catch (error) { media.release(); throw error; }
}

export async function loadProxyMedia(value, kind, { signal, onProgress } = {}) {
  const approved = approvedImageSource(value);
  if (approved?.kind !== "proxy" || !["audio", "video"].includes(kind)) throw new Error("媒体地址或类型无效，请使用 HTTPS 音视频地址。");
  const native = globalThis.window?.ToolBox?.network;
  if (native?.openMedia && native.closeMedia) {
    const epoch = mediaEpoch;
    const controller = new AbortController();
    let session = null, released = false;
    const media = {
      release() {
        if (released) return;
        released = true;
        controller.abort();
        signal?.removeEventListener("abort", abort);
        if (session?.sessionId) void Promise.resolve(native.closeMedia(session.sessionId)).catch(() => {});
        activeMedia.delete(media);
      },
    };
    const abort = () => media.release();
    activeMedia.add(media);
    if (signal?.aborted) media.release();
    else signal?.addEventListener("abort", abort, { once: true });
    try {
      session = await native.openMedia({ url: approved.url, kind }, { signal: controller.signal });
      if (released || epoch !== mediaEpoch) {
        if (session?.sessionId) void Promise.resolve(native.closeMedia(session.sessionId)).catch(() => {});
        throw Object.assign(new Error("媒体加载已取消。"), { code: "CANCELLED" });
      }
      if (typeof session?.sessionId !== "string" || !session.sessionId || typeof session.url !== "string") throw new Error("宿主返回了无效的媒体会话。");
      const local = new URL(session.url, window.location.href);
      if (local.origin !== window.location.origin || local.username || local.password || local.search || local.hash || !/^\/\.toolbox\/media\/[a-z0-9_-]+$/i.test(local.pathname)) throw new Error("宿主返回了无效的媒体会话。");
      return { url: local.href, release: media.release, isActive: () => !released && epoch === mediaEpoch };
    } catch (error) { media.release(); throw error; }
  }
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  else signal?.addEventListener("abort", abort, { once: true });
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
      blob = null;
      activeMedia.delete(media);
    },
  };
  activeMedia.add(media);
  try {
    blob = await transport.load(approved.url, {
      accept: kind === "audio" ? "audio/*" : "video/*",
      mime: kind === "audio" ? /^audio\// : /^video\//,
      signal: controller.signal,
      timeoutMs: 60000, onProgress,
    });
    if (released || epoch !== mediaEpoch) {
      blob = null;
      throw Object.assign(new Error("媒体加载已取消。"), { code: "CANCELLED" });
    }
    url = URL.createObjectURL(blob);
    return { url, release: media.release, isActive: () => !released && epoch === mediaEpoch };
  } catch (error) {
    media.release();
    throw error;
  } finally {
    signal?.removeEventListener("abort", abort);
  }
}
