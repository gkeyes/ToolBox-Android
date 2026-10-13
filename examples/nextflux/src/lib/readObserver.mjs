// One IntersectionObserver for every mounted article card. A card counts as
// read once it has been visible and then leaves through the top of the list.
export function createReadObserver({ getRoot, onRead, threshold = 0.2, Observer = globalThis.IntersectionObserver }) {
  const records = new Map(); // element -> article
  const seen = new Set(); // article ids that have been visible in this list
  let observer = null;
  let root;

  const handle = (entries) => {
    for (const entry of entries) {
      const article = records.get(entry.target);
      if (!article) continue;
      if (entry.isIntersecting) {
        seen.add(article.id);
      } else if (seen.has(article.id) && entry.rootBounds && entry.boundingClientRect.top < entry.rootBounds.top) {
        records.delete(entry.target);
        observer.unobserve(entry.target);
        onRead(article);
      }
    }
  };

  const current = () => {
    const next = getRoot?.() ?? null;
    if (observer && next === root) return observer;
    observer?.disconnect();
    root = next;
    observer = new Observer(handle, { root, threshold });
    for (const element of records.keys()) observer.observe(element);
    return observer;
  };

  return {
    observe(element, article) {
      if (!element || typeof Observer !== "function") return () => {};
      records.set(element, article);
      current().observe(element);
      return () => {
        if (records.get(element) !== article) return;
        records.delete(element);
        observer?.unobserve(element);
      };
    },
    // Re-read the scroll root (it changes when the list remounts after loading).
    refresh() { if (records.size) current(); },
    reset() { seen.clear(); },
    dispose() {
      observer?.disconnect();
      observer = null;
      records.clear();
      seen.clear();
    },
  };
}
