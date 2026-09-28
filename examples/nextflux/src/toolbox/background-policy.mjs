export const DEFAULT_BACKGROUND_INTERVAL_MINUTES = 15;

export function backgroundIntervalMs(value) {
  const minutes = Number(value);
  const effective = Number.isFinite(minutes) && minutes > 0
    ? minutes
    : DEFAULT_BACKGROUND_INTERVAL_MINUTES;
  return effective * 60 * 1000;
}

export function backgroundHealth(status = {}, event, now = Date.now(), intervalMs = DEFAULT_BACKGROUND_INTERVAL_MINUTES * 60 * 1000) {
  const base = {
    enabled: Boolean(status.enabled),
    state: status.state || "stopped",
    lastAttemptAt: Number.isFinite(status.lastAttemptAt) ? status.lastAttemptAt : null,
    lastSuccessAt: Number.isFinite(status.lastSuccessAt) ? status.lastSuccessAt : null,
    lastError: typeof status.lastError === "string" ? status.lastError : null,
    consecutiveFailures: Number.isSafeInteger(status.consecutiveFailures) && status.consecutiveFailures >= 0
      ? status.consecutiveFailures
      : 0,
    nextRunAt: Number.isFinite(status.nextRunAt) ? status.nextRunAt : null,
    lastReason: typeof status.lastReason === "string" ? status.lastReason : null,
    recent: Array.isArray(status.recent) ? status.recent.slice(-8) : [],
  };
  if (!event || typeof event !== "object") return base;
  const remember = (next, outcome) => ({
    ...next,
    recent: [...base.recent, {
      at: now,
      reason: typeof event.reason === "string" ? event.reason : base.lastReason,
      outcome,
    }].slice(-8),
  });
  switch (event.type) {
    case "enabled":
      return { ...base, enabled: true, state: "running", nextRunAt: now + intervalMs };
    case "attempt":
      return remember(
        { ...base, enabled: true, state: "syncing", lastAttemptAt: now, lastReason: event.reason || null, nextRunAt: now + intervalMs },
        "attempt",
      );
    case "success":
      return remember(
        { ...base, enabled: true, state: "running", lastSuccessAt: now, lastError: null, consecutiveFailures: 0, nextRunAt: now + intervalMs },
        "success",
      );
    case "deferred":
      return remember(
        { ...base, enabled: true, state: "deferred", lastError: String(event.message || "后台同步已让位给前台操作。").slice(0, 160), nextRunAt: now + intervalMs },
        "deferred",
      );
    case "failure":
      return remember({
        ...base,
        enabled: true,
        state: "error",
        lastError: String(event.message || "后台同步失败").slice(0, 160),
        consecutiveFailures: base.consecutiveFailures + 1,
        nextRunAt: now + intervalMs,
      }, "failure");
    case "stopped":
      return { ...base, enabled: false, state: "stopped", nextRunAt: null, lastReason: null };
    default:
      return base;
  }
}
