import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const root = resolve(import.meta.dirname, '../..');
const read = path => readFileSync(resolve(root, path), 'utf8');
const api = read('tool-api/src/main/kotlin/io/toolbox/tool/api/ToolBoxApiV1.kt');
const manual = read('sdk/help/manual.md');
const schema = JSON.parse(read('schema/manifest.schema.json'));
const sdk = manual.match(/```ts toolbox-api\.d\.ts\n([\s\S]*?)\n```/)?.[1];
assert(sdk, 'Developer Help must contain its SDK declaration');
const union = name => [...sdk.match(new RegExp(`export type ${name} =([\\s\\S]*?);`))[1].matchAll(/"([^"]+)"/g)].map(m => m[1]).sort();
const methods = [...api.matchAll(/MethodDescriptor\("([^"]+)"/g)].map(m => m[1]).sort();
const capabilities = [...api.matchAll(/CapabilityDescriptor\(ToolBoxCapabilityId\.\w+, "([^"]+)"/g)].map(m => m[1]).sort();
assert.deepEqual(union('ToolBoxMethodName'), methods);
assert.deepEqual(union('ToolBoxCapability'), capabilities);
assert.deepEqual(schema.properties.permissions.items.properties.name.enum.toSorted(), capabilities);
assert.equal(schema.properties.network.properties.maxResponseBytes.maximum, Number.MAX_SAFE_INTEGER);
const source = read('tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeWebMessageBridge.kt');
const body = source.slice(source.indexOf('private fun shim(')).match(/return """([\s\S]*?)"""\.trimIndent\(\)/)[1];
const shim = body.replaceAll("${'$'}", '$').replaceAll('${identity.versionCode}', '1')
  .replaceAll('$BRIDGE_OBJECT', '__toolboxNative').replaceAll('$nonce', '"nonce"')
  .replaceAll('$toolId', '"io.example.contract"').replaceAll('$generation', '"generation"');
const sent = [];
const bridge = { postMessage(encoded) {
  const message = JSON.parse(encoded); sent.push(message);
  const result = message.method === 'network.readStream' ? { data: 'AQID', done: true, receivedBytes: 3 } : {};
  queueMicrotask(() => bridge.onmessage({ data: JSON.stringify({ id: message.id, ok: true, result }) }));
}};
const context = vm.createContext({ __toolboxNative: bridge, Uint8Array, atob, queueMicrotask, addEventListener() {}, dispatchEvent() {} });
vm.runInContext(shim, context);
assert.deepEqual([...source.matchAll(/call\('([^']+)'/g)].map(m => m[1]).filter((v,i,a) => a.indexOf(v) === i).sort(), methods);
const runtimeRpc = read('tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeRpc.kt');
for (const retired of ['network.authorizeDomain', 'network.listDomains', 'allowDomains', 'allowUserDomains', 'allowRedirects', 'storageBytes', 'maxBridgePayloadBytes', 'USER_GESTURE_REQUIRED', 'RATE_LIMITED', 'retryAfterMs']) {
  assert(!api.includes(retired), `API descriptor retains retired contract: ${retired}`);
  assert(!manual.includes(retired), `SDK manual retains retired contract: ${retired}`);
  assert(!source.includes(retired), `Web bridge retains retired contract: ${retired}`);
  assert(!runtimeRpc.includes(retired), `RPC dispatcher retains retired contract: ${retired}`);
  assert(!read('tool-package/src/main/kotlin/io/toolbox/tool/packagekit/ManifestValidator.kt').includes(retired), `Manifest parser retains retired field: ${retired}`);
  assert(!read('schema/manifest.schema.json').includes(retired), `Manifest schema retains retired field: ${retired}`);
}
for (const clientPath of [
  'examples/nextflux/src/api/miniflux.js',
  'examples/nextflux/src/toolbox/cache.js',
  'examples/nextflux/src/toolbox/cache-worker.js',
  'examples/nextflux/src/toolbox/cache-client.js',
  'examples/nextflux/src/toolbox/actions.js',
  'examples/nextflux/src/toolbox/network.js',
  'examples/nextflux/src/toolbox/title-filter/title-filter-api.mjs',
  'examples/health-records/web/app.mjs',
  'examples/health-records/web/io.mjs',
  'examples/rss-scout/app.js',
  'examples/rss-scout/network.js',
  'examples/stock-monitor/app.js',
  'examples/github-actions-watcher/app.js',
  'examples/notification-lab/app.js',
]) {
  const client = read(clientPath);
  for (const retired of ['USER_GESTURE_REQUIRED', 'RATE_LIMITED', 'retryAfterMs']) {
    assert(!client.includes(retired), `${clientPath} retains retired host error: ${retired}`);
  }
}
await context.ToolBox.clipboard.writeText('');
assert.equal(sent.findLast(request => request.method === 'clipboard.writeText')?.params.text, '');
await context.ToolBox.share.text('line\nnext');
assert.equal(sent.findLast(request => request.method === 'share.text')?.params.text, 'line\nnext');
const chunk = await context.ToolBox.network.readStream('stream-fixture', { expectedChunkBytes: 131072 });
assert.equal(sent.findLast(request => request.method === 'network.readStream')?.params.expectedChunkBytes, 131072);
assert.deepEqual([...chunk.data], [1, 2, 3]);
await assert.rejects(context.ToolBox.network.readStream('stream-fixture', { expectedChunkBytes: Number.MAX_SAFE_INTEGER + 1 }));

function runtimeHarness(respond = () => ({ ok: true, result: {} })) {
  const sent = [];
  const errors = [];
  const timers = new Map();
  let clock = 0;
  let timerId = 0;
  let performanceStep = 0;
  let measured = 0;
  const initialState = { generation: 'generation', revision: 0, foreground: false, closing: false };
  const native = { postMessage(encoded) {
    const request = JSON.parse(encoded);
    sent.push(request);
    const result = request.method === 'ready'
      ? { ok: true, result: { runtimeState: initialState } }
      : respond(request);
    if (result) queueMicrotask(() => native.onmessage({ data: JSON.stringify({ id: request.id, ...result }) }));
  } };
  const page = vm.createContext({
    __toolboxNative: native,
    Uint8Array,
    atob,
    queueMicrotask,
    performance: { now: () => (measured += performanceStep) },
    setTimeout: (callback, delay = 0) => {
      const id = ++timerId;
      timers.set(id, { callback, at: clock + delay });
      return id;
    },
    clearTimeout: id => timers.delete(id),
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options?.detail; } },
    addEventListener() {},
    dispatchEvent(event) { if (event.type === 'toolbox:runtime.error') errors.push(event.detail); },
  });
  vm.runInContext(shim, page);
  // Drain nested promise/allSettled/retry continuations without advancing fake timers.
  const settle = async () => { for (let index = 0; index < 64; index++) await Promise.resolve(); };
  const emit = payload => native.onmessage({ data: JSON.stringify(payload) });
  const runNextTimer = async (advance = 0) => {
    clock += advance;
    const next = [...timers].filter(([, task]) => task.at <= clock)
      .sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
    if (!next) return false;
    timers.delete(next[0]);
    next[1].callback();
    await settle();
    return true;
  };
  return { page, sent, errors, timers, emit, settle, runNextTimer,
    setPerformanceStep: step => { performanceStep = step; measured = 0; } };
}

const stateHarness = runtimeHarness(request => ({ ok: true,
  result: request.method === 'runtime.getState'
    ? { generation: 'generation', revision: 1, foreground: true, closing: false } : {} }));
await stateHarness.settle();
assert.equal(stateHarness.sent.filter(request => request.method === 'ready').length, 1, 'SDK must send ready once');
const revisions = [];
stateHarness.page.ToolBox.runtime.onStateChanged(state => revisions.push(state.revision));
await stateHarness.settle();
assert.deepEqual(revisions, [0]);
const foregroundState = { generation: 'generation', revision: 1, foreground: true, closing: false };
stateHarness.emit({ type: 'runtimeState', data: foregroundState });
stateHarness.emit({ type: 'runtimeState', data: foregroundState });
stateHarness.emit({ type: 'runtimeState', data: { ...foregroundState, revision: 0 } });
assert.deepEqual(revisions, [0, 1], 'Duplicate or stale revisions must not reach listeners');
assert.equal((await stateHarness.page.ToolBox.runtime.getState()).revision, 1);
assert.equal(stateHarness.sent.filter(request => request.method === 'ready').length, 1);

const eventsHarness = runtimeHarness();
await eventsHarness.settle();
for (let sequence = 1; sequence <= 64; sequence++) {
  eventsHarness.emit({ type: 'event', event: 'background.timer', generation: 'generation', sequence,
    data: { sequence } });
}
await eventsHarness.settle();
assert(eventsHarness.sent.some(request => request.method === 'runtime.ackEvents' && request.params.sequence === 32),
  '32 received events must trigger an ACK');
assert(eventsHarness.sent.some(request => request.method === 'runtime.ackEvents' && request.params.sequence === 64));
eventsHarness.emit({ type: 'event', event: 'background.timer', generation: 'generation', sequence: 65,
  data: { sequence: 65 } });
const replayed = [];
eventsHarness.page.ToolBox.background.onTimer(value => replayed.push(value.sequence));
await eventsHarness.settle();
await eventsHarness.runNextTimer();
assert.deepEqual(replayed, Array.from({ length: 32 }, (_, index) => index + 1),
  'Late subscription must replay FIFO and yield after 32 inspected events');
await eventsHarness.runNextTimer();
assert.equal(replayed.length, 64);
await eventsHarness.runNextTimer();
assert.deepEqual(replayed, Array.from({ length: 65 }, (_, index) => index + 1));
await eventsHarness.runNextTimer(49);
assert(!eventsHarness.sent.some(request => request.method === 'runtime.ackEvents' && request.params.sequence === 65));
await eventsHarness.runNextTimer(1);
assert(eventsHarness.sent.some(request => request.method === 'runtime.ackEvents' && request.params.sequence === 65),
  'A partial batch must be acknowledged at the 50 ms soft target');

let ackAttempts = 0;
const ackBusyHarness = runtimeHarness(request => {
  if (request.method === 'runtime.ackEvents' && ++ackAttempts === 1) {
    return { ok: false, error: { code: 'BUSY', message: 'Control request budget is full' } };
  }
  return { ok: true, result: {} };
});
await ackBusyHarness.settle();
for (let sequence = 1; sequence <= 32; sequence++) ackBusyHarness.emit({ type: 'event',
  event: 'background.timer', generation: 'generation', sequence, data: { sequence } });
await ackBusyHarness.settle();
assert.equal(ackAttempts, 1, 'A full batch must send one ACK before backoff');
await ackBusyHarness.runNextTimer(49);
assert.equal(ackAttempts, 1, 'BUSY must not spin or preempt its 50 ms retry');
await ackBusyHarness.runNextTimer(1);
assert.deepEqual(ackBusyHarness.sent.filter(request => request.method === 'runtime.ackEvents')
  .map(request => request.params.sequence), [32, 32], 'The same high-water mark must retry after BUSY');

const timeSliceHarness = runtimeHarness();
await timeSliceHarness.settle();
for (let sequence = 1; sequence <= 20; sequence++) timeSliceHarness.emit({ type: 'event', event: 'alarm',
  generation: 'generation', sequence, data: { sequence } });
timeSliceHarness.setPerformanceStep(0.5);
const timeSliceReplay = [];
timeSliceHarness.page.ToolBox.alarms.onAlarm(value => timeSliceReplay.push(value.sequence));
await timeSliceHarness.settle();
await timeSliceHarness.runNextTimer();
assert(timeSliceReplay.length > 0 && timeSliceReplay.length < 20,
  'Replay must yield when its 2 ms inspection budget is reached');

const overflowHarness = runtimeHarness();
await overflowHarness.settle();
for (let sequence = 1; sequence <= 2000 && !overflowHarness.errors.length; sequence++) {
  overflowHarness.emit({ type: 'event', event: 'background.timer', generation: 'generation', sequence,
    data: { text: 'x'.repeat(1024) } });
}
assert.equal(overflowHarness.errors[0]?.code, 'EVENT_BACKLOG_OVERFLOW');
assert.equal(overflowHarness.errors[0]?.droppedCount, 1);
assert.throws(() => overflowHarness.page.ToolBox.background.onTimer(() => {}),
  error => error.code === 'EVENT_BACKLOG_OVERFLOW');

let storageAttempts = 0;
let flushAttempts = 0;
const flushHarness = runtimeHarness(request => {
  if (request.method === 'storage.apply' && ++storageAttempts === 1) {
    return { ok: false, error: { code: 'BUSY', message: 'Ordinary request budget is full' } };
  }
  if (request.method === 'runtime.flushComplete' && ++flushAttempts === 1) {
    return { ok: false, error: { code: 'BUSY', message: 'Control request budget is full' } };
  }
  return { ok: true, result: {} };
});
await flushHarness.settle();
flushHarness.page.ToolBox.runtime.registerFlushHandler(() =>
  flushHarness.page.ToolBox.storage.apply({ set: [{ key: 'checkpoint', value: { value: 'saved' } }] }));
flushHarness.emit({ type: 'runtimeState', data: { generation: 'generation', revision: 1,
  foreground: false, closing: true, closeToken: 'close-token-1' } });
await flushHarness.settle();
assert.equal(storageAttempts, 1);
assert(!flushHarness.sent.some(request => request.method === 'runtime.flushComplete'));
await flushHarness.runNextTimer(49);
assert.equal(storageAttempts, 1);
await flushHarness.runNextTimer(1);
assert.equal(storageAttempts, 2);
assert.deepEqual(JSON.parse(JSON.stringify(flushHarness.sent.find(request => request.method === 'storage.apply').params)),
  { set: [{ key: 'checkpoint', value: { value: 'saved' } }] }, 'Flush must contain a real nonempty storage mutation');
assert.equal(flushAttempts, 1);
await flushHarness.runNextTimer(50);
assert.equal(flushAttempts, 2, 'Final acknowledgement must retry a temporarily full control budget');
assert(flushHarness.sent.some(request => request.method === 'runtime.flushComplete' &&
  request.params.closeToken === 'close-token-1' && request.params.saved === true));
await assert.rejects(flushHarness.page.ToolBox.storage.set('late', 'value'),
  error => error.code === 'SESSION_ENDED');

let finishOldFlush;
let flushCalls = 0;
const cancelHarness = runtimeHarness();
await cancelHarness.settle();
cancelHarness.page.ToolBox.runtime.registerFlushHandler(() => ++flushCalls === 1
  ? new Promise(resolve => { finishOldFlush = resolve; }) : Promise.resolve());
cancelHarness.emit({ type: 'runtimeState', data: { generation: 'generation', revision: 1,
  foreground: false, closing: true, closeToken: 'old-token' } });
await cancelHarness.settle();
cancelHarness.emit({ type: 'runtimeState', data: { generation: 'generation', revision: 2,
  foreground: false, closing: false } });
cancelHarness.emit({ type: 'runtimeState', data: { generation: 'generation', revision: 3,
  foreground: false, closing: true, closeToken: 'new-token' } });
await cancelHarness.settle();
finishOldFlush();
await cancelHarness.settle();
assert.equal(flushCalls, 2);
assert.deepEqual(cancelHarness.sent.filter(request => request.method === 'runtime.flushComplete')
  .map(request => request.params.closeToken), ['new-token']);

const forbidden = [/\.addJavascriptInterface\s*\(/, /setAllowUniversalAccessFromFileURLs\(true\)/, /allowUniversalAccessFromFileURLs\s*=\s*true/];
for (const module of ['app', 'tool-runtime']) {
  const base = resolve(root, module, 'src/main');
  for (const path of readdirSync(base, { recursive: true }).filter(p => /\.(kt|java)$/.test(p))) {
    const text = readFileSync(resolve(base, path), 'utf8');
    for (const pattern of forbidden) assert(!pattern.test(text), `${module}/${path}: forbidden WebView entry point`);
  }
}
console.log(`Host contract: ${methods.length} methods, ${capabilities.length} capabilities; bridge binary/text behavior and security entry checks passed.`);
