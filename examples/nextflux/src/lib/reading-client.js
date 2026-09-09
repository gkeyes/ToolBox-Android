let nextTaskId = 0;

// All DOM work is bounded by both a time budget and an operation cap. Waiting
// for the next pull until this batch is mounted gives the worker backpressure.
export function startProgressiveReading({ html, baseUrl, apply, onBatch, onDone, onError }, {
  createWorker = () => new Worker(new URL("./reading-worker.js", import.meta.url), { type: "module" }),
  schedule = (callback) => requestAnimationFrame(callback),
  unschedule = (handle) => cancelAnimationFrame(handle),
  now = () => performance.now(),
} = {}) {
  const id = ++nextTaskId;
  let worker, frame, cancelled = false, operations = [], cursor = 0, done = false;
  const fail = (message) => {
    if (cancelled) return;
    cancel();
    onError(new Error(message || "正文加载失败，请返回文章列表后重试，或复制文章链接阅读。"));
  };
  const mount = () => {
    frame = null;
    if (cancelled) return;
    const start = now();
    let count = 0;
    try {
      while (cursor < operations.length && count < 64 && (count === 0 || now() - start < 8)) {
        apply(operations[cursor++]);
        count += 1;
      }
      onBatch?.();
      if (cursor < operations.length) frame = schedule(mount);
      else if (done) { worker.terminate(); onDone?.(); }
      else { operations = []; cursor = 0; worker.postMessage({ type: "reading:next", id }); }
    } catch { fail(); }
  };
  const message = ({ data }) => {
    if (cancelled || data.id !== id) return;
    if (data.type === "reading:error") { fail(data.message); return; }
    if (data.type !== "reading:batch") return;
    operations = data.operations;
    cursor = 0;
    done = data.done;
    frame = schedule(mount);
  };
  function cancel() {
    cancelled = true;
    if (frame != null) unschedule(frame);
    worker?.removeEventListener("message", message);
    worker?.removeEventListener("error", failed);
    worker?.removeEventListener("messageerror", failed);
    worker?.terminate();
    operations = [];
  }
  const failed = (event) => { event.preventDefault?.(); fail(); };
  try {
    worker = createWorker();
    worker.addEventListener("message", message);
    worker.addEventListener("error", failed);
    worker.addEventListener("messageerror", failed);
    worker.postMessage({ type: "reading:start", id, html, baseUrl });
  } catch { fail(); }
  return { id, cancel };
}

export function createHighlightQueue({
  createWorker = () => new Worker(new URL("./highlight-worker.js", import.meta.url), { type: "module" }),
  schedule = (callback) => setTimeout(callback, 0),
  unschedule = (handle) => clearTimeout(handle),
} = {}) {
  let worker, active, scheduled, disposed = false, nextId = 0;
  const pending = [];
  const pump = () => {
    scheduled = null;
    if (disposed || active) return;
    active = pending.shift();
    if (!active) return;
    try {
      if (!worker) {
        worker = createWorker();
        worker.addEventListener("message", receive);
        worker.addEventListener("error", failed);
        worker.addEventListener("messageerror", failed);
      }
      worker.postMessage({ type: "highlight:run", id: active.id, code: active.code, language: active.language });
    } catch { failed(); }
  };
  const queuePump = () => { if (!disposed && !active && scheduled == null && pending.length) scheduled = schedule(pump); };
  const receive = ({ data }) => {
    if (disposed || !active || data.id !== active.id) return;
    const task = active;
    active = null;
    if (!task.cancelled && data.type === "highlight:result") task.publish(data.html);
    queuePump();
  };
  const failed = () => { worker?.terminate(); worker = null; active = null; queuePump(); };
  return {
    enqueue(code, language, publish) {
      if (disposed) return () => {};
      const task = { id: ++nextId, code, language, publish, cancelled: false };
      pending.push(task);
      queuePump();
      return () => {
        task.cancelled = true;
        const index = pending.indexOf(task);
        if (index >= 0) pending.splice(index, 1);
      };
    },
    dispose() {
      disposed = true;
      if (scheduled != null) unschedule(scheduled);
      pending.length = 0;
      active = null;
      worker?.terminate();
    },
  };
}

// Do not even construct a Shiki worker for offscreen blocks. Leaving the near
// viewport cancels queued work; an in-flight result is discarded by its lease.
export function observeCodeVisibility(element, enqueue, Observer = globalThis.IntersectionObserver) {
  let cancel, completed = false;
  if (!Observer) return () => {}; // readable plaintext remains a safe fallback
  const observer = new Observer((entries) => {
    for (const entry of entries) {
      if (entry.target !== element || completed) continue;
      if (entry.isIntersecting && !cancel) {
        cancel = enqueue(() => { completed = true; observer.disconnect(); });
      } else if (!entry.isIntersecting && cancel) { cancel(); cancel = null; }
    }
  }, { root: element.closest(".article-scroll-area"), rootMargin: "600px 0px" });
  observer.observe(element);
  return () => { observer.disconnect(); cancel?.(); };
}
