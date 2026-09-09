import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

// Execute the production document-start shim, not a second implementation of it.
const kotlin = fs.readFileSync(new URL('../../tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeWebMessageBridge.kt', import.meta.url), 'utf8');
const section = kotlin.slice(kotlin.indexOf('private fun shim('));
const body = section.match(/return """([\s\S]*?)"""\.trimIndent\(\)/)?.[1];
assert.ok(body, 'Production shim must remain extractable');
const source = body
  .replaceAll("${'$'}", '$')
  .replaceAll('$BRIDGE_OBJECT', '__toolboxNative')
  .replaceAll('$nonce', JSON.stringify('fixture-nonce'))
  .replaceAll('$toolId', JSON.stringify('io.toolbox.fixture'))
  .replaceAll('$generation', JSON.stringify('fixture-generation'))
  .replaceAll('${identity.versionCode}', '1');

function fixture() {
  const messages = [];
  const bridge = { postMessage(encoded) { messages.push(JSON.parse(encoded)); } };
  const context = { __toolboxNative: bridge, queueMicrotask, addEventListener() {} };
  vm.runInNewContext(source, context);
  const reply = (request, result = null) => bridge.onmessage({ data: JSON.stringify({ id: request.id, ok: true, result }) });
  return { api: context.ToolBox, bridge, messages, reply };
}

test('pending calls are bounded and completed replies return capacity', async () => {
  const { api, messages, reply } = fixture();
  const waiting = Array.from({ length: 32 }, () => api.ready());
  await assert.rejects(api.ready(), error => error.code === 'BUSY');
  assert.equal(messages.length, 32);
  reply(messages[0], { apiVersion: '1.0' });
  await waiting[0];
  const next = api.ready();
  assert.equal(messages.length, 33);
  messages.slice(1).forEach(message => reply(message));
  await Promise.all([...waiting, next]);
});

test('serialization and transport exceptions never leak pending slots', async () => {
  const { api, bridge, messages, reply } = fixture();
  const cycle = {}; cycle.self = cycle;
  for (let i = 0; i < 40; i++) await assert.rejects(api.storage.set('cycle', cycle), /circular/i);
  const send = bridge.postMessage;
  bridge.postMessage = () => { throw new Error('transport unavailable'); };
  for (let i = 0; i < 40; i++) await assert.rejects(api.ready(), /transport unavailable/);
  bridge.postMessage = send;
  const next = api.ready();
  assert.equal(messages.length, 1);
  reply(messages[0]);
  await next;
});

test('native BUSY rejects its correlated request and permits retry', async () => {
  const { api, bridge, messages, reply } = fixture();
  const request = api.ready();
  bridge.onmessage({ data: JSON.stringify({ id: messages[0].id, ok: false, error: { code: 'BUSY', message: 'Host queue is full' } }) });
  await assert.rejects(request, error => error.code === 'BUSY');
  const retry = api.ready();
  reply(messages[1]);
  await retry;
});


test('ordinary storage batches preserve their wire shape, key order, and typed errors', async () => {
  const { api, bridge, messages, reply } = fixture();
  const read = api.storage.getMany(['second', 'missing', 'first', 'second']);
  assert.equal(messages[0].method, 'storage.getMany');
  assert.deepEqual(messages[0].params, { keys: ['second', 'missing', 'first', 'second'] });
  reply(messages[0], [2, null, { text: '完整正文' }, 2]);
  assert.equal(JSON.stringify(await read), JSON.stringify([2, null, { text: '完整正文' }, 2]));
  const mutation = { set: [{ key: 'root', value: { generation: 3 } }, { key: 'nullable', value: null }], remove: ['old'] };
  const write = api.storage.apply(mutation);
  assert.equal(messages[1].method, 'storage.apply');
  assert.deepEqual(messages[1].params, mutation);
  reply(messages[1]);
  await write;
  const rejected = api.storage.apply({ set: [{ key: 'root', value: 4 }], remove: ['root'] });
  bridge.onmessage({ data: JSON.stringify({ id: messages[2].id, ok: false, error: { code: 'INVALID_REQUEST', message: 'Conflicting mutation' } }) });
  await assert.rejects(rejected, error => error.code === 'INVALID_REQUEST');
  assert.equal(api.storage.secure.getMany, undefined);
  assert.equal(api.storage.secure.apply, undefined);
});

test('invalid batch JSON never reaches native storage or consumes a pending slot', async () => {
  const { api, messages, reply } = fixture();
  for (const mutation of [undefined, null, [], { set: [{ key: 'a', value: undefined }] },
    { set: [{ key: 'a', value: { nested: NaN } }] }, { set: [{ key: 'a', value: [() => 1] }] }]) {
    await assert.rejects(api.storage.apply(mutation), error => error.code === 'INVALID_REQUEST');
  }
  assert.equal(messages.length, 0);
  const write = api.storage.apply({});
  reply(messages[0]);
  await write;
});
