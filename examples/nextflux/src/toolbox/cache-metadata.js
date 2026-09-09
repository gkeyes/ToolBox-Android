import { Parser } from "htmlparser2";
import { cleanAttributes } from "./content.js";

// Keep the list's cleanTitle/extractTextFromHtml semantics without creating DOM nodes.
const OMIT_TEXT = new Set(["script", "style", "iframe", "object", "embed", "svg", "math", "template"]);
const OMIT_IMAGES = new Set(["script", "style", "iframe", "template"]);
const WHITESPACE = /\s/;
const PREVIEW_LENGTH = 300;

function parseMetadata(html, textLimit, findImage = false) {
  let text = "";
  let pendingSpace = false;
  let omittedTextDepth = 0;
  let omittedImageDepth = 0;
  let imageFound = false;
  let imageSource = null;

  const parser = new Parser({
    onopentag(name, attributes) {
      if (OMIT_TEXT.has(name)) omittedTextDepth += 1;
      if (OMIT_IMAGES.has(name)) omittedImageDepth += 1;
      if (findImage && !imageFound && !omittedImageDepth && name === "img") {
        // The first img wins even if it has no src, matching querySelector("img").
        imageFound = true;
        imageSource = attributes.src || null;
      }
    },
    onclosetag(name) {
      if (OMIT_TEXT.has(name)) omittedTextDepth -= 1;
      if (OMIT_IMAGES.has(name)) omittedImageDepth -= 1;
    },
    ontext(value) {
      if (omittedTextDepth || text.length >= textLimit) return;
      // Parser text callbacks may split entities and whitespace arbitrarily. Keep
      // whitespace pending until real text follows, just like replace(/\s+/g, " ").trim().
      // A bounded preview never retains a second copy of the complete article body.
      for (let index = 0; index < value.length && text.length < textLimit; index += 1) {
        const character = value[index];
        if (WHITESPACE.test(character)) {
          pendingSpace = text.length > 0;
          continue;
        }
        if (pendingSpace) {
          text += " ";
          pendingSpace = false;
          if (text.length >= textLimit) break;
        }
        text += character;
      }
    },
  }, { xmlMode: false, decodeEntities: true });

  parser.end(html ? String(html) : "");
  return { text, imageSource };
}

function coverSource(value, articleUrl) {
  if (typeof value !== "string" || !value) return null;
  // Reuse the article image URL rules, including /proxy/ resolution and raster data.
  // This is metadata only; media.js still owns approval, fetching and byte limits.
  return cleanAttributes("img", { src: value }, articleUrl)["data-image-source"] || null;
}

/**
 * Derive list-only fields in a static module Worker. Does not mutate the article,
 * create DOM nodes, execute article markup or load any of its resources.
 * previewText keeps the existing cleanText.slice(0, 300) UTF-16 length semantics.
 */
export function deriveArticleMetadata(article) {
  const { text: titleText } = parseMetadata(article?.title, Infinity);
  const content = article?.content;
  const enclosure = content && Array.isArray(article?.enclosures)
    ? article.enclosures.find((item) => typeof item?.mime_type === "string" && item.mime_type.startsWith("image/"))
    : null;
  const { text: previewText, imageSource } = parseMetadata(content, PREVIEW_LENGTH, !enclosure?.url);

  return {
    titleText,
    previewText,
    coverUrl: content ? coverSource(enclosure?.url || imageSource, article?.url) : null,
  };
}
