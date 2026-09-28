import test from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULT_BACKGROUND_INTERVAL_MINUTES,
  backgroundHealth,
  backgroundIntervalMs,
} from "../src/toolbox/background-policy.mjs";

test("manual sync still receives the documented 15 minute background interval", () => {
  assert.equal(DEFAULT_BACKGROUND_INTERVAL_MINUTES, 15);
  assert.equal(backgroundIntervalMs("0"), 15 * 60 * 1000);
  assert.equal(backgroundIntervalMs(0), 15 * 60 * 1000);
  assert.equal(backgroundIntervalMs("bad"), 15 * 60 * 1000);
});

test("explicit positive sync intervals are preserved for background runtime timers", () => {
  for (const minutes of [5, 15, 30, 60]) {
    assert.equal(backgroundIntervalMs(String(minutes)), minutes * 60 * 1000);
  }
});

test("background health records attempts, success and stop without losing history", () => {
  let state = backgroundHealth();
  state = backgroundHealth(state, { type: "enabled" }, 1_000, 300_000);
  assert.equal(state.enabled, true);
  assert.equal(state.state, "running");
  assert.equal(state.nextRunAt, 301_000);

  state = backgroundHealth(state, { type: "attempt", reason: "timer" }, 2_000, 300_000);
  assert.equal(state.state, "syncing");
  assert.equal(state.lastAttemptAt, 2_000);
  assert.equal(state.lastReason, "timer");

  state = backgroundHealth(state, { type: "success" }, 3_000, 300_000);
  assert.equal(state.state, "running");
  assert.equal(state.lastSuccessAt, 3_000);
  assert.equal(state.lastError, null);
  assert.equal(state.consecutiveFailures, 0);

  state = backgroundHealth(state, { type: "stopped" }, 4_000, 300_000);
  assert.equal(state.enabled, false);
  assert.equal(state.state, "stopped");
  assert.equal(state.nextRunAt, null);
  assert.equal(state.lastSuccessAt, 3_000);
});

test("background health keeps a bounded failure signal and clears it after recovery", () => {
  let state = backgroundHealth({}, { type: "enabled" }, 1_000, 900_000);
  state = backgroundHealth(state, { type: "failure", message: "x".repeat(400) }, 2_000, 900_000);
  assert.equal(state.state, "error");
  assert.equal(state.consecutiveFailures, 1);
  assert.equal(state.lastError.length, 160);
  assert.equal(state.nextRunAt, 902_000);

  state = backgroundHealth(state, { type: "success" }, 3_000, 900_000);
  assert.equal(state.state, "running");
  assert.equal(state.consecutiveFailures, 0);
  assert.equal(state.lastError, null);
});


test("background health distinguishes a deferred sync from a real success", () => {
  let state = backgroundHealth({}, { type: "enabled" }, 1_000, 1_800_000);
  state = backgroundHealth(state, { type: "attempt", reason: "timer" }, 2_000, 1_800_000);
  state = backgroundHealth(state, { type: "deferred", message: "后台同步已让位给前台操作。" }, 3_000, 1_800_000);
  assert.equal(state.state, "deferred");
  assert.equal(state.lastSuccessAt, null);
  assert.equal(state.consecutiveFailures, 0);
  assert.equal(state.lastError, "后台同步已让位给前台操作。");
  assert.deepEqual(state.recent.map((item) => item.outcome), ["attempt", "deferred"]);

  state = backgroundHealth(state, { type: "success" }, 4_000, 1_800_000);
  assert.equal(state.state, "running");
  assert.equal(state.lastSuccessAt, 4_000);
  assert.equal(state.lastError, null);
  assert.deepEqual(state.recent.map((item) => item.outcome), ["attempt", "deferred", "success"]);
});

test("background health keeps only the latest eight diagnostic events", () => {
  let state = backgroundHealth({}, { type: "enabled" }, 0, 300_000);
  for (let i = 1; i <= 12; i += 1) {
    state = backgroundHealth(state, { type: "attempt", reason: "timer" }, i * 1_000, 300_000);
  }
  assert.equal(state.recent.length, 8);
  assert.equal(state.recent[0].at, 5_000);
  assert.equal(state.recent.at(-1).at, 12_000);
});
