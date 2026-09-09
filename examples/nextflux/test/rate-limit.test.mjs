import test from "node:test";
import assert from "node:assert/strict";
import { waitForRateLimit } from "../src/toolbox/rate-limit.js";

function timer() {
  let elapsed = 0;
  const waits = [];
  return {
    waits,
    now: () => elapsed,
    sleep: async (milliseconds) => { waits.push(milliseconds); elapsed += milliseconds; },
    advance: (milliseconds) => { elapsed += milliseconds; },
  };
}

test("admission backoff waits only the remaining window and checks at expiry", async () => {
  const clock = timer();
  const checks = [];
  assert.equal(await waitForRateLimit({ code: "RATE_LIMITED", retryAfterMs: 2250 }, () => {
    checks.push(clock.now());
  }, clock), true);
  assert.deepEqual(clock.waits, [1000, 1000, 250]);
  assert.deepEqual(checks, [0, 1000, 2000, 2250]);

  const delayed = timer();
  assert.equal(await waitForRateLimit({ code: "RATE_LIMITED", retryAfterMs: 1500 }, async () => {
    if (delayed.now() === 0) delayed.advance(750);
  }, delayed), true);
  assert.deepEqual(delayed.waits, [750], "time spent checking does not restart the window");
});

test("older hosts and invalid optional waits retain the 60-second fallback", async () => {
  for (const retryAfterMs of [undefined, null, -1, 1.5, Infinity, NaN, "1"]) {
    const clock = timer();
    await waitForRateLimit({ code: "RATE_LIMITED", retryAfterMs }, undefined, clock);
    assert.equal(clock.now(), 60000);
    assert.equal(clock.waits.length, 60);
  }
  const clock = timer();
  let checks = 0;
  await waitForRateLimit({ code: "RATE_LIMITED", retryAfterMs: 0 }, () => { checks += 1; }, clock);
  assert.equal(checks, 1, "even an expired window rechecks intent");
  assert.deepEqual(clock.waits, []);
});

test("account or intent cancellation interrupts a pending admission wait", async () => {
  const clock = timer();
  assert.equal(await waitForRateLimit({ code: "RATE_LIMITED", retryAfterMs: 9000 },
    () => clock.now() < 1000, clock), false);
  assert.deepEqual(clock.waits, [1000]);

  const failure = Object.assign(new Error("account changed"), { code: "ACCOUNT_CHANGED" });
  const next = timer();
  await assert.rejects(waitForRateLimit({ code: "RATE_LIMITED", retryAfterMs: 9000 }, () => {
    if (next.now()) throw failure;
  }, next), (error) => error === failure);
  assert.deepEqual(next.waits, [1000]);
});

test("HTTP 429 and uncertain transport failures cannot enter admission retry", async () => {
  for (const error of [
    { code: "HTTP_ERROR", response: { status: 429 } },
    { code: "RATE_LIMITED", response: { status: 429 } },
    { code: "NETWORK_TIMEOUT" },
    { code: "NETWORK_UNAVAILABLE" },
  ]) {
    const clock = timer();
    await assert.rejects(waitForRateLimit(error, undefined, clock), (failure) => failure === error);
    assert.deepEqual(clock.waits, []);
  }
});
