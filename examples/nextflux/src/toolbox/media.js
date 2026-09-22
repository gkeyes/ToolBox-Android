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

const activeMedia = new Set();
let mediaEpoch = 0;

export function clearMediaCache() {
  imageDimensions.clear();
  images.clear();
  mediaEpoch += 1;
  for (const media of activeMedia) media.release();
}

export async function loadProxyMedia(value, kind) {
  const approved = approvedImageSource(value);
  if (approved?.kind !== "proxy" || !["audio", "video"].includes(kind)) throw new Error("媒体地址或类型无效，请使用 HTTPS 音视频地址。");
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
    });
    if (released || epoch !== mediaEpoch) {
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
