import test from "node:test";
import assert from "node:assert/strict";
import {
  CONTINUOUS_PULL_TRIGGER,
  continuousPullResistance,
  findNextUnreadArticle,
  springFrame,
} from "../src/toolbox/continuous-reading-motion.mjs";

test("continuous reading resistance is smooth, monotonic and becomes harder near the threshold", () => {
  const samples = [0, 8, 24, 36, 48, 60, 72, 82, CONTINUOUS_PULL_TRIGGER, 120, 170]
    .map((value) => continuousPullResistance(value));
  assert.equal(samples[0], 0);
  for (let i = 1; i < samples.length; i += 1) assert.ok(samples[i] > samples[i - 1]);
  assert.ok(continuousPullResistance(24) / 24 > continuousPullResistance(92) / 92);
  assert.ok(continuousPullResistance(170) < 80);
});

test("next unread selection skips already-read articles after the active article", () => {
  const rows = [
    { id: 1, status: "read" },
    { id: 2, status: "read" },
    { id: 3, status: "unread" },
    { id: 4, status: "read" },
  ];
  assert.equal(findNextUnreadArticle(rows, 1)?.id, 3);
  assert.equal(findNextUnreadArticle(rows, 3), null);
});

test("spring solver advances toward the target with bounded dt", () => {
  const first = springFrame({ x: 0, velocity: 0, target: 100, dt: 1 / 60, stiffness: 250, damping: 28 });
  assert.ok(first.x > 0);
  assert.ok(first.velocity > 0);
  const bounded = springFrame({ x: 0, velocity: 0, target: 100, dt: 1, stiffness: 250, damping: 28 });
  assert.ok(Number.isFinite(bounded.x));
  assert.ok(bounded.x < 30);
});
