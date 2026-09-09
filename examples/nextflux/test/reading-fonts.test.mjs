import test from "node:test";
import assert from "node:assert/strict";
import { FONT_SCAN_LIMITS, startReadingFontLoader } from "../src/lib/readingFontLoader.js";

// A deterministic DOM/observer/scheduler boundary for the production controller.
// Browser tests cover the real renderer; these tests never use layout guesses.
function harness(articleId = "1") {
  const counters = { walkers: 0, queries: 0, reads: 0, links: 0 };
  const listeners = new Map();
  const tasks = new Map();
  const observers = [];
  let nextTask = 0;
  class Element {
    nodeType = 1;
    childNodes = [];
    parentElement = null;
    isConnected = true;
    constructor(tag = "p", attrs = {}) { this.tag = tag; this.attrs = attrs; }
    get firstChild() { counters.links += 1; return this.childNodes[0] || null; }
    get parentNode() { counters.links += 1; return this.parentElement; }
    get nextSibling() { counters.links += 1; return this.parentElement?.childNodes[this.parentElement.childNodes.indexOf(this) + 1] || null; }
    append(node) { node.parentElement = this; this.childNodes.push(node); return node; }
    matches(selector) {
      if (selector.startsWith("[")) return Object.hasOwn(this.attrs, selector.slice(1, -1));
      if (selector.startsWith(".")) return this.attrs.class?.split(" ").includes(selector.slice(1)) || false;
      return selector.split(",").some((name) => name.trim() === this.tag);
    }
    closest(selector) { let node = this; while (node) { if (node.matches(selector)) return node; node = node.parentElement; } return null; }
    contains(node) { while (node) { if (node === this) return true; node = node.parentElement; } return false; }
    getAttribute(name) { return this.attrs[name] ?? null; }
    querySelector(selector) {
      for (const child of this.childNodes) {
        if (child.nodeType === 1 && child.matches(selector)) return child;
        const found = child.querySelector?.(selector);
        if (found) return found;
      }
      return null;
    }
    get textContent() { throw new Error("Do not concatenate an entire reading block"); }
    getBoundingClientRect() { throw new Error("Font scanning must not measure text nodes or blocks"); }
  }
  class Text {
    nodeType = 3;
    childNodes = [];
    parentElement = null;
    reads = 0;
    constructor(value) { this.value = value; }
    get firstChild() { return null; }
    get parentNode() { return this.parentElement; }
    get nextSibling() { return this.parentElement?.childNodes[this.parentElement.childNodes.indexOf(this) + 1] || null; }
    get length() { return this.value.length; }
    get nodeValue() { counters.reads += 1; this.reads += 1; return this.value; }
  }
  const scroll = new Element("div", { class: "article-scroll-area" });
  const view = scroll.append(new Element("div", { class: "article-view-content" }));
  const title = view.append(new Element("h1", { class: "article-title", "data-font-block": "" }));
  title.append(new Text("Title"));
  const root = view.append(new Element("div", { "data-font-reading-root": "", "data-font-article-id": articleId }));
  const document = {
    querySelectorAll() { counters.queries += 1; return [root]; },
    createTreeWalker(scope, show, filter) {
      counters.walkers += 1;
      const afterSubtree = (node) => {
        while (node !== scope) {
          const parent = node.parentElement;
          const index = parent.childNodes.indexOf(node);
          if (index + 1 < parent.childNodes.length) return parent.childNodes[index + 1];
          node = parent;
        }
        return null;
      };
      return {
        currentNode: scope,
        nextNode() {
          let candidate = this.currentNode.childNodes[0] || afterSubtree(this.currentNode);
          while (candidate) {
            const accepted = filter?.acceptNode(candidate) ?? 1;
            if (accepted === 2) candidate = afterSubtree(candidate);
            else if (show === 1 && candidate.nodeType !== 1) candidate = candidate.childNodes[0] || afterSubtree(candidate);
            else { this.currentNode = candidate; return candidate; }
          }
          return null;
        },
      };
    },
  };
  const window = {
    IntersectionObserver: class {
      targets = new Set();
      disconnected = false;
      constructor(callback, options) { Object.assign(this, { callback, options }); observers.push(this); }
      observe(target) { this.targets.add(target); }
      unobserve(target) { this.targets.delete(target); }
      disconnect() { this.disconnected = true; this.targets.clear(); }
      intersect(target, isIntersecting = true) { this.callback([{ target, isIntersecting }]); }
    },
    addEventListener(name, callback) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(callback); },
    removeEventListener(name, callback) { listeners.get(name)?.delete(callback); },
  };
  function emit(name, detail) { for (const callback of listeners.get(name) || []) callback({ detail }); }
  const block = (text, tag = "p", parent = root) => {
    const element = parent.append(new Element(tag, { "data-font-block": "" }));
    const node = element.append(new Text(text));
    return { element, node };
  };
  return {
    root, title, scroll, block, Text, Element, counters, observers, tasks, listeners, emit,
    options: { articleId, document, window, now: () => 0, scheduleTask(callback) { const id = ++nextTask; tasks.set(id, callback); return () => tasks.delete(id); } },
    async step() {
      const first = tasks.entries().next().value;
      if (!first) return false;
      tasks.delete(first[0]); first[1]();
      // Settle the controller's async load continuation without a real timer.
      await Promise.resolve(); await Promise.resolve();
      return true;
    },
  };
}

