import { PhotoView } from "react-photo-view";
import { memo, useState } from "react";
import { ImageOff } from "lucide-react";
import { cn } from "@/lib/utils.js";
import { approvedImageSource, useSafeImage } from "@/toolbox/media.js";
import { useStore } from "@nanostores/react";
import { imageGalleryActive } from "@/stores/articlesStore.js";

function ArticleImage({ imgNode, type = "article" }) {
  const { src, "data-image-source": sanitizedSource, alt = "" } = imgNode.attribs;
  const source = sanitizedSource || src;
  const galleryOpen = useStore(imageGalleryActive);
  const { containerRef, url, error, retry } = useSafeImage(source, false, galleryOpen);
  const [dimensions, setDimensions] = useState(null);
  const [failedUrl, setFailedUrl] = useState(null);
  const failure = error || (failedUrl && failedUrl === url ? "图片解码失败，暂时无法显示。" : null);
  const measured = dimensions?.source === source ? dimensions : null;
  const imageStyle = measured ? { width: measured.width, aspectRatio: `${measured.width} / ${measured.height}` } : undefined;
  return (
    <div ref={containerRef} className={cn("flex justify-center my-2 min-h-12", type === "article" ? "max-w-[calc(100%+2.5rem)]! -mx-5" : "rounded-lg shadow-custom! mx-auto overflow-hidden w-fit")}>
      {failure || !source ? (
        <div className="flex flex-col items-center gap-2 text-muted p-5 text-center" role="status">
          <ImageOff className="size-5" />
          <span className="text-sm">{failure || "没有可安全显示的图片地址。"}</span>
          {alt && <span className="text-xs">{alt}</span>}
          {approvedImageSource(source)?.kind === "proxy" && <button type="button" className="text-sm text-accent min-h-12 px-4" onClick={(event) => { event.preventDefault(); event.stopPropagation(); setFailedUrl(null); retry(); }}>重试图片</button>}
        </div>
      ) : (
        <div className={cn("max-w-full overflow-hidden", !measured && "w-full")} style={imageStyle}>
          {url ? (
            <PhotoView key={url} src={url}>
              <img
                src={url}
                alt={alt}
                decoding="async"
                className="h-auto object-contain my-0 mx-auto max-w-full"
                onLoad={(event) => {
                  const { naturalWidth: width, naturalHeight: height } = event.currentTarget;
                  if (width > 0 && height > 0) {
                    setDimensions((previous) => previous?.source === source && previous.width === width && previous.height === height
                      ? previous : { source, width, height });
                  }
                }}
                onError={() => setFailedUrl(url)}
              />
            </PhotoView>
          ) : <div role="status" className={cn("bg-default w-full rounded-lg text-muted text-xs flex items-center justify-center", measured ? "h-full" : "min-h-12 p-4")}>图片加载中…</div>}
        </div>
      )}
    </div>
  );
}

export default memo(ArticleImage);
