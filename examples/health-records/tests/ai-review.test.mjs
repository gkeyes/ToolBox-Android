import test from "node:test";
import assert from "node:assert/strict";
import * as ai from "../web/ai.mjs";
import { emptyArchive, metricKey } from "../web/model.mjs";
import { applyNameSuggestions } from "../web/names.mjs";
import { createStore } from "../web/store.mjs";
import { freshDom, settle } from "./memory-dom.mjs";

const item = (name, unit = "") => ({ name, unit, value: "12", normal: "0–20" });
const fixture = () => ({ ...emptyArchive(), records: [
  { id: "a", date: "2026-01-01", type: "blood_bio", items: [item("ALT", "U/L"), item("NEUT#"), item("白细胞", "10^9/L"), item("AST")] },
  { id: "b", date: "2026-02-01", type: "blood_bio", items: [item("谷丙转氨酶"), item("中性粒细胞百分比", "%"), item("谷草转氨酶"), item("葡萄糖")] },
] });
const proposal = (name, target, unit = "") => ({ key: metricKey("blood_bio", item(name, unit)), target, reason: "项目缩写与全称一致" });

test("summary and history prompts share the validator's explicit section and text limits", () => {
  for (const mode of ["summary", "trace"]) {
    assert.match(ai.AI_MODES[mode].prompt, /最多 12 个/);
    assert.match(ai.AI_MODES[mode].prompt, /120/);
    assert.match(ai.AI_MODES[mode].prompt, /10000/);
  }
  const section = { title: "合成标题", text: "合成内容" };
  assert.doesNotThrow(() => ai.validateAiReport({ summary: "合成摘要", sections: Array(12).fill(section) }));
  assert.throws(() => ai.validateAiReport({ summary: "合成摘要", sections: Array(14).fill(section) }));
});

test("AI output contracts require JSON field types and never coerce malformed OCR into a saved record", async () => {
  const request = ai.makeAiRequest({ aiProvider: "minimax", minimaxModel: "MiniMax-M3" }, "SYNTHETIC_KEY", ai.OCR_PROMPT, {});
  const system = JSON.parse(request.body).messages[0].content;
  assert.match(system, /JSON.*对象/); assert.match(system, /转义/);
  const good = { date: "", type: "blood", items: [{ name: "合成", value: "5.3%", unit: "", normal: "" }] };
  assert.doesNotThrow(() => ai.validateOcr(good));
  for (const invalid of [null, [], { ...good, date: null }, { ...good, date: 20260101 }, { ...good, items: {} }, { ...good, items: [{ ...good.items[0], value: 5.3 }] }, { ...good, items: [{ ...good.items[0], unit: null }] }, { ...good, items: [{ ...good.items[0], normal: [] }] }]) assert.throws(() => ai.validateOcr(invalid));
  for (const value of [null, [], { summary: 12, sections: [] }, { summary: "合成", sections: [{}] }, { summary: "合成", sections: [{ title: "标题", text: ["文本"] }] }]) assert.throws(() => ai.validateAiReport(value));
  assert.deepEqual(good.items[0], { name: "合成", value: "5.3%", unit: "", normal: "" });
});

test("name review retains independent valid suggestions when another suggestion is invalid, without changing input", () => {
  const archive = fixture(), original = structuredClone(archive);
  const good = proposal("ALT", "谷丙转氨酶", "U/L"), bad = proposal("NEUT#", "目录不存在的名称");
  for (const suggestions of [[good, bad], [bad, good]]) {
    const reviewed = ai.reviewSuggestions({ suggestions }, archive, "cleanup");
    assert.equal(reviewed.suggestions.length, 1);
    assert.equal(reviewed.suggestions[0].source, "ALT");
    assert.equal(reviewed.rejected.length, 1);
    assert.match(reviewed.rejected[0].reason, /目录/);
  }
  assert.deepEqual(archive, original);
});

test("review rejects every duplicate source and cyclic component without discarding an independent name group", () => {
  const archive = fixture();
  const independent = proposal("AST", "谷草转氨酶");
  for (const conflict of [
    [proposal("ALT", "谷丙转氨酶", "U/L"), proposal("谷丙转氨酶", "ALT")],
    [proposal("ALT", "谷丙转氨酶", "U/L"), proposal("ALT", "AST", "U/L")],
  ]) {
    const reviewed = ai.reviewSuggestions({ suggestions: [...conflict, independent] }, archive, "cleanup");
    assert.deepEqual(reviewed.suggestions.map(s => s.source), ["AST"]);
    assert.equal(reviewed.rejected.length, 2);
    assert.doesNotThrow(() => applyNameSuggestions(structuredClone(archive), reviewed.suggestions));
  }
});

