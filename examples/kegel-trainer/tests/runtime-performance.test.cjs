const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const examples = path.resolve(__dirname, '../..');

const settle = () => new Promise((resolve) => setImmediate(resolve));

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

