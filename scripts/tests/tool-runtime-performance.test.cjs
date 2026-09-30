const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const examples = path.resolve(__dirname, '../../examples');

function loadApp(name, expose, toolbox, extraWindow = {}) {
  const filename = path.join(examples, name, 'app.js');
  const marker = '  bootWork = boot();\n})();';
  let source = fs.readFileSync(filename, 'utf8');
  assert.equal(source.split(marker).length, 2, `${name} startup changed`);
  source = source.replace(marker, `  window.testApi = { ${expose} };\n})();`);
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
  vm.runInNewContext(source, { window, document, URL, Intl, Date, console, setTimeout, clearTimeout }, { filename });
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
