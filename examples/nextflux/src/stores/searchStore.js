import { atom } from "nanostores";
import { feeds } from "@/stores/feedsStore";
import { settingsState } from "@/stores/settingsStore";
import { searchArticles } from "@/db/storage";

export const searchResults = atom([]);
export const feedSearchResults = atom([]);
export const searching = atom(false);

// 执行本地文章搜索
export async function search(keyword) {
  try {
    return await searchArticles(keyword, settingsState.get().showHiddenFeeds, settingsState.get().sortField);
  } catch (error) {
    console.error("搜索失败:", error);
  }
}

// 执行订阅源搜索
export async function searchFeeds(keyword) {
  try {
    const showHiddenFeeds = settingsState.get().showHiddenFeeds;
    return feeds.get().filter((feed) => {
      // 先检查feed是否应该显示
      if (!showHiddenFeeds && feed.hide_globally) {
        return false;
      }
      const searchText = [feed.title, feed.url].join(" ").toLowerCase();
      return searchText.includes(keyword.toLowerCase());
    });
  } catch (error) {
    console.error("搜索失败:", error);
  }
}
