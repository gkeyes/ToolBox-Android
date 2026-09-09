import { ALLOWED_TAGS, safeContentUrl } from "../toolbox/content.js";

const SAFE_ATTRIBUTES = new Set(["alt", "title", "colspan", "rowspan", "start", "class", "href", "rel", "data-article-anchor"]);

// Only this module owns descendants of the supplied empty article body. React
// owns its surrounding UI and portals; no raw HTML is ever assigned to the DOM.
export function createReadingDom(root, baseUrl, addPortal) {
  const document = root.ownerDocument;
  const nodes = new Map([[0, root]]);
  const dirty = new Set();
  const anchors = new Map();
  let pendingAnchor;
  const mark = (element) => dirty.add(element.closest("[data-font-block]") || root);
  const visitAnchor = (element, attrs) => {
    const name = attrs["data-article-anchor"];
    if (name && !anchors.has(name)) anchors.set(name, element);
    if (name && pendingAnchor === name && element.isConnected) { element.scrollIntoView({ block: "start" }); pendingAnchor = null; }
  };
  const attributes = (element, tag, attrs = {}) => {
    for (const [name, value] of Object.entries(attrs)) {
      if (!SAFE_ATTRIBUTES.has(name)) continue;
      if (name === "class" && (tag !== "code" || !/^(?:language-|lang-)[a-zA-Z0-9_+-]{1,40}$/.test(value))) continue;
      if (name === "href" && (tag !== "a" || !safeContentUrl(value))) continue;
      element.setAttribute(name, value);
    }
    if (tag === "a" && attrs.href) {
      try {
        const target = new URL(attrs.href), base = new URL(baseUrl);
        if (target.hash && target.origin === base.origin && target.pathname === base.pathname && target.search === base.search) {
          element.setAttribute("data-reading-local-anchor", decodeURIComponent(target.hash.slice(1)));
        }
      } catch { /* malformed fragments retain normal external-link handling */ }
    }
    visitAnchor(element, attrs);
  };
  const replaceContainer = (id, tag) => {
    const old = nodes.get(id);
    if (!old || old === root || old.tagName.toLowerCase() === tag) return old;
    const element = document.createElement(tag);
    for (const attribute of old.attributes) {
      if (["data-font-block", "data-article-anchor", "title"].includes(attribute.name)) element.setAttribute(attribute.name, attribute.value);
    }
    while (old.firstChild) element.appendChild(old.firstChild);
    old.replaceWith(element);
    nodes.set(id, element);
    const anchorName = element.getAttribute("data-article-anchor");
    if (anchorName && anchors.get(anchorName) === old) anchors.set(anchorName, element);
    visitAnchor(element, Object.fromEntries(Array.from(element.attributes, ({ name, value }) => [name, value])));
    dirty.delete(old);
    mark(element);
    return element;
  };
  return {
    apply(operation) {
      const parent = nodes.get(operation.parent);
      if (operation.type === "blockify") { replaceContainer(operation.id, "div"); return; }
      if (operation.type === "code") {
        const plainElement = nodes.get(operation.id);
        if (!plainElement) return;
        const container = plainElement.parentElement;
        const target = document.createElement("div");
        container.appendChild(target);
        // Retain the exact pre, lines and text nodes already mounted in frames.
        // Closing even a multi-megabyte pre only adds the small controls portal.
        addPortal({ ...operation, target, plainElement, container });
        mark(plainElement);
        return;
      }
      if (!parent) throw new Error("Invalid article node parent");
      if (operation.type === "text") {
        parent.appendChild(document.createTextNode(operation.text));
        mark(parent);
        return;
      }
      let element;
      if (operation.type === "element") {
        if (!ALLOWED_TAGS.has(operation.tag) || operation.tag === "img") throw new Error("Invalid article node");
        element = document.createElement(operation.tag);
        if (operation.block) element.setAttribute("data-font-block", "");
        if (operation.codeLine) element.classList.add("line");
        attributes(element, operation.tag, operation.attrs);
      } else if (["image", "media", "imageLink"].includes(operation.type)) {
        element = document.createElement("div");
        element.setAttribute("data-font-block", "");
        addPortal({ ...operation, target: element });
      } else throw new Error("Invalid article operation");
      nodes.set(operation.id, element);
      if (operation.codeBlock) {
        const wrapper = document.createElement("div");
        wrapper.className = "code-block relative group";
        wrapper.dataset.highlighted = "false";
        element.classList.add("overflow-x-auto", "pt-12");
        wrapper.appendChild(element);
        parent.appendChild(wrapper);
      } else parent.appendChild(element);
      if (operation.attrs) visitAnchor(element, operation.attrs);
      mark(element);
    },
    takeDirtyBlocks() { const result = [...dirty]; dirty.clear(); return result; },
    navigateAnchor(name) {
      const target = anchors.get(name);
      if (target) target.scrollIntoView({ block: "start" });
      else pendingAnchor = name;
    },
    finish() { nodes.clear(); },
    dispose() { nodes.clear(); dirty.clear(); anchors.clear(); pendingAnchor = null; },
  };
}
