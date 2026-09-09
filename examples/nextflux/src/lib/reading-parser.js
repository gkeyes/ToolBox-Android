import { Parser } from "htmlparser2";
import { ALLOWED_TAGS, DROP_CONTENT, cleanAttributes, safeContentUrl } from "../toolbox/content.js";

const INPUT_CHUNK = 2048;
const BATCH_NODES = 96;
const BATCH_TEXT = 16 * 1024;
const BLOCK_TAGS = new Set("p div h1 h2 h3 h4 h5 h6 blockquote pre li dt dd th td caption figcaption summary".split(" "));
const mediaUrl = (source, base) => safeContentUrl(source, source?.startsWith("/proxy/") ? "https://miniflux.xiaochen.win/" : base);

// A flat, already-sanitized tree stream. No DOM parser or HTML serialization is
// involved, so malformed markup cannot become executable when inserted later.
// Pulling one bounded batch at a time also bounds the worker/main-thread queue.
export function createReadingParser(html, baseUrl) {
  const source = typeof html === "string" ? html : "";
  let offset = 0, nextId = 1, ended = false;
  const pending = [];
  const stack = [{ id: 0, blocked: false, depth: 0 }];
  let queuedText = 0;
  const emit = (operation) => { pending.push(operation); queuedText += operation.text?.length || 0; };
  const openCodeLine = (code) => {
    code.lineId = nextId++;
    emit({ type: "element", id: code.lineId, parent: code.parent, tag: "span", attrs: {}, codeLine: true });
  };
  const appendCode = (code, text) => {
    code.parts.push(text);
    const lines = text.split("\n");
    for (let index = 0; index < lines.length; index += 1) {
      if (index) {
        emit({ type: "text", parent: code.parent, text: "\n" });
        openCodeLine(code);
      }
      for (let start = 0; start < lines[index].length; start += INPUT_CHUNK) emit({ type: "text", parent: code.lineId, text: lines[index].slice(start, start + INPUT_CHUNK) });
    }
  };
  const parser = new Parser({
    onopentag(tag, attributes) {
      const parent = stack.at(-1);
      const entry = { tag, id: parent.id, blocked: parent.blocked || DROP_CONTENT.has(tag), depth: parent.depth, media: parent.media, code: parent.code };
      stack.push(entry);
      if (parent.media && tag === "source" && !parent.media.url) parent.media.url = mediaUrl(attributes.src, baseUrl);
      if (entry.blocked) return;
      if (parent.code) {
        if (tag === "code") {
          const attrs = cleanAttributes(tag, attributes, baseUrl);
          if (attrs.class) parent.code.language = attrs.class.replace(/^(language-|lang-)/, "");
        }
        if (tag === "br") appendCode(parent.code, "\n");
        return;
      }
      if (["iframe", "audio", "video"].includes(tag)) {
        entry.blocked = true;
        entry.media = { id: nextId++, parent: parent.id, kind: tag, url: mediaUrl(attributes.src, baseUrl) };
        entry.ownsMedia = true;
        return;
      }
      if (!ALLOWED_TAGS.has(tag)) return;
      // Flatten pathological depth without truncating any safe article text.
      if (parent.depth >= 80) return;
      entry.id = nextId++;
      entry.depth += 1;
      const attrs = cleanAttributes(tag, attributes, baseUrl);
      entry.attrs = attrs;
      if (tag === "img") {
        const paragraph = stack.findLast((item) => item.tag === "p" && !item.blocked);
        if (paragraph) emit({ type: "blockify", id: paragraph.id });
        if (parent.tag === "a" && parent.attrs?.href) {
          if (!parent.linkWithImage) emit({ type: "blockify", id: parent.id });
          parent.linkWithImage = true;
        }
        emit({ type: "image", id: entry.id, parent: parent.id, attrs });
        return;
      }
      if (tag === "pre") {
        entry.code = { id: entry.id, parent: nextId++, parts: [], language: "text" };
        entry.ownsCode = true;
        emit({ type: "element", id: entry.id, parent: parent.id, tag, attrs, block: true, codeBlock: true });
        emit({ type: "element", id: entry.code.parent, parent: entry.id, tag: "code", attrs: {} });
        openCodeLine(entry.code);
        return;
      }
      emit({ type: "element", id: entry.id, parent: parent.id, tag, attrs, block: BLOCK_TAGS.has(tag) });
    },
    ontext(text) {
      const parent = stack.at(-1);
      if (parent.blocked || !text) return;
      if (parent.code) { appendCode(parent.code, text); return; }
      // Text callbacks may span tokenizer chunks (entities/raw text). Split them
      // as well, including huge articles containing one uninterrupted text node.
      for (let index = 0; index < text.length; index += INPUT_CHUNK) {
        emit({ type: "text", parent: parent.id, text: text.slice(index, index + INPUT_CHUNK) });
      }
    },
    onclosetag() {
      const entry = stack.pop();
      if (entry.code && !entry.ownsCode && /^(p|div|h[1-6])$/.test(entry.tag) && !entry.blocked) appendCode(entry.code, "\n");
      if (entry.ownsMedia) emit({ type: "media", ...entry.media });
      if (entry.ownsCode) emit({ type: "code", id: entry.id, code: entry.code.parts.join(""), language: entry.code.language });
      if (entry.linkWithImage) emit({ type: "imageLink", parent: entry.id, id: nextId++, href: entry.attrs.href });
    },
  }, { decodeEntities: true, lowerCaseTags: true, lowerCaseAttributeNames: true, xmlMode: false });
  return {
    next() {
      const start = performance.now();
      while (!ended && pending.length < BATCH_NODES && queuedText < BATCH_TEXT && performance.now() - start < 8) {
        if (offset < source.length) {
          const end = Math.min(source.length, offset + INPUT_CHUNK);
          parser.write(source.slice(offset, end));
          offset = end;
        } else { parser.end(); ended = true; }
      }
      const operations = pending.splice(0, BATCH_NODES);
      for (const operation of operations) queuedText -= operation.text?.length || 0;
      return { operations, done: ended && pending.length === 0 };
    },
  };
}
