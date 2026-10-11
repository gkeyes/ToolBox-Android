// Design system v2 spring tokens (shared with the ToolBox host's Compose motion).
// Pure module: used by framer-motion configs, CSS linear() easings and tests.

/** dampingRatio / stiffness pairs from DESIGN_SYSTEM.md §動效 (mass = 1). */
export const SPRINGS = Object.freeze({
  spatial: Object.freeze({ dampingRatio: 0.86, stiffness: 380 }),
  effects: Object.freeze({ dampingRatio: 1.0, stiffness: 600 }),
  pressIn: Object.freeze({ dampingRatio: 1.0, stiffness: 1500 }),
  pressRelease: Object.freeze({ dampingRatio: 0.62, stiffness: 420 }),
  page: Object.freeze({ dampingRatio: 1.0, stiffness: 380 }),
  sheet: Object.freeze({ dampingRatio: 0.9, stiffness: 380 }),
  dialog: Object.freeze({ dampingRatio: 0.9, stiffness: 500 }),
});

/** Normalised step response (0 → 1) of a unit-mass spring at time t seconds. */
export function springPosition({ dampingRatio: z, stiffness: k }, t) {
  const w0 = Math.sqrt(k);
  if (z < 1) {
    const wd = w0 * Math.sqrt(1 - z * z);
    return 1 - Math.exp(-z * w0 * t) * (Math.cos(wd * t) + (z * w0 / wd) * Math.sin(wd * t));
  }
  if (z === 1) return 1 - Math.exp(-w0 * t) * (1 + w0 * t);
  const s = Math.sqrt(z * z - 1);
  const r1 = -w0 * (z - s);
  const r2 = -w0 * (z + s);
  return 1 - (r2 * Math.exp(r1 * t) - r1 * Math.exp(r2 * t)) / (r2 - r1);
}

/** Time (ms, rounded up to 10ms) after which the spring stays within `epsilon` of rest. */
export function springDuration(spring, epsilon = 0.001) {
  const dt = 1 / 1000;
  let last = 0;
  for (let t = 0; t < 3; t += dt) {
    if (Math.abs(1 - springPosition(spring, t)) > epsilon) last = t;
  }
  return Math.ceil((last * 1000 + 1) / 10) * 10;
}

/** CSS `linear()` easing sampled from the spring, valid for `springDuration(spring)`. */
export function springLinearEasing(spring, samples = 24) {
  const duration = springDuration(spring) / 1000;
  const points = [];
  for (let i = 0; i <= samples; i++) {
    const value = i === samples ? 1 : springPosition(spring, (duration * i) / samples);
    points.push(Number(value.toFixed(4)));
  }
  return `linear(${points.join(", ")})`;
}

/** framer-motion transition for a spring token (mass 1 → damping = 2ζ√k). */
export function framerSpring(spring) {
  return {
    type: "spring",
    stiffness: spring.stiffness,
    damping: 2 * spring.dampingRatio * Math.sqrt(spring.stiffness),
    mass: 1,
    restDelta: 0.001,
    restSpeed: 0.01,
  };
}
