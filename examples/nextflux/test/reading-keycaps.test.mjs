import test from "node:test";
import assert from "node:assert/strict";
import { createReadingParser } from "../src/lib/reading-parser.js";

function read(html) {
  const parser = createReadingParser(html, "https://example.org/article");
  const operations = [];
  for (;;) {
    const batch = parser.next();
    operations.push(...batch.operations);
    if (batch.done) return operations;
  }
}
const text = (operations) => operations.filter((op) => op.type === "text").map((op) => op.text).join("");

test("article display qualifies keycaps while ordinary text and text-presentation selectors stay intact", () => {
  const bases = "0123456789#*";
  for (const base of bases) {
    const expected = `${base}\ufe0f\u20e3`;
    for (const input of [`${base}\u20e3`, expected, `${base}\u20e3\ufe0f`]) {
      assert.equal(text(read(`<p>${input}标签</p>`)), `${expected}标签`);
    }
  }
  const untouched = "普通 123 #tag *号 😀 ♥︎ ♥️ 6\ufe0e\u20e3 6 \u20e3";
  assert.equal(text(read(`<p>${untouched}</p>`)), untouched);
});

test("entities and tokenizer chunk boundaries cannot split keycap normalization", () => {
  assert.equal(text(read("<p>&#35;&#8419;&#65039;标签 &#54;&#xfe0f;&#x20e3;</p>")), "#️⃣标签 6️⃣");
  for (let offset = 2044; offset <= 2049; offset++) {
    const prefix = "a".repeat(offset - 3);
    assert.equal(text(read(`<p>${prefix}6\u20e3\ufe0f尾</p>`)), `${prefix}6️⃣尾`);
  }
  assert.equal(text(read("#⃣")), "#️⃣");
  assert.equal(text(read("<p>123</p><p>#</p>")), "123#");
});

test("only display text changes; link destinations, code and the source HTML are preserved", () => {
  const raw = "6\u20e3\ufe0f";
  const href = `https://example.org/?q=${raw}#${raw}`;
  const source = `<p><a href="${href}">${raw}</a></p><code>${raw}</code><pre><code>${raw}</code></pre>`;
  const operations = read(source);
  assert.equal(text(operations), `6️⃣${raw}${raw}`);
  assert.equal(operations.find((op) => op.tag === "a").attrs.href, new URL(href).href);
  assert.equal(operations.find((op) => op.type === "code").code, raw);
  assert.equal(source, `<p><a href="${href}">${raw}</a></p><code>${raw}</code><pre><code>${raw}</code></pre>`);
});
