const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const examples = path.resolve(__dirname, '../../examples');
const tick = () => new Promise(setImmediate);
const settle = async (rounds = 6) => { for (let i = 0; i < rounds; i += 1) await tick(); };
const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const apiError = (code) => Object.assign(new Error(code), { code });

// Minimal DOM: enough for the bundled tools' getElementById / template / closest / event usage.
class Element {
  constructor(tag = 'div', className = '') {
    this.tagName = tag.toUpperCase(); this.className = className; this.children = []; this.parent = null;
    this.dataset = {}; this.style = {}; this.hidden = false; this.disabled = false; this.value = ''; this.textContent = '';
    this.listeners = {};
    const self = this;
    this.classList = {
      contains: (name) => self.className.split(/\s+/).includes(name),
      toggle: (name, on) => { const set = new Set(self.className.split(/\s+/).filter(Boolean)); if (on) set.add(name); else set.delete(name); self.className = [...set].join(' '); },
    };
  }
  get isConnected() { return true; }
  get lastElementChild() { return this.children[this.children.length - 1]; }
  matches(selector) { return selector.startsWith('.') ? this.classList.contains(selector.slice(1)) : this.tagName === selector.toUpperCase(); }
  closest(selector) { for (let node = this; node; node = node.parent) if (node.matches?.(selector)) return node; return null; }
  querySelector(selector) { for (const child of this.children) { if (child.matches(selector)) return child; const found = child.querySelector(selector); if (found) return found; } return null; }
  querySelectorAll() { return []; }
  append(...items) { for (const item of items) { const nodes = item instanceof Fragment ? item.children : [item]; for (const node of nodes) { node.parent = this; this.children.push(node); } } }
  prepend(...items) { this.children.unshift(...items); }
  replaceChildren(...items) { this.children = []; this.append(...items); }
  remove() {}
  addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
  setAttribute() {}
  focus() {}
  async fire(type, extra = {}) {
    const event = { type, target: this, preventDefault() {}, ...extra };
    for (let node = this; node; node = node.parent) for (const listener of node.listeners[type] || []) await listener(event);
  }
}
class Fragment extends Element {}

function build(spec) {
  const [tag, className, children = []] = spec;
  const node = new Element(tag, className);
  for (const child of children) node.append(build(child));
  return node;
}

function loadTool(name, toolbox, { templates = {}, expose = '', marker = null } = {}) {
  const filename = path.join(examples, name, 'app.js');
  let source = fs.readFileSync(filename, 'utf8');
  if (expose) {
    assert.equal(source.split(marker).length, 2, `${name} startup changed`);
    source = source.replace(marker, `${marker.replace('})();', '')}  window.testApi = { ${expose} };\n})();`);
  }
  const nodes = new Map();
  const document = {
    getElementById(id) {
      if (!nodes.has(id)) {
        const node = new Element(id);
        if (templates[id]) node.content = { cloneNode: () => { const fragment = new Fragment(); fragment.append(build(templates[id])); return fragment; } };
        nodes.set(id, node);
      }
      return nodes.get(id);
    },
    querySelectorAll() { return []; },
    createElement: (tag) => new Element(tag),
  };
  const window = { ToolBox: toolbox };
  vm.runInNewContext(source, { window, document, Intl, Date, console, setTimeout, clearTimeout, BigInt, Promise }, { filename });
  return { $: (id) => document.getElementById(id), api: window.testApi };
}

// ---------- position-calculator ----------

function positionCalculator(values, toolbox = {}) {
  const tool = loadTool('position-calculator', toolbox);
  for (const [id, value] of Object.entries(values)) tool.$(id).value = value;
  return tool;
}

test('position calculator caps shares by what the capital can buy and says so', async () => {
  const tool = positionCalculator({ capital: '100000', risk: '1', entry: '10', stop: '9.99' });
  await tool.$('calculator').fire('submit');
  assert.equal(tool.$('shares').textContent, '10,000 股');
  assert.equal(tool.$('amount').textContent, '100,000.00 元');
  assert.equal(tool.$('result-note').hidden, false);
  assert.match(tool.$('result-note').textContent, /资金/);
});

test('position calculator keeps the lot that float division used to drop', async () => {
  const tool = positionCalculator({ capital: '1000000', risk: '0.1', entry: '1.1', stop: '1.0' });
  await tool.$('calculator').fire('submit');
  assert.equal(tool.$('shares').textContent, '10,000 股');
  assert.equal(tool.$('result-note').hidden, true);
});

