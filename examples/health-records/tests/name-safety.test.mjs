import test from "node:test";
import assert from "node:assert/strict";
import * as names from "../web/names.mjs";
import { aiPayload, validateSuggestions } from "../web/ai.mjs";
import { emptyArchive, metricKey } from "../web/model.mjs";

const item = (name, unit = "") => ({ name, unit, value: "12", normal: "0–20" });
const record = (name, id = "source", type = "blood_bio", unit = "") => ({ id, date: "2026-09-01", type, items: [item(name, unit)] });
const archiveOf = (...records) => ({ ...emptyArchive(), records });
const context = (name, unit = "") => ({ name, unit, specimen: "血样" });
const suggestion = (name, target, unit = "") => ({ key: metricKey("blood_bio", item(name, unit)), target, reason: `${name} 与 ${target} 是同义名称` });

test("full-width quantity hints are shown for review without vetoing AI or explicitly confirmed aliases", async () => {
  for (const [source, target] of [["中性粒细胞％", "中性粒细胞绝对值"], ["NEUT#", "中性粒细胞百分比"], ["NEUT＃", "NEUT％"]]) {
    assert.equal(names.nameContextCompatible(context(source), context(target)), true);
    assert.match(names.nameContextNotice(context(source), context(target)), /数量/);
    const archive = archiveOf(record(target, "target")), original = record(source), catalog = names.buildNameCatalog(archive);
    let calls = 0;
    const result = await names.alignRecordNames(original, catalog, { request: async (_, payload) => {
      calls++;
      return { matches: [{ sourceId: "i0", targetId: payload.groups[0].candidates[0].id }] };
    } });
    assert.equal(calls, 1); assert.equal(result.record.items[0].name, target); assert.match(result.review[0].detail, /核对/);
    assert.deepEqual(names.confirmedNameAliases(catalog, original.type, [{ item: item(target), review: result.review[0] }]), { [metricKey(original.type, original.items[0])]: target });
    archive.records.push(original);
    assert.match(validateSuggestions({ suggestions: [suggestion(source, target)] }, archive, "cleanup")[0].notice, /数量/);
    archive.aliasMap[metricKey(original.type, original.items[0])] = target;
    const reloaded = names.buildNameCatalog(archive);
    assert.equal(reloaded.aliases.has(metricKey(original.type, original.items[0])), true);
  }
  assert.equal(names.nameContextCompatible(context("NEUT＃"), context("中性粒细胞绝对值", "10^9/L")), true);
  assert.equal(names.nameContextCompatible(context("LH", "U/mL"), context("黄体生成激素", "mIU/mL")), true);
});

test("explicit method annotations produce review notices without overriding AI or user-confirmed mappings", async () => {
  const source = "葡萄糖（Method A）", target = "葡萄糖（Method B）";
  for (const [a, b] of [["葡萄糖（Method_A）", "葡萄糖（Method_B）"], ["葡萄糖（方法 A）", "葡萄糖（方法 B）"], ["葡萄糖（方法_A）", "葡萄糖（方法_B）"]]) {
    assert.equal(names.nameContextCompatible(context(a), context(b)), true); assert.match(names.nameContextNotice(context(a), context(b)), /方法/);
  }
  assert.match(names.nameContextNotice(context(source), context(target)), /方法/);
  assert.equal(names.nameContextCompatible(context(source), context("GLU (method: A)", "mmol/L")), true);
  assert.equal(names.nameContextCompatible(context(source), context("GLU (method_A)", "mmol/L")), true);
  assert.equal(names.nameContextCompatible(context("葡萄糖（方法 A）"), context("GLU（方法：A）", "mmol/L")), true);
  assert.match(names.nameContextNotice(context(source), context("葡萄糖")), /方法/);
  const original = record(source), archive = archiveOf(record(target, "target"));
  const catalog = names.buildNameCatalog(archive);
  const result = await names.alignRecordNames(original, catalog, { request: async (_, payload) => ({ matches: [{ sourceId: "i0", targetId: payload.groups[0].candidates[0].id }] }) });
  assert.equal(result.stats.aiRequests, 1); assert.equal(result.record.items[0].name, target); assert.match(result.review[0].detail, /方法/);
  assert.deepEqual(names.confirmedNameAliases(catalog, original.type, [{ item: item(target), review: result.review[0] }]), { [metricKey(original.type, original.items[0])]: target });
  archive.records.push(original);
  assert.match(validateSuggestions({ suggestions: [suggestion(source, target)] }, archive, "cleanup")[0].notice, /方法/);
  archive.aliasMap[metricKey(original.type, original.items[0])] = target;
  assert.equal(names.buildNameCatalog(archive).aliases.has(metricKey(original.type, original.items[0])), true);
});

