import { useState } from "react";
import { cn } from "@/lib/utils.js";
import { settingsState } from "@/stores/settingsStore";
import { useStore } from "@nanostores/react";
import {
  ImageOffIcon as ImageOff,
} from "@/components/ui/icons.jsx";
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
          "card-image bg-default overflow-hidden",
          cardImageSize === "large"
            ? "aspect-video w-full rounded-[var(--ds-radius-control)] mt-1"
            : "w-14 h-14 shrink-0 self-center rounded-[var(--ds-radius-small)]",
        )}
      >
        <div className="flex flex-col items-center justify-center h-full gap-1 text-muted">
          <ImageOff className="size-4 text-muted" />
          <button type="button" className="text-[11px] leading-none text-accent min-h-8 px-1" onClick={(event) => { event.preventDefault(); event.stopPropagation(); retry(); }}>重试图片</button>
        </div>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className={cn(
        "card-image bg-default overflow-hidden",
        loading && "animate-pulse!",
        cardImageSize === "large"
          ? "aspect-video w-full rounded-[var(--ds-radius-control)] mt-1"
          : "w-14 h-14 shrink-0 self-center rounded-[var(--ds-radius-small)]",
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
            : "aspect-square w-14",
        )}
      />}
    </div>
  );
}

const arePropsEqual = (prevProps, nextProps) => {
  return prevProps.imageUrl === nextProps.imageUrl && JSON.stringify(prevProps.imageSources) === JSON.stringify(nextProps.imageSources);
};

export default memo(ArticleCardCover, arePropsEqual);
