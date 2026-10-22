import test from "node:test";
import assert from "node:assert/strict";
import { imageIsInline, imageLineOpen, smallImage } from "../src/reading/inlineImage.mjs";

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
  const paragraph = element("P", [text("前面一段话。"), last]);
  assert.equal(imageLineOpen(last), true);
  assert.equal(imageIsInline(last, {}), false);
  const more = text(" more");
  more.parentElement = paragraph;
  paragraph.childNodes.push(more);
  assert.equal(imageLineOpen(last), false);
  assert.equal(imageIsInline(last, {}), true);
  const tail = image();
  const article = element("DIV", [element("P", [text("前面一段话。"), tail])]);
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

test("a nested break ends the line, the root bounds the article, an icon class survives cleaning", async () => {
  const photo = image();
  element("P", [text("如下图："), photo, element("SPAN", [element("BR"), text("next line")])]);
  assert.equal(imageIsInline(photo, {}), false);
  const tail = image();
  const root = element("DIV", [element("P", [text("text "), tail])]);
  root.hasAttribute = (name) => name === "data-font-reading-root";
  element("SECTION", [root, element("P", [text("正在准备正文…")])]);
  assert.equal(imageLineOpen(tail), true);
  const { cleanAttributes } = await import("../src/toolbox/content.js");
  const attrs = cleanAttributes("img", { class: "wp-smiley emoji", src: "https://example.com/123" });
  assert.equal(attrs["data-image-icon"], "true");
  assert.equal(cleanAttributes("img", { class: "wide photo" })["data-image-icon"], undefined);
  const icon = image();
  element("P", [element("STRONG", [text("标签")]), icon]);
  assert.equal(imageIsInline(icon, attrs), true);
});

test("emoji, stickers and badges stay inline with text on one side (recheck-127)", () => {
  const cases = [
    [(img) => element("P", [text("今天很开心"), img]), { "data-image-source": "https://twemoji.example/v/72x72/1f600.png", alt: "😀" }],
    [(img) => element("P", [img, text("今天很开心")]), { "data-image-source": "https://cdn.example/1f600.png" }],
    [(img) => element("P", [text("哈哈"), img]), { "data-image-source": "https://img.example/expression/xiaoku.png", alt: "[笑cry]" }],
    [(img) => element("P", [element("STRONG", [text("标签")]), img]), { "data-image-source": "https://example.com/uploads/build.png" }],
    [(img) => element("P", [element("A", [img])]), { "data-image-source": "https://img.shields.io/badge/build-passing-green" }],
  ];
  for (const [build, attrs] of cases) { const img = image(); build(img); assert.equal(imageIsInline(img, attrs), true, JSON.stringify(attrs)); }
});

test("photos with a lead-in, credit or caption on one side keep the reserve", () => {
  const cases = [
    (img) => element("P", [element("SPAN", [text("来源")]), img]),
    (img) => element("P", [text("如下图"), img]),
    (img) => element("P", [text("这是前面的一段话。"), img]),
    (img) => element("P", [img, text("图1：说明")]),
    (img) => element("P", [img, text("▲ 现场")]),
  ];
  const hashed = image(); element("P", [element("SPAN", [text("来源")]), hashed]);
  assert.equal(imageIsInline(hashed, { "data-image-source": "https://example.com/ab12cd.png" }), false);
  for (const build of cases) { const img = image(); build(img); assert.equal(imageIsInline(img, { "data-image-source": "https://example.com/2026/10/photo.jpg" }), false); }
});

test("a photo alone keeps its reserve whatever words its file name or placeholder alt holds (recheck-148 P2-1)", () => {
  for (const attrs of [
    { "data-image-source": "https://cdn.example/2026/smile-campaign.jpg" },
    { "data-image-source": "https://cdn.example/2026/facial-expression-study.jpg" },
    { "data-image-source": "https://cdn.example/2026/sticker-shop-opening.jpg" },
    { "data-image-source": "https://cdn.example/2026/a.jpg", alt: "[图片]" },
    { "data-image-source": "https://cdn.example/2026/a.jpg", alt: "[image]" },
  ]) {
    const img = image(); element("DIV", [element("P", [img]), element("P", [text("next paragraph")])]);
    assert.equal(imageIsInline(img, attrs), false, JSON.stringify(attrs));
  }
  for (const attrs of [
    { "data-image-source": "https://face.t.sinajs.cn/t4/appstyle/expression/ext/normal/xiaoku.png" },
    { "data-image-source": "https://cdn.example/stickers/cat.gif" },
    { "data-image-source": "https://cdn.example/images/smile.gif" },
    { "data-image-source": "https://cdn.example/a.png", alt: "[笑cry]" },
  ]) {
    const img = image(); element("P", [text("哈哈"), img]);
    assert.equal(imageIsInline(img, attrs), true, JSON.stringify(attrs));
  }
});

test("a photo with a sentence or a credit on one side keeps its reserve (recheck-148 P2-2)", () => {
  const photo = { "data-image-source": "https://example.com/2026/10/photo.jpg" };
  const cases = [
    (img) => element("P", [text("今天下午记者在现场看到大批市民排队等候入场"), img]),
    (img) => element("P", [text("The new station opened to the public this morning"), img]),
    (img) => element("P", [img, text("Figure 1 The new station")]),
    (img) => element("P", [img, text("图2 现场")]),
    (img) => element("P", [img, text("张三 摄")]),
  ];
  for (const build of cases) { const img = image(); build(img); assert.equal(imageIsInline(img, photo), false); }
  const badge = image(); element("P", [element("STRONG", [text("下载")]), badge]);
  assert.equal(imageIsInline(badge, photo), true);
});

test("covers: folders, declared logos, every source, stickers and placeholder alts (recheck-156 N1-N5)", () => {
  assert.equal(smallImage({ src: "https://cdn.example/pixel/2026/hero.jpg" }), false);
  assert.equal(smallImage({ src: "https://cdn.example/beacon/launch/photo.jpg" }), false);
  assert.equal(smallImage({ src: "https://t.example/pixel?id=1" }), true);
  assert.equal(smallImage({ src: "https://cdn.example/new-logo.png", width: "1200", height: "675" }), false);
  assert.equal(smallImage({ src: "https://cdn.example/site-logo.png" }), true);
  assert.equal(smallImage({ src: "https://cdn.example/real.jpg", srcset: "https://t.example/pixel.gif 1x, https://cdn.example/real@2x.jpg 2x" }), false);
  assert.equal(smallImage({ src: "https://cdn.example/real.jpg", "data-src": "https://t.example/pixel.gif" }), false);
  assert.equal(smallImage({ src: "https://t.example/spacer.gif", "data-src": "https://t.example/pixel.gif" }), true);
  assert.equal(smallImage({ src: "https://cdn.example/stickers/cat.gif" }), true);
  assert.equal(smallImage({ src: "https://face.t.sinajs.cn/t4/appstyle/expression/ext/normal/xiaoku.png" }), true);
  assert.equal(smallImage({ src: "https://cdn.example/a.jpg", alt: "[图片]" }), false);
  assert.equal(smallImage({ src: "https://cdn.example/a.jpg", alt: "[笑cry]" }), true);
  assert.equal(smallImage({ src: "https://cdn.example/2026/smile-campaign.jpg" }), false);
});
