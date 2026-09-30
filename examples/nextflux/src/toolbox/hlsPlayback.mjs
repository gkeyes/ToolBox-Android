import Hls from "hls.js";
import { createNativeHlsLoader } from "./nativeHlsLoader.mjs";

export function attachHlsPlayback(element, url, { onReady, onError }) {
  if (!Hls.isSupported()) throw Object.assign(new Error("当前 WebView 不支持 HLS 分片播放。"), { code: "UNSUPPORTED" });
  const policy = { default: { maxTimeToFirstByteMs: 15000, maxLoadTimeMs: 60000, timeoutRetry: null, errorRetry: null } };
  const hls = new Hls({
    loader: createNativeHlsLoader(),
    // The runtime permits packaged Workers only; Hls's inline Blob Worker is
    // not allowed by its CSP. Media transmuxing stays within the local bundle.
    enableWorker: false,
    progressive: false,
    startLevel: 0,
    capLevelToPlayerSize: true,
    maxBufferLength: 15,
    maxMaxBufferLength: 30,
    backBufferLength: 10,
    maxBufferSize: 20 * 1024 * 1024,
    manifestLoadPolicy: policy, playlistLoadPolicy: policy,
    fragLoadPolicy: policy, keyLoadPolicy: policy,
  });
  let released = false, ready = false;
  hls.on(Hls.Events.FRAG_BUFFERED, () => {
    if (!released && !ready) { ready = true; onReady?.(); }
  });
  hls.on(Hls.Events.ERROR, (_, data) => {
    if (!released && data.fatal) {
      const nativeCode = data.networkDetails?.nativeCode;
      const message = nativeCode === "PERMISSION_DENIED" ? "请在小工具权限中开启网络访问。" : "流媒体加载失败，请重试或使用备用媒体源。";
      onError?.(Object.assign(new Error(message), { code: nativeCode || "NETWORK_UNAVAILABLE" }));
    }
  });
  hls.attachMedia(element);
  hls.loadSource(url);
  return {
    release() {
      if (released) return;
      released = true;
      hls.destroy();
      element.removeAttribute("src");
      element.load();
    },
  };
}
