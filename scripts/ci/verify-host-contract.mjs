import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
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
// Hand-kept copies of the capability list: the install validator and the host's supported set.
const kotlinStrings = (path, start) => {
  const text = read(path);
  const from = text.indexOf(start);
  assert(from >= 0, `${path}: ${start} not found`);
  const body = text.slice(from + start.length, text.indexOf(')', from + start.length));
  return [...body.matchAll(/"([^"]+)"/g)].map(m => m[1]).sort();
};
assert.deepEqual(kotlinStrings('tool-package/src/main/kotlin/io/toolbox/tool/packagekit/ManifestValidator.kt',
  'allowedPermissions = setOf('), capabilities, 'ManifestValidator.allowedPermissions must match ToolBoxApiV1');
assert.deepEqual(kotlinStrings('tool-package/src/main/kotlin/io/toolbox/tool/packagekit/lifecycle/PackageLifecycle.kt',
  'val All = setOf('), capabilities, 'SupportedToolCapabilities.All must match ToolBoxApiV1');
// Default grants are kept twice (install-time grant rows and the permission center's reset state);
// both must name the same real capabilities.
const installDefaults = kotlinStrings('tool-package/src/main/kotlin/io/toolbox/tool/packagekit/lifecycle/DefaultToolPackageManager.kt',
  'val DEFAULT_GRANTED_CAPABILITIES = setOf(');
const centerDefaults = kotlinStrings('app/src/main/kotlin/io/toolbox/host/permissions/PermissionCenterViewModel.kt',
  'fun String.defaultEnabled() = this in setOf(');
assert(installDefaults.length > 0, 'DEFAULT_GRANTED_CAPABILITIES must not be empty');
assert.deepEqual(centerDefaults, installDefaults,
  'PermissionCenterViewModel.defaultEnabled must match DefaultToolPackageManager.DEFAULT_GRANTED_CAPABILITIES');
for (const name of installDefaults) assert(capabilities.includes(name), `Default grant ${name} is not a ToolBoxApiV1 capability`);
// scripts/package-tool.py mirrors ManifestValidator so a package it builds installs; its copies must not drift.
const pythonStrings = (path, start) => {
  const text = read(path);
  const from = text.indexOf(start);
  assert(from >= 0, `${path}: ${start} not found`);
  const body = text.slice(from + start.length, text.indexOf('}', from + start.length));
  return [...body.matchAll(/"([^"]+)"/g)].map(m => m[1]).sort();
};
const validatorPath = 'tool-package/src/main/kotlin/io/toolbox/tool/packagekit/ManifestValidator.kt';
assert.deepEqual(pythonStrings('scripts/package-tool.py', 'PERMISSIONS = {'), capabilities,
  'package-tool.py PERMISSIONS must match ToolBoxApiV1');
assert.deepEqual(pythonStrings('scripts/package-tool.py', 'V03_CAPABILITIES = {'),
  kotlinStrings(validatorPath, 'val v03Capabilities = setOf('), 'package-tool.py V03_CAPABILITIES must match ManifestValidator');
assert.deepEqual(pythonStrings('scripts/package-tool.py', 'TOP_LEVEL_FIELDS = {'),
  kotlinStrings(validatorPath, 'val TOP_LEVEL_FIELDS = setOf('), 'package-tool.py TOP_LEVEL_FIELDS must match ManifestValidator');
// The document-start SDK is an asset; RuntimeBridgeSession.shim fills these placeholders.
const shimTemplate = read('tool-runtime/src/main/assets/toolbox-runtime/runtime-sdk.js');
const shim = shimTemplate.replaceAll('__TOOLBOX_VERSION_CODE__', '1')
  .replaceAll('__TOOLBOX_BRIDGE_OBJECT__', '__toolboxNative').replaceAll('__TOOLBOX_NONCE__', '"nonce"')
  .replaceAll('__TOOLBOX_TOOL_ID__', '"io.example.contract"').replaceAll('__TOOLBOX_GENERATION__', '"generation"');