test('position calculator explains a zero-lot result', async () => {
  const tool = positionCalculator({ capital: '1000', risk: '1', entry: '10', stop: '9' });
  await tool.$('calculator').fire('submit');
  assert.equal(tool.$('shares').textContent, '0 股');
  assert.equal(tool.$('result-note').hidden, false);
  assert.match(tool.$('result-note').textContent, /风险预算/);
});

test('position calculator restore leaves fields the user already typed', async () => {
  const ready = defer();
  const toolbox = { ready: () => ready.promise, storage: { get: async () => ({ capital: 5, risk: 2, entry: 3, stop: 1 }), set: async () => {} } };
  const tool = positionCalculator({ capital: '100000', risk: '1', entry: '10', stop: '9.5' }, toolbox);
  tool.$('capital').value = '250000';
  await tool.$('capital').fire('input');
  ready.resolve({});
  await settle();
  assert.equal(tool.$('capital').value, '250000');
  assert.equal(tool.$('risk').value, '2');
});

test('position calculator saves the exact typed strings and still restores old numeric saves', async () => {
  const saves = [];
  const tool = positionCalculator({ capital: '100000', risk: '1', entry: '1.0000000000000001', stop: '0.5' },
    { storage: { set: async (_key, value) => { saves.push(value); }, get: async () => null } });
  await tool.$('calculator').fire('submit');
  assert.equal(saves.at(-1).entry, '1.0000000000000001');

  const restored = positionCalculator({}, { ready: async () => ({}), storage: { get: async () => ({ capital: '1.0000000000000001', risk: 2, entry: '10', stop: 9 }), set: async () => {} } });
  await settle();
  assert.equal(restored.$('capital').value, '1.0000000000000001');
  assert.equal(restored.$('risk').value, '2');
  assert.equal(restored.$('stop').value, '9');
});

test('position calculator rejects absurd exponents with the validation error', async () => {
  for (const capital of ['1e100000000', '1e-100000000', '1e400']) {
    const tool = positionCalculator({ capital, risk: '1', entry: '10', stop: '9' });
    const started = Date.now();
    await tool.$('calculator').fire('submit');
    assert.ok(Date.now() - started < 1000);
    assert.equal(tool.$('form-error').hidden, false, capital);
    assert.equal(tool.$('result').hidden, true);
  }
  const ok = positionCalculator({ capital: '1e5', risk: '1', entry: '10', stop: '9.5' });
  await ok.$('calculator').fire('submit');
  assert.equal(ok.$('shares').textContent, '2,000 股');
});

test('position calculator rejects a huge pasted mantissa without building BigInts for it', async () => {
  const huge = `1.${'0'.repeat(3_000_000)}1`;
  for (const [field, value] of [['capital', huge], ['entry', huge], ['stop', `0.${'0'.repeat(3_000_000)}1`], ['risk', `0.${'1'.repeat(1500)}`]]) {
    const tool = positionCalculator({ capital: '100000', risk: '1', entry: '10', stop: '9', [field]: value });
    const started = Date.now();
    await tool.$('calculator').fire('submit');
    assert.ok(Date.now() - started < 1000, field);
    assert.equal(tool.$('form-error').hidden, false, field);
    assert.match(tool.$('form-error').textContent, /位数过多/, field);
    assert.equal(tool.$('result').hidden, true);
  }
  // Trailing fractional zeros do not count: the value is unchanged, so it still calculates.
  const zeros = positionCalculator({ capital: `100000.${'0'.repeat(3_000_000)}`, risk: '1', entry: '10', stop: '9.5' });
  const started = Date.now();
  await zeros.$('calculator').fire('submit');
  assert.ok(Date.now() - started < 1000);
  assert.equal(zeros.$('shares').textContent, '2,000 股');
});

// ---------- quick-notes ----------

const NOTE_TEMPLATE = ['article', 'note', [['p', 'note-body'], ['p', 'note-time'], ['div', 'note-actions', [
  ['button', 'text-button edit'], ['button', 'text-button copy'], ['button', 'text-button danger delete']]]]];

