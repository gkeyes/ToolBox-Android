import test from "node:test";
import assert from "node:assert/strict";
import { startProgressiveReading } from "../src/reading/client.js";

function harness({ maxOperations = 12 } = {}) {
  const worker = new EventTarget();
  const posted = [], applied = [], frames = new Map();
  let nextFrame = 0, paused = true, completed = 0, terminated = 0;
  worker.postMessage = (data) => posted.push(data);
  worker.terminate = () => { terminated += 1; };
  const request = startProgressiveReading({ html: "fixture", baseUrl: "https://example.invalid", apply: (op) => applied.push(op),
    maxOperations, shouldPause: () => paused, onDone: () => { completed += 1; }, onError: (error) => { throw error; },
  }, { createWorker: () => worker, now: () => 0,
    schedule: (callback) => { frames.set(++nextFrame, callback); return nextFrame; },
    unschedule: (id) => frames.delete(id),
  });
  const batch = (operations, done) => {
    const event = new Event("message");
    event.data = { type: "reading:batch", id: request.id, operations, done };
    worker.dispatchEvent(event);
  };
  const frame = () => {
    const [id, callback] = frames.entries().next().value ?? [];
    if (!callback) return false;
    frames.delete(id); callback(); return true;
  };
  return { request, batch, frame, posted, applied, frames, resume: () => { paused = false; request.resume(); },
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
