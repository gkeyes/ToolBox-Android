import test from "node:test";
import assert from "node:assert/strict";
import { createNetworkPriorityGate } from "../src/toolbox/foreground-gate.mjs";

test("background waits while a foreground request owns priority", async () => {
  const gate = createNetworkPriorityGate();
  const release = gate.beginForeground();
  let passed = false;
  const waiter = gate.waitForForeground().then(() => { passed = true; });
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(passed, false);
  release();
  await waiter;
  assert.equal(passed, true);
});

test("multiple foreground requests must all finish before background continues", async () => {
  const gate = createNetworkPriorityGate();
  const first = gate.beginForeground();
  const second = gate.beginForeground();
  let passed = false;
  const waiter = gate.waitForForeground().then(() => { passed = true; });
  first();
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(passed, false);
  second();
  await waiter;
  assert.equal(passed, true);
});