function quickNotes(initial = {}, { beforeSet, ready } = {}) {
  const store = new Map(Object.entries(initial));
  const writes = [];
  const handlers = new Set();
  const toolbox = {
    ready: ready || (async () => ({})),
    ui: { toast: async () => {} },
    runtime: { registerFlushHandler: (handler) => { handlers.add(handler); return () => handlers.delete(handler); } },
    storage: {
      get: async (key) => store.has(key) ? structuredClone(store.get(key)) : null,
      set: async (key, value) => { if (beforeSet) await beforeSet(key); writes.push(key); store.set(key, structuredClone(value)); },
      remove: async (key) => { store.delete(key); },
    },
  };
  const tool = loadTool('quick-notes', toolbox, { templates: { 'note-template': NOTE_TEMPLATE } });
  // Like the injected SDK: the handler set is snapshotted when the close starts, and the close fails if any handler rejects.
  const flush = () => Promise.allSettled([...handlers].map((handler) => Promise.resolve().then(handler))).then((results) => {
    const failed = results.find((result) => result.status === 'rejected');
    if (failed) throw failed.reason;
  });
  return { ...tool, store, writes, flush };
}
const noteButton = (tool, index, kind) => tool.$('notes').children[index].querySelector(`.${kind}`);

test('quick notes delete can be undone', async () => {
  const tool = quickNotes({ notes: [{ id: 'a', text: 'keep me', updatedAt: 2 }, { id: 'b', text: 'other', updatedAt: 1 }] });
  await settle();
  await noteButton(tool, 0, 'delete').fire('click');
  assert.deepEqual(tool.store.get('notes').map((note) => note.id), ['b']);
  assert.equal(tool.$('undo-bar').hidden, false);
  await tool.$('undo').fire('click');
  assert.deepEqual(tool.store.get('notes').map((note) => note.id), ['a', 'b']);
  assert.equal(tool.$('undo-bar').hidden, true);
  assert.equal(tool.$('notes').children.length, 2);
});

test('quick notes deleting the note being edited clears the editor and its draft', async () => {
  const tool = quickNotes({ notes: [{ id: 'a', text: 'keep me', updatedAt: 2 }, { id: 'b', text: 'other', updatedAt: 1 }] });
  await settle();
  await noteButton(tool, 0, 'edit').fire('click');
  tool.$('note-input').value = 'keep me, edited';
  await tool.flush();
  assert.equal(tool.store.has('draft'), true);
  await noteButton(tool, 0, 'delete').fire('click');
  assert.equal(tool.$('note-input').value, '');
  assert.equal(tool.$('cancel-edit').hidden, true);
  assert.equal(tool.store.has('draft'), false);
  await tool.$('undo').fire('click');
  await tool.$('editor').fire('submit');
  assert.deepEqual(tool.store.get('notes').map((note) => note.text), ['keep me', 'other']);
  await tool.flush();
  assert.equal(tool.store.has('draft'), false);
});

test('quick notes editing can be cancelled without changing the note', async () => {
  const tool = quickNotes({ notes: [{ id: 'a', text: 'original', updatedAt: 1 }] });
  await settle();
  await noteButton(tool, 0, 'edit').fire('click');
  assert.equal(tool.$('cancel-edit').hidden, false);
  tool.$('note-input').value = 'changed';
  await tool.$('cancel-edit').fire('click');
  assert.equal(tool.$('note-input').value, '');
  assert.equal(tool.$('editor-state').textContent, '新笔记');
  assert.equal(tool.$('cancel-edit').hidden, true);
  assert.equal(tool.store.get('notes')[0].text, 'original');
});

test('quick notes keeps an unsaved draft through the flush handler', async () => {
  const first = quickNotes({ notes: [{ id: 'a', text: 'original', updatedAt: 1 }] });
  await settle();
  await noteButton(first, 0, 'edit').fire('click');
  first.$('note-input').value = 'half-written edit';
  await first.flush();
  assert.deepEqual(first.store.get('draft'), { text: 'half-written edit', editingId: 'a' });

  const second = quickNotes(Object.fromEntries(first.store));
  await settle();
  assert.equal(second.$('note-input').value, 'half-written edit');
  assert.equal(second.$('cancel-edit').hidden, false);
  await second.$('editor').fire('submit');
  assert.equal(second.store.get('notes')[0].text, 'half-written edit');
  assert.equal(second.store.has('draft'), false);
});

test('quick notes flush during a save does not keep the saved text as a draft', async () => {
  const gate = defer();
  const tool = quickNotes({ notes: [] }, { beforeSet: (key) => key === 'notes' ? gate.promise : undefined });
  await settle();
  tool.$('note-input').value = 'saved then closed';
  const saving = tool.$('editor').fire('submit');
  await tick();
  // The host tears the page down as soon as the flush handler settles; what is stored then is what survives.
  let draftAtFlush = null;
  const flushing = tool.flush().then(() => { draftAtFlush = tool.store.get('draft') ?? null; });
  await settle();
  gate.resolve();
  await Promise.all([saving, flushing]);
  assert.deepEqual(tool.store.get('notes').map((note) => note.text), ['saved then closed']);
  assert.equal(draftAtFlush, null);
});

