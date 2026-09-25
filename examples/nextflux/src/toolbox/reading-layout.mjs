import { Parser } from "htmlparser2";

const DROP = new Set(["script","style","object","embed","svg","math","template","form","input","button","textarea","select","option","meta","link","base"]);
const PROTECTED = new Set(["pre","code","table","thead","tbody","tfoot","tr","th","td","ul","ol","li","blockquote"]);
const HEADING = /^h[1-6]$/;

function visibleLength(text) {
  return String(text || "").replace(/\s+/g, " ").trim().length;
}

function meaningfulBreaks(text) {
  const lines = String(text || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  return Math.max(0, lines.length - 1);
}

export function analyzeReadingLayout(html) {
  const source = String(html || "");
  const metrics = {
    textChars: 0,
    paragraphs: 0,
    breaks: 0,
    listItems: 0,
    headings: 0,
    divs: 0,
    blockquotes: 0,
    tables: 0,
    preformatted: 0,
    meaningfulNewlines: 0,
  };
  const stack = [{ blocked: false, protected: false }];
  const parser = new Parser({
    onopentag(tag) {
      const parent = stack.at(-1);
      const blocked = parent.blocked || DROP.has(tag);
      const protectedText = parent.protected || PROTECTED.has(tag);
      stack.push({ blocked, protected: protectedText });
      if (blocked) return;
      if (tag === "p") metrics.paragraphs += 1;
      else if (tag === "br") metrics.breaks += 1;
      else if (tag === "li") metrics.listItems += 1;
      else if (HEADING.test(tag)) metrics.headings += 1;
      else if (tag === "div") metrics.divs += 1;
      else if (tag === "blockquote") metrics.blockquotes += 1;
      else if (tag === "table") metrics.tables += 1;
      else if (tag === "pre") metrics.preformatted += 1;
    },
    ontext(text) {
      const parent = stack.at(-1);
      if (parent.blocked) return;
      metrics.textChars += visibleLength(text);
      if (!parent.protected) metrics.meaningfulNewlines += meaningfulBreaks(text);
    },
    onclosetag() { if (stack.length > 1) stack.pop(); },
  }, { decodeEntities: true, lowerCaseTags: true, lowerCaseAttributeNames: true, xmlMode: false });
  parser.end(source);

  const semanticBlocks = metrics.paragraphs + metrics.breaks + metrics.listItems + metrics.headings +
    metrics.blockquotes + metrics.tables + metrics.preformatted;
  const structureSparse = semanticBlocks <= 2 && metrics.divs <= 2;
  const needsRepair = metrics.textChars >= 500 && metrics.meaningfulNewlines >= 3 && structureSparse;
  return { ...metrics, semanticBlocks, structureSparse, needsRepair };
}

export function shouldPreserveTextBreaks(text, protectedText = false, repairEnabled = false) {
  if (!repairEnabled || protectedText) return false;
  const value = String(text || "");
  return /\r?\n/.test(value) && /\S/.test(value);
}
