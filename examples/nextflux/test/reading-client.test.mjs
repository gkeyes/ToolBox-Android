import test from "node:test";
import assert from "node:assert/strict";
import { startProgressiveReading } from "../src/reading/client.js";

function harness({ maxOperations = 12, immediateFirstBatch = false, initiallyPaused = true, retainPrefix = false, prepared } = {}) {
  const worker = new EventTarget();
  const posted = [], applied = [], metadata = [], frames = new Map();
  let nextFrame = 0, paused = initiallyPaused, completed = 0, terminated = 0;
  worker.postMessage = (data) => posted.push(data);
  worker.terminate = () => { terminated += 1; };
  const request = startProgressiveReading({ html: "fixture", baseUrl: "https://example.invalid", apply: (op) => applied.push(op),
    immediateFirstBatch, maxOperations, retainPrefix, prepared, onMetadata: (data) => metadata.push(data),
    shouldPause: () => paused, onDone: () => { completed += 1; }, onError: (error) => { throw error; },
  }, { createWorker: () => worker, now: () => 0,
    schedule: (callback) => { frames.set(++nextFrame, callback); return nextFrame; },
    unschedule: (id) => frames.delete(id),
  });
  const batch = (operations, done, batchMetadata) => {
    const event = new Event("message");
    event.data = { type: "reading:batch", id: request.id, operations, done, metadata: batchMetadata };
    worker.dispatchEvent(event);
  };
  const frame = () => {
    const [id, callback] = frames.entries().next().value ?? [];
    if (!callback) return false;
    frames.delete(id); callback(); return true;
  };
  return { request, batch, frame, posted, applied, metadata, frames, resume: () => { paused = false; request.resume(); },
    completed: () => completed, terminated: () => terminated };
}

test("a first-screen pause retains unfinished operations and resumes without duplication", () => {
  const h = harness();
  const operations = Array.from({ length: 80 }, (_, id) => ({ id }));
  h.batch(operations, true);
  h.frame();
  assert.deepEqual(h.applied, operations.slice(0, 12));
  assert.equal(h.frames.size, 0);
  assert.equal(h.completed(), 0);
  assert.equal(h.terminated(), 0);
  h.resume();
  while (h.frame()) {}
  assert.deepEqual(h.applied, operations);
  assert.equal(h.completed(), 1);
  assert.equal(h.terminated(), 1);
});

test("a paused viewport does not request the next worker batch until resumed", () => {
  const h = harness();
  h.batch([{ id: 1 }], false);
  h.frame();
  assert.deepEqual(h.posted.map((item) => item.type), ["reading:start"]);
  h.resume(); h.frame();
  assert.deepEqual(h.posted.map((item) => item.type), ["reading:start", "reading:next"]);
  h.batch([{ id: 2 }], true); h.frame();
  assert.deepEqual(h.applied, [{ id: 1 }, { id: 2 }]);
  assert.equal(h.completed(), 1);
});

test("resuming normal reading restores its operation budget on the next frame", () => {
  let budget = 12;
  const h = harness({ maxOperations: () => budget });
  const operations = Array.from({ length: 80 }, (_, id) => ({ id }));
  h.batch(operations, true);
  h.frame();
  assert.equal(h.applied.length, 12);
  budget = 48;
  h.resume(); h.frame();
  assert.equal(h.applied.length, 60);
  h.frame();
  assert.deepEqual(h.applied, operations);
  assert.equal(h.completed(), 1);
});

test("cancelling a paused preview discards late batches and resume work", () => {
  const h = harness();
  h.batch(Array.from({ length: 80 }, (_, id) => ({ id })), false); h.frame();
  h.request.cancel(); h.resume();
  h.batch([{ id: 999 }], true);
  assert.equal(h.frames.size, 0);
  assert.equal(h.applied.length, 12);
  assert.equal(h.completed(), 0);
  assert.equal(h.terminated(), 1);
});

test("only the first foreground batch can mount immediately and it still obeys the work cap", () => {
  const active = harness({ immediateFirstBatch: true, initiallyPaused: false, maxOperations: 2 });
  active.batch([1, 2, 3], false);
  assert.deepEqual(active.applied, [1, 2]);
  active.frame();
  active.batch([4], true);
  assert.deepEqual(active.applied, [1, 2, 3]);
  active.frame();
  assert.equal(active.completed(), 1);
  const paused = harness({ immediateFirstBatch: true });
  paused.batch([1], true);
  assert.deepEqual(paused.applied, []);
  paused.request.cancel();
});

