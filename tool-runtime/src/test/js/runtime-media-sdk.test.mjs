import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
const source = readFileSync(resolve(root, 'tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeWebMessageBridge.kt'), 'utf8');
const template = source.slice(source.indexOf('private fun shim(')).match(/return """([\s\S]*?)"""\.trimIndent\(\)/)?.[1];
assert.ok(template, 'Tests load the production document-start SDK');
const shim = template.replaceAll("${'$'}", '$').replaceAll('${identity.versionCode}', '1')
  .replaceAll('$BRIDGE_OBJECT', '__toolboxNative').replaceAll('$nonce', '"nonce"')
  .replaceAll('$toolId', '"io.example.media"').replaceAll('$generation', '"generation"');

class TrackedSignal extends EventTarget {
  aborted = false;
  abortListeners = new Set();
  addEventListener(type, listener, options) {
    if (type === 'abort') this.abortListeners.add(listener);
    super.addEventListener(type, listener, options);
  }
  removeEventListener(type, listener, options) {
    if (type === 'abort') this.abortListeners.delete(listener);
    super.removeEventListener(type, listener, options);
  }
  abort() {
    if (this.aborted) return;
    this.aborted = true;
    this.dispatchEvent(new Event('abort'));
  }
}

function harness(onPost) {
  const sent = [];
  const listeners = new Map();
  const reply = (message, result) => bridge.onmessage({ data: JSON.stringify({ id: message.id, ok: true, result }) });
  const fail = (message, code) => bridge.onmessage({ data: JSON.stringify({ id: message.id, ok: false, error: { code, message: code } }) });
  const bridge = { postMessage(encoded) {
    const message = JSON.parse(encoded);
    sent.push(message);
    onPost?.(message);
    if (message.method === 'network.closeMedia') queueMicrotask(() => reply(message, null));
  } };
  const context = vm.createContext({
    __toolboxNative: bridge, Uint8Array, atob, queueMicrotask, crypto: webcrypto,
    addEventListener(name, callback) {
      const callbacks = listeners.get(name) ?? [];
      callbacks.push(callback);
      listeners.set(name, callbacks);
    },
    dispatchEvent() {},
  });
  vm.runInContext(shim, context);
  return {
    api: context.ToolBox.network,
    sent, reply, fail,
    opens: () => sent.filter(message => message.method === 'network.openMedia'),
    closes: () => sent.filter(message => message.method === 'network.closeMedia'),
    pagehide: () => listeners.get('pagehide')?.forEach(callback => callback()),
  };
}

const sourceUrl = 'https://cdn.example.test/video.mp4';
const opened = message => ({ sessionId: message.params.sessionId, url: 'https://tool.toolbox.invalid/.toolbox/media/' + 'b'.repeat(32) });
const flush = () => new Promise(resolve => setImmediate(resolve));
const cancelled = error => error?.code === 'CANCELLED';

test('openMedia creates random IDs and sends only its wire parameters', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new TrackedSignal();
  const first = host.api.openMedia({ url: sourceUrl, kind: 'video' }, { signal });
  const second = host.api.openMedia({ url: sourceUrl });
  const [a, b] = host.opens();
  assert.match(a.params.sessionId, /^media-[0-9a-f]{32}$/);
  assert.match(b.params.sessionId, /^media-[0-9a-f]{32}$/);
  assert.notEqual(a.params.sessionId, b.params.sessionId);
  assert.deepEqual(Object.keys(a.params).sort(), ['kind', 'sessionId', 'url']);
  assert.deepEqual(Object.keys(b.params).sort(), ['sessionId', 'url']);
  assert.equal(a.params.url, sourceUrl);
  assert.equal(a.params.kind, 'video');
  host.reply(a, opened(a));
  host.reply(b, opened(b));
  assert.equal((await first).sessionId, a.params.sessionId);
  assert.equal((await second).sessionId, b.params.sessionId);
  assert.equal(signal.abortListeners.size, 1);
});

test('a pre-aborted signal never opens or closes a native session', { timeout: 2_000 }, async () => {
  const host = harness();
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(host.api.openMedia({ url: sourceUrl }, { signal: controller.signal }), cancelled);
  assert.equal(host.opens().length, 0);
  assert.equal(host.closes().length, 0);
});

test('abort immediately rejects a pending open and closes its late result', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new TrackedSignal();
  const pending = host.api.openMedia({ url: sourceUrl }, { signal });
  const rejected = assert.rejects(pending, cancelled);
  const request = host.opens()[0];
  signal.abort();
  await rejected;
  assert.equal(host.closes().length, 1);
  assert.equal(host.closes()[0].params.sessionId, request.params.sessionId);
  assert.equal(signal.abortListeners.size, 0);
  host.reply(request, opened(request));
  await flush();
  assert.equal(host.closes().length, 2);
  assert.equal(host.closes()[1].params.sessionId, request.params.sessionId);
});

