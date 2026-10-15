const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const dir = path.resolve(__dirname, '..');
const read = (name) => fs.readFileSync(path.join(dir, name), 'utf8');

function loadRuntime(toolbox) {
  const window = { ToolBox: toolbox };
  const document = { documentElement: { dataset: { runtime: 'toolbox' } } };
  vm.runInNewContext(read('runtime.js'), { window, document, TextDecoder, URL, Blob: class {}, setTimeout });
  return window.ZZRuntime;
}

test('zhuzhang version is the same in the manifest, the app and the sidebar', () => {
  const { version } = JSON.parse(read('manifest.json'));
  assert.match(read('app.js'), new RegExp(`const VERSION = '${version.replaceAll('.', '\\.')}';`));
  assert.ok(read('index.html').includes(`筑账 ${version} · `));
});

test('zhuzhang keeps the id its installed copies use, so updates keep the ledger', () => {
  assert.equal(JSON.parse(read('manifest.json')).id, 'io.gkeyes.zhuzhang');
  assert.match(read('app.js'), /const KEY = 'ledger\.v1';/);
});

test('zhuzhang follows the system dark mode', () => {
  assert.match(read('index.html'), /<meta content="light dark" name="color-scheme"\/>/);
  assert.match(read('style.css'), /@media \(prefers-color-scheme:dark\)\{/);
  assert.equal(JSON.parse(read('manifest.json')).ui.statusBarStyle, 'auto');
});

test('zhuzhang registers its close-time save with the host when it can', () => {
  let registered = null;
  loadRuntime({ runtime: { registerFlushHandler(handler) { registered = handler; } } }).onFlush(async () => 'saved');
  assert.equal(typeof registered, 'function');
  assert.doesNotThrow(() => loadRuntime({}).onFlush(async () => {}));
});
