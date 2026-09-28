export const CONTINUOUS_PULL_TRIGGER = 92;
export const CONTINUOUS_PULL_MAX = 170;
export const CONTINUOUS_BRIDGE_HEIGHT = 96;

export function continuousPullResistance(rawPull) {
  const x = Math.max(0, Math.min(CONTINUOUS_PULL_MAX, Number(rawPull) || 0));
  if (x <= 24) return x * 0.82;
  if (x <= 72) {
    const t = (x - 24) / 48;
    const eased = t * t * (3 - 2 * t);
    return 19.68 + 29 * eased;
  }
  if (x <= CONTINUOUS_PULL_TRIGGER) {
    const t = (x - 72) / (CONTINUOUS_PULL_TRIGGER - 72);
    return 48.68 + 8.5 * (1 - Math.pow(1 - t, 2.2));
  }
  return 57.18 + (x - CONTINUOUS_PULL_TRIGGER) * 0.18;
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

export function springFrame({ x, velocity, target, dt, stiffness, damping, mass = 1 }) {
  const safeDt = Math.max(0, Math.min(0.032, Number(dt) || 0));
  const force = -stiffness * (x - target);
  const dampingForce = -damping * velocity;
  const acceleration = (force + dampingForce) / mass;
  const nextVelocity = velocity + acceleration * safeDt;
  const nextX = x + nextVelocity * safeDt;
  return { x: nextX, velocity: nextVelocity };
}
