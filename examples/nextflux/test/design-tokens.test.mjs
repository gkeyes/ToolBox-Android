import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { SPRINGS, springDuration, springLinearEasing, springPosition, framerSpring } from "../src/design/spring.mjs";

const css = readFileSync(new URL("../src/design/tokens.css", import.meta.url), "utf8");
const kebab = (name) => name.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);

test("spring tokens match design system v2", () => {
  assert.deepEqual(
    Object.fromEntries(Object.entries(SPRINGS).map(([k, v]) => [k, [v.dampingRatio, v.stiffness]])),
    {
      spatial: [0.86, 380], effects: [1, 600], pressIn: [1, 1500], pressRelease: [0.62, 420],
      page: [1, 380], sheet: [0.9, 380], dialog: [0.9, 500],
    },
  );
});

test("CSS linear() easings and durations are generated from the same springs", () => {
  for (const [name, spring] of Object.entries(SPRINGS)) {
    const key = kebab(name);
    assert.ok(css.includes(`--ds-ease-${key}: ${springLinearEasing(spring)};`), `ease ${key} is stale`);
    assert.ok(css.includes(`--ds-dur-${key}: ${springDuration(spring)}ms;`), `duration ${key} is stale`);
  }
});

test("spring response settles at rest and press release overshoots slightly", () => {
  for (const spring of Object.values(SPRINGS)) {
    assert.ok(Math.abs(1 - springPosition(spring, springDuration(spring) / 1000)) <= 0.001);
  }
  let peak = 0;
  for (let t = 0; t < 0.6; t += 0.001) peak = Math.max(peak, springPosition(SPRINGS.pressRelease, t));
  assert.ok(peak > 1.05 && peak < 1.12, `overshoot ${peak}`);
});

test("framer-motion springs convert damping ratio with unit mass", () => {
  const { stiffness, damping, mass } = framerSpring(SPRINGS.spatial);
  assert.equal(stiffness, 380);
  assert.equal(mass, 1);
  assert.ok(Math.abs(damping / (2 * Math.sqrt(380)) - 0.86) < 1e-9);
});

test("palettes follow the iOS 27 host: grouped grey page, white cards, system blue", () => {
  for (const value of ["#F2F2F7", "#FFFFFF", "#000000", "#1C1C1E", "#0064D2", "#0A84FF", "rgb(60 60 67 / 0.22)", "rgb(84 84 88 / 0.65)"]) {
    assert.ok(css.includes(value), value);
  }
  assert.match(css, /@media \(prefers-color-scheme: dark\) \{\s*:root:not\(\[data-theme\]\)/, "first frame follows host scheme");
});
