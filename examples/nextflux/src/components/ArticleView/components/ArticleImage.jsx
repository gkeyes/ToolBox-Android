import { PhotoView } from "react-photo-view";
import { memo, useState } from "react";
import { ImageOff } from "lucide-react";
import { cn } from "@/lib/utils.js";
import { approvedImageSource, useSafeImage } from "@/toolbox/media.js";

function ArticleImage({ imgNode, type = "article" }) {
  const { src, "data-image-source": sanitizedSource, alt = "" } = imgNode.attribs;
  const source = sanitizedSource || src;
  const { containerRef, url, error, retry } = useSafeImage(source);
  const [failedUrl, setFailedUrl] = useState(null);
  const failure = error || (failedUrl && failedUrl === url ? "图片解码失败，暂时无法显示。" : null);
  return (
    <div ref={containerRef} className={cn("flex justify-center my-2 min-h-12", type === "article" ? "max-w-[calc(100%+2.5rem)]! -mx-5" : "rounded-lg shadow-custom! mx-auto overflow-hidden w-fit")}>
      {failure || !source ? (
        <div className="flex flex-col items-center gap-2 text-muted p-5 text-center" role="status">
          <ImageOff className="size-5" />
          <span className="text-sm">{failure || "没有可安全显示的图片地址。"}</span>
          {alt && <span className="text-xs">{alt}</span>}
          {approvedImageSource(source)?.kind === "proxy" && <button type="button" className="text-sm text-accent min-h-12 px-4" onClick={(event) => { event.preventDefault(); event.stopPropagation(); setFailedUrl(null); retry(); }}>重试图片</button>}
        </div>
      ) : url ? (
        <PhotoView key={url} src={url}>
          <img src={url} alt={alt} className="h-auto object-contain m-0 max-w-full" onError={() => setFailedUrl(url)} />
        </PhotoView>
      ) : <div role="status" className="bg-default min-h-12 w-full rounded-lg text-muted text-xs text-center p-4">图片加载中…</div>}
    </div>
  );
}

export default memo(ArticleImage);