const settle = async (h, limit = 100) => { for (let i = 0; i < limit && await h.step(); i += 1) {} };

test("system and local fonts return before DOM queries, observers, listeners or text scanning", () => {
  const forbidden = new Proxy({}, { get() { throw new Error("System font touched DOM"); } });
  for (const family of ["system-ui", "sans-serif", "serif", "monospace", "Local Installed Font", "constructor", "__proto__", ""]) {
    const stop = startReadingFontLoader(family, { articleId: 1, document: forbidden, window: forbidden });
    stop();
  }
});

test("online font reads only intersecting blocks and incrementally reads appended text", async () => {
  const h = harness();
  const near = h.block("近处汉字");
  const far = h.block("远方独有龘");
  const code = h.block("代码不读取", "pre");
  const loads = [];
  const stop = startReadingFontLoader("Inter", { ...h.options, load: async (_family, text) => loads.push(text) });
  await settle(h);
  assert.equal(h.observers.length, 1);
  assert.equal(h.observers[0].options.root, h.scroll);
  assert.equal(h.observers[0].options.rootMargin, "500px 0px");
  assert.equal(h.observers[0].targets.has(code.element), false);
  assert.equal(h.counters.reads, 0);
  h.observers[0].intersect(near.element);
  await settle(h);
  assert.match(loads.join(""), /近/);
  assert.equal(far.node.reads, 0);
  const reads = near.node.reads;
  const appended = near.element.append(new h.Text("追加𠮷"));
  h.emit("nextflux:reading-content", { root: h.root, blocks: [near.element] });
  await settle(h);
  assert.equal(near.node.reads, reads, "completed text is not reread when a later node is appended");
  assert.ok(appended.reads > 0);
  assert.match(loads.join(""), /𠮷/);
  assert.equal(h.counters.queries, 1, "incremental events do not query the document again");
  h.observers[0].intersect(far.element);
  await settle(h);
  assert.match(loads.join(""), /龘/);
  stop();
  assert.equal(h.tasks.size, 0);
  assert.equal(h.observers[0].disconnected, true);
});

test("long repeated text yields within a character budget and still reaches its tail", async () => {
  const h = harness();
  const long = h.block("a".repeat(FONT_SCAN_LIMITS.characters * 4) + "尾𠮷");
  const loads = [];
  const stop = startReadingFontLoader("Inter", { ...h.options, load: async (_family, text) => loads.push(text) });
  await settle(h);
  h.observers[0].intersect(long.element);
  await h.step();
  assert.equal(loads.join("").includes("尾"), false);
  assert.ok(h.tasks.size > 0, "unscanned characters are deferred to another task");
  await settle(h);
  assert.match(loads.join(""), /尾𠮷/);
  assert.ok(loads.every((text) => Array.from(text).length <= FONT_SCAN_LIMITS.characters));
  assert.equal(loads.join("").split("a").length - 1, 1, "already selected code points are deduplicated across slices");
  stop();
});

