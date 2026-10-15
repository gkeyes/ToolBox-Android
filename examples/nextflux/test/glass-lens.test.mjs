import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");

// Every chrome surface painted with the glass filter takes the per-element
// edge lens first, with a frosted fallback when there is none.
test("glass chrome uses the edge lens with a frosted fallback", () => {
  const css = source("design/ios27.css");
  const uses = css.match(/backdrop-filter:[^;]*var\(--nf-glass-filter\)/g);
  assert.ok(uses.length >= 10, `only ${uses.length} glass filter uses`);
  for (const use of uses) assert.match(use, /var\(--nf-lens, blur\(12px\)\) var\(--nf-glass-filter\)/, use);
  assert.match(css, /--nf-glass-filter: blur\(2px\) saturate\(180%\) brightness\(1\.05\);/);
});

test("the app installs the lens", () => {
  assert.match(source("render.jsx"), /\ninstallLens\(\);\n/);
});
