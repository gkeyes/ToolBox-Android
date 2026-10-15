import {
  Clock,
  ArrowUpFromLine,
} from "lucide-react";
import {
  StarIcon as Star,
  ReadStateEmptyIcon as Circle,
  ReadStateIcon as CircleDot,
} from "@/components/ui/icons.jsx";
import { cn } from "@/lib/utils";
import { formatPublishDate } from "@/lib/format";
import ArticleCardCover from "./ArticleCardCover.jsx";
import {
  handleMarkStatus,
  handleMarkRead,
  handleMarkAboveAsRead,
} from "@/handlers/articleHandlers.js";
import { useEffect, useRef, useState } from "react";
import { useStore } from "@nanostores/react";
import { settingsState } from "@/stores/settingsStore";
import FeedIcon from "@/components/ui/FeedIcon.jsx";
import { useTranslation } from "react-i18next";
import { ContextMenu, ContextMenuItem } from "@/components/ui/ContextMenu";

// Cards deliberately subscribe to no router or viewport state: opening or
// closing an article changes the route, and every mounted card re-rendering
// at that moment delays the page motion. The list passes `active` (only the
// two affected rows change) and a stable `onOpen` that owns navigation.
// feedTitle comes from the list's precomputed map, and mark-as-read uses the
// list's single IntersectionObserver (observeRead) instead of one per card.
export default function ArticleCard({ article, active = false, onOpen, feedTitle, observeRead }) {
  const { t } = useTranslation();
  const cardRef = useRef(null);
  const {
    markAsReadOnScroll,
    cardImageSize,
    showFavicon,
    showReadingTime,
    textPreviewLines,
    titleLines,
  } = useStore(settingsState);
  const [contextMenu, setContextMenu] = useState({
    isOpen: false,
    position: { x: 0, y: 0 },
  });

  const imageUrl = article.coverUrl;
  const imageSources = article.coverSources;

  const previewContent = article.previewText || "";

  useEffect(() => {
    // 如果文章已读或未启用滚动标记已读,则不需要观察
    if (article.status === "read" || !markAsReadOnScroll || !observeRead) return;
    return observeRead(cardRef.current, article);
  }, [article, markAsReadOnScroll, observeRead]);

  const handleArticleClick = async (article) => {
    onOpen?.(article, cardRef.current?.querySelector(".card-title"));
    if (article.status !== "read") {
      await handleMarkRead(article);
    }
  };

  const handleClick = () => {
    handleArticleClick(article);
  };

  const handleContextMenu = (e) => {
    e.preventDefault();
    setContextMenu({
      isOpen: true,
      position: { x: e.clientX, y: e.clientY, anchor: e.currentTarget },
    });
  };

  const closeContextMenu = () => {
    setContextMenu({ isOpen: false, position: { x: 0, y: 0 } });
  };

  return (
    <>
      <div
        ref={cardRef}
        className={cn(
          "article-card ds-state ds-press-row cursor-pointer select-none overflow-hidden",
          "relative contain-content",
          active && "is-active",
        )}
        data-article-id={article.id}
        data-read={article.status === "read" ? "true" : undefined}
        onClick={handleClick}
        onContextMenu={handleContextMenu}
      >
        <div
          className={cn(
            "card-content flex gap-3",
            article.status === "read" && article.starred === 0 && "is-read",
          )}
        >
          <div className="card-header flex flex-col gap-1 flex-1 min-w-0">
            <div className="card-meta flex items-center justify-between gap-2">
              <div className="card-source flex items-center flex-1 gap-1.5 min-w-0">
                {article.status !== "read" && <span className="card-unread-dot" aria-hidden="true" />}
                <div className="card-source-content flex gap-1.5 items-center min-w-0">
                  {showFavicon && <FeedIcon feedId={article.feedId} />}
                  <span className="card-source-title ds-caption font-medium line-clamp-1">
                    {feedTitle || article.feedId}
                  </span>
                </div>
              </div>
              <div className="card-time-wrapper flex items-center gap-1 ds-caption ds-text-tertiary ds-tnum">
                {article.starred === 1 && (
                  <span className="card-star text-warning">
                    <Star className="size-3 fill-current" />
                  </span>
                )}
                <span className="card-time whitespace-nowrap">
                  {formatPublishDate(article.published_at)}
                </span>
              </div>
            </div>
            <div className="card-content-body flex">
              <div className="flex flex-col gap-0.5 flex-1 min-w-0">
                <h3
                  data-shared-article-title={article.id}
                  className={cn(
                    "card-title ds-headline text-wrap break-words",
                    article.status === "read" && "is-read",
                  )}
                  style={{
                    wordBreak: "break-word",
                    overflow: "hidden",
                    display: "-webkit-box",
                    WebkitBoxOrient: "vertical",
                    WebkitLineClamp: titleLines === 0 ? "none" : titleLines,
                  }}
                >
                  {article.titleText ?? article.title}
                </h3>
                {textPreviewLines !== 0 && previewContent && (
                  <span
                    className="card-preview ds-subhead text-wrap break-words w-full max-w-full overflow-hidden"
                    style={{
                      wordBreak: "break-word",
                      overflow: "hidden",
                      display: "-webkit-box",
                      WebkitBoxOrient: "vertical",
                      WebkitLineClamp: textPreviewLines,
                    }}
                  >
                    {previewContent}
                  </span>
                )}
                {showReadingTime && (
                  <div className="card-reading-time ds-caption ds-text-tertiary ds-tnum flex items-center gap-1">
                    <Clock className="size-3 shrink-0" />
                    <span className="line-clamp-1">
                      {article.reading_time === 0
                        ? t("common.lessThanAMinute")
                        : `${article.reading_time} ${t("common.minute")}`}
                    </span>
                  </div>
                )}
                {cardImageSize === "large" && (
                  <ArticleCardCover imageUrl={imageUrl} imageSources={imageSources} />
                )}
              </div>
            </div>
          </div>
          {cardImageSize === "small" && (
            <ArticleCardCover imageUrl={imageUrl} imageSources={imageSources} />
          )}
        </div>
      </div>

      <ContextMenu
        isOpen={contextMenu.isOpen}
        onClose={closeContextMenu}
        position={contextMenu.position}
      >
        <ContextMenuItem
          onClick={() => {
            handleMarkAboveAsRead(article.id);
            closeContextMenu();
          }}
          startContent={<ArrowUpFromLine className="size-4 text-muted" />}
        >
          {t("common.markAboveAsRead")}
        </ContextMenuItem>
        <ContextMenuItem
          onClick={() => {
            handleMarkStatus(article);
            closeContextMenu();
          }}
          startContent={
            article.status === "read" ? (
              <CircleDot className="size-4 text-muted" />
            ) : (
              <Circle className="size-4 text-muted" />
            )
          }
        >
          {article.status === "read"
            ? t("common.markAsUnread")
            : t("common.markAsRead")}
        </ContextMenuItem>
      </ContextMenu>
    </>
  );
}
