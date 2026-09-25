import { Parser } from "htmlparser2";

const DROP = new Set(["script","style","object","embed","svg","math","template","form","input","button","textarea","select","option","meta","link","base"]);
const PROTECTED = new Set(["pre","code","table","thead","tbody","tfoot","tr","th","td","ul","ol","li","blockquote"]);
const EMPHASIS = new Set(["strong","b","em"]);
const BLOCKISH = new Set(["p","div","section","article","main","aside","figcaption","caption","dd","dt"]);
const HEADING = /^h[1-6]$/;
const NOISE = /^(?:广告|廣告|advertisement|ad(?:vertisement)?|sponsored)$/i;
const SECTION_PREFIX = /^(?:第[一二三四五六七八九十百零〇两兩\d]+(?:道菜|部分|部份|章|节|節|项|項|步|点|點)|[一二三四五六七八九十]+[、.．]|菜单|菜單|甜点|甜點|表演|作者|摄影|攝影|图片|圖片|相关阅读|相關閱讀|更多阅读|更多閱讀)/;

function compact(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function visibleLength(text) {
  return compact(text).length;
}

function meaningfulBreaks(text) {
  const lines = String(text || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  return Math.max(0, lines.length - 1);
}

function sectionPrefix(text) {
  return compact(text).match(SECTION_PREFIX)?.[0] || "";
}

export function isStandaloneNoiseText(text, length = visibleLength(text)) {
  const value = compact(text);
  return length > 0 && length <= 24 && NOISE.test(value);
}

export function isRecoverableSectionText(text, length = visibleLength(text)) {
  if (length <= 0 || length > 160) return false;
  return Boolean(sectionPrefix(text));
}

export function analyzeReadingStructure(html) {
  const source = String(html || "");
  const metrics = {
    textChars: 0,
    paragraphs: 0,
    breaks: 0,
    listItems: 0,
    headings: 0,
    divs: 0,
    textualDivs: 0,
    blockquotes: 0,
    tables: 0,
    preformatted: 0,
    figures: 0,
    captions: 0,
    emphasis: 0,
    meaningfulNewlines: 0,
    inlineSectionCues: 0,
    standaloneNoiseBlocks: 0,
  };
  const stack = [{ tag: null, blocked: false, protected: false, textLength: 0, preview: "" }];
  const parser = new Parser({
    onopentag(tag) {
      const parent = stack.at(-1);
      const blocked = parent.blocked || DROP.has(tag);
      const protectedText = parent.protected || PROTECTED.has(tag);
      stack.push({ tag, blocked, protected: protectedText, textLength: 0, preview: "" });
      if (blocked) return;
      if (tag === "p") metrics.paragraphs += 1;
      else if (tag === "br") metrics.breaks += 1;
      else if (tag === "li") metrics.listItems += 1;
      else if (HEADING.test(tag)) metrics.headings += 1;
      else if (tag === "div") metrics.divs += 1;
      else if (tag === "blockquote") metrics.blockquotes += 1;
      else if (tag === "table") metrics.tables += 1;
      else if (tag === "pre") metrics.preformatted += 1;
      else if (tag === "figure") metrics.figures += 1;
      else if (tag === "figcaption") metrics.captions += 1;
      if (EMPHASIS.has(tag)) metrics.emphasis += 1;
    },
    ontext(text) {
      const leaf = stack.at(-1);
      if (leaf.blocked || !text) return;
      metrics.textChars += visibleLength(text);
      if (!leaf.protected) metrics.meaningfulNewlines += meaningfulBreaks(text);
      const normalized = compact(text);
      if (!normalized) return;
      for (let index = 1; index < stack.length; index += 1) {
        const entry = stack[index];
        if (entry.blocked) continue;
        entry.textLength += normalized.length;
        if (entry.preview.length < 192) {
          entry.preview = compact(entry.preview ? `${entry.preview} ${normalized}` : normalized).slice(0, 192);
        }
      }
    },
    onclosetag() {
      if (stack.length <= 1) return;
      const entry = stack.pop();
      if (entry.blocked) return;
      if (entry.tag === "div" && entry.textLength >= 40) metrics.textualDivs += 1;
      if (EMPHASIS.has(entry.tag) && isRecoverableSectionText(entry.preview, entry.textLength)) metrics.inlineSectionCues += 1;
      if (BLOCKISH.has(entry.tag) && isStandaloneNoiseText(entry.preview, entry.textLength)) metrics.standaloneNoiseBlocks += 1;
    },
  }, { decodeEntities: true, lowerCaseTags: true, lowerCaseAttributeNames: true, xmlMode: false });
  parser.end(source);

  const semanticBlocks = metrics.paragraphs + metrics.breaks + metrics.listItems + metrics.headings +
    metrics.blockquotes + metrics.tables + metrics.preformatted + metrics.figures + metrics.captions;
  const averageDivText = metrics.textualDivs ? metrics.textChars / metrics.textualDivs : metrics.textChars;
  const structureSparse = semanticBlocks <= 2 && (metrics.textualDivs <= 3 || averageDivText >= 360);
  const restoreSourceBreaks = metrics.textChars >= 500 && metrics.meaningfulNewlines >= 3 && structureSparse;
  const recoverInlineSections = metrics.textChars >= 700 && structureSparse &&
    metrics.meaningfulNewlines < 3 && metrics.inlineSectionCues >= 2;
  const normalizationMode = restoreSourceBreaks ? "restore-lines" :
    recoverInlineSections ? "recover-inline-sections" : "preserve";
  return {
    ...metrics,
    semanticBlocks,
    averageDivText,
    structureSparse,
    restoreSourceBreaks,
    recoverInlineSections,
    normalizationMode,
    needsRepair: normalizationMode !== "preserve",
  };
}

export function createReadingNormalizationPlan(html) {
  const metrics = analyzeReadingStructure(html);
  return {
    ...metrics,
    removeStandaloneNoise: true,
  };
}

export function shouldPreserveTextBreaks(text, protectedText = false, plan = false) {
  const enabled = typeof plan === "object" ? plan.restoreSourceBreaks : Boolean(plan);
  if (!enabled || protectedText) return false;
  const value = String(text || "");
  return /\r?\n/.test(value) && /\S/.test(value);
}

export function shouldRecoverInlineSection({ tag, text, textLength, parentTag, plan }) {
  if (!plan?.recoverInlineSections || !EMPHASIS.has(tag)) return false;
  if (PROTECTED.has(parentTag)) return false;
  return isRecoverableSectionText(text, textLength);
}

export function shouldRemoveStandaloneNoise({ tag, text, textLength, hasMedia, plan }) {
  if (!plan?.removeStandaloneNoise || hasMedia || PROTECTED.has(tag)) return false;
  return isStandaloneNoiseText(text, textLength);
}
