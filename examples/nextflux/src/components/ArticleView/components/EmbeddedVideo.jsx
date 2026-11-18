import { useEffect, useRef } from "react";

export default function EmbeddedVideo({ source, onReady, onFailure, onClose }) {
  const elementRef = useRef(null);
  const callbacks = useRef({ onReady, onFailure, onClose });
  callbacks.current = { onReady, onFailure, onClose };
  useEffect(() => {
    const controller = new AbortController();
    let active = true, handle = null;
    const closeHandle = (player) => { if (player?.close) void Promise.resolve().then(() => player.close()).catch(() => {}); };
    const release = () => {
      controller.abort();
      closeHandle(handle);
      handle = null;
    };
    const stop = () => {
      if (!active) return;
      active = false;
      release();
      callbacks.current.onClose();
    };
    const visibilityChanged = () => { if (document.hidden) stop(); };
    document.addEventListener("visibilitychange", visibilityChanged);
    window.addEventListener("pagehide", stop);
    void (async () => {
      try {
        if (document.hidden) { stop(); return; }
        const player = await window.ToolBox.browser.openEmbedded(source, elementRef.current, { signal: controller.signal });
        if (!active || controller.signal.aborted) { closeHandle(player); return; }
        if (!player || typeof player.close !== "function") throw new Error("Invalid player session");
        handle = player;
        if (player.closed && typeof player.closed.then === "function") void player.closed.then(stop).catch(() => {});
        callbacks.current.onReady();
      } catch (error) {
        if (!active || controller.signal.aborted) return;
        if (error?.code === "CANCELLED" || error?.name === "AbortError") { stop(); return; }
        callbacks.current.onFailure(error);
      }
    })();
    return () => {
      active = false;
      document.removeEventListener("visibilitychange", visibilityChanged);
      window.removeEventListener("pagehide", stop);
      release();
    };
  }, [source]);
  return <div ref={elementRef} className="article-video-player article-video-embedded-player" role="group" aria-label="YouTube 视频播放器" />;
}
