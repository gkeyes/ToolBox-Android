const STRUCTURE_LIMIT = 12000;
const RESPONSE_LIMIT = 2 * 1024 * 1024;
const DROP = "script,style,noscript,template,svg,canvas,iframe,object,embed,form,input,textarea,select,button";

function fail(code, message) { return Object.assign(new Error(message), { code }); }
function decode(response) {
  if (response?.bodyEncoding !== "base64") return response?.body || "";
  return new TextDecoder().decode(Uint8Array.from(atob(response.body || ""), c => c.charCodeAt(0)));
}
export function validateArticleUrl(value) {
  let url; try { url = new URL(value); } catch { throw fail("INVALID_URL", "文章地址无效。"); }
  if (!["https:", "http:"].includes(url.protocol) || url.username || url.password) throw fail("INVALID_URL", "只支持普通 HTTP/HTTPS 文章地址。");
  return url.href;
}
function safeToken(value) {
  return typeof value === "string" && value.length <= 48 && /^[A-Za-z_][A-Za-z0-9_-]*$/.test(value) && !/[0-9a-f]{10,}/i.test(value);
}
function hint(el) {
  const tag = el.tagName.toLowerCase();
  if (safeToken(el.id)) return `${tag}#${CSS.escape(el.id)}`;
  const classes = [...el.classList].filter(safeToken).slice(0, 3);
  return tag + classes.map(v => "." + CSS.escape(v)).join("");
}
export function inspectSelector(html, selector) {
  if (typeof selector !== "string" || !selector.trim() || selector.length > 800 || /[\r\n{}]/.test(selector)) throw fail("INVALID_RULE", "AI 返回的采集规则无效。");
  const doc = new DOMParser().parseFromString(html, "text/html");
  let nodes; try { nodes = [...doc.querySelectorAll(selector)]; } catch { throw fail("INVALID_RULE", "AI 返回的 CSS 选择器无法解析。"); }
  const text = nodes.map(n => n.textContent || "").join(" ").replace(/\s+/g, " ").trim();
  return { matches: nodes.length, textChars: text.length, paragraphs: nodes.reduce((n, el) => n + el.querySelectorAll("p").length, 0), images: nodes.reduce((n, el) => n + el.querySelectorAll("img").length, 0) };
}
export function summarizeHtmlStructure(html) {
  const doc = new DOMParser().parseFromString(html, "text/html");
  doc.querySelectorAll(DROP).forEach(node => node.remove());
  const rows = [...doc.querySelectorAll("article,main,[role=main],section,div")].map((el, index) => {
    const textChars = (el.textContent || "").replace(/\s+/g, " ").trim().length;
    return { index, selector: hint(el), textChars, p: el.querySelectorAll("p").length, img: el.querySelectorAll("img").length, headings: el.querySelectorAll("h1,h2,h3").length };
  }).filter(x => x.textChars >= 120).sort((a,b) => b.textChars - a.textChars);
  const seen = new Set(), lines = [];
  for (const row of rows) {
    if (!row.selector || seen.has(row.selector)) continue;
    seen.add(row.selector);
    lines.push(`${row.selector} | text=${row.textChars} | p=${row.p} | img=${row.img} | headings=${row.headings}`);
    if (lines.length >= 100) break;
  }
  const title = (doc.querySelector("title")?.textContent || "").replace(/\s+/g, " ").trim().slice(0, 180);
  return (`PAGE_TITLE: ${title}\nCANDIDATE_CONTAINERS:\n` + lines.join("\n")).slice(0, STRUCTURE_LIMIT);
}
export async function fetchSourceSnapshot(url, network = () => globalThis.window?.ToolBox?.network) {
  const target = validateArticleUrl(url);
  const bridge = network();
  if (!bridge?.request) throw fail("BRIDGE_REQUIRED", "请在 ToolBox 内使用全文适配。");
  let response;
  try { response = await bridge.request({ url: target, method: "GET", timeoutMs: 15000, maxResponseBytes: RESPONSE_LIMIT, headers: { Accept: "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1" } }); }
  catch (error) { throw fail(error?.code || "NETWORK_ERROR", "无法读取原网页结构；可能存在反爬、登录或网络限制。"); }
  if (!response || response.status < 200 || response.status >= 300) throw fail("HTTP_ERROR", `原网页返回 HTTP ${response?.status || "?"}。`);
  const html = decode(response);
  if (!/<(?:html|article|main|body|div)[\s>]/i.test(html)) throw fail("NOT_HTML", "原网页没有返回可分析的 HTML。");
  return { url: target, html, structure: summarizeHtmlStructure(html) };
}