const sent = [];
const bridge = { postMessage(encoded) {
  const message = JSON.parse(encoded); sent.push(message);
  const result = message.method === 'network.readStream' ? { data: 'AQID', done: true, receivedBytes: 3 }
    : message.method === 'files.read' ? { base64: 'AQID' } : {};
  queueMicrotask(() => bridge.onmessage({ data: JSON.stringify({ id: message.id, ok: true, result }) }));
}};
const context = vm.createContext({ __toolboxNative: bridge, Uint8Array, atob, btoa, queueMicrotask, addEventListener() {}, dispatchEvent() {} });
vm.runInContext(shim, context);
// The SDK's close-ticket write set must equal the native WRITE_METHODS that the close flush admits.
const sdkWrites = [...shimTemplate.match(/const writeMethods = new Set\(\[([^\]]*)\]\)/)[1].matchAll(/'([^']+)'/g)].map(m => m[1]).sort();
assert.deepEqual(sdkWrites, kotlinStrings('tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimePresentationState.kt',
  'val WRITE_METHODS = setOf('), 'runtime-sdk.js writeMethods must match RuntimePresentationState.WRITE_METHODS');
assert.deepEqual([...shimTemplate.matchAll(/call\('([^']+)'/g)].map(m => m[1]).filter((v,i,a) => a.indexOf(v) === i).sort(), methods);
await context.ToolBox.clipboard.writeText('');
assert.equal(sent.findLast(request => request.method === 'clipboard.writeText')?.params.text, '');
await context.ToolBox.share.text('line\nnext');
assert.equal(sent.findLast(request => request.method === 'share.text')?.params.text, 'line\nnext');
const chunk = await context.ToolBox.network.readStream('stream-fixture', { expectedChunkBytes: 131072 });
assert.equal(sent.findLast(request => request.method === 'network.readStream')?.params.expectedChunkBytes, 131072);
assert.deepEqual([...chunk.data], [1, 2, 3]);
await assert.rejects(context.ToolBox.network.readStream('stream-fixture', { expectedChunkBytes: Number.MAX_SAFE_INTEGER + 1 }));
// Bytes travel as one base64 string each way; the public API still takes and returns Uint8Array.
await context.ToolBox.crypto.sha256(new Uint8Array([1, 2, 3]));
assert.deepEqual(sent.findLast(request => request.method === 'crypto.sha256')?.params.value, { base64: 'AQID' });
await context.ToolBox.crypto.sha256('text');
assert.equal(sent.findLast(request => request.method === 'crypto.sha256')?.params.value, 'text');
await context.ToolBox.files.save('a.bin', 'application/octet-stream', new Uint8Array(70000).fill(255));
const savedContent = sent.findLast(request => request.method === 'files.save')?.params.content;
assert.equal(typeof savedContent?.base64, 'string');
assert.equal(Buffer.from(savedContent.base64, 'base64').length, 70000);
await context.ToolBox.network.request({ url: 'https://example.test/', method: 'POST', body: new Uint8Array([1, 2, 3]) });
const sentBody = sent.findLast(request => request.method === 'network.request')?.params;
assert.deepEqual({ body: sentBody.body, bodyEncoding: sentBody.bodyEncoding }, { body: { base64: 'AQID' }, bodyEncoding: 'bytes' });
assert.deepEqual([...await context.ToolBox.files.read('token-fixture')], [1, 2, 3]);

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
    btoa,
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

// A notification tap delivered before the page subscribed is replayed to notifications.onOpen.
const openHarness = runtimeHarness();
await openHarness.settle();
openHarness.emit({ type: 'event', event: 'notifications.open', generation: 'generation', sequence: 1,
  data: { id: 'feed-12', openedAt: 5 } });
const openedTaps = [];
openHarness.page.ToolBox.notifications.onOpen(value => openedTaps.push(value));
await openHarness.settle();
await openHarness.runNextTimer();
assert.deepEqual(JSON.parse(JSON.stringify(openedTaps)), [{ id: 'feed-12', openedAt: 5 }], 'notifications.onOpen must receive taps made before it subscribed');

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

// SEC-X1: the document-start script removes WebRTC, which CSP connect-src and request interception never see.
const webRtcHardening = read('tool-runtime/src/main/assets/toolbox-runtime/webrtc-hardening.js');
const rtcNames = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCRtpSender'];
const rtcWindow = vm.createContext({});
vm.runInContext(`class EventTarget {}
  class RTCPeerConnection extends EventTarget {}
  globalThis.RTCPeerConnection = RTCPeerConnection;
  globalThis.webkitRTCPeerConnection = RTCPeerConnection;
  globalThis.RTCDataChannel = class RTCDataChannel extends EventTarget {};
  globalThis.RTCRtpSender = class RTCRtpSender {};
  globalThis.keep = 1;`, rtcWindow);
vm.runInContext(webRtcHardening, rtcWindow);
assert.equal(vm.runInContext('globalThis.__toolboxWebRtcBlocked', rtcWindow), true);
assert.equal(vm.runInContext('keep', rtcWindow), 1, 'Unrelated globals stay');
for (const name of rtcNames) {
  assert.equal(vm.runInContext(`typeof globalThis.${name}`, rtcWindow), 'undefined', `${name} must be removed`);
  vm.runInContext(`try { globalThis.${name} = function () {}; } catch (_) {}`, rtcWindow);
  assert.equal(vm.runInContext(`typeof globalThis.${name}`, rtcWindow), 'undefined', `${name} must not be writable`);
  assert.throws(() => vm.runInContext(`'use strict'; Object.defineProperty(globalThis, '${name}', { value: 1 });`, rtcWindow),
    `${name} must not be redefinable`);
}
const hardenedTool = read('tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/HardenedRuntimeWebView.kt');
assert(/addDocumentStartJavaScript\(\s*createdWebView,\s*RuntimeHardeningScripts\.webRtc\(context\),\s*RuntimeHardeningScripts\.WEBRTC_ORIGIN_RULES,?\s*\)/.test(hardenedTool),
  'Every tool WebView must install the WebRTC hardening script');
assert(/val WEBRTC_ORIGIN_RULES: Set<String> = setOf\("\*"\)/.test(hardenedTool),
  'WebRTC hardening must reach every frame, including opaque-origin iframes');
// The script runs in every frame, so a second run in the same global must be a harmless no-op.
vm.runInContext(webRtcHardening, rtcWindow);
assert.equal(vm.runInContext('globalThis.__toolboxWebRtcBlocked', rtcWindow), true);
// An opaque-origin frame without the WebRTC globals still ends up locked and marked.
const bareFrame = vm.createContext({});
vm.runInContext(webRtcHardening, bareFrame);
assert.equal(vm.runInContext('globalThis.__toolboxWebRtcBlocked', bareFrame), true);
assert.equal(vm.runInContext('typeof RTCPeerConnection', bareFrame), 'undefined');
// A marker pre-set by a parent page must not make the script skip the lock.
const forgedFrame = vm.createContext({});
vm.runInContext('globalThis.RTCPeerConnection = class RTCPeerConnection {}; globalThis.__toolboxWebRtcBlocked = true;',
  forgedFrame);
vm.runInContext(webRtcHardening, forgedFrame);
assert.equal(vm.runInContext('typeof RTCPeerConnection', forgedFrame), 'undefined', 'A forged marker must not skip the lock');
assert.equal(vm.runInContext(`Object.getOwnPropertyDescriptor(globalThis, '__toolboxWebRtcBlocked').configurable`, forgedFrame),
  false);
// A same-task iframe: the parent reads contentWindow/contentDocument before the child's own
// document-start script has run. The parent's frame accessors must lock the child first, with the
// same lock (including the child's own frame accessors), and the child's later run must still pass.
const frameRealm = `class HTMLIFrameElement {
    constructor(win) { this.win = win; }
    get contentWindow() { return this.win; }
    get contentDocument() { return this.win ? this.win.doc : null; }
  }
  globalThis.HTMLIFrameElement = HTMLIFrameElement;
  globalThis.RTCPeerConnection = class RTCPeerConnection {};
  globalThis.RTCDataChannel = class RTCDataChannel {};
  globalThis.doc = { kind: 'document' };`;
const parentFrame = vm.createContext({});
vm.runInContext(frameRealm, parentFrame);
vm.runInContext(webRtcHardening, parentFrame);
const childFrame = vm.createContext({});
vm.runInContext(frameRealm, childFrame);
parentFrame.childWindow = vm.runInContext('globalThis', childFrame);
assert.equal(vm.runInContext('typeof new HTMLIFrameElement(childWindow).contentWindow.RTCPeerConnection', parentFrame),
  'undefined', 'contentWindow must hand out a locked child');
assert.equal(vm.runInContext(`(() => { try { new (new HTMLIFrameElement(childWindow).contentWindow.RTCPeerConnection)();
  return 'constructed'; } catch (_) { return 'blocked'; } })()`, parentFrame), 'blocked');
assert.equal(vm.runInContext('typeof RTCDataChannel', childFrame), 'undefined');
assert.equal(vm.runInContext('__toolboxWebRtcBlocked', childFrame), true);
assert.equal(vm.runInContext(`Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'contentWindow').configurable`,
  childFrame), false, 'The child realm gets the same frame-accessor lock');
vm.runInContext(webRtcHardening, childFrame);
assert.equal(vm.runInContext('typeof RTCPeerConnection', childFrame), 'undefined', 'The child document-start run is harmless');
const docChild = vm.createContext({});
vm.runInContext(frameRealm, docChild);
parentFrame.docChildWindow = vm.runInContext('globalThis', docChild);
assert.equal(vm.runInContext('new HTMLIFrameElement(docChildWindow).contentDocument.kind', parentFrame), 'document');
assert.equal(vm.runInContext('typeof RTCPeerConnection', docChild), 'undefined', 'contentDocument must lock the child too');
assert.throws(() => vm.runInContext(`'use strict'; Object.defineProperty(HTMLIFrameElement.prototype, 'contentWindow',
  { get() { return null; } });`, parentFrame), 'The wrapped accessor must not be replaceable');
// A child that cannot be locked: same-origin (document readable) is hidden, cross-origin is left to its own script.
parentFrame.stuck = vm.runInContext(`Object.defineProperty(globalThis, 'RTCPeerConnection',
  { value: function () {}, writable: false, configurable: false }); globalThis`, vm.createContext({ document: {} }));
assert.equal(vm.runInContext('new HTMLIFrameElement(stuck).contentWindow', parentFrame), null);
parentFrame.crossOrigin = new Proxy({}, { get() { throw new Error('SecurityError'); },
  getOwnPropertyDescriptor() { throw new Error('SecurityError'); } });
assert.equal(vm.runInContext('new HTMLIFrameElement(crossOrigin).contentWindow === crossOrigin', parentFrame), true);

// window[i] / frames[i] / named access never touch a frame accessor: every insertion call must lock
// the document's child windows before it returns, using the original window.length getter.
const domRealm = `class Node { appendChild(child) { frameList.push(child); return child; }
    get ownerDocument() { return theDocument; } }
  class Document extends Node { get defaultView() { return globalThis; } get ownerDocument() { return null; }
    get body() { return null; } set body(value) { frameList.push(pendingFrame); } }
  class Element extends Node { set innerHTML(html) { frameList.push(pendingFrame); } }
  globalThis.Node = Node; globalThis.Document = Document; globalThis.Element = Element;
  globalThis.theDocument = new Document();
  globalThis.frameList = [];
  Object.defineProperty(globalThis, 'length', { get() { return frameList.length; }, configurable: true });
  globalThis.RTCPeerConnection = class RTCPeerConnection {};`;
const insertParent = vm.createContext({});
vm.runInContext(domRealm, insertParent);
vm.runInContext(webRtcHardening, insertParent);
vm.runInContext(`for (let i = 0; i < 8; i++) Object.defineProperty(globalThis, i, { get() { return frameList[i]; } });`,
  insertParent);
const appended = vm.createContext({});
vm.runInContext(domRealm, appended);
insertParent.appendedWindow = vm.runInContext('globalThis', appended);
vm.runInContext("Object.defineProperty(globalThis, 'length', { value: 0 });", insertParent);
vm.runInContext('new Element().appendChild(appendedWindow)', insertParent);
assert.equal(vm.runInContext('typeof RTCPeerConnection', appended), 'undefined',
  'appendChild must lock a new child before window[0] can reach it, even with window.length replaced');
assert.equal(vm.runInContext('Element.prototype.appendChild.name', insertParent), 'appendChild');
const parsed = vm.createContext({});
vm.runInContext(domRealm, parsed);
insertParent.pendingFrame = vm.runInContext('globalThis', parsed);
vm.runInContext("new Element().innerHTML = '<iframe></iframe>'", insertParent);
assert.equal(vm.runInContext('typeof RTCPeerConnection', parsed), 'undefined', 'An innerHTML insertion must lock its frames too');
const replacedBody = vm.createContext({});
vm.runInContext(domRealm, replacedBody);
insertParent.pendingFrame = vm.runInContext('globalThis', replacedBody);
vm.runInContext('theDocument.body = {}', insertParent);
assert.equal(vm.runInContext('typeof RTCPeerConnection', replacedBody), 'undefined', 'Replacing document.body must lock its frames');
// A page that makes WeakSet.prototype.has always true cannot make a child realm look already wrapped.
const lyingSet = vm.createContext({});
vm.runInContext(domRealm, lyingSet);
insertParent.lyingWindow = vm.runInContext('globalThis', lyingSet);
const childAppend = vm.runInContext('Node.prototype.appendChild', lyingSet);
vm.runInContext('WeakSet.prototype.has = () => true; new Element().appendChild(lyingWindow)', insertParent);
assert.notEqual(vm.runInContext('Node.prototype.appendChild', lyingSet), childAppend, 'The child realm must still be wrapped');
// The frame's own document-start run on top of the parent's wrapping still locks later children.
vm.runInContext(webRtcHardening, insertParent);
const later = vm.createContext({});
vm.runInContext(domRealm, later);
insertParent.laterWindow = vm.runInContext('globalThis', later);
vm.runInContext('new Element().appendChild(laterWindow)', insertParent);
assert.equal(vm.runInContext('typeof RTCPeerConnection', later), 'undefined');

// window.open('', name) returns a frame by name without any accessor or insertion call.
const openerRealm = vm.createContext({});
vm.runInContext(`globalThis.RTCPeerConnection = class RTCPeerConnection {};
  globalThis.open = function open(url, name) { return namedFrames[name] || null; };
  globalThis.namedFrames = {};`, openerRealm);
vm.runInContext(webRtcHardening, openerRealm);
const opened = vm.createContext({});
vm.runInContext('globalThis.RTCPeerConnection = class RTCPeerConnection {};', opened);
openerRealm.namedFrames.target = vm.runInContext('globalThis', opened);
assert.equal(vm.runInContext("open('', 'target') !== null", openerRealm), true);
assert.equal(vm.runInContext('typeof RTCPeerConnection', opened), 'undefined', 'window.open must lock the window it returns');

const forbidden = [/\.addJavascriptInterface\s*\(/, /setAllowUniversalAccessFromFileURLs\(true\)/, /allowUniversalAccessFromFileURLs\s*=\s*true/];
for (const module of ['app', 'tool-runtime']) {
  const base = resolve(root, module, 'src/main');
  for (const path of readdirSync(base, { recursive: true }).filter(p => /\.(kt|java)$/.test(p))) {
    const text = readFileSync(resolve(base, path), 'utf8');
    for (const pattern of forbidden) assert(!pattern.test(text), `${module}/${path}: forbidden WebView entry point`);
  }
}
console.log(`Host contract: ${methods.length} methods, ${capabilities.length} capabilities; bridge binary/text behavior and security entry checks passed.`);
execFileSync(process.execPath, ['--test', resolve(root, 'tool-runtime/src/test/js/runtime-media-sdk.test.mjs')], { stdio: 'inherit' });