test("one reader adopts the paused worker, replays its prefix, then drains it under foreground backpressure", () => {
  const preview = harness({ retainPrefix: true, maxOperations: 2 });
  const info = { title: "fixture" };
  preview.batch([1, 2, 3, 4], false, info);
  preview.frame();
  assert.deepEqual(preview.applied, [1, 2]);
  const token = preview.request.prepareHandoff();
  const otherToken = preview.request.prepareHandoff();
  assert.ok(token && otherToken);
  const reader = harness({ prepared: token, initiallyPaused: false, maxOperations: 3 });
  assert.equal(reader.request.id, preview.request.id);
  assert.deepEqual(reader.posted, []);
  assert.deepEqual(reader.metadata, [info]);
  assert.equal(token.take({}), null);
  assert.equal(otherToken.take({}), null);
  preview.request.cancel();
  preview.resume();
  assert.equal(preview.terminated(), 0);
  assert.equal(preview.frames.size, 1);
  preview.frame();
  assert.deepEqual(reader.applied, [1, 2, 3]);
  assert.deepEqual(preview.posted.map((item) => item.type), ["reading:start"]);
  preview.frame();
  assert.deepEqual(preview.posted.map((item) => item.type), ["reading:start", "reading:next"]);
  preview.batch([5], true);
  preview.frame();
  assert.deepEqual(preview.applied, [1, 2]);
  assert.deepEqual(reader.applied, [1, 2, 3, 4, 5]);
  assert.equal(preview.completed(), 0);
  assert.equal(reader.completed(), 1);
  assert.equal(preview.terminated(), 1);
});

test("cancelling after adoption removes scheduled replay and ignores late messages from the original worker", () => {
  const preview = harness({ retainPrefix: true });
  preview.batch([1], false); preview.frame();
  const reader = harness({ prepared: preview.request.prepareHandoff(), initiallyPaused: false });
  assert.equal(reader.request.id, preview.request.id);
  reader.request.cancel();
  preview.request.cancel();
  preview.batch([2], true, { title: "late" });
  assert.equal(preview.frame(), false);
  assert.deepEqual(reader.applied, []);
  assert.deepEqual(reader.metadata, []);
  assert.equal(reader.completed(), 0);
  assert.equal(preview.terminated(), 1);
});

test("an unclaimed token expires with its preview, while a previously completed preview can replay without another worker", () => {
  const removed = harness({ retainPrefix: true });
  removed.batch([1], false); removed.frame();
  const stale = removed.request.prepareHandoff();
  assert.ok(stale);
  removed.request.cancel();
  const fresh = harness({ prepared: stale });
  assert.notEqual(fresh.request.id, removed.request.id);
  assert.deepEqual(fresh.posted.map((item) => item.type), ["reading:start"]);
  fresh.request.cancel();

  const finished = harness({ retainPrefix: true });
  finished.batch([1, 2], true); finished.frame();
  assert.equal(finished.terminated(), 1);
  const reader = harness({ prepared: finished.request.prepareHandoff(), initiallyPaused: false });
  finished.request.cancel();
  finished.frame();
  assert.deepEqual(reader.posted, []);
  assert.deepEqual(reader.applied, [1, 2]);
  assert.equal(reader.completed(), 1);
  assert.equal(finished.terminated(), 1);
});

test("the operation and text caps each allow the boundary and fall back to a fresh worker beyond it", () => {
  for (const [operations, transferable] of [
    [Array(512).fill(1), true], [Array(513).fill(1), false],
    [[{ attrs: { alt: "x".repeat(128 * 1024) } }], true],
    [[{ attrs: { alt: "x".repeat(128 * 1024 + 1) } }], false],
  ]) {
    const preview = harness({ retainPrefix: true, maxOperations: 1024 });
    preview.batch(operations, false); preview.frame();
    const token = preview.request.prepareHandoff();
    assert.equal(Boolean(token), transferable);
    const reader = harness({ prepared: token, initiallyPaused: false });
    assert.equal(reader.request.id === preview.request.id, transferable);
    assert.deepEqual(reader.posted.map((item) => item.type), transferable ? [] : ["reading:start"]);
    preview.request.cancel();
    reader.request.cancel();
    assert.equal(preview.terminated(), 1);
  }
});
