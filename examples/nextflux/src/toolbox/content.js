import { imageDimension } from "./imageDimensions.js";

import { ALLOWED_TAGS, DROP_CONTENT } from "../reading/schema.mjs";
export { ALLOWED_TAGS, DROP_CONTENT };

export function safeContentUrl(value, baseUrl) {
  if (typeof value !== "string" || !value || Array.from(value).some((char) => char.codePointAt(0) <= 32 || char.codePointAt(0) === 127)) return null;
  try {
    const url = new URL(value, baseUrl || "https://miniflux.xiaochen.win/");
    return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

export function cleanAttributes(tag, attributes, baseUrl) {
  const clean = {};
  for (const [name, value] of Object.entries(attributes)) {
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
    if (name === "src" && tag === "img") {
      // Source is metadata only: renamed until ArticleImage validates and resolves it.
      if (/^data:image\/(png|jpeg|gif|webp|avif|bmp|x-icon);base64,/i.test(value)) clean["data-image-source"] = value;
      else {
        const url = safeContentUrl(value, value.startsWith("/proxy/") ? "https://miniflux.xiaochen.win/" : baseUrl);
        if (url) clean["data-image-source"] = url;
      }
    }
  }
  return clean;
}
