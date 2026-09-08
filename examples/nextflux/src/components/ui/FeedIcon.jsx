import { useState, useEffect } from "react";
import { Rss } from "lucide-react";
import { settingsState } from "@/stores/settingsStore";
import { useStore } from "@nanostores/react";
import { cn } from "@/lib/utils";
import { loadAccountFeedIcon } from "@/stores/syncStore.js";

const FeedIcon = ({ feedId }) => {
  const { feedIconShape, useGrayIcon } = useStore(settingsState);
  const [error, setError] = useState(false);
  const [isBlurry, setIsBlurry] = useState(false);
  const [iconData, setIconData] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setError(false);
    setIsBlurry(false);
    setIsLoading(true);
    setIconData(null);
    const loadIcon = async () => {
      try {
        if (feedId) {
          const icon = await loadAccountFeedIcon(feedId);
          if (cancelled) return;

          if (icon) {
            if (!/^image\/(png|jpeg|gif|webp|avif|x-icon|vnd.microsoft.icon);base64,[A-Za-z0-9+/=\s]+$/.test(icon.data)) {
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

  // 检查图片质量
  const handleLoad = (e) => {
    const img = e.target;
    // 如果图片实际尺寸小于预期尺寸(32x32)，认为图片质量不佳
    if (img.naturalWidth < 32 || img.naturalHeight < 32) {
      setIsBlurry(true);
    }
  };

  // 如果URL无效、图片加载失败或图片模糊，显示默认图标
  if (error || isBlurry) {
    return (
      <span
        className={cn(
          "flex items-center shrink-0 justify-center w-5 h-5 p-0.5 bg-white transition-opacity duration-300 ease-in-out animate-in fade-in shadow-custom",
          feedIconShape === "circle" ? "rounded-full" : "rounded-sm",
        )}
      >
        <Rss strokeWidth={3} className="size-3 text-black/60" />
      </span>
    );
  }

  return (
    <img
      alt="Feed icon"
      src={iconData}
      className={cn(
        "size-5 p-0.5 bg-white shadow-custom transition-opacity duration-300 ease-in-out animate-in fade-in",
        useGrayIcon ? "grayscale" : "",
        feedIconShape === "circle" ? "rounded-full" : "rounded-sm",
        isLoading && "opacity-0",
      )}
      onError={handleError}
      onLoad={(e) => {
        setIsLoading(false);
        handleLoad(e);
      }}
    />
  );
};

export default FeedIcon;
