import { Parser } from "htmlparser2";

const RESPONSE_LIMIT=3*1024*1024;
function fail(code,message){return Object.assign(new Error(message),{code});}
function decode(response){
  if(response?.bodyEncoding!=="base64")return response?.body||"";
  return new TextDecoder().decode(Uint8Array.from(atob(response.body||""),c=>c.charCodeAt(0)));
}
export function validateArticleUrl(value){
  let url;try{url=new URL(value);}catch{throw fail("INVALID_URL","文章地址无效。");}
  if(!["https:","http:"].includes(url.protocol)||url.username||url.password)throw fail("INVALID_URL","只支持普通 HTTP/HTTPS 文章地址。");
  return url.href;
}

function resourceBaseUrl(html, pageUrl) {
  let found = false, href, inertDepth = 0;
  const inertTags = new Set(["template", "noscript", "svg", "math"]);
  const parser = new Parser({
    onopentag(tag, attributes) {
      if (inertTags.has(tag)) inertDepth += 1;
      if (!found && !inertDepth && tag === "base" && Object.hasOwn(attributes, "href")) {
        found = true;
        href = attributes.href;
      }
    },
    onclosetag(tag) {
      if (inertTags.has(tag)) inertDepth -= 1;
    },
  }, { decodeEntities: true });
  parser.end(html);
  if (!found) return pageUrl;
  try { return validateArticleUrl(new URL(href, pageUrl).href); }
  catch { return pageUrl; }
}

export async function fetchWebDocument(url,network=()=>globalThis.window?.ToolBox?.network){
  const target=validateArticleUrl(url),bridge=network();
  if(!bridge?.request)throw fail("BRIDGE_REQUIRED","请在 ToolBox 内读取原网页。");
  let response;
  try{response=await bridge.request({url:target,method:"GET",timeoutMs:15000,maxResponseBytes:RESPONSE_LIMIT,headers:{Accept:"text/html,application/xhtml+xml;q=0.9,*/*;q=0.1"}});}
  catch(error){throw fail(error?.code||"NETWORK_ERROR","无法读取原网页；可能存在反爬、登录或网络限制。");}
  if(!response||response.status<200||response.status>=300)throw fail("HTTP_ERROR",`原网页返回 HTTP ${response?.status||"?"}。`);
  const headers=response.headers||{};
  const finalHeader=Object.hasOwn(headers,"x-toolbox-final-url")?headers["x-toolbox-final-url"]:Object.entries(headers).find(([name])=>name.toLowerCase()==="x-toolbox-final-url")?.[1];
  const finalUrl=finalHeader===undefined?target:validateArticleUrl(finalHeader);
  const html=decode(response);
  if(!/<(?:html|article|main|body|div)[\s>]/i.test(html))throw fail("NOT_HTML","原网页没有返回可分析的 HTML。");
  return {url:finalUrl,baseUrl:resourceBaseUrl(html,finalUrl),html};
}
