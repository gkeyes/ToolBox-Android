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

// Sheets, alerts, toasts and the link dialog are glass, not a solid ground.
test("every sheet, alert and toast floats on glass", () => {
  const css = source("design/ios27.css");
  assert.match(css, /html \.nextflux-modal-surface \{\n  background: var\(--nf-menu\);\n  -webkit-backdrop-filter: blur\(30px\)/);
  assert.doesNotMatch(css, /html \.nextflux-modal-surface \{ background: var\(--nf-sheet-bg\); \}/);
  assert.match(css, /html \.nf-title-filter-toast \{\n  background: var\(--nf-menu\);/);
  assert.match(css, /html \.toolbox-link-dialog \{\n  background: var\(--nf-menu\);/);
  assert.match(source("components/ui/MobileSheet.jsx"), /"nextflux-modal-surface nextflux-mobile-sheet nextflux-floating-sheet p-0"/);
});
