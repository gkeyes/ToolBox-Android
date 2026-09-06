import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { webcrypto } from 'node:crypto';
import vm from 'node:vm';
import test from 'node:test';

function bridge() {
  const file = new URL('../../main/kotlin/io/toolbox/tool/runtime/RuntimeWebMessageBridge.kt', import.meta.url);
  const source = readFileSync(file, 'utf8').split('return """')[1].split('""".trimIndent()')[0]
    .replaceAll("${'$'}", '$').replaceAll('$BRIDGE_OBJECT', '__toolboxNative')
    .replaceAll('$nonce', '"test-session-nonce"').replaceAll('$toolId', '"io.toolbox.test"')
    .replaceAll('$generation', '"test-generation"').replaceAll('${identity.versionCode}', '1');
  const calls = [], events = new Map();
  const native = { postMessage(value) { calls.push(JSON.parse(value)); } };
  const context = vm.createContext({ __toolboxNative: native, Uint8Array, crypto: webcrypto, atob,
    AbortController, queueMicrotask, addEventListener: (name, callback) => events.set(name, callback) });
  vm.runInContext(source, context);
  const reply = (request, result) => native.onmessage({ data: JSON.stringify({ id: request.id, ok: true, result }) });
  const reject = (request, code) => native.onmessage({ data: JSON.stringify({ id: request.id, ok: false, error: { code, message: code } }) });
  return { network: context.ToolBox.network, calls, reply, reject, events };
}

test('stream methods expose true incremental bytes and preserve the request body encoding', async () => {
  const { network, calls, reply } = bridge();
  assert.equal(typeof network.openStream, 'function');
  const opening = network.openStream({ url: 'https://example.com', body: new Uint8Array([1, 2]) });
  const open = calls.at(-1);
  assert.equal(open.method, 'network.openStream');
  assert.equal(open.params.request.bodyEncoding, 'bytes');
  assert.deepEqual(open.params.request.body, [1, 2]);
  reply(open, { streamId: open.params.streamId, status: 200, headers: {} });
  const handle = await opening;
  const reading = network.readStream(handle.streamId);
  reply(calls.at(-1), { data: '5L2g', done: false, receivedBytes: 3 });
  const chunk = await reading;
  assert(chunk.data instanceof Uint8Array);
  assert.equal(new TextDecoder().decode(chunk.data), '你');
  assert.equal(chunk.done, false);
  assert.equal(chunk.receivedBytes, 3);
  const finishing = network.readStream(handle.streamId);
  reply(calls.at(-1), { data: '', done: true, receivedBytes: 3 });
  assert.equal((await finishing).done, true);
});

test('abort before headers cancels the native operation and late headers never escape', async () => {
  const { network, calls, reply } = bridge();
  const controller = new AbortController();
  const opening = network.openStream({ url: 'https://example.com' }, { signal: controller.signal });
  const open = calls[0];
  controller.abort();
  assert.equal(calls[1].method, 'network.cancelStream');
  assert.equal(calls[1].params.streamId, open.params.streamId);
  reply(calls[1], null);
  reply(open, { streamId: open.params.streamId, status: 200, headers: {} });
  await assert.rejects(opening, { code: 'CANCELLED' });
  const cancelled = new AbortController();
  cancelled.abort();
  const count = calls.length;
  await assert.rejects(network.openStream({ url: 'https://example.com' }, { signal: cancelled.signal }), { code: 'CANCELLED' });
  assert.equal(calls.length, count);
});

test('leaving the page cancels open and opening streams', async () => {
  const { network, calls, reply, events } = bridge();
  const opening = network.openStream({ url: 'https://example.com' });
  const open = calls[0];
  events.get('pagehide')();
  assert.equal(calls.at(-1).method, 'network.cancelStream');
  reply(calls.at(-1), null);
  reply(open, { streamId: open.params.streamId, status: 200, headers: {} });
  await assert.rejects(opening, { code: 'CANCELLED' });
});

test('a rejected overlapping read does not detach cancellation from the active read', async () => {
  const { network, calls, reply, reject } = bridge();
  const controller = new AbortController();
  const opening = network.openStream({ url: 'https://example.com' }, { signal: controller.signal });
  const open = calls[0];
  reply(open, { streamId: open.params.streamId, status: 200, headers: {} });
  const stream = await opening;
  const first = network.readStream(stream.streamId);
  const firstCall = calls.at(-1);
  const overlapping = network.readStream(stream.streamId);
  reject(calls.at(-1), 'BUSY');
  await assert.rejects(overlapping, { code: 'BUSY' });
  controller.abort();
  assert.equal(calls.at(-1).method, 'network.cancelStream');
  reply(calls.at(-1), null);
  reply(firstCall, { data: 'AQ==', done: false, receivedBytes: 1 });
  await assert.rejects(first, { code: 'CANCELLED' });
});
