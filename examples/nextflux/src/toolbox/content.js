import { imageDimension } from "./imageDimensions.js";

import { ALLOWED_TAGS, DROP_CONTENT } from "../reading/schema.mjs";
export { ALLOWED_TAGS, DROP_CONTENT };

const LAZY_IMAGE_ATTRIBUTES = ["data-src", "data-original", "data-lazy-src"];

export function safeContentUrl(value, baseUrl) {
  if (typeof value !== "string" || !value || Array.from(value).some((char) => char.codePointAt(0) <= 32 || char.codePointAt(0) === 127)) return null;
  try {
    const url = new URL(value, baseUrl || "https://miniflux.xiaochen.win/");
    return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

function safeImageSource(value, baseUrl) {
  if (typeof value !== "string" || !value) return null;
  if (/^data:image\/(png|jpeg|gif|webp|avif|bmp|x-icon);base64,/i.test(value)) return value;
  return safeContentUrl(value, value.startsWith("/proxy/") ? "https://miniflux.xiaochen.win/" : baseUrl);
}

function srcsetImageSource(value, baseUrl) {
  if (typeof value !== "string" || !value.trim() || /^data:/i.test(value.trim())) return null;
  let selected = null;
  for (const item of value.split(",")) {
    const fields = item.trim().split(/\s+/);
    const source = safeImageSource(fields[0], baseUrl);
    if (!source) continue;
    const descriptor = fields[1] || "1x";
    const match = descriptor.match(/^(\d+(?:\.\d+)?)(w|x)$/);
    const score = match ? Number(match[1]) : 1;
    if (!Number.isFinite(score) || score <= 0) continue;
    if (!selected || score > selected.score) selected = { source, score };
  }
  return selected?.source || null;
}

function imageSource(attributes, baseUrl) {
  for (const name of LAZY_IMAGE_ATTRIBUTES) {
    const source = safeImageSource(attributes?.[name], baseUrl);
    if (source) return source;
  }
  return safeImageSource(attributes?.src, baseUrl)
    || srcsetImageSource(attributes?.srcset, baseUrl);
}

export function cleanAttributes(tag, attributes, baseUrl) {
  const sourceAttributes = attributes && typeof attributes === "object" ? attributes : {};
  const clean = {};
  for (const [name, value] of Object.entries(sourceAttributes)) {
    if (["alt", "title"].includes(name)) clean[name] = String(value);
    if (tag === "img" && ["width", "height"].includes(name) && imageDimension(value)) clean[name] = String(imageDimension(value));
    if (["colspan", "rowspan", "start"].includes(name) && /^\d+$/.test(value)) clean[name] = value;
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
    // Prefer the original lazy-load source, otherwise preserve src semantics
    // and only fall back to the richest srcset candidate. The chosen value stays inert metadata until ArticleImage applies
    // the stricter media transport validation.
    const source = imageSource(sourceAttributes, baseUrl);
    if (source) clean["data-image-source"] = source;
  }
  return clean;
}
