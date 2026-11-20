import { toast } from "sonner";
import { currentAccountCheck, onAccountInvalidated } from "../stores/syncStore.js";
import {
  activeArticle,
  updateArticleStarred,
  updateArticleStatus,
  queueArticleRead,
  markAllAsRead,
  markAboveAsRead,
  markBelowAsRead,
  fullTextLoadingId,
} from "../stores/articlesStore.js";
import minifluxAPI from "@/api/miniflux";
import { loadBestFullText } from "@/reading/fulltext.mjs";

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

let fullTextRequest = null;

function stopWaitingForFullText() {
  if (!fullTextRequest) return;
  fullTextRequest.controller.abort();
  fullTextRequest = null;
  fullTextLoadingId.set(null);
}

activeArticle.listen((article, previous) => {
  if (article?.id !== previous?.id) stopWaitingForFullText();
});
onAccountInvalidated(stopWaitingForFullText);

export const handleToggleContent = async (article) => {
  const current = activeArticle.get();
  if (!article || !current || current.id !== article.id) return;

  // The same action can end the wait. Its old completion must leave a new
  // request (including a retry of the same article) and its state alone.
  if (fullTextRequest) {
    stopWaitingForFullText();
    return;
  }

  const showOriginal = !current.shownOriginal;
  const check = currentAccountCheck();
  if (!showOriginal) {
    try { check(false); }
    catch (error) {
      if (error.code !== "ACCOUNT_CHANGED") throw error;
      return;
    }
    activeArticle.set({
      ...current, content: current.originalContent, shownOriginal: false,
      fullTextSource: null, fullTextBaseUrl: null, fullTextCandidates: null,
    });
    return;
  }

  const request = { controller: new AbortController() };
  fullTextRequest = request;
  fullTextLoadingId.set(current.id);
  try {
    check();
    const result = await loadBestFullText({
      article: current, api: minifluxAPI, signal: request.controller.signal,
    });
    if (fullTextRequest !== request) return;
    check(false);
    activeArticle.set({
      ...activeArticle.get(), content: result.content, shownOriginal: true,
      fullTextSource: result.source, fullTextBaseUrl: result.baseUrl, fullTextCandidates: result.candidates,
    });
  } catch (error) {
    if (fullTextRequest === request && error.code !== "ACCOUNT_CHANGED") {
      toast.error(error.message || "操作失败，请检查网络后重试。");
    }
  } finally {
    if (fullTextRequest === request) {
      fullTextRequest = null;
      fullTextLoadingId.set(null);
    }
  }
};
