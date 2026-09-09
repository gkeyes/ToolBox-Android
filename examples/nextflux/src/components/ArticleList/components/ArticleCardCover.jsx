import { useRef, useState } from "react";
import { cn } from "@/lib/utils.js";
import { settingsState } from "@/stores/settingsStore";
import { useStore } from "@nanostores/react";
import { ImageOff } from "lucide-react";
import { memo } from "react";
import { Image } from "@/components/ui/Image.jsx";

function ArticleCardCover({ imageUrl }) {
  const [state, setState] = useState({ source: imageUrl, loaded: false, error: false });
  const error = state.source === imageUrl && state.error;
  const loading = state.source !== imageUrl || !state.loaded;
  const imgRef = useRef(null);
  const { cardImageSize } = useStore(settingsState);

  if (!imageUrl) {
    return null;
  }

  if (error) {
    return (
      <div
        className={cn(
          "card-image bg-default rounded-lg shadow-custom overflow-hidden",
          cardImageSize === "large"
            ? "aspect-video w-full"
            : "w-20 h-20 shrink-0",
        )}
      >
        <div className="flex flex-col items-center justify-center h-full gap-2 text-muted">
          <ImageOff className="size-5 text-muted" />
        </div>
      </div>
    );
  }

  return (
    <div
      ref={imgRef}
      className={cn(
        "card-image bg-default rounded-lg shadow-custom overflow-hidden",
        loading && "animate-pulse!",
        cardImageSize === "large"
          ? "aspect-video w-full"
          : "w-20 h-20 shrink-0",
      )}
    >
      <Image
        key={imageUrl}
        alt=""
        src={imageUrl}
        onLoad={() => setState({ source: imageUrl, loaded: true, error: false })}
        onError={() => setState({ source: imageUrl, loaded: false, error: true })}
        loading="eager"
        decoding="async"
        className={cn(
          "object-cover",
          cardImageSize === "large"
            ? "aspect-video w-full"
            : "aspect-square w-20",
        )}
      />
    </div>
  );
}

const arePropsEqual = (prevProps, nextProps) => {
  return prevProps.imageUrl === nextProps.imageUrl;
};

export default memo(ArticleCardCover, arePropsEqual);
