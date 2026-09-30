// DW's rich-text renderer maps MASTER_* to 90X (900–906), FREE_IMAGE to
// format 7 and LEGACY to format 4. Its separate hero images use 60X instead.
// Only resolve the public DW template; other sites' templates stay inert.
export function dwImageSources(attributes, articleUrl) {
  try {
    const page = new URL(articleUrl);
    if (page.hostname !== "dw.com" && !page.hostname.endsWith(".dw.com")) return [];
    const template = attributes["data-url"], format = attributes["data-format"];
    if (typeof template !== "string" || typeof format !== "string" || !format) return [];
    const match = template.match(/^https:\/\/static\.dw\.com\/image\/(\d+)_\$\{formatId\}\.(?:jpg|webp)$/);
    if (!match || (attributes["data-id"] && attributes["data-id"] !== match[1])) return [];
    const ids = format.includes("LEGACY") ? [4] : format === "FREE_IMAGE" ? [7] : [906, 905, 904, 903, 902, 901, 900];
    return ids.map((id) => template.replace("${formatId}", String(id)));
  } catch { return []; }
}
