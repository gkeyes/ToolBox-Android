import { useEffect, useRef, useState } from "react";
import { useStore } from "@nanostores/react";
import {
  filter,
  filteredArticles,
  loadArticles,
  getArticleQueryGeneration,
  resetArticleQuery,
  loading,
  visibleRange,
} from "@/stores/articlesStore.js";
import { lastSync } from "@/stores/syncStore.js";
import { useParams } from "react-router";
import ArticleListHeader from "./components/ArticleListHeader";
import ArticleListContent from "./components/ArticleListContent";
import { settingsState } from "@/stores/settingsStore.js";
import ArticleView from "@/components/ArticleView/ArticleView.jsx";
import Indicator from "@/components/ArticleList/components/Indicator.jsx";
import { cn } from "@heroui/react";
import { toast } from "sonner";

const ArticleList = () => {
  const { feedId, categoryId } = useParams();
  const $filteredArticles = useStore(filteredArticles);
  const $filter = useStore(filter);
  const $lastSync = useStore(lastSync);
  const {
    showUnreadByDefault,
    sortDirection,
    sortField,
    showHiddenFeeds,
    hideDuplicateArticles,
    showIndicator,
    floatingSidebar,
    articleListStyle,
    listFontScale,
  } = useStore(settingsState);
  const virtuosoRef = useRef(null);
  const [headerCollapsed, setHeaderCollapsed] = useState(false);

  const lastSyncTime = useRef(null);
  const lastQuery = useRef(null);

  useEffect(() => {
    const query = JSON.stringify([feedId, categoryId, $filter, sortDirection, sortField, showHiddenFeeds, hideDuplicateArticles]);
    // A sync refresh away from the top keeps this session alive for pagination.
    // A changed filter/source always starts a new session, even after a sync.
    if (
      lastQuery.current === query &&
      $lastSync !== lastSyncTime.current &&
      visibleRange.get().startIndex !== 0
    ) {
      // 记录上一次同步时间
      lastSyncTime.current = $lastSync;
      return;
    }
    // 记录上一次同步时间
    lastSyncTime.current = $lastSync;
    lastQuery.current = query;
    const generation = resetArticleQuery();
    const handleFetchArticles = async () => {
      loading.set(true);
      try {
        await loadArticles(
          feedId || categoryId,
          feedId ? "feed" : categoryId ? "category" : null,
        );
      } catch (failure) {
        if (generation === getArticleQueryGeneration()) toast.error(failure.message || "加载文章失败，请刷新后重试。");
      } finally {
        if (generation === getArticleQueryGeneration()) loading.set(false);
      }
    };
    handleFetchArticles();
  }, [
    feedId,
    categoryId,
    $filter,
    sortDirection,
    sortField,
    showHiddenFeeds,
    hideDuplicateArticles,
    $lastSync,
  ]);

  useEffect(() => () => { resetArticleQuery(); }, []);

  // 组件挂载时设置默认过滤器
  useEffect(() => {
    if (!feedId && !categoryId && showUnreadByDefault) {
      filter.set("unread");
    }
  }, []);

  return (
    <div className="main-content flex">
      <div
        className={cn(
          "nextflux-article-list-page bg-background w-full relative max-w-screen md:w-84 md:max-w-[30%] md:min-w-[18rem] h-dvh flex flex-col",
          floatingSidebar ? "md:border-r" : "",
        )}
        data-list-style={articleListStyle === "cards" ? "cards" : "lines"}
        style={{ "--nf-list-scale": Number.isFinite(listFontScale) ? Math.min(1.3, Math.max(0.85, listFontScale)) : 1 }}
      >
        <ArticleListHeader collapsed={headerCollapsed} />
        {showIndicator && <Indicator virtuosoRef={virtuosoRef} />}
        <ArticleListContent
          articles={$filteredArticles}
          virtuosoRef={virtuosoRef}
          onCollapsedChange={setHeaderCollapsed}
          setVisibleRange={(range) => {
            visibleRange.set(range);
          }}
        />
        <div className="nextflux-list-transition-cover" aria-hidden="true" />
      </div>
      <ArticleView />
    </div>
  );
};

export default ArticleList;
