import test from "node:test";
import assert from "node:assert/strict";
import { cleanAttributes } from "../src/toolbox/content.js";
import { createReadingParser } from "../src/reading/parser.js";

const base = "https://news.example/articles/story/";
const candidates = (attrs) => attrs["data-image-candidates"]
  ? JSON.parse(attrs["data-image-candidates"]) : [attrs["data-image-source"]].filter(Boolean);
function imageOperations(html, url = base) {
  const parser = createReadingParser(html, url), images = [];
  for (;;) {
    const batch = parser.next();
    images.push(...batch.operations.filter((op) => op.type === "image"));
    if (batch.done) return images;
  }
}

test("a bad src retains real srcset alternatives in descending resolution order", () => {
  const attrs = cleanAttributes("img", {
    src: "/story-page", srcset: "/small.jpg 320w, /large.jpg 1280w, /medium.jpg 640w",
  }, base);
  assert.deepEqual(candidates(attrs), [
    "https://news.example/story-page", "https://news.example/large.jpg",
    "https://news.example/medium.jpg", "https://news.example/small.jpg",
  ]);
  assert.equal(attrs.src, undefined);
  assert.equal(attrs.srcset, undefined);
});

test("embedded placeholders yield to lazy srcset and keep no active lazy attributes", () => {
  const transparent = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
  for (const name of ["srcset", "data-srcset", "data-lazy-srcset"]) {
    const attrs = cleanAttributes("img", { src: transparent, [name]: "small.jpg 1x, full.jpg 2x", onerror: "alert(1)" }, base);
    assert.equal(attrs["data-image-source"], "https://news.example/articles/story/full.jpg");
    assert.deepEqual(candidates(attrs).slice(0, 2), [
      "https://news.example/articles/story/full.jpg", "https://news.example/articles/story/small.jpg",
    ]);
    assert.equal(attrs[name], undefined);
    assert.equal(attrs.onerror, undefined);
  }
});

test("commas in CDN parameters and data URLs are not candidate separators", () => {
  const attrs = cleanAttributes("img", {
    srcset: "https://cdn.example/cdn-cgi/image/width=320,quality=80/photo.jpg 320w, https://cdn.example/cdn-cgi/image/width=1280,quality=80/photo.jpg 1280w",
  }, base);
  assert.deepEqual(candidates(attrs), [
    "https://cdn.example/cdn-cgi/image/width=1280,quality=80/photo.jpg",
    "https://cdn.example/cdn-cgi/image/width=320,quality=80/photo.jpg",
  ]);
  const data = "data:image/png;base64,iVBORw0KGgo=";
  assert.equal(cleanAttributes("img", { srcset: `${data} 1x, /real.jpg 2x` }, base)["data-image-source"], "https://news.example/real.jpg");
});

test("invalid descriptors and executable, credentialed or insecure sources are excluded", () => {
  const attrs = cleanAttributes("img", {
    "data-src": "http://cdn.example/lazy.jpg",
    src: "https://news.example/real.jpg",
    srcset: "javascript:alert(1) 2x, https://user:secret@cdn.example/img 3x, /zero.jpg 0w, /bad.jpg bogus, /bad-mix.jpg 2x 300w, /negative.jpg -1x",
  }, base);
  assert.deepEqual(candidates(attrs), ["https://news.example/real.jpg"]);
});

test("relative server proxy and archive URLs use the Miniflux origin", () => {
  for (const source of ["/proxy/signature/encoded-origin", "/media/v1/store/encoded-origin/signature"]) {
    assert.equal(cleanAttributes("img", { src: source }, base)["data-image-source"], `https://miniflux.xiaochen.win${source}`);
  }
});

test("picture sources contribute candidates even when img has no src", () => {
  const images = imageOperations('<picture><source type="image/avif" srcset="/a.avif 1x, /a2.avif 2x"><source type="image/webp" data-srcset="/a.webp 1x"><img alt="picture"></picture><img src="/outside.jpg">');
  assert.equal(images.length, 2);
  assert.deepEqual(candidates(images[0].attrs), [
    "https://news.example/a2.avif", "https://news.example/a.avif", "https://news.example/a.webp",
  ]);
  assert.deepEqual(candidates(images[1].attrs), ["https://news.example/outside.jpg"]);
});

test("candidate lists deduplicate exact identities without changing signed URLs", () => {
  const signed = "https://cdn.example/image?sig=a%2Fb&v=2";
  const attrs = cleanAttributes("img", { "data-src": signed, src: signed, srcset: `${signed} 2x, /backup.jpg 1x` }, base);
  assert.deepEqual(candidates(attrs), [signed, "https://news.example/backup.jpg"]);
});

test("server archives and proxies precede direct lazy or srcset origins", () => {
  const cached = "https://miniflux.xiaochen.win/media/v1/store/encoded-origin/signature";
  const proxy = "https://miniflux.xiaochen.win/proxy/signature/encoded-origin";
  const attrs = cleanAttributes("img", { "data-src": "https://cdn.example/direct.jpg", src: cached, srcset: `${proxy} 1x, https://cdn.example/large.jpg 2x` }, base);
  assert.deepEqual(candidates(attrs), [cached, proxy, "https://cdn.example/direct.jpg", "https://cdn.example/large.jpg"]);
});

test("retained extraction choices are validated instead of trusting serialized metadata", () => {
  const cached = "https://miniflux.xiaochen.win/media/v1/store/encoded-origin/signature";
  const attrs = cleanAttributes("img", {
    "data-image-candidates": JSON.stringify(["javascript:alert(1)", "https://user:secret@cdn.example/p.jpg", "/valid.jpg", cached]),
    src: "/fallback.jpg",
  }, base);
  assert.deepEqual(candidates(attrs), [cached, "https://news.example/valid.jpg", "https://news.example/fallback.jpg"]);
});

test("DW inline format templates use the verified 90X mapping", () => {
  const html = '<img data-format="MASTER_LANDSCAPE" data-id="79414331" data-url="https://static.dw.com/image/79414331_${formatId}.jpg" alt="supercomputer">';
  const attrs = imageOperations(html, "https://www.dw.com/zh/article/a-79476680")[0].attrs;
  assert.deepEqual(candidates(attrs), [906, 905, 904, 903, 902, 901, 900].map((id) => `https://static.dw.com/image/79414331_${id}.jpg`));
  assert.deepEqual(candidates(imageOperations(html, base)[0].attrs), []);
  assert.deepEqual(candidates(imageOperations(html.replace('data-id="79414331"', 'data-id="42"'), "https://www.dw.com/zh/article")[0].attrs), []);
  assert.equal(cleanAttributes("img", { "data-format": "FREE_IMAGE", "data-url": "https://static.dw.com/image/42_${formatId}.jpg" }, "https://www.dw.com/zh/")["data-image-source"], "https://static.dw.com/image/42_7.jpg");
  assert.equal(cleanAttributes("img", { "data-format": "LEGACY_LANDSCAPE", "data-url": "https://static.dw.com/image/42_${formatId}.jpg" }, "https://www.dw.com/zh/")["data-image-source"], "https://static.dw.com/image/42_4.jpg");
});
