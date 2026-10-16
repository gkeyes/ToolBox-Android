// Whether an image of unknown size sits inside running text (an icon, emoji
// or badge) rather than being a photo on its own line. Inline images skip the
// 16:9 reserve; a photo keeps it, so the text below does not jump when it
// loads (recheck-107: a photo after `text<br>`, before `<br>caption` or next
// to a `<span>来源</span>` lost its reserve and pushed the text ~150px down).
//
// Only text on the image's own line counts: a <br> or a block ends the line,
// and inline wrappers (`<strong>before <img></strong> after`) are looked
// through. Text on both sides means the image is mid-sentence, which a photo
// never is. Text on one side only is ambiguous (`<strong>标签</strong><img>`
// is usually an icon, `<span>来源</span><img>` a photo), so it is inline only
// when the image names itself as an icon. Works on DOM and htmlparser2 nodes.
const BLOCK = /^(?:BR|FIGCAPTION|FIGURE|DIV|P|UL|OL|LI|TABLE|THEAD|TBODY|TR|TD|TH|BLOCKQUOTE|PRE|H[1-6]|SECTION|ARTICLE|ASIDE|HEADER|FOOTER|DETAILS|SUMMARY|HR|IMG|PICTURE|VIDEO|IFRAME|BODY|HTML)$/;
const ICON = /(?:^|[/_.\-\s])(?:icons?|emoji|emoticons?|smil(?:e|ey|ies)|badges?|logos?|avatars?|favicon|stickers?)(?:$|[/_.\-\s\d])/i;
const SMALL = 64;
// Sanitising drops image classes; content.js keeps this one marker instead.
export const namesIcon = (value) => ICON.test(String(value || ""));

const isText = (node) => node.nodeType === 3 || node.type === "text";
const tagOf = (node) => String(node.tagName || node.name || "").toUpperCase();
const textOf = (node) => node.textContent ?? node.data ?? "";
const marked = (node) => Boolean(node.hasAttribute?.("data-font-block")) || Boolean(node.attribs && "data-font-block" in node.attribs);
const breaks = (node) => !isText(node) && (BLOCK.test(tagOf(node)) || marked(node));
const parentOf = (node) => node?.parentElement ?? node?.parent ?? null;
const childrenOf = (node) => [...(node.childNodes ?? node.children ?? [])];
// The reading root and blocks end a line; inline wrappers do not.
const isRoot = (node) => Boolean(node.hasAttribute?.("data-font-reading-root"));
const wraps = (node) => node && !breaks(node) && !isRoot(node);
const number = (value) => { const parsed = Number.parseFloat(value); return Number.isFinite(parsed) && parsed > 0 ? parsed : 0; };

// Anything after the node inside the article (not past the reading root,
// whose React siblings such as the loading line are not article content).
const followed = (node) => {
  for (let current = node; parentOf(current) && !isRoot(current); current = parentOf(current)) {
    const siblings = childrenOf(parentOf(current));
    if (siblings.indexOf(current) < siblings.length - 1) return true;
  }
  return false;
};

// The first thing met inside a node walking in `step` order: "text", "end"
// (a nested <br> or block) or null when it holds neither.
function look(node, step) {
  if (isText(node)) return textOf(node).trim() ? "text" : null;
  if (breaks(node)) return "end";
  const children = childrenOf(node);
  if (step < 0) children.reverse();
  for (const child of children) {
    const found = look(child, step);
    if (found) return found;
  }
  return null;
}

// "text", "end" (a <br> or block ends the line first) or "open" (the image
// is still the last parsed node: more may be mounted after it). Once anything
// follows the enclosing block, the line is closed.
function scan(node, step) {
  let current = node;
  for (;;) {
    const parent = parentOf(current);
    if (!parent) return "open";
    const siblings = childrenOf(parent);
    for (let index = siblings.indexOf(current) + step; index >= 0 && index < siblings.length; index += step) {
      const sibling = siblings[index];
      const found = look(sibling, step);
      if (found) return found;
    }
    if (!wraps(parent)) return step > 0 && followed(parent) ? "end" : "open";
    current = parent;
  }
}

export function imageIsInline(target, attrs = {}) {
  const width = number(attrs.width), height = number(attrs.height);
  if (width || height) return (width > 0 && width <= SMALL) || (height > 0 && height <= SMALL);
  const parent = parentOf(target);
  if (!parent || tagOf(parent) === "FIGURE") return false;
  const before = scan(target, -1) === "text";
  const after = scan(target, 1) === "text";
  if (before && after) return true;
  if (!before && !after) return false;
  return ICON.test(`${attrs["data-image-source"] || attrs.src || ""} ${attrs["data-image-icon"] ? "icon" : ""} ${attrs.alt || ""}`);
}

// True while nothing follows the image on its line yet, so text mounted by a
// later progressive batch could still make it inline.
export function imageLineOpen(target) {
  return Boolean(parentOf(target)) && scan(target, 1) === "open";
}
