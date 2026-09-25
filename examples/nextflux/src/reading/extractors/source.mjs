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
export async function fetchWebDocument(url,network=()=>globalThis.window?.ToolBox?.network){
  const target=validateArticleUrl(url),bridge=network();
  if(!bridge?.request)throw fail("BRIDGE_REQUIRED","请在 ToolBox 内读取原网页。");
  let response;
  try{response=await bridge.request({url:target,method:"GET",timeoutMs:15000,maxResponseBytes:RESPONSE_LIMIT,headers:{Accept:"text/html,application/xhtml+xml;q=0.9,*/*;q=0.1"}});}
  catch(error){throw fail(error?.code||"NETWORK_ERROR","无法读取原网页；可能存在反爬、登录或网络限制。");}
  if(!response||response.status<200||response.status>=300)throw fail("HTTP_ERROR",`原网页返回 HTTP ${response?.status||"?"}。`);
  const html=decode(response);
  if(!/<(?:html|article|main|body|div)[\s>]/i.test(html))throw fail("NOT_HTML","原网页没有返回可分析的 HTML。");
  return {url:target,html};
}
