const STRUCTURE_LIMIT = 12000;
import { fetchWebDocument } from "../../reading/extractors/source.mjs";
const STRUCTURE_DROP = "script,style,noscript,template,svg,canvas,iframe,object,embed,form,input,textarea,select,button";

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
  return {
    matches: nodes.length,
    textChars: text.length,
    paragraphs: nodes.reduce((n, el) => n + el.querySelectorAll("p").length, 0),
    breaks: nodes.reduce((n, el) => n + el.querySelectorAll("br").length, 0),
    listItems: nodes.reduce((n, el) => n + el.querySelectorAll("li").length, 0),
    headings: nodes.reduce((n, el) => n + el.querySelectorAll("h1,h2,h3,h4,h5,h6").length, 0),
    figures: nodes.reduce((n, el) => n + el.querySelectorAll("figure").length, 0),
    captions: nodes.reduce((n, el) => n + el.querySelectorAll("figcaption").length, 0),
    images: nodes.reduce((n, el) => n + el.querySelectorAll("img").length, 0),
  };
}
export function summarizeHtmlStructure(html) {
  const doc = new DOMParser().parseFromString(html, "text/html");
  doc.querySelectorAll(STRUCTURE_DROP).forEach(node => node.remove());
  const rows = [...doc.querySelectorAll("article,main,[role=main],section,div")].map((el, index) => {
    const textChars = (el.textContent || "").replace(/\s+/g, " ").trim().length;
    return { index, selector: hint(el), textChars, p: el.querySelectorAll("p").length, br: el.querySelectorAll("br").length, li: el.querySelectorAll("li").length, fig: el.querySelectorAll("figure").length, cap: el.querySelectorAll("figcaption").length, img: el.querySelectorAll("img").length, headings: el.querySelectorAll("h1,h2,h3,h4,h5,h6").length };
  }).filter(x => x.textChars >= 120).sort((a,b) => b.textChars - a.textChars);
  const seen = new Set(), lines = [];
  for (const row of rows) {
    if (!row.selector || seen.has(row.selector)) continue;
    seen.add(row.selector);
    lines.push(`${row.selector} | text=${row.textChars} | p=${row.p} | br=${row.br} | li=${row.li} | headings=${row.headings} | figure=${row.fig} | caption=${row.cap} | img=${row.img}`);
    if (lines.length >= 100) break;
  }
  const title = (doc.querySelector("title")?.textContent || "").replace(/\s+/g, " ").trim().slice(0, 180);
  return (`PAGE_TITLE: ${title}\nCANDIDATE_CONTAINERS:\n` + lines.join("\n")).slice(0, STRUCTURE_LIMIT);
}
export async function fetchSourceSnapshot(url, network = () => globalThis.window?.ToolBox?.network) {
  const source = await fetchWebDocument(url, network);
  return { ...source, structure: summarizeHtmlStructure(source.html) };
}
