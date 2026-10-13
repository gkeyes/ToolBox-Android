const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const examples = path.resolve(__dirname, '../..');

function loadApp(name, toolbox, extraWindow = {}, options = {}) {
  const filename = path.join(examples, name, 'app.js');
  const source = fs.readFileSync(filename, 'utf8');
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
  const hook = { boot: Boolean(options.boot) };
  const window = { ToolBox: toolbox, __TOOLBOX_TEST__: hook, ...extraWindow };
  const timers = { setTimeout: options.setTimeout || setTimeout, clearTimeout: options.clearTimeout || clearTimeout };
  vm.runInNewContext(source, { window, document, URL, Intl, Date: options.Date || Date, console, ...timers }, { filename });
  const dispatch = (id, type, event = {}) => {
    const node = document.getElementById(id);
    domReads -= 1;
    for (const listener of node.listeners[type] || []) listener({ preventDefault() {}, target: node, ...event });
  };
  return { api: hook.api, nodes, dispatch, node: (id) => { const node = document.getElementById(id); domReads -= 1; return node; }, counts: () => ({ domReads, created }) };
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
  const h = loadApp('stock-monitor', toolbox, { StockMonitorLive: stockLive });
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
  const h = loadApp('stock-monitor', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
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
  const h = loadApp('stock-monitor', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
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
  const h = loadApp('stock-monitor', host.toolbox, { StockMonitorLive: stockLive },
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
  const h = loadApp('stock-monitor', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
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
  const h = loadApp('stock-monitor', host.toolbox, { StockMonitorLive: stockLive });
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
  const h = loadApp('stock-monitor', { background: {} }, { StockMonitorLive: stockLive });
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

const settleMany = async (count = 8) => { for (let index = 0; index < count; index += 1) await settle(); };

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
  const h = loadApp('stock-monitor', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
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
  const h = loadApp('stock-monitor', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
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
  const h = loadApp('stock-monitor', host.toolbox, { StockMonitorLive: stockLive }, { boot: true });
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
  const h = loadApp('stock-monitor', host.toolbox, { StockMonitorLive: stockLive }, { Date: FixedDate });
  h.api.loaded();
  h.api.state.items = [h.api.createItem({ provider: 'tencent', symbol: '600550', price: 10.5, fetchedAt: fixedNow - 60000 })];
  assert.equal(await h.api.startMonitoring(), true);
  assert.equal(live.at(-1).shortText, '获取中');
  await h.api.refreshAll();
  assert.equal(host.calls.length, 0, 'closed-market quote not fetched again');
  assert.notEqual(live.at(-1).shortText, '获取中', 'live notification no longer shows loading');
});
