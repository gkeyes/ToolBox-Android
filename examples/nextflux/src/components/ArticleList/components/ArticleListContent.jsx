import { memo, useCallback, useEffect, useRef, useState } from "react";
import ArticleCard from "./ArticleCard";
import { useParams } from "react-router-dom";
import {
  filter,
  hasMore,
  currentPage,
  loadingMore,
  loading,
  getArticleQueryGeneration,
} from "@/stores/articlesStore.js";
import { useStore } from "@nanostores/react";
import { Virtuoso } from "react-virtuoso";
import { useIsMobile } from "@/hooks/use-mobile.jsx";
import { Button } from "@heroui/react";
import { CheckCheck, Inbox, Loader2 } from "lucide-react";
import { handleMarkAllRead } from "@/handlers/articleHandlers";
import { isSyncing } from "@/stores/syncStore.js";
import { useTranslation } from "react-i18next";
import { loadArticles } from "@/stores/articlesStore";
import { settingsState } from "@/stores/settingsStore.js";
import { cn } from "@/lib/utils.js";
import { createArticleRequestGate } from "@/lib/articleReadingState.js";
import { toast } from "sonner";
import { ArticleListLargeTitle } from "./ArticleListHeader.jsx";
import { claimStagger, STAGGER_LIMIT } from "@/design/motion.js";

// The large title collapses into the top bar once it has scrolled under it.
const COLLAPSE_AT = 40;
// Items mounted within this window after the first load take part in the
// once-per-process stagger; later virtualised mounts appear immediately.
const STAGGER_WINDOW_MS = 600;

const ArticleItem = memo(({ article, index, staggerUntil }) => {
  const [stagger] = useState(() => index < STAGGER_LIMIT && performance.now() < staggerUntil);
  return (
    <div
      className={cn("article-list-item", stagger && "ds-stagger-item")}
      style={stagger ? { animationDelay: `calc(var(--ds-stagger-step) * ${index})` } : undefined}
    >
      <ArticleCard article={article} />
    </div>
  );
});
ArticleItem.displayName = "ArticleItem";

const ListHeader = () => (
  <div className="vlist-header">
    <ArticleListLargeTitle />
  </div>
);
const ListEmpty = ({ context: { t } }) => (
  <div className="ds-empty article-list-empty">
    <span className="ds-empty-icon"><Inbox /></span>
    <p className="ds-empty-title">{t("articleList.emptyListTitle")}</p>
    <p className="ds-empty-text">{t("articleList.emptyListText")}</p>
  </div>
);
const ListFooter = ({ context: { feedId, categoryId, $filter, $isSyncing, $loadingMore, t, count } }) => (
  <div className="vlist-footer h-24 pt-3 px-4">
    {count > 0 && (
      <Button
        size="md"
        variant="secondary"
        isDisabled={$filter === "starred"}
        fullWidth
        onPress={() => {
          if (feedId) handleMarkAllRead("feed", feedId);
          else if (categoryId) handleMarkAllRead("category", categoryId);
          else handleMarkAllRead();
        }}
      >
        {$isSyncing || $loadingMore ? <Loader2 className="size-4 animate-spin" /> : <CheckCheck className="size-4" />}
        {t("articleList.markAllRead")}
      </Button>
    )}
  </div>
);
const listComponents = { Header: ListHeader, Footer: ListFooter, EmptyPlaceholder: ListEmpty };
const computeArticleKey = (_index, article) => article.id;

