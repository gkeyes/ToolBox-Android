import { useState, useEffect } from "react";
import {
  FeedIcon as Rss,
} from "@/components/ui/icons.jsx";
import { settingsState } from "@/stores/settingsStore";
import { useStore } from "@nanostores/react";
import { cn } from "@/lib/utils";
import { loadAccountFeedIcon } from "@/stores/syncStore.js";

const FeedIcon = ({ feedId }) => {
  const { feedIconShape, useGrayIcon } = useStore(settingsState);
  const [error, setError] = useState(false);
  const [iconData, setIconData] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setError(false);
    setIsLoading(true);
    setIconData(null);
    const loadIcon = async () => {
      try {
        if (feedId) {
          const icon = await loadAccountFeedIcon(feedId);
          if (cancelled) return;

          if (icon) {
            if (!/^image\/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+$/.test(icon.data)) {
              setError(true);
              return;
            }
            setIconData(`data:${icon.data}`);
            return;
          }
        }

        setError(true);
      } catch (err) {
        if (!cancelled && err.code !== "ACCOUNT_CHANGED") setError(true);
      }
    };

    loadIcon();
    return () => { cancelled = true; };
  }, [feedId]);

  // 处理图片加载错误
  const handleError = () => {
    setError(true);
  };

  // Invalid data or a native decode failure keeps the RSS fallback.
  if (error) {
    return (
      <span
        className={cn(
          "feed-icon-fallback flex items-center shrink-0 justify-center w-5 h-5 p-0.5",
          feedIconShape === "circle" ? "rounded-full" : "rounded-[6px]",
        )}
      >
        <Rss className="size-3.5" />
      </span>
    );
  }

  return (
    <img
      alt="Feed icon"
      src={iconData}
      className={cn(
        "size-5 shrink-0 p-0.5 bg-white shadow-custom transition-opacity duration-150 ease-out",
        useGrayIcon ? "grayscale" : "",
        feedIconShape === "circle" ? "rounded-full" : "rounded-[6px]",
        isLoading && "opacity-0",
      )}
      onError={handleError}
      onLoad={() => setIsLoading(false)}
    />
  );
};

export default FeedIcon;
