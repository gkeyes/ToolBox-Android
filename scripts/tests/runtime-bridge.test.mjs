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
  const context = { __toolboxNative: bridge, queueMicrotask };
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
