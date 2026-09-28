export const CONTINUOUS_PULL_TRIGGER = 132;
export const CONTINUOUS_PULL_MAX = 260;
export const CONTINUOUS_BRIDGE_HEIGHT = 112;

export function continuousPullResistance(rawPull) {
  const x = Math.max(0, Math.min(CONTINUOUS_PULL_MAX, Number(rawPull) || 0));
  if (x <= 32) return x * 0.72;
  if (x <= 96) {
    const t = (x - 32) / 64;
    const eased = t * t * (3 - 2 * t);
    return 23.04 + 38 * eased;
  }
  if (x <= CONTINUOUS_PULL_TRIGGER) {
    const t = (x - 96) / (CONTINUOUS_PULL_TRIGGER - 96);
    return 61.04 + 14 * (1 - Math.pow(1 - t, 2.35));
  }
  return 75.04 + (x - CONTINUOUS_PULL_TRIGGER) * 0.12;
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