test("a visible parent does not scan a nested block until that block is near the viewport", async () => {
  const h = harness();
  const parent = h.block("父块");
  const nested = h.block("嵌套远处", "p", parent.element);
  const loads = [];
  const stop = startReadingFontLoader("Inter", { ...h.options, load: async (_family, text) => loads.push(text) });
  await settle(h);
  h.observers[0].intersect(parent.element);
  await settle(h);
  assert.equal(nested.node.reads, 0);
  assert.match(loads.join(""), /父/);
  h.observers[0].intersect(nested.element);
  await settle(h);
  assert.match(loads.join(""), /嵌/);
  stop();
});

test("skipping many nested blocks also yields instead of walking the entire article in one call", async () => {
  const h = harness();
  h.root.attrs["data-font-block"] = "";
  const paragraphs = Array.from({ length: 1000 }, () => h.block("还未进入邻近视口"));
  const stop = startReadingFontLoader("Inter", { ...h.options, load: async () => {} });
  await settle(h);
  h.counters.links = 0;
  h.observers[0].intersect(h.root);
  await h.step();
  assert.ok(h.counters.links < paragraphs.length, "the first scan cannot visit every nested block");
  assert.ok(h.tasks.size > 0);
  await settle(h);
  assert.ok(paragraphs.every(({ node }) => node.reads === 0));
  stop();
});

test("missing IntersectionObserver keeps the sample fallback without scanning or rescheduling forever", async () => {
  const h = harness();
  const paragraph = h.block("无需布局扫描");
  h.options.window.IntersectionObserver = undefined;
  const stop = startReadingFontLoader("Inter", { ...h.options, load: async () => {} });
  await settle(h);
  h.emit("nextflux:reading-content", { root: h.root, blocks: [paragraph.element] });
  await settle(h);
  assert.equal(h.counters.reads, 0);
  assert.equal(h.counters.walkers, 0);
  assert.equal(h.tasks.size, 0);
  stop();
});

test("failed font requests stop scheduling, preserve the pending subset and retry explicitly", async () => {
  const h = harness();
  let failed = true;
  const attempts = [];
  const errors = [];
  const stop = startReadingFontLoader("Inter", {
    ...h.options,
    load: async (_family, text) => { attempts.push(text); if (failed) throw new Error("网络权限未开启"); },
    onError: (error) => errors.push(error.message),
  });
  await settle(h);
  assert.deepEqual(errors, ["网络权限未开启"]);
  assert.equal(h.tasks.size, 0);
  failed = false;
  h.emit("nextflux:retry-font");
  await settle(h);
  assert.equal(attempts.length, 2);
  assert.equal(attempts[0], attempts[1]);
  stop();
});

test("article version reset and font disposal abort stale loads without stale errors", async () => {
  const h = harness();
  const requests = [];
  const errors = [];
  const stop = startReadingFontLoader("Inter", { ...h.options,
    load: (_family, text, { signal }) => new Promise((resolve, reject) => requests.push({ text, signal, resolve, reject })),
    onError: (error) => errors.push(error),
  });
  await settle(h);
  assert.equal(requests.length, 1);
  h.emit("nextflux:reading-content", { root: h.root, title: h.title, blocks: [], reset: true });
  assert.equal(requests[0].signal.aborted, true);
  await settle(h);
  assert.equal(requests.length, 2);
  requests[0].reject(new Error("stale failure"));
  await Promise.resolve();
  assert.deepEqual(errors, []);
  stop();
  assert.equal(requests[1].signal.aborted, true);
  requests[1].resolve();
  await Promise.resolve();
  assert.equal(h.tasks.size, 0);
  assert.ok([...h.listeners.values()].every((listeners) => listeners.size === 0));
});

test("old article events cannot replace the current reading root", async () => {
  const h = harness("2");
  const old = new h.Element("div", { "data-font-reading-root": "", "data-font-article-id": "1" });
  const stop = startReadingFontLoader("Inter", { ...h.options, load: async () => {} });
  await settle(h);
  h.emit("nextflux:reading-content", { root: old, blocks: [], reset: true });
  assert.equal(h.observers.length, 1);
  assert.equal(h.observers[0].disconnected, false);
  stop();
});
