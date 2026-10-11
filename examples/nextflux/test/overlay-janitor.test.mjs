import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");

// The janitor must be installed by the app entry, gated on the stores that
// mean an overlay is legitimately open.
test("the app installs the overlay janitor with the open-overlay stores", () => {
  const entry = source("render.jsx");
  assert.match(entry, /installOverlayJanitor\(\{ isOverlayExpected: \(\) => isModalOpen\.get\(\) \|\| imageGalleryActive\.get\(\) \}\)/);
});

// Every article media element must stay inline (no fullscreen host exists).
test("article media elements opt out of fullscreen, picture-in-picture and cast", () => {
  const iframe = source("components/ArticleView/components/Iframe.jsx");
  const streaming = source("components/ArticleView/components/StreamingMedia.jsx");
  for (const tag of iframe.match(/<(?:video|audio)\b[^>]*>/g)) assert.match(tag, /ref=\{keepMediaInline\}/, tag);
  assert.match(streaming, /keepMediaInline\(elementRef\.current\)/);
  assert.match(streaming, /"nofullscreen noremoteplayback nodownload"/);
});
