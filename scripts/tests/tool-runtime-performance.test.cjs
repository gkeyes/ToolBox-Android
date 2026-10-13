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
    children: [], dataset: {}, style: {}, textContent: '', listeners: {},
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); },
    setAttribute() {}, close() {}, focus() {},
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
  vm.runInNewContext(source, { window, document, URL, Intl, Date: options.Date || Date, console, ...timers }, { filename });
  const dispatch = (id, type, event = {}) => {
    const node = document.getElementById(id);
    domReads -= 1;
    for (const listener of node.listeners[type] || []) listener({ preventDefault() {}, target: node, ...event });
  };
  return { api: window.testApi, nodes, dispatch, node: (id) => { const node = document.getElementById(id); domReads -= 1; return node; }, counts: () => ({ domReads, created }) };
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
  const h = loadApp('stock-monitor', 'state, createItem, refreshAll, dirtyItems, loaded: () => { stateLoaded = true; markStateLoaded(); }', toolbox, { StockMonitorLive: stockLive });
  h.api.loaded();
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
  const h = loadApp('stock-monitor', 'state, createItem, refreshAll, loaded: () => { stateLoaded = true; markStateLoaded(); }', host.toolbox, { StockMonitorLive: stockLive },
    { setTimeout: fakeSetTimeout, clearTimeout: fakeClearTimeout });
  h.api.loaded();
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

test('stock editor save and monitor toggle before the saved list loads never persist the default list', async () => {
  let releaseStorage;
  const host = stockHost();
  let started = 0;
  host.toolbox.background.start = async () => { started += 1; return { sessionId: 'session-2' }; };
  host.toolbox.background.listSessions = async () => [];
  host.toolbox.storage.get = () => new Promise((resolve) => { releaseStorage = resolve; });
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'get state() { return state; }, persist', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
  await settle();
  const field = (id, value) => { h.nodes.get(id) || h.dispatch(id, 'noop'); h.nodes.get(id).value = value; };
  h.dispatch('stock-form', 'noop');
  h.nodes.get('stock-form').elements = { provider: { value: 'tencent' } };
  field('symbol', '000002'); field('stock-name', ''); field('upper-price', ''); field('lower-price', '');
  field('interval', '300000'); field('api-token', ''); h.nodes.get('enabled') || h.dispatch('enabled', 'noop');
  h.nodes.get('enabled').checked = true;
  h.dispatch('stock-form', 'submit');
  h.dispatch('toggle-monitor', 'click');
  void h.api.persist();
  for (let index = 0; index < 5; index += 1) await settle();
  assert.equal(host.writes.length, 0, 'nothing persisted before the saved list is read');
  assert.equal(started, 0, 'monitoring is not toggled from the default state');
  releaseStorage({ version: 1, intervalMs: 300000, monitoring: false, items: [{ id: 'saved', provider: 'tencent', symbol: '000001' }] });
  for (let index = 0; index < 20; index += 1) await settle();
  assert.ok(host.writes.length > 0);
  for (const write of host.writes) assert.ok(write.items.some((item) => item.symbol === '000001'), 'saved stock kept');
  assert.deepEqual(h.api.state.items.map((item) => item.symbol), ['000001', '000002']);
});

