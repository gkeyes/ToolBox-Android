const DROP_SELECTOR = [
  "script", "style", "iframe", "object", "embed", "svg", "math", "template",
  "noscript", "form", "button", "input", "textarea", "select", "option",
  "pre", "code", "table", "nav", "aside",
].join(",");

const BLOCK_TAGS = new Set([
  "ADDRESS", "ARTICLE", "BLOCKQUOTE", "DD", "DIV", "DL", "DT", "FIGCAPTION",
  "FIGURE", "FOOTER", "H1", "H2", "H3", "H4", "H5", "H6", "HEADER", "LI",
  "MAIN", "P", "SECTION", "SUMMARY",
]);

export function normalizeSpeechText(value) {
  return String(value || "")
    .replace(/\u00a0/g, " ")
    .replace(/[ \t\f\v]+/g, " ")
    .replace(/ *\n */g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .join("\n")
    .trim();
}

function appendNodeText(node, output) {
  if (!node) return;
  if (node.nodeType === Node.TEXT_NODE) {
    output.push(node.nodeValue || "");
    return;
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return;
  const tag = node.tagName;
  if (tag === "BR") {
    output.push("\n");
    return;
  }
  const block = BLOCK_TAGS.has(tag);
  if (block) output.push("\n");
  for (const child of node.childNodes) appendNodeText(child, output);
  if (block) output.push("\n");
}

export function htmlToSpeechText(html) {
  if (typeof document === "undefined") {
    return normalizeSpeechText(String(html || "").replace(/<[^>]+>/g, " "));
  }
  const template = document.createElement("template");
  template.innerHTML = String(html || "");
  template.content.querySelectorAll(DROP_SELECTOR).forEach((node) => node.remove());
  const output = [];
  for (const child of template.content.childNodes) appendNodeText(child, output);
  return normalizeSpeechText(output.join(""));
}

function sentenceUnits(paragraph) {
  const units = paragraph.match(/[^。！？!?；;]+[。！？!?；;]?/g);
  return (units || [paragraph]).map((part) => part.trim()).filter(Boolean);
}

function hardSplit(value, maxChars) {
  const parts = [];
  let rest = value.trim();
  while (rest.length > maxChars) {
    let cut = maxChars;
    const floor = Math.floor(maxChars * 0.55);
    for (const marker of ["，", ",", "、", "：", ":", " "]) {
      const index = rest.lastIndexOf(marker, maxChars);
      if (index >= floor) { cut = index + 1; break; }
    }
    parts.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest) parts.push(rest);
  return parts;
}

export function splitSpeechText(text, { targetChars = 720, maxChars = 1200 } = {}) {
  if (!Number.isInteger(targetChars) || !Number.isInteger(maxChars) || targetChars < 120 || maxChars < targetChars) {
    throw new TypeError("Invalid speech chunk limits");
  }
  const normalized = normalizeSpeechText(text);
  if (!normalized) return [];
  const units = [];
  for (const paragraph of normalized.split("\n")) {
    for (const sentence of sentenceUnits(paragraph)) {
      if (sentence.length > maxChars) units.push(...hardSplit(sentence, maxChars));
      else units.push(sentence);
    }
  }
  const chunks = [];
  let current = "";
  const flush = () => {
    const value = current.trim();
    if (value) chunks.push(value);
    current = "";
  };
  for (const unit of units) {
    if (!current) { current = unit; continue; }
    const candidate = `${current}${/^[，。！？!?；;：:、]/.test(unit) ? "" : " "}${unit}`;
    if (candidate.length <= targetChars || (current.length < targetChars * 0.45 && candidate.length <= maxChars)) {
      current = candidate;
    } else {
      flush();
      current = unit;
    }
  }
  flush();
  return chunks;
}

export function articleToSpeechText(article, { readTitle = true } = {}) {
  const body = htmlToSpeechText(article?.content || "");
  const title = normalizeSpeechText(article?.titleText ?? article?.title ?? "");
  if (!body && !title) return "";
  return normalizeSpeechText(readTitle && title ? `${title}\n${body}` : body);
}
