import { toast } from "sonner";
import { runAccountOperation } from "../stores/syncStore.js";
import {
  activeArticle,
  updateArticleStarred,
  updateArticleStatus,
  queueArticleRead,
  markAllAsRead,
  markAboveAsRead,
  markBelowAsRead,
  loadingOriginContent,
} from "../stores/articlesStore.js";
import minifluxAPI from "@/api/miniflux";

// 处理文章状态更新
export const handleMarkStatus = async (article) => {
  try {
    await updateArticleStatus(article);
  } catch (err) {
    if (err.code !== "ACCOUNT_CHANGED") toast.error(err.message || "操作失败，请检查网络后重试。");
  }
};

// Automatic reading events have an explicit target. Re-entering a virtualized
// card cannot turn an acknowledged read back into unread.
export const handleMarkRead = async (article) => {
  try { await queueArticleRead(article); }
  catch (err) {
    if (err.code !== "ACCOUNT_CHANGED") toast.error(err.message || "操作失败，请检查网络后重试。");
  }
};

// 处理文章星标状态更新
export const handleToggleStar = async (article) => {
  try {
    await updateArticleStarred(article);
  } catch (err) {
    if (err.code !== "ACCOUNT_CHANGED") toast.error(err.message || "操作失败，请检查网络后重试。");
  }
};

// 处理标记所有文章为已读
export const handleMarkAllRead = async (type, id) => {
  try {
    switch (type) {
      case "feed":
        await markAllAsRead("feed", id);
        break;
      case "category":
        await markAllAsRead("category", id);
        break;
      default:
        await markAllAsRead();
    }
  } catch (err) {
    if (err.code !== "ACCOUNT_CHANGED") toast.error(err.message || "操作失败，请检查网络后重试。");
  }
};

// 处理标记上方文章为已读
export const handleMarkAboveAsRead = async (articleId) => {
  try {
    await markAboveAsRead(articleId);
  } catch (err) {
    if (err.code !== "ACCOUNT_CHANGED") toast.error(err.message || "操作失败，请检查网络后重试。");
  }
};

// 处理标记下方文章为已读
export const handleMarkBelowAsRead = async (articleId) => {
  try {
    await markBelowAsRead(articleId);
  } catch (err) {
    if (err.code !== "ACCOUNT_CHANGED") toast.error(err.message || "操作失败，请检查网络后重试。");
  }
};

// 处理内容切换
export const handleToggleContent = async (article) => {
  if (!article || loadingOriginContent.get()) return;

  try {
    loadingOriginContent.set(true);
    const showOriginal = !article.shownOriginal;

    await runAccountOperation(async (check) => {
      const content = showOriginal
        ? await minifluxAPI.fetchEntryContent(article.id)
        : article.originalContent;
      check(false);
      if (activeArticle.get()?.id === article.id) {
        activeArticle.set({ ...activeArticle.get(), content, shownOriginal: showOriginal });
      }
    });
  } catch (error) {
    if (error.code !== "ACCOUNT_CHANGED") toast.error(error.message || "操作失败，请检查网络后重试。");
  } finally {
    loadingOriginContent.set(false);
  }
};
