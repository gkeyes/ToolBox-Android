import { useCallback, useEffect, useRef, useState } from "react";
import { request, SERVER_URL } from "./network.js";

const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
const MAX_IMAGES = 24;
const MAX_IN_FLIGHT = 3;
const RASTER_MIME = /^image\/(?:png|jpeg|gif|webp|avif|bmp|x-icon|vnd\.microsoft\.icon)$/i;
const activeImages = new Map();
const ownedBlobs = new Set();
const queue = [];
let inFlight = 0;

export function approvedImageSource(value, allowLocal = false) {
  if (typeof value !== "string" || !value || value.length > MAX_IMAGE_BYTES * 1.4) return null;
  if (/^data:image\/(png|jpeg|gif|webp|avif|bmp|x-icon);base64,[a-z0-9+/=\s]+$/i.test(value)) return { kind: "local", url: value };
  if (ownedBlobs.has(value)) return { kind: "local", url: value };
  if (allowLocal && /^(?:\.\/|\/)?(?:assets\/)?[a-z0-9_./-]+\.(?:png|jpe?g|gif|webp|avif|svg)$/i.test(value) && !value.includes("..") && !value.startsWith("//")) return { kind: "local", url: value };
  try {
    const url = new URL(value, `${SERVER_URL}/`);
    if (url.origin !== SERVER_URL || url.username || url.password || url.hash || !/^\/proxy\/[a-z0-9_=-]+\/[a-z0-9_=-]+$/i.test(url.pathname) || url.search) return null;
    return { kind: "proxy", url: url.href };
  } catch { return null; }
}

function drainQueue() {
  while (inFlight < MAX_IN_FLIGHT && queue.length) {
    const start = queue.shift();
    inFlight += 1;
    start().finally(() => { inFlight -= 1; drainQueue(); });
  }
}

// Only signed Miniflux proxy paths are fetched. Credentials are never attached.
export function acquireImage(value, allowLocal = false) {
  const approved = approvedImageSource(value, allowLocal);
  if (!approved) throw new Error("图片未通过服务器代理。请在 Miniflux 开启 MEDIA_PROXY_MODE=all，并重新抓取文章。");
  if (approved.kind === "local") return { promise: Promise.resolve(approved.url), release() {} };
  let entry = activeImages.get(approved.url);
  if (!entry) {
    if (activeImages.size >= MAX_IMAGES) throw new Error("当前显示的图片较多，请滚动后重试。");
    entry = { references: 0, blobUrl: null, done: false };
    activeImages.set(approved.url, entry);
    entry.promise = new Promise((resolve, reject) => {
      queue.push(async () => {
        try {
          if (!entry.references) throw new Error("图片加载已取消。");
          const response = await request(approved.url, { maxResponseBytes: MAX_IMAGE_BYTES, headers: { Accept: "image/png,image/jpeg,image/webp,image/gif,image/avif,image/bmp" } });
          if (response.status < 200 || response.status >= 300) throw new Error(`图片加载失败（HTTP ${response.status}）。`);
          const mime = Object.entries(response.headers || {}).find(([key]) => key.toLowerCase() === "content-type")?.[1]?.split(";")[0]?.trim();
          if (!RASTER_MIME.test(mime || "")) throw new Error("服务器未返回受支持的图片格式。");
          if (response.bodyEncoding !== "base64" || typeof response.body !== "string" || response.body.length > Math.ceil(MAX_IMAGE_BYTES / 3) * 4) throw new Error("图片内容无效或超过 2 MiB。");
          const bytes = Uint8Array.from(atob(response.body), (char) => char.charCodeAt(0));
          if (!bytes.length || bytes.length > MAX_IMAGE_BYTES) throw new Error("图片内容无效或超过 2 MiB。");
          if (!entry.references) throw new Error("图片加载已取消。");
          entry.blobUrl = URL.createObjectURL(new Blob([bytes], { type: mime }));
          ownedBlobs.add(entry.blobUrl);
          resolve(entry.blobUrl);
        } catch (error) {
          // A failed shared entry must not pin every copy to the same rejection.
          if (activeImages.get(approved.url) === entry) activeImages.delete(approved.url);
          reject(error);
        }
        finally {
          entry.done = true;
          if (!entry.references && activeImages.get(approved.url) === entry) activeImages.delete(approved.url);
        }
      });
    });
  }
  entry.references += 1;
  drainQueue();
  let released = false;
  return {
    promise: entry.promise,
    release() {
      if (released) return;
      released = true;
      entry.references -= 1;
      if (!entry.references) {
        if (entry.blobUrl) { ownedBlobs.delete(entry.blobUrl); URL.revokeObjectURL(entry.blobUrl); }
        if (entry.done && activeImages.get(approved.url) === entry) activeImages.delete(approved.url);
      }
    },
  };
}

