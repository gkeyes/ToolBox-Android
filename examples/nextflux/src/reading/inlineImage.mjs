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
// 1fxxx codepoint file name, a 72x72 size folder), sticker and expression
// folders, a smiley file name ("smile.gif") and badge services. Only a whole
// folder or file name counts, so a photo named "smile-campaign.jpg" or
// "facial-expression-study.jpg" keeps its reserve (recheck-148 P2-1). Alt text
// that is only emoji says the same. A bracketed sticker name ([笑cry],
// Weibo/QQ style) only counts on a sticker's file type and never as a
// placeholder ([图片], [image]) or a file name ([IMG_1234]), which feeds put
// on photos. The cover and the inline reserve share these rules.
const SMALL_SOURCE = /twemoji|(?:^|\/)(?:emojis?|emoticons?|stickers?|expressions?|smileys?|smilies)\/|(?:^|\/)(?:smile|smiley|emoji|emoticon|sticker)(?:[-_]?\d{1,4})?\.(?:png|gif|svg|webp)(?:$|[?#])|(?:^|\/)(?:16|20|24|32|36|48|64|72|96|128)x(?:16|20|24|32|36|48|64|72|96|128)\/|(?:^|\/)1f[0-9a-f]{3}(?:[-_][0-9a-f]{4,5})*\.(?:png|gif|svg|webp)(?:$|[?#])|(?:^|\/\/)(?:img\.shields\.io|badgen\.net|badge\.fury\.io)\//i;
const EMOJI_ALT = /^\s*(?:\p{Extended_Pictographic}|\p{Regional_Indicator}|\u200d|\ufe0f)+\s*$/u;
const STICKER_ALT = /^\s*\[[^\[\]\d_]{1,8}\]\s*$/u;
const PLACEHOLDER_ALT = /^\s*\[\s*(?:图片|圖片|照片|图像|圖像|配图|配圖|image|img|photo|picture|pic)\s*\]\s*$/i;
const stickerAlt = (alt) => STICKER_ALT.test(String(alt || "")) && !PLACEHOLDER_ALT.test(String(alt || ""));
// A photo's file type (JPEG, AVIF, HEIC, also as a CDN format parameter or a
// WordPress ".jpg.webp" copy) or a photo CDN (WeChat article images, Zhihu,
// Google/Blogger, Weibo uploads); GIF, SVG, icon files and GitHub's camo badge
// proxy are emoji, stickers or badges. PNG and WebP are both, so the words
// decide for them.
const PHOTO_FORMAT = /\.(?:jpe?g|jfif|avif|hei[cf])(?:\.webp)?(?:$|[?#])|[?&](?:wx_fmt|format|fm)=(?:jpe?g|avif|hei[cf])\b|^https?:\/\/(?:mmbiz\.qpic\.cn|pic\d*\.zhimg\.com|(?:lh\d+|blogger)\.googleusercontent\.com|(?:wx|tva)\d\.sinaimg\.cn)\//i;
const SMALL_FORMAT = /\.(?:gif|svg|ico|bmp)(?:$|[?#])|[?&](?:wx_fmt|format|fm)=(?:gif|svg)\b|^https?:\/\/camo\.githubusercontent\.com\//i;
// An icon word anywhere in the address, query included ("a.png?k=icon").
const SOURCE_ICON = /(?:^|[/_.\-\s?&=])(?:icons?|emoji|emoticons?|emotions?|smil(?:e|ey|ies)|badges?|logos?|avatars?|favicon|stickers?)(?:$|[/_.\-\s\d?&=#])/i;
// Words around a photo on the same line: a lead-in that ends a sentence or
// introduces it (。 ： 如下图) or holds a credit (来源：某某, Source) before
// it; a caption marker (图：, 图1, Figure 1, ▲, 来源) or a credit (张三 摄)
// on either side. A badge's label is a word or two, so longer text on one
// side of an image of unknown type reads as the sentence around a photo
// (recheck-148 P2-2).
const PHOTO_BEFORE = /[。.!！?？:：]\s*$|来源|图源|出处|摄影|图片|下图|如图|见图|如下|source|credit|photo|\bvia\b|图\s*\d|\bfig(?:ure|\.)?\s*\d|摄\s*$/i;
const PHOTO_AFTER = /^\s*(?:图\s*\d|图\s*[：:]|[▲△↑]|来源|图源|出处|摄影|图片|source|credit|photo|via\b|fig(?:ure|\.)?\s*\d)|摄\s*$/i;
const LABEL = 16;

// Images too small to stand for the article: anything 64 px or less on a
// side, icons, emoji, stickers, badges, logos, and tracking pixels and
// spacers, which often come without a size. The article's cover skips them.
// Only a whole file name counts as a tracker or a logo ("/pixel.gif",
// "site-logo.png"), so a photo named "google-pixel-10-pro.jpg" or one in a
// "/pixel/2026/" folder stays, and a logo declared larger than an icon is a
// picture. The image is small only when every source it has (src, data-src,
// each srcset entry) is, so a real src beside a lazy spacer still counts.
const TRACKER = /(?:^|\/)(?:pixel|spacer|blank|beacon|tracker|tracking|1x1|clear|transparent|trans|empty|dot)\.(?:gif|png|jpe?g|webp|svg)(?:$|[?#])|(?:^|\/)(?:pixel|beacon|1x1)(?:$|[?#])/i;
const COVER_ICON = /(?:^|[/_\-])(?:logo|favicon|avatar|icon|badge)s?\.(?:png|gif|svg|ico|webp)(?:$|[?#])/i;
const SOURCES = ["src", "data-src", "data-original", "data-lazy-src", "data-srcset", "data-lazy-srcset", "srcset"];
const sourcesOf = (attrs) => SOURCES.flatMap((name) => {
  const value = String(attrs[name] || "").trim();
  if (!value) return [];
  return name.endsWith("srcset") ? value.split(/,\s+/).map((entry) => entry.trim().split(/\s+/)[0]).filter(Boolean) : [value];
});
// An address together with the origin a Miniflux /proxy/ or /media/v1/
// address carries base64url-encoded, so file names and types still read
// through the server's cache. The cover and the inline reserve both use it.
const ORIGIN = /^[A-Za-z0-9_-]{12,}={0,2}$/;
function decoded(segment) {
  try {
    const text = globalThis.atob(segment.replace(/-/g, "+").replace(/_/g, "/"));
    return /^https?:\/\//i.test(text) ? text : "";
  } catch { return ""; }
}
function withOrigin(address) {
  const value = String(address || "");
  if (!/\/(?:proxy|media\/v1)\//.test(value)) return [value];
  return [value, ...value.split(/[/?#]/).filter((segment) => ORIGIN.test(segment)).map(decoded).filter(Boolean)];
}

// One source that cannot be the cover: a tracker, an emoji or sticker set or a
// badge service. The cover is then taken from the image's other sources.
export const smallSource = (source) => withOrigin(source).some((address) => SMALL_SOURCE.test(address) || TRACKER.test(address));
export function smallImage(attrs = {}) {
  const width = number(attrs.width), height = number(attrs.height);
  if ((width > 0 && width <= SMALL) || (height > 0 && height <= SMALL)) return true;
  const sources = sourcesOf(attrs);
  if (EMOJI_ALT.test(String(attrs.alt || "")) || (stickerAlt(attrs.alt) && !sources.flatMap(withOrigin).some((source) => PHOTO_FORMAT.test(source)))) return true;
  const declared = width > 0 || height > 0;
  if (!declared && namesIcon(attrs.class)) return true;
  return sources.length > 0 && sources.every((source) => smallSource(source) || (!declared && withOrigin(source).some((address) => COVER_ICON.test(address))));
}

// Every address the image may load from, with proxy origins.
function addressesOf(attrs) {
  let candidates = [];
  try { candidates = JSON.parse(attrs["data-image-candidates"] || "[]"); } catch { /* no NextFlux metadata */ }
  const addresses = [attrs["data-image-source"], ...(Array.isArray(candidates) ? candidates : []), attrs.src].filter(Boolean).flatMap(withOrigin);
  // A lazy image's spacer placeholder says nothing about the picture.
  return addresses.filter((address) => !TRACKER.test(address));
}

export function imageIsInline(target, attrs = {}) {
  const width = number(attrs.width), height = number(attrs.height);
  if (width || height) return (width > 0 && width <= SMALL) || (height > 0 && height <= SMALL);
  const parent = parentOf(target);
  if (!parent || tagOf(parent) === "FIGURE") return false;
  const addresses = addressesOf(attrs);
  const source = addresses.join(" ");
  if (attrs["data-image-icon"] || addresses.some((address) => SMALL_SOURCE.test(address)) || EMOJI_ALT.test(String(attrs.alt || ""))) return true;
  const before = scan(target, -1).text;
  const after = scan(target, 1).text;
  if (before && after) return true;
  // A photo alone on its line keeps the reserve whatever its alt says.
  if (!before && !after) return false;
  // Text on one side only. A JPEG or a photo CDN is a photo whatever the
  // words; a sticker name or an icon word marks the rest as inline. Otherwise
  // a photo when the words read like a lead-in, credit or caption. A GIF, SVG
  // or camo badge after a sentence is an emoji or badge (recheck-159: those
  // reserved 16:9 and pulled the text up 173 px, as labelled badges did in
  // recheck-127); a PNG, WebP or unknown type is a photo after more than a
  // label's words (recheck-164: PNG screenshots and WeChat PNG pictures after
  // an unpunctuated sentence jumped 173 px down).
  if (addresses.some((address) => PHOTO_FORMAT.test(address))) return false;
  if (stickerAlt(attrs.alt) || SOURCE_ICON.test(source) || ICON.test(String(attrs.alt || ""))) return true;
  if (before ? PHOTO_BEFORE.test(before) : PHOTO_AFTER.test(after)) return false;
  return addresses.some((address) => SMALL_FORMAT.test(address)) || [...(before || after)].length <= LABEL;
}

// True while nothing follows the image on its line yet, so text mounted by a
// later progressive batch could still make it inline.
export function imageLineOpen(target) {
  return Boolean(parentOf(target)) && scan(target, 1) === OPEN;
}