test("classification review isolates missing evidence while preserving a supported independent report", () => {
  const archive = fixture(), before = structuredClone(archive);
  const reviewed = ai.reviewSuggestions({ suggestions: [
    { recordId: "a", type: "blood", evidenceItemIds: ["i2"], reason: "白细胞支持血常规分类" },
    { recordId: "b", type: "blood", evidenceItemIds: ["i99"], reason: "AI 引用不存在的项目编号" },
  ] }, archive, "classify");
  assert.deepEqual(reviewed.suggestions.map(s => s.recordId), ["a"]);
  assert.equal(reviewed.rejected.length, 1);
  assert.deepEqual(archive, before);
});

test("review distinguishes valid empty output from all rejected output and still refuses malformed batches", () => {
  const archive = fixture();
  assert.deepEqual(ai.reviewSuggestions({ suggestions: [] }, archive, "cleanup"), { suggestions: [], rejected: [], received: 0 });
  const rejected = ai.reviewSuggestions({ suggestions: [proposal("ALT", "未知项目", "U/L")] }, archive, "cleanup");
  assert.equal(rejected.suggestions.length, 0); assert.equal(rejected.rejected.length, 1); assert.equal(rejected.received, 1);
  for (const value of [null, {}, { suggestions: {} }, { suggestions: Array(201).fill(null) }]) assert.throws(() => ai.reviewSuggestions(value, archive, "cleanup"));
});

test("cleanup uses short in-request identifiers and maps them back only to the same frozen catalogue", () => {
  const archive = fixture(), payload = ai.aiPayload(archive, "cleanup");
  assert.ok(payload.metrics.every(m => /^m\d+$/.test(m.id) && !Object.hasOwn(m, "key")));
  const source = payload.metrics.find(m => m.name === "ALT"), target = payload.metrics.find(m => m.name === "谷丙转氨酶");
  const reviewed = ai.reviewSuggestions({ suggestions: [{ sourceId: source.id, targetId: target.id, reason: "缩写与全称一致" }] }, archive, "cleanup");
  assert.equal(reviewed.suggestions[0].key, metricKey("blood_bio", item("ALT", "U/L")));
  assert.equal(reviewed.suggestions[0].target, "谷丙转氨酶");
  const invalid = ai.reviewSuggestions({ suggestions: [{ sourceId: source.id, targetId: "invented", reason: "未知" }] }, archive, "cleanup");
  assert.equal(invalid.suggestions.length, 0); assert.equal(invalid.rejected.length, 1);
  assert.ok(payload.metrics.every(m => !Object.hasOwn(m, "value") && !Object.hasOwn(m, "normal") && !Object.hasOwn(m, "date")));
});

test("cleanup validates the exact target identifier rather than a compatible namesake", () => {
  for (const [sourceName, targetName, incompatibleUnit, targetType] of [
    ["WBC", "白细胞", "/HP", "urine"],
    ["NEUT#", "中性粒细胞", "%", "blood"],
  ]) {
    const archive = { ...emptyArchive(), records: [
      { id: "source", date: "2026-01-01", type: "blood", items: [item(sourceName, "10^9/L")] },
      { id: "compatible", date: "2026-01-02", type: "blood", items: [item(targetName, "10^9/L")] },
      { id: "incompatible", date: "2026-01-03", type: targetType, items: [item(targetName, incompatibleUnit)] },
    ] }, original = structuredClone(archive);
    const metrics = ai.aiPayload(archive, "cleanup").metrics;
    const sourceId = metrics.find(m => m.name === sourceName).id;
    const targetId = metrics.find(m => m.name === targetName && m.unit === incompatibleUnit).id;
    const reviewed = ai.reviewSuggestions({ suggestions: [{ sourceId, targetId, reason: "合成错误指向" }] }, archive, "cleanup");
    assert.equal(reviewed.suggestions.length, targetType === "urine" ? 0 : 1);
    assert.equal(reviewed.rejected.length, targetType === "urine" ? 1 : 0);
    if (targetType === "urine") assert.match(reviewed.rejected[0].reason, /标本/);
    else assert.match(reviewed.suggestions[0].notice, /核对/);
    assert.deepEqual(archive, original);
  }
});

test("review groups cycles through existing scoped and legacy aliases without losing independent suggestions", () => {
  for (const scoped of [false, true]) {
    const archive = { ...emptyArchive(), records: ["A", "B", "C", "D", "AST", "谷草转氨酶"].map((name, i) => ({ id: String(i), date: "2026-01-01", type: "blood_bio", items: [item(name)] })) };
    const aliasKey = name => scoped ? metricKey("blood_bio", item(name)) : name;
    archive.aliasMap = { [aliasKey("B")]: "C", [aliasKey("D")]: "A" };
    const suggestions = [proposal("A", "B"), proposal("C", "D"), proposal("AST", "谷草转氨酶")], original = structuredClone(archive);
    for (const ordered of [suggestions, [...suggestions].reverse()]) {
      const reviewed = ai.reviewSuggestions({ suggestions: ordered }, archive, "cleanup");
      assert.deepEqual(reviewed.suggestions.map(s => s.source), ["AST"]);
      assert.equal(reviewed.rejected.length, 2);
      const updated = structuredClone(archive); applyNameSuggestions(updated, reviewed.suggestions);
      const expected = structuredClone(archive.records); expected[4].items[0].name = "谷草转氨酶";
      assert.deepEqual(updated.records, expected);
    }
    assert.deepEqual(archive, original);
  }
});

