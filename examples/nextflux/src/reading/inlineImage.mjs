// Whether an image of unknown size sits inside running text (an icon, emoji
// or badge) rather than being a photo on its own line. Inline images skip the
// 16:9 reserve; a photo keeps it, so the text below does not jump when it
// loads (recheck-107: a photo after `text<br>`, before `<br>caption` or next
// to a `<span>来源</span>` lost its reserve and pushed the text ~150px down).
//
// Only text on the image's own line counts: a <br> or a block ends the line.
// Text on both sides means the image is mid-sentence, which a photo never is.
// Text on one side only is ambiguous (`<strong>标签</strong><img>` is usually
// an icon, `<span>来源</span><img>` a photo), so it is inline only when the
// image names itself as an icon. Works on DOM nodes and on htmlparser2 nodes.
const BLOCK = /^(?:BR|FIGCAPTION|FIGURE|DIV|P|UL|OL|LI|TABLE|BLOCKQUOTE|PRE|H[1-6]|SECTION|ARTICLE|ASIDE|HEADER|FOOTER|DETAILS|HR|IMG|PICTURE|VIDEO|IFRAME)$/;
const ICON = /(?:^|[/_.\-\s])(?:icons?|emoji|emoticons?|smil(?:e|ey|ies)|badges?|logos?|avatars?|favicon|stickers?)(?:$|[/_.\-\s\d])|\.svg(?:$|[?#])/i;
const SMALL = 64;

const isText = (node) => node.nodeType === 3 || node.type === "text";
const tagOf = (node) => String(node.tagName || node.name || "").toUpperCase();
const textOf = (node) => (isText(node) ? node.textContent ?? node.data ?? "" : node.textContent ?? (node.children || []).map(textOf).join(""));
const breaks = (node) => !isText(node) && (BLOCK.test(tagOf(node))
  || Boolean(node.hasAttribute?.("data-font-block")) || Boolean(node.attribs && "data-font-block" in node.attribs));
const number = (value) => { const parsed = Number.parseFloat(value); return Number.isFinite(parsed) && parsed > 0 ? parsed : 0; };

function lineHasText(siblings, from, step) {
  for (let index = from; index >= 0 && index < siblings.length; index += step) {
    const node = siblings[index];
    if (breaks(node)) return false;
    if (textOf(node).trim()) return true;
  }
  return false;
}

export function imageIsInline(target, attrs = {}) {
  const width = number(attrs.width), height = number(attrs.height);
  if (width || height) return (width > 0 && width <= SMALL) || (height > 0 && height <= SMALL);
  const parent = target?.parentElement ?? target?.parent;
  if (!parent || tagOf(parent) === "FIGURE") return false;
  const siblings = [...(parent.childNodes ?? parent.children ?? [])];
  const index = siblings.indexOf(target);
  if (index < 0) return false;
  const before = lineHasText(siblings, index - 1, -1);
  const after = lineHasText(siblings, index + 1, 1);
  if (before && after) return true;
  if (!before && !after) return false;
  return ICON.test(`${attrs["data-image-source"] || attrs.src || ""} ${attrs.class || ""} ${attrs.alt || ""}`);
}