export function useSafeImage(src, allowLocal = false) {
  const containerRef = useRef(null);
  const [visible, setVisible] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState({ source: null, url: null, error: null });
  useEffect(() => {
    const element = containerRef.current;
    if (!element || typeof IntersectionObserver === "undefined") { setVisible(true); return; }
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), { rootMargin: "400px" });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!visible || !src) return;
    setState({ source: src, url: null, error: null });
    let mounted = true;
    let handle;
    try {
      handle = acquireImage(src, allowLocal);
      handle.promise.then(
        (url) => mounted && setState({ source: src, url, error: null }),
        (error) => mounted && setState({ source: src, url: null, error: error.message }),
      );
    } catch (error) { setState({ source: src, url: null, error: error.message }); }
    return () => { mounted = false; handle?.release(); };
  }, [src, visible, allowLocal, attempt]);
  const retry = useCallback(() => {
    setState({ source: src, url: null, error: null });
    setAttempt((value) => value + 1);
  }, [src]);
  return { containerRef, retry, url: visible && state.source === src && (!state.url?.startsWith("blob:") || ownedBlobs.has(state.url)) ? state.url : null, error: state.source === src ? state.error : null };
}

const MAX_MEDIA_BYTES = 4 * 1024 * 1024;
let activeMedia = 0;
export async function loadProxyMedia(value, kind) {
  const approved = approvedImageSource(value);
  if (approved?.kind !== "proxy" || !["audio", "video"].includes(kind)) throw new Error("该媒体未通过 Miniflux 代理，请复制或分享链接后播放。");
  if (activeMedia >= 2) throw new Error("请先关闭已加载的音视频，再加载其他媒体。");
  activeMedia += 1;
  try {
    const response = await request(approved.url, { maxResponseBytes: MAX_MEDIA_BYTES, headers: { Accept: kind === "audio" ? "audio/*" : "video/*" } });
    if (response.status < 200 || response.status >= 300) throw new Error("媒体加载失败，请复制或分享链接后播放。");
    const mime = Object.entries(response.headers || {}).find(([key]) => key.toLowerCase() === "content-type")?.[1]?.split(";")[0]?.trim()?.toLowerCase();
    const accepted = kind === "audio" ? /^audio\/(mpeg|mp4|ogg|wav|x-wav|aac|webm|flac)$/ : /^video\/(mp4|webm|ogg|quicktime)$/;
    if (!accepted.test(mime || "")) throw new Error("服务器未返回支持的音视频格式，请使用链接播放。");
    if (response.bodyEncoding !== "base64" || typeof response.body !== "string" || response.body.length > Math.ceil(MAX_MEDIA_BYTES / 3) * 4) throw new Error("媒体超过 4 MiB 或内容无效，请使用链接播放。");
    const bytes = Uint8Array.from(atob(response.body), (char) => char.charCodeAt(0));
    if (!bytes.length || bytes.length > MAX_MEDIA_BYTES) throw new Error("媒体超过 4 MiB 或内容无效，请使用链接播放。");
    const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
    let released = false;
    return { url, release() { if (!released) { released = true; URL.revokeObjectURL(url); activeMedia -= 1; } } };
  } catch (error) {
    activeMedia -= 1;
    if (error?.code === "QUOTA_EXCEEDED") throw new Error("媒体超过 4 MiB，请复制或分享链接后播放。");
    throw error;
  }
}
