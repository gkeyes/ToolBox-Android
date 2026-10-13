const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const examples = path.resolve(__dirname, '../../examples');

function loadApp(name, expose, toolbox, extraWindow = {}, options = {}) {
  const filename = path.join(examples, name, 'app.js');
  const marker = '  bootWork = boot();\n})();';
  let source = fs.readFileSync(filename, 'utf8');
  assert.equal(source.split(marker).length, 2, `${name} startup changed`);
  const startup = options.boot ? '  bootWork = boot();\n' : '';
  source = source.replace(marker, `${startup}  window.testApi = { ${expose} };\n})();`);
  const nodes = new Map();
  let domReads = 0;
  let created = 0;
  const element = () => ({
    children: [], dataset: {}, style: {}, textContent: '',
    addEventListener() {}, setAttribute() {},
    append(...items) { this.children.push(...items); },
    replaceChildren(...items) { this.children = items; },
  });
  const document = {
    getElementById(id) {
      domReads += 1;
      if (!nodes.has(id)) nodes.set(id, element());
      return nodes.get(id);
    },
    querySelectorAll() { return []; },
    createElement() { created += 1; return element(); },
  };
  const window = { ToolBox: toolbox, ...extraWindow };
  const timers = { setTimeout: options.setTimeout || setTimeout, clearTimeout: options.clearTimeout || clearTimeout };
  vm.runInNewContext(source, { window, document, URL, Intl, Date, console, ...timers }, { filename });
  return { api: window.testApi, nodes, counts: () => ({ domReads, created }) };
}

test('stock background refresh fetches each enabled quote, saves once and leaves DOM untouched', async () => {
  const calls = [], writes = [];
  const quote = [];
  quote[1] = '测试股票'; quote[3] = '10.50'; quote[4] = '10.00';
  quote[30] = '20260930100000'; quote[32] = '5.00'; quote[34] = '';
  const toolbox = {
    background: { onTimer() {}, onRestore() {} },
    storage: { set: async (_key, value) => { writes.push(value); } },
    network: { request: async (request) => {
      calls.push(request.url);
      const code = new URL(request.url).searchParams.get('code');
      return { status: 200, bodyEncoding: 'text', body: JSON.stringify({ data: { [code]: { qt: { [code]: quote } } } }) };
    } },
  };
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'state, createItem, refreshAll, dirtyItems', toolbox, { StockMonitorLive: stockLive });
  h.api.state.items = [
    h.api.createItem({ provider: 'tencent', symbol: '600550' }),
    h.api.createItem({ provider: 'tencent', symbol: '000001' }),
  ];
  const before = h.counts();
  await h.api.refreshAll({ background: true });
  assert.equal(calls.length, 2);
  assert.equal(writes.length, 1);
  assert.equal(h.api.dirtyItems.size, 2);
  assert.equal(h.api.state.items[0].price, 10.5);
  assert.deepEqual(h.counts(), before);
});

function stockQuote(name) {
  const quote = [];
  quote[1] = name; quote[3] = '10.50'; quote[4] = '10.00';
  quote[30] = '20260930100000'; quote[32] = '5.00'; quote[34] = '';
  return quote;
}

