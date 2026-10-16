import test from "node:test";
import assert from "node:assert/strict";
import { imageIsInline } from "../src/reading/inlineImage.mjs";

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
  assert.equal(imageIsInline(badge), true);
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
