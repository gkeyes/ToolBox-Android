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
// is usually a badge, `<span>来源</span><img>` a photo), so the words decide.
// Emoji, stickers and badge services are inline wherever they sit. Works on
// DOM and htmlparser2 nodes.
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

// The first thing met inside a node walking in `step` order: its text,
// END (a nested <br> or block) or null when it holds neither.
const END = { end: true };
const OPEN = { open: true };
function look(node, step) {
  if (isText(node)) { const text = textOf(node).trim(); return text ? { text } : null; }
  if (breaks(node)) return END;
  const children = childrenOf(node);
  if (step < 0) children.reverse();
  for (const child of children) {
    const found = look(child, step);
    if (found) return found;
  }
  return null;
}

// The nearest text on the image's line in `step` direction, END (a <br> or
// block ends the line first) or OPEN (the image is still the last parsed
// node: more may be mounted after it). Once anything follows the enclosing
// block, the line is closed.
function scan(node, step) {
  let current = node;
  for (;;) {
    const parent = parentOf(current);
    if (!parent) return OPEN;
    const siblings = childrenOf(parent);
    for (let index = siblings.indexOf(current) + step; index >= 0 && index < siblings.length; index += step) {
      const found = look(siblings[index], step);
      if (found) return found;
    }
    if (!wraps(parent)) return step > 0 && followed(parent) ? END : OPEN;
    current = parent;
  }
}

// Images that are small whatever surrounds them: emoji sets (twemoji, a
// 1fxxx codepoint file name, a 72x72 size folder), stickers and expressions, and
// badge services. Alt text that is only emoji or a bracketed sticker name
// ([笑cry], Weibo/QQ style) says the same.
const SMALL_SOURCE = /twemoji|emoji|emoticon|smil(?:e|ey|ies)|sticker|expression|(?:^|\/)(?:16|20|24|32|36|48|64|72|96|128)x(?:16|20|24|32|36|48|64|72|96|128)\/|(?:^|\/)1f[0-9a-f]{3}(?:[-_][0-9a-f]{4,5})*\.(?:png|gif|svg|webp)(?:$|[?#])|(?:^|\/\/)(?:img\.shields\.io|badgen\.net|badge\.fury\.io)\//i;
const SMALL_ALT = /^\s*(?:(?:\p{Extended_Pictographic}|\p{Regional_Indicator}|\u200d|\ufe0f)+|\[[^\[\]]{1,12}\])\s*$/u;
// Words around a photo on the same line: a lead-in that ends a sentence or
// introduces it (。 ： 如下图) or holds a credit (来源：某某, Source) before
// it; a caption marker (图：, ▲, 来源) after it.
const PHOTO_BEFORE = /[。.!！?？:：]\s*$|来源|图源|出处|摄影|图片|下图|如图|见图|如下|source|credit|photo|\bvia\b/i;
const PHOTO_AFTER = /^\s*(?:图\s*\d*\s*[：:]|[▲△↑]|来源|图源|出处|摄影|图片|source|credit|photo|via\b)/i;

// Images too small to stand for the article: anything 64 px or less on a
// side, icons, emoji, stickers and badges, and tracking pixels and spacers,
// which often come without a size. The article's cover skips them.
const TRACKER = /(?:^|[/_.\-])(?:pixel|spacer|blank|beacon|tracking|tracker|1x1)(?:$|[/_.\-?#])/i;
export function smallImage(attrs = {}) {
  const width = number(attrs.width), height = number(attrs.height);
  if ((width > 0 && width <= SMALL) || (height > 0 && height <= SMALL)) return true;
  const source = String(attrs.src || "");
  return namesIcon(attrs.class) || SMALL_SOURCE.test(source) || TRACKER.test(source) || SMALL_ALT.test(String(attrs.alt || ""));
}

export function imageIsInline(target, attrs = {}) {
  const width = number(attrs.width), height = number(attrs.height);
  if (width || height) return (width > 0 && width <= SMALL) || (height > 0 && height <= SMALL);
  const parent = parentOf(target);
  if (!parent || tagOf(parent) === "FIGURE") return false;
  const source = String(attrs["data-image-source"] || attrs.src || "");
  if (attrs["data-image-icon"] || SMALL_SOURCE.test(source) || SMALL_ALT.test(String(attrs.alt || ""))) return true;
  const before = scan(target, -1).text;
  const after = scan(target, 1).text;
  if (before && after) return true;
  if (!before && !after) return false;
  if (ICON.test(`${source} ${attrs.alt || ""}`)) return true;
  // Text on one side only: a photo when the words read like a lead-in,
  // credit or caption; otherwise the image sits in the line like a badge
  // after its label (recheck-127: those reserved 16:9 and jumped 173 px).
  return before ? !PHOTO_BEFORE.test(before) : !PHOTO_AFTER.test(after);
}

// True while nothing follows the image on its line yet, so text mounted by a
// later progressive batch could still make it inline.
export function imageLineOpen(target) {
  return Boolean(parentOf(target)) && scan(target, 1) === OPEN;
}
