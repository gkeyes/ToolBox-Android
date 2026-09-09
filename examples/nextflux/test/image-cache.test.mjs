import test from "node:test";
import assert from "node:assert/strict";
import { createImageCache } from "../src/toolbox/imageCache.js";

function harness(options = {}) {
  let time = 0;
  let sequence = 0;
  const timers = new Map();
  const revoked = [];
  const requested = [];
  const created = [];
  const cache = createImageCache({
    load: async (source) => { requested.push(source); return new Blob([source]); },
    now: () => time,
    setTimer(callback, delay) { const id = ++sequence; timers.set(id, { callback, due: time + delay }); return id; },
    clearTimer: (id) => timers.delete(id),
    createUrl() { const url = `blob:cache-${++sequence}`; created.push(url); return url; },
    revokeUrl: (url) => revoked.push(url),
    ...options,
  });
  function advance(milliseconds) {
    time += milliseconds;
    while (true) {
      const due = [...timers].find(([, timer]) => timer.due <= time);
      if (!due) break;
      timers.delete(due[0]);
      due[1].callback();
    }
  }
  return { cache, advance, revoked, requested, created };
}

test("released images reuse their Blob for 30 seconds and a live lease never expires", async () => {
  const { cache, advance, requested, revoked } = harness();
  const first = cache.acquire("image");
  const url = await first.promise;
  first.release();
  first.release();
  advance(29999);
  const again = cache.acquire("image");
  assert.equal(again.url, url);
  assert.equal(await again.promise, url);
  assert.deepEqual(requested, ["image"]);
  advance(60000);
  assert.deepEqual(revoked, []);
  again.release();
  advance(29999);
  assert.ok(cache.hasBlob(url));
  advance(1);
  assert.deepEqual(revoked, [url]);
  assert.equal(cache.hasBlob(url), false);
});

test("idle count evicts the least recently used image and preserves active images", async () => {
  const { cache, revoked } = harness();
  const active = cache.acquire("active");
  const activeUrl = await active.promise;
  const urls = [];
  for (let index = 0; index < 12; index += 1) {
    const image = cache.acquire(`idle-${index}`);
    urls.push(await image.promise);
    image.release();
  }
  const touched = cache.acquire("idle-0");
  touched.release();
  const extra = cache.acquire("extra");
  await extra.promise;
  extra.release();
  assert.deepEqual(revoked, [urls[1]]);
  assert.ok(cache.hasBlob(urls[0]));
  assert.ok(cache.hasBlob(activeUrl));
  active.release();
  cache.clear();
});

test("the idle Blob budget is 8 MiB, independent of active resources", async () => {
  const { cache, revoked } = harness({ load: async () => new Blob([new Uint8Array(2 * 1024 * 1024)]) });
  const active = cache.acquire("active");
  const activeUrl = await active.promise;
  const urls = [];
  for (let index = 0; index < 5; index += 1) {
    const image = cache.acquire(`idle-${index}`);
    urls.push(await image.promise);
    image.release();
  }
  assert.deepEqual(revoked, [urls[0]]);
  assert.ok(cache.hasBlob(activeUrl));
  for (const url of urls.slice(1)) assert.ok(cache.hasBlob(url));
  active.release();
  cache.clear();
});

test("idle images do not consume the existing 24 active image slots", async () => {
  const { cache } = harness();
  for (let index = 0; index < 12; index += 1) {
    const idle = cache.acquire(`idle-${index}`);
    await idle.promise;
    idle.release();
  }
  const active = Array.from({ length: 24 }, (_, index) => cache.acquire(`active-${index}`));
  assert.throws(() => cache.acquire("overflow"), /图片较多/);
  await Promise.all(active.map((image) => image.promise));
  active.forEach((image) => image.release());
  cache.clear();
});

test("a gallery lease obtained from an owned Blob survives the inline image release", async () => {
  const { cache, advance, revoked } = harness();
  const inline = cache.acquire("gallery-image");
  const url = await inline.promise;
  const gallery = cache.acquire(url);
  assert.equal(await gallery.promise, url);
  inline.release();
  advance(60000);
  assert.deepEqual(revoked, []);
  gallery.release();
  advance(30000);
  assert.deepEqual(revoked, [url]);
});

test("decode retries replace the cached image without revoking another viewer's lease", async () => {
  const { cache, revoked, requested } = harness();
  const first = cache.acquire("image");
  const otherViewer = cache.acquire("image");
  const failedUrl = await first.promise;
  first.invalidate();
  first.release();
  assert.ok(cache.hasBlob(failedUrl));
  assert.deepEqual(revoked, []);
  const retry = cache.acquire("image");
  const freshUrl = await retry.promise;
  assert.notEqual(freshUrl, failedUrl);
  otherViewer.release();
  assert.deepEqual(revoked, [failedUrl]);
  const shared = cache.acquire("image");
  assert.equal(await shared.promise, freshUrl);
  assert.deepEqual(requested, ["image", "image"]);
  retry.release();
  shared.release();
  cache.clear();
});

test("released queued work never starts and account clearing keeps native concurrency bounded", async () => {
  const pending = [];
  const { cache, created } = harness({ load: (source) => new Promise((resolve) => pending.push({ source, resolve })) });
  const old = Array.from({ length: 4 }, (_, index) => cache.acquire(`old-${index}`));
  const rejected = old.map((image) => assert.rejects(image.promise, { code: "CANCELLED" }));
  assert.equal(pending.length, 3);
  old[3].release();
  cache.clear();
  const fresh = cache.acquire("new-account");
  assert.equal(pending.length, 3, "old native requests still occupy their three slots");
  pending.slice(0, 3).forEach(({ resolve }) => resolve(new Blob(["old"])));
  await Promise.all(rejected);
  await new Promise(setImmediate);
  assert.deepEqual(pending.map(({ source }) => source), ["old-0", "old-1", "old-2", "new-account"]);
  assert.deepEqual(created, [], "late replies cannot create or repopulate Blobs");
  pending[3].resolve(new Blob(["new"]));
  const freshUrl = await fresh.promise;
  old.forEach((image) => image.release());
  assert.ok(cache.hasBlob(freshUrl));
  fresh.release();
  cache.clear();
});
