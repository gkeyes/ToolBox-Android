// Pure route helpers for code that runs outside React's render (key handlers),
// so it can read the current route on demand instead of subscribing to it.

export function parseHashRoute(hash) {
  const path = String(hash ?? "").replace(/^#/, "").split("?")[0] || "/";
  const [listPath, articlePart] = path.split("/article/");
  const basePath = listPath.replace(/\/$/, "");
  const feedId = /^\/feed\/([^/]+)/.exec(basePath)?.[1];
  const categoryId = /^\/category\/([^/]+)/.exec(basePath)?.[1];
  const articleId = articlePart?.split("/")[0] || undefined;
  return { basePath, feedId, categoryId, articleId };
}

// Sidebar order used by the p/n shortcuts: all, then each category followed by
// its feeds while that category is expanded.
export function sidebarItems(feedsByCategory, expandedState, defaultExpand) {
  const items = [{ type: "all", id: "all", path: "/" }];
  for (const category of feedsByCategory) {
    const isExpanded = expandedState[category.id] ?? defaultExpand;
    items.push({ type: "category", id: category.id, path: `/category/${category.id}`, isExpanded });
    if (!isExpanded) continue;
    for (const feed of category.feeds) {
      items.push({ type: "feed", id: feed.id, categoryId: category.id, path: `/feed/${feed.id}` });
    }
  }
  return items;
}

export function currentSidebarIndex(items, { feedId, categoryId }) {
  if (feedId) return items.findIndex((item) => item.type === "feed" && item.id === parseInt(feedId));
  if (categoryId) return items.findIndex((item) => item.type === "category" && String(item.id) === String(categoryId));
  return 0;
}

export function adjacentSidebarPath(items, route, step) {
  const index = currentSidebarIndex(items, route);
  if (index < 0 && step < 0) return null;
  return items[index + step]?.path ?? null;
}
