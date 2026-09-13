import test from "node:test";
import assert from "node:assert/strict";
import { createMediaTransport } from "../src/toolbox/mediaTransport.js";

const MiB = 1024 * 1024;
const url = "https://miniflux.xiaochen.win/proxy/signature/media";
const options = { accept: "video/*", mime: /^video\/mp4$/ };
function nativeStream(size, type = "video/mp4") {
  let sequence = 0;
  const pending = new Map();
  const cancelled = [];
  return {
    cancelled,
    async openStream() {
      const streamId = `stream-${++sequence}`;
      pending.set(streamId, size);
      return { streamId, status: 200, headers: { "content-type": type } };
    },
    async readStream(streamId) {
      const remaining = pending.get(streamId);
      assert.notEqual(remaining, undefined);
      const bytes = Math.min(remaining, MiB);
      const next = remaining - bytes;
      if (next) pending.set(streamId, next); else pending.delete(streamId);
      return { data: new Uint8Array(bytes), done: next === 0, receivedBytes: size - next };
    },
    async cancelStream(streamId) { cancelled.push(streamId); pending.delete(streamId); },
  };
}

test("streamed media exceeds old 2 MiB and 4 MiB ceilings without network.request", async () => {
  let api = nativeStream(3 * MiB, "image/png");
  const transport = createMediaTransport({ network: () => api, maxBytes: 16 * MiB });
  const image = await transport.load(url, { accept: "image/*", mime: /^image\/png$/ });
  api = nativeStream(5 * MiB);
  const video = await transport.load(url, options);
  assert.equal(image.size, 3 * MiB);
  assert.equal(video.size, 5 * MiB);
  assert.equal(transport.retainedBytes, 8 * MiB);
  transport.release(image); transport.release(image); transport.release(video);
  assert.equal(transport.retainedBytes, 0);
});

test("shared retained bytes are bounded, failed reads release reservations, and leases release capacity", async () => {
  let api = nativeStream(6 * MiB);
  const transport = createMediaTransport({ network: () => api, maxBytes: 8 * MiB });
  const first = await transport.load(url, options);
  api = nativeStream(3 * MiB);
  await assert.rejects(transport.load(url, options), { code: "RESOURCE_PRESSURE" });
  assert.equal(transport.retainedBytes, 6 * MiB);
  assert.equal(api.cancelled.length, 1);
  transport.release(first);
  const next = await transport.load(url, options);
  assert.equal(next.size, 3 * MiB);
  transport.release(next);
  assert.equal(transport.retainedBytes, 0);
});

test("cancelled queued work never opens a native stream", async () => {
  const api = nativeStream(MiB);
  let opened = 0;
  let continueHeaders;
  const original = api.openStream.bind(api);
  api.openStream = async () => { opened++; await new Promise(resolve => { continueHeaders = resolve; }); return original(); };
  const transport = createMediaTransport({ network: () => api, maxConcurrent: 1 });
  const first = transport.load(url, options);
  await new Promise(resolve => setImmediate(resolve));
  const controller = new AbortController();
  const queued = transport.load(url, { ...options, signal: controller.signal });
  controller.abort();
  await assert.rejects(queued, { code: "CANCELLED" });
  assert.equal(opened, 1);
  continueHeaders();
  transport.release(await first);
  assert.equal(transport.retainedBytes, 0);
});

test("invalid MIME cancels the native stream before accumulating a body", async () => {
  const api = nativeStream(MiB, "text/html");
  const transport = createMediaTransport({ network: () => api });
  await assert.rejects(transport.load(url, options), { code: "INVALID_MIME" });
  assert.equal(api.cancelled.length, 1);
  assert.equal(transport.retainedBytes, 0);
});
