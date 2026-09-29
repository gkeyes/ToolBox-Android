import assert from "node:assert/strict";
import test from "node:test";
import {
  NAVIGATION_STATES,
  createNavigationTransitionMachine,
  navigationVisualState,
} from "../src/motion/navigationTransition.mjs";

test("navigation visual state keeps iOS-style parallax restrained and compositor-only", () => {
  const resting = navigationVisualState(0, 400);
  assert.equal(resting.progress, 0);
  assert.equal(resting.listX, -92);
  assert.equal(resting.coverOpacity, 0.025);
  assert.equal(resting.shadowOpacity, 0);

  const half = navigationVisualState(200, 400);
  assert.equal(half.progress, 0.5);
  assert.ok(half.listX > -12 && half.listX < -11);
  assert.ok(half.coverOpacity > 0.003 && half.coverOpacity < 0.0032);
  assert.ok(half.shadowOpacity > 0.60 && half.shadowOpacity < 0.63);

  const revealed = navigationVisualState(400, 400);
  assert.equal(revealed.progress, 1);
  assert.equal(revealed.listX, 0);
  assert.equal(revealed.coverOpacity, 0);
});

test("navigation state machine models push, interactive pop and exit explicitly", () => {
  const changes = [];
  const machine = createNavigationTransitionMachine({ onChange: (next, previous) => changes.push([previous, next]) });
  assert.equal(machine.state, NAVIGATION_STATES.IDLE);
  assert.equal(machine.transition(NAVIGATION_STATES.PREPARING), true);
  assert.equal(machine.transition(NAVIGATION_STATES.ENTERING), true);
  assert.equal(machine.transition(NAVIGATION_STATES.INTERACTIVE_POP), true);
  assert.equal(machine.transition(NAVIGATION_STATES.SETTLING_POP), true);
  assert.equal(machine.transition(NAVIGATION_STATES.EXITING), true);
  assert.equal(machine.transition(NAVIGATION_STATES.PREPARING), false);
  machine.reset(NAVIGATION_STATES.IDLE);
  assert.equal(machine.state, NAVIGATION_STATES.IDLE);
  assert.deepEqual(changes.slice(0, 5), [
    ["idle", "preparing"],
    ["preparing", "entering"],
    ["entering", "interactive-pop"],
    ["interactive-pop", "settling-pop"],
    ["settling-pop", "exiting"],
  ]);
});
