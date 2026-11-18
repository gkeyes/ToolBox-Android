import { createElement } from "react";
import { Marked } from "marked";
import { parseDocument } from "htmlparser2";
import { cleanAttributes, safeContentUrl } from "./content.js";

const escapeHtml = (text) => text.replace(/[&<>"']/g, (character) => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
})[character]);

// Keep options local. AI output is Markdown data, never executable HTML.
const markdown = new Marked({
  gfm: true,
  breaks: true,
  renderer: { html: ({ text }) => escapeHtml(text) },
});
const TAGS = new Set([
  "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote",
  "ul", "ol", "li", "strong", "em", "del", "pre", "code", "a",
  "table", "thead", "tbody", "tr", "th", "td",
]);
const TABLE_CONTAINERS = new Set(["table", "thead", "tbody", "tfoot", "tr"]);

// Reuse the reader's URL/attribute policy and existing HTML parser, but not its
// article worker or normalization: streamed summaries must preserve Markdown.
// React constructs every node; no generated HTML is assigned to innerHTML.
export function renderSummaryMarkdown(source, baseUrl) {
  if (typeof source !== "string" || !source) return null;
  const render = (nodes, parentKey = "", parentTag = "") => nodes.map((node, index) => {
    const key = `${parentKey}.${index}`;
    // Markdown's HTML formatter inserts newlines between table rows/cells.
    // React's table content model excludes those whitespace-only text nodes.
    if (node.type === "text") return TABLE_CONTAINERS.has(parentTag) && !node.data.trim() ? null : node.data;
    if (node.type !== "tag") return null;
    const tag = node.name;
    const attributes = node.attribs || {};
    if (tag === "img") {
      // Images remain explicit links instead of fetching model-generated
      // remote media outside the reader's controlled media transport.
      const href = safeContentUrl(attributes.src, baseUrl);
      const label = attributes.alt || "图片";
      return href ? createElement("a", { key, href, rel: "noopener noreferrer" }, label) : label;
    }
    if (tag === "input" && attributes.type === "checkbox") {
      const checked = Object.hasOwn(attributes, "checked");
      return createElement("input", { key, type: "checkbox", checked, disabled: true, readOnly: true, "aria-label": checked ? "已完成" : "未完成" });
    }
    const children = render(node.children || [], key, tag);
    if (!TAGS.has(tag)) return children;
    const props = { ...cleanAttributes(tag, attributes, baseUrl), key };
    if (props.class) { props.className = props.class; delete props.class; }
    if (tag === "a" && !props.href) return createElement("span", { key }, children);
    if (["th", "td"].includes(tag) && ["left", "center", "right"].includes(attributes.align)) {
      props.style = { textAlign: attributes.align };
    }
    if (tag === "pre") props.tabIndex = 0;
    if (tag === "br" || tag === "hr") return createElement(tag, props);
    const element = createElement(tag, props, children);
    return tag === "table"
      ? createElement("div", { key, className: "nextflux-summary-table", role: "region", "aria-label": "摘要表格", tabIndex: 0 }, element)
      : element;
  });
  try {
    return render(parseDocument(markdown.parse(source)).children);
  } catch {
    // Even a malformed/incomplete reply must remain readable and closable.
    return source;
  }
}
