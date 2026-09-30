import { imageDimension } from "./imageDimensions.js";
import { dwImageSources } from "../reading/adapters/dw-images.mjs";

import { ALLOWED_TAGS, DROP_CONTENT } from "../reading/schema.mjs";
export { ALLOWED_TAGS, DROP_CONTENT };

const LAZY_IMAGE_ATTRIBUTES = ["data-src", "data-original", "data-lazy-src"];
const LAZY_SRCSET_ATTRIBUTES = ["data-srcset", "data-lazy-srcset"];
const MAX_IMAGE_CANDIDATES = 32;
const SPACE = /[\t\n\f\r ]/;

export function safeContentUrl(value, baseUrl) {
  if (typeof value !== "string" || !value || Array.from(value).some((char) => char.codePointAt(0) <= 32 || char.codePointAt(0) === 127)) return null;
  try {
    const url = new URL(value, baseUrl || "https://miniflux.xiaochen.win/");
    return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

function safeImageSource(value, baseUrl) {
  if (typeof value !== "string" || !value) return null;
  if (/^data:image\/(png|jpeg|gif|webp|avif|bmp|x-icon);base64,[a-z0-9+/=\s]+$/i.test(value)) return value;
  const url = safeContentUrl(value, /^\/(?:proxy\/|media\/v1\/)/.test(value) ? "https://miniflux.xiaochen.win/" : baseUrl);
  // The native media transport only accepts HTTPS. An HTTP lazy placeholder
  // must not hide a usable HTTPS src or srcset candidate.
  return url?.startsWith("https:") ? url : null;
}

export function parseImageSrcset(value, baseUrl) {
  if (typeof value !== "string") return [];
  const candidates = [];
  let position = 0;
  // Srcset URLs end at ASCII whitespace, not at a comma. CDN transforms and
  // data URLs commonly contain commas; only descriptor separators split them.
  while (position < value.length) {
    while (position < value.length && (SPACE.test(value[position]) || value[position] === ",")) position += 1;
    const start = position;
    while (position < value.length && !SPACE.test(value[position])) position += 1;
    let source = value.slice(start, position), descriptor = "";
    if (source.endsWith(",")) source = source.replace(/,+$/, "");
    else {
      let parentheses = 0;
      while (position < value.length) {
        const char = value[position++];
        if (char === "," && !parentheses) break;
        if (char === "(") parentheses += 1;
        if (char === ")" && parentheses) parentheses -= 1;
        descriptor += char;
      }
    }
    descriptor = descriptor.trim();
    const match = descriptor ? descriptor.match(/^(\d+(?:\.\d+)?)(w|x)$/) : ["", "1", "x"];
    if (!match || (match[2] === "w" && !/^\d+$/.test(match[1]))) continue;
    const score = Number(match[1]), url = safeImageSource(source, baseUrl);
    if (url && Number.isFinite(score) && score > 0) candidates.push({ url, score, descriptor: descriptor || "1x" });
  }
  return candidates.sort((a, b) => b.score - a.score);
}

export function srcsetImageSources(value, baseUrl) {
  return parseImageSrcset(value, baseUrl).map(({ url }) => url);
}

function embeddedPlaceholder(source) {
  if (!/^data:image\/(?:gif|png);base64,/i.test(source)) return false;
  try {
    const prefix = atob(source.split(",")[1].replace(/\s/g, "").slice(0, 64));
    const byte = (index) => prefix.charCodeAt(index);
    if (prefix.startsWith("GIF8")) return (byte(6) | byte(7) << 8) <= 2 && (byte(8) | byte(9) << 8) <= 2;
    if (prefix.startsWith("\x89PNG\r\n\x1a\n") && prefix.length >= 24) {
      const size = (index) => byte(index) * 2 ** 24 + (byte(index + 1) << 16) + (byte(index + 2) << 8) + byte(index + 3);
      return size(16) <= 2 && size(20) <= 2;
    }
  } catch { /* malformed embedded data can still be rejected by the decoder */ }
  return false;
}

export function imageSourceCandidates(attributes = {}, baseUrl, pictureSources = [], articleUrl = baseUrl) {
  const candidates = [];
  const add = (value) => { if (value && !candidates.includes(value)) candidates.push(value); };
  const srcsets = (attrs, names) => { for (const name of names) for (const url of srcsetImageSources(attrs[name], baseUrl)) add(url); };
  // Extraction can retain choices which Defuddle would otherwise discard.
  // Validate metadata with exactly the same URL rules as ordinary attributes.
  try {
    const retained = JSON.parse(attributes["data-image-candidates"]);
    if (Array.isArray(retained)) for (const source of retained.slice(0, MAX_IMAGE_CANDIDATES)) add(safeImageSource(source, baseUrl));
  } catch { /* most source HTML has no NextFlux metadata */ }
  for (const name of LAZY_IMAGE_ATTRIBUTES) add(safeImageSource(attributes[name], baseUrl));
  srcsets(attributes, LAZY_SRCSET_ATTRIBUTES);
  for (const source of dwImageSources(attributes, articleUrl)) add(source);
  for (const source of pictureSources) {
    if (source.type && !/^image\//i.test(source.type)) continue;
    srcsets(source, [...LAZY_SRCSET_ATTRIBUTES, "srcset"]);
  }
  add(safeImageSource(attributes.src, baseUrl));
  srcsets(attributes, ["srcset"]);
  const remote = candidates.filter((url) => !url.startsWith("data:"));
  const embedded = candidates.filter((url) => url.startsWith("data:") && (!remote.length || !embeddedPlaceholder(url)));
  // Preserve server cache/proxy admission. The server itself handles cache
  // misses; direct origins are alternatives after every server candidate.
  const archived = (url) => /^https:\/\/miniflux\.xiaochen\.win\/(?:proxy\/|media\/v1\/)/.test(url);
  return [...remote.filter(archived), ...remote.filter((url) => !archived(url)), ...embedded].slice(0, MAX_IMAGE_CANDIDATES);
}

export function cleanAttributes(tag, attributes, baseUrl, pictureSources) {
  const sourceAttributes = attributes && typeof attributes === "object" ? attributes : {};
  const clean = {};
  for (const [name, value] of Object.entries(sourceAttributes)) {
    if (["alt", "title"].includes(name)) clean[name] = String(value);
    if (tag === "img" && ["width", "height"].includes(name) && imageDimension(value)) clean[name] = String(imageDimension(value));
    if (["colspan", "rowspan", "start"].includes(name) && /^\d+$/.test(value)) clean[name] = value;
    if (tag === "p" && name === "data-reading-role" && ["list", "meta"].includes(value)) clean[name] = value;
    // Keep article-local anchors as inert metadata, never document-global IDs.
    if ((name === "id" && tag !== "img") || (name === "name" && tag === "a")) {
      if (value) clean["data-article-anchor"] = value;
    }
    if (name === "class" && tag === "code") {
      const language = String(value).match(/(?:^|\s)(?:language-|lang-)[a-zA-Z0-9_+-]+(?=\s|$)/)?.[0]?.trim();
      if (language) clean.class = language;
    }
    if (name === "href" && tag === "a") {
      const url = safeContentUrl(value, baseUrl);
      if (url) { clean.href = url; clean.rel = "noopener noreferrer"; }
    }
  }
  if (tag === "img") {
    // Keep all usable choices inert until ArticleImage acquires them through
    // the native transport. The first source preserves the older portal API.
    const candidates = imageSourceCandidates(sourceAttributes, baseUrl, pictureSources);
    if (candidates.length) clean["data-image-source"] = candidates[0];
    if (candidates.length > 1) clean["data-image-candidates"] = JSON.stringify(candidates);
  }
  return clean;
}
