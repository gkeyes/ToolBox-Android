import { useState } from "react";
import { cn } from "@/lib/utils.js";
import { settingsState } from "@/stores/settingsStore";
import { useStore } from "@nanostores/react";
import { ImageOff } from "lucide-react";
import { memo } from "react";
import { useSafeImage } from "@/toolbox/media.js";

function ArticleCardCover({ imageUrl, imageSources }) {
  const sources = imageSources?.length ? imageSources : imageUrl;
  const { containerRef, url, error, onError, retry } = useSafeImage(sources);
  const [loadedUrl, setLoadedUrl] = useState(null);
  const loading = !url || loadedUrl !== url;
  const { cardImageSize } = useStore(settingsState);

  if (!imageUrl && !imageSources?.length) {
    return null;
  }

  if (error) {
    return (
      <div
        ref={containerRef}
        className={cn(
          "card-image bg-default rounded-lg shadow-custom overflow-hidden",
          cardImageSize === "large"
            ? "aspect-video w-full"
            : "w-20 h-20 shrink-0",
        )}
      >
        <div className="flex flex-col items-center justify-center h-full gap-2 text-muted">
          <ImageOff className="size-5 text-muted" />
          <button type="button" className="text-xs text-accent min-h-12 px-3" onClick={(event) => { event.preventDefault(); event.stopPropagation(); retry(); }}>重试图片</button>
        </div>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className={cn(
        "card-image bg-default rounded-lg shadow-custom overflow-hidden",
        loading && "animate-pulse!",
        cardImageSize === "large"
          ? "aspect-video w-full"
          : "w-20 h-20 shrink-0",
      )}
    >
      {url && <img
        key={url}
        alt=""
        src={url}
        onLoad={() => setLoadedUrl(url)}
        onError={() => onError(url)}
        loading="eager"
        decoding="async"
        className={cn(
          "object-cover",
          cardImageSize === "large"
            ? "aspect-video w-full"
            : "aspect-square w-20",
        )}
      />}
    </div>
  );
}

const arePropsEqual = (prevProps, nextProps) => {
  return prevProps.imageUrl === nextProps.imageUrl && JSON.stringify(prevProps.imageSources) === JSON.stringify(nextProps.imageSources);
};

export default memo(ArticleCardCover, arePropsEqual);
