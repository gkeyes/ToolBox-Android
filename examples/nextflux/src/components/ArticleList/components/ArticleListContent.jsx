import { memo, useCallback, useEffect, useRef } from "react";
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
import { CheckCheck, Loader2 } from "lucide-react";
import { handleMarkAllRead } from "@/handlers/articleHandlers";
import { isSyncing } from "@/stores/syncStore.js";
import { useTranslation } from "react-i18next";
import { loadArticles } from "@/stores/articlesStore";
import { settingsState } from "@/stores/settingsStore.js";
import { cn } from "@/lib/utils.js";
import { createArticleRequestGate } from "@/lib/articleReadingState.js";
import { toast } from "sonner";

const ArticleItem = memo(({ article, isLast }) => (
  <div className="mx-2">
    <ArticleCard article={article} />
    {!isLast && <div className="h-4" />}
  </div>
));
ArticleItem.displayName = "ArticleItem";

const ListHeader = () => <div className="vlist-header h-2" />;
const ListFooter = ({ context: { feedId, categoryId, $filter, $isSyncing, $loadingMore, t } }) => (
  <div className="vlist-footer h-24 pt-2 px-2">
    <Button
      size="sm"
      variant="tertiary"
      className="text-muted"
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
  </div>
);
const listComponents = { Header: ListHeader, Footer: ListFooter };
const computeArticleKey = (_index, article) => article.id;

export default function ArticleListContent({
  articles,
  setVisibleRange,
  virtuosoRef,
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
  const setListScroller = useCallback((element) => { listScroller.current = element; }, []);

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
    <div className="h-full">
      {$loading ? (
        <Loader2 className="size-4 animate-spin mx-auto mt-3 text-accent" />
      ) : (
        <div
          className={cn(
            "article-list-content flex-1 h-full",
            reduceMotion
              ? ""
              : " animate-in duration-400 fade-in slide-in-from-bottom-12 ease-in-out",
          )}
        >
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
                isLast={index === articles.length - 1}
              />
            )}
          />
        </div>
      )}
    </div>
  );
}