test("cleanup cannot reverse or cycle canonical names through different units while one-way synonyms and specimen boundaries remain valid", () => {
  const archive = archiveOf(record("ALT", "a", "blood_bio", "U/L"), record("谷丙转氨酶", "b"), record("GPT", "c", "blood_bio", "IU/L"));
  const before = structuredClone(archive), forward = suggestion("ALT", "谷丙转氨酶", "U/L"), reverse = suggestion("谷丙转氨酶", "ALT");
  for (const selected of [[forward, reverse], [forward, suggestion("谷丙转氨酶", "GPT"), suggestion("GPT", "ALT", "IU/L")]]) {
    assert.throws(() => validateSuggestions({ suggestions: selected }, archive, "cleanup"), /循环|双向/);
    assert.throws(() => names.applyNameSuggestions(archive, selected), /循环|双向/);
    assert.deepEqual(archive, before);
  }
  const oneWay = validateSuggestions({ suggestions: [forward] }, archive, "cleanup");
  names.applyNameSuggestions(archive, oneWay);
  assert.deepEqual(archive.records.map((r) => r.items[0].name), ["谷丙转氨酶", "谷丙转氨酶", "GPT"]);
  assert.deepEqual(archive.records.map((r) => r.items[0].unit), ["U/L", "", "IU/L"]);
  assert.doesNotThrow(() => names.assertNameSuggestionGraph(before, [forward, reverse]), "OCR aliases retain their separate source-unit contexts");
  const split = archiveOf(record("A", "blood-a"), record("B", "blood-b"), record("A", "urine-a", "urine"), record("B", "urine-b", "urine"));
  names.applyNameSuggestions(split, [suggestion("A", "B"), { key: metricKey("urine", item("B")), target: "A" }]);
  assert.deepEqual(split.records.map((r) => r.items[0].name), ["B", "B", "A", "A"]);
  const normalized = archiveOf(record("ALT", "a", "blood_bio", "U/L"), record("ＡＬＴ", "wide", "blood_bio", "IU/L"), record("谷丙转氨酶", "b"));
  assert.throws(() => validateSuggestions({ suggestions: [forward, suggestion("谷丙转氨酶", "ＡＬＴ")] }, normalized, "cleanup"), /循环|双向/);
});

test("cleanup rejects cycles across the selected suggestions and existing scoped or legacy aliases", () => {
  const archive = archiveOf(record("谷丙转氨酶", "a"), record("丙氨酸氨基转移酶", "b"));
  const [a, b] = archive.records.map((r) => r.items[0].name), before = structuredClone(archive);
  assert.throws(() => validateSuggestions({ suggestions: [suggestion(a, b), suggestion(b, a)] }, archive, "cleanup"), /循环/);
  assert.deepEqual(archive, before);
  for (const key of [metricKey("blood_bio", item(b)), b]) {
    const existing = { ...archive, aliasMap: { [key]: a } };
    assert.throws(() => validateSuggestions({ suggestions: [suggestion(a, b)] }, existing, "cleanup"), /循环/);
  }
  const mixed = archiveOf(record("A", "a"), record("B", "b"), record("C", "c"));
  mixed.aliasMap = { A: "B", B: "C" };
  const chosen = validateSuggestions({ suggestions: [suggestion("B", "A")] }, mixed, "cleanup");
  names.applyNameSuggestions(mixed, chosen);
  assert.deepEqual(names.buildNameCatalog(mixed).candidates.map((entry) => entry.name), ["C"]);
});

