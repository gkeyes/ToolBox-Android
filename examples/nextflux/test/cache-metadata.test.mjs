import test from "node:test";
import assert from "node:assert/strict";
import { deriveArticleMetadata } from "../src/toolbox/cache-metadata.js";

const articleUrl = "https://article.example/posts/current.html";
const proxyUrl = "https://miniflux.xiaochen.win/proxy/signature/image";

test("metadata decodes entities once, strips markup and normalizes text whitespace", () => {
  assert.deepEqual(deriveArticleMetadata({
    title: "  <b>鱼 &amp; Chips</b>&nbsp; &quot;标题&quot; &#x1F600; &#169; ",
    content: "<p>First&nbsp; line</p>\n<p>Second &lt;literal&gt; &amp;lt;escaped&amp;gt;</p>",
  }), {
    titleText: '鱼 & Chips "标题" 😀 ©',
    previewText: "First line Second <literal> &lt;escaped&gt;",
    coverUrl: null,
  });
  assert.equal(deriveArticleMetadata({ content: "a<br>b<div>c</div>d" }).previewText, "abcd");
});

test("metadata accepts plain text and absent article fields", () => {
  assert.deepEqual(deriveArticleMetadata({ title: "  Plain\ttext  ", content: "中文\n正文 & more" }), {
    titleText: "Plain text", previewText: "中文 正文 & more", coverUrl: null,
  });
  for (const article of [undefined, null, {}, { title: null, content: "" }]) {
    assert.deepEqual(deriveArticleMetadata(article), { titleText: "", previewText: "", coverUrl: null });
  }
});

test("metadata omits the same script, style and embedded subtrees as cleanTitle", () => {
  const hidden = '<script>globalThis.metadataExecuted = true;</script><style>body { color: red; }</style><iframe>iframe</iframe><object>object</object><embed src="ignored"><svg><text>svg</text></svg><math><mi>math</mi></math><template><p>template</p></template>';
  assert.deepEqual(deriveArticleMetadata({ title: `Before ${hidden} After`, content: `<p>Before ${hidden} After</p>` }), {
    titleText: "Before After", previewText: "Before After", coverUrl: null,
  });
  assert.equal(deriveArticleMetadata({ content: "a<object><object>hidden</object>hidden</object>b" }).previewText, "ab");
});

test("metadata resolves relative first images and the fixed Miniflux proxy origin", () => {
  for (const [source, expected] of [
    ["../images/cover.png", "https://article.example/images/cover.png"],
    ["/images/cover.png", "https://article.example/images/cover.png"],
    ["//cdn.example/cover.png", "https://cdn.example/cover.png"],
    ["/proxy/signature/image", proxyUrl],
    ["https://cdn.example/cover.png?a=1&amp;b=2", "https://cdn.example/cover.png?a=1&b=2"],
    ["data:image/png;base64,aGVsbG8=", "data:image/png;base64,aGVsbG8="],
  ]) {
    assert.equal(deriveArticleMetadata({ url: articleUrl, content: `<img src="${source}"><img src="/ignored.png">` }).coverUrl, expected);
  }
  assert.equal(deriveArticleMetadata({ url: articleUrl, content: '<img alt="missing"><img src="/later.png">' }).coverUrl, null);
  assert.equal(deriveArticleMetadata({ url: articleUrl, content: '<template><img src="/hidden.png"></template><iframe><img src="/hidden.png"></iframe><img src="/cover.png">' }).coverUrl, "https://article.example/cover.png");
});

test("metadata keeps first image enclosure precedence and the empty body rule", () => {
  const enclosures = [
    { mime_type: "audio/mpeg", url: "/audio.mp3" },
    { mime_type: "image/jpeg", url: "/proxy/signature/image" },
    { mime_type: "image/png", url: "https://cdn.example/later.png" },
  ];
  assert.equal(deriveArticleMetadata({ content: '<img src="/html.png">', url: articleUrl, enclosures }).coverUrl, proxyUrl);
  assert.equal(deriveArticleMetadata({ content: "", url: articleUrl, enclosures }).coverUrl, null);
  assert.equal(deriveArticleMetadata({
    content: '<img src="/html.png">', url: articleUrl,
    enclosures: [{ mime_type: "image/jpeg" }, enclosures[2]],
  }).coverUrl, "https://article.example/html.png");
});

test("metadata rejects executable URLs and credentials without replacing the selected cover", () => {
  for (const source of [
    "javascript:alert(1)", "java&#10;script:alert(1)", "data:text/html,x", "data:image/svg+xml;base64,PHN2Zz4=",
    "file:///private/image.png", "blob:https://article.example/image", "https://user:password@cdn.example/image.png",
    "https://cdn.example/white space.png", "http://[invalid",
  ]) {
    assert.equal(deriveArticleMetadata({ url: articleUrl, content: `<img src="${source}"><img src="/later.png">` }).coverUrl, null, source);
  }
  assert.equal(deriveArticleMetadata({
    content: '<img src="/safe.png">', url: articleUrl,
    enclosures: [{ mime_type: "image/png", url: "javascript:alert(1)" }],
  }).coverUrl, null);
});

test("metadata stays inert and leaves the original body and enclosures unchanged", () => {
  const originalDescriptors = new Map();
  const attempted = [];
  for (const name of ["document", "DOMParser", "Image", "fetch", "XMLHttpRequest", "metadataExecuted"]) {
    originalDescriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
    Object.defineProperty(globalThis, name, {
      configurable: true,
      get() { attempted.push(name); throw new Error(`Metadata must not access ${name}`); },
      set() { attempted.push(name); throw new Error(`Metadata must not modify ${name}`); },
    });
  }
  try {
    const content = '<script>globalThis.metadataExecuted = true; fetch("https://evil.example")</script><style>@import "https://evil.example/style";</style><img src="/proxy/signature/image" onerror="globalThis.metadataExecuted = true">Visible';
    const enclosures = Object.freeze([]);
    const article = Object.freeze({ title: "<b>Safe</b>", content, enclosures, url: articleUrl });
    assert.deepEqual(deriveArticleMetadata(article), { titleText: "Safe", previewText: "Visible", coverUrl: proxyUrl });
    assert.equal(article.content, content);
    assert.equal(article.enclosures, enclosures);
    assert.deepEqual(attempted, []);
  } finally {
    for (const [name, descriptor] of originalDescriptors) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
  }
});

test("metadata limits normalized previews to the existing 300 UTF-16 units and preserves Unicode", () => {
  const text = "中文 😀 e\u0301 ".repeat(80).trim();
  assert.equal(deriveArticleMetadata({ title: "标题 👩‍💻 e\u0301", content: ` \n<p>${text}</p> \t` }).titleText, "标题 👩‍💻 e\u0301");
  assert.equal(deriveArticleMetadata({ content: ` \n<p>${text}</p> \t` }).previewText, text.slice(0, 300));
  assert.equal(deriveArticleMetadata({ content: `${"a".repeat(299)}&nbsp; <b>b</b>` }).previewText, `${"a".repeat(299)} `);
  assert.equal(deriveArticleMetadata({ content: `${"a".repeat(299)}   ` }).previewText, "a".repeat(299));
  assert.equal(deriveArticleMetadata({ content: `<p>${"a".repeat(5000)}</p><img src="/proxy/signature/image">` }).coverUrl, proxyUrl);
});
