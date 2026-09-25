import test from "node:test";
import assert from "node:assert/strict";
import {
  analyzeReadingStructure,
  createReadingNormalizationPlan,
  isStandaloneNoiseText,
  shouldPreserveTextBreaks,
  shouldRecoverInlineSection,
} from "../src/toolbox/reading-normalizer.mjs";
import { createReadingParser } from "../src/lib/reading-parser.js";

function operationsFor(html) {
  const parser = createReadingParser(html, "https://example.test/post");
  const operations = [];
  for (;;) {
    const batch = parser.next();
    operations.push(...batch.operations);
    if (batch.done) return { operations, normalization: parser.normalization };
  }
}

test("structured articles stay in preserve mode", () => {
  const html = Array.from({ length: 8 }, (_, index) =>
    `<p>第${index + 1}段 ${"正文".repeat(45)}</p>`).join("");
  const structure = analyzeReadingStructure(html);
  assert.equal(structure.normalizationMode, "preserve");
  assert.equal(structure.needsRepair, false);
  assert.equal(structure.paragraphs, 8);
});

test("sparse long text with source line breaks uses restore-lines", () => {
  const sparseText = Array.from({ length: 7 }, (_, index) =>
    `第${index + 1}段 ${"正文".repeat(45)}`).join("\n");
  const plan = createReadingNormalizationPlan(`<div>${sparseText}</div>`);
  assert.equal(plan.normalizationMode, "restore-lines");
  assert.equal(plan.restoreSourceBreaks, true);
  assert.ok(plan.meaningfulNewlines >= 6);
  assert.equal(shouldPreserveTextBreaks("第一行\n第二行", false, plan), true);
});

test("protected structures never receive plain-text newline repair", () => {
  const plan = { restoreSourceBreaks: true };
  assert.equal(shouldPreserveTextBreaks("第一行\n第二行", true, plan), false);
  assert.equal(shouldPreserveTextBreaks("第一行\n第二行", false, { restoreSourceBreaks: false }), false);
});

test("dense one-block content with repeated inline section cues enters conservative recovery", () => {
  const body = "正文".repeat(220);
  const html = `<p>${body}<strong>第一道菜</strong>${body}<strong>第二道菜</strong>${body}</p>`;
  const plan = createReadingNormalizationPlan(html);
  assert.equal(plan.normalizationMode, "recover-inline-sections");
  assert.equal(plan.recoverInlineSections, true);
  assert.ok(plan.inlineSectionCues >= 2);
  assert.equal(shouldRecoverInlineSection({
    tag: "strong", text: "第一道菜", textLength: 4, parentTag: "p", plan,
  }), true);
});

test("parser emits structural recovery operations only when plan warrants them", () => {
  const body = "正文".repeat(220);
  const dense = operationsFor(`<p>${body}<strong>第一道菜</strong>${body}<strong>第二道菜</strong>${body}</p>`);
  assert.equal(dense.normalization.normalizationMode, "recover-inline-sections");
  assert.equal(dense.operations.filter((op) => op.type === "recoverSection").length, 2);

  const normal = operationsFor("<p><strong>第一道菜</strong>只是普通短文。</p>");
  assert.equal(normal.normalization.normalizationMode, "preserve");
  assert.equal(normal.operations.some((op) => op.type === "recoverSection"), false);
});

test("standalone advertisement labels are removed from the reading view only", () => {
  assert.equal(isStandaloneNoiseText("广告"), true);
  assert.equal(isStandaloneNoiseText("Advertisement"), true);
  assert.equal(isStandaloneNoiseText("这篇文章讨论广告行业"), false);

  const parsed = operationsFor("<p>正文第一段。</p><p>广告</p><p>正文第二段。</p>");
  assert.ok(parsed.operations.some((op) => op.type === "remove"));
  const legitimate = operationsFor("<p>这篇文章讨论广告行业的发展。</p>");
  assert.equal(legitimate.operations.some((op) => op.type === "remove"), false);
});

test("reading parser keeps normal paragraphs untouched while marking sparse source lines", () => {
  const sparseText = Array.from({ length: 7 }, (_, index) =>
    `第${index + 1}段 ${"正文".repeat(45)}`).join("\n");
  const sparse = operationsFor(`<div>${sparseText}</div>`);
  assert.ok(sparse.operations.some((operation) => operation.type === "text" && operation.preserveBreaks));

  const normal = operationsFor("<p>第一段</p><p>第二段</p>");
  assert.equal(normal.operations.some((operation) => operation.type === "text" && operation.preserveBreaks), false);
});

test("normalizer CSS is separate from visual typography", async () => {
  const { readFile } = await import("node:fs/promises");
  const [normalizerCss, typographyCss] = await Promise.all([
    readFile(new URL("../src/components/ArticleView/ReadingNormalizer.css", import.meta.url), "utf8"),
    readFile(new URL("../src/components/ArticleView/ReadingTypography.css", import.meta.url), "utf8"),
  ]);
  assert.match(normalizerCss, /article-preserve-breaks/);
  assert.match(normalizerCss, /article-recovered-section-label/);
  assert.doesNotMatch(typographyCss, /article-preserve-breaks/);
  assert.match(typographyCss, /line-break:\s*strict/);
  assert.match(typographyCss, /text-autospace:\s*ideograph-alpha ideograph-numeric/);
  assert.match(typographyCss, /\.article-content\s+figcaption/);
  assert.match(typographyCss, /\.article-content\s+table/);
});
