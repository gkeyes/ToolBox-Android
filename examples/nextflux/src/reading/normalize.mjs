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
const SEMANTIC_ITEM_RE = /^(?:第[一二三四五六七八九十百]+道菜|前菜|主菜|甜点|甜點|饮品|飲品|表演|菜单|菜單|配料|配料表|ingredients?|dessert|appetizer|main course|performance)$/i;

export function semanticizeElement({tag,textLength,text,childElements,parentTag,parentChildElements,parentTextLength,hasBlockChild,hasMedia} = {}) {
  const value=compact(text);
  if (!value || hasMedia || hasBlockChild) return null;
  // A common editorial pattern uses sibling spans as visual lines inside a div.
  // Preserve ordinary inline prose; only promote short, standalone siblings
  // when the parent itself is a generic container with multiple inline children.
  const inlineChildren=(parentChildElements||[]).filter((item)=>INLINE_SEMANTIC_TAGS.has(item.tag));
  if (tag==="span" && parentTag==="div" && inlineChildren.length>=2 &&
      textLength>=2 && textLength<=96 && value.length<=96) {
    return {tag:"div",role:"semantic-line"};
  }
  // Menu/section labels are high-confidence headings when they are standalone
  // text nodes, not ordinary prose.
  if ((tag==="div"||tag==="span") && parentTag==="div" &&
      SEMANTIC_ITEM_RE.test(value) && textLength<=24) {
    return {tag:"h3",role:"semantic-section"};
  }
  return null;
}
