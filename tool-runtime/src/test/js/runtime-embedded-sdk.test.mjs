import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
const shim = readFileSync(resolve(root, 'tool-runtime/src/main/assets/toolbox-runtime/runtime-sdk.js'), 'utf8')
  .replaceAll('__TOOLBOX_VERSION_CODE__', '1')
  .replaceAll('__TOOLBOX_BRIDGE_OBJECT__', '__toolboxNative').replaceAll('__TOOLBOX_NONCE__', '"nonce"')
  .replaceAll('__TOOLBOX_TOOL_ID__', '"io.example.embedded"').replaceAll('__TOOLBOX_GENERATION__', '"generation"');
const video = 'https://www.youtube-nocookie.com/embed/y8-kOYgmNdA';
const cancelled = error => error?.code === 'CANCELLED';
const settle = async () => { for (let index = 0; index < 48; index++) await Promise.resolve(); };

class Events {
  listeners = new Map();
  addEventListener(type, callback, options) {
    const entries = this.listeners.get(type) ?? new Map();
    entries.set(callback, options);
    this.listeners.set(type, entries);
  }
  removeEventListener(type, callback) { this.listeners.get(type)?.delete(callback); }
  dispatchEvent(event) {
    for (const [callback, options] of [...(this.listeners.get(event.type) ?? [])]) {
      if (options?.once) this.removeEventListener(event.type, callback);
      if (typeof callback === 'function') callback(event);
      else callback.handleEvent(event);
    }
    return true;
  }
  emit(type) { this.dispatchEvent({ type, target: this }); }
  get listenerCount() { return [...this.listeners.values()].reduce((sum, entries) => sum + entries.size, 0); }
}

class Signal extends Events {
  aborted = false;
  abort() { if (!this.aborted) { this.aborted = true; this.emit('abort'); } }
}

