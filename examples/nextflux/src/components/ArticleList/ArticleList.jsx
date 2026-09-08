import { useEffect, useRef } from "react";
import { useStore } from "@nanostores/react";
import {
  filter,
  filteredArticles,
  loadArticles,
  hasMore,
  currentPage,
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

  useEffect(() => {
    // 如果为同步触发刷新且当前文章列表不在顶部，则暂时不刷新列表，防止位置发生位移
    if (
      $lastSync !== lastSyncTime.current &&
      visibleRange.get().startIndex !== 0
    ) {
      // 记录上一次同步时间
      lastSyncTime.current = $lastSync;
      return;
    }
    // 记录上一次同步时间
    lastSyncTime.current = $lastSync;
    let ignore = false;
    const handleFetchArticles = async () => {
      filteredArticles.set([]);
      loading.set(true);
      try {
        const res = await loadArticles(
          feedId || categoryId,
          feedId ? "feed" : categoryId ? "category" : null,
        );

        if (ignore) {
          return;
        }

        filteredArticles.set(res.articles);
        hasMore.set(res.isMore);
        currentPage.set(1);
        loading.set(false);
      } catch {
        console.error("加载文章失败");
        loading.set(false);
      }
    };
    handleFetchArticles(ignore);

    return () => {
      ignore = true;
    };
  }, [
    feedId,
    categoryId,
    $filter,
    sortDirection,
    sortField,
    showHiddenFeeds,
    $lastSync,
  ]);

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
