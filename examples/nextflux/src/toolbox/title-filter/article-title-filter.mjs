/** Resolve the feed from the active entry, never from the surrounding feed/category
 * route. Mixed lists and cached entries must not write another feed's rules.
 */
function positiveId(value) {
  if (typeof value !== "number" && typeof value !== "string") return null;
  if (typeof value === "string" && !/^[1-9][0-9]*$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

export function articleIdFromHash(hash) {
  if (typeof hash !== "string") return null;
  const path = hash.replace(/^#/, "").split("?")[0];
  const match = path.match(/(?:^|\/)article\/([1-9][0-9]*)\/?$/);
  return match ? positiveId(match[1]) : null;
}

export function resolveArticleFilterScope(article, routeArticleId) {
  const articleId = positiveId(article?.id);
  if (!articleId || articleId !== positiveId(routeArticleId)) return null;
  // Current NextFlux caches use feedId; the alternatives handle original API
  // entries. An invalid or contradictory identity is not guessed around.
  const values = [article.feedId, article.feed_id, article.feed?.id]
    .filter(value => value !== undefined && value !== null);
  if (!values.length) return null;
  const ids = values.map(positiveId);
  if (ids.some(id => !id || id !== ids[0])) return null;
  return { articleId, feedId: ids[0], key: `${articleId}:${ids[0]}` };
}

export function isArticleFilterScopeCurrent(scope, article, hash) {
  return Boolean(scope && resolveArticleFilterScope(article, articleIdFromHash(hash))?.key === scope.key);
}

/** Uses the application's existing entry store, router and keyword editor.
 * No requests, subscriptions to article bodies or separate keyword storage.
 */
export function createArticleTitleFilterControl({ React, FeedTitleFilterButton, activeArticle, useParams, getHash = () => window.location.hash }) {
  const { createElement: h, useSyncExternalStore } = React;
  const subscribe = notify => activeArticle.listen(notify);
  const snapshot = () => activeArticle.get();
  return function ArticleTitleFilterButton() {
    const article = useSyncExternalStore(subscribe, snapshot, snapshot);
    const { articleId } = useParams();
    const scope = resolveArticleFilterScope(article, articleId);
    if (!scope) return null;
    return h(FeedTitleFilterButton, {
      key: scope.key,
      feedId: scope.feedId,
      contextKey: scope.key,
      // Recheck after every await as well as at click time. In particular, a
      // route may already have changed before the new cached entry arrives.
      isContextCurrent: () => isArticleFilterScopeCurrent(scope, activeArticle.get(), getHash()),
    });
  };
}