// The harness models browser geometry, events and scheduling; it never inserts a real remote frame.
// Native RPC replies stay under test control so late opens and overlapping updates are observable.
function harness({ onPost, autoClose = true } = {}) {
  const document = new Events();
  const windowEvents = new Events();
  const visualViewport = Object.assign(new Events(), { width: 360, height: 640, offsetLeft: 0, offsetTop: 0, scale: 1 });
  const rafs = new Map();
  const timers = new Map();
  const observers = new Set();
  let sequence = 0;
  let clock = 0;
  let nextSession = 0;
  let eventSequence = 0;
  let hit = null;
  class Element extends Events {
    nodeType = 1;
    isConnected = true;
    ownerDocument = document;
    parentElement = null;
    style = {};
    computed = { overflow: 'visible', overflowX: 'visible', overflowY: 'visible', visibility: 'visible', display: 'block', opacity: '1', contain: 'none' };
    rect;
    clientTop = 0;
    clientLeft = 0;
    constructor(rect, parent = null) { super(); this.rect = { ...rect }; this.parentElement = parent; }
    get parentNode() { return this.parentElement; }
    get clientWidth() { return this.rect.width; }
    get clientHeight() { return this.rect.height; }
    getBoundingClientRect() {
      const { x, y, width, height } = this.rect;
      return { x, y, left: x, top: y, right: x + width, bottom: y + height, width, height };
    }
    getClientRects() { return this.isConnected && this.computed.display !== 'none' ? [this.getBoundingClientRect()] : []; }
    contains(node) { for (let item = node; item; item = item.parentElement) if (item === this) return true; return false; }
    closest() { return null; }
  }
  const html = new Element({ x: 0, y: 0, width: 360, height: 640 });
  const body = new Element({ x: 0, y: 0, width: 360, height: 640 }, html);
  const anchor = new Element({ x: 20, y: 100, width: 320, height: 180 }, body);
  hit = anchor;
  Object.assign(document, {
    documentElement: html, body, hidden: false, visibilityState: 'visible',
    elementFromPoint: () => hit,
    elementsFromPoint: () => hit ? [hit] : [],
    contains: element => element?.ownerDocument === document && element.isConnected,
  });
  class ResizeObserver {
    observed = new Set();
    constructor(callback) { this.callback = callback; observers.add(this); }
    observe(element) { this.observed.add(element); }
    unobserve(element) { this.observed.delete(element); }
    disconnect() { this.observed.clear(); }
  }
  class MutationObserver extends ResizeObserver {}
  const sent = [];
  const reply = (request, result) => bridge.onmessage({ data: JSON.stringify({ id: request.id, ok: true, result }) });
  const fail = (request, code) => bridge.onmessage({ data: JSON.stringify({ id: request.id, ok: false, error: { code, message: code } }) });
  const bridge = { postMessage(encoded) {
    const request = JSON.parse(encoded);
    sent.push(request);
    onPost?.(request);
    if (request.method === 'ready') queueMicrotask(() => reply(request, {
      runtimeState: { generation: 'generation', revision: 0, foreground: true, closing: false },
    }));
    if (autoClose && request.method === 'browser.closeEmbedded') queueMicrotask(() => reply(request, null));
    if (request.method === 'runtime.flushComplete') queueMicrotask(() => reply(request, null));
    if (request.method === 'runtime.ackEvents') queueMicrotask(() => reply(request, null));
  } };
  const context = vm.createContext({
    __toolboxNative: bridge, document, visualViewport, Element, HTMLElement: Element,
    ResizeObserver, MutationObserver, Uint8Array, atob, btoa, URL, AbortController,
    crypto: webcrypto, queueMicrotask, innerWidth: 360, innerHeight: 640,
    getComputedStyle: element => element.computed,
    performance: { now: () => clock },
    requestAnimationFrame(callback) { const id = ++sequence; rafs.set(id, callback); return id; },
    cancelAnimationFrame(id) { rafs.delete(id); },
    setTimeout(callback, delay = 0) { const id = ++sequence; timers.set(id, { callback, due: clock + delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    addEventListener: windowEvents.addEventListener.bind(windowEvents),
    removeEventListener: windowEvents.removeEventListener.bind(windowEvents),
    dispatchEvent: windowEvents.dispatchEvent.bind(windowEvents),
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options?.detail; } },
  });
  context.window = context;
  document.defaultView = context;
  vm.runInContext(shim, context);
  const listenerCount = () => document.listenerCount + windowEvents.listenerCount + visualViewport.listenerCount;
  const baselineListeners = listenerCount();
  const requests = method => sent.filter(request => request.method === `browser.${method}`);
  return {
    api: context.ToolBox.browser, context, anchor, body, document, visualViewport, Element, sent, reply, fail, settle,
    opens: () => requests('openEmbedded'), updates: () => requests('updateEmbedded'), closes: () => requests('closeEmbedded'),
    dispatch: type => { document.emit(type); windowEvents.emit(type); },
    setHit: element => { hit = element; },
    opened(request) { const id = (++nextSession).toString(16).padStart(32, '0'); reply(request, { id }); return id; },
    async frame() {
      clock += 40;
      const callbacks = [...rafs.values()];
      rafs.clear();
      callbacks.forEach(callback => callback(clock));
      for (const [id, timer] of [...timers]) if (timer.due <= clock) { timers.delete(id); timer.callback(); }
      await settle();
    },
    async frames(count = 3) { for (let index = 0; index < count; index++) await this.frame(); },
    resize() { for (const observer of observers) if (observer.observed.size) observer.callback([...observer.observed].map(target => ({ target }))); },
    state({ foreground = true, closing = false, revision = 1 } = {}) {
      bridge.onmessage({ data: JSON.stringify({ type: 'runtimeState', data: {
        generation: 'generation', revision, foreground, closing, ...(closing ? { closeToken: 'close-token' } : {}),
      } }) });
    },
    ended(id) {
      bridge.onmessage({ data: JSON.stringify({ type: 'event', event: 'browser.embeddedClosed',
        generation: 'generation', sequence: ++eventSequence, data: { id } }) });
    },
    pagehide() { windowEvents.emit('pagehide'); },
    assertReleased() {
      assert.equal(rafs.size, 0, 'A closed embed must not retain animation-frame work');
      assert.equal([...observers].filter(observer => observer.observed.size).length, 0, 'A closed embed must disconnect its observers');
      assert.equal(listenerCount(), baselineListeners, 'A closed embed must release per-player DOM listeners');
    },
  };
}

async function mount(host, options) {
  const pending = host.api.openEmbedded(video, host.anchor, options);
  await host.settle();
  const request = host.opens().at(-1);
  assert.ok(request, 'Opening the visible anchor must reach the native player');
  host.opened(request);
  const handle = await pending;
  await host.settle();
  return handle;
}

test('embedding is lazy and sends only CSS geometry and the requested source', { timeout: 2_000 }, async () => {
  const host = harness();
  await host.settle();
  await host.frames();
  assert.equal(host.opens().length, 0);
  const handle = await mount(host);
  assert.deepEqual(host.opens()[0].params, {
    url: video, x: 20, y: 100, width: 320, height: 180, viewportWidth: 360,
    clipTop: 0, clipRight: 0, clipBottom: 0, clipLeft: 0,
  });
  assert.equal(handle.id, '0'.repeat(31) + '1');
  await handle.close();
  await handle.closed;
  host.assertReleased();
});

test('a scroll container clips the player without changing its intrinsic size', { timeout: 2_000 }, async () => {
  const host = harness();
  const scroller = new host.Element({ x: 0, y: 120, width: 320, height: 160 }, host.body);
  scroller.computed.overflow = scroller.computed.overflowX = scroller.computed.overflowY = 'hidden';
  host.anchor.parentElement = scroller;
  host.anchor.rect = { x: -20, y: 80, width: 360, height: 240 };
  const handle = await mount(host);
  assert.deepEqual(host.opens()[0].params, {
    url: video, x: -20, y: 80, width: 360, height: 240, viewportWidth: 360,
    clipTop: 40, clipRight: 20, clipBottom: 40, clipLeft: 20,
  });
  await handle.close();
});

test('visual viewport offsets and width are respected after zoom or keyboard changes', { timeout: 2_000 }, async () => {
  const host = harness();
  Object.assign(host.visualViewport, { width: 300, height: 400, offsetLeft: 10, offsetTop: 30 });
  host.anchor.rect = { x: 30, y: 50, width: 240, height: 135 };
  const handle = await mount(host);
  const bounds = host.opens()[0].params;
  assert.equal(bounds.x, 20);
  assert.equal(bounds.y, 20);
  assert.equal(bounds.viewportWidth, 300);
  assert.equal(bounds.clipTop + bounds.clipRight + bounds.clipBottom + bounds.clipLeft, 0);
  await handle.close();
});

test('scroll bursts coalesce and an in-flight update cannot queue stale positions', { timeout: 2_000 }, async () => {
  const host = harness();
  const handle = await mount(host);
  await host.frames();
  assert.equal(host.updates().length, 0, 'Unchanged bounds must not generate update traffic');
  host.anchor.rect.y = 80;
  for (let index = 0; index < 12; index++) host.dispatch('scroll');
  await host.frames();
  assert.equal(host.updates().length, 1);
  const first = host.updates()[0];
  for (const y of [60, 40, 20]) {
    host.anchor.rect.y = y;
    host.dispatch('scroll');
    await host.frames();
  }
  assert.equal(host.updates().length, 1, 'Only one geometry update may be in flight');
  host.reply(first, null);
  await host.settle();
  await host.frames();
  assert.equal(host.updates().length, 2);
  assert.equal(host.updates()[1].params.y, 20, 'The next update must use the newest position');
  host.reply(host.updates()[1], null);
  await host.settle();
  await host.frames();
  assert.equal(host.updates().length, 2);
  await handle.close();
  host.assertReleased();
});

test('close is idempotent and a late update reply cannot restart tracking', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new Signal();
  const handle = await mount(host, { signal });
  host.anchor.rect.y = 60;
  await host.frames();
  const update = host.updates()[0];
  assert.ok(update);
  await Promise.all([handle.close(), handle.close()]);
  await handle.closed;
  assert.equal(host.closes().length, 1);
  assert.deepEqual(host.closes()[0].params, { id: handle.id });
  assert.equal(signal.listenerCount, 0);
  host.reply(update, null);
  signal.abort();
  host.dispatch('scroll');
  host.resize();
  await host.frames();
  assert.equal(host.closes().length, 1);
  assert.equal(host.updates().length, 1);
  host.assertReleased();
});

