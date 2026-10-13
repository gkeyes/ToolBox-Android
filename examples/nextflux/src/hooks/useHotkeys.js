import { openInBrowser } from "@/toolbox/actions.js";
import { useEffect, useRef } from "react";
import { useNavigate } from "react-router";
import {
  activeArticle,
  filteredArticles,
  imageGalleryActive,
} from "@/stores/articlesStore";
import {
  handleMarkStatus,
  handleToggleStar,
  handleToggleContent,
} from "@/handlers/articleHandlers";
import { forceSync } from "@/stores/syncStore";
import {
  searchDialogOpen,
  addFeedModalOpen,
  isModalOpen,
} from "@/stores/modalStore.js";
import {
  feedsByCategory,
  categoryExpandedState,
  updateCategoryExpandState,
} from "@/stores/feedsStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import { adjacentSidebarPath, parseHashRoute, sidebarItems } from "@/lib/hashRoute.mjs";

// The listener is registered once and reads stores and the route when a key is
// pressed, so the host component does not re-render on list, article or route changes.
export function useHotkeys() {
  const navigate = useNavigate();
  const navigateRef = useRef(navigate);
  navigateRef.current = navigate;

  useEffect(() => {
    const go = (path) => navigateRef.current(path);
    const currentSidebarItems = () => sidebarItems(
      feedsByCategory.get(), categoryExpandedState.get(), settingsState.get().defaultExpandCategory,
    );

    const handleKeyDown = async (e) => {
      if (isModalOpen.get()) return;
      // 如果焦点在输入框中,不触发快捷键
      if (
        e.target.tagName === "INPUT" ||
        e.target.tagName === "TEXTAREA" ||
        e.target.isContentEditable
      ) {
        return;
      }

      if (e.key === "N" && e.shiftKey) {
        e.preventDefault();
        addFeedModalOpen.set(!addFeedModalOpen.get());
        return;
      }

      const route = parseHashRoute(window.location.hash);
      const { articleId, basePath } = route;
      const $articles = filteredArticles.get();
      const $activeArticle = activeArticle.get();
      const currentIndex = $articles.findIndex((a) => a.id === $activeArticle?.id);

      switch (e.key.toLowerCase()) {
        case "f": // 搜索
          e.preventDefault();
          searchDialogOpen.set(true);
          break;

        case "j": // 下一篇
          if (!articleId) {
            if ($articles.length === 0) {
              return;
            }
            const nextArticle = $articles[0];
            go(`${basePath}/article/${nextArticle.id}`);
            if (nextArticle.status !== "read") {
              await handleMarkStatus(nextArticle);
            }
          } else if (currentIndex < $articles.length - 1) {
            const nextArticle = $articles[currentIndex + 1];
            go(`${basePath}/article/${nextArticle.id}`);
            if (nextArticle.status !== "read") {
              await handleMarkStatus(nextArticle);
            }
          }
          break;

        case "k": // 上一篇
          if (currentIndex > 0) {
            const prevArticle = $articles[currentIndex - 1];
            go(`${basePath}/article/${prevArticle.id}`);
            if (prevArticle.status !== "read") {
              await handleMarkStatus(prevArticle);
            }
          }
          break;

        case "m": // 标记已读/未读
          if (articleId && $activeArticle) {
            await handleMarkStatus($activeArticle);
          }
          break;

        case "s": // 收藏/取消收藏
          if (articleId && $activeArticle) {
            await handleToggleStar($activeArticle);
          }
          break;

        case "r": // 刷新
          if (!e.ctrlKey && !e.metaKey) {
            await forceSync();
          }
          break;

        case "escape": // 关闭文章
          if (imageGalleryActive.get()) {
            return;
          } else {
            go(basePath || "/");
          }
          break;

        case "v": // 在应用内浏览器打开原文
          if (articleId && $activeArticle) {
            e.preventDefault();
            void openInBrowser($activeArticle.url);
          }
          break;

        case "g": // 原文/摘要切换
          if (articleId && $activeArticle) {
            await handleToggleContent($activeArticle);
          }
          break;

        case "p": // 上一个订阅或分组
        case "n": { // 下一个订阅或分组
          e.preventDefault();
          const path = adjacentSidebarPath(currentSidebarItems(), route, e.key.toLowerCase() === "p" ? -1 : 1);
          if (path) go(path);
          break;
        }

        case "x": { // 切换分组展开状态
          e.preventDefault();
          const expanded = categoryExpandedState.get();
          const { defaultExpandCategory } = settingsState.get();
          if (route.categoryId) {
            updateCategoryExpandState(route.categoryId, !(expanded[route.categoryId] ?? defaultExpandCategory));
          } else if (route.feedId) {
            // 如果当前是订阅，找到其所属分组
            const feedId = parseInt(route.feedId);
            const item = currentSidebarItems().find((entry) => entry.type === "feed" && entry.id === feedId);
            if (item?.categoryId) {
              updateCategoryExpandState(item.categoryId, !(expanded[item.categoryId] ?? defaultExpandCategory));
              go(`/category/${item.categoryId}`);
            }
          }
          break;
        }

        default:
          break;
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, []);
}
