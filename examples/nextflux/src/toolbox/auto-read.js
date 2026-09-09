// One instance belongs to the current account. Invalidation releases every
// waiter, including batches already admitted to the account operation queue.
export function createAutoReadQueue({
  run, selectIds, send, save,
  schedule = (task, delay) => setTimeout(task, delay),
  unschedule = (timer) => clearTimeout(timer),
  delayMs = 75, batchSize = 256,
}) {
  const entries = new Map();
  const pending = new Map();
  let timer = null;

  const clearTimer = () => {
    if (timer !== null) unschedule(timer);
    timer = null;
  };
  const isCurrent = (entry) => entries.get(entry.id) === entry;
  function finish(entry, failure) {
    if (!isCurrent(entry)) return;
    entries.delete(entry.id);
    pending.delete(entry.id);
    if (failure) entry.reject(failure);
    else entry.resolve();
  }

  function flush() {
    clearTimer();
    const batch = [...pending.values()].slice(0, batchSize);
    if (!batch.length) return;
    for (const entry of batch) {
      pending.delete(entry.id);
      entry.phase = "queued";
    }
    const operation = run(async (check) => {
      const selected = batch.filter(isCurrent);
      if (!selected.length) return;
      const eligible = new Set(await selectIds(selected.map((entry) => entry.id)));
      check();
      const sending = selected.filter((entry) => isCurrent(entry) && eligible.has(entry.id));
      if (!sending.length) return;
      const ids = sending.map((entry) => entry.id);
      const requestCheck = () => {
        check();
        // Admission throttling may leave a request unsent while the user
        // changes its intent. Mutate the original array before a retry builds
        // its payload, retaining only entries still owned by this batch.
        ids.splice(0, ids.length, ...sending.filter(isCurrent).map((entry) => entry.id));
        return ids.length > 0;
      };
      requestCheck.beforeRequest = () => {
        if (!requestCheck()) return false;
        for (const entry of sending.filter(isCurrent)) entry.phase = "sent";
      };
      requestCheck.onRateLimited = () => {
        for (const entry of sending.filter(isCurrent)) {
          // A manual action may arrive while native admission is pending. If
          // it was rejected, that automatic request was never sent after all.
          if (entry.cancelAfterRejection) finish(entry);
          else entry.phase = "queued";
        }
      };
      await send(ids, requestCheck);
      await save(ids.map((id) => ({ id, status: "read" })), check);
    }, { priority: "auto" });
    operation.then(
      () => batch.forEach((entry) => finish(entry)),
      (failure) => batch.forEach((entry) => finish(entry, failure)),
    );
    if (pending.size) timer = schedule(flush, delayMs);
  }

  return {
    enqueue(value) {
      const id = Number(value);
      if (!Number.isSafeInteger(id) || id <= 0) return Promise.reject(new Error("文章编号无效，请刷新后重试。"));
      const existing = entries.get(id);
      if (existing) return existing.promise;
      let resolve, reject;
      const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
      const entry = { id, phase: "pending", promise, resolve, reject };
      entries.set(id, entry);
      pending.set(id, entry);
      if (pending.size >= batchSize) flush();
      else if (timer === null) timer = schedule(flush, delayMs);
      return promise;
    },
    cancelUnsent(value) {
      const entry = entries.get(Number(value));
      if (entry?.phase === "sent") entry.cancelAfterRejection = true;
      else if (entry) finish(entry);
      if (!pending.size) clearTimer();
    },
    cancelAll(failure) {
      clearTimer();
      for (const entry of entries.values()) finish(entry, failure);
    },
  };
}
