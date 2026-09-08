import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";
import { approvedImageSource, useSafeImage } from "@/toolbox/media.js";

export const Image = ({ src, alt, className, fallbackSrc, onLoad, onError, ...props }) => {
  const [failedSource, setFailedSource] = useState(null);
  const [failedUrl, setFailedUrl] = useState(null);
  const [loadedUrl, setLoadedUrl] = useState(null);
  const requestedSource = failedSource === src && fallbackSrc ? fallbackSrc : src;
  const { containerRef, url, error, retry } = useSafeImage(requestedSource, true);
  useEffect(() => {
    if (error && requestedSource === src && fallbackSrc) setFailedSource(src);
  }, [error, requestedSource, src, fallbackSrc]);
  const failure = error || (url && failedUrl === url ? "图片无法解码，请重试。" : null);
  return (
    <span ref={containerRef} className="relative inline-block min-h-5 min-w-5">
      {failure ? (
        <span className={cn("flex flex-col items-center justify-center gap-1", className)}>
          <span role="img" aria-label={alt || "图片无法显示"} title={failure}>{alt || "图片无法显示"}</span>
          {approvedImageSource(requestedSource, true)?.kind === "proxy" && <button type="button" className="text-xs text-accent min-h-12 px-3" onClick={(event) => { event.preventDefault(); event.stopPropagation(); setFailedSource(null); setFailedUrl(null); retry(); }}>重试图片</button>}
        </span>
      ) : url ? (
        <img
          {...props}
          src={url}
          alt={alt || ""}
          className={cn(loadedUrl !== url && "opacity-0", "transition-opacity duration-300", className)}
          onLoad={(event) => { setLoadedUrl(url); onLoad?.(event); }}
          onError={(event) => { setFailedUrl(url); if (requestedSource === src && fallbackSrc) setFailedSource(src); onError?.(event); }}
        />
      ) : <span className={cn("block bg-default min-h-5 min-w-5", className)} />}
    </span>
  );
};