test('a pre-aborted signal never creates a native player', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new Signal();
  signal.abort();
  await assert.rejects(host.api.openEmbedded(video, host.anchor, { signal }), cancelled);
  assert.equal(host.opens().length, 0);
  assert.equal(host.closes().length, 0);
  host.assertReleased();
});

test('aborting a pending open rejects immediately and closes its late native result', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new Signal();
  const pending = host.api.openEmbedded(video, host.anchor, { signal });
  const rejected = assert.rejects(pending, cancelled);
  await host.settle();
  const request = host.opens()[0];
  signal.abort();
  await rejected;
  assert.equal(signal.listenerCount, 0);
  const id = host.opened(request);
  await host.settle();
  assert.deepEqual(host.closes().map(close => close.params.id), [id]);
  host.assertReleased();
});

test('a superseded pending open is closed before a new native open can run', { timeout: 2_000 }, async () => {
  const host = harness();
  const first = host.api.openEmbedded(video, host.anchor);
  const firstRejected = assert.rejects(first, cancelled);
  await host.settle();
  const a = host.opens()[0];
  const second = host.api.openEmbedded(video, host.anchor);
  await firstRejected;
  await host.settle();
  assert.equal(host.opens().length, 1, 'The second native open waits for the stale first open');
  const staleId = host.opened(a);
  await host.settle();
  assert.equal(host.opens().length, 2);
  const closed = host.closes().find(request => request.params.id === staleId);
  assert.ok(closed);
  const b = host.opens()[1];
  assert.ok(host.sent.indexOf(closed) < host.sent.indexOf(b), 'The old native player must be closed before its replacement opens');
  host.opened(b);
  const handle = await second;
  await handle.close();
  host.assertReleased();
});

