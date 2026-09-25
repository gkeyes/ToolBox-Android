import Defuddle from "defuddle";

const STRUCTURAL_SELECTOR = "p,h1,h2,h3,h4,h5,h6,ul,ol,li,figure,figcaption,blockquote,pre,table";
const NOISE_SELECTOR = [
  "script","style","template","noscript","form","button","input","select","textarea",
  "[hidden]","[aria-hidden='true']",
  "[class*='advert']","[class*=' ad-']","[class^='ad-']","[id*='advert']","[id^='ad-']",
  "[class*='newsletter']","[class*='subscribe']","[class*='recommend']","[class*='related']",
].join(",");

function structureCount(element) {
  return element ? element.querySelectorAll(STRUCTURAL_SELECTOR).length : 0;
}

function textLength(element) {
  return String(element?.textContent || "").replace(/\s+/g, " ").trim().length;
}

function cleanLocatedRoot(root) {
  const clone = root.cloneNode(true);
  for (const node of clone.querySelectorAll(NOISE_SELECTOR)) node.remove();
  for (const node of clone.querySelectorAll("p,div,span")) {
    const text = String(node.textContent || "").replace(/\s+/g, " ").trim();
    if (/^(?:广告|廣告|advertisement|sponsored)$/i.test(text) && !node.querySelector("img,video,audio")) node.remove();
  }
  return clone.innerHTML.trim();
}

export function extractWithDefuddle({html,url}){
  if(typeof DOMParser!=="function")throw new Error("当前环境不支持网页结构解析。");
  const original=new DOMParser().parseFromString(String(html||""),"text/html");
  // Defuddle gets its own DOM because extraction mutates the document.
  const working=new DOMParser().parseFromString(String(html||""),"text/html");
  const result=new Defuddle(working,{
    url,
    useAsync:false,
    removeHiddenElements:false,
    standardize:true,
    debug:true,
  }).parse();
  const standardized=String(result?.content||"").trim();
  const selector=String(result?.debug?.contentSelector||"").trim();
  let located=null;
  try { if(selector) located=original.querySelector(selector); } catch {}

  // Defuddle is primarily our locator. If the untouched source root contains
  // materially richer semantic structure and comparable text, preserve that
  // original DOM instead of accepting a flattened standardized rewrite.
  const locatedChars=textLength(located);
  const standardizedDoc=new DOMParser().parseFromString(`<main>${standardized}</main>`,"text/html");
  const standardizedRoot=standardizedDoc.querySelector("main");
  const sourceStructure=structureCount(located);
  const standardizedStructure=structureCount(standardizedRoot);
  const standardizedChars=textLength(standardizedRoot);
  const sourceCoverage=standardizedChars ? locatedChars / standardizedChars : (locatedChars ? 1 : 0);
  const useSource=Boolean(located && locatedChars >= 160 && sourceCoverage >= .72 &&
    (sourceStructure >= standardizedStructure + 2 || (standardizedStructure <= 2 && sourceStructure >= 3)));

  const content=useSource ? cleanLocatedRoot(located) : standardized;
  if(!content)throw new Error("Defuddle 未提取到可用正文。");
  return {
    source: useSource ? "defuddle-source-dom" : "defuddle",
    content,
    metadata:{
      title:result.title||"", author:result.author||"", language:result.language||"",
      selector, extractionMode:useSource?"source-dom":"standardized",
      sourceStructure, standardizedStructure, sourceCoverage,
    }
  };
}
