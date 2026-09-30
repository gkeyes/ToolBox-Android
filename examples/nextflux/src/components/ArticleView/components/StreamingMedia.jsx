import { useEffect, useRef } from "react";
import { attachProxyHlsMedia } from "@/toolbox/media.js";

export default function StreamingMedia({ source, kind, onReady, onFailure }) {
  const elementRef = useRef(null);
  const callbacks = useRef({ onReady, onFailure });
  callbacks.current = { onReady, onFailure };
  useEffect(() => {
    let mounted = true, handle = null;
    const controller = new AbortController();
    attachProxyHlsMedia(elementRef.current, source, {
      signal: controller.signal,
      onReady: () => { if (mounted) callbacks.current.onReady(); },
      onError: (error) => { if (mounted) callbacks.current.onFailure(error); },
    }).then((session) => { if (mounted) handle = session; else session.release(); })
      .catch((error) => { if (mounted) callbacks.current.onFailure(error); });
    return () => { mounted = false; controller.abort(); handle?.release(); };
  }, [source, kind]);
  return kind === "audio"
    ? <audio ref={elementRef} controls preload="none" className="w-full" />
    : <video ref={elementRef} controls playsInline preload="none" className="w-full" />;
}
