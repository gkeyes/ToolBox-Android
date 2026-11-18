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

function textOf(node) {
  return node.type === "text" ? node.data : (node.children || []).map(textOf).join("");
}

test("Chinese summary emphasis handles punctuation and the screenshot's spaced closing markers", () => {
  const { html, tags } = documentFor([
    "> **疗程完成度不等于因果证据。 **能完成 ≥5 个周期的患者。",
    ">",
    "> **PEACE-3 回答的是“在恩扎卢胺基础上加用 Ra-223 有何收益”，不是“Ra-223 应排在化疗之前还是之后”。 **对既往 ARPi 治疗后进展的患者。",
    "", "前文**“重点”**后文，**中文句号。**接着说明。",
  ].join("\n"));
  assert.equal(tags("blockquote").length, 1);
  assert.deepEqual(tags("strong").map(textOf), [
    "疗程完成度不等于因果证据。 ",
    "PEACE-3 回答的是“在恩扎卢胺基础上加用 Ra-223 有何收益”，不是“Ra-223 应排在化疗之前还是之后”。 ",
    "“重点”", "中文句号。",
  ]);
  assert.ok(!html.includes("**"));
  assert.match(html, /<\/strong>能完成/);
  assert.match(html, /<\/strong>接着说明/);
});

test("Chinese emphasis preserves code, escaped stars, standard nesting and paragraph boundaries", () => {
  const literal = "**中文句号。 **接着说明";
  const { tags, nodes } = documentFor([
    `\`${literal}\``, "", "```md", literal, "```", "",
    "\\*\\*中文句号。 \\*\\*接着说明", "",
    "**bold with *italic* inside** and __strong__ and *italic*", "",
    "**未完成的段落", "", "另一段。**保持分开",
  ].join("\n"));
  assert.deepEqual(tags("code").map(textOf), [literal, `${literal}\n`]);
  assert.deepEqual(tags("strong").map(textOf), ["bold with italic inside", "strong"]);
  assert.deepEqual(tags("em").map(textOf), ["italic", "italic"]);
  assert.ok(nodes.some((node) => node.type === "text" && node.data === literal));
  assert.ok(tags("p").some((node) => textOf(node) === "**未完成的段落"));
  assert.ok(tags("p").some((node) => textOf(node) === "另一段。**保持分开"));
});

test("streamed Chinese emphasis stays readable until the closing marker and following text arrive", () => {
  for (const source of ["**中文句号。 ", "**中文句号。 *", "**中文句号。 **"]) {
    const { tags } = documentFor(source);
    assert.equal(tags("strong").length, 0);
    assert.equal(textOf(tags("p")[0]), source);
  }
  const { tags } = documentFor("**中文句号。 **接着说明");
  assert.deepEqual(tags("strong").map(textOf), ["中文句号。 "]);
  assert.equal(textOf(tags("p")[0]), "中文句号。 接着说明");
});

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