test('an abort followed by late native failure stays cancelled', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new TrackedSignal();
  const pending = host.api.openMedia({ url: sourceUrl }, { signal });
  const rejected = assert.rejects(pending, cancelled);
  signal.abort();
  await rejected;
  host.fail(host.opens()[0], 'PERMISSION_DENIED');
  await flush();
  assert.equal(host.closes().length, 1);
  assert.equal(signal.abortListeners.size, 0);
});

test('explicit close rejects a pending open and removes its signal listener', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new TrackedSignal();
  const pending = host.api.openMedia({ url: sourceUrl }, { signal });
  const rejected = assert.rejects(pending, cancelled);
  const request = host.opens()[0];
  assert.equal(await host.api.closeMedia(request.params.sessionId), undefined);
  await rejected;
  assert.equal(signal.abortListeners.size, 0);
  signal.abort();
  assert.equal(host.closes().length, 1);
  host.reply(request, opened(request));
  await flush();
  assert.equal(host.closes().length, 2);
});

test('close after successful open detaches the signal and avoids pagehide repeats', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new TrackedSignal();
  const pending = host.api.openMedia({ url: sourceUrl }, { signal });
  const request = host.opens()[0];
  host.reply(request, opened(request));
  const session = await pending;
  await host.api.closeMedia(session.sessionId);
  signal.abort();
  host.pagehide();
  await flush();
  assert.equal(signal.abortListeners.size, 0);
  assert.equal(host.closes().length, 1);
});

test('abort after successful open closes the native session', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new TrackedSignal();
  const pending = host.api.openMedia({ url: sourceUrl }, { signal });
  const request = host.opens()[0];
  host.reply(request, opened(request));
  await pending;
  signal.abort();
  await flush();
  assert.equal(host.closes().length, 1);
  assert.equal(host.closes()[0].params.sessionId, request.params.sessionId);
  assert.equal(signal.abortListeners.size, 0);
});

test('native open failure removes all local session state', { timeout: 2_000 }, async () => {
  const host = harness();
  const signal = new TrackedSignal();
  const pending = host.api.openMedia({ url: sourceUrl }, { signal });
  host.fail(host.opens()[0], 'PERMISSION_DENIED');
  await assert.rejects(pending, error => error?.code === 'PERMISSION_DENIED');
  signal.abort();
  host.pagehide();
  assert.equal(host.closes().length, 0);
  assert.equal(signal.abortListeners.size, 0);
});

test('pagehide closes opened and pending sessions and discards replies to the ended document', { timeout: 2_000 }, async () => {
  const host = harness();
  const firstSignal = new TrackedSignal();
  const secondSignal = new TrackedSignal();
  const first = host.api.openMedia({ url: sourceUrl }, { signal: firstSignal });
  const a = host.opens()[0];
  host.reply(a, opened(a));
  await first;
  const second = host.api.openMedia({ url: sourceUrl }, { signal: secondSignal });
  const b = host.opens()[1];
  const rejected = assert.rejects(second, cancelled);
  host.pagehide();
  await rejected;
  assert.deepEqual(host.closes().map(message => message.params.sessionId).sort(), [a.params.sessionId, b.params.sessionId].sort());
  assert.equal(firstSignal.abortListeners.size, 0);
  assert.equal(secondSignal.abortListeners.size, 0);
  const sentAtDisposal = host.sent.length;
  // Navigation also clears the native media registry and cancels pending opens.
  // Its old document reply channel has ended; the SDK must not revive it.
  host.reply(b, opened(b));
  await flush();
  await assert.rejects(host.api.openMedia({ url: sourceUrl }), error => error?.code === 'SESSION_ENDED');
  firstSignal.abort();
  secondSignal.abort();
  assert.equal(host.sent.length, sentAtDisposal);
  assert.equal(host.closes().length, 2);
});

test('bridge send failure detaches the abort listener', { timeout: 2_000 }, async () => {
  const host = harness(() => { throw new Error('Native bridge ended'); });
  const signal = new TrackedSignal();
  await assert.rejects(host.api.openMedia({ url: sourceUrl }, { signal }), /Native bridge ended/);
  signal.abort();
  host.pagehide();
  assert.equal(host.opens().length, 1);
  assert.equal(host.closes().length, 0);
  assert.equal(signal.abortListeners.size, 0);
});
