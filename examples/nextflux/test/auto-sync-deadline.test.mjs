import test from "node:test";
import assert from "node:assert/strict";
import { createAutoSyncDeadline } from "../src/toolbox/auto-sync-deadline.mjs";

test("late native callback cannot claim a completed automatic cycle twice", () => {
  const schedule = createAutoSyncDeadline();
  const intervalMs = 900_000;
  assert.equal(schedule.claim({ now: 900_000, intervalMs, lastSuccessAt: 0 }), true);
  assert.equal(schedule.claim({ now: 900_100, intervalMs, lastSuccessAt: 0 }), false);
  assert.equal(schedule.claim({ now: 900_200, intervalMs, lastSuccessAt: 900_100 }), false);
  assert.equal(schedule.claim({ now: 1_800_101, intervalMs, lastSuccessAt: 900_100 }), true);
});

test("failed automatic sync retries only after the configured interval", () => {
  const schedule = createAutoSyncDeadline();
  assert.equal(schedule.claim({ now: 100, intervalMs: 60_000 }), true);
  assert.equal(schedule.claim({ now: 59_999, intervalMs: 60_000 }), false);
  assert.equal(schedule.claim({ now: 60_100, intervalMs: 60_000 }), true);
});

test("an old timer after interval change follows the new deadline", () => {
  const schedule = createAutoSyncDeadline();
  assert.equal(schedule.claim({ now: 900_000, intervalMs: 900_000, lastSuccessAt: 0 }), true);
  assert.equal(schedule.claim({ now: 910_000, intervalMs: 1_800_000, lastSuccessAt: 900_100 }), false);
  assert.equal(schedule.claim({ now: 2_700_100, intervalMs: 1_800_000, lastSuccessAt: 900_100 }), true);
});
