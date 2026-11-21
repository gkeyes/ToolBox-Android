import { PhotoView } from "react-photo-view";
import { memo, useRef, useState } from "react";
import {
  ImageOffIcon as ImageOff,
} from "@/components/ui/icons.jsx";
import { cn } from "@/lib/utils.js";
import { approvedImageSource, shareableImageType, useSafeImage } from "@/toolbox/media.js";
import { useStore } from "@nanostores/react";
import { imageGalleryActive } from "@/stores/articlesStore.js";
import { imageDimensions, imageSize } from "@/toolbox/imageDimensions.js";
import { imageIsInline } from "@/reading/inlineImage.mjs";

const TAP_MAX_DURATION_MS = 500;

function ArticleImage({ imgNode, type = "article", inline: inlineHint = false }) {
  const attributes = imgNode?.attribs && typeof imgNode.attribs === "object" ? imgNode.attribs : {};
  const { src, "data-image-source": sanitizedSource, "data-image-candidates": candidatesMetadata, alt = "" } = attributes;
  let candidates;
  try { candidates = JSON.parse(candidatesMetadata); } catch { /* older article portals contain a single source */ }
  if (!Array.isArray(candidates)) candidates = [sanitizedSource || src].filter(Boolean);
  const primarySource = candidates[0];
  const galleryOpen = useStore(imageGalleryActive);
  const { containerRef, url, error, retry, onError, source: loadedSource } = useSafeImage(candidates, false, galleryOpen);
  const source = loadedSource || primarySource;
  const [dimensions, setDimensions] = useState(null);
  const tapStartedAt = useRef(null);
  const epoch = imageDimensions.epoch;
  const failure = error;
  const measured = imageSize(attributes.width, attributes.height)
    || (dimensions && dimensions.source === source && dimensions.epoch === epoch ? dimensions : null)
    || (source ? imageDimensions.get(source) : null);
  // An image of unknown size holds a 16:9 box until it loads, instead of a
  // 48px strip that pushed the paragraphs below it down by hundreds of px.
  // Not for an image inside running text (icons, emoji, badges): its box
  // collapsed to the icon and pulled the text below up by ~180px. See
  // reading/inlineImage.mjs for what counts as running text.
  const inline = inlineHint || imageIsInline(imgNode, attributes);
  const reserve = !measured && type === "article" && !inline;
  const imageStyle = measured ? { width: measured.width, aspectRatio: `${measured.width} / ${measured.height}` } : reserve ? { aspectRatio: "16 / 9" } : undefined;
  const imageLink = loadedSource && !loadedSource.startsWith("data:") && approvedImageSource(loadedSource);
  return (
    <div ref={containerRef} className={cn("flex justify-center my-2 min-h-12", type === "article" ? "max-w-[calc(100%+2.5rem)]! -mx-5" : "rounded-lg shadow-custom! mx-auto overflow-hidden w-fit")}>
      {failure || !source ? (
        <div className="flex flex-col items-center gap-2 text-muted p-5 text-center" role="status">
          <ImageOff className="size-5" />
          <span className="text-sm">{failure || "没有可安全显示的图片地址。"}</span>
          {alt && <span className="text-xs">{alt}</span>}
          {candidates.some((candidate) => approvedImageSource(candidate)?.kind === "proxy") && <button type="button" className="text-sm text-accent min-h-12 px-4" onClick={(event) => { event.preventDefault(); event.stopPropagation(); retry(); }}>重试图片</button>}
        </div>
      ) : (
        <div className={cn("max-w-full overflow-hidden", !measured && "w-full")} style={imageStyle}>
          {url ? (
            <PhotoView key={url} src={url}>
              <img
                src={url}
                alt={alt}
                decoding="async"
                tabIndex={type === "article" ? 0 : undefined}
                aria-label={type === "article" && !alt ? "文章图片" : undefined}
                aria-haspopup={type === "article" ? "dialog" : undefined}
                data-nextflux-image-share={type === "article" ? shareableImageType(url) : undefined}
                data-nextflux-image-source={type === "article" && imageLink?.kind === "proxy" ? imageLink.url : undefined}
                onPointerDown={type === "article" ? (event) => {
                  tapStartedAt.current = event.pointerType === "mouse" ? null : event.timeStamp;
                } : undefined}
                onPointerCancel={type === "article" ? () => { tapStartedAt.current = null; } : undefined}
                onClickCapture={type === "article" ? (event) => {
                  const startedAt = tapStartedAt.current;
                  tapStartedAt.current = null;
                  // WebView can emit a click on release even after a hold.
                  // Intercept it before PhotoView opens, while keeping keyboard clicks.
                  if (event.detail > 0 && startedAt !== null && event.timeStamp - startedAt >= TAP_MAX_DURATION_MS) {
                    event.preventDefault();
                    event.stopPropagation();
                  }
                } : undefined}
                onKeyDown={type === "article" ? (event) => {
                  if (event.key !== "Enter" && event.key !== " ") return;
                  event.preventDefault();
                  event.currentTarget.click();
                } : undefined}
                className="h-auto object-contain my-0 mx-auto max-w-full"
                style={measured ? { width: "100%", aspectRatio: `${measured.width} / ${measured.height}` } : undefined}
                onLoad={(event) => {
                  const { naturalWidth: width, naturalHeight: height } = event.currentTarget;
                  if (epoch === imageDimensions.epoch && imageSize(width, height)) {
                    imageDimensions.remember(source, width, height, epoch);
                    setDimensions((previous) => previous?.source === source && previous.epoch === epoch && previous.width === width && previous.height === height
                      ? previous : { source, width, height, epoch });
                  }
                }}
                onError={() => onError(url)}
              />
            </PhotoView>
          ) : <div role="status" className={cn("bg-default w-full rounded-lg text-muted text-xs flex items-center justify-center", measured || reserve ? "h-full" : "min-h-12 p-4")}>图片加载中…</div>}
        </div>
      )}
    </div>
  );
}

export default memo(ArticleImage);
