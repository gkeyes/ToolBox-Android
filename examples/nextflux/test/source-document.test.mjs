import assert from "node:assert/strict";
import test from "node:test";
import { fetchWebDocument } from "../src/reading/extractors/source.mjs";

const requestedUrl = "https://news.example/old/post";
const finalUrl = "https://news.example/new/article/";
const page = head => `<html><head>${head}</head><body><article>Article<img src="image.png"></article></body></html>`;
function bridge(html = page(""), headers = {}) {
  const requests = [];
  return {
    requests,
    network: () => ({ async request(request) { requests.push(request); return { status: 200, headers, body: html }; } }),
  };
}

test("the host final URL is the effective page URL with unchanged request credentials", async () => {
  const fixture = bridge(page(""), { "X-ToolBox-Final-URL": finalUrl });
  const result = await fetchWebDocument(requestedUrl, fixture.network);
  assert.equal(result.url, finalUrl);
  assert.equal(result.baseUrl, finalUrl);
  assert.equal(fixture.requests[0].url, requestedUrl);
  assert.deepEqual(fixture.requests[0].headers, { Accept: "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1" });
});

test("the request URL is retained when the host supplies no final URL", async () => {
  const result = await fetchWebDocument(requestedUrl, bridge().network);
  assert.equal(result.url, requestedUrl);
  assert.equal(result.baseUrl, requestedUrl);
});

test("the canonical host final URL wins over a differently cased origin response header", async () => {
  const fixture = bridge(page(""), { "X-ToolBox-Final-URL": "https://wrong.example/", "x-toolbox-final-url": finalUrl });
  const result = await fetchWebDocument(requestedUrl, fixture.network);
  assert.equal(result.url, finalUrl);
  assert.equal(result.baseUrl, finalUrl);
});

test("unsafe or nonabsolute final URLs are rejected", async () => {
  for (const value of ["javascript:alert(1)", "data:text/html,hello", "ftp://news.example/post", "https://user@news.example/post", "/new/article/"]) {
    await assert.rejects(fetchWebDocument(requestedUrl, bridge(page(""), { "x-toolbox-final-url": value }).network), { code: "INVALID_URL" });
  }
});

test("relative base href resolves against the final page URL for all HTML quoting forms", async () => {
  for (const tag of ['<base href="../assets/">', "<BASE HREF='../assets/'>", "<base href=../assets/>"]) {
    const fixture = bridge(page(tag), { "x-toolbox-final-url": finalUrl });
    const result = await fetchWebDocument(requestedUrl, fixture.network);
    assert.equal(result.url, finalUrl);
    assert.equal(result.baseUrl, "https://news.example/new/assets/");
    assert.equal(new URL("image.png", result.baseUrl).href, "https://news.example/new/assets/image.png");
  }
});

test("base href entities and cross-origin URLs retain exact resource addressing", async () => {
  const html = page('<base href="//cdn.example/assets/?a=1&amp;b=2">');
  const result = await fetchWebDocument(requestedUrl, bridge(html, { "x-toolbox-final-url": finalUrl }).network);
  assert.equal(result.baseUrl, "https://cdn.example/assets/?a=1&b=2");
  assert.equal(result.html, html);
});

test("comments, script text and inert template bases do not change resource addressing", async () => {
  const html = page('<!-- <base href="https://wrong.example/"> --><script>const fake = \'<base href="https://wrong.example/">\';</script><template><base href="https://wrong.example/"></template><base href="/real/">');
  const result = await fetchWebDocument(requestedUrl, bridge(html, { "x-toolbox-final-url": finalUrl }).network);
  assert.equal(result.baseUrl, "https://news.example/real/");
});

test("the first base with href wins and unsafe bases fall back to the page URL", async () => {
  for (const href of ["javascript:alert(1)", "data:text/plain,base", "https://user@cdn.example/assets/"]) {
    const result = await fetchWebDocument(requestedUrl, bridge(page(`<base target="_blank"><base href="${href}"><base href="https://later.example/">`), { "x-toolbox-final-url": finalUrl }).network);
    assert.equal(result.baseUrl, finalUrl);
  }
  const result = await fetchWebDocument(requestedUrl, bridge(page('<base href=""><base href="https://later.example/">'), { "x-toolbox-final-url": finalUrl }).network);
  assert.equal(result.baseUrl, finalUrl);
});

test("base64 HTML is decoded once and uses the same final and base URL semantics", async () => {
  const html = page('<base href="../media/">');
  const network = () => ({ async request() { return { status: 200, headers: { "x-toolbox-final-url": finalUrl }, bodyEncoding: "base64", body: Buffer.from(html).toString("base64") }; } });
  const result = await fetchWebDocument(requestedUrl, network);
  assert.equal(result.html, html);
  assert.equal(result.url, finalUrl);
  assert.equal(result.baseUrl, "https://news.example/new/media/");
});
