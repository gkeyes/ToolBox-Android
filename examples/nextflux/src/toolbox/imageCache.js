const cancelled = () => Object.assign(new Error("图片加载已取消。"), { code: "CANCELLED" });

// Only disposable image Blobs live here; article records never enter this cache.
export function createImageCache({
  load,
  maxActive = 24,
  maxConcurrent = 3,
  maxIdle = 12,
  maxIdleBytes = 8 * 1024 * 1024,
  idleMs = 30000,
  now = () => Date.now(),
  setTimer = (callback, delay) => setTimeout(callback, delay),
  clearTimer = (timer) => clearTimeout(timer),
  createUrl = (blob) => URL.createObjectURL(blob),
  revokeUrl = (url) => URL.revokeObjectURL(url),
}) {
  const bySource = new Map();
  const byBlob = new Map();
  const entries = new Set();
  const idle = new Map();
  const queue = [];
  let running = 0;
  let epoch = 0;
  let expiryTimer = null;

  function forget(entry, error = cancelled()) {
    entry.invalidated = true;
    if (bySource.get(entry.source) === entry) bySource.delete(entry.source);
    idle.delete(entry);
    entries.delete(entry);
    const queuedIndex = queue.indexOf(entry);
    if (queuedIndex >= 0) queue.splice(queuedIndex, 1);
    if (entry.blobUrl) {
      byBlob.delete(entry.blobUrl);
      revokeUrl(entry.blobUrl);
      entry.blobUrl = null;
    }
    if (!entry.settled) {
      entry.settled = true;
      entry.reject(error);
    }
  }

  function scheduleExpiry() {
    if (expiryTimer !== null) clearTimer(expiryTimer);
    expiryTimer = null;
    const oldest = idle.values().next().value;
    if (oldest !== undefined) {
      expiryTimer = setTimer(trimIdle, Math.max(0, oldest + idleMs - now()));
      // Browser timers are numbers; Node verification should not wait 30 seconds.
      expiryTimer?.unref?.();
    }
  }

  function trimIdle() {
    let bytes = 0;
    for (const entry of idle.keys()) bytes += entry.bytes;
    const cutoff = now() - idleMs;
    for (const [entry, releasedAt] of idle) {
      if (releasedAt > cutoff && idle.size <= maxIdle && bytes <= maxIdleBytes) break;
      bytes -= entry.bytes;
      forget(entry);
    }
    scheduleExpiry();
  }

  async function start(entry) {
    running += 1;
    const check = () => {
      if (entry.epoch !== epoch || entry.invalidated || !entry.references) throw cancelled();
    };
    try {
      const blob = await load(entry.source, check);
      check();
      entry.blobUrl = createUrl(blob);
      entry.bytes = blob.size;
      byBlob.set(entry.blobUrl, entry);
      entry.settled = true;
      entry.resolve(entry.blobUrl);
    } catch (error) {
      forget(entry, error);
    } finally {
      running -= 1;
      drain();
    }
  }

  function drain() {
    while (running < maxConcurrent && queue.length) start(queue.shift());
  }

  function lease(entry) {
    if (!entry.references) {
      let active = 0;
      for (const value of entries) if (value.references) active += 1;
      if (active >= maxActive) throw new Error("当前显示的图片较多，请滚动后重试。");
    }
    entry.references += 1;
    idle.delete(entry);
    scheduleExpiry();
    let released = false;
    return {
      url: entry.blobUrl,
      promise: entry.promise,
      invalidate() {
        // A decode failure may have other viewers. Retire it without revoking
        // their lease, and let a retry create a fresh entry for the same source.
        entry.invalidated = true;
        if (bySource.get(entry.source) === entry) bySource.delete(entry.source);
        if (!entry.references) forget(entry);
      },
      release() {
        if (released) return;
        released = true;
        entry.references -= 1;
        if (entry.references) return;
        if (entry.invalidated || !entry.blobUrl) forget(entry);
        else {
          idle.set(entry, now());
          trimIdle();
        }
      },
    };
  }

  return {
    hasBlob: (url) => byBlob.has(url),
    acquire(source) {
      trimIdle();
      let entry = bySource.get(source) || byBlob.get(source);
      if (entry?.invalidated) throw cancelled();
      if (!entry) {
        if (source.startsWith("blob:")) throw cancelled();
        entry = { source, epoch, references: 0, blobUrl: null, bytes: 0, settled: false, invalidated: false };
        entry.promise = new Promise((resolve, reject) => { entry.resolve = resolve; entry.reject = reject; });
        // Admission precedes registration, so a refused image leaves no work.
        const handle = lease(entry);
        entries.add(entry);
        bySource.set(source, entry);
        queue.push(entry);
        drain();
        return handle;
      }
      return lease(entry);
    },
    clear() {
      epoch += 1;
      // Account teardown invalidates every lease, including late native replies.
      for (const entry of entries) forget(entry);
      scheduleExpiry();
    },
  };
}