test('stock manual refresh during a background pass runs its own forced pass afterwards', async () => {
  const host = stockHost();
  const original = host.toolbox.network.request;
  const waiting = [];
  host.toolbox.network.request = (request) => new Promise((resolve) => { waiting.push(() => resolve(original(request))); });
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'state, createItem, refreshAll, loaded: () => { stateLoaded = true; markStateLoaded(); }', host.toolbox, { StockMonitorLive: stockLive });
  h.api.loaded();
  h.api.state.items = [h.api.createItem({ provider: 'tencent', symbol: '600550' })];
  const background = h.api.refreshAll({ background: true });
  const manual = h.api.refreshAll({ announce: true, force: true });
  assert.notEqual(manual, background);
  for (let index = 0; index < 50 && (waiting.length || host.calls.length < 2); index += 1) {
    waiting.splice(0).forEach((resolve) => resolve());
    await settle();
  }
  await Promise.all([background, manual]);
  assert.equal(host.calls.length, 2, 'forced pass fetched after the background pass');
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
  assert.equal(skipWhileClosed(createItem({ symbol: '600550', fetchedAt: since + 60000, price: 12, upper: 11 }), since), false,
    'unlatched crossed threshold is re-evaluated');
  assert.equal(skipWhileClosed(createItem({ symbol: '600550', fetchedAt: since + 60000, price: 12, upper: 11, aboveLatched: true }), since), true);
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

function loadKegel({ toolbox, localStorage, navigator = {} } = {}) {
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
  const context = { window, document, navigator, performance: { now: () => clock }, console, Intl, Date, setTimeout, clearTimeout };
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

function legacyStore(entries) {
  const data = new Map(entries.map(([key, value]) => [key, JSON.stringify(value)]));
  return {
    data,
    getItem: (key) => data.get(key) ?? null,
    setItem: (key, value) => { data.set(key, String(value)); },
    removeItem: (key) => data.delete(key),
  };
}

test('Kegel keeps legacy history while storage is switched off and migrates once it is enabled', async () => {
  const oldEntry = { completedAt: '2026-01-01T00:00:00Z', mode: '标准训练', reps: 10, durationMs: 60000 };
  const localStorage = legacyStore([['wenli.history.v1', [oldEntry]]]);
  let denied = true;
  let stateListener = null;
  const applied = [];
  const toolbox = {
    ready: async () => ({}),
    runtime: {
      onStateChanged(listener) { stateListener = listener; listener({ foreground: true, closing: false }); },
      registerFlushHandler() {},
    },
    storage: {
      getMany: async () => {
        if (denied) throw Object.assign(new Error('denied'), { code: 'PERMISSION_DENIED' });
        return [null, null];
      },
      apply: async (mutation) => { applied.push(JSON.parse(JSON.stringify(mutation))); },
    },
    haptics: { perform: async () => {} },
  };
  const h = loadKegel({ toolbox, localStorage });
  for (let index = 0; index < 5; index += 1) await settle();
  assert.match(h.nodes.get('history-summary').textContent, /存储权限未开启：1 条/);
  assert.match(h.nodes.get('toast').textContent, /开启「存储」/);
  h.click('start-button');
  h.runFrames(1, 70000);
  const saved = JSON.parse(localStorage.data.get('wenli.history.v1'));
  assert.equal(saved.length, 2, 'new session recorded in the legacy copy, old entry kept');
  denied = false;
  stateListener({ foreground: false, closing: false });
  stateListener({ foreground: true, closing: false });
  for (let index = 0; index < 5; index += 1) await settle();
  assert.equal(applied.length, 1);
  const history = applied[0].set.find((entry) => entry.key === 'wenli.history.v1').value;
  assert.equal(history.length, 2);
  assert.equal(localStorage.data.size, 0, 'legacy copy removed only after migration');
});

test('Kegel keeps preference edits made while saved preferences are loading', async () => {
  let release;
  const applied = [];
  const toolbox = {
    ready: async () => ({}),
    runtime: { onStateChanged(listener) { listener({ foreground: true, closing: false }); }, registerFlushHandler() {} },
    storage: {
      getMany: () => new Promise((resolve) => { release = resolve; }),
      apply: async (mutation) => { applied.push(JSON.parse(JSON.stringify(mutation))); },
    },
  };
  const h = loadKegel({ toolbox });
  await settle();
  for (const listener of h.nodes.get('volume-range').listeners.input) listener({ target: { value: '30' } });
  release([{ selectedMode: 'standard', volume: 80, sound: false }, []]);
  for (let index = 0; index < 5; index += 1) await settle();
  assert.equal(h.nodes.get('volume-output').textContent, '30%');
  assert.equal(h.nodes.get('summary-title').textContent, '标准训练');
  const prefs = applied.at(-1).set.find((entry) => entry.key === 'wenli.preferences.v1').value;
  assert.equal(prefs.volume, 30);
  assert.equal(prefs.selectedMode, 'standard');
  assert.equal(prefs.sound, false);
});

function kegelHost({ getMany }) {
  const applied = [];
  const host = { applied, flush: null, stateListener: null };
  host.toolbox = {
    ready: async () => ({}),
    runtime: {
      onStateChanged(listener) { host.stateListener = listener; listener({ foreground: true, closing: false }); },
      registerFlushHandler(handler) { host.flush = handler; },
    },
    storage: { getMany, apply: async (mutation) => { applied.push(JSON.parse(JSON.stringify(mutation))); } },
    haptics: { perform: async () => {} },
  };
  host.savedHistory = () => {
    const writes = applied.flatMap((mutation) => mutation.set).filter((entry) => entry.key === 'wenli.history.v1');
    return writes.at(-1)?.value;
  };
  return host;
}

const settleMany = async (count = 8) => { for (let index = 0; index < count; index += 1) await settle(); };

test('Kegel flush while the first read is pending waits for it and saves edits made meanwhile', async () => {
  let release;
  const host = kegelHost({ getMany: () => new Promise((resolve) => { release = resolve; }) });
  const h = loadKegel({ toolbox: host.toolbox });
  await settle();
  for (const listener of h.nodes.get('volume-range').listeners.input) listener({ target: { value: '30' } });
  h.click('start-button');
  h.runFrames(1, 70000);
  let flushed = false;
  const flushing = host.flush().then(() => { flushed = true; });
  await settleMany();
  assert.equal(flushed, false, 'flush waits for the pending read');
  const old = { completedAt: '2026-01-01T00:00:00Z', mode: '标准训练', reps: 10, durationMs: 60000 };
  release([{ volume: 80 }, [old]]);
  await flushing;
  const prefs = host.applied.flatMap((m) => m.set).filter((e) => e.key === 'wenli.preferences.v1').at(-1).value;
  assert.equal(prefs.volume, 30);
  assert.equal(host.savedHistory().length, 2, 'new session saved together with the stored one');
});

test('Kegel clearing history while the saved copy loads does not bring it back', async () => {
  let release;
  const host = kegelHost({ getMany: () => new Promise((resolve) => { release = resolve; }) });
  const h = loadKegel({ toolbox: host.toolbox });
  await settle();
  h.click('start-button');
  h.runFrames(1, 70000);
  h.click('confirm-clear-button');
  const old = { completedAt: '2026-01-01T00:00:00Z', mode: '标准训练', reps: 10, durationMs: 60000 };
  release([null, [old]]);
  await settleMany();
  assert.deepEqual(host.savedHistory(), []);
  assert.equal(h.nodes.get('history-summary').textContent, '还没有训练记录');
});

test('Kegel warns when the localStorage fallback cannot be written and keeps sessions for migration', async () => {
  let denied = true;
  const hostEntry = { completedAt: '2026-01-01T00:00:00Z', mode: '标准训练', reps: 10, durationMs: 60000 };
  const host = kegelHost({ getMany: async () => {
    if (denied) throw Object.assign(new Error('denied'), { code: 'PERMISSION_DENIED' });
    return [null, [hostEntry]];
  } });
  const localStorage = { getItem: () => null, setItem() { throw new Error('QuotaExceededError'); }, removeItem() {} };
  const h = loadKegel({ toolbox: host.toolbox, localStorage });
  await settleMany();
  assert.doesNotMatch(h.nodes.get('history-summary').textContent, /暂存在网页存储/);
  assert.match(h.nodes.get('toast').textContent, /写入失败/);
  h.click('start-button');
  h.runFrames(1, 70000);
  assert.match(h.nodes.get('history-summary').textContent, /本次会话已完成 1 次/);
  denied = false;
  host.stateListener({ foreground: false, closing: false });
  host.stateListener({ foreground: true, closing: false });
  await settleMany();
  assert.equal(host.savedHistory().length, 2);
});

test('Kegel merges sessions kept in the fallback while storage was off into existing host data', async () => {
  let denied = true;
  const hostEntry = { completedAt: '2026-01-01T00:00:00Z', mode: '标准训练', reps: 10, durationMs: 60000 };
  const host = kegelHost({ getMany: async () => {
    if (denied) throw Object.assign(new Error('denied'), { code: 'PERMISSION_DENIED' });
    return [{ volume: 80, sound: false }, [hostEntry]];
  } });
  const localStorage = legacyStore([]);
  const h = loadKegel({ toolbox: host.toolbox, localStorage });
  await settleMany();
  for (const listener of h.nodes.get('volume-range').listeners.input) listener({ target: { value: '30' } });
  h.click('start-button');
  h.runFrames(1, 70000);
  assert.equal(JSON.parse(localStorage.data.get('wenli.history.v1')).length, 1);
  denied = false;
  host.stateListener({ foreground: false, closing: false });
  host.stateListener({ foreground: true, closing: false });
  await settleMany();
  const history = host.savedHistory();
  assert.equal(history.length, 2, 'fallback session merged with the host copy');
  assert.equal(new Set(history.map((entry) => entry.completedAt)).size, 2);
  const prefs = host.applied.at(-1).set.find((e) => e.key === 'wenli.preferences.v1').value;
  assert.equal(prefs.volume, 30, 'edit made while storage was off kept');
  assert.equal(prefs.sound, false, 'untouched host preference kept');
  assert.equal(localStorage.data.size, 0, 'fallback removed after the host write');
  assert.match(h.nodes.get('history-summary').textContent, /已保存 2 次/);
});

test('stock saved-state read failure keeps the background timer until the state is read', async () => {
  let fail = true;
  const timers = [];
  const host = stockHost();
  host.toolbox.background.setTimer = async (_key, interval) => { timers.push(interval); };
  host.toolbox.storage.get = async () => {
    if (fail) throw Object.assign(new Error('busy'), { code: 'BUSY' });
    return { version: 1, intervalMs: 900000, monitoring: true, sessionId: 'session-1',
      items: [{ id: 'saved', provider: 'tencent', symbol: '000001', name: '自选' }] };
  };
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'get state() { return state; }', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
  await settleMany(20);
  assert.deepEqual(timers, [], 'default interval never replaces the saved schedule');
  assert.equal(h.api.state.monitoring, false);
  fail = false;
  host.listeners.timer({ key: 'quote-poll' });
  await settleMany(20);
  assert.deepEqual(timers, [900000]);
  assert.equal(h.api.state.monitoring, true);
  assert.deepEqual(h.api.state.items.map((item) => item.symbol), ['000001']);
});

test('stock edits while the saved list is unread are refused instead of reported as saved', async () => {
  const host = stockHost();
  host.toolbox.storage.get = async () => { throw Object.assign(new Error('busy'), { code: 'BUSY' }); };
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'get state() { return state; }, get status() { return statusMessage; }', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
  await settleMany(20);
  const id = h.api.state.items[0].id;
  const toggle = { checked: false };
  h.dispatch('watchlist', 'change', { target: { closest: (selector) => (selector === '.enabled-toggle' ? toggle : { dataset: { id } }) } });
  await settleMany(20);
  assert.equal(h.api.state.items[0].enabled, true, 'toggle reverted because nothing was saved');
  assert.match(h.api.status, /无法保存|未保存/);
  assert.equal(host.writes.length, 0);
});

test('stock editor save keeps its target when the editor closes or is submitted again mid-save', async () => {
  const releaseToken = [];
  const host = stockHost();
  host.toolbox.storage.get = async () => ({ version: 1, intervalMs: 300000, monitoring: false, sessionId: null,
    items: [{ id: 'saved', provider: 'tencent', symbol: '000001', name: '自选' }] });
  host.toolbox.background.listSessions = async () => [];
  host.toolbox.storage.secure.set = () => new Promise((resolve) => { releaseToken.push(resolve); });
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'get state() { return state; }, saveEditor, closeEditor, edit: (id) => { editingId = id; }', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
  await settleMany(20);
  h.node('stock-form').elements = { provider: { value: 'tencent' } };
  Object.assign(h.node('symbol'), { value: '000002' });
  Object.assign(h.node('stock-name'), { value: '改名' });
  for (const id of ['upper-price', 'lower-price']) h.node(id).value = '';
  h.node('interval').value = '300000';
  h.node('enabled').checked = true;
  h.node('api-token').value = 'token';
  h.api.edit('saved');
  const first = h.api.saveEditor({ preventDefault() {} });
  const second = h.api.saveEditor({ preventDefault() {} });
  h.api.closeEditor();
  for (const release of releaseToken) release();
  await Promise.all([first, second]);
  await settleMany(20);
  assert.deepEqual(h.api.state.items.map((item) => [item.id, item.symbol]), [['saved', '000002']]);
});

test('Kegel without a host keeps using records saved in localStorage', async () => {
  const old = { completedAt: '2026-01-01T00:00:00Z', mode: '标准训练', reps: 10, durationMs: 60000 };
  const localStorage = legacyStore([['wenli.history.v1', [old]], ['wenli.preferences.v1', { selectedMode: 'standard' }]]);
  const h = loadKegel({ localStorage });
  assert.match(h.nodes.get('history-summary').textContent, /已保存 1 次/);
  assert.equal(h.nodes.get('summary-title').textContent, '标准训练');
  h.click('start-button');
  h.runFrames(1, 3600000);
  assert.equal(JSON.parse(localStorage.data.get('wenli.history.v1')).length, 2);
});

test('Kegel training started before a failed handshake keeps running', async () => {
  let reject;
  const h = loadKegel({ toolbox: { ready: () => new Promise((_resolve, fail) => { reject = fail; }) } });
  h.click('start-button');
  reject(new Error('no host'));
  await settleMany();
  h.runFrames(70, 16);
  assert.equal(h.nodes.get('countdown').textContent, '4');
});

test('Kegel switches to the localStorage fallback when storage permission is revoked', async () => {
  let denied = false;
  const localStorage = legacyStore([]);
  const host = kegelHost({ getMany: async () => [null, []] });
  host.toolbox.storage.apply = async () => { if (denied) throw Object.assign(new Error('denied'), { code: 'PERMISSION_DENIED' }); };
  const h = loadKegel({ toolbox: host.toolbox, localStorage });
  await settleMany();
  denied = true;
  h.click('start-button');
  h.runFrames(1, 70000);
  await settleMany();
  assert.equal(JSON.parse(localStorage.data.get('wenli.history.v1')).length, 1);
  assert.match(h.nodes.get('history-summary').textContent, /存储权限未开启：1 条/);
  await host.flush();
});

// Host whose storage keeps what was applied, with a switch that revokes the permission.
function persistentKegelHost(initial) {
  const store = new Map(Object.entries(initial));
  const host = kegelHost({ getMany: async (keys) => keys.map((key) => (store.has(key) ? JSON.parse(JSON.stringify(store.get(key))) : null)) });
  host.denied = false;
  host.store = store;
  host.toolbox.storage.apply = async (mutation) => {
    if (host.denied) throw Object.assign(new Error('denied'), { code: 'PERMISSION_DENIED' });
    for (const entry of mutation.set || []) store.set(entry.key, JSON.parse(JSON.stringify(entry.value)));
    host.applied.push(JSON.parse(JSON.stringify(mutation)));
  };
  host.recover = async () => {
    host.denied = false;
    host.stateListener({ foreground: false, closing: false });
    host.stateListener({ foreground: true, closing: false });
    await settleMany();
  };
  return host;
}

test('Kegel history cleared while storage is being revoked stays cleared after recovery', async () => {
  const old = { completedAt: '2026-01-01T00:00:00Z', mode: '标准训练', reps: 10, durationMs: 60000 };
  const host = persistentKegelHost({ 'wenli.history.v1': [old] });
  const localStorage = legacyStore([]);
  const h = loadKegel({ toolbox: host.toolbox, localStorage });
  await settleMany();
  assert.match(h.nodes.get('history-summary').textContent, /已保存 1 次/);
  host.denied = true;
  h.click('confirm-clear-button');
  await settleMany();
  assert.deepEqual(JSON.parse(localStorage.data.get('wenli.history.v1')), []);
  await host.recover();
  assert.deepEqual(host.store.get('wenli.history.v1'), []);
  assert.equal(h.nodes.get('history-summary').textContent, '还没有训练记录');
});

test('Kegel preference changed back while storage is revoked survives recovery', async () => {
  const host = persistentKegelHost({ 'wenli.preferences.v1': { volume: 80 } });
  const localStorage = legacyStore([]);
  const h = loadKegel({ toolbox: host.toolbox, localStorage });
  await settleMany();
  const setVolume = (value) => { for (const listener of h.nodes.get('volume-range').listeners.input) listener({ target: { value } }); };
  setVolume('30');
  await settleMany();
  assert.equal(host.store.get('wenli.preferences.v1').volume, 30);
  host.denied = true;
  setVolume('80');
  await settleMany();
  await host.recover();
  assert.equal(h.nodes.get('volume-output').textContent, '80%');
  assert.equal(host.store.get('wenli.preferences.v1').volume, 80);
});

test('Kegel uses its localStorage records when the host handshake fails', async () => {
  const old = { completedAt: '2026-01-01T00:00:00Z', mode: '标准训练', reps: 10, durationMs: 60000 };
  const localStorage = legacyStore([['wenli.history.v1', [old]]]);
  const h = loadKegel({ toolbox: { ready: () => Promise.reject(new Error('no host')) }, localStorage });
  await settleMany();
  assert.match(h.nodes.get('history-summary').textContent, /已保存 1 次/);
  h.click('start-button');
  h.runFrames(1, 3600000);
  assert.equal(JSON.parse(localStorage.data.get('wenli.history.v1')).length, 2);
});

test('Kegel keeps a setting changed during a handshake that then fails', async () => {
  let reject;
  const localStorage = legacyStore([]);
  const h = loadKegel({ toolbox: { ready: () => new Promise((_resolve, fail) => { reject = fail; }) }, localStorage });
  for (const listener of h.nodes.get('volume-range').listeners.input) listener({ target: { value: '30' } });
  reject(new Error('no host'));
  await settleMany();
  assert.equal(JSON.parse(localStorage.data.get('wenli.preferences.v1')).volume, 30);
});

test('Kegel vibrates through the browser when host haptics are unavailable', async () => {
  const patterns = [];
  const h = loadKegel({ navigator: { vibrate: (pattern) => { patterns.push(pattern); return true; } } });
  assert.doesNotMatch(h.nodes.get('vibration-support-text').textContent, /不支持/);
  h.click('start-button');
  assert.equal(patterns.length, 1);
});

test('stock monitoring started after the close replaces the loading live notification with cached quotes', async () => {
  const fixedNow = Date.parse('2026-10-13T20:00:00+08:00');
  class FixedDate extends Date {
    constructor(...args) { if (args.length) super(...args); else super(fixedNow); }
    static now() { return fixedNow; }
  }
  const live = [];
  const host = stockHost({
    notifications: { live: {
      start: async (request) => { live.push(request); return {}; },
      update: async (request) => { live.push(request); return {}; },
    } },
  });
  host.toolbox.background.start = async () => ({ sessionId: 'session-1' });
  const stockLive = require(path.join(examples, 'stock-monitor', 'live-summary.js'));
  const h = loadApp('stock-monitor', 'state, createItem, refreshAll, startMonitoring, loaded: () => { stateLoaded = true; markStateLoaded(); }',
    host.toolbox, { StockMonitorLive: stockLive }, { Date: FixedDate });
  h.api.loaded();
  h.api.state.items = [h.api.createItem({ provider: 'tencent', symbol: '600550', price: 10.5, fetchedAt: fixedNow - 60000 })];
  assert.equal(await h.api.startMonitoring(), true);
  assert.equal(live.at(-1).shortText, '获取中');
  await h.api.refreshAll();
  assert.equal(host.calls.length, 0, 'closed-market quote not fetched again');
  assert.notEqual(live.at(-1).shortText, '获取中', 'live notification no longer shows loading');
});
