import test from "node:test";
import assert from "node:assert/strict";
import { createReadingParser } from "../src/lib/reading-parser.js";

function parse(html, baseUrl = "https://example.org/article") {
  const parser = createReadingParser(html, baseUrl);
  const operations = [];
  let batch;
  do { batch = parser.next(); operations.push(...batch.operations); } while (!batch.done);
  return operations;
}
const textOf = (operations) => operations.filter((item) => item.type === "text").map((item) => item.text).join("");

test("safe SAX nodes drop active/foreign content and ignore all untrusted metadata and executable attributes", () => {
  const operations = parse('<p onclick="steal()" style="color:red">safe<script>bad</script><style>bad</style><svg><a>bad</a></svg><math>bad</math><template><img src="https://evil.org">bad</template><form>bad<input></form><span data-media-kind="video" data-media-url="https://evil.org" data-reading-local-anchor="evil">visible</span></p>');
  assert.equal(textOf(operations), "safevisible");
  const elements = operations.filter((item) => item.type === "element");
  // In HTML mode <form> implicitly closes <p>. The unmatched final </p>
  // creates an empty paragraph; dropping the form must retain that safe repair.
  assert.deepEqual(elements.map((item) => item.tag), ["p", "span", "p"]);
  assert.ok(elements.every((item) => item.parent === 0));
  assert.ok(!operations.some((item) => item.parent === elements.at(-1).id));
  assert.ok(operations.every((item) => !item.attrs || Object.keys(item.attrs).length === 0));
});

test("entity-decoded URLs and duplicated attributes cannot bypass URL, media and image rules", () => {
  const operations = parse('<a href="jav&#x61;script:alert(1)" href="https://good.org">bad link</a><img src="/proxy/a/b" srcset="https://evil.org" onerror="boom" alt="safe"><video autoplay><source src="/proxy/media/file"></video><iframe src="https://media.example/video"><img src="https://evil.org">fallback</iframe>');
  assert.deepEqual(operations.find((item) => item.tag === "a").attrs, {});
  assert.deepEqual(operations.find((item) => item.type === "image").attrs, { "data-image-source": "https://miniflux.xiaochen.win/proxy/a/b", alt: "safe" });
  assert.deepEqual(operations.filter((item) => item.type === "media").map(({ kind, url }) => ({ kind, url })), [
    { kind: "video", url: "https://miniflux.xiaochen.win/proxy/media/file" },
    { kind: "iframe", url: "https://media.example/video" },
  ]);
  assert.equal(operations.filter((item) => item.type === "image").length, 1);
  assert.equal(textOf(operations), "bad link");
});

test("malformed paragraph/list/table closures remain safe and retain article text without HTML reparsing", () => {
  const operations = parse('<p>first<p>second<ul><li>one<li>two</ul><table><tbody><tr><td>A<td>B</tr><tr><td>C</table><p>last &lt;img onerror=evil&gt;');
  const nodes = new Map([[0, { tag: "root" }]]);
  for (const item of operations) if (item.type === "element") { assert.ok(nodes.has(item.parent)); nodes.set(item.id, item); }
  const cells = operations.filter((item) => item.tag === "td");
  assert.equal(cells.length, 3);
  assert.ok(cells.every((item) => nodes.get(item.parent).tag === "tr"));
  assert.equal(textOf(operations), "firstsecondonetwoABClast <img onerror=evil>");
  assert.ok(!operations.some((item) => item.type === "image"));
});

test("mutation-XSS payloads never create active nodes and literal markup remains text", () => {
  for (const html of [
    '<math><mtext><table><mglyph><style><!--</style><img title="--><img src=x onerror=evil>">',
    '<svg><style><img src=x onerror=evil></style></svg><p>safe</p>',
    '<noscript><p title="</noscript><img src=x onerror=evil>">text</p>',
    '<table><svg><desc><template><img src=x onerror=evil></template></desc></svg></table>',
    '<textarea>&lt;/textarea&gt;&lt;script&gt;evil&lt;/script&gt;</textarea><p>safe</p>',
  ]) {
    for (const item of parse(html)) {
      if (item.type === "element") assert.ok(!["script", "style", "svg", "math", "template", "iframe"].includes(item.tag));
      for (const key of Object.keys(item.attrs || {})) assert.ok(!/^on|style|src$|srcset|data-media/.test(key), key);
    }
  }
});

test("code keeps complete whitespace and blank lines, anchors use inert metadata, and image links retain their URL", () => {
  const code = "\n  const value = '<script>';\n\n\n  end();\n";
  const operations = parse(`<h2 id="chapter">Title</h2><a href="#chapter">Jump</a><a href="/gallery"><img src="/proxy/a/b"></a><pre><code class="language-js evil">${code.replaceAll("<", "&lt;")}</code></pre>`);
  assert.equal(operations.find((item) => item.tag === "h2").attrs["data-article-anchor"], "chapter");
  assert.equal(operations.find((item) => item.tag === "a").attrs.href, "https://example.org/article#chapter");
  assert.equal(operations.find((item) => item.type === "imageLink").href, "https://example.org/gallery");
  assert.equal(operations.find((item) => item.type === "code").code, code);
  assert.equal(operations.find((item) => item.type === "code").language, "js");
});