test('quick notes closing while startup is still restoring keeps what was typed', async () => {
  const ready = defer();
  const tool = quickNotes({ notes: [{ id: 'a', text: 'original', updatedAt: 1 }] }, { ready: () => ready.promise });
  await settle();
  tool.$('note-input').value = 'typed during startup';
  let draftAtFlush = null;
  const flushing = tool.flush().then(() => { draftAtFlush = tool.store.get('draft') ?? null; });
  await settle();
  ready.resolve({});
  await flushing;
  assert.deepEqual(draftAtFlush, { text: 'typed during startup', editingId: null });
  assert.equal(tool.$('note-input').value, 'typed during startup');
});

test('quick notes closing while deleting the edited note does not keep its text as a draft', async () => {
  let gate = null;
  const tool = quickNotes({ notes: [{ id: 'a', text: 'secret', updatedAt: 2 }, { id: 'b', text: 'other', updatedAt: 1 }] },
    { beforeSet: (key) => key === 'notes' ? gate?.promise : undefined });
  await settle();
  await noteButton(tool, 0, 'edit').fire('click');
  tool.$('note-input').value = 'secret, edited';
  await tool.flush();
  assert.equal(tool.store.has('draft'), true);
  gate = defer();
  const deleting = noteButton(tool, 0, 'delete').fire('click');
  await tick();
  // The host tears the page down as soon as the flush handler settles; what is stored then is what survives.
  let draftAtFlush = 'unset';
  const flushing = tool.flush().then(() => { draftAtFlush = tool.store.get('draft') ?? null; });
  await settle();
  gate.resolve();
  await Promise.all([deleting, flushing]);
  assert.equal(draftAtFlush, null);
  assert.deepEqual(tool.store.get('notes').map((note) => note.id), ['b']);

  const reopened = quickNotes(Object.fromEntries(tool.store));
  await settle();
  assert.equal(reopened.$('note-input').value, '');
});

// ---------- notification-lab ----------

function notificationLab({ sessions = [], saved = null, start, update, end, ready, stop, cancelTimer } = {}) {
  const calls = { start: [], update: [], end: [], bgStart: 0, set: 0, timers: [] };
  const listeners = {};
  let sessionCounter = 0;
  const toolbox = {
    ready: ready || (async () => ({ hostVersion: 'test', apiVersion: '1.0', runtimeState: {} })),
    runtime: {
      onStateChanged: (listener) => { listeners.state = listener; listener({ foreground: true, closing: false }); return () => {}; },
      registerFlushHandler: (handler) => { listeners.flush = handler; return () => {}; },
    },
    storage: { get: async () => saved, set: async () => { calls.set += 1; } },
    background: {
      start: async () => { calls.bgStart += 1; sessionCounter += 1; return { sessionId: `session-${sessionCounter}-xxxxxxxx` }; },
      stop: async (id) => { if (stop) return stop(id); },
      listSessions: async () => sessions,
      setTimer: async (...args) => { calls.timers.push(args); },
      cancelTimer: async (key) => { if (cancelTimer) return cancelTimer(key); },
      onTimer: (listener) => { listeners.timer = listener; },
      onRestore: (listener) => { listeners.restore = listener; },
    },
    notifications: {
      post: async () => {}, update: async () => {}, cancel: async () => {},
      live: {
        start: async (request) => { calls.start.push(request); return start ? start(request) : { androidLive: 'REQUESTED', hyperOsIsland: 'UNAVAILABLE', hyperOsProtocolVersion: 0, hyperOsPermissionReported: false }; },
        update: async (request) => { calls.update.push(request); return update ? update(request) : { androidLive: 'REQUESTED' }; },
        end: async (id) => { calls.end.push(id); if (end) return end(id); },
      },
    },
  };
  const tool = loadTool('notification-lab', toolbox, {
    marker: '  bootWork = boot();\n})();',
    expose: 'state, cleanText, updateLive, startLive, endLive, render, bootWork, setForeground: (value) => { runtimeForeground = value; }',
  });
  return { ...tool, calls, listeners };
}

