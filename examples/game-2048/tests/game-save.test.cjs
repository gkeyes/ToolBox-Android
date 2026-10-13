const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '..');

function loadGame(toolbox) {
  const nodes = new Map();
  const element = () => ({
    children: [], dataset: {}, style: {}, hidden: true, textContent: '', listeners: {},
    classList: { add() {}, remove() {} },
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); },
    setAttribute() {}, focus() {}, showModal() {}, setPointerCapture() {},
    append(...items) { this.children.push(...items); },
    replaceChildren(...items) { this.children = items; },
  });
  const document = {
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); },
    createElement: element,
  };
  const listeners = {};
  const window = {
    ToolBox: toolbox,
    requestAnimationFrame() { return 0; },
    setTimeout, clearTimeout,
    addEventListener(type, listener) { (listeners[type] ||= []).push(listener); },
  };
  const context = vm.createContext({ window, document, console });
  context.globalThis = context;
  const run = (file) => {
    const filename = path.join(root, file);
    vm.runInContext(fs.readFileSync(filename, 'utf8'), context, { filename });
  };
  run('game.js');
  window.Game2048Core = context.Game2048Core;
  run('app.js');
  return {
    nodes,
    key(name) { for (const listener of listeners.keydown || []) listener({ key: name, preventDefault() {} }); },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('flush retries a save that fails after closing started waiting', async () => {
  const sets = [];
  let flush = null;
  let failNext = 0;
  let pendingWrite = null;
  const toolbox = {
    ready: async () => ({}),
    runtime: { registerFlushHandler(handler) { flush = handler; } },
    storage: {
      get: async () => null,
      set: (_key, value) => new Promise((resolve, reject) => {
        pendingWrite = () => {
          if (failNext > 0) { failNext -= 1; reject(Object.assign(new Error('busy'), { code: 'BUSY' })); }
          else { sets.push(value); resolve(); }
        };
      }),
    },
  };
  const game = loadGame(toolbox);
  for (let index = 0; index < 5; index += 1) await settle();
  assert.equal(typeof flush, 'function');
  for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']) {
    game.key(key);
    await settle();
    if (pendingWrite) break;
  }
  await settle();
  assert.ok(pendingWrite, 'one move started a write');
  let done = false;
  let failure = null;
  const flushing = flush().then(() => { done = true; }, (error) => { done = true; failure = error; });
  await settle();
  // The in-flight write fails only after flush has started waiting for it.
  failNext = 1;
  const inFlight = pendingWrite;
  pendingWrite = null;
  inFlight();
  for (let index = 0; index < 20 && !done; index += 1) {
    await settle();
    if (pendingWrite) { const retry = pendingWrite; pendingWrite = null; retry(); }
  }
  await flushing;
  assert.equal(failure, null, 'flush retried instead of reporting failure');
  assert.equal(sets.length, 1, 'the retry saved the game');
  assert.equal(game.nodes.get('save-state').textContent, '自动保存');
});

test('a single failed write does not switch the game to session-only mode', async () => {
  let fail = true;
  const sets = [];
  const toolbox = {
    ready: async () => ({}),
    runtime: { registerFlushHandler() {} },
    storage: {
      get: async () => null,
      set: async (_key, value) => {
        if (fail) { fail = false; throw new Error('busy'); }
        sets.push(value);
      },
    },
  };
  const game = loadGame(toolbox);
  for (let index = 0; index < 5; index += 1) await settle();
  for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']) game.key(key);
  for (let index = 0; index < 10; index += 1) await settle();
  assert.ok(sets.length >= 1, 'later moves are still saved');
});

function manualHost() {
  const host = { sets: [], pending: [], flush: null, stateListener: null };
  host.toolbox = {
    ready: async () => ({}),
    runtime: {
      registerFlushHandler(handler) { host.flush = handler; },
      onStateChanged(listener) { host.stateListener = listener; listener({ foreground: true, closing: false }); },
    },
    storage: {
      get: async () => null,
      set: (_key, value) => new Promise((resolve) => { host.pending.push(() => { host.sets.push(value); resolve(); }); }),
    },
  };
  host.release = () => { const writes = host.pending; host.pending = []; for (const write of writes) write(); };
  return host;
}

async function moveOnce(game, host) {
  const moves = () => game.nodes.get('move-count').textContent;
  const before = moves();
  for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']) {
    game.key(key);
    for (let index = 0; index < 3; index += 1) await settle();
    if (moves() !== before) return;
  }
  assert.fail('no move was possible');
}

test('flush waits for a save queued by a move made while it was waiting', async () => {
  const host = manualHost();
  const game = loadGame(host.toolbox);
  for (let index = 0; index < 5; index += 1) await settle();
  await moveOnce(game, host);
  let done = false;
  const flushing = host.flush().then(() => { done = true; });
  await settle();
  await moveOnce(game, host);
  const moves = Number(game.nodes.get('move-count').textContent);
  for (let index = 0; index < 10 && !done; index += 1) { host.release(); await settle(); }
  await flushing;
  assert.equal(host.pending.length, 0, 'no write left in flight after flush');
  assert.equal(host.sets.at(-1).moveCount, moves, 'the latest move was saved');
});

test('moves are ignored while the tool is closing', async () => {
  const host = manualHost();
  const game = loadGame(host.toolbox);
  for (let index = 0; index < 5; index += 1) await settle();
  host.stateListener({ foreground: false, closing: true });
  for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']) game.key(key);
  for (let index = 0; index < 5; index += 1) await settle();
  assert.equal(host.pending.length, 0);
  assert.equal(game.nodes.get('move-count').textContent, '0');
  host.stateListener({ foreground: true, closing: false });
  await moveOnce(game, host);
});
