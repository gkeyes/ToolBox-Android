// A request is claimed synchronously, before React can render another loading state.
// Replacing a query lets its new request start without waiting for the old promise.
export function createArticleRequestGate() {
  let current = null;
  return {
    acquire(generation) {
      if (current?.generation === generation) return null;
      current = { generation };
      return current;
    },
    isCurrent(request, generation) {
      return current === request && request.generation === generation;
    },
    release(request) {
      if (current !== request) return false;
      current = null;
      return true;
    },
    invalidate() { current = null; },
  };
}

function metadataSignature(article) {
  const metadata = { ...article };
  delete metadata.content;
  delete metadata.originalContent;
  delete metadata.shownOriginal;
  return JSON.stringify(metadata);
}

// A cache refresh must not switch out of full-text mode, or roll back an
// acknowledged status change that arrived while this snapshot was being read.
export function mergeArticleForReading(current, loaded, atRequestStart) {
  if (!loaded) return null;
  if (!current || current.id !== loaded.id) {
    return { ...loaded, originalContent: loaded.content, shownOriginal: false };
  }
  const next = {
    ...loaded,
    originalContent: loaded.content,
    content: current.shownOriginal ? current.content : loaded.content,
    shownOriginal: Boolean(current.shownOriginal),
  };
  if (atRequestStart?.id === current.id) {
    for (const field of ["status", "starred"]) {
      if (current[field] !== atRequestStart[field]) next[field] = current[field];
    }
  }
  if (current.content === next.content &&
      current.originalContent === next.originalContent &&
      Boolean(current.shownOriginal) === next.shownOriginal &&
      metadataSignature(current) === metadataSignature(next)) return current;
  return next;
}

export function startArticleRead(articleId, { load, getCurrent, publish, notFound, onError }) {
  let cancelled = false;
  const atRequestStart = getCurrent();
  const done = (async () => {
    try {
      const loaded = await load(articleId);
      if (cancelled) return;
      if (!loaded) { notFound(); return; }
      const current = getCurrent();
      const next = mergeArticleForReading(current, loaded, atRequestStart);
      if (next !== current) publish(next);
    } catch (error) {
      if (!cancelled) onError(error);
    }
  })();
  return { done, cancel() { cancelled = true; } };
}

// Keep the parsed React tree across metadata, typography and list updates.
// Only the displayed article's current source is retained; closing the reader
// releases the cache with its component instance.
export function createArticleContentRenderer(sanitize, parse) {
  let previous = null;
  return (article) => {
    const key = [article?.id, article?.bodyDigest, article?.content, article?.url];
    if (previous && key.every((value, index) => value === previous.key[index])) {
      return previous.result;
    }
    const result = parse(sanitize(article?.content, article?.url));
    previous = { key, result };
    return result;
  };
}

export function createArticleScrollReset() {
  let displayedId = null;
  return (viewport, articleId) => {
    if (articleId == null) { displayedId = null; return; }
    if (!viewport || displayedId === articleId) return;
    viewport.scrollTo({ top: 0, behavior: "instant" });
    displayedId = articleId;
  };
}
