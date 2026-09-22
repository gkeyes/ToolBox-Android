(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.GitHubWatcherReliability = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function timeoutError(message = "本轮同步超时，将自动重试") {
    return Object.assign(new Error(message), { kind: "offline", code: "NETWORK_TIMEOUT" });
  }

  // A retired operation can settle, but can no longer commit state. Native network
  // calls also have their own timeout; this deadline protects the JS/bridge boundary.
  class Lease {
    constructor(timeoutMs, now = Date.now) {
      this.now = now;
      this.deadline = now() + timeoutMs;
      this.failure = null;
      this.pending = new Set();
    }
    remaining() { return Math.max(0, this.deadline - this.now()); }
    assert() {
      if (this.failure) throw this.failure;
      if (!this.remaining()) throw timeoutError();
    }
    invalidate(error = Object.assign(new Error("同步已取消"), { code: "CANCELLED" })) {
      if (this.failure) return;
      this.failure = error;
      for (const reject of [...this.pending]) reject(error);
    }
    wait(value, maxMs = this.remaining()) {
      // Attach rejection handlers even when already retired, avoiding unhandled
      // rejections from a native call that was dispatched just before cancellation.
      const source = Promise.resolve(value);
      return new Promise((resolve, reject) => {
        let done = false;
        let timer;
        const finish = (error, result) => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          this.pending.delete(cancel);
          if (error) reject(error); else resolve(result);
        };
        const cancel = (error) => finish(error);
        this.pending.add(cancel);
        source.then((result) => {
          try { this.assert(); finish(null, result); } catch (error) { finish(error); }
        }, (error) => finish(error));
        try {
          this.assert();
          timer = setTimeout(() => finish(timeoutError()), Math.max(1, Math.min(maxMs, this.remaining())));
        } catch (error) { finish(error); }
      });
    }
  }

  function mergeRuns(existing, incoming) {
    const byId = new Map(existing.map((run) => [run.id, run]));
    for (const run of incoming) {
      if (!run || !Number.isSafeInteger(Number(run.id))) continue;
      const old = byId.get(run.id);
      const attempt = Number(run.run_attempt) || 1;
      const oldAttempt = Number(old?.run_attempt) || 1;
      if (old && (attempt < oldAttempt || (attempt === oldAttempt &&
          ((old.status === "completed" && run.status !== "completed") ||
           (Date.parse(run.updated_at || "") || 0) < (Date.parse(old.updated_at || "") || 0))))) continue;
      byId.set(run.id, run);
    }
    return [...byId.values()].sort((a, b) => (Date.parse(b.created_at || "") || 0) - (Date.parse(a.created_at || "") || 0));
  }

  function nextDelay(startedAt, interval, now, blockedUntil = 0) {
    return Math.max(1000, startedAt + interval - now, blockedUntil - now);
  }

  return { Lease, timeoutError, mergeRuns, nextDelay };
});
