let activeArticleId = null;

const nextFrame = () => new Promise((resolve) => requestAnimationFrame(resolve));

function matchingTitles(articleId) {
  const id = String(articleId);
  return [...document.querySelectorAll("[data-shared-article-title]")]
    .filter((element) => element.dataset.sharedArticleTitle === id);
}

async function waitForTarget(articleId, source, timeout = 220) {
  const started = performance.now();
  while (performance.now() - started < timeout) {
    const target = matchingTitles(articleId).find((element) => element !== source);
    if (target) return target;
    await nextFrame();
  }
  return null;
}

export function sharedArticleTitleProps(articleId) {
  const id = String(articleId);
  return {
    "data-shared-article-title": id,
    style: activeArticleId === id ? { viewTransitionName: "nextflux-article-title" } : undefined,
  };
}

export function navigateWithSharedArticleTitle({
  articleId,
  to,
  navigate,
  source,
  enabled = true,
}) {
  if (!enabled || typeof document === "undefined" || typeof document.startViewTransition !== "function") {
    navigate(to);
    return null;
  }

  const id = String(articleId);
  activeArticleId = id;
  if (source?.style) source.style.viewTransitionName = "nextflux-article-title";
  document.documentElement.dataset.nextfluxViewTransition = "article";

  const transition = document.startViewTransition(async () => {
    navigate(to);
    const target = await waitForTarget(id, source);
    if (target?.style) target.style.viewTransitionName = "nextflux-article-title";
  });

  const cleanup = () => {
    for (const element of matchingTitles(id)) {
      if (element.style.viewTransitionName === "nextflux-article-title") element.style.viewTransitionName = "";
    }
    if (activeArticleId === id) activeArticleId = null;
    delete document.documentElement.dataset.nextfluxViewTransition;
  };
  transition.finished.then(cleanup, cleanup);
  return transition;
}
