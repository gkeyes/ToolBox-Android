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
await context.ToolBox.clipboard.writeText('');
assert.equal(sent.at(-1).params.text, '');
await context.ToolBox.share.text('line\nnext');
assert.equal(sent.at(-1).params.text, 'line\nnext');
const chunk = await context.ToolBox.network.readStream('stream-fixture', { expectedChunkBytes: 131072 });
assert.equal(sent.at(-1).params.expectedChunkBytes, 131072);
assert.deepEqual([...chunk.data], [1, 2, 3]);
await assert.rejects(context.ToolBox.network.readStream('stream-fixture', { expectedChunkBytes: Number.MAX_SAFE_INTEGER + 1 }));
const forbidden = [/\.addJavascriptInterface\s*\(/, /setAllowUniversalAccessFromFileURLs\(true\)/, /allowUniversalAccessFromFileURLs\s*=\s*true/];
for (const module of ['app', 'tool-runtime']) {
  const base = resolve(root, module, 'src/main');
  for (const path of readdirSync(base, { recursive: true }).filter(p => /\.(kt|java)$/.test(p))) {
    const text = readFileSync(resolve(base, path), 'utf8');
    for (const pattern of forbidden) assert(!pattern.test(text), `${module}/${path}: forbidden WebView entry point`);
  }
}
console.log(`Host contract: ${methods.length} methods, ${capabilities.length} capabilities; bridge binary/text behavior and security entry checks passed.`);
