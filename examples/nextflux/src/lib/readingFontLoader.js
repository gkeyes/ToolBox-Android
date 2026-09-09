import { getFontConfig, loadFont } from "./fontLoader.js";

export const FONT_SCAN_LIMITS = Object.freeze({ characters: 2048, nodes: 64, milliseconds: 4 });
const BLOCK_SELECTOR = "[data-font-block]";
const ROOT_SELECTOR = "[data-font-reading-root]";
const EXCLUDED_SELECTOR = "pre, code, script, style";

function deferScan(callback) {
  if (typeof globalThis.requestIdleCallback === "function") {
    const id = globalThis.requestIdleCallback(callback, { timeout: 100 });
    return () => globalThis.cancelIdleCallback(id);
  }
  const id = setTimeout(callback, 16);
  return () => clearTimeout(id);
}

// The article renderer appends nodes in document order and reports only the
// blocks it changed. Retain each cursor's position, including the final text
// offset, so a later batch does not revisit the beginning of a long paragraph.
function createBlockCursor(block) {
  return { node: block, offset: 0, descend: true, climb: null, done: false };
}

// Take one DOM step at a time. A rejecting TreeWalker filter can otherwise
// visit thousands of nested blocks inside a single nextNode() call.
function advanceBlockCursor(cursor, block) {
  const move = (node) => {
    cursor.node = node;
    cursor.offset = 0;
    cursor.descend = true;
    cursor.climb = null;
  };
  if (!cursor.climb && cursor.descend) {
    cursor.descend = false;
    const node = cursor.node;
    if (node.nodeType === 1 && (node === block || (!node.matches(EXCLUDED_SELECTOR) && !node.matches(BLOCK_SELECTOR))) && node.firstChild) {
      move(node.firstChild);
      return true;
    }
  }
  const current = cursor.climb || cursor.node;
  if (current === block) return false;
  if (current.nextSibling) { move(current.nextSibling); return true; }
  if (!current.parentNode || current.parentNode === block) { cursor.climb = null; return false; }
  cursor.climb = current.parentNode;
  return true;
}

