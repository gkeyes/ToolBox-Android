const ALLOWED_TAGS = new Set("p div span br hr h1 h2 h3 h4 h5 h6 blockquote pre code strong b em i u s del ins small sub sup mark abbr q cite kbd samp var ul ol li dl dt dd table thead tbody tfoot tr th td caption colgroup col figure figcaption details summary a img".split(" "));
const DROP_CONTENT = new Set(["script", "style", "object", "embed", "svg", "math", "template", "form", "input", "button", "textarea", "select", "option", "meta", "link", "base"]);

export function safeContentUrl(value, baseUrl) {
  if (typeof value !== "string" || !value || Array.from(value).some((char) => char.codePointAt(0) <= 32 || char.codePointAt(0) === 127)) return null;
  try {
    const url = new URL(value, baseUrl || "https://miniflux.xiaochen.win/");
    return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

export function cleanAttributes(tag, attributes, baseUrl) {
  const clean = {};
  for (const [name, value] of Object.entries(attributes)) {
    if (["alt", "title"].includes(name)) clean[name] = String(value).slice(0, 2000);
    if (["colspan", "rowspan", "start"].includes(name) && /^\d{1,3}$/.test(value)) clean[name] = value;
    if (name === "class" && tag === "code") {
      const language = String(value).match(/(?:^|\s)(?:language-|lang-)[a-zA-Z0-9_+-]{1,40}(?=\s|$)/)?.[0]?.trim();
      if (language) clean.class = language;
    }
    if (name === "href" && tag === "a") {
      const url = safeContentUrl(value, baseUrl);
      if (url) { clean.href = url; clean.rel = "noopener noreferrer"; }
    }
    if (name === "src" && tag === "img") {
      // Source is metadata only: renamed until ArticleImage validates and resolves it.
      if (/^data:image\/(png|jpeg|gif|webp|avif|bmp|x-icon);base64,/i.test(value)) clean["data-image-source"] = value;
      else {
        const url = safeContentUrl(value, value.startsWith("/proxy/") ? "https://miniflux.xiaochen.win/" : baseUrl);
        if (url) clean["data-image-source"] = url;
      }
    }
  }
  return clean;
}

// Template contents stay inert. Reconstruct an allowlisted tree before parsing/rendering.
export function sanitizeArticleHtml(html, baseUrl, documentObject = globalThis.document) {
  const input = documentObject.createElement("template");
  input.innerHTML = typeof html === "string" ? html : "";
  const output = documentObject.createElement("template");
  function copyChildren(source, target, depth = 0) {
    if (depth > 80) return;
    for (const child of source.childNodes) {
      if (child.nodeType === 3) { target.appendChild(documentObject.createTextNode(child.textContent)); continue; }
      if (child.nodeType !== 1) continue;
      const tag = child.tagName.toLowerCase();
      if (DROP_CONTENT.has(tag)) continue;
      if (["iframe", "audio", "video"].includes(tag)) {
        const mediaSource = child.getAttribute("src") || child.querySelector("source")?.getAttribute("src");
        const url = safeContentUrl(mediaSource, mediaSource?.startsWith("/proxy/") ? "https://miniflux.xiaochen.win/" : baseUrl);
        const placeholder = documentObject.createElement("div");
        placeholder.setAttribute("data-media-kind", tag);
        if (url) placeholder.setAttribute("data-media-url", url);
        target.appendChild(placeholder);
        continue;
      }
      if (!ALLOWED_TAGS.has(tag)) { copyChildren(child, target, depth + 1); continue; }
      const element = documentObject.createElement(tag);
      const attributes = Object.fromEntries(Array.from(child.attributes, ({ name, value }) => [name, value]));
      for (const [name, value] of Object.entries(cleanAttributes(tag, attributes, baseUrl))) element.setAttribute(name, value);
      copyChildren(child, element, depth + 1);
      target.appendChild(element);
    }
  }
  copyChildren(input.content, output.content);
  return output.innerHTML;
}
