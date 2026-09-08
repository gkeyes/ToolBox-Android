import { Inbox, Search } from "lucide-react";
import FeedIcon from "@/components/ui/FeedIcon.jsx";
import { formatDate } from "@/lib/format.js";
import { useEffect, useRef, useState } from "react";
import { Virtuoso } from "react-virtuoso";
import { useTranslation } from "react-i18next";
import { searching } from "@/stores/searchStore.js";
import { useStore } from "@nanostores/react";
import { ProgressCircle } from "@heroui/react";

export default function SearchResults({
  results,
  keyword,
  onSelect,
  type = "articles",
  isComposing,
}) {
  const { t } = useTranslation();
  const [selectedIndex, setSelectedIndex] = useState(-1);
  const [hoverEffect, setHoverEffect] = useState(true);
  const listRef = useRef(null);
  const $searching = useStore(searching);

  // 处理键盘事件
  useEffect(() => {
    const handleKeyDown = (e) => {
      if (results.length === 0) return;

      switch (e.key) {
        case "ArrowDown":
          e.preventDefault();
          setSelectedIndex((prev) =>
            prev < results.length - 1 ? prev + 1 : prev,
          );
          setHoverEffect(false);
          break;
        case "ArrowUp":
          e.preventDefault();
          setSelectedIndex((prev) => (prev > 0 ? prev - 1 : prev));
          setHoverEffect(false);
          break;
        case "Enter":
          // 检查是否正在进行中文输入
          if (e.isComposing) {
            return;
          }

          e.preventDefault();
          if (selectedIndex >= 0) {
            onSelect(results[selectedIndex]);
          }
          break;
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [results, selectedIndex, onSelect]);

  // 确保选中项在视野内
  useEffect(() => {
    if (selectedIndex >= 0 && listRef.current) {
      listRef.current.scrollIntoView({
        index: selectedIndex,
        behavior: "instant",
      });
    }
  }, [selectedIndex]);

  // 重置搜索时重置选中项
  useEffect(() => {
    setSelectedIndex(0);
  }, [keyword]);

  if (!keyword) {
    return (
      <div className="flex flex-col items-center gap-2 w-full justify-center h-full text-muted opacity-60">
        <Inbox className="size-16" />
        {t("search.searchPlaceholder")}
      </div>
    );
  }

  if ($searching) {
    return (
      <div className="flex flex-col items-center gap-2 w-full justify-center h-full text-muted opacity-60">
        <ProgressCircle aria-label="loading" />
      </div>
    );
  }

  if (!isComposing && results.length === 0 && !$searching) {
    return (
      <div className="flex flex-col items-center gap-2 w-full justify-center h-full text-muted opacity-60">
        <Search className="size-16" />
        {t("search.searchResultsPlaceholder")}
      </div>
    );
  }

  return (
    <Virtuoso
      ref={listRef}
      className="h-full mx-2"
      totalCount={results.length}
      data={results}
      components={{
        Header: () => <div className="header h-2"></div>,
        Footer: () => <div className="footer h-2"></div>,
      }}
      itemContent={(index, item) => (
        <div
          key={item.id}
          className={`flex items-center justify-between gap-2 px-2 py-2 text-sm rounded-lg cursor-pointer ${
            index === selectedIndex
              ? "bg-default/80"
              : hoverEffect
                ? "hover:bg-default/60"
                : ""
          }`}
          onClick={() => onSelect(item)}
          onMouseMove={() => setHoverEffect(true)}
        >
          <div className="flex items-center gap-2">
            <FeedIcon feedId={type === "articles" ? item.feedId : item.id} />
            <div className="flex-1 line-clamp-1">{item.title}</div>
          </div>
          <div className="shrink-0 line-clamp-1 text-xs text-muted opacity-60 font-mono">
            {type === "articles" && formatDate(item.published_at)}
          </div>
        </div>
      )}
    />
  );
}
