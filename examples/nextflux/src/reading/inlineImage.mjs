// An image counts as inline (an icon or emoji in a sentence) when its own
// container holds text outside block children: `<p>Label <img></p>` or
// `<p><strong>Label</strong><img></p>`, but not a figure's caption or the
// paragraph after a full-width image. Inline images skip the 16:9 reserve.
const BLOCK = /^(?:FIGCAPTION|FIGURE|DIV|P|UL|OL|LI|TABLE|BLOCKQUOTE|PRE|H[1-6]|SECTION|ARTICLE|ASIDE|HEADER|FOOTER|DETAILS|HR)$/;

export function imageIsInline(target) {
  const parent = target?.parentElement;
  if (!parent || parent.tagName === "FIGURE") return false;
  return [...parent.childNodes].some((node) => node !== target
    && !(node.nodeType === 1 && (BLOCK.test(node.tagName) || node.hasAttribute("data-font-block")))
    && Boolean(node.textContent?.trim()));
}
