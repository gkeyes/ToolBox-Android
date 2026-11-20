let nextTaskId = 0;
const MAX_PREPARED_OPERATIONS = 512;
const MAX_PREPARED_TEXT = 128 * 1024;

function operationTextLength(value) {
  if (typeof value === "string") return value.length;
  if (!value || typeof value !== "object") return 0;
  return Object.values(value).reduce((length, field) => length + operationTextLength(field), 0);
}

// All DOM work is bounded by both a time budget and an operation cap. Waiting
// for the next pull until this batch is mounted gives the worker backpressure.
export function startProgressiveReading(options, {
  createWorker = () => new Worker(new URL("./worker.js", import.meta.url), { type: "module" }),
  schedule = (callback) => requestAnimationFrame(callback),
  unschedule = (handle) => cancelAnimationFrame(handle),
  now = () => performance.now(),
} = {}) {
  const adopted = options.prepared?.take(options);
  if (adopted) return adopted;

  const { html, baseUrl, title, retainPrefix } = options;
  const id = ++nextTaskId;
  let consumer = options, owner = {}, metadata, completed = false;
  let prefix = retainPrefix ? [] : null;
  let prefixText = 0;
  let worker, frame, cancelled = false, paused = false, operations = [], cursor = 0, done = false;
  const fail = (message) => {
    if (cancelled) return;
    const { onError } = consumer;
    cancel();
    onError(new Error(message || "正文加载失败，请返回文章列表后重试，或复制文章链接阅读。"));
  };
  const mount = () => {
    frame = null;
    if (cancelled) return;
    const { apply, onBatch, onDone, shouldPause, maxOperations = 48 } = consumer;
    const start = now();
    const operationLimit = typeof maxOperations === "function" ? maxOperations() : maxOperations;
    let count = 0;
    try {
      while (cursor < operations.length && count < operationLimit && (count === 0 || now() - start < 4)) {
        const operation = operations[cursor++];
        apply(operation);
        if (prefix) {
          prefixText += operationTextLength(operation);
          if (prefix.length >= MAX_PREPARED_OPERATIONS || prefixText > MAX_PREPARED_TEXT) prefix = null;
          else prefix.push(operation);
        }
        count += 1;
      }
      onBatch?.();
      if (cursor === operations.length && done) {
        closeWorker();
        operations = [];
        cursor = 0;
        completed = true;
        onDone?.();
      }
      else if (shouldPause?.()) paused = true;
      else if (cursor < operations.length) frame = schedule(mount);
      else { operations = []; cursor = 0; worker.postMessage({ type: "reading:next", id }); }
    } catch { fail(); }
  };
  let firstBatch = true;
  const message = ({ data }) => {
    if (cancelled || data.id !== id) return;
    if (data.type === "reading:error") { fail(data.message); return; }
    if (data.type !== "reading:batch") return;
    if (data.metadata) {
      metadata = data.metadata;
      consumer.onMetadata?.(metadata);
    }
    operations = data.operations;
    cursor = 0;
    done = data.done;
    const mountNow = firstBatch && consumer.immediateFirstBatch && !consumer.shouldPause?.();
    firstBatch = false;
    if (mountNow) mount();
    else frame = schedule(mount);
  };
  function closeWorker() {
    worker?.removeEventListener("message", message);
    worker?.removeEventListener("error", failed);
    worker?.removeEventListener("messageerror", failed);
    worker?.terminate();
    worker = null;
  }
  function cancel() {
    cancelled = true;
    if (frame != null) unschedule(frame);
    closeWorker();
    operations = [];
    prefix = null;
    metadata = null;
    consumer = null;
  }
  const failed = (event) => { event.preventDefault?.(); fail(); };
  function requestFor(requestOwner) {
    const transferable = () => requestOwner === owner && !cancelled && prefix && (paused || completed);
    return {
      id,
      cancel() { if (requestOwner === owner) cancel(); },
      resume() {
        if (requestOwner !== owner || cancelled || !paused || consumer.shouldPause?.()) return;
        paused = false;
        frame = schedule(mount);
      },
      prepareHandoff() {
        if (!transferable()) return null;
        return { take(nextConsumer) {
          if (!transferable()) return null;
          // A paused batch has not requested its successor. Replay what is
          // already visible, then drain its remaining operations in order.
          operations = prefix.concat(operations.slice(cursor));
          cursor = 0;
          prefix = null;
          paused = false;
          completed = false;
          owner = {};
          consumer = nextConsumer;
          if (metadata) consumer.onMetadata?.(metadata);
          frame = schedule(mount);
          return requestFor(owner);
        } };
      },
    };
  }
  try {
    worker = createWorker();
    worker.addEventListener("message", message);
    worker.addEventListener("error", failed);
    worker.addEventListener("messageerror", failed);
    worker.postMessage({ type: "reading:start", id, html, baseUrl, title });
  } catch { fail(); }
  return requestFor(owner);
}

export function createHighlightQueue({
  createWorker = () => new Worker(new URL("../lib/highlight-worker.js", import.meta.url), { type: "module" }),
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