test("cleanup batch renames each original metric once and rejects collisions without partial mutation", () => {
  const archive = archiveOf(record("甲", "a"), record("乙", "b"), record("丙", "c"));
  const before = structuredClone(archive), chosen = [suggestion("甲", "乙"), suggestion("乙", "丙")];
  names.applyNameSuggestions(archive, chosen);
  assert.deepEqual(archive.records.map((r) => r.items[0].name), ["乙", "丙", "丙"]);
  assert.deepEqual(archive.records.map((r) => r.items.map(({ name, ...rest }) => rest)), before.records.map((r) => r.items.map(({ name, ...rest }) => rest)));
  assert.equal(names.buildNameCatalog(archive).candidates.length, 1);
  const collision = archiveOf({ ...record("甲"), items: [item("甲"), item("乙")] }), unchanged = structuredClone(collision);
  assert.throws(() => names.applyNameSuggestions(collision, [suggestion("甲", "乙")]), /重复/);
  assert.deepEqual(collision, unchanged);
  const loop = archiveOf(record("甲", "a"), record("乙", "b")), loopBefore = structuredClone(loop);
  assert.throws(() => names.applyNameSuggestions(loop, [suggestion("甲", "乙"), suggestion("乙", "甲")]), /循环/);
  assert.deepEqual(loop, loopBefore);
  const equivalent = archiveOf(record("A", "a"), record("B", "b"), record("C", "c"));
  equivalent.aliasMap = {
    [metricKey("blood_bio", item("A"))]: "C",
    '["血样","Ａ",""]': "C",
    [metricKey("blood_bio", item("C"))]: "A",
  };
  names.applyNameSuggestions(equivalent, [suggestion("A", "B")]);
  assert.deepEqual(equivalent.records.map((r) => r.items[0].name), ["B", "B", "C"]);
  assert.deepEqual(names.buildNameCatalog(equivalent).candidates.map((c) => c.name), ["B"]);
  assert.equal(names.buildNameCatalog(equivalent).aliases.get(metricKey("blood_bio", item("C"))), "B");
  assert.equal(Object.hasOwn(equivalent.aliasMap, '["血样","Ａ",""]'), false);
  assert.throws(() => names.applyNameSuggestions(equivalent, [suggestion("B", "C"), { key: '["血样","Ｂ",""]', target: "A" }]), /重复|不同/);
});

test("classification verifies evidence IDs and format without a local medical whitelist or literal-reason matching", () => {
  const archive = archiveOf(record("葡萄糖", "glucose"), record("白细胞", "wbc"));
  archive.profile.history = "PRIVATE_HISTORY";
  const proposed = { recordId: "glucose", type: "blood", reason: "有白细胞和血小板", evidenceItemIds: ["i0"] };
  assert.deepEqual(validateSuggestions({ suggestions: [proposed] }, archive, "classify")[0].evidenceNames, ["葡萄糖"]);
  const payload = aiPayload(archive, "classify");
  assert.deepEqual(payload.records[0], { id: "glucose", type: "blood_bio", items: [{ id: "i0", name: "葡萄糖", unit: "" }] });
  assert.equal(JSON.stringify(payload).includes("PRIVATE_HISTORY"), false);
  for (const change of [{ reason: "" }, { reason: "   " }, { evidenceItemIds: ["i99"] }, { evidenceItemIds: [] }, { evidenceItemIds: ["i0", "i0"] }]) {
    assert.throws(() => validateSuggestions({ suggestions: [{ ...proposed, ...change }] }, archive, "classify"));
  }
  const valid = { recordId: "wbc", type: "blood", reason: "白细胞属于本记录中的血常规项目", evidenceItemIds: ["i0"] };
  assert.deepEqual(validateSuggestions({ suggestions: [valid] }, archive, "classify")[0].evidenceNames, ["白细胞"]);
  assert.equal(validateSuggestions({ suggestions: [{ ...valid, reason: "另有血小板，所以是血常规" }] }, archive, "classify")[0].reason, "另有血小板，所以是血常规");
  for (const change of [{ evidenceItemIds: ["i9"] }, { evidenceItemIds: undefined }]) {
    assert.throws(() => validateSuggestions({ suggestions: [{ ...valid, ...change }] }, archive, "classify"));
  }
  const unknown = archiveOf(record("未知测量", "unknown"));
  assert.equal(validateSuggestions({ suggestions: [{ ...valid, recordId: "unknown", reason: "未知测量", evidenceItemIds: ["i0"] }] }, unknown, "classify")[0].recordId, "unknown");
  assert.deepEqual(archive.records.map((r) => r.type), ["blood_bio", "blood_bio"]);
});
