// Event/DOM double for deterministic pending-operation tests; not a rendering oracle.
export class MemoryNode {
  constructor(tag = "#text", text = "") {
    this.tagName = tag.toUpperCase(); this.attributes = new Map(); this.childNodes = []; this.parentNode = null;
    this.listeners = new Map(); this.dataset = {}; this.style = {}; this.value = ""; this.disabled = false; this.hidden = false; this.open = false; this._text = text;
    this.classList = {
      add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/), ...names])].join(" ").trim(); },
      remove: (...names) => { this.className = this.className.split(/\s+/).filter((name) => !names.includes(name)).join(" "); },
    };
  }
  get children() { return this.childNodes.filter((node) => node.tagName !== "#TEXT"); }
  get className() { return this.attributes.get("class") || ""; }
  set className(value) { this.attributes.set("class", value); }
  get textContent() { return this._text + this.childNodes.map((node) => node.textContent).join(""); }
  set textContent(value) { this.replaceChildren(); this._text = String(value); }
  append(...nodes) {
    for (const value of nodes) {
      const node = value instanceof MemoryNode ? value : new MemoryNode("#text", String(value));
      node.parentNode = this; this.childNodes.push(node);
    }
  }
  after(node) { node.parentNode = this.parentNode; this.parentNode.childNodes.splice(this.parentNode.childNodes.indexOf(this) + 1, 0, node); }
  replaceChildren(...nodes) { for (const child of this.childNodes) child.parentNode = null; this.childNodes = []; this._text = ""; this.append(...nodes); }
  remove() { if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter((node) => node !== this); this.parentNode = null; }
  setAttribute(name, value) { this.attributes.set(name, String(value)); if (name.startsWith("data-")) this.dataset[name.slice(5)] = String(value); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(name, listener) { if (!this.listeners.has(name)) this.listeners.set(name, []); this.listeners.get(name).push(listener); }
  isDisabled() { for (let n = this; n; n = n.parentNode) if (n.disabled && (n === this || n.tagName === "FIELDSET")) return true; return false; }
  async fire(type, extras = {}) {
    if (["click", "input", "change"].includes(type) && this.isDisabled()) return;
    const event = { type, target: this, preventDefault() {}, stopPropagation() {}, ...extras }, pending = [];
    for (let n = this; n; n = extras.bubbles ? n.parentNode : null) for (const listener of n.listeners.get(type) || []) pending.push(listener(event));
    await Promise.all(pending);
  }
  async enter(value) { if (!this.isDisabled()) { this.value = value; await this.fire("input", { bubbles: true }); } }
  dispatchEvent(event) { void this.fire(event.type, { bubbles: event.bubbles }); return true; }
  contains(node) { return node === this || this.childNodes.some((child) => child.contains(node)); }
  allDescendants() { return this.childNodes.flatMap((child) => [child, ...child.allDescendants()]); }
  querySelectorAll(selector) {
    return this.allDescendants().filter((node) => {
      const attr = /^\[([^=\]]+)(?:="([^"]*)")?\]$/.exec(selector);
      return attr ? attr[2] === undefined ? node.attributes.has(attr[1]) : node.getAttribute(attr[1]) === attr[2] : node.tagName.toLowerCase() === selector;
    });
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  focus() { document.activeElement = this; }
  scrollIntoView() {}
  showModal() { this.open = true; }
  close() { this.open = false; }
  getContext() { return { fillRect() {}, drawImage() {} }; }
  toBlob(callback, type) { callback(new Blob([new Uint8Array([1, 2, 3])], { type })); }
}

export function freshDom(api) {
  const nodes = Object.fromEntries(["main", "navigation", "dialog", "toast"].map((id) => [id, new MemoryNode(id === "dialog" ? "dialog" : "div")]));
  const doc = new MemoryNode("document"); doc.body = new MemoryNode("body"); doc.documentElement = new MemoryNode("html"); doc.activeElement = doc.body;
  doc.getElementById = (id) => nodes[id]; doc.createElement = (tag) => new MemoryNode(tag); doc.createElementNS = (_, tag) => new MemoryNode(tag); doc.createTextNode = (text) => new MemoryNode("#text", text);
  doc.body.append(...Object.values(nodes)); globalThis.document = doc; globalThis.Node = MemoryNode;
  globalThis.window = { ToolBox: api, scrollY: 0, scrollTo() {}, addEventListener() {} };
  return nodes;
}

export const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
export const settle = async () => { for (let i = 0; i < 60; i++) await Promise.resolve(); };
