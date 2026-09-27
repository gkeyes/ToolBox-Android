import { DomUtils, parseDocument } from "htmlparser2";

const compact = (value) => String(value || "").replace(/\s+/g, " ").trim();
const normalizeTitle = (value) => compact(value)
  .toLocaleLowerCase()
  .replace(/[\s\u3000"'“”‘’《》〈〉【】\[\]（）(){}：:，,。.!！?？·•—–_-]+/gu, "");

function textOf(html) {
  const document = parseDocument(`<div>${html}</div>`, { decodeEntities: true });
  return compact(DomUtils.textContent(document));
}

function sameTitle(left, right) {
  const a = normalizeTitle(left);
  const b = normalizeTitle(right);
  return a.length >= 4 && a === b;
}

function telegramHost(baseUrl) {
  try {
    const host = new URL(baseUrl).hostname.toLowerCase();
    return host === "t.me" || host.endsWith(".t.me") ||
      host === "telegram.me" || host.endsWith(".telegram.me");
  } catch {
    return false;
  }
}

function splitMessageParagraph(innerHtml) {
  return String(innerHtml || "")
    .split(/(?:<br\s*\/?>\s*){2,}/giu)
    .map((segment) => segment.trim())
    .filter((segment) => textOf(segment));
}

export function matchesTelegramArticle({ baseUrl }) {
  return telegramHost(baseUrl);
}

export function adaptTelegramArticle({ html, title }) {
  const source = String(html || "");
  const document = parseDocument(source, { decodeEntities: true });
  const paragraphs = DomUtils.findAll((node) => node?.name === "p", document.children || []);

  let target = null;
  let segments = null;
  for (const paragraph of paragraphs) {
    const inner = DomUtils.getInnerHTML(paragraph);
    const candidate = splitMessageParagraph(inner);
    if (candidate.length < 2) continue;
    if (compact(DomUtils.textContent(paragraph)).length < 24) continue;
    target = paragraph;
    segments = candidate;
    break;
  }

  if (!target || !segments) return { html: source, changed: false };

  if (title && segments.length > 1 && sameTitle(textOf(segments[0]), title)) {
    segments = segments.slice(1);
  }
  if (!segments.length) return { html: source, changed: false };

  const canonical = segments.map((segment) => `<p>${segment}</p>`).join("");
  const serialized = DomUtils.getInnerHTML(document);
  const original = DomUtils.getOuterHTML(target);
  const index = serialized.indexOf(original);
  if (index < 0) return { html: source, changed: false };

  return {
    html: serialized.slice(0, index) + canonical + serialized.slice(index + original.length),
    changed: true,
  };
}