export function startReadingFontLoader(family, {
  articleId,
  document = globalThis.document,
  window = globalThis.window,
  load = loadFont,
  onError = () => {},
  scheduleTask = deferScan,
  now = () => performance.now(),
} = {}) {
  // System/local/unknown fonts must exit before looking up any reading DOM,
  // creating a walker, or installing an observer or event listener.
  if (!getFontConfig(family) || articleId == null || !document || !window) return () => {};

  let stopped = false;
  let root = null;
  let title = null;
  let observer = null;
  let controller = null;
  let cancelTask = null;
  let discovery = null;
  let busy = false;
  let failed = false;
  const states = new Map();
  const toObserve = new Set();
  const readable = new Set();
  const pending = new Set();
  // One bit per Unicode code point bounds deduplication memory at 136 KiB,
  // independent of article length. Characters remain local to subset selection.
  const seen = new Uint8Array(Math.ceil(0x110000 / 8));
  const alreadySeen = (point) => Boolean(seen[point >> 3] & (1 << (point & 7)));
  const belongs = (block) => block === title || (root && (block === root || root.contains(block)));

  function schedule() {
    if (!stopped && root && !cancelTask) cancelTask = scheduleTask(() => { cancelTask = null; tick(); });
  }

  function disposeRoot() {
    controller?.abort();
    observer?.disconnect();
    cancelTask?.();
    controller = null;
    observer = null;
    cancelTask = null;
    discovery = null;
    root = null;
    title = null;
    busy = false;
    failed = false;
    states.clear();
    toObserve.clear();
    readable.clear();
    pending.clear();
    seen.fill(0);
  }

  function changed(block) {
    if (!observer || !belongs(block) || block.closest(EXCLUDED_SELECTOR)) return;
    const state = states.get(block);
    if (!state) toObserve.add(block);
    else {
      if (state.cursor) { state.cursor.done = false; state.cursor.descend = true; state.cursor.climb = null; }
      if (state.near) readable.add(block);
    }
  }

  function attach(nextRoot, nextTitle) {
    disposeRoot();
    root = nextRoot;
    title = nextTitle || null;
    controller = new AbortController();
    for (const char of "Aa 中文") pending.add(char);
    if (typeof window.IntersectionObserver === "function") {
      const activeController = controller;
      observer = new window.IntersectionObserver((entries) => {
        if (stopped || controller !== activeController || activeController.signal.aborted) return;
        for (const entry of entries) {
          if (!belongs(entry.target)) { observer.unobserve(entry.target); states.delete(entry.target); readable.delete(entry.target); continue; }
          const state = states.get(entry.target);
          if (!state) continue;
          state.near = entry.isIntersecting;
          if (state.near && !state.cursor?.done) readable.add(entry.target);
          else readable.delete(entry.target);
        }
        schedule();
      }, { root: root.closest(".article-scroll-area"), rootMargin: "500px 0px", threshold: 0 });
      // Discovery is only needed when an online font is selected after content
      // has already mounted. Later renderer batches use their explicit blocks.
      discovery = document.createTreeWalker(root, 0xffffffff);
      if (root.matches(BLOCK_SELECTOR)) changed(root);
      if (title) changed(title);
    }
    schedule();
  }

  async function hydrate() {
    if (stopped || busy || failed || !pending.size || !controller) return;
    const activeController = controller;
    const characters = [...pending];
    busy = true;
    try {
      await load(family, characters.join(""), { signal: activeController.signal });
      if (stopped || activeController !== controller || activeController.signal.aborted) return;
      for (const char of characters) {
        const point = char.codePointAt(0);
        seen[point >> 3] |= 1 << (point & 7);
        pending.delete(char);
      }
    } catch (error) {
      if (stopped || activeController !== controller || activeController.signal.aborted || error.name === "AbortError") return;
      failed = true;
      onError(error);
    } finally {
      if (!stopped && activeController === controller && !activeController.signal.aborted) {
        busy = false;
        if (!failed && (pending.size || readable.size || discovery || toObserve.size)) schedule();
      }
    }
  }

  function tick() {
    if (stopped || !root) return;
    if (!root.isConnected) { disposeRoot(); return; }
    const deadline = now() + FONT_SCAN_LIMITS.milliseconds;
    let nodes = 0;
    let characters = 0;
    // Leave half the node budget for already-visible text while later blocks
    // are still being registered. Discovery must not delay the reading viewport.
    while (observer && nodes < FONT_SCAN_LIMITS.nodes / 2 && now() < deadline && (toObserve.size || discovery)) {
      let block;
      if (toObserve.size) {
        block = toObserve.values().next().value;
        toObserve.delete(block);
      } else {
        block = discovery.nextNode();
        if (!block) { discovery = null; break; }
        if (block.nodeType !== 1 || !block.matches(BLOCK_SELECTOR)) { nodes += 1; continue; }
      }
      nodes += 1;
      if (!belongs(block) || block.closest(EXCLUDED_SELECTOR) || states.has(block)) continue;
      states.set(block, { near: false, cursor: null });
      observer.observe(block);
    }
    if (!busy && !failed) {
      for (const block of readable) {
        if (nodes >= FONT_SCAN_LIMITS.nodes || characters >= FONT_SCAN_LIMITS.characters || pending.size >= FONT_SCAN_LIMITS.characters || now() >= deadline) break;
        if (!belongs(block)) { readable.delete(block); observer?.unobserve(block); states.delete(block); continue; }
        const state = states.get(block);
        const cursor = state.cursor ||= createBlockCursor(block);
        while (nodes < FONT_SCAN_LIMITS.nodes && characters < FONT_SCAN_LIMITS.characters && pending.size < FONT_SCAN_LIMITS.characters && now() < deadline) {
          // Recheck the final text node when a producer appended more text to it.
          if (cursor.node?.nodeType === 3 && cursor.offset < cursor.node.length) {
            const value = cursor.node.nodeValue;
            while (cursor.offset < value.length && characters < FONT_SCAN_LIMITS.characters && pending.size < FONT_SCAN_LIMITS.characters) {
              const point = value.codePointAt(cursor.offset);
              const char = String.fromCodePoint(point);
              cursor.offset += char.length;
              characters += 1;
              if (!alreadySeen(point)) pending.add(char);
            }
            if (cursor.offset < value.length) break;
          }
          const advanced = advanceBlockCursor(cursor, block);
          nodes += 1;
          if (!advanced) { cursor.done = true; readable.delete(block); break; }
        }
      }
      if (pending.size) void hydrate();
    }
    if (discovery || toObserve.size || (!busy && !failed && readable.size)) schedule();
  }

  function contentChanged(event) {
    const detail = event.detail;
    const nextRoot = detail?.root;
    if (!nextRoot || nextRoot.getAttribute("data-font-article-id") !== String(articleId)) return;
    if (detail.removed) {
      if (nextRoot === root) disposeRoot();
      return;
    }
    if (!nextRoot.isConnected) return;
    if (nextRoot !== root || detail.reset) attach(nextRoot, detail.title);
    for (const block of detail.blocks || []) changed(block);
    schedule();
  }

  function retry() {
    if (!root) return;
    failed = false;
    schedule();
  }

  window.addEventListener("nextflux:reading-content", contentChanged);
  window.addEventListener("nextflux:retry-font", retry);
  for (const candidate of document.querySelectorAll(ROOT_SELECTOR)) {
    if (candidate.getAttribute("data-font-article-id") === String(articleId)) {
      attach(candidate, candidate.closest(".article-view-content")?.querySelector(".article-title"));
      break;
    }
  }
  return () => {
    stopped = true;
    disposeRoot();
    window.removeEventListener("nextflux:reading-content", contentChanged);
    window.removeEventListener("nextflux:retry-font", retry);
  };
}