function stockHost(overrides = {}) {
  const calls = [], writes = [], listeners = {};
  const toolbox = {
    ready: async () => ({ hostVersion: 'test', apiVersion: '1.0' }),
    runtime: { onStateChanged() {}, registerFlushHandler(handler) { listeners.flush = handler; } },
    background: {
      onTimer(listener) { listeners.timer = listener; },
      onRestore(listener) { listeners.restore = listener; },
      listSessions: async () => [{ sessionId: 'session-1' }],
      setTimer: async () => {},
    },
    notifications: { live: { start: async () => ({}), update: async () => ({}) } },
    storage: {
      get: async () => null,
      set: async (_key, value) => { writes.push(JSON.parse(JSON.stringify(value))); },
      secure: { get: async () => null },
    },
    network: { request: async (request) => {
      calls.push(request);
      const code = new URL(request.url).searchParams.get('code');
      return { status: 200, bodyEncoding: 'text', body: JSON.stringify({ data: { [code]: { qt: { [code]: stockQuote('自选') } } } }) };
    } },
    ...overrides,
  };
  return { toolbox, calls, writes, listeners };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('stock timer event before boot waits for the saved watchlist instead of persisting defaults', async () => {
  let releaseStorage;
  const host = stockHost();
  host.toolbox.storage.get = () => new Promise((resolve) => { releaseStorage = resolve; });
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'get state() { return state; }', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
  await settle();
  host.listeners.timer({ key: 'quote-poll' });
  await settle();
  assert.equal(host.calls.length, 0, 'no quote fetched before the watchlist is loaded');
  assert.equal(host.writes.length, 0, 'nothing persisted before the watchlist is loaded');
  releaseStorage({ version: 1, intervalMs: 300000, monitoring: true, sessionId: 'session-1',
    items: [{ id: 'saved', provider: 'tencent', symbol: '000001', name: '自选' }] });
  for (let index = 0; index < 20; index += 1) await settle();
  assert.ok(host.writes.length > 0);
  for (const write of host.writes) assert.deepEqual(write.items.map((item) => item.symbol), ['000001']);
  assert.ok(host.calls.every((request) => request.url.includes('sz000001')));
  assert.deepEqual(h.api.state.items.map((item) => item.symbol), ['000001']);
});

test('stock storage read failure keeps working in memory without overwriting the saved list', async () => {
  const host = stockHost();
  host.toolbox.storage.get = async () => { throw Object.assign(new Error('busy'), { code: 'BUSY' }); };
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'get state() { return state; }', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
  for (let index = 0; index < 20; index += 1) await settle();
  assert.equal(h.api.state.items.length, 1);
  assert.equal(host.calls.length, 1, 'quotes still refresh from the in-memory list');
  assert.equal(h.api.state.items[0].price, 10.5);
  assert.equal(host.writes.length, 0);
});

test('stock hung quote request times out so the next poll can run', async () => {
  const host = stockHost();
  host.toolbox.network.request = (request) => { host.calls.push(request); return new Promise(() => {}); };
  const pending = [];
  const fakeSetTimeout = (fn, ms) => { if (ms >= 60000) { pending.push(fn); return pending.length; } return setTimeout(fn, ms); };
  const fakeClearTimeout = (id) => { if (typeof id === 'number' && id <= pending.length) pending[id - 1] = null; else clearTimeout(id); };
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'state, createItem, refreshAll', host.toolbox, { StockMonitorLive: stockLive },
    { setTimeout: fakeSetTimeout, clearTimeout: fakeClearTimeout });
  h.api.state.items = [h.api.createItem({ provider: 'tencent', symbol: '600550' })];
  const work = h.api.refreshAll({ background: true, force: true });
  await settle();
  assert.ok(host.calls[0].timeoutMs > 0, 'host deadline requested');
  for (const fn of pending.splice(0)) fn?.();
  await work;
  assert.match(h.api.state.items[0].error, /超时/);
  host.toolbox.network.request = async (request) => {
    host.calls.push(request);
    return { status: 200, bodyEncoding: 'text', body: JSON.stringify({ data: { sh600550: { qt: { sh600550: stockQuote('保变电气') } } } }) };
  };
  await h.api.refreshAll({ background: true, force: true });
  assert.equal(host.calls.length, 2, 'a later poll is not blocked by the hung request');
  assert.equal(h.api.state.items[0].error, null);
  assert.equal(h.api.state.items[0].price, 10.5);
});

test('stock market-closed polling skips A-share quotes already refreshed after the close', () => {
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'chinaMarketClosedSince, skipWhileClosed, createItem', { background: {} }, { StockMonitorLive: stockLive });
  const { chinaMarketClosedSince, skipWhileClosed, createItem } = h.api;
  const at = (iso) => new Date(iso);
  assert.equal(chinaMarketClosedSince(at('2026-10-13T10:00:00+08:00')), null);
  assert.equal(chinaMarketClosedSince(at('2026-10-13T12:00:00+08:00')), Date.parse('2026-10-13T11:31:00+08:00'));
  assert.equal(chinaMarketClosedSince(at('2026-10-13T20:00:00+08:00')), Date.parse('2026-10-13T15:01:00+08:00'));
  assert.equal(chinaMarketClosedSince(at('2026-10-13T08:00:00+08:00')), Date.parse('2026-10-12T15:01:00+08:00'));
  assert.equal(chinaMarketClosedSince(at('2026-10-12T08:00:00+08:00')), Date.parse('2026-10-09T15:01:00+08:00'));
  assert.equal(chinaMarketClosedSince(at('2026-10-11T12:00:00+08:00')), Date.parse('2026-10-09T15:01:00+08:00'));
  const since = Date.parse('2026-10-13T15:01:00+08:00');
  assert.equal(skipWhileClosed(createItem({ symbol: '600550', fetchedAt: since - 60000 }), since), false, 'one refresh after close');
  assert.equal(skipWhileClosed(createItem({ symbol: '600550', fetchedAt: since + 60000 }), since), true);
  assert.equal(skipWhileClosed(createItem({ provider: 'twelve', symbol: 'AAPL', fetchedAt: since + 60000 }), since), false);
  assert.equal(skipWhileClosed(createItem({ symbol: '600550', fetchedAt: since + 60000 }), null), false);
});

test('notification lab retains only the latest 200 events and reconstructs them on return', () => {
  const toolbox = { background: { onTimer() {}, onRestore() {} } };
  const h = loadApp('notification-lab', 'state, appendEvent, renderLog, setForeground: (value) => { runtimeForeground = value; }', toolbox);
  const before = h.counts();
  for (let index = 0; index < 205; index += 1) h.api.appendEvent(`event-${index}`);
  assert.equal(h.api.state.eventCount, 200);
  assert.deepEqual(h.counts(), before);
  h.api.setForeground(true);
  h.api.renderLog();
  const entries = h.nodes.get('event-log').children;
  assert.equal(entries.length, 200);
  assert.equal(entries[0].children[1].textContent, 'event-204');
  assert.equal(entries[199].children[1].textContent, 'event-5');
});

