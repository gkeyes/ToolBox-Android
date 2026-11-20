import { Parser } from "htmlparser2";
import { ALLOWED_TAGS, BLOCK_TAGS, BLOCKISH_TAGS, DROP_CONTENT, HEADING_RE, PROTECTED_TAGS, STANDALONE_NOISE_RE } from "./schema.mjs";
import { shouldRemoveStandaloneNoise } from "./normalize.mjs";
import { createTelegramOpeningTitle } from "./adapters/telegram-title.mjs";

const CHARACTER_SCRIPTS = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u;
const WORD_CHAR = /[\p{L}\p{N}]/u;
const VISIBLE_TITLE_TEXT = /[\p{L}\p{N}\p{S}]/u;
const ASCII_SENTENCE_MARK = /^[,.:;!?]$/;
const COMBINING_MARK = /\p{M}/u;
const MEDIA_TAGS = new Set(["img", "iframe", "audio", "video"]);
const WORD_BREAK_TAGS = new Set([...BLOCK_TAGS, "br", "hr", ...MEDIA_TAGS]);

// Keep word boundaries across inline markup and decoded entity callbacks.
function addWords(counter, text) {
  for (const character of text) {
    if (CHARACTER_SCRIPTS.test(character)) {
      counter.count++;
      counter.inWord = false;
    } else if (WORD_CHAR.test(character)) {
      if (!counter.inWord) counter.count++;
      counter.inWord = true;
    } else if (!COMBINING_MARK.test(character)) {
      counter.inWord = false;
    }
  }
}

const compact = (value) => String(value || "").replace(/\s+/g, " ").trim();
const meaningfulBreaks = (value) => Math.max(0, String(value || "").split(/\r?\n/).map(v=>v.trim()).filter(Boolean).length - 1);

function joinNormalizedText(before, after) {
  return before + (before.endsWith(" ") && after.startsWith(" ") ? after.slice(1) : after);
}

function truncatedTitlePrefix(headline) {
  const marker = headline.match(/(?:^|[^.．…])([.．…]+)$/u)?.[1];
  return marker && (marker.includes("…") || marker.length >= 3)
    ? headline.slice(0, -marker.length).trimEnd()
    : "";
}

