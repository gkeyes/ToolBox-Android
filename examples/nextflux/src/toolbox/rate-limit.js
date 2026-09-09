const FALLBACK_WAIT_MS = 60000;
const CHECK_INTERVAL_MS = 1000;
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

// RATE_LIMITED is the host's admission rejection: the operation has not run.
// HTTP 429 and unknown transport outcomes must never enter this retry path.
export async function waitForRateLimit(error, check = () => true, options = {}) {
  if (error?.code !== "RATE_LIMITED" || error?.response) throw error;
  const now = options.now || Date.now;
  const pause = options.sleep || sleep;
  const wait = Number.isInteger(error.retryAfterMs) && error.retryAfterMs >= 0
    ? error.retryAfterMs : FALLBACK_WAIT_MS;
  const deadline = now() + wait;
  while (true) {
    if (await check() === false) return false;
    const remaining = deadline - now();
    if (remaining <= 0) return true;
    await pause(Math.min(CHECK_INTERVAL_MS, remaining));
  }
}