test("AI final JSON accepts BOM and complete fences but rejects unpaired wrappers, ambiguous output and invalid data", async () => {
  const result = { summary: "合成测试摘要", sections: [] }, json = JSON.stringify(result);
  const api = content => ({
    storage: { secure: { get: async () => "SYNTHETIC_KEY" } },
    network: { request: async () => ({ status: 200, bodyEncoding: "text", body: JSON.stringify({ choices: [{ finish_reason: "stop", message: { content } }], base_resp: { status_code: 0 } }) }) },
  });
  const settings = { aiProvider: "minimax", minimaxModel: "MiniMax-M3" };
  for (const content of [json, `\uFEFF${json}`, `\uFEFF\x20${json}\x20`, `\uFEFF\x20\x60\x60\x60json\n${json}\n\x60\x60\x60`]) assert.deepEqual(await ai.requestAi(api(content), settings, "", {}), result);
  for (const content of [`\x60\x60\x60json\n${json}`, `${json}\n\x60\x60\x60`, `${json}\n${json}`, `<think>${json}</think>`, json.slice(0, -1), '{"summary":"unescaped\nnewline","sections":[]}', '[]', 'null', '{"summary":"first","summary":"second","sections":[]}', '{"summary":"first","\\u0073ummary":"second","sections":[]}', '{"matches":[{"sourceId":"first","sourceId":"second"}]}']) await assert.rejects(ai.requestAi(api(content), settings, "", {}));
  const escaped = { summary: '带引号的 "summary": 不是字段，换行\n和反斜杠\\保留', sections: [{ title: "A", text: "first" }, { title: "B", text: "second" }] };
  assert.deepEqual(await ai.requestAi(api(JSON.stringify(escaped)), settings, "", {}), escaped);
});

test("AI review screen shows blocked suggestions separately and saves only explicitly checked valid names", async () => {
  const timeout = globalThis.setTimeout;
  globalThis.setTimeout = (...args) => { const timer = timeout(...args); timer.unref?.(); return timer; };
  try {
    for (const mixed of [true, false]) {
      const archive = fixture(); archive.settings.aiProvider = "minimax";
      const data = new Map(), storage = { get: async key => structuredClone(data.get(key) ?? null), set: async (key, value) => data.set(key, structuredClone(value)), remove: async key => data.delete(key), keys: async () => [...data.keys()], secure: { get: async () => "SYNTHETIC_KEY" } };
      const store = createStore(storage); await store.load(); await store.update(() => archive);
      const suggestions = [proposal("NEUT#", "目录不存在的名称"), ...(mixed ? [proposal("ALT", "谷丙转氨酶", "U/L")] : [])];
      const screen = freshDom({ ready: async () => ({}), storage, network: { request: async request => ({ status: 200, bodyEncoding: "text", body: JSON.stringify({ choices: [{ finish_reason: "tool_calls", message: { content: "", tool_calls: [{ type: "function", function: { name: JSON.parse(request.body).tools[0].function.name, arguments: JSON.stringify({ suggestions }) } }] } }], base_resp: { status_code: 0 } }) }) } });
      await import(`../web/app.mjs?partial-review=${mixed}`); await settle();
      const click = async (root, label) => { const button = root.querySelectorAll("button").find(n => n.textContent.startsWith(label)); assert.ok(button, label); await button.fire("click"); await settle(); };
      await click(screen.navigation, "我的"); await click(screen.main, "AI 资料助手"); await click(screen.main, "指标名称整理"); await click(screen.dialog, "同意发送并整理");
      assert.ok(screen.dialog.textContent.includes("无法应用"));
      assert.ok(screen.dialog.textContent.includes("目录不存在的名称"));
      assert.equal(screen.dialog.textContent.includes("未能完成整理"), false);
      assert.equal(screen.dialog.textContent.includes("暂无修改建议"), false);
      assert.deepEqual((await createStore(storage).load()).records, archive.records);
      const checks = screen.dialog.querySelectorAll("input").filter(n => n.getAttribute("type") === "checkbox");
      assert.equal(checks.length, mixed ? 1 : 0);
      if (mixed) {
        assert.equal(Boolean(checks[0].checked), false); checks[0].checked = true; await checks[0].fire("change");
        await click(screen.dialog, "应用已勾选的建议");
        const expected = structuredClone(archive.records); expected[0].items[0].name = "谷丙转氨酶";
        assert.deepEqual((await createStore(storage).load()).records, expected);
      } else assert.equal(screen.dialog.querySelectorAll("button").some(n => n.textContent === "应用已勾选的建议"), false);
    }
  } finally { globalThis.setTimeout = timeout; }
});