export function analyzeArticle(html, { countWords = false, title = "", telegram = false } = {}) {
  const headline = compact(title);
  const prefix = countWords ? truncatedTitlePrefix(headline) : "";
  let openingText = prefix ? "" : null;
  const telegramTitle = countWords && telegram ? createTelegramOpeningTitle(prefix || headline) : null;
  let telegramOpening = telegramTitle ? "" : null;
  let titleTail = "", titleInBody = false;
  const matchTelegramOpening = (text, boundary = false) => {
    if (telegramOpening === null || titleInBody) return;
    if (telegramOpening === true) {
      if (!VISIBLE_TITLE_TEXT.test(text)) return;
      titleInBody = true;
      telegramOpening = null;
      return;
    }

    const opening = joinNormalizedText(telegramOpening, text).trimStart();
    if (!opening) return;
    const matched = telegramTitle.pattern.exec(opening);
    if (!matched) {
      telegramOpening = boundary || opening.length >= telegramTitle.limit ? null : opening;
      return;
    }

    const after = opening.slice(matched[0].length);
    const pendingBoundary = !after || ASCII_SENTENCE_MARK.test(after);
    const chineseContinuation = telegramTitle.endsWithHan && /^\p{Script=Han}/u.test(after);
    const hasBoundary = /^[ ，。！？；：、]/u.test(after) || /^[,.:;!?] /.test(after) ||
      telegramTitle.endsWithChinesePunctuation || chineseContinuation || (boundary && pendingBoundary);
    if (prefix && (!telegramTitle.standaloneLatinWord || hasBoundary)) {
      titleInBody = VISIBLE_TITLE_TEXT.test(after);
      telegramOpening = titleInBody ? null : true;
    } else {
      titleInBody = !prefix && hasBoundary;
      // Wait at an inline split. A dot may still be inside a version number.
      telegramOpening = !titleInBody && pendingBoundary ? opening : null;
    }
  };
  const words = { count: 0, inWord: false };
  const source=String(html||"");
  const metrics={
    textChars:0, paragraphs:0, breaks:0, listItems:0, headings:0, divs:0, textualDivs:0,
    blockquotes:0, tables:0, preformatted:0, figures:0, captions:0, images:0,
    links:0, linkTextChars:0, meaningfulNewlines:0, standaloneNoiseBlocks:0,
    largestBlockChars:0, topLevelTextChars:0,
  };
  const stack=[{tag:null,blocked:false,protected:false,textLength:0,preview:"",link:false}];
  const parser=new Parser({
    onopentag(tag, attrs){
      const parent=stack.at(-1);
      const blocked=parent.blocked||DROP_CONTENT.has(tag);
      const entry={tag,blocked,protected:parent.protected||PROTECTED_TAGS.has(tag),textLength:0,preview:"",link:parent.link||tag==="a"};
      if (countWords) {
        entry.skipWords = parent.skipWords || blocked || MEDIA_TAGS.has(tag) || tag === "head";
        entry.hasMedia = MEDIA_TAGS.has(tag);
        if (!parent.skipWords && WORD_BREAK_TAGS.has(tag)) words.inWord = false;
        const hidden = attrs.hidden != null || attrs["aria-hidden"] === "true";
        entry.skipTitle = entry.skipWords || parent.skipTitle || tag === "details" || hidden;
        if (openingText !== null) entry.openingText = openingText;
        if (telegramOpening !== null) entry.telegramOpening = telegramOpening;
        if (telegramOpening && WORD_BREAK_TAGS.has(tag) && !parent.skipTitle && !hidden) matchTelegramOpening("", true);
        if (openingText && openingText !== prefix && WORD_BREAK_TAGS.has(tag) && !parent.skipTitle && !hidden) openingText = null;
        if (WORD_BREAK_TAGS.has(tag) || entry.skipTitle) titleTail = "";
        entry.hadTitle = titleInBody;
        entry.startWords = words.count;
        entry.startedInWord = words.inWord;
      }
      stack.push(entry);
      if(blocked)return;
      if(tag==="p")metrics.paragraphs++;
      else if(tag==="br")metrics.breaks++;
      else if(tag==="li")metrics.listItems++;
      else if(HEADING_RE.test(tag))metrics.headings++;
      else if(tag==="div")metrics.divs++;
      else if(tag==="blockquote")metrics.blockquotes++;
      else if(tag==="table")metrics.tables++;
      else if(tag==="pre")metrics.preformatted++;
      else if(tag==="figure")metrics.figures++;
      else if(tag==="figcaption")metrics.captions++;
      else if(tag==="img")metrics.images++;
      else if(tag==="a")metrics.links++;
    },
    ontext(text){
      const leaf=stack.at(-1);
      if(leaf.blocked||!text)return;
      const normalizedText = text.replace(/\s+/g, " ");
      if (countWords && !leaf.skipWords) {
        addWords(words, text);
        if (headline && !titleInBody && !leaf.skipTitle) {
          // Inline markup and HTML entities can split a title between callbacks.
          // Carry only enough text for a match spanning the next callback.
          const candidate = joinNormalizedText(titleTail, normalizedText);
          titleInBody = candidate.includes(headline);
          titleTail = titleInBody ? "" : candidate.slice(Math.max(0, candidate.length - headline.length + 1));
          // Match the entire surviving prefix within the first visible run.
          // Once it matches, the continuation can begin on the next line.
          if (!titleInBody && openingText !== null) {
            const opening = joinNormalizedText(openingText, normalizedText).trimStart();
            if (prefix.startsWith(opening)) openingText = opening;
            else if (!opening.startsWith(prefix)) openingText = null;
            else if (VISIBLE_TITLE_TEXT.test(opening.slice(prefix.length))) {
              titleInBody = true;
              openingText = null;
            } else openingText = prefix;
          }
          if (telegramOpening !== null) matchTelegramOpening(normalizedText);
        }
      }
      const normalized=normalizedText.trim();
      const len=normalized.length;
      metrics.textChars+=len;
      if(stack.length===1)metrics.topLevelTextChars+=len;
      if(leaf.link)metrics.linkTextChars+=len;
      if(!leaf.protected)metrics.meaningfulNewlines+=meaningfulBreaks(text);
      if(!normalized)return;
      for(let i=1;i<stack.length;i++){
        const entry=stack[i];
        if(entry.blocked)continue;
        entry.textLength+=normalized.length;
        if(entry.preview.length<96)entry.preview=(entry.preview?entry.preview+" "+normalized:normalized).slice(0,96);
      }
    },
    onclosetag(){
      if(stack.length<=1)return;
      const entry=stack.pop();
      if (countWords) {
        if (entry.hasMedia) stack.at(-1).hasMedia = true;
        // Discard labels that the renderer removes, including nested formatting.
        if (!entry.skipWords && entry.textLength <= 24 && ALLOWED_TAGS.has(entry.tag) && shouldRemoveStandaloneNoise({
          tag: entry.tag, text: entry.preview, textLength: entry.textLength,
          hasMedia: entry.hasMedia, plan: { removeStandaloneNoise: true },
        })) {
          titleInBody = entry.hadTitle;
          titleTail = "";
          openingText = entry.openingText ?? null;
          telegramOpening = entry.telegramOpening ?? null;
          words.count = entry.startWords;
          words.inWord = entry.startedInWord;
        }
        if (openingText && openingText !== prefix && WORD_BREAK_TAGS.has(entry.tag) && !entry.skipTitle) openingText = null;
        if (telegramOpening && WORD_BREAK_TAGS.has(entry.tag) && !entry.skipTitle) matchTelegramOpening("", true);
        if (WORD_BREAK_TAGS.has(entry.tag) || entry.skipTitle) titleTail = "";
        if (!entry.skipWords && WORD_BREAK_TAGS.has(entry.tag)) words.inWord = false;
      }
      if(entry.blocked)return;
      if(entry.tag==="div"&&entry.textLength>=40)metrics.textualDivs++;
      if(BLOCKISH_TAGS.has(entry.tag)){
        metrics.largestBlockChars=Math.max(metrics.largestBlockChars,entry.textLength);
        if(entry.textLength>0&&entry.textLength<=24&&STANDALONE_NOISE_RE.test(compact(entry.preview)))metrics.standaloneNoiseBlocks++;
      }
    }
  },{decodeEntities:true,lowerCaseTags:true,lowerCaseAttributeNames:true,xmlMode:false});
  parser.end(source);
  if (telegramOpening) matchTelegramOpening("", true);
  if (countWords) {
    metrics.wordCount = words.count;
    metrics.titleInBody = titleInBody;
  }
  const semanticBlocks=metrics.paragraphs+metrics.breaks+metrics.listItems+metrics.headings+metrics.blockquotes+metrics.tables+metrics.preformatted+metrics.figures+metrics.captions;
  const averageDivText=metrics.textualDivs?metrics.textChars/metrics.textualDivs:metrics.textChars;
  const linkDensity=metrics.textChars?metrics.linkTextChars/metrics.textChars:0;
  const largestBlockRatio=metrics.textChars?metrics.largestBlockChars/metrics.textChars:0;
  const topLevelTextRatio=metrics.textChars?metrics.topLevelTextChars/metrics.textChars:0;
  // A sanitizer can remove layout containers but preserve their text, leaving
  // large runs of article copy directly at the document root. That content is
  // text-complete but structurally flattened and should lose to an equivalent
  // source-DOM candidate when one is available.
  const flattenedRootText=metrics.textChars>=240&&metrics.topLevelTextChars>=120&&topLevelTextRatio>=.32;
  // Generic div wrappers are not semantic structure. A long article with almost
  // no paragraphs/headings/lists and one dominant block is sparse even when an
  // extractor emitted many nested divs.
  const structureSparse=semanticBlocks<=2&&(
    metrics.textualDivs<=3 || averageDivText>=360 ||
    (metrics.textChars>=500&&largestBlockRatio>=.82)
  );
  const restoreSourceBreaks=metrics.textChars>=500&&metrics.meaningfulNewlines>=3&&structureSparse;
  return {...metrics,semanticBlocks,averageDivText,structureSparse,restoreSourceBreaks,
    normalizationMode:restoreSourceBreaks?"restore-lines":"preserve",
    needsRepair:restoreSourceBreaks||flattenedRootText,linkDensity,largestBlockRatio,
    topLevelTextRatio,flattenedRootText};
}

