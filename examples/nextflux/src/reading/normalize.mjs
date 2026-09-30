import { STANDALONE_NOISE_RE, PROTECTED_TAGS } from "./schema.mjs";

const compact=(v)=>String(v||"").replace(/\s+/g," ").trim();

export function createNormalizationPlan(analysis){
  return {restoreSourceBreaks:Boolean(analysis?.restoreSourceBreaks),removeStandaloneNoise:true};
}
export function shouldPreserveTextBreaks(text, protectedText=false, plan={}){
  return Boolean(plan.restoreSourceBreaks&&!protectedText&&/\r?\n/.test(String(text||""))&&/\S/.test(String(text||"")));
}
export function shouldRemoveStandaloneNoise({tag,text,textLength,hasMedia,plan}){
  if(!plan?.removeStandaloneNoise||hasMedia||PROTECTED_TAGS.has(tag))return false;
  const value=compact(text);
  return textLength>0&&textLength<=24&&STANDALONE_NOISE_RE.test(value);
}

export function normalizeParagraphLeadingText(text,{started=false,protectedText=false}={}){
  const value=String(text||"");
  return !started&&!protectedText ? value.replace(/^[\s\u00a0\u3000]+/u,"") : value;
}

export function hasSentenceTerminal(text){
  return /[。！？!?]|\.(?=\s|$|["'”’」』】）])/u.test(String(text||""));
}

function isEditorialMetadata(text, textLength, linkTextLength) {
  const value=compact(text);
  if(textLength>96)return false;
  if(textLength>0&&linkTextLength/textLength>=.8)return true;
  if(linkTextLength>0&&/(?:是.{0,48}(?:记者|编辑|作者)|为.{0,48}(?:撰稿|撰写|报道))/u.test(value))return true;
  return /^(?:翻译|译者|来源|作者|撰文|编辑|责任编辑|校对|摄影|图源)[：:]|^点击(?:查看|阅读)(?:本文|原文|英文版|全文|详情)|(?:对本文有报道贡献|为本文提供报道)[。.!！]?$/u.test(value);
}

export function paragraphPresentation({tag,protectedText=false,hasSentence=false,role,text,textLength=0,linkTextLength=0}={}){
  if(tag!=="p"||protectedText||!hasSentence||role||isEditorialMetadata(text,textLength,linkTextLength))return null;
  return {indent:true};
}

const INLINE_SEMANTIC_TAGS = new Set(["span","b","strong","em","i","small"]);
const EMPHASIS_TAGS = new Set(["b","strong"]);
const STRUCTURAL_PARENT_TAGS = new Set(["div","article","section","main"]);
const prosePunctuation=/[。！？!?；;，,：:]$/u;

function isStructuralSectionCandidate(item){
  if(!item || !["div","span"].includes(item.tag)) return false;
  if(item.hasMedia || item.hasBlockChild || item.textLength<1 || item.textLength>32) return false;
  const children=(item.childElements||[]).filter(child=>child?.id || child?.tag);
  if(children.length!==1) return false;
  const only=children[0];
  if(!EMPHASIS_TAGS.has(only.tag)) return false;
  const own=compact(item.text);
  const emphasized=compact(only.text);
  return Boolean(own && emphasized && emphasized.length>=Math.max(1,Math.floor(own.length*.8)));
}

function hasActiveSectionBefore(parentChildElements=[], siblingIndex=-1){
  if(siblingIndex<=0) return false;
  for(let i=siblingIndex-1;i>=0;i-=1){
    const sibling=parentChildElements[i];
    if(isStructuralSectionCandidate(sibling)) return true;
    const value=compact(sibling?.text);
    if((sibling?.textLength||0)>120 || prosePunctuation.test(value)) return false;
  }
  return false;
}

export function semanticizeElement({
  tag,textLength,text,childElements,parentTag,parentChildElements,
  parentTextLength,hasBlockChild,hasMedia,siblingIndex,
} = {}) {
  const value=compact(text);
  if (!value || hasMedia || hasBlockChild) return null;

  const siblings=parentChildElements||[];

  // Generic structural heading detection: a short generic block whose visible
  // content is almost entirely a single strong/b element, among repeated sibling
  // blocks. No site names, menu words or article-specific text are involved.
  if (STRUCTURAL_PARENT_TAGS.has(parentTag) && isStructuralSectionCandidate({
    tag,textLength,text:value,childElements,hasBlockChild,hasMedia,
  })) {
    const siblingBlocks=siblings.filter(item=>item.tag==="div"||item.tag==="span");
    if(siblingBlocks.length>=3) return {tag:"h3",role:"semantic-section"};
  }

  // Inline visual rows are common in card/list-like source markup.
  const inlineChildren=siblings.filter((item)=>INLINE_SEMANTIC_TAGS.has(item.tag));
  if (tag==="span" && STRUCTURAL_PARENT_TAGS.has(parentTag) && inlineChildren.length>=2 &&
      textLength>=2 && textLength<=96 && value.length<=96) {
    return {tag:"div",role:"semantic-line"};
  }

  // Generic div-based rows are only promoted while inside a structurally
  // detected section. This prevents ordinary short <div> paragraphs elsewhere
  // in an article from being reformatted merely because they are siblings.
  const siblingBlocks=siblings.filter((item)=>item.tag==="div");
  if (tag==="div" && STRUCTURAL_PARENT_TAGS.has(parentTag) && siblingBlocks.length>=3 &&
      hasActiveSectionBefore(siblings,siblingIndex) &&
      textLength>=2 && textLength<=96 && value.length<=96 &&
      !prosePunctuation.test(value)) {
    return {tag:"div",role:"semantic-line"};
  }
  return null;
}
