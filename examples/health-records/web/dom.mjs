export function h(tag, attrs = {}, ...children) {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs || {})) {
    if (value == null || value === false) continue;
    if (name.startsWith("on") && typeof value === "function") element.addEventListener(name.slice(2).toLowerCase(), value);
    else if (name === "class") element.className = value;
    else if (["value", "checked", "disabled", "hidden", "selected"].includes(name)) element[name] = value;
    else element.setAttribute(name, value === true ? "" : String(value));
  }
  for (const child of children.flat(Infinity)) if (child !== null && child !== undefined && child !== false) element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return element;
}

export function svg(tag, attrs = {}, ...children) {
  const element = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [name, value] of Object.entries(attrs)) element.setAttribute(name, String(value));
  for (const child of children.flat(Infinity)) if (child != null) element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return element;
}

const paths = {
  leaf: ["M20 3C10 3 3 6 4 13s9 9 13 2c3-4 3-8 3-12Z", "M3 22c1-7 5-10 11-15M7 15h6M8 12V8"],
  grid: ["M3 3h6v6H3zM15 3h6v6h-6zM3 15h6v6H3zM15 15h6v6h-6z"],
  file: ["M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8Z", "M14 3v6h5M8 12h8M8 16h6"],
  flask: ["M9 3h6M10 3v6l-6 10a1.4 1.4 0 0 0 1.2 2h13.6a1.4 1.4 0 0 0 1.2-2L14 9V3M7 15h10M10 12h.01M14 18h.01"],
  scan: ["M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3M3 12h18"],
  trend: ["M3 3v18h18M5 14l5-6 5 4 6-8"],
  user: ["M20 21v-2a7 7 0 0 0-7-7h-2a7 7 0 0 0-7 7v2Z", "M16 6a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z"],
  settings: ["m9 3-1 3-3 1-2 3 2 2-1 3 3 2 2-1 3 2 3-2 2 1 3-2-1-3 2-2-2-3-3-1-1-3Z", "M15 11a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"],
  plus: ["M12 5v14M5 12h14"],
  chevron: ["m9 5 7 7-7 7"],
  back: ["M20 12H4m7-7-7 7 7 7"],
  close: ["m6 6 12 12M6 18 18 6"],
  trash: ["M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"],
  edit: ["m15 4 5 5M3 21l5-1L21 7a2 2 0 0 0 0-3l-1-1a2 2 0 0 0-3 0L4 16Z"],
  search: ["M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0ZM15 15l6 6"],
  download: ["M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"],
  upload: ["M12 16V4m-5 5 5-5 5 5M4 16v5h16v-5"],
  spark: ["m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z"],
  shield: ["M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6ZM8 12l3 3 5-6"],
  calendar: ["M4 5h16v16H4ZM4 10h16M8 3v4M16 3v4M8 14h2M14 14h2M8 18h2"],
  moon: ["M20 15A9 9 0 0 1 9 4a9 9 0 1 0 11 11Z"],
};

export function icon(name, className = "") {
  return svg("svg", { viewBox: "0 0 24 24", class: `icon ${className}`.trim(), fill: "none", stroke: "currentColor", "stroke-width": 1.8, "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true", focusable: "false" },
    (paths[name] || paths.file).map((d) => svg("path", { d })));
}

export function button(label, onClick, className = "button", iconName = null) {
  return h("button", { type: "button", class: className, onClick }, iconName && icon(iconName), label);
}

export function iconButton(label, name, onClick, className = "") {
  return h("button", { type: "button", class: `icon-button ${className}`, "aria-label": label, title: label, onClick }, icon(name));
}

export const sectionHeading = (title, action = null) => h("div", { class: "section-heading" }, h("h2", {}, title), action);
export const field = (label, control, hint = null) => h("label", { class: "field" }, h("span", { class: "field-label" }, label), control, hint && h("span", { class: "field-hint" }, hint));

export function emptyState(title, description, action = null) {
  return h("div", { class: "empty-state" }, h("span", { class: "empty-icon" }, icon("file")), h("h3", {}, title), h("p", {}, description), action);
}
