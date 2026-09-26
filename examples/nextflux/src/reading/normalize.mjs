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

const INLINE_SEMANTIC_TAGS = new Set(["span","b","strong","em","i","small"]);
const EMPHASIS_TAGS = new Set(["b","strong"]);
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
  if (parentTag==="div" && isStructuralSectionCandidate({
    tag,textLength,text:value,childElements,hasBlockChild,hasMedia,
  })) {
    const siblingBlocks=siblings.filter(item=>item.tag==="div"||item.tag==="span");
    if(siblingBlocks.length>=3) return {tag:"h3",role:"semantic-section"};
  }

  // Inline visual rows are common in card/list-like source markup.
  const inlineChildren=siblings.filter((item)=>INLINE_SEMANTIC_TAGS.has(item.tag));
  if (tag==="span" && parentTag==="div" && inlineChildren.length>=2 &&
      textLength>=2 && textLength<=96 && value.length<=96) {
    return {tag:"div",role:"semantic-line"};
  }

  // Generic div-based rows are only promoted while inside a structurally
  // detected section. This prevents ordinary short <div> paragraphs elsewhere
  // in an article from being reformatted merely because they are siblings.
  const siblingBlocks=siblings.filter((item)=>item.tag==="div");
  if (tag==="div" && parentTag==="div" && siblingBlocks.length>=3 &&
      hasActiveSectionBefore(siblings,siblingIndex) &&
      textLength>=2 && textLength<=96 && value.length<=96 &&
      !prosePunctuation.test(value)) {
    return {tag:"div",role:"semantic-line"};
  }
  return null;
}
