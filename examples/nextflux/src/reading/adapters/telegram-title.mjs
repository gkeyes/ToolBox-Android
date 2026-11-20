const PHOTO_TAG = /^(🖼[\uFE0E\uFE0F]?|\[Photo\]) /u;
const MAX_COMPARISON_LENGTH = 512;
const literal = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// RSSHub may prefix the message excerpt with an image label that is absent
// from the message. The label is optional only in this comparison.
export function createTelegramOpeningTitle(title) {
  // Keep the optional pattern small. Longer titles still use exact matching.
  if (title.length > MAX_COMPARISON_LENGTH) return null;
  const photoTag = PHOTO_TAG.exec(title);
  const text = photoTag ? title.slice(photoTag[0].length) : title;
  if (!/[\p{L}\p{N}]/u.test(text)) return null;

  let pattern = "";
  let offset = 0;
  for (const match of text.matchAll(/ ?([,，:：]) ?/gu)) {
    const start = match.index;
    const end = start + match[0].length;
    const before = text.slice(Math.max(0, start - 2), start);
    const after = text.slice(end, end + 2);
    // Width and padding are typographical differences between prose words.
    // Keep punctuation literal in timestamps, numeric values and Latin text.
    const chineseProse = (/\p{Script=Han}$/u.test(before) && (!after || /^\p{L}/u.test(after))) ||
      (/\p{L}$/u.test(before) && /^\p{Script=Han}/u.test(after));
    if (!chineseProse) continue;
    const comma = match[1] === "," || match[1] === "，";
    pattern += literal(text.slice(offset, start)) + (comma ? " ?[,，] ?" : " ?[:：] ?");
    offset = end;
  }
  if (!photoTag && !offset) return null;
  const optionalPhotoTag = !photoTag ? "" : photoTag[1] === "[Photo]"
    ? "(?:\\[Photo\\] )?"
    : "(?:🖼[\\uFE0E\\uFE0F]? )?";
  return {
    pattern: new RegExp("^" + optionalPhotoTag + pattern + literal(text.slice(offset)), "u"),
    // Each prose mark can consume two spaces in the normalized opening.
    limit: 3 * title.length + 2,
    endsWithChinesePunctuation: /[，。！？；：、]$/u.test(text),
    endsWithHan: /\p{Script=Han}$/u.test(text),
    standaloneLatinWord: /^\p{Script=Latin}[\p{Script=Latin}\p{M}]*$/u.test(text),
  };
}
