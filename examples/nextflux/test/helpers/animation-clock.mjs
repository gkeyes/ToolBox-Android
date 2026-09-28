import assert from "node:assert/strict";

export function withAnimationClock(run) {
  const keys = ["performance", "requestAnimationFrame", "cancelAnimationFrame"];
  const originals = keys.map((key) => Object.getOwnPropertyDescriptor(globalThis, key));
  let now = 0;
  let id = 0;
  const frames = new Map();
  Object.defineProperty(globalThis, "performance", { configurable: true, value: { now: () => now } });
  globalThis.requestAnimationFrame = (callback) => { frames.set(++id, callback); return id; };
  globalThis.cancelAnimationFrame = (frame) => frames.delete(frame);
  const clock = {
    now: () => now,
    pending: () => frames.size,
    set: (value) => { now = value; },
    step: (elapsed) => {
      now += elapsed;
      const callbacks = [...frames.values()];
      frames.clear();
      for (const callback of callbacks) callback(now);
    },
    settle: () => {
      let count = 0;
      while (frames.size && count++ < 120) clock.step(1000 / 60);
      assert.equal(frames.size, 0, "animation should settle in fewer than 120 frames");
    },
  };
  try {
    return run(clock);
  } finally {
    keys.forEach((key, index) => {
      if (originals[index]) Object.defineProperty(globalThis, key, originals[index]);
      else delete globalThis[key];
    });
  }
}
