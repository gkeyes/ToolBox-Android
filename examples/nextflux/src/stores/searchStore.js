import { atom } from "nanostores";
import { feeds } from "@/stores/feedsStore";
import { settingsState } from "@/stores/settingsStore";
import { searchArticles } from "@/db/storage";

export const searchResults = atom([]);
export const feedSearchResults = atom([]);
export const searching = atom(false);

// 执行本地文章搜索；失败时抛出，由调用方提示并显示空结果
export async function search(keyword) {
  const results = await searchArticles(keyword, settingsState.get().showHiddenFeeds, settingsState.get().sortField);
  return Array.isArray(results) ? results : [];
}

// 执行订阅源搜索
export async function searchFeeds(keyword) {
  const showHiddenFeeds = settingsState.get().showHiddenFeeds;
  return feeds.get().filter((feed) => {
    // 先检查feed是否应该显示
    if (!showHiddenFeeds && feed.hide_globally) {
      return false;
    }
    const searchText = [feed.title, feed.url].join(" ").toLowerCase();
    return searchText.includes(keyword.toLowerCase());
  });
}
