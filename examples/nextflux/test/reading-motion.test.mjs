import assert from "node:assert/strict";
import test from "node:test";
import { animateReadingEntrance, animateReadingValue, readingEntranceEase, READING_ENTRANCE, READING_SPRING } from "../src/toolbox/reading-motion.mjs";
import { withAnimationClock } from "./helpers/animation-clock.mjs";

test("reading release uses one critically damped spring and settles without overshoot", () => {
  assert.equal(READING_SPRING.damping, 2 * Math.sqrt(READING_SPRING.stiffness * READING_SPRING.mass));
  for (const [from, to, velocity] of [[150, 0, 0], [0, 430, 800], [0, 360, 100000], [150, 0, 1000]]) {
    withAnimationClock((clock) => {
      const updates = [];
      let done = 0;
      animateReadingValue({ from, to, velocity, onUpdate: (value) => updates.push(value), onDone: () => { done += 1; } });
      clock.settle();
      assert.equal(done, 1);
      assert.equal(updates[0], from);
      assert.equal(updates.at(-1), to);
      assert.ok(clock.now() <= 350);
      for (let i = 1; i < updates.length; i += 1) {
        assert.ok(Number.isFinite(updates[i]));
        assert.ok(updates[i] >= Math.min(from, to) && updates[i] <= Math.max(from, to));
        assert.ok(to > from ? updates[i] >= updates[i - 1] : updates[i] <= updates[i - 1]);
      }
    });
  }
});

test("the same elapsed time produces the same position at different frame rates", () => {
  const valueAt100ms = (frameCount) => withAnimationClock((clock) => {
    let value;
    const cancel = animateReadingValue({ from: 150, to: 0, onUpdate: (position) => { value = position; } });
    for (let i = 0; i < frameCount; i += 1) clock.step(100 / frameCount);
    cancel();
    return value;
  });
  const expected = 150 * (1 + 3.2) * Math.exp(-3.2);
  assert.ok(Math.abs(valueAt100ms(6) - expected) < 1e-9);
  assert.ok(Math.abs(valueAt100ms(12) - expected) < 1e-9);
  assert.ok(Math.abs(valueAt100ms(1) - expected) < 1e-9);
});

test("a long frame or resumed tab completes from real elapsed time in one finite update", () => {
  withAnimationClock((clock) => {
    const updates = [];
    let done = 0;
    animateReadingValue({ from: 430, to: 0, onUpdate: (value) => updates.push(value), onDone: () => { done += 1; } });
    clock.step(2000);
    assert.deepEqual(updates, [430, 0]);
    assert.equal(done, 1);
    assert.equal(clock.pending(), 0);
  });
});

test("cancel stops updates and completion, including cancellation during an update", () => {
  withAnimationClock((clock) => {
    let done = 0;
    const updates = [];
    let cancel;
    cancel = animateReadingValue({
      from: 0, to: 360,
      onUpdate: (value) => { updates.push(value); if (value > 0) cancel(); },
      onDone: () => { done += 1; },
    });
    clock.step(16);
    const count = updates.length;
    clock.step(2000);
    assert.equal(updates.length, count);
    assert.equal(done, 0);
    assert.equal(clock.pending(), 0);
  });
});

test("reduced motion and zero-distance values finish exactly once without scheduling frames", () => {
  for (const options of [{ from: 0, to: 360, reduceMotion: true }, { from: 40, to: 40 }]) {
    withAnimationClock((clock) => {
      const updates = [];
      let done = 0;
      const cancel = animateReadingValue({ ...options, onUpdate: (value) => updates.push(value), onDone: () => { done += 1; } });
      cancel();
      clock.step(2000);
      assert.deepEqual(updates, [options.to]);
      assert.equal(done, 1);
      assert.equal(clock.pending(), 0);
    });
  }
});


test("article entrance uses a fixed non-bouncy navigation curve", () => {
  assert.equal(READING_ENTRANCE.duration, 340);
  assert.equal(readingEntranceEase(0), 0);
  assert.equal(readingEntranceEase(1), 1);
  assert.ok(readingEntranceEase(0.25) > 0.25);
  assert.ok(readingEntranceEase(0.5) > readingEntranceEase(0.25));

  withAnimationClock((clock) => {
    const updates = [];
    let done = 0;
    animateReadingEntrance({
      from: 400,
      to: 0,
      onUpdate: (value) => updates.push(value),
      onDone: () => { done += 1; },
    });
    clock.step(170);
    assert.ok(updates.at(-1) < 200);
    assert.ok(updates.at(-1) > 0);
    clock.step(170);
    assert.equal(updates.at(-1), 0);
    assert.equal(done, 1);
    for (let index = 1; index < updates.length; index += 1) {
      assert.ok(updates[index] <= updates[index - 1]);
      assert.ok(updates[index] >= 0);
    }
  });
});
