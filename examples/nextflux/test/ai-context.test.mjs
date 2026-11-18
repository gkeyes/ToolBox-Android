import test from "node:test";
import assert from "node:assert/strict";
import {
  AI_CONTEXT_OPTIONS, boardInputPolicy, measureMessages, measureText,
  normalizeAiContextWindow, resolveAiContextWindow, validInputPolicy,
} from "../src/toolbox/ai-context.mjs";
import { decodeSettings, settingsState } from "../src/stores/settingsStore.js";
import { getPreference } from "../src/toolbox/preferences.js";

test("context selections normalise persisted numbers without coercing arbitrary data", () => {
  assert.deepEqual(AI_CONTEXT_OPTIONS.map(({ value }) => value), ["auto", "32000", "64000", "128000", "256000", "1000000"]);
  for (const value of AI_CONTEXT_OPTIONS.map(({ value }) => value)) {
    assert.equal(normalizeAiContextWindow(value), value);
    if (value !== "auto") assert.equal(normalizeAiContextWindow(Number(value)), value);
  }
  assert.equal(normalizeAiContextWindow(" 128000 "), "128000");
  for (const value of [undefined, null, true, false, "", "1M", "1e6", "32000.0", 0, -1, 32001, 32000.5, Infinity, NaN, [], [128000], {}, { toString() { throw new Error("must not coerce"); } }]) {
    assert.equal(normalizeAiContextWindow(value), "auto");
  }
});

test("known context families include documented variants and explicit provider prefixes", () => {
  const known = new Map([
    [1047576, ["gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4.1-mini-2025-04-14", "openai/gpt-4.1", "openai/gpt-4.1-nano-2025-04-14"]],
    [128000, ["gpt-4o", "gpt-4o-mini", "gpt-4o-2024-08-06", "openai/gpt-4o-mini-2024-07-18", " GPT-4O-MINI "]],
    [1048576, ["gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite", "google/gemini-2.5-pro", "google/gemini-2.5-flash-lite"]],
  ]);
  for (const [context, models] of known) {
    for (const aiModel of models) {
      assert.equal(resolveAiContextWindow({ aiModel }), context, aiModel);
      assert.deepEqual(boardInputPolicy({ aiModel }), { unit: "utf8", budget: Math.floor(context * 0.8) - 1024 }, aiModel);
    }
  }
});

test("unknown models do not inherit capacity from a loose name match or mismatched provider", () => {
  for (const aiModel of [undefined, null, 128000, {}, "", "custom-model", "gpt-4.10", "gpt-4.1-turbo", "gpt-4.1-preview", "prefix-gpt-4.1", "gpt-4o-mini-search-preview", "gpt-4o-2024-08-06-extra", "other/gpt-4.1", "google/gpt-4.1", "openai/gemini-2.5-pro", "gemini-2.5-flash-preview-05-20", "gemini-2.5-flash-image", "gemini-2.5-pro:free", "google/gemini-2.5-pro/other", "gemini-3-pro"] ) {
    assert.equal(resolveAiContextWindow({ aiModel }), null, String(aiModel));
    assert.deepEqual(boardInputPolicy({ aiModel }), { unit: "characters", budget: 24000 }, String(aiModel));
  }
  assert.deepEqual(boardInputPolicy(), { unit: "characters", budget: 24000 });
  assert.deepEqual(boardInputPolicy(null), { unit: "characters", budget: 24000 });
});

test("an explicit context selection overrides a known model and an unknown proxy", () => {
  for (const { value } of AI_CONTEXT_OPTIONS.filter(({ value }) => value !== "auto")) {
    for (const aiModel of ["gpt-4o-mini", "openai/gpt-4.1", "proxy-name"]) {
      const settings = { aiModel, aiContextWindow: value };
      assert.equal(resolveAiContextWindow(settings), Number(value));
      assert.deepEqual(boardInputPolicy(settings), { unit: "utf8", budget: Math.floor(Number(value) * 0.8) - 1024 });
      assert.ok(validInputPolicy(boardInputPolicy(settings)));
    }
  }
  assert.equal(resolveAiContextWindow({ aiModel: "gpt-4o-mini", aiContextWindow: "invalid" }), 128000);
  assert.deepEqual(boardInputPolicy({ aiModel: "gpt-4.1", aiContextWindow: 32000 }), { unit: "utf8", budget: 24576 });
});

test("UTF-8 budgets include multibyte text in every message while legacy budgets retain JS length", () => {
  assert.equal(measureText("ABC", "utf8"), 3);
  assert.equal(measureText("新闻🙂", "utf8"), 10);
  assert.equal(measureText("新闻🙂", "characters"), 4);
  assert.equal(measureText("é", "utf8"), 2);
  assert.equal(measureText("e\u0301", "utf8"), 3);
  assert.equal(measureText(null, "utf8"), 0);
  const messages = [{ role: "system", content: "请总结\n" }, { role: "user", content: "[1] ABC\n新闻🙂" }];
  assert.equal(measureMessages(messages, { unit: "utf8" }), 28);
  assert.equal(measureMessages(messages, { unit: "characters" }), 16);
  assert.equal(measureMessages([], { unit: "utf8" }), 0);
});

test("checkpoint input policies only accept bounded integer budgets and recognised units", () => {
  for (const unit of ["characters", "utf8"]) {
    for (const budget of [4096, 24000, 1000000]) assert.ok(validInputPolicy({ unit, budget }));
    for (const budget of [4095, 1000001, 24000.5, "24000", 0, -1, NaN, Infinity, undefined]) assert.equal(validInputPolicy({ unit, budget }), false);
  }
  for (const policy of [null, undefined, false, 24000, "utf8", [], {}, { unit: "tokens", budget: 24000 }, { unit: "UTF8", budget: 24000 }]) {
    assert.equal(validInputPolicy(policy), false);
  }
});

test("stored settings migrate to auto and retain valid manual capacity", () => {
  assert.equal(settingsState.get().aiContextWindow, "auto");
  for (const input of ["{broken", "null", "[]", "{}", '{"aiContextWindow":"broken"}', '{"aiContextWindow":{}}']) {
    assert.equal(decodeSettings(input).aiContextWindow, "auto");
  }
  for (const value of [128000, "128000"]) {
    assert.equal(decodeSettings(JSON.stringify({ aiModel: "custom", aiContextWindow: value })).aiContextWindow, "128000");
  }
});

test("manual capacities are encoded while switching back to auto removes the optional field", () => {
  const previous = settingsState.get();
  try {
    settingsState.set({ ...previous, aiContextWindow: "1000000" });
    assert.equal(JSON.parse(getPreference("settings")).aiContextWindow, "1000000");
    settingsState.set({ ...previous, aiContextWindow: "auto" });
    const saved = getPreference("settings");
    assert.equal(Object.hasOwn(JSON.parse(saved), "aiContextWindow"), false);
    assert.equal(decodeSettings(saved).aiContextWindow, "auto");
  } finally { settingsState.set(previous); }
});