test('opening another video closes the active handle and resolves its closed promise', { timeout: 2_000 }, async () => {
  const host = harness();
  const first = await mount(host);
  const second = await mount(host);
  await first.closed;
  assert.ok(host.closes().some(request => request.params.id === first.id));
  assert.notEqual(first.id, second.id);
  await second.close();
  host.assertReleased();
});

test('native admission failure releases all local tracking and signal listeners', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new Signal();
  const pending = host.api.openEmbedded(video, host.anchor, { signal });
  const rejected = assert.rejects(pending, error => error?.code === 'PERMISSION_DENIED');
  await host.settle();
  host.fail(host.opens()[0], 'PERMISSION_DENIED');
  await rejected;
  signal.abort();
  await host.frames();
  assert.equal(host.closes().length, 0);
  assert.equal(signal.listenerCount, 0);
  host.assertReleased();
});

for (const state of [{ foreground: false }, { closing: true }]) {
  test(`runtime ${state.closing ? 'closing' : 'backgrounding'} releases the inline player`, { timeout: 2_000 }, async () => {
    const host = harness();
    const handle = await mount(host);
    host.state(state);
    await host.settle();
    await handle.closed;
    assert.equal(host.closes().length, 1);
    host.assertReleased();
  });
}

test('pagehide closes the active player and the ended document cannot reopen it', { timeout: 2_000 }, async () => {
  const host = harness();
  const handle = await mount(host);
  host.pagehide();
  await host.settle();
  await handle.closed;
  assert.equal(host.closes().length, 1);
  host.assertReleased();
  await assert.rejects(host.api.openEmbedded(video, host.anchor), error => error?.code === 'SESSION_ENDED');
  assert.equal(host.opens().length, 1);
});