test('notification lab cleanText honours its max length', () => {
  const lab = notificationLab();
  assert.equal(lab.api.cleanText('abcdefghijklmnop', 12), 'abcdefghijk…');
  assert.equal(Array.from(lab.api.cleanText('一二三四五六七八九十十一十二十三', 12)).length, 12);
  assert.equal(lab.api.cleanText('short', 12), 'short');
  assert.equal(lab.api.cleanText(`long ${'x'.repeat(500)}`).length, 505);
});

test('notification lab live ticks do not write storage; flush does', async () => {
  const lab = notificationLab();
  await lab.api.bootWork;
  await lab.api.startLive();
  const writes = lab.calls.set;
  for (let i = 0; i < 5; i += 1) await lab.api.updateLive();
  assert.equal(lab.calls.update.length, 5);
  assert.equal(lab.calls.set, writes);
  await lab.listeners.flush();
  assert.equal(lab.calls.set, writes + 1);
});

test('notification lab checkpoints the background tick at most every 30 seconds', async () => {
  const lab = notificationLab();
  await lab.api.bootWork;
  await lab.api.startLive();
  const realNow = Date.now;
  let now = realNow();
  Date.now = () => now;
  try {
    const writes = lab.calls.set;
    for (let i = 0; i < 14; i += 1) { now += 2000; await lab.api.updateLive(); }
    assert.equal(lab.calls.set, writes, 'no write within the first 30 seconds of updates');
    now += 2000; await lab.api.updateLive();
    assert.equal(lab.calls.set, writes + 1, 'a process death after this loses at most 30 seconds of ticks');
    for (let i = 0; i < 14; i += 1) { now += 2000; await lab.api.updateLive(); }
    assert.equal(lab.calls.set, writes + 1);
  } finally { Date.now = realNow; }
});

test('notification lab re-acquires the session after SESSION_ENDED', async () => {
  let failed = false;
  const lab = notificationLab({ update: () => { if (!failed) { failed = true; throw apiError('SESSION_ENDED'); } return {}; } });
  await lab.api.bootWork;
  await lab.api.startLive();
  const firstSession = lab.api.state.sessionId;
  await lab.api.updateLive();
  assert.equal(lab.calls.bgStart, 2);
  assert.notEqual(lab.api.state.sessionId, firstSession);
  assert.equal(lab.calls.start.at(-1).sessionId, lab.api.state.sessionId);
});

test('notification lab endLive tolerates NOT_FOUND', async () => {
  const lab = notificationLab({ end: () => { throw apiError('NOT_FOUND'); } });
  await lab.api.bootWork;
  await lab.api.startLive();
  await lab.api.endLive();
  assert.equal(lab.api.state.live, false);
});

test('notification lab disables the interval select while busy', async () => {
  const lab = notificationLab();
  await lab.api.bootWork;
  lab.api.state.busy = true;
  lab.api.render();
  assert.equal(lab.$('interval').disabled, true);
  lab.api.state.busy = false;
  lab.api.render();
  assert.equal(lab.$('interval').disabled, false);
});

test('notification lab starts the live notification once when onRestore races boot', async () => {
  const ready = defer();
  const lab = notificationLab({
    ready: () => ready.promise,
    sessions: [{ sessionId: 'restored-session' }],
    saved: { preset: 'market', tick: 3, intervalMs: 2000, live: true, auto: false },
  });
  lab.listeners.restore({ reason: 'process' });
  ready.resolve({ hostVersion: 'test', apiVersion: '1.0' });
  await lab.api.bootWork;
  await settle(20);
  assert.equal(lab.calls.start.length, 1);
  assert.equal(lab.calls.start[0].sessionId, 'restored-session');
});

test('notification lab stopping the background during an auto update does not restart it', async () => {
  const pending = defer();
  let updates = 0;
  const lab = notificationLab({ update: () => { updates += 1; return updates === 1 ? pending.promise : {}; } });
  await lab.api.bootWork;
  await lab.api.startLive();
  lab.api.state.auto = true;
  const update = lab.api.updateLive();
  await settle();
  await lab.$('stop-background').fire('click');
  assert.equal(lab.api.state.sessionId, null);
  pending.reject(apiError('INVALID_SESSION'));
  await update.catch(() => {});
  await settle();
  assert.equal(lab.calls.bgStart, 1, 'no new background session after the user stopped it');
  assert.equal(lab.calls.start.length, 1, 'no new live notification after the user stopped it');
  assert.equal(lab.api.state.sessionId, null);
  assert.equal(lab.api.state.live, false);
  assert.equal(lab.api.state.liveResult, null);
});

