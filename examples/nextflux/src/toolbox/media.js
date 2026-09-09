import { useCallback, useEffect, useRef, useState } from "react";
import { request, SERVER_URL } from "./network.js";
import { createImageCache } from "./imageCache.js";

const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
const RASTER_MIME = /^image\/(?:png|jpeg|gif|webp|avif|bmp|x-icon|vnd\.microsoft\.icon)$/i;
const images = createImageCache({
  async load(url, check) {
    const response = await request(url, { maxResponseBytes: MAX_IMAGE_BYTES, headers: { Accept: "image/png,image/jpeg,image/webp,image/gif,image/avif,image/bmp" } });
    check();
    if (response.status < 200 || response.status >= 300) throw new Error(`图片加载失败（HTTP ${response.status}）。`);
    const mime = Object.entries(response.headers || {}).find(([key]) => key.toLowerCase() === "content-type")?.[1]?.split(";")[0]?.trim();
    if (!RASTER_MIME.test(mime || "")) throw new Error("服务器未返回受支持的图片格式。");
    if (response.bodyEncoding !== "base64" || typeof response.body !== "string" || response.body.length > Math.ceil(MAX_IMAGE_BYTES / 3) * 4) throw new Error("图片内容无效或超过 2 MiB。");
    const bytes = Uint8Array.from(atob(response.body), (char) => char.charCodeAt(0));
    if (!bytes.length || bytes.length > MAX_IMAGE_BYTES) throw new Error("图片内容无效或超过 2 MiB。");
    return new Blob([bytes], { type: mime });
  },
});

export function approvedImageSource(value, allowLocal = false) {
  if (typeof value !== "string" || !value || value.length > MAX_IMAGE_BYTES * 1.4) return null;
  if (/^data:image\/(png|jpeg|gif|webp|avif|bmp|x-icon);base64,[a-z0-9+/=\s]+$/i.test(value)) return { kind: "local", url: value };
  if (images.hasBlob(value)) return { kind: "local", url: value };
  if (allowLocal && /^(?:\.\/|\/)?(?:assets\/)?[a-z0-9_./-]+\.(?:png|jpe?g|gif|webp|avif|svg)$/i.test(value) && !value.includes("..") && !value.startsWith("//")) return { kind: "local", url: value };
  try {
    const url = new URL(value, `${SERVER_URL}/`);
    if (url.origin !== SERVER_URL || url.username || url.password || url.hash || !/^\/proxy\/[a-z0-9_=-]+\/[a-z0-9_=-]+$/i.test(url.pathname) || url.search) return null;
    return { kind: "proxy", url: url.href };
  } catch { return null; }
}

// Only signed Miniflux proxy paths are fetched. Credentials are never attached.
export function acquireImage(value, allowLocal = false) {
  const approved = approvedImageSource(value, allowLocal);
  if (!approved) throw new Error("图片未通过服务器代理。请在 Miniflux 开启 MEDIA_PROXY_MODE=all，并重新抓取文章。");
  if (approved.kind === "local" && !images.hasBlob(approved.url)) {
    return { url: approved.url, promise: Promise.resolve(approved.url), release() {} };
  }
  return images.acquire(approved.url);
}

export function useSafeImage(src, allowLocal = false, keepAlive = false) {
  const containerRef = useRef(null);
  const leaseRef = useRef(null);
  const [visible, setVisible] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState({ source: null, url: null, error: null });
  // Opening a gallery retains mounted images, without fetching unseen images.
  const active = visible || (keepAlive && leaseRef.current?.source === src);
  useEffect(() => {
    const element = containerRef.current;
    if (!element || typeof IntersectionObserver === "undefined") { setVisible(true); return; }
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), { rootMargin: "400px" });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!active || !src) return;
    let mounted = true;
    let handle;
    try {
      handle = acquireImage(src, allowLocal);
      leaseRef.current = { source: src, handle };
      setState({ source: src, url: handle.url, error: null });
      handle.promise.then(
        (url) => mounted && setState({ source: src, url, error: null }),
        (error) => mounted && setState({ source: src, url: null, error: error.message }),
      );
    } catch (error) { setState({ source: src, url: null, error: error.message }); }
    return () => {
      mounted = false;
      if (leaseRef.current?.handle === handle) leaseRef.current = null;
      handle?.release();
    };
  }, [src, active, allowLocal, attempt]);
  const retry = useCallback(() => {
    leaseRef.current?.handle.invalidate?.();
    setState({ source: src, url: null, error: null });
    setAttempt((value) => value + 1);
  }, [src]);
  return { containerRef, retry, url: active && state.source === src && (!state.url?.startsWith("blob:") || images.hasBlob(state.url)) ? state.url : null, error: state.source === src ? state.error : null };
}

const MAX_MEDIA_BYTES = 4 * 1024 * 1024;
const activeMedia = new Set();
let mediaEpoch = 0;

export function clearMediaCache() {
  images.clear();
  mediaEpoch += 1;
  for (const media of activeMedia) media.release?.();
}

export async function loadProxyMedia(value, kind) {
  const approved = approvedImageSource(value);
  if (approved?.kind !== "proxy" || !["audio", "video"].includes(kind)) throw new Error("该媒体未通过 Miniflux 代理，请复制或分享链接后播放。");
  if (activeMedia.size >= 2) throw new Error("请先关闭已加载的音视频，再加载其他媒体。");
  const media = { epoch: mediaEpoch };
  activeMedia.add(media);
  try {
    const response = await request(approved.url, { maxResponseBytes: MAX_MEDIA_BYTES, headers: { Accept: kind === "audio" ? "audio/*" : "video/*" } });
    if (media.epoch !== mediaEpoch) throw Object.assign(new Error("媒体加载已取消。"), { code: "CANCELLED" });
    if (response.status < 200 || response.status >= 300) throw new Error("媒体加载失败，请复制或分享链接后播放。");
    const mime = Object.entries(response.headers || {}).find(([key]) => key.toLowerCase() === "content-type")?.[1]?.split(";")[0]?.trim()?.toLowerCase();
    const accepted = kind === "audio" ? /^audio\/(mpeg|mp4|ogg|wav|x-wav|aac|webm|flac)$/ : /^video\/(mp4|webm|ogg|quicktime)$/;
    if (!accepted.test(mime || "")) throw new Error("服务器未返回支持的音视频格式，请使用链接播放。");
    if (response.bodyEncoding !== "base64" || typeof response.body !== "string" || response.body.length > Math.ceil(MAX_MEDIA_BYTES / 3) * 4) throw new Error("媒体超过 4 MiB 或内容无效，请使用链接播放。");
    const bytes = Uint8Array.from(atob(response.body), (char) => char.charCodeAt(0));
    if (!bytes.length || bytes.length > MAX_MEDIA_BYTES) throw new Error("媒体超过 4 MiB 或内容无效，请使用链接播放。");
    const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
    let released = false;
    media.release = () => { if (!released) { released = true; URL.revokeObjectURL(url); activeMedia.delete(media); } };
    return { url, release: media.release };
  } catch (error) {
    activeMedia.delete(media);
    if (error?.code === "QUOTA_EXCEEDED") throw new Error("媒体超过 4 MiB，请复制或分享链接后播放。");
    throw error;
  }
}
