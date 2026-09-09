import { useEffect, useRef } from "react";
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
import { useParams } from "react-router-dom";
import ArticleListHeader from "./components/ArticleListHeader";
import ArticleListContent from "./components/ArticleListContent";
import ArticleListFooter from "./components/ArticleListFooter";
import { settingsState } from "@/stores/settingsStore.js";
import ArticleView from "@/components/ArticleView/ArticleView.jsx";
import Indicator from "@/components/ArticleList/components/Indicator.jsx";
import { cn } from "@heroui/react";
import { useIsMobile } from "@/hooks/use-mobile.jsx";
import { toast } from "sonner";

const ArticleList = () => {
  const { feedId, categoryId, articleId } = useParams();
  const $filteredArticles = useStore(filteredArticles);
  const $filter = useStore(filter);
  const $lastSync = useStore(lastSync);
  const {
    showUnreadByDefault,
    sortDirection,
    sortField,
    showHiddenFeeds,
    showIndicator,
    floatingSidebar,
  } = useStore(settingsState);
  const virtuosoRef = useRef(null);
  const { isMedium } = useIsMobile();
  // 判断是否在移动端且正在查看文章详情
  const isArticleDetailOpen = isMedium && !!articleId;

  const lastSyncTime = useRef(null);
  const lastQuery = useRef(null);

  useEffect(() => {
    const query = JSON.stringify([feedId, categoryId, $filter, sortDirection, sortField, showHiddenFeeds]);
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
          "w-full relative max-w-screen md:w-84 md:max-w-[30%] md:min-w-[18rem] h-dvh flex flex-col",
          floatingSidebar ? "md:border-r" : "",
          // iOS 风格动画：移动端查看文章详情时，列表向左移动
          isArticleDetailOpen && "article-list-shifted",
        )}
      >
        <ArticleListHeader />
        {showIndicator && <Indicator virtuosoRef={virtuosoRef} />}
        <ArticleListContent
          articles={$filteredArticles}
          virtuosoRef={virtuosoRef}
          setVisibleRange={(range) => {
            visibleRange.set(range);
          }}
        />
        <ArticleListFooter />
      </div>
      <ArticleView />
    </div>
  );
};

export default ArticleList;
