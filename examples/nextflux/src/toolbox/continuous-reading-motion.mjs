export const CONTINUOUS_PULL_TRIGGER = 150;
export const CONTINUOUS_PAGE_START = 150;
export const CONTINUOUS_MAX_EXTRA = 240;
export const CONTINUOUS_MOTION_SPEED = 1.25;

export function continuousRubberBand(rawPull) {
  const x = Math.max(0, Number(rawPull) || 0);
  return 78 * (1 - Math.exp(-x / 100));
}

export function findNextUnreadArticle(articles, currentId) {
  const rows = Array.isArray(articles) ? articles : [];
  const id = Number(currentId);
  const index = rows.findIndex((article) => Number(article?.id) === id);
  if (index < 0) return rows.find((article) => article?.status === "unread") ?? null;
  for (let i = index + 1; i < rows.length; i += 1) {
    if (rows[i]?.status === "unread") return rows[i];
  }
  return null;
}
