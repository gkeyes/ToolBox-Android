const cancelled = () => Object.assign(new Error("图片加载已取消。"), { code: "CANCELLED" });

// Only disposable image Blobs live here; article records never enter this cache.
export function createImageCache({
  load,
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
  let epoch = 0;
  let expiryTimer = null;

  function forget(entry, error = cancelled()) {
    entry.invalidated = true;
    entry.controller?.abort();
    entry.blob = null;
    if (bySource.get(entry.source) === entry) bySource.delete(entry.source);
    idle.delete(entry);
    entries.delete(entry);
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
    const check = () => {
      if (entry.epoch !== epoch || entry.invalidated || !entry.references) throw cancelled();
    };
    let blob;
    try {
      blob = await load(entry.source, check, entry.controller.signal);
      check();
      entry.blob = blob;
      entry.blobUrl = createUrl(blob);
      entry.bytes = blob.size;
      byBlob.set(entry.blobUrl, entry);
      entry.settled = true;
      entry.resolve(entry.blobUrl);
    } catch (error) {
      forget(entry, error);
    }
  }

  function lease(entry) {
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
        entry = { source, epoch, references: 0, blob: null, blobUrl: null, bytes: 0, settled: false, invalidated: false, controller: new AbortController() };
        entry.promise = new Promise((resolve, reject) => { entry.resolve = resolve; entry.reject = reject; });
        const handle = lease(entry);
        entries.add(entry);
        bySource.set(source, entry);
        // The shared media transport owns network admission and cancellation.
        start(entry);
        return handle;
      }
      return lease(entry);
    },
    evictIdle() {
      for (const entry of idle.keys()) forget(entry);
      scheduleExpiry();
    },
    clear() {
      epoch += 1;
      // Account teardown invalidates every lease, including late native replies.
      for (const entry of entries) forget(entry);
      scheduleExpiry();
    },
  };
}
