export function createAutoSyncDeadline() {
  let version = 0;
  let interval = null;
  let nextEligibleAt = 0;
  let claimed = null;

  const updateInterval = (intervalMs) => {
    if (intervalMs !== interval) {
      interval = intervalMs;
      version += 1;
      nextEligibleAt = 0;
      claimed = null;
    }
  };
  const dueAt = (intervalMs, lastSuccessAt = 0) => {
    updateInterval(intervalMs);
    return Math.max(lastSuccessAt ? lastSuccessAt + intervalMs : 0, nextEligibleAt);
  };

  return {
    dueAt,
    claim({ now, intervalMs, lastSuccessAt = 0, epoch = 0 }) {
      const deadline = dueAt(intervalMs, lastSuccessAt);
      const key = `${epoch}:${version}:${deadline}`;
      if (now < deadline || claimed === key) return false;
      claimed = key;
      nextEligibleAt = now + intervalMs;
      return true;
    },
    reset() {
      nextEligibleAt = 0;
      claimed = null;
    },
  };
}
