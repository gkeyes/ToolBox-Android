import test from "node:test";
import assert from "node:assert/strict";

// Actual Nanostores/persistence module; the browser media source is the only fake.
const listeners = new Set();
const classes = new Set();
const attrs = new Map();
const media = {
  matches: true,
  addEventListener(type, fn) { assert.equal(type, "change"); listeners.add(fn); },
  removeEventListener(type, fn) { assert.equal(type, "change"); listeners.delete(fn); },
};
globalThis.window = {
  matchMedia: () => media,
  document: { documentElement: {
    classList: { add: (...names) => names.forEach((n) => classes.add(n)), remove: (...names) => names.forEach((n) => classes.delete(n)) },
    setAttribute: (key, value) => attrs.set(key, value),
  } },
};
const { initTheme, setTheme, currentThemeMode, themeState } = await import("../src/stores/themeStore.js");
function change(dark) {
  media.matches = dark;
  for (const listener of listeners) listener({ matches: dark });
}

test("system theme initializes dark and updates both DOM and subscribed components without reload", () => {
  const dispose = initTheme();
  const modes = [];
  const unlisten = currentThemeMode.subscribe((mode) => modes.push(mode));
  try {
    assert.equal(currentThemeMode.get(), "dark");
    assert.equal(attrs.get("data-theme"), "dark");
    change(false);
    assert.equal(currentThemeMode.get(), "light");
    assert.equal(classes.has("light"), true);
    assert.equal(classes.has("dark"), false);
    change(true);
    assert.equal(currentThemeMode.get(), "dark");
    assert.deepEqual(modes, ["dark", "light", "dark"]);
    assert.equal(themeState.get().themeMode, "system");
  } finally { unlisten(); dispose(); }
});

test("explicit mini-tool theme wins and selected palette survives returning to system", () => {
  const dispose = initTheme();
  try {
    setTheme("light", "stone");
    change(true);
    assert.equal(currentThemeMode.get(), "light");
    assert.equal(attrs.get("data-theme"), "stone");
    setTheme("dark", "nord-dark");
    change(false);
    assert.equal(currentThemeMode.get(), "dark");
    assert.equal(attrs.get("data-theme"), "nord-dark");
    setTheme("system");
    assert.equal(currentThemeMode.get(), "light");
    assert.equal(attrs.get("data-theme"), "stone");
    change(true);
    assert.equal(attrs.get("data-theme"), "nord-dark");
    assert.equal(themeState.get().themeMode, "system");
  } finally { dispose(); }
});

test("reinitialization is idempotent and old disposal cannot detach a newer listener", () => {
  const oldDispose = initTheme();
  const dispose = initTheme();
  oldDispose();
  assert.equal(listeners.size, 1);
  change(false);
  assert.equal(currentThemeMode.get(), "light");
  dispose();
  assert.equal(listeners.size, 0);
});
