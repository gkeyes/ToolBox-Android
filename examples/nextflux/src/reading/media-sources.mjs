import { imageSourceCandidates, safeContentUrl } from "../toolbox/content.js";
import { SERVER_BASE } from "../toolbox/server.mjs";

export function isMediaPlaylist({ url, type = "" }) {
  return /(?:mpegurl|vnd\.apple\.mpegurl|dash\+xml)/i.test(type) || /\.(?:m3u8|mpd)(?:[?#]|$)/i.test(url || "");
}

export function isHlsPlaylist({ url, type = "" }) {
  return /mpegurl/i.test(type) || /\.m3u8(?:[?#]|$)/i.test(url || "");
}

export function mediaPosterSources(attributes = {}, baseUrl) {
  return imageSourceCandidates({
    "data-image-candidates": JSON.stringify([
      attributes.poster, attributes["data-poster"], attributes["data-lazy-poster"],
    ].filter(Boolean)),
  }, baseUrl);
}

// The article's cover stands for its first video enclosure only; a second
// video showing the same picture would look like a repeat. Audio has none.
export function enclosurePosterSources(enclosure, enclosures = [], article = {}) {
  const firstVideo = enclosures.find((item) => !String(item?.mime_type || "").startsWith("audio/"));
  if (enclosure !== firstVideo) return [];
  return article?.coverSources?.length ? article.coverSources : [article?.coverUrl].filter(Boolean);
}

export function mediaSourceCandidates(attributes = {}, sources = [], baseUrl) {
  const candidates = [];
  for (const source of [attributes, ...sources].slice(0, 33)) {
    const value = source?.src || source?.["data-src"] || source?.["data-lazy-src"] || source?.url;
    const url = safeContentUrl(value, /^\/(?:proxy\/|media\/v1\/)/.test(value || "") ? SERVER_BASE : baseUrl);
    if (!url?.startsWith("https:") || candidates.some((item) => item.url === url)) continue;
    const type = typeof source.type === "string" ? source.type.trim().slice(0, 256) : "";
    candidates.push({ url, type });
  }
  // Native Blob playback handles direct media, whereas HLS/DASH needs a
  // streaming player. A playlist must not hide a later direct MP4 source.
  return [...candidates.filter((source) => !isMediaPlaylist(source)), ...candidates.filter(isMediaPlaylist)].slice(0, 32);
}
