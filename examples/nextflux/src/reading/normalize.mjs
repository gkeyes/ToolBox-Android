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
