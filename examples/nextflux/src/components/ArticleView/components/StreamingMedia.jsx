import { useEffect, useRef } from "react";
import { attachProxyHlsMedia } from "@/toolbox/media.js";

// The ToolBox runtime WebView has no custom-view (fullscreen) host. Entering
// fullscreen there hands the page to a view that is never shown, and the
// reader stops receiving input. Keep media inline: no fullscreen, picture-in-
// picture or cast entries in the native controls. Set as DOM attributes so no
// React version needs to know these properties.
export function keepMediaInline(element) {
  if (!element) return;
  element.setAttribute("controlslist", "nofullscreen noremoteplayback nodownload");
  element.setAttribute("disablepictureinpicture", "");
  element.setAttribute("disableremoteplayback", "");
}

export default function StreamingMedia({ source, kind, onReady, onFailure }) {
  const elementRef = useRef(null);
  const callbacks = useRef({ onReady, onFailure });
  callbacks.current = { onReady, onFailure };
  useEffect(() => {
    keepMediaInline(elementRef.current);
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
