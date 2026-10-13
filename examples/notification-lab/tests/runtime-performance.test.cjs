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

test('notification lab retains only the latest 200 events and reconstructs them on return', () => {
  const toolbox = { background: { onTimer() {}, onRestore() {} } };
  const h = loadApp('notification-lab', toolbox);
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

