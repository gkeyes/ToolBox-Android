/**
 * Font Loader - 按需加载字体
 * 参考 shadcn/ui 的字体系统设计
 * 字体来源：Google Fonts 官方
 */

// 字体分类配置
export const FONT_CATEGORIES = {
  sans: {
    label: "Sans",
    fonts: [
      {
        name: "Inter",
        value: "Inter",
        url: "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Geist",
        value: "Geist",
        url: "https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Nunito Sans",
        value: "Nunito Sans",
        url: "https://fonts.googleapis.com/css2?family=Nunito+Sans:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Figtree",
        value: "Figtree",
        url: "https://fonts.googleapis.com/css2?family=Figtree:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "DM Sans",
        value: "DM Sans",
        url: "https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Manrope",
        value: "Manrope",
        url: "https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Outfit",
        value: "Outfit",
        url: "https://fonts.googleapis.com/css2?family=Outfit:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Space Grotesk",
        value: "Space Grotesk",
        url: "https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Montserrat",
        value: "Montserrat",
        url: "https://fonts.googleapis.com/css2?family=Montserrat:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Instrument Sans",
        value: "Instrument Sans",
        url: "https://fonts.googleapis.com/css2?family=Instrument+Sans:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
    ],
  },
  serif: {
    label: "Serif",
    fonts: [
      {
        name: "Noto Serif",
        value: "Noto Serif",
        url: "https://fonts.googleapis.com/css2?family=Noto+Serif:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Merriweather",
        value: "Merriweather",
        url: "https://fonts.googleapis.com/css2?family=Merriweather:wght@400;500;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Lora",
        value: "Lora",
        url: "https://fonts.googleapis.com/css2?family=Lora:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Playfair Display",
        value: "Playfair Display",
        url: "https://fonts.googleapis.com/css2?family=Playfair+Display:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "EB Garamond",
        value: "EB Garamond",
        url: "https://fonts.googleapis.com/css2?family=EB+Garamond:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "Instrument Serif",
        value: "Instrument Serif",
        url: "https://fonts.googleapis.com/css2?family=Instrument+Serif:wght@400&display=swap",
        preview: "Aa",
      },
    ],
  },
  mono: {
    label: "Mono",
    fonts: [
      {
        name: "Geist Mono",
        value: "Geist Mono",
        url: "https://fonts.googleapis.com/css2?family=Geist+Mono:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
      {
        name: "JetBrains Mono",
        value: "JetBrains Mono",
        url: "https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600;700&display=swap",
        preview: "Aa",
      },
    ],
  },
  chinese: {
    label: "中文字体",
    fonts: [
      {
        name: "Noto Sans SC",
        value: "Noto Sans SC",
        url: "https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;700&display=swap",
        preview: "中文",
      },
      {
        name: "Noto Serif SC",
        value: "Noto Serif SC",
        url: "https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@400;500;700&display=swap",
        preview: "中文",
      },
      {
        name: "LXGW WenKai",
        value: "LXGW WenKai",
        url: "https://cdn.jsdelivr.net/npm/lxgw-wenkai-webfont@1.7.0/lxgwwenkai-regular.css",
        extraUrls: ["https://cdn.jsdelivr.net/npm/lxgw-wenkai-webfont@1.7.0/lxgwwenkai-bold.css"],
        preview: "中文",
      },
    ],
  },
};

// 系统字体（无需加载）
export const SYSTEM_FONTS = [
  { name: "System UI", value: "system-ui", preview: "Aa" },
  { name: "Sans Serif", value: "sans-serif", preview: "Aa" },
  { name: "Serif", value: "serif", preview: "Aa" },
  { name: "Monospace", value: "monospace", preview: "Aa" },
];

// 扁平化所有自定义字体配置（用于快速查找）
const ALL_CUSTOM_FONTS = Object.create(null);
Object.values(FONT_CATEGORIES).forEach((category) => {
  category.fonts.forEach((font) => {
    ALL_CUSTOM_FONTS[font.value] = font;
  });
});

// Remote CSS is parsed as data, never installed as a stylesheet. In particular,
// no article text or account credentials are sent to a font provider.
const MAX_FONT_BYTES = 4 * 1024 * 1024;
const MAX_CSS_BYTES = 512 * 1024;
const metadata = new Map();
const files = new Map();
const faces = new Map();
const loadedFonts = new Set();
const statuses = new Map();
const listeners = new Set();
const unicodeRanges = new WeakMap();
let requests = Promise.resolve();
let lastRequest = 0;
const MODERN_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

export function approvedFontUrl(value, base, kind = "font") {
  const url = new URL(value, base);
  if (url.protocol !== "https:" || url.port || url.username || url.password || url.hash) throw new Error("字体地址不受支持。");
  const jsdelivr = url.hostname === "cdn.jsdelivr.net" && /^\/npm\/lxgw-wenkai-webfont@1\.7\.0\//.test(url.pathname) && !url.search;
  const accepted = kind === "css"
    ? (url.hostname === "fonts.googleapis.com" && url.pathname === "/css2") || (jsdelivr && /\/lxgwwenkai-(regular|bold)\.css$/.test(url.pathname))
    : (url.hostname === "fonts.gstatic.com" && url.pathname.startsWith("/s/")) || (jsdelivr && /\/files\/lxgwwenkai-(regular|bold)-subset-\d+\.woff2$/.test(url.pathname));
  if (!accepted) throw new Error("字体地址不受支持。");
  return url.href;
}

export function parseUnicodeRanges(value) {
  if (!value) return [[0, 0x10ffff]];
  return value.split(",").map((part) => {
    const match = part.trim().match(/^U\+([0-9a-f]{1,6}|[0-9a-f]{0,5}\?{1,6})(?:-([0-9a-f]{1,6}))?$/i);
    if (!match || (match[1].includes("?") && match[2])) throw new Error("字体字符范围无效。");
    const start = parseInt(match[1].replaceAll("?", "0"), 16);
    const end = parseInt(match[2] || match[1].replaceAll("?", "f"), 16);
    if (start > end || end > 0x10ffff) throw new Error("字体字符范围无效。");
    return [start, end];
  });
}

export function fontFaceMatchesText(face, text) {
  const ranges = parseUnicodeRanges(face.unicodeRange);
  return Array.from(text).some((char) => ranges.some(([start, end]) => char.codePointAt(0) >= start && char.codePointAt(0) <= end));
}

function fontFaceMatchesPoints(face, points) {
  let cached = unicodeRanges.get(face);
  if (!cached || cached.value !== face.unicodeRange) {
    cached = { value: face.unicodeRange, ranges: parseUnicodeRanges(face.unicodeRange) };
    unicodeRanges.set(face, cached);
  }
  // Binary search the bounded, deduplicated input once per range rather than
  // allocating/traversing the same text again for every font-face definition.
  return cached.ranges.some(([start, end]) => {
    let low = 0;
    let high = points.length;
    while (low < high) {
      const middle = (low + high) >>> 1;
      if (points[middle] < start) low = middle + 1;
      else high = middle;
    }
    return low < points.length && points[low] <= end;
  });
}

export function parseFontCss(css, base, family) {
  if (typeof css !== "string" || css.length > MAX_CSS_BYTES) throw new Error("字体样式过大或无效。");
  const result = [];
  for (const block of css.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/@font-face\s*\{([^}]+)\}/gi)) {
    const property = (name) => block[1].match(new RegExp(`(?:^|;)\\s*${name}\\s*:\\s*([^;]+)`, "i"))?.[1]?.trim();
    const cssFamily = property("font-family")?.replace(/^['"]|['"]$/g, "");
    if (cssFamily !== family) continue;
    const source = property("src")?.match(/url\(\s*(['"]?)([^\s)'";]+)\1\s*\)/i)?.[2];
    if (!source) continue;
    const weight = property("font-weight") || "400";
    const style = property("font-style") || "normal";
    if (!/^[1-9]\d{0,2}(?:\s+[1-9]\d{0,2})?$/.test(weight) || !/^(normal|italic)$/.test(style)) continue;
    const unicodeRange = property("unicode-range") || "U+0-10FFFF";
    parseUnicodeRanges(unicodeRange);
    result.push({ url: approvedFontUrl(source, base), weight, style, unicodeRange });
  }
  if (!result.length) throw new Error("字体服务未返回受支持的字体。");
  return result;
}

async function fontRequest(url, kind) {
  approvedFontUrl(url, undefined, kind);
  const pending = requests.catch(() => {}).then(async () => {
    const network = globalThis.window?.ToolBox?.network;
    if (!network?.request) throw new Error("请在 ToolBox 中加载在线字体。");
    // Font subsets share the host quota with article sync. Keep only one request
    // in flight and no more than one per second, including metadata requests.
    const delay = Math.max(0, 1000 - (Date.now() - lastRequest));
    if (delay) await new Promise((resolve) => setTimeout(resolve, delay));
    lastRequest = Date.now();
    let response;
    try {
      response = await network.request({ url, method: "GET", headers: { Accept: kind === "css" ? "text/css" : "font/woff2,font/woff,font/ttf,application/octet-stream", "User-Agent": MODERN_USER_AGENT }, timeoutMs: 30000, maxResponseBytes: kind === "css" ? MAX_CSS_BYTES : MAX_FONT_BYTES });
    } catch {
      throw new Error("在线字体加载失败，请检查网络权限后重试；当前使用系统字体。");
    }
    if (response.status < 200 || response.status >= 300) throw new Error(`在线字体加载失败（HTTP ${response.status}）；当前使用系统字体。`);
    return response;
  });
  requests = pending;
  return pending;
}

function readBytes(response, maxBytes) {
  if (response.bodyEncoding !== "base64" || typeof response.body !== "string" || response.body.length > Math.ceil(maxBytes / 3) * 4 || !/^[A-Za-z0-9+/]*={0,2}$/.test(response.body)) throw new Error("字体文件内容无效或超过大小限制。");
  const bytes = Uint8Array.from(atob(response.body), (char) => char.charCodeAt(0));
  if (!bytes.length || bytes.length > maxBytes) throw new Error("字体文件内容无效或超过大小限制。");
  return bytes;
}

function memoize(map, key, work) {
  if (!map.has(key)) map.set(key, work().catch((error) => { map.delete(key); throw error; }));
  return map.get(key);
}

function getMetadata(config) {
  return memoize(metadata, config.value, async () => {
    const result = [];
    for (const url of [config.url, ...(config.extraUrls || [])]) {
      const response = await fontRequest(url, "css");
      const css = response.bodyEncoding === "base64" ? new TextDecoder().decode(readBytes(response, MAX_CSS_BYTES)) : response.body;
      result.push(...parseFontCss(css, url, config.value));
    }
    return result;
  });
}

function checkCancelled(signal) {
  if (signal?.aborted) throw new DOMException("字体加载已取消。", "AbortError");
}

// The native request API has no per-request AbortSignal. Stop this consumer
// promptly; shared bounded downloads may finish, but cancelled readers cannot
// install a face or schedule another subset.
function waitForFontWork(work, signal) {
  if (!signal) return work;
  checkCancelled(signal);
  return new Promise((resolve, reject) => {
    const aborted = () => reject(new DOMException("字体加载已取消。", "AbortError"));
    signal.addEventListener("abort", aborted, { once: true });
    work.then((value) => {
      signal.removeEventListener("abort", aborted);
      resolve(value);
    }, (error) => {
      signal.removeEventListener("abort", aborted);
      reject(error);
    });
  });
}

async function loadFace(family, face, signal) {
  const key = JSON.stringify([family, face]);
  const work = memoize(faces, key, async () => {
    const source = await memoize(files, face.url, async () => {
      const response = await fontRequest(face.url, "font");
      const bytes = readBytes(response, MAX_FONT_BYTES);
      const signature = Array.from(bytes.slice(0, 4)).join(",");
      const mime = { "119,79,70,50": "font/woff2", "119,79,70,70": "font/woff", "0,1,0,0": "font/ttf", "79,84,84,79": "font/otf" }[signature];
      if (!mime) throw new Error("字体服务未返回有效的字体文件。");
      return `url(data:${mime};base64,${response.body})`;
    });
    const font = new FontFace(family, source, { weight: face.weight, style: face.style, unicodeRange: face.unicodeRange, display: "swap" });
    await font.load();
    return font;
  });
  const font = await waitForFontWork(work, signal);
  checkCancelled(signal);
  document.fonts.add(font);
  return font;
}

export function getFontStatus(family) { return statuses.get(family) || { loading: false, error: null }; }
export function subscribeFontStatus(listener) { listeners.add(listener); return () => listeners.delete(listener); }
function updateStatus(family, value) {
  statuses.set(family, value);
  for (const listener of listeners) listener(family, value);
}
const activeLoads = new Map();
export function isFontLoaded(family) { return SYSTEM_FONTS.some((font) => font.value === family) || loadedFonts.has(family); }

export async function loadFont(family, text = "Aa 中文", { signal } = {}) {
  checkCancelled(signal);
  if (SYSTEM_FONTS.some((font) => font.value === family)) return;
  const config = ALL_CUSTOM_FONTS[family];
  if (!config) throw new Error("所选字体不存在，请重新选择。");
  activeLoads.set(family, (activeLoads.get(family) || 0) + 1);
  updateStatus(family, { loading: true, error: null });
  try {
    const definitions = await waitForFontWork(getMetadata(config), signal);
    checkCancelled(signal);
    const points = [...new Set(Array.from(text, (char) => char.codePointAt(0)))].sort((left, right) => left - right);
    // Selection happens locally; the provider only sees fixed font asset URLs.
    for (let index = 0; index < definitions.length; index += 1) {
      if (index > 0 && index % 16 === 0) await new Promise((resolve) => setTimeout(resolve, 0));
      checkCancelled(signal);
      if (fontFaceMatchesPoints(definitions[index], points)) await loadFace(family, definitions[index], signal);
    }
    checkCancelled(signal);
    loadedFonts.add(family);
  } catch (error) {
    if (error.name !== "AbortError") updateStatus(family, { loading: false, error: error.message || "字体加载失败，当前使用系统字体。" });
    throw error;
  } finally {
    activeLoads.set(family, activeLoads.get(family) - 1);
    updateStatus(family, { ...getFontStatus(family), loading: activeLoads.get(family) > 0 });
  }
}

export async function loadFonts(families, text) {
  for (const family of new Set(families)) await loadFont(family, text);
}
export function preloadLikelyFont(family) { loadFont(family).catch(() => {}); }
export function getFontFamilyValue(family) {
  const category = Object.entries(FONT_CATEGORIES).find(([, item]) => item.fonts.some((font) => font.value === family))?.[0];
  if (category) return `"${family}", ${category === "serif" ? "Georgia, serif" : category === "mono" ? "ui-monospace, monospace" : "system-ui, sans-serif"}`;
  return SYSTEM_FONTS.some((font) => font.value === family) ? family : "system-ui";
}
export function getFontConfig(family) { return ALL_CUSTOM_FONTS[family] || null; }
export default { loadFont, loadFonts, isFontLoaded, preloadLikelyFont, getFontFamilyValue, getFontConfig, FONT_CATEGORIES, SYSTEM_FONTS };
