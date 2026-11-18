import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { parseDocument } from "htmlparser2";
import { renderSummaryMarkdown } from "../src/toolbox/summary-markdown.mjs";

function documentFor(source) {
  const html = renderToStaticMarkup(createElement("div", null, renderSummaryMarkdown(source, "https://news.example/articles/42")));
  const nodes = [];
  const visit = (node) => { nodes.push(node); for (const child of node.children || []) visit(child); };
  visit(parseDocument(html));
  return { html, tags: (name) => nodes.filter((node) => node.name === name), nodes };
}

test("Markdown links use the article base and reject active schemes after entity decoding", () => {
  const { tags } = documentFor([
    "[relative](../source?x=1&y=2)", "[HTTPS](https://safe.example/report#section)",
    "[script](javascript:alert(1))", "[entity](java&#x73;cript:alert(1))",
    "[colon](javascript&#58;alert(1))", "[data](data:text/html,unsafe)",
    "[file](file:///private/key)", "[credentials](https://user:pass@safe.example/)",
  ].join("\n\n"));
  assert.deepEqual(tags("a").map((node) => node.attribs.href), [
    "https://news.example/source?x=1&y=2", "https://safe.example/report#section",
  ]);
  assert.ok(tags("a").every((node) => node.attribs.rel === "noopener noreferrer"));
});

test("raw HTML stays literal and Markdown images become explicit links without active media", () => {
  const { html, tags, nodes } = documentFor([
    '<svg onload="alert(1)"><script>alert(1)</script></svg>',
    '<img src="https://tracker.example/pixel" onerror="alert(1)">',
    "", "![图示](https://images.example/diagram.svg)",
    "", "![不可用图片](javascript:alert(1))",
    "", "```html", '<iframe src="https://frame.example/"></iframe>', "```",
  ].join("\n"));
  for (const tag of ["svg", "script", "img", "iframe", "object", "embed"]) assert.equal(tags(tag).length, 0);
  assert.match(html, /&lt;svg/);
  assert.match(html, /&lt;iframe/);
  assert.deepEqual(tags("a").map((node) => node.attribs.href), ["https://images.example/diagram.svg"]);
  assert.ok(nodes.every((node) => Object.keys(node.attribs || {}).every((attribute) => !/^on|^src$/i.test(attribute))));
});

test("GFM task lists, ordered starts and table alignment keep their semantics", () => {
  const { tags } = documentFor([
    "7. 从第七项开始", "8. 下一项", "", "- [x] 已处理", "- [ ] 待处理", "",
    "| 左 | 右 |", "| :--- | ---: |", "| A | B |",
  ].join("\n"));
  assert.equal(tags("ol")[0].attribs.start, "7");
  assert.equal(tags("input").length, 2);
  assert.ok(tags("input").every((node) => node.attribs.type === "checkbox" && Object.hasOwn(node.attribs, "disabled")));
  assert.equal(Object.hasOwn(tags("input")[0].attribs, "checked"), true);
  assert.equal(Object.hasOwn(tags("input")[1].attribs, "checked"), false);
  assert.deepEqual(tags("td").map((node) => node.attribs.style), ["text-align:left", "text-align:right"]);
  for (const tag of ["table", "thead", "tbody", "tr"]) {
    assert.ok(tags(tag).every((node) => node.children.every((child) => child.type !== "text" || child.data.trim())), "table containers have no formatting-only text nodes");
  }
});