test('hiding the document cancels an unresolved open and closes its late result', { timeout: 2_000 }, async () => {
  const host = harness();
  const pending = host.api.openEmbedded(video, host.anchor);
  const rejected = assert.rejects(pending, cancelled);
  await host.settle();
  host.document.hidden = true;
  host.document.visibilityState = 'hidden';
  host.document.emit('visibilitychange');
  await rejected;
  const id = host.opened(host.opens()[0]);
  await host.settle();
  assert.deepEqual(host.closes().map(request => request.params.id), [id]);
  host.assertReleased();
});

test('removing the anchor stops the native player even without an explicit component close', { timeout: 2_000 }, async () => {
  const host = harness();
  const handle = await mount(host);
  host.anchor.isConnected = false;
  await host.frames();
  await handle.closed;
  assert.equal(host.closes().length, 1);
  host.assertReleased();
});

test('a covering dialog hides the native overlay and uncovering restores its bounds', { timeout: 2_000 }, async () => {
  const host = harness();
  const handle = await mount(host);
  host.setHit(new host.Element({ x: 0, y: 0, width: 360, height: 640 }, host.body));
  await host.frames();
  const hidden = host.updates().at(-1);
  assert.ok(hidden);
  const bounds = hidden.params;
  assert.ok(bounds.clipLeft + bounds.clipRight >= bounds.width || bounds.clipTop + bounds.clipBottom >= bounds.height,
    'The external native surface must not cover a dialog drawn by the article page');
  host.reply(hidden, null);
  host.setHit(host.anchor);
  await host.settle();
  await host.frames();
  const visible = host.updates().at(-1);
  assert.notEqual(visible.id, hidden.id);
  assert.equal(visible.params.clipTop + visible.params.clipRight + visible.params.clipBottom + visible.params.clipLeft, 0);
  host.reply(visible, null);
  await handle.close();
});

test('a native session ending during a geometry update resolves closed and stops retrying', { timeout: 2_000 }, async () => {
  const host = harness();
  const handle = await mount(host);
  host.anchor.rect.y = 50;
  await host.frames();
  host.fail(host.updates()[0], 'SESSION_ENDED');
  await host.settle();
  await handle.closed;
  await host.frames();
  assert.equal(host.updates().length, 1);
  host.assertReleased();
});

test('native close events stop only their matching player and release DOM tracking', { timeout: 2_000 }, async () => {
  const host = harness();
  const handle = await mount(host);
  let ended = false;
  handle.closed.then(() => { ended = true; });
  host.ended('f'.repeat(32));
  await host.settle();
  assert.equal(ended, false, 'An unrelated player event must not stop this video');
  host.ended(handle.id);
  await handle.closed;
  await host.settle();
  assert.equal(ended, true);
  host.assertReleased();
});

test('native closure arriving before open response cannot expose a dead player handle', { timeout: 2_000 }, async () => {
  const host = harness();
  const pending = host.api.openEmbedded(video, host.anchor);
  const rejected = assert.rejects(pending, cancelled);
  await host.settle();
  const id = 'a'.repeat(32);
  host.ended(id);
  await host.settle();
  host.reply(host.opens()[0], { id });
  await rejected;
  await host.settle();
  assert.deepEqual(host.closes().map(request => request.params.id), [id]);
  host.assertReleased();
  const fresh = await mount(host);
  await fresh.close();
  host.assertReleased();
});

test('a replaced player closure event cannot cancel its pending replacement', { timeout: 2_000 }, async () => {
  const host = harness();
  const first = await mount(host);
  const pending = host.api.openEmbedded(video, host.anchor);
  await first.closed;
  await host.settle();
  host.ended(first.id);
  await host.settle();
  const id = host.opened(host.opens()[1]);
  const replacement = await pending;
  assert.equal(replacement.id, id);
  assert.notEqual(replacement.id, first.id);
  await replacement.close();
  host.assertReleased();
});
