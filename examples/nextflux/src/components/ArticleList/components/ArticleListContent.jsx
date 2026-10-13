import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import ArticleCard from "./ArticleCard";
import { useNavigate, useParams } from "react-router";
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
import {
  Loader2,
} from "lucide-react";
import {
  MarkAllReadIcon as CheckCheck,
  EmptyIcon as Inbox,
} from "@/components/ui/icons.jsx";
import { handleMarkAllRead, handleMarkRead } from "@/handlers/articleHandlers";
import { feeds } from "@/stores/feedsStore.js";
import { createReadObserver } from "@/lib/readObserver.mjs";
import { dayField } from "@/lib/format.js";
import { isSyncing } from "@/stores/syncStore.js";
import { useTranslation } from "react-i18next";
import { loadArticles } from "@/stores/articlesStore";
import { settingsState } from "@/stores/settingsStore.js";
import { cn } from "@/lib/utils.js";
import { createArticleRequestGate } from "@/lib/articleReadingState.js";
import { toast } from "sonner";
import { claimStagger, STAGGER_LIMIT } from "@/design/motion.js";
import { navigateWithSharedArticleTitle } from "@/motion/sharedElement.mjs";

// The large title collapses into the top bar once it has scrolled under it.
const COLLAPSE_AT = 40;
// Items mounted within this window after the first load take part in the
// once-per-process stagger; later virtualised mounts appear immediately.
const STAGGER_WINDOW_MS = 600;

// Local calendar day of an article, for day headers between list items.
const dayKey = (value) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
};
function dayLabel(value, t, language) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const today = new Date();
  const start = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const diff = Math.round((start(today) - start(date)) / 86400000);
  if (diff === 0) return t("settings.appearance.today");
  if (diff === 1) return t("settings.appearance.yesterday");
  return date.toLocaleDateString(language || undefined, {
    month: "long", day: "numeric", weekday: "short",
    ...(date.getFullYear() !== today.getFullYear() ? { year: "numeric" } : {}),
  });
}

const ArticleItem = memo(({ article, index, staggerUntil, active, onOpen, day, feedTitle, observeRead }) => {
  const [stagger] = useState(() => index < STAGGER_LIMIT && performance.now() < staggerUntil);
  return (
    <div
      className={cn("article-list-item", day && "has-day", stagger && "ds-stagger-item")}
      style={stagger ? { animationDelay: `calc(var(--ds-stagger-step) * ${index})` } : undefined}
    >
      {day && <div className="article-day" role="heading" aria-level={2}><span>{day}</span></div>}
      <ArticleCard article={article} active={active} onOpen={onOpen} feedTitle={feedTitle} observeRead={observeRead} />
    </div>
  );
});
ArticleItem.displayName = "ArticleItem";

const ListHeader = () => (
  <div className="vlist-header" aria-hidden="true">
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
  const { t, i18n } = useTranslation();
  const { feedId, categoryId, articleId } = useParams();
  const navigate = useNavigate();
  const activeId = parseInt(articleId);
  const $filter = useStore(filter);
  const $isSyncing = useStore(isSyncing);
  const { isMedium } = useIsMobile();
  const index = articles.findIndex(
    (article) => article.id === parseInt(articleId),
  );
  const $loading = useStore(loading);
  const $loadingMore = useStore(loadingMore);
  const { reduceMotion, sortField } = useStore(settingsState);
  const $feeds = useStore(feeds);
  // One lookup for all rows; cards do not subscribe to the feed catalog.
  const feedTitles = useMemo(() => new Map($feeds.map((feed) => [feed.id, feed.title])), [$feeds]);
  const dateField = dayField(sortField);
  const openContext = useRef(null);
  useLayoutEffect(() => {
    openContext.current = { navigate, isMedium, reduceMotion };
  });
  // Stable for the list's lifetime so memoised rows never re-render for it.
  const openArticle = useCallback((article, source) => {
    const { navigate: go, isMedium: medium, reduceMotion: reduced } = openContext.current;
    const basePath = (window.location.hash.slice(1).split("?")[0] || "/").split("/article/")[0];
    const toUrl =
      basePath === "/"
        ? `/article/${article.id}`
        : `${basePath}/article/${article.id}`;
    navigateWithSharedArticleTitle({
      articleId: article.id,
      to: toUrl,
      navigate: go,
      source,
      enabled: !medium && !reduced,
    });
  }, []);
  const pageRequests = useRef(null);
  const listScroller = useRef(null);
  const readObserver = useRef(null);
  if (!readObserver.current) {
    readObserver.current = createReadObserver({
      getRoot: () => listScroller.current ?? document.querySelector(".v-list"),
      onRead: (article) => { void handleMarkRead(article); },
    });
  }
  const observeRead = useCallback((element, article) => readObserver.current.observe(element, article), []);
  useEffect(() => () => readObserver.current?.dispose(), []);
  if (!pageRequests.current) pageRequests.current = createArticleRequestGate();
  const collapsedRef = useRef(false);
  const staggerUntil = useRef(0);
  const reportCollapsed = useCallback((value) => {
    if (collapsedRef.current === value) return;
    collapsedRef.current = value;
    onCollapsedChange?.(value);
  }, [onCollapsedChange]);
  const chromeAnchor = useRef(0);
  const handleScroll = useCallback(() => {
    const scroller = listScroller.current;
    const top = scroller?.scrollTop ?? 0;
    reportCollapsed(top > COLLAPSE_AT);
    // Immersive list: reading down tucks the header away; a short scroll up,
    // or the top of the list, brings it back. DOM attribute, no re-render.
    const page = scroller?.closest?.(".nextflux-article-list-page");
    if (!page) return;
    let chrome = page.dataset.chrome || "shown";
    if (top < 48) chrome = "shown";
    else if (top - chromeAnchor.current > 12) chrome = "hidden";
    else if (chromeAnchor.current - top > 12) chrome = "shown";
    if (page.dataset.chrome !== chrome) page.dataset.chrome = chrome;
    if (Math.abs(top - chromeAnchor.current) > 12 || (chrome === "hidden" && top > chromeAnchor.current) || (chrome === "shown" && top < chromeAnchor.current)) {
      chromeAnchor.current = top;
    }
  }, [reportCollapsed]);
  const setListScroller = useCallback((element) => {
    listScroller.current?.removeEventListener?.("scroll", handleScroll);
    listScroller.current = element;
    readObserver.current?.refresh();
    element?.addEventListener?.("scroll", handleScroll, { passive: true });
    reportCollapsed((element?.scrollTop ?? 0) > COLLAPSE_AT);
  }, [handleScroll, reportCollapsed]);
  if (!$loading && staggerUntil.current === 0 && articles.length > 0) {
    staggerUntil.current = !reduceMotion && claimStagger("article-list")
      ? performance.now() + STAGGER_WINDOW_MS
      : -1;
  }

  useEffect(() => {
    // A new source starts at the top with its header visible.
    if ($loading) {
      reportCollapsed(false);
      const page = listScroller.current?.closest?.(".nextflux-article-list-page");
      if (page) page.dataset.chrome = "shown";
      chromeAnchor.current = 0;
    }
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
                day={index === 0 || dayKey(articles[index - 1]?.[dateField]) !== dayKey(article[dateField])
                  ? dayLabel(article[dateField], t, i18n.language)
                  : ""}
                feedTitle={feedTitles.get(article.feedId)}
                observeRead={observeRead}
                staggerUntil={staggerUntil.current}
                active={article.id === activeId}
                onOpen={openArticle}
              />
            )}
          />
        </div>
      )}
    </div>
  );
}
