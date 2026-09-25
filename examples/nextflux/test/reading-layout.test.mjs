import test from "node:test";
import assert from "node:assert/strict";
import { analyzeReadingLayout, shouldPreserveTextBreaks } from "../src/toolbox/reading-layout.mjs";
import { createReadingParser } from "../src/lib/reading-parser.js";

test("reader layout repair only activates for long sparse text with meaningful source lines", () => {
  const sparseText = Array.from({ length: 7 }, (_, index) => `第${index + 1}段 ${"正文".repeat(45)}`).join("\n");
  const sparse = analyzeReadingLayout(`<div>${sparseText}</div>`);
  assert.equal(sparse.needsRepair, true);
  assert.ok(sparse.meaningfulNewlines >= 6);

  const structured = analyzeReadingLayout(Array.from({ length: 7 }, (_, index) => `<p>第${index + 1}段 ${"正文".repeat(45)}</p>`).join(""));
  assert.equal(structured.needsRepair, false);
  assert.equal(structured.paragraphs, 7);
});

test("protected reading structures never receive newline repair", () => {
  assert.equal(shouldPreserveTextBreaks("第一行\n第二行", true, true), false);
  assert.equal(shouldPreserveTextBreaks("第一行\n第二行", false, false), false);
  assert.equal(shouldPreserveTextBreaks("第一行\n第二行", false, true), true);
});

test("reading parser marks sparse source newline text without rewriting structured paragraphs", () => {
  const sparseText = Array.from({ length: 7 }, (_, index) => `第${index + 1}段 ${"正文".repeat(45)}`).join("\n");
  const parser = createReadingParser(`<div>${sparseText}</div>`, "https://example.test/post");
  const operations = [];
  for (;;) {
    const batch = parser.next();
    operations.push(...batch.operations);
    if (batch.done) break;
  }
  assert.ok(operations.some((operation) => operation.type === "text" && operation.preserveBreaks));

  const normal = createReadingParser("<p>第一段</p><p>第二段</p>", "https://example.test/post");
  const normalOps = [];
  for (;;) {
    const batch = normal.next();
    normalOps.push(...batch.operations);
    if (batch.done) break;
  }
  assert.equal(normalOps.some((operation) => operation.type === "text" && operation.preserveBreaks), false);
});
