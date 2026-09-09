// Installed unchanged in both production builds. No source-module replacement,
// clock acceleration, screenshots, tracing, CPU throttle or synthetic state edit.
export function installPerformanceInstrumentation() {
  const state = {
    longTasks: [], fontWalks: [], fontNodes: 0, cacheCompletions: [],
    reading: null, action: null, pendingClick: null,
    longTaskSupported: PerformanceObserver.supportedEntryTypes.includes("longtask"),
  };
  window.__nextfluxPerf = state;
  const collectLongTasks = (entries) => {
    for (const entry of entries) state.longTasks.push({ startTime: entry.startTime, duration: entry.duration, name: entry.name });
  };
  const longTaskObserver = state.longTaskSupported ? new PerformanceObserver((list) => collectLongTasks(list.getEntries())) : null;
  longTaskObserver?.observe({ type: "longtask", buffered: true });

  const createTreeWalker = Document.prototype.createTreeWalker;
  Document.prototype.createTreeWalker = function (root, whatToShow, ...args) {
    const walker = createTreeWalker.call(this, root, whatToShow, ...args);
    if (whatToShow !== NodeFilter.SHOW_TEXT || !root?.matches?.(".article-title, .article-content")) return walker;
    const record = { startTime: performance.now(), root: root.matches(".article-title") ? "title" : "content", visitedNodes: 0 };
    state.fontWalks.push(record);
    const nextNode = walker.nextNode.bind(walker);
    walker.nextNode = () => {
      const node = nextNode();
      if (node) { record.visitedNodes += 1; state.fontNodes += 1; }
      return node;
    };
    return walker;
  };

  // Call only once after app readiness: fixture init-script ordering is not
  // assumed. The production Worker still sends and resolves every operation.
  state.observeFixture = () => {
    const results = window.__nextfluxTest.results;
    const push = results.push.bind(results);
    results.push = (...items) => {
      for (const item of items) state.cacheCompletions.push({ id: item.id, method: item.method, ok: item.ok, at: performance.now() });
      return push(...items);
    };
  };
  state.snapshot = (name) => ({
    name, at: performance.now(), fontNodes: state.fontNodes,
    storageIndex: window.__nextfluxTest.storageCalls.length,
    resultIndex: state.cacheCompletions.length,
    networkIndex: window.__nextfluxTest.network.length,
    readingRequestIndex: window.__nextfluxTest.readingRequests?.length || 0,
    highlightRequestIndex: window.__nextfluxTest.highlightRequests?.length || 0,
  });
  state.finish = (start, endAt = performance.now()) => {
    collectLongTasks(longTaskObserver?.takeRecords() || []);
    const longTasks = state.longTasks.filter((entry) => entry.startTime < endAt && entry.startTime + entry.duration > start.at);
    const storage = window.__nextfluxTest.storageCalls.slice(start.storageIndex);
    return {
      name: start.name, startedAt: start.at, endedAt: endAt, elapsedMs: endAt - start.at,
      longTaskCount: longTasks.length,
      longTaskTotalMs: longTasks.reduce((sum, entry) => sum + entry.duration, 0),
      longTaskMaxMs: Math.max(0, ...longTasks.map((entry) => entry.duration)),
      longTasks, fontScanNodes: state.fontNodes - start.fontNodes,
      fontScans: state.fontWalks.filter((entry) => entry.startTime >= start.at && entry.startTime <= endAt),
      storageApplyCalls: storage.filter((call) => call.method === "apply").length,
      storageGetManyCalls: storage.filter((call) => call.method === "getMany").length,
      storageCalls: storage,
      cacheCompletions: state.cacheCompletions.slice(start.resultIndex),
      network: window.__nextfluxTest.network.slice(start.networkIndex),
      readingWorkerRequests: (window.__nextfluxTest.readingRequests?.length || 0) - start.readingRequestIndex,
      highlightWorkerRequests: (window.__nextfluxTest.highlightRequests?.length || 0) - start.highlightRequestIndex,
    };
  };

  let readingObserver;
  let queued = false;
  const inspectReading = () => {
    queued = false;
    const reading = state.reading;
    if (!reading?.start || reading.bodyCompleteAt !== null) return;
    const content = document.querySelector(".article-content");
    const first = content?.querySelector("p");
    const viewport = document.querySelector(".article-scroll-area");
    if (!content || !first || !viewport || !first.textContent.includes(reading.expected.first)) return;
    const rect = first.getBoundingClientRect();
    const clip = viewport.getBoundingClientRect();
    if (reading.firstReadableAt === null && rect.height > 0 && rect.bottom > clip.top && rect.top < Math.min(clip.bottom, innerHeight)) {
      reading.firstReadableAt = performance.now();
    }
    const paragraphs = content.querySelectorAll("p");
    if (paragraphs.length === reading.expected.paragraphCount &&
        paragraphs[paragraphs.length - 1]?.textContent === reading.expected.last &&
        content.querySelectorAll(".code-block pre:not([hidden]) code").length === reading.expected.codeBlockCount) {
      reading.bodyCompleteAt = performance.now();
      readingObserver.disconnect();
    }
  };
  const scheduleReadingInspection = () => {
    if (queued) return;
    queued = true;
    // DOM commit followed by two animation frames is a paint opportunity proxy,
    // not browser FCP, a screenshot, a pixel assertion or physical-device input.
    requestAnimationFrame(() => requestAnimationFrame(inspectReading));
  };
  state.armReading = (expected) => {
    readingObserver?.disconnect();
    state.reading = { expected, start: null, firstReadableAt: null, bodyCompleteAt: null };
    state.pendingClick = { type: "reading", id: expected.id };
    readingObserver = new MutationObserver(scheduleReadingInspection);
    readingObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
  };
  let actionObserver;
  state.armAction = (selector, text = null, expectedUiSelector = null) => {
    actionObserver?.disconnect();
    state.action = null;
    state.pendingClick = { type: "action", selector, text, expectedUiSelector };
  };
  // Start at primary pointerdown: React Aria onPress may run on pointerup,
  // before DOM click capture. Starting at click could miss the actual write.
  document.addEventListener("pointerdown", (event) => {
    if (!event.isTrusted || event.button !== 0) return;
    const pending = state.pendingClick;
    if (!pending) return;
    const element = event.target instanceof Element ? event.target : event.target?.parentElement;
    if (pending.type === "reading" && element?.closest(`[data-article-id="${pending.id}"]`)) {
      state.reading.start = state.snapshot("article-open");
      state.pendingClick = null;
      scheduleReadingInspection();
    } else if (pending.type === "action") {
      const target = element?.closest(pending.selector);
      if (target && (pending.text === null || target.textContent.trim() === pending.text)) {
        state.action = state.snapshot("single-state-operation");
        state.action.uiPaintAt = null;
        if (pending.expectedUiSelector) {
          const observeUi = () => requestAnimationFrame(() => requestAnimationFrame(() => {
            if (state.action.uiPaintAt === null && document.querySelector(pending.expectedUiSelector)) {
              state.action.uiPaintAt = performance.now();
              actionObserver.disconnect();
            }
          }));
          actionObserver = new MutationObserver(observeUi);
          actionObserver.observe(document.body, { attributes: true, childList: true, subtree: true });
          observeUi();
        }
        state.pendingClick = null;
      }
    }
  }, true);
}
