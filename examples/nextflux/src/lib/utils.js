import { clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs) {
  return twMerge(clsx(inputs));
}

export function extractFirstImage(article) {
  if (!article?.content) return null;

  // 检查附件中的图片
  if (article.enclosures?.length > 0) {
    const imgEnclosure = article.enclosures.find((enclosure) =>
      enclosure.mime_type?.startsWith("image/"),
    );
    if (imgEnclosure?.url) {
      return imgEnclosure.url;
    }
  }

  // 如果附件中没有图片，则从内容中查找
  const template = document.createElement("template");
  template.innerHTML = article.content;
  const img = template.content.querySelector("img");
  const src = img?.getAttribute("src");
  if (!src) return null;
  try { return new URL(src, src.startsWith("/proxy/") ? "https://miniflux.xiaochen.win" : article.url).href; } catch { return null; }
}

export function getFontSizeClass(fontSize) {
  // fontSize 参数单位为 px
  if (fontSize === 14) {
    return "prose-sm"; // 14px
  } else if (fontSize === 16) {
    return "prose-base"; // 16px (默认)
  } else if (fontSize === 18) {
    return "prose-lg"; // 18px
  } else if (fontSize === 20) {
    return "prose-xl"; // 20px
  } else {
    return "prose-2xl"; // 24px
  }
}

// Templates are inert: even malformed article HTML cannot start image loads here.
export function cleanTitle(html) {
  if (!html) return "";
  const template = document.createElement("template");
  template.innerHTML = String(html);
  template.content.querySelectorAll("script, style, iframe, object, embed, svg, math, template").forEach(node => node.remove());
  return (template.content.textContent || "").replace(/\s+/g, " ").trim();
}
export const extractTextFromHtml = cleanTitle;

// 获取链接的hostname
export function getHostname(href) {
  try {
    return new URL(href).hostname;
  } catch {
    return href;
  }
}
