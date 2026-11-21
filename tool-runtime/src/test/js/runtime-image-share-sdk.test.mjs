import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";

const sdk = readFileSync(new URL("../../main/assets/toolbox-runtime/runtime-sdk.js", import.meta.url), "utf8")
  .replaceAll("__TOOLBOX_VERSION_CODE__", "1")
  .replaceAll("__TOOLBOX_BRIDGE_OBJECT__", "__toolboxNative")
  .replaceAll("__TOOLBOX_NONCE__", '"image-share-fixture"')
  .replaceAll("__TOOLBOX_TOOL_ID__", '"io.example.image"')
  .replaceAll("__TOOLBOX_GENERATION__", '"image-share-generation"');

function host(onMessage) {
  const sent = [];
  function reply({ id }, failure) {
    bridge.onmessage({ data: JSON.stringify(failure
      ? { id, ok: false, error: { code: failure, message: failure } }
      : { id, ok: true, result: null }) });
  }
  const bridge = {
    postMessage(encoded) {
      const message = JSON.parse(encoded);
      if (message.method.startsWith("share.")) sent.push(message);
      if (onMessage?.(message, reply) !== false) queueMicrotask(() => reply(message));
    },
  };
  const realm = vm.createContext({
    __toolboxNative: bridge, atob, btoa, Blob, Uint8Array, crypto: webcrypto, queueMicrotask,
    addEventListener() {}, dispatchEvent() {},
  });
  vm.runInContext(sdk, realm);
  return { share: realm.ToolBox.share, sent };
}

test("sharing a Blob sends bounded original bytes and finishes after the last chunk", async () => {
  const fixture = host();
  const chunkLimit = 256 * 1024;
  const original = Uint8Array.from({ length: 2 * chunkLimit + 7 }, (_, index) => (index % 251) ^ (index >>> 16));
  await fixture.share.image(new Blob([original], { type: "image/png" }));
  const [begin, first, second, last, finish] = fixture.sent;
  assert.deepEqual(fixture.sent.map(({ method }) => method), [
    "share.image.begin", "share.image.write", "share.image.write", "share.image.write", "share.image.finish",
  ]);
  const { transferId } = begin.params;
  assert.match(transferId, /^image-[0-9a-f]{32}$/);
  assert.deepEqual(begin.params, { transferId, mimeType: "image/png", length: original.length });
  assert.deepEqual([first, second, last].map(({ params }) => params.offset), [0, chunkLimit, 2 * chunkLimit]);
  const chunks = [first, second, last].map(({ params }) => Buffer.from(params.content.base64, "base64"));
  assert.deepEqual(chunks.map(chunk => chunk.length), [chunkLimit, chunkLimit, 7]);
  assert.deepEqual(Buffer.concat(chunks), Buffer.from(original));
  assert.ok(fixture.sent.every(({ params }) => params.transferId === transferId));
  assert.deepEqual(finish.params, { transferId });
});

test("unsupported, oversize and pre-cancelled shares make no native requests", async () => {
  const fixture = host();
  await assert.rejects(() => fixture.share.image(new Blob()), { code: "INVALID_REQUEST" });
  await assert.rejects(() => fixture.share.image(new Blob(["<svg/>"], { type: "image/svg+xml" })), { code: "INVALID_MIME" });
  const mebibyte = new Uint8Array(1024 * 1024);
  await assert.rejects(() => fixture.share.image(new Blob(Array(33).fill(mebibyte), { type: "image/png" })), { code: "QUOTA_EXCEEDED" });
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(() => fixture.share.image(new Blob(["original"], { type: "image/jpeg" }), { signal: controller.signal }), { code: "CANCELLED" });
  assert.deepEqual(fixture.sent, []);
});

test("cancelling an in-flight write releases the transfer without finishing it", { timeout: 2_000 }, async () => {
  let resumeWrite;
  let writeArrived;
  let hold = true;
  const heldWrite = new Promise(resolve => { writeArrived = resolve; });
  const fixture = host((message, reply) => {
    if (message.method === "share.image.write" && hold) {
      hold = false;
      resumeWrite = () => reply(message);
      writeArrived();
      return false;
    }
  });
  const controller = new AbortController();
  const image = new Blob(["original"], { type: "image/webp" });
  const interrupted = fixture.share.image(image, { signal: controller.signal });
  await heldWrite;
  await assert.rejects(() => fixture.share.image(image), { code: "BUSY" });
  controller.abort();
  resumeWrite();
  await assert.rejects(interrupted, { code: "CANCELLED" });
  const firstId = fixture.sent[0].params.transferId;
  assert.ok(fixture.sent.some(({ method }) => method === "share.image.cancel"));
  assert.ok(fixture.sent.every(({ method, params }) => method !== "share.image.finish" && params.transferId === firstId));

  await fixture.share.image(image);
  const [retryInitialization] = fixture.sent.filter(({ params }) => params.transferId !== firstId);
  assert.equal(retryInitialization.method, "share.image.begin");
  assert.equal(fixture.sent.at(-1).method, "share.image.finish");
});
