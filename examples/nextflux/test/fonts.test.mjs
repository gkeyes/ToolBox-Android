import test from "node:test";
import assert from "node:assert/strict";
import { approvedFontUrl, parseUnicodeRanges, fontFaceMatchesText, parseFontCss, loadFont, FONT_CATEGORIES, getFontStatus } from "../src/lib/fontLoader.js";

const cssUrl = "https://fonts.googleapis.com/css2?family=Inter:wght@400&display=swap";
const css = (family, src, range = "U+0-7F") => `@font-face {font-family: '${family}'; font-style: normal; font-weight: 400; src: url('${src}') format('woff2'); unicode-range: ${range};}`;

test("font URL policy accepts only HTTPS font providers and pinned WenKai resources", () => {
  assert.equal(approvedFontUrl(cssUrl, undefined, "css"), cssUrl);
  assert.equal(approvedFontUrl("./files/lxgwwenkai-regular-subset-1.woff2", "https://cdn.jsdelivr.net/npm/lxgw-wenkai-webfont@1.7.0/lxgwwenkai-regular.css"), "https://cdn.jsdelivr.net/npm/lxgw-wenkai-webfont@1.7.0/files/lxgwwenkai-regular-subset-1.woff2");
  for (const value of ["http://fonts.gstatic.com/s/a.woff2", "https://fonts.gstatic.com.evil.test/s/a.woff2", "https://secret@fonts.gstatic.com/s/a.woff2", "https://fonts.gstatic.com:8443/s/a.woff2", "https://fonts.gstatic.com/s/a.woff2#hidden", "https://cdn.jsdelivr.net/npm/other-package/font.woff2", "https://cdn.jsdelivr.net/npm/lxgw-wenkai-webfont@latest/files/a.woff2", "https://127.0.0.1/s/a.woff2", "data:font/woff2;base64,AAAA"]) assert.throws(() => approvedFontUrl(value));
});

test("unicode ranges cover inclusive bounds, wildcards and supplementary characters", () => {
  assert.deepEqual(parseUnicodeRanges("U+4E00-4E02, U+1F6??, U+41"), [[0x4e00, 0x4e02], [0x1f600, 0x1f6ff], [65, 65]]);
  const face = { unicodeRange: "U+4E00-4E02, U+1F600-1F601" };
  for (const point of [0x4e00, 0x4e02, 0x1f600, 0x1f601]) assert.equal(fontFaceMatchesText(face, String.fromCodePoint(point)), true);
  for (const point of [0x4dff, 0x4e03, 0x1f5ff, 0x1f602]) assert.equal(fontFaceMatchesText(face, String.fromCodePoint(point)), false);
  for (const value of ["U+FFFFFF", "U+110000", "U+90-80", "U+?1", "U+4?-50", "U+1234567", "url(evil)"]) assert.throws(() => parseUnicodeRanges(value));
});

test("CSS parsing keeps requested family and validates each remote source without injecting CSS", () => {
  const source = css("Inter", "https://fonts.gstatic.com/s/inter/latin.woff2") + css("Other", "https://evil.test/font.woff2");
  assert.deepEqual(parseFontCss(source, cssUrl, "Inter"), [{ url: "https://fonts.gstatic.com/s/inter/latin.woff2", weight: "400", style: "normal", unicodeRange: "U+0-7F" }]);
  assert.throws(() => parseFontCss(css("Inter", "https://evil.test/font.woff2"), cssUrl, "Inter"));
  assert.throws(() => parseFontCss(css("Inter", "https://fonts.gstatic.com/s/inter/a.woff2", "U+110000"), cssUrl, "Inter"));
});

test("selected font only loads matching character subsets through credential-free native requests", async () => {
  const payloads = [];
  const installed = [];
  const oldWindow = globalThis.window;
  const oldDocument = globalThis.document;
  const oldFontFace = globalThis.FontFace;
  globalThis.window = { ToolBox: { network: { request: async (payload) => {
    payloads.push(payload);
    if (payload.url.startsWith("https://fonts.googleapis.com/")) return { status: 200, body: css("Inter", "https://fonts.gstatic.com/s/inter/latin.woff2") + css("Inter", "https://fonts.gstatic.com/s/inter/chinese.woff2", "U+4E00-9FFF"), bodyEncoding: "text" };
    return { status: 200, body: btoa("wOF2test-font"), bodyEncoding: "base64" };
  } } } };
  globalThis.document = { fonts: { add: (face) => installed.push(face) } };
  globalThis.FontFace = class { constructor(family, source, descriptors) { Object.assign(this, { family, source, descriptors }); } async load() { return this; } };
  try {
    await loadFont("Inter", "Hello");
    assert.equal(payloads.length, 2);
    assert.equal(installed.length, 1);
    assert.match(installed[0].source, /^url\(data:font\/woff2;base64,/);
    assert.equal(payloads.some((payload) => payload.url.includes("chinese")), false);
    assert.equal(payloads.some((payload) => payload.url.includes("Hello") || payload.url.includes("text=")), false);
    for (const payload of payloads) {
      assert.equal(payload.headers.Authorization, undefined);
      assert.equal(payload.headers.Cookie, undefined);
      assert.equal(payload.maxResponseBytes <= 4 * 1024 * 1024, true);
    }
    await loadFont("Inter", "Hello");
    assert.equal(payloads.length, 2);
    assert.equal(getFontStatus("Inter").error, null);
  } finally { globalThis.window = oldWindow; globalThis.document = oldDocument; globalThis.FontFace = oldFontFace; }
});

test("missing native network fails closed and keeps a retryable error status", async () => {
  globalThis.window = {};
  await assert.rejects(loadFont("Geist"), /ToolBox/);
  assert.equal(getFontStatus("Geist").loading, false);
  assert.match(getFontStatus("Geist").error, /ToolBox/);
  assert.equal(Object.values(FONT_CATEGORIES).flatMap((category) => category.fonts).length, 21);
});
