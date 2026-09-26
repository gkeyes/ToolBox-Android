import assert from "node:assert/strict";
import test from "node:test";
import { createImageDimensions, imageDimension, imageSize } from "../src/toolbox/imageDimensions.js";
import { cleanAttributes } from "../src/toolbox/content.js";

test("dimensions accept only bounded positive integers, never CSS or partial numbers", () => {
  assert.deepEqual(imageSize("640", "480"), { width: 640, height: 480 });
  for (const value of [0, -1, 100001, Infinity, NaN, 1.5, "10px", "100%", "1e3", "10;position:fixed", true, null, {}, "", " 2"]) {
    assert.equal(imageDimension(value), null, String(value));
    assert.equal(imageSize(value, 10), null);
  }
});

test("sanitizer preserves dimensions without admitting styles, handlers or active src", () => {
  assert.deepEqual(cleanAttributes("img", {
    src: "https://images.example/a.png?a=1&b=2", width: "640", height: "480",
    style: "position:fixed", onload: "alert(1)", srcset: "https://elsewhere.example/evil.png 2x",
  }), { "data-image-source": "https://images.example/a.png?a=1&b=2", width: "640", height: "480" });
  assert.deepEqual(cleanAttributes("img", { width: "20px", height: "-1", src: "javascript:alert(1)" }), {});
  assert.deepEqual(cleanAttributes("div", { width: "10", height: "20" }), {});
});

test("sanitizer promotes lazy and srcset image sources without activating unsafe attributes", () => {
  const base = "https://news.example/articles/1";
  const transparent = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
  assert.deepEqual(cleanAttributes("img", {
    src: transparent, "data-src": "/media/full.jpg", onerror: "alert(1)",
  }, base), { "data-image-source": "https://news.example/media/full.jpg" });
  assert.deepEqual(cleanAttributes("img", {
    srcset: "/media/small.jpg 320w, /media/large.jpg 1280w",
  }, base), { "data-image-source": "https://news.example/media/large.jpg" });
  assert.deepEqual(cleanAttributes("img", {
    src: "https://news.example/fallback.jpg", "data-src": "javascript:alert(1)",
  }, base), { "data-image-source": "https://news.example/fallback.jpg" });
});

test("measurements follow exact source identity, not query reordering or decoding", () => {
  const cache = createImageDimensions();
  const source = "https://images.example/media/v1/key?sig=a%2Fb&v=2";
  cache.remember(source, 640, 480);
  assert.deepEqual(cache.get(source), { width: 640, height: 480 });
  assert.equal(cache.get(source.replace("a%2Fb", "a/b")), null);
  assert.equal(cache.get("https://images.example/media/v1/key?v=2&sig=a%2Fb"), null);
  assert.equal(cache.get("https://other.example/media/v1/key?sig=a%2Fb&v=2"), null);
});

test("capacity evicts oldest measurement and refreshing a measurement keeps it", () => {
  const cache = createImageDimensions(2);
  const [a, b, c] = ["a", "b", "c"].map((id) => `https://images.example/${id}`);
  cache.remember(a, 10, 20); cache.remember(b, 20, 30);
  cache.remember(a, 40, 50); cache.remember(c, 60, 70);
  assert.equal(cache.get(b), null);
  assert.deepEqual(cache.get(a), { width: 40, height: 50 });
  assert.deepEqual(cache.get(c), { width: 60, height: 70 });
});

test("logout invalidates earlier measurements and late image callbacks", () => {
  const cache = createImageDimensions(), epoch = cache.epoch;
  const source = "https://images.example/a";
  cache.remember(source, 10, 20, epoch);
  cache.clear();
  assert.equal(cache.get(source), null);
  assert.equal(cache.remember(source, 30, 40, epoch), null);
  assert.equal(cache.get(source), null);
  cache.remember(source, 50, 60, cache.epoch);
  assert.deepEqual(cache.get(source), { width: 50, height: 60 });
});

test("no large embedded URLs, temporary Blob identities, or invalid measurements retained", () => {
  const cache = createImageDimensions();
  for (const source of ["blob:https://tool.example/id", "data:image/png;base64,AAAA", "https://images.example/" + "a".repeat(8192)]) {
    assert.equal(cache.remember(source, 10, 20), null);
    assert.equal(cache.get(source), null);
  }
  const source = "https://images.example/a";
  cache.remember(source, 10, 20);
  cache.remember(source, 0, 20);
  assert.deepEqual(cache.get(source), { width: 10, height: 20 });
  assert.throws(() => createImageDimensions(0), RangeError);
});
