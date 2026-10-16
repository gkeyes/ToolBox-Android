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

// Buttons are iOS interactive glass: no shrink, grow and brighten on press,
// glass material on filled buttons, press state from touch too.
test("buttons grow into glass on press instead of shrinking", () => {
  const css = source("design/glass-buttons.css");
  assert.match(css, /--ds-press-scale: 1;/);
  assert.match(css, /--ds-press-scale-row: 1;/);
  assert.match(css, /scale: var\(--nf-press-grow\);\n  filter: brightness\(1\.08\);/);
  assert.match(css, /--nf-press-grow: 1\.08;/);
  assert.match(source("index.css"), /@import "\.\/design\/ios27\.css";\n@import "\.\/design\/glass-buttons\.css";/);
  const lens = source("design/lens.js");
  assert.match(lens, /export function installGlassPress/);
  assert.match(lens, /\["touchstart", start\]/);
  assert.match(source("render.jsx"), /\ninstallGlassPress\(\);\n/);
  assert.doesNotMatch(source("components/Settings/Settings.jsx"), /size-10 min-w-10/);
});

// Scrolling swaps the edge lens for the same blur without bending.
test("the edge lens pauses while the page scrolls", () => {
  assert.match(source("design/lens.js"), /doc\.addEventListener\("scroll", onScroll, \{ capture: true, passive: true \}\)/);
  assert.match(source("design/lens-host.css"), /:root\[data-nf-scrolling\] \[style\*="--nf-lens"\] \{ --nf-lens: blur\(0px\) !important; \}/);
});
