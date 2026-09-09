import test, { afterEach, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { approvedImageSource, acquireImage, clearMediaCache, loadProxyMedia } from "../src/toolbox/media.js";
import { safeContentUrl, cleanAttributes } from "../src/toolbox/content.js";

const origin = "https://miniflux.xiaochen.win";
const proxy = `${origin}/proxy/signature/aW1hZ2U=`;

beforeEach(() => clearMediaCache());
afterEach(() => clearMediaCache());

test("media accepts only exact-origin signed proxy URLs, bounded raster data and trusted local assets", () => {
  assert.equal(approvedImageSource(proxy)?.url, proxy);
  assert.equal(approvedImageSource("/proxy/signature/aW1hZ2U=")?.url, proxy);
  assert.equal(approvedImageSource("data:image/png;base64,aGVsbG8=")?.kind, "local");
  assert.equal(approvedImageSource("/assets/logo.svg", true)?.kind, "local");
  for (const value of [
    "https://evil.example/proxy/a/b", `${origin}.evil.example/proxy/a/b`,
    "http://miniflux.xiaochen.win/proxy/a/b", "https://user:secret@miniflux.xiaochen.win/proxy/a/b",
    `${origin}/v1/users`, `${proxy}?url=https://evil.example`, `${proxy}#bad`,
    `${origin}/proxy/../../v1/me`, `${origin}/proxy/a%2fb/c`,
    "//evil.example/a.png", "javascript:alert(1)", "data:image/svg+xml;base64,PHN2Zz4=",
    "data:text/html;base64,PGltZz4=", "blob:https://evil.example/123", "/assets/../../secret.svg",
  ]) assert.equal(approvedImageSource(value), null, value);
});

test("article attributes remove executable styles, handlers, srcset and non-web links", () => {
  const image = cleanAttributes("img", { src: "/proxy/a/b", srcset: "https://evil.example/a.png 2x", onerror: "alert(1)", style: "background:url(https://evil.example)", id: "ToolBox", alt: "safe", "data-media-url": "https://evil.example" }, "https://article.example/post");
  assert.deepEqual(image, { "data-image-source": `${origin}/proxy/a/b`, alt: "safe" });
  assert.deepEqual(cleanAttributes("a", { href: "javascript:alert(1)", onclick: "steal()", download: "file" }), {});
  assert.deepEqual(cleanAttributes("code", { class: "language-js fixed inset-0", style: "display:none" }), { class: "language-js" });
  assert.equal(safeContentUrl("/post", "https://article.example/page"), "https://article.example/post");
  for (const value of ["javascript:alert(1)", "data:text/html,x", "file:///a", "https://user:password@example.org", "java\nscript:alert(1)"]) assert.equal(safeContentUrl(value), null);
});

test("image proxy uses native network with no credentials and reuses a released Blob", async () => {
  const calls = [];
  const revoked = [];
  const originalCreate = URL.createObjectURL;
  const originalRevoke = URL.revokeObjectURL;
  URL.createObjectURL = () => "blob:test-image";
  URL.revokeObjectURL = (url) => revoked.push(url);
  globalThis.window = { ToolBox: { network: { request: async (payload) => { calls.push(payload); return { status: 200, headers: { "Content-Type": "image/png" }, bodyEncoding: "base64", body: "aGVsbG8=" }; } } } };
  try {
    const a = acquireImage(proxy);
    const b = acquireImage(proxy);
    assert.equal(await a.promise, "blob:test-image");
    assert.equal(await b.promise, "blob:test-image");
    assert.equal(calls.length, 1);
    assert.deepEqual(Object.keys(calls[0].headers), ["Accept"]);
    assert.equal(calls[0].url, proxy);
    assert.equal(calls[0].maxResponseBytes, 2 * 1024 * 1024);
    a.release(); assert.equal(revoked.length, 0);
    b.release(); b.release(); assert.deepEqual(revoked, []);
    const reused = acquireImage(proxy);
    assert.equal(reused.url, "blob:test-image");
    assert.equal(await reused.promise, "blob:test-image");
    assert.equal(calls.length, 1);
    reused.release();
    clearMediaCache();
    assert.deepEqual(revoked, ["blob:test-image"]);
    assert.equal(approvedImageSource("blob:test-image"), null);
    assert.throws(() => acquireImage("https://evil.example/image.png"), /代理/);
    assert.equal(calls.length, 1);
  } finally { clearMediaCache(); URL.createObjectURL = originalCreate; URL.revokeObjectURL = originalRevoke; delete globalThis.window; }
});

test("media rejects unexpected MIME and external URLs and releases a loaded audio Blob", async () => {
  const calls = [];
  globalThis.window = { ToolBox: { network: { request: async (payload) => { calls.push(payload); return { status: 200, headers: { "content-type": "audio/mpeg" }, bodyEncoding: "base64", body: "aGVsbG8=" }; } } } };
  try {
    await assert.rejects(loadProxyMedia("https://external.example/song.mp3", "audio"), /代理/);
    assert.equal(calls.length, 0);
    const audio = await loadProxyMedia(proxy, "audio");
    assert.match(audio.url, /^blob:/);
    assert.equal(calls[0].maxResponseBytes, 4 * 1024 * 1024);
    assert.deepEqual(calls[0].headers, { Accept: "audio/*" });
    audio.release(); audio.release();
    const image = acquireImage(proxy);
    await assert.rejects(image.promise, /图片格式/);
    image.release();
  } finally { delete globalThis.window; }
});

test("in-flight image queue stays bounded and cancels released queued work", async () => {
  const pending = [];
  globalThis.window = { ToolBox: { network: { request: () => new Promise((resolve) => pending.push(resolve)) } } };
  const handles = [];
  try {
    for (let i = 0; i < 24; i += 1) {
      const handle = acquireImage(`${origin}/proxy/bounded/image${i}`);
      handle.promise.catch(() => {});
      handles.push(handle);
    }
    assert.equal(pending.length, 3);
    assert.throws(() => acquireImage(`${origin}/proxy/bounded/overflow`), /图片较多/);
    handles.forEach((handle) => handle.release());
    pending.forEach((resolve) => resolve({ status: 500, headers: {}, body: "", bodyEncoding: "text" }));
    await Promise.allSettled(handles.map((handle) => handle.promise));
    assert.equal(pending.length, 3);
  } finally { delete globalThis.window; }
});

test("syntax highlighting uses the JavaScript engine and escapes code markup", async () => {
  const [{ createHighlighterCore }, { createJavaScriptRegexEngine }, { default: javascript }, { default: theme }] = await Promise.all([
    import("shiki/core"), import("shiki/engine/javascript"), import("shiki/langs/javascript.mjs"), import("shiki/themes/catppuccin-latte.mjs"),
  ]);
  const highlighter = await createHighlighterCore({ langs: [javascript], themes: [theme], engine: createJavaScriptRegexEngine({ forgiving: true }) });
  try {
    const html = highlighter.codeToHtml('const value = "<script>";', { lang: "javascript", theme: "catppuccin-latte" });
    assert.match(html, /shiki/);
    assert.ok(!html.includes("<script>"));
    assert.match(html, /&#x3C;script>/);
  } finally { highlighter.dispose(); }
});

test("failed shared image can retry without old references removing the recovered cache entry", async () => {
  let denied = true;
  let requests = 0;
  globalThis.window = { ToolBox: { network: { request: async () => {
    requests += 1;
    if (denied) throw { code: "PERMISSION_DENIED" };
    return { status: 200, headers: { "content-type": "image/png" }, bodyEncoding: "base64", body: "aGVsbG8=" };
  } } } };
  const first = acquireImage(proxy);
  const sharedFailure = acquireImage(proxy);
  try {
    await assert.rejects(first.promise, /网络访问/);
    await assert.rejects(sharedFailure.promise, /网络访问/);
    denied = false;
    const retried = acquireImage(proxy);
    const blob = await retried.promise;
    first.release(); sharedFailure.release();
    const sharedSuccess = acquireImage(proxy);
    assert.equal(await sharedSuccess.promise, blob);
    assert.equal(requests, 2);
    retried.release();
    assert.equal(approvedImageSource(blob)?.kind, "local");
    sharedSuccess.release();
    assert.equal(approvedImageSource(blob)?.kind, "local");
    clearMediaCache();
    assert.equal(approvedImageSource(blob), null);
  } finally { first.release(); sharedFailure.release(); delete globalThis.window; }
});

test("account teardown discards a late image or media reply before Blob creation", async () => {
  const pending = [];
  const originalCreate = URL.createObjectURL;
  let created = 0;
  URL.createObjectURL = () => { created += 1; return `blob:late-${created}`; };
  globalThis.window = { ToolBox: { network: { request: () => new Promise((resolve) => pending.push(resolve)) } } };
  try {
    const image = acquireImage(proxy);
    const imageRejected = assert.rejects(image.promise, { code: "CANCELLED" });
    const audio = loadProxyMedia(proxy, "audio");
    const audioRejected = assert.rejects(audio, { code: "CANCELLED" });
    clearMediaCache();
    pending[0]({ status: 200, headers: { "content-type": "image/png" }, bodyEncoding: "base64", body: "aGVsbG8=" });
    pending[1]({ status: 200, headers: { "content-type": "audio/mpeg" }, bodyEncoding: "base64", body: "aGVsbG8=" });
    await Promise.all([imageRejected, audioRejected]);
    assert.equal(created, 0);
    image.release();
  } finally { clearMediaCache(); URL.createObjectURL = originalCreate; delete globalThis.window; }
});
