import { Parser } from "htmlparser2";
import { imageSourceCandidates } from "./content.js";

export const ARTICLE_METADATA_VERSION = 1;
export const currentArticleMetadata = (row) => row?.metadataVersion === ARTICLE_METADATA_VERSION && Array.isArray(row.coverSources);

// Keep the list's cleanTitle/extractTextFromHtml semantics without creating DOM nodes.
const OMIT_TEXT = new Set(["script", "style", "iframe", "object", "embed", "svg", "math", "template"]);
const OMIT_IMAGES = new Set(["script", "style", "iframe", "template"]);
const WHITESPACE = /\s/;
const PREVIEW_LENGTH = 300;

function parseMetadata(html, textLimit, findImage = false, baseUrl) {
  let text = "";
  let pendingSpace = false;
  let omittedTextDepth = 0;
  let omittedImageDepth = 0;
  let imageSources = [];
  const pictures = [];

  const parser = new Parser({
    onopentag(name, attributes) {
      if (OMIT_TEXT.has(name)) omittedTextDepth += 1;
      if (OMIT_IMAGES.has(name)) omittedImageDepth += 1;
      if (findImage && !omittedImageDepth && !imageSources.length) {
        if (name === "picture") pictures.push([]);
        if (name === "source" && pictures.length) pictures.at(-1).push(attributes);
        if (name === "img") imageSources = imageSourceCandidates(attributes, baseUrl, pictures.at(-1));
      }
    },
    onclosetag(name) {
      if (name === "picture") pictures.pop();
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
  return { text, imageSources };
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
  const { text: previewText, imageSources } = parseMetadata(content, PREVIEW_LENGTH, true, article?.url);
  const coverSources = content ? imageSourceCandidates({
    "data-image-candidates": JSON.stringify([enclosure?.url, ...imageSources].filter(Boolean)),
  }, article?.url) : [];

  return {
    titleText,
    previewText,
    coverUrl: coverSources[0] || null,
    coverSources,
    metadataVersion: ARTICLE_METADATA_VERSION,
  };
}
