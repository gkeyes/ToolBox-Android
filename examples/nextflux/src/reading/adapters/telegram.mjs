import { DomUtils, parseDocument } from "htmlparser2";

const compact = (value) => String(value || "").replace(/\s+/g, " ").trim();
const normalizeTitle = (value) => compact(value)
  .toLocaleLowerCase()
  .replace(/^[^\p{L}\p{N}]+/u, "")
  .replace(/[\s\u3000"'“”‘’《》〈〉【】\[\]（）(){}：:，,。.!！?？·•—–_-]+/gu, "");

function textOf(html) {
  const document = parseDocument(`<div>${html}</div>`, { decodeEntities: true });
  return compact(DomUtils.textContent(document));
}

function sameTitle(left, right) {
  const a = normalizeTitle(left);
  const b = normalizeTitle(right);
  return a.length >= 2 && a === b;
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

function messageLines(paragraph) {
  const lines = [""];
  for (const child of paragraph.children || []) {
    if (child.name === "br") lines.push("");
    else lines[lines.length - 1] += DomUtils.getOuterHTML(child);
  }
  return lines.map((html) => ({ html: html.trim(), text: textOf(html) }));
}

function lineRole(text) {
  if (/^(?:[•●▪◦*+-]\s+|\d{1,2}[.、)]\s*|\p{Extended_Pictographic})/u.test(text)) return "list";
  if (/^(?:来源|致谢|翻译|编辑|投稿|订阅|关注)[：:\s]|^@|^——\s*(?:来源|作者)/u.test(text)) return "meta";
  return "prose";
}

function endsSentence(text) {
  return /[。！？!?.](?:["'”’」』】）]*)$/u.test(text);
}

function joinLine(previous, next) {
  return /[a-z0-9]$/iu.test(previous) && /^[a-z0-9]/iu.test(next) ? " " : "";
}

function continuationSeparator(previous, next) {
  // A comma-led continuation is one sentence. Other single breaks may be
  // intentional rows, so preserve their visual boundary.
  return /[，,；;、]$/u.test(previous) ? joinLine(previous, next) : "<br>";
}

function composeParagraphs(lines) {
  const blocks = [];
  let current = null;
  const flush = () => { if (current) blocks.push(current); current = null; };
  for (const line of lines) {
    if (!line.text) { flush(); continue; }
    const role = lineRole(line.text);
    if (current && (role !== "prose" || current.role !== "prose" || endsSentence(current.lastText))) flush();
    if (!current) current = { html: line.html, role, lastText: line.text };
    else {
      current.html += continuationSeparator(current.lastText, line.text) + line.html;
      current.lastText = line.text;
    }
  }
  flush();
  return blocks.map(({ html, role }) => `<p${role === "prose" ? "" : ` data-reading-role="${role}"`}>${html}</p>`).join("");
}

export function matchesTelegramArticle({ baseUrl }) {
  return telegramHost(baseUrl);
}

export function adaptTelegramArticle({ html, title }) {
  const source = String(html || "");
  const document = parseDocument(source, { decodeEntities: true });
  const paragraphs = DomUtils.findAll((node) => node?.name === "p", document.children || []);

  let target = null;
  let lines = null;
  for (const paragraph of paragraphs) {
    const candidate = messageLines(paragraph);
    if (candidate.length < 2 || candidate.filter((line) => line.text).length < 2) continue;
    target = paragraph;
    lines = candidate;
    break;
  }

  if (!target || !lines) return { html: source, changed: false };

  if (title && lines.length > 1 && sameTitle(lines[0].text, title)) {
    lines = lines.slice(1);
    if (/^[-=~_]{3,}$/u.test(lines[0]?.text || "")) lines = lines.slice(1);
  }
  if (!lines.some((line) => line.text)) return { html: source, changed: false };

  const canonical = composeParagraphs(lines);
  const serialized = DomUtils.getInnerHTML(document);
  const original = DomUtils.getOuterHTML(target);
  const index = serialized.indexOf(original);
  if (index < 0) return { html: source, changed: false };

  return {
    html: serialized.slice(0, index) + canonical + serialized.slice(index + original.length),
    changed: true,
  };
}