export function scoreArticle(htmlOrMetrics) {
  const m=typeof htmlOrMetrics==="string"?analyzeArticle(htmlOrMetrics):htmlOrMetrics;
  let score=0;
  score+=Math.min(30,m.textChars/45);
  score+=Math.min(18,m.paragraphs*1.8);
  score+=Math.min(8,m.headings*2);
  score+=Math.min(8,m.listItems*.8);
  score+=Math.min(8,m.figures*2+m.captions*2);
  score+=Math.min(6,m.images*1.2);
  if(m.textChars>=800&&m.semanticBlocks>=4)score+=10;
  if(m.textChars>=500&&m.semanticBlocks<=2)score-=24;
  if(m.textChars>=500&&m.largestBlockRatio>.82)score-=15;
  if(m.textChars>=500&&m.paragraphs===0&&m.headings===0&&m.listItems===0)score-=12;
  if(m.linkDensity>.35)score-=18;
  if(m.flattenedRootText)score-=22;
  score-=Math.min(15,m.standaloneNoiseBlocks*5);
  if(m.needsRepair)score-=8;
  return Math.max(0,Math.min(100,Math.round(score)));
}

export function evaluateArticleCandidate(candidate) {
  const analysis=analyzeArticle(candidate.content);
  return {...candidate,analysis,score:scoreArticle(analysis)};
}

export function chooseBestArticleCandidate(candidates) {
  const valid=candidates.filter(c=>typeof c?.content==="string"&&c.content.trim());
  if(!valid.length)throw new Error("没有可用的全文候选。");
  const evaluated=valid.map(evaluateArticleCandidate);
  const maxChars=Math.max(...evaluated.map(c=>c.analysis.textChars));
  for(const item of evaluated){
    const coverage=maxChars?item.analysis.textChars/maxChars:1;
    item.coverage=coverage;
    if(maxChars>=800&&coverage<.55)item.score=Math.max(0,item.score-28);
    if(maxChars>=800&&coverage<.35)item.score=Math.max(0,item.score-20);
  }
  evaluated.sort((a,b)=>b.score-a.score||b.analysis.textChars-a.analysis.textChars);
  return {selected:evaluated[0],candidates:evaluated};
}