export default function ArticleListContent({
  articles,
  setVisibleRange,
  virtuosoRef,
  onCollapsedChange,
}) {
  const { t } = useTranslation();
  const { feedId, categoryId, articleId } = useParams();
  const $filter = useStore(filter);
  const $isSyncing = useStore(isSyncing);
  const { isMedium } = useIsMobile();
  const index = articles.findIndex(
    (article) => article.id === parseInt(articleId),
  );
  const $loading = useStore(loading);
  const $loadingMore = useStore(loadingMore);
  const { reduceMotion } = useStore(settingsState);
  const pageRequests = useRef(null);
  const listScroller = useRef(null);
  if (!pageRequests.current) pageRequests.current = createArticleRequestGate();
  const collapsedRef = useRef(false);
  const staggerUntil = useRef(0);
  const reportCollapsed = useCallback((value) => {
    if (collapsedRef.current === value) return;
    collapsedRef.current = value;
    onCollapsedChange?.(value);
  }, [onCollapsedChange]);
  const handleScroll = useCallback(() => {
    reportCollapsed((listScroller.current?.scrollTop ?? 0) > COLLAPSE_AT);
  }, [reportCollapsed]);
  const setListScroller = useCallback((element) => {
    listScroller.current?.removeEventListener?.("scroll", handleScroll);
    listScroller.current = element;
    element?.addEventListener?.("scroll", handleScroll, { passive: true });
    reportCollapsed((element?.scrollTop ?? 0) > COLLAPSE_AT);
  }, [handleScroll, reportCollapsed]);
  if (!$loading && staggerUntil.current === 0 && articles.length > 0) {
    staggerUntil.current = !reduceMotion && claimStagger("article-list")
      ? performance.now() + STAGGER_WINDOW_MS
      : -1;
  }

  useEffect(() => {
    // A new source starts at the top with the expanded large title.
    if ($loading) reportCollapsed(false);
  }, [$loading, reportCollapsed]);

  useEffect(() => {
    if (isMedium) {
      return;
    }
    if (index >= 0) {
      virtuosoRef.current?.scrollIntoView({
        index: index,
        behavior: reduceMotion ? "auto" : "smooth",
      });
    }
  }, [isMedium, index, reduceMotion, virtuosoRef]);

  useEffect(() => {
    const requests = pageRequests.current;
    return () => requests.invalidate();
  }, []);

  const handleEndReached = useCallback(async () => {
    if (!hasMore.get() || loading.get()) return;
    const generation = getArticleQueryGeneration();
    const request = pageRequests.current.acquire(generation);
    if (!request) return;

    try {
      loadingMore.set(true);
      const nextPage = currentPage.get() + 1;
      if (feedId) {
        await loadArticles(feedId, "feed", nextPage, true);
      } else if (categoryId) {
        await loadArticles(categoryId, "category", nextPage, true);
      } else {
        await loadArticles(null, null, nextPage, true);
      }
    } catch (failure) {
      if (pageRequests.current.isCurrent(request, getArticleQueryGeneration())) toast.error(failure.message || "加载文章失败，请刷新后重试。");
    } finally {
      if (pageRequests.current.isCurrent(request, getArticleQueryGeneration())) loadingMore.set(false);
      pageRequests.current.release(request);
    }
  }, [feedId, categoryId]);

  const handleNearBottom = useCallback((atBottom) => {
    // Ignore Virtuoso's initial unmeasured bottom state. A short first page is
    // covered by endReached; prefetch starts once the reader actually scrolls.
    if (atBottom && listScroller.current?.scrollTop > 0) void handleEndReached();
  }, [handleEndReached]);

  return (
    <div className="article-list-body min-h-0 flex-1">
      {$loading ? (
        <div className="article-list-loading h-full">
          <ArticleListLargeTitle />
          <div className="flex justify-center pt-6" role="progressbar" aria-label={t("common.loading")}>
            <span className="ds-spinner" />
          </div>
        </div>
      ) : (
        <div className="article-list-content h-full">
          <Virtuoso
            ref={virtuosoRef}
            scrollerRef={setListScroller}
            className="v-list h-full"
            overscan={{ main: 400, reverse: 200 }}
            data={articles}
            computeItemKey={computeArticleKey}
            rangeChanged={setVisibleRange}
            context={{
              feedId,
              categoryId,
              $filter,
              $isSyncing,
              $loadingMore,
              t,
              count: articles.length,
            }}
            totalCount={articles.length}
            endReached={handleEndReached}
            atBottomThreshold={600}
            atBottomStateChange={handleNearBottom}
            components={listComponents}
            itemContent={(index, article) => (
              <ArticleItem
                key={article.id}
                article={article}
                index={index}
                staggerUntil={staggerUntil.current}
              />
            )}
          />
        </div>
      )}
    </div>
  );
}
