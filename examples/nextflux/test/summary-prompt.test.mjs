import test from "node:test";
import assert from "node:assert/strict";
import { DEFAULT_SUMMARY_PROMPT, LEGACY_SUMMARY_PROMPT, resolveSummaryPrompt } from "../src/toolbox/summary-prompt.mjs";

test("missing and exact legacy built-in prompts use the Markdown default", () => {
  assert.equal(resolveSummaryPrompt(undefined), DEFAULT_SUMMARY_PROMPT);
  assert.equal(resolveSummaryPrompt(LEGACY_SUMMARY_PROMPT), DEFAULT_SUMMARY_PROMPT);
  assert.equal(resolveSummaryPrompt(DEFAULT_SUMMARY_PROMPT), DEFAULT_SUMMARY_PROMPT);
  assert.match(DEFAULT_SUMMARY_PROMPT, /same language as the article/);
  assert.match(DEFAULT_SUMMARY_PROMPT, /Markdown/);
  assert.doesNotMatch(DEFAULT_SUMMARY_PROMPT, /no markdown/);
});

test("custom prompts, including deliberate plain text and empty prompts, remain verbatim", () => {
  for (const prompt of [
    "", " ", "\n\t", "请用三句话总结，保留数据。", "Use plain text, no markdown.",
    ` ${LEGACY_SUMMARY_PROMPT}`, `${LEGACY_SUMMARY_PROMPT}\n`,
    `${LEGACY_SUMMARY_PROMPT} Also include the publication date.`,
    "Use Markdown.\r\nKeep every heading and line break.\n",
  ]) assert.equal(resolveSummaryPrompt(prompt), prompt);
});

test("prompt resolution does not coerce or mutate stored values", () => {
  const custom = Object.freeze({ toString() { throw new Error("must not coerce stored data"); } });
  for (const value of [null, false, 0, custom]) assert.equal(resolveSummaryPrompt(value), value);
  const stored = Object.freeze({ aiPrompt: LEGACY_SUMMARY_PROMPT, aiModel: "custom-model" });
  assert.equal(resolveSummaryPrompt(stored.aiPrompt), DEFAULT_SUMMARY_PROMPT);
  assert.equal(stored.aiPrompt, LEGACY_SUMMARY_PROMPT);
  assert.equal(stored.aiModel, "custom-model");
});
