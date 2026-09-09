import { createHash } from "node:crypto";

export const fixtureTimestamp = "2026-09-09T00:00:00.000Z";
export const librarySize = 96;
export const continuousReadIds = Array.from({ length: 24 }, (_, index) => 96 - index);
const escapeHtml = (value) => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");

function article(id, name, paragraphs, codes = []) {
  const first = `FIXTURE_BODY_${id} PERFORMANCE_${name}_START`;
  const last = `END_FIXTURE_BODY_${id} PERFORMANCE_${name}_END`;
  const allParagraphs = [first, ...paragraphs, last];
  // Put prose before every block, including the first screen. All code remains
  // real article content, so offscreen enhancement can be deferred legitimately.
  const sections = paragraphs.map((paragraph, index) => `<p>${escapeHtml(paragraph)}</p>${codes[index] === undefined ? "" : `<pre><code class="language-javascript">${escapeHtml(codes[index])}</code></pre>`}`);
  const html = `<p>${first}</p>${sections.join("")}<p>${last}</p>`;
  return {
    id, name, first, last, paragraphs: allParagraphs, codes, html,
    metadata: {
      id, name, utf8Bytes: Buffer.byteLength(html), sha256: createHash("sha256").update(html).digest("hex"),
      paragraphCount: allParagraphs.length, codeBlockCount: codes.length,
      codeLineCount: codes.reduce((sum, code) => sum + code.split("\n").length, 0),
      textCharacters: allParagraphs.join("").length + codes.join("").length,
    },
  };
}

export const articles = [
  article(96, "long-prose", Array.from({ length: 720 }, (_, index) =>
    `Paragraph ${index + 1}: A complete synthetic reading passage preserves each word, punctuation mark and sequence. 中文阅读内容用于系统字体扫描对比。`)),
  article(95, "dense-code", Array.from({ length: 32 }, (_, index) =>
    `Code section ${index + 1}: Read the complete implementation below, then continue to the following explanation.`),
  Array.from({ length: 32 }, (_, block) => Array.from({ length: 40 }, (_, line) =>
    `const entry_${block}_${line} = { id: ${block * 40 + line}, title: "Fixture item", unread: true }; // preserve this line`).join("\n"))),
];

export const articleBodies = Object.fromEntries(articles.map(({ id, html }) => [id, html]));
