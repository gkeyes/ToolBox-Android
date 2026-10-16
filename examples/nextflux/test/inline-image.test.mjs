import test from "node:test";
import assert from "node:assert/strict";
import { imageIsInline, imageLineOpen } from "../src/reading/inlineImage.mjs";

const text = (data) => ({ nodeType: 3, textContent: data });
const element = (tagName, children = [], attrs = {}) => {
  const node = {
    nodeType: 1, tagName, childNodes: children, parentElement: null,
    hasAttribute: (name) => name in attrs,
    get textContent() { return children.map((child) => child.textContent).join(""); },
  };
  for (const child of children) child.parentElement = node;
  return node;
};
const image = () => element("DIV", [], { "data-font-block": "" });

test("an image in running text is inline", () => {
  const icon = image();
  element("DIV", [text("Label "), icon, text(" more")]);
  assert.equal(imageIsInline(icon), true);
  const badge = image();
  element("DIV", [element("STRONG", [text("Label")]), badge]);
  assert.equal(imageIsInline(badge, { "data-image-source": "https://example.com/img/badge-new.png" }), true);
  const small = image();
  element("DIV", [element("SPAN", [text("来源")]), small]);
  assert.equal(imageIsInline(small, { width: "20", height: "20" }), true);
});

// recheck-107 P2-2: photos next to text on their own line keep the reserve.
test("a photo on its own line next to text is not inline", () => {
  const afterBreak = image();
  element("P", [text("前面的文字"), element("BR"), afterBreak]);
  assert.equal(imageIsInline(afterBreak, { "data-image-source": "https://example.com/photo.jpg" }), false);
  const beforeCaption = image();
  element("P", [beforeCaption, element("BR"), text("图片说明")]);
  assert.equal(imageIsInline(beforeCaption, {}), false);
  const source = image();
  element("P", [element("SPAN", [text("来源：某某")]), source]);
  assert.equal(imageIsInline(source, { "data-image-source": "https://example.com/2026/10/photo.jpg" }), false);
  const wide = image();
  element("P", [text("text "), wide, text(" more")]);
  assert.equal(imageIsInline(wide, { width: "1200", height: "800" }), false);
});

test("htmlparser2 nodes are classified the same way", () => {
  const img = { type: "tag", name: "img", attribs: {}, children: [] };
  const parent = { type: "tag", name: "p", children: [{ type: "text", data: "前面的文字" }, { type: "tag", name: "br", attribs: {}, children: [] }, img] };
  img.parent = parent;
  assert.equal(imageIsInline(img, {}), false);
  const icon = { type: "tag", name: "img", attribs: {}, children: [] };
  icon.parent = { type: "tag", name: "p", children: [{ type: "text", data: "Label " }, icon, { type: "text", data: " more" }] };
  assert.equal(imageIsInline(icon, {}), true);
});

test("a figure, a lone image or a following paragraph is not running text", () => {
  const figure = image();
  element("FIGURE", [figure, element("FIGCAPTION", [text("caption")])]);
  assert.equal(imageIsInline(figure), false);
  const lone = image();
  element("DIV", [lone, element("P", [text("next paragraph")]), image()]);
  assert.equal(imageIsInline(lone), false);
  const blank = image();
  element("DIV", [text("  "), blank]);
  assert.equal(imageIsInline(blank), false);
  assert.equal(imageIsInline(image()), false);
});

test("text beyond an inline wrapper still counts, an SVG alone is no icon", () => {
  const wrapped = image();
  element("P", [element("STRONG", [text("before "), wrapped]), text(" after")]);
  assert.equal(imageIsInline(wrapped, { "data-image-source": "https://example.com/123.png" }), true);
  const diagram = image();
  element("P", [element("SPAN", [text("来源")]), diagram]);
  assert.equal(imageIsInline(diagram, { "data-image-source": "https://example.com/diagram.svg" }), false);
});

test("a line with nothing after the image yet stays open", () => {
  const last = image();
  const paragraph = element("P", [text("text "), last]);
  assert.equal(imageLineOpen(last), true);
  assert.equal(imageIsInline(last, {}), false);
  const more = text(" more");
  more.parentElement = paragraph;
  paragraph.childNodes.push(more);
  assert.equal(imageLineOpen(last), false);
  assert.equal(imageIsInline(last, {}), true);
  const tail = image();
  const article = element("DIV", [element("P", [text("text "), tail])]);
  assert.equal(imageLineOpen(tail), true);
  const next = element("P", [text("next paragraph")]);
  next.parentElement = article;
  article.childNodes.push(next);
  assert.equal(imageLineOpen(tail), false);
  assert.equal(imageIsInline(tail, {}), false);
  const ended = image();
  element("P", [ended, element("BR"), text("caption")]);
  assert.equal(imageLineOpen(ended), false);
});