test('Kegel training clock stays frozen while paused and resumes from remaining time', () => {
  const { TrainingEngine, PRESETS } = require(path.join(examples, 'kegel-trainer', 'app.js'));
  let now = 0;
  const engine = new TrainingEngine({ now: () => now });
  engine.start(PRESETS.beginner);
  now = 2000;
  const paused = engine.pause();
  now = 62000;
  assert.equal(engine.snapshot().elapsedMs, paused.elapsedMs);
  assert.equal(engine.snapshot().remainingMs, paused.remainingMs);
  engine.resume();
  now += paused.remainingMs - 1;
  assert.equal(engine.tick().phase, 'prepare');
  now += 1;
  assert.equal(engine.tick().phase, 'contract');
});

function loadKegel({ toolbox, localStorage } = {}) {
  const filename = path.join(examples, 'kegel-trainer', 'app.js');
  const nodes = new Map();
  const writes = { text: 0 };
  let frames = [];
  let clock = 1000;
  const element = () => {
    let text = '';
    const node = {
      children: [], dataset: {}, style: {}, hidden: false, listeners: {},
      get textContent() { return text; },
      set textContent(value) { writes.text += 1; text = String(value); },
      get childElementCount() { return this.children.length; },
      get lastChild() { return node; },
      classList: { toggle() {}, add() {}, remove() {} },
      addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); },
      setAttribute() {}, closest() { return element(); },
      append(...items) { this.children.push(...items); },
      replaceChildren(...items) { this.children = items; },
      showModal() {}, close() {}, focus() {}, pause() {}, play: async () => {},
    };
    return node;
  };
  const document = {
    body: { dataset: {} },
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); },
    querySelectorAll() { return []; },
    createElement: element,
  };
  const window = {
    ToolBox: toolbox,
    localStorage,
    matchMedia: () => ({ matches: false, addEventListener() {} }),
    requestAnimationFrame(fn) { frames.push(fn); return frames.length; },
    cancelAnimationFrame() {},
    scrollTo() {},
    setTimeout, clearTimeout,
  };
  const context = { window, document, navigator: {}, performance: { now: () => clock }, console, Intl, Date, setTimeout, clearTimeout };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), context, { filename });
  return {
    nodes, writes, window,
    click(id) { for (const listener of nodes.get(id).listeners.click || []) listener({}); },
    runFrames(count, step) {
      for (let index = 0; index < count; index += 1) {
        clock += step;
        const pending = frames; frames = [];
        for (const fn of pending) fn(clock);
      }
    },
  };
}

test('Kegel trains without a host and only repaints values that change each frame', () => {
  const h = loadKegel();
  assert.equal(h.window.WenliTraining, undefined);
  h.click('start-button');
  assert.equal(h.nodes.get('countdown').textContent, '5');
  const before = h.writes.text;
  h.runFrames(60, 16);
  assert.ok(h.writes.text - before <= 3, `text writes over 60 frames: ${h.writes.text - before}`);
  assert.equal(h.nodes.get('countdown').textContent, '5');
  h.runFrames(10, 16);
  assert.equal(h.nodes.get('countdown').textContent, '4');
});

test('Kegel falls back to foreground training when the host handshake fails', async () => {
  const h = loadKegel({ toolbox: { ready: () => Promise.reject(new Error('no host')) } });
  await settle();
  h.click('start-button');
  assert.equal(h.nodes.get('countdown').textContent, '5');
});

test('Kegel imports legacy localStorage once into ToolBox storage and uses host haptics', async () => {
  const legacy = new Map([
    ['wenli.preferences.v1', JSON.stringify({ selectedMode: 'standard', vibration: true })],
    ['wenli.history.v1', JSON.stringify([{ completedAt: '2026-01-01T00:00:00Z', mode: '标准训练', reps: 10, durationMs: 60000 }])],
  ]);
  const localStorage = {
    getItem: (key) => legacy.get(key) ?? null,
    removeItem: (key) => legacy.delete(key),
  };
  const applied = [], haptics = [];
  let flush = null;
  const toolbox = {
    ready: async () => ({}),
    runtime: {
      onStateChanged(listener) { listener({ foreground: true, closing: false }); },
      registerFlushHandler(handler) { flush = handler; },
    },
    storage: {
      getMany: async () => [null, null],
      apply: async (mutation) => { applied.push(JSON.parse(JSON.stringify(mutation))); },
    },
    haptics: { perform: async (effect) => { haptics.push(effect); } },
  };
  const h = loadKegel({ toolbox, localStorage });
  for (let index = 0; index < 5; index += 1) await settle();
  assert.equal(applied.length, 1);
  assert.deepEqual(applied[0].set.map((entry) => entry.key), ['wenli.preferences.v1', 'wenli.history.v1']);
  assert.equal(applied[0].set[1].value.length, 1);
  assert.equal(legacy.size, 0, 'legacy keys removed after a successful import');
  assert.equal(h.nodes.get('summary-title').textContent, '标准训练');
  h.click('start-button');
  assert.deepEqual(haptics, ['click']);
  await flush();
});
