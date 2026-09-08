import { atom, computed } from "nanostores";
import { persistentAtom } from "@nanostores/persistent";
import {
  getFeeds,
  getCategories,
  getUnreadCount,
  getStarredCount,
} from "../db/storage";
import { filter } from "@/stores/articlesStore.js";
import { settingsState } from "@/stores/settingsStore.js";

export const feeds = atom([]);
export const categories = atom([]);
export const error = atom(null);
export const unreadCounts = atom({});
export const starredCounts = atom({});

export const categoryExpandedState = persistentAtom(
  "categoryExpanded",
  {},
  {
    encode: (value) => JSON.stringify(value),
    decode: (str) => JSON.parse(str),
  },
);

// 更新分类展开状态
export const updateCategoryExpandState = (categoryId, isExpanded) => {
  const currentState = categoryExpandedState.get();
  categoryExpandedState.set({
    ...currentState,
    [categoryId]: isExpanded,
  });
};

export const filteredFeeds = computed(
  [feeds, filter, starredCounts, unreadCounts, settingsState],
  ($feeds, $filter, $starredCounts, $unreadCounts, $settings) => {
    const visibleFeeds = $settings.showHiddenFeeds
      ? $feeds
      : $feeds.filter((feed) => !feed.hide_globally);
    return visibleFeeds.filter((feed) => {
      switch ($filter) {
        case "starred":
          return $starredCounts[feed.id] > 0;
        case "unread":
          return $unreadCounts[feed.id] > 0;
        default:
          return true;
      }
    });
  },
);

export const feedsByCategory = computed(
  [filteredFeeds, categories, unreadCounts, starredCounts],
  ($filteredFeeds, $categories, $unreadCounts, $starredCounts) => {
    return Object.entries(
      $filteredFeeds.reduce((acc, feed) => {
        const categoryId = feed.categoryId || "uncategorized";
        const category = $categories.find((c) => c.id === feed.categoryId);
        const categoryName = category ? category.title : "未分类";

        if (!acc[categoryId]) {
          acc[categoryId] = {
            name: categoryName,
            feeds: [],
          };
        }
        acc[categoryId].feeds.push(feed);
        return acc;
      }, {}),
    )
      .map(([id, category]) => ({
        id,
        title: category.name,
        isActive: false,
        feeds: category.feeds.map((feed) => ({
          ...feed,
          unreadCount: $unreadCounts[feed.id] || 0,
          starredCount: $starredCounts[feed.id] || 0,
        })),
      }))
      .sort((a, b) => a.title.localeCompare(b.title));
  },
);

export const getCategoryCount = computed(
  [feeds, filter, starredCounts, unreadCounts],
  ($feeds, $filter, $starredCounts, $unreadCounts) => (categoryId) => {
    // 根据分类ID筛选出该分类下的所有订阅源
    const categoryFeeds = $feeds.filter((feed) =>
      categoryId === "uncategorized"
        ? !feed.categoryId
        : feed.categoryId === parseInt(categoryId),
    );

    switch ($filter) {
      case "starred":
        return categoryFeeds.reduce(
          (sum, feed) => sum + ($starredCounts[feed.id] || 0),
          0,
        );
      case "unread":
      default:
        return categoryFeeds.reduce(
          (sum, feed) => sum + ($unreadCounts[feed.id] || 0),
          0,
        );
    }
  },
);

export const getFeedCount = computed(
  [filter, starredCounts, unreadCounts],
  ($filter, $starredCounts, $unreadCounts) => (feedId) => {
    switch ($filter) {
      case "starred":
        return $starredCounts[feedId] || 0;
      case "unread":
      default:
        return $unreadCounts[feedId] || 0;
    }
  },
);

export const totalUnreadCount = computed([unreadCounts], ($unreadCounts) => {
  return Object.values($unreadCounts).reduce((sum, count) => sum + count, 0);
});

export const totalStarredCount = computed([starredCounts], ($starredCounts) => {
  return Object.values($starredCounts).reduce((sum, count) => sum + count, 0);
});

export async function loadFeeds() {
  try {
    const storedFeeds = await getFeeds();
    feeds.set(storedFeeds || []);
    const storedCategories = await getCategories();
    categories.set(storedCategories || []);
    const filteredFeeds = settingsState.get().showHiddenFeeds
      ? storedFeeds
      : storedFeeds.filter((feed) => !feed.hide_globally);

    // 获取未读和收藏计数
    const unreadCount = {};
    const starredCount = {};
    for (const feed of filteredFeeds) {
      unreadCount[feed.id] = await getUnreadCount(feed.id);
      starredCount[feed.id] = await getStarredCount(feed.id);
    }
    unreadCounts.set(unreadCount);
    starredCounts.set(starredCount);
  } catch (err) {
    error.set("加载订阅源失败");
    console.error(err);
  }
}
