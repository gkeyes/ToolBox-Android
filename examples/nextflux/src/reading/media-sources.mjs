import { safeContentUrl } from "../toolbox/content.js";

export function isMediaPlaylist({ url, type = "" }) {
  return /(?:mpegurl|vnd\.apple\.mpegurl|dash\+xml)/i.test(type) || /\.(?:m3u8|mpd)(?:[?#]|$)/i.test(url || "");
}

export function isHlsPlaylist({ url, type = "" }) {
  return /mpegurl/i.test(type) || /\.m3u8(?:[?#]|$)/i.test(url || "");
}

export function mediaSourceCandidates(attributes = {}, sources = [], baseUrl) {
  const candidates = [];
  for (const source of [attributes, ...sources].slice(0, 33)) {
    const value = source?.src || source?.["data-src"] || source?.["data-lazy-src"] || source?.url;
    const url = safeContentUrl(value, /^\/(?:proxy\/|media\/v1\/)/.test(value || "") ? "https://miniflux.xiaochen.win/" : baseUrl);
    if (!url?.startsWith("https:") || candidates.some((item) => item.url === url)) continue;
    const type = typeof source.type === "string" ? source.type.trim().slice(0, 256) : "";
    candidates.push({ url, type });
  }
  // Native Blob playback handles direct media, whereas HLS/DASH needs a
  // streaming player. A playlist must not hide a later direct MP4 source.
  return [...candidates.filter((source) => !isMediaPlaylist(source)), ...candidates.filter(isMediaPlaylist)].slice(0, 32);
}
