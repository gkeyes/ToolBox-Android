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

// ---------- quick-notes ----------

const NOTE_TEMPLATE = ['article', 'note', [['p', 'note-body'], ['p', 'note-time'], ['div', 'note-actions', [
  ['button', 'text-button edit'], ['button', 'text-button copy'], ['button', 'text-button danger delete']]]]];

function quickNotes(initial = {}) {
  const store = new Map(Object.entries(initial));
  const writes = [];
  let flush = null;
  const toolbox = {
    ready: async () => ({}),
    ui: { toast: async () => {} },
    runtime: { registerFlushHandler: (handler) => { flush = handler; return () => {}; } },
    storage: {
      get: async (key) => store.has(key) ? structuredClone(store.get(key)) : null,
      set: async (key, value) => { writes.push(key); store.set(key, structuredClone(value)); },
      remove: async (key) => { store.delete(key); },
    },
  };
  const tool = loadTool('quick-notes', toolbox, { templates: { 'note-template': NOTE_TEMPLATE } });
  return { ...tool, store, writes, flush: () => flush() };
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

// ---------- notification-lab ----------

function notificationLab({ sessions = [], saved = null, start, update, end, ready } = {}) {
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
      stop: async () => {},
      listSessions: async () => sessions,
      setTimer: async (...args) => { calls.timers.push(args); },
      cancelTimer: async () => {},
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

test('background task list refreshes when returning to the foreground', async () => {
  const demo = taskDemo();
  await settle();
  const before = demo.calls.list;
  demo.setForeground(false);
  demo.setForeground(true);
  await settle();
  assert.equal(demo.calls.list, before + 1);
});