test('notification lab timer firing while stopping cancels the timer does not reacquire the session', async () => {
  const cancelGate = defer();
  const stopGate = defer();
  const pendingUpdate = defer();
  pendingUpdate.promise.catch(() => {}); // No update may be waiting on it once the fix blocks the timer.
  let stopCalled = false;
  const lab = notificationLab({
    update: () => pendingUpdate.promise,
    cancelTimer: () => cancelGate.promise,
    stop: () => { stopCalled = true; return stopGate.promise; },
  });
  await lab.api.bootWork;
  await lab.api.startLive();
  lab.api.state.auto = true;
  const stopping = lab.$('stop-background').fire('click');
  await settle();
  // A queued timer callback runs while stopBackground waits for cancelTimer.
  lab.listeners.timer({ key: 'notification-lab-live' });
  await settle();
  cancelGate.resolve();
  await settle();
  assert.equal(stopCalled, true);
  // The session ends under the in-flight update before stopBackground clears its state.
  pendingUpdate.reject(apiError('INVALID_SESSION'));
  await settle();
  stopGate.resolve();
  await stopping;
  await settle();
  assert.equal(lab.calls.bgStart, 1, 'no replacement background session');
  assert.equal(lab.calls.start.length, 1, 'no replacement live notification');
  assert.equal(lab.api.state.sessionId, null);
});

// ---------- background-task-demo ----------

const TASK_TEMPLATE = ['article', 'task', [['div', 'task-heading', [['strong', 'task-key'], ['span', 'task-state']]],
  ['p', 'task-type'], ['p', 'task-result'], ['button', 'cancel']]];

function taskDemo({ getResult, cancel } = {}) {
  let foreground = null;
  const calls = { list: 0, cancel: [] };
  const toolbox = {
    ready: async () => ({}),
    runtime: { onStateChanged: (listener) => { foreground = listener; listener({ foreground: true, closing: false }); return () => {}; } },
    background: {
      list: async () => { calls.list += 1; return [
        { taskId: 't1', key: 'github-repo-1', state: 'QUEUED', periodic: false },
        { taskId: 't2', key: 'demo-notify-2', state: 'QUEUED', periodic: false },
      ]; },
      getResult: getResult || (async () => null),
      cancel: async (id) => { calls.cancel.push(id); if (cancel) await cancel(id); },
    },
  };
  const tool = loadTool('background-task-demo', toolbox, { templates: { 'task-template': TASK_TEMPLATE } });
  return { ...tool, calls, setForeground: (value) => foreground({ foreground: value, closing: false }) };
}

test('background task list survives one failing getResult', async () => {
  const demo = taskDemo({ getResult: async (id) => { if (id === 't1') throw apiError('INTERNAL'); return null; } });
  await settle();
  const rows = demo.$('tasks').children;
  assert.equal(rows.length, 2);
  assert.match(rows[0].querySelector('.task-result').textContent, /结果读取失败/);
  assert.equal(rows[1].querySelector('.task-result').textContent, '尚未产生执行结果');
});

test('background task cancel is disabled while in flight', async () => {
  const pending = defer();
  const demo = taskDemo({ cancel: () => pending.promise });
  await settle();
  const button = demo.$('tasks').children[0].querySelector('.cancel');
  const first = button.fire('click');
  assert.equal(button.disabled, true);
  await button.fire('click');
  assert.deepEqual(demo.calls.cancel, ['t1']);
  pending.resolve();
  await first;
});

test('background task cancel failure re-enables the re-rendered row', async () => {
  const pending = defer();
  const demo = taskDemo({ cancel: () => pending.promise });
  await settle();
  const first = demo.$('tasks').children[0].querySelector('.cancel').fire('click');
  await demo.$('refresh').fire('click');
  await settle();
  const replacement = demo.$('tasks').children[0].querySelector('.cancel');
  assert.equal(replacement.disabled, true);
  pending.reject(apiError('INTERNAL'));
  await first;
  assert.equal(replacement.disabled, false);
  assert.equal(replacement.textContent, '取消任务');
});

test('background task list refreshes when returning to the foreground', async () => {
  const demo = taskDemo();
  await settle();
  const before = demo.calls.list;
  demo.setForeground(false);
  demo.setForeground(true);
  await settle();
  assert.equal(demo.calls.list, before + 1);
});
