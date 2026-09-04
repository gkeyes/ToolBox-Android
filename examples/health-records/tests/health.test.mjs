import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFile } from "node:fs/promises";
import { compareReference, referenceDraft, formatReference, normalizeArchive, emptyArchive, mergeArchive, buildIndex, monthlyDays, metricKey, canonicalUnit, renameMetric, localDate } from "../web/model.mjs";
import { createStore } from "../web/store.mjs";
import { archiveToWorkbook, workbookToArchive, encodeWorkbook, checkZipBudget } from "../web/backup.mjs";
import { makeAiRequest, validateSuggestions, validateAiReport } from "../web/ai.mjs";
import { imageDimensions } from "../web/image.mjs";
import * as model from "../web/model.mjs";

const require = createRequire(import.meta.url), XLSX = require("../web/vendor/xlsx.full.min.js");
const fixture = normalizeArchive(JSON.parse(await readFile(new URL("demo.json", import.meta.url), "utf8")));
let nextId = 0;
const id = () => `test-${++nextId}`;

test("reference entry and comparisons preserve exclusive, qualitative and uncertain results", () => {
  for (const dash of ["-", "~", "～", "–", "—"]) {
    const draft = referenceDraft(`4.0${dash}10.0`);
    assert.equal(draft.mode, "range"); assert.equal(formatReference(draft), "4.0–10.0");
    assert.equal(compareReference("6.20", `4.0${dash}10.0`), "within");
  }
  for (const [value, normal, expected] of [
    ["0", "0-1", "within"], ["-2", "-5--1", "within"], ["10", "<10", "high"], ["10", "≤10", "within"],
    ["4", ">4", "low"], ["4", "≥4", "within"], ["<5", "0-10", "unknown"], [">12", "0-10", "high"],
    ["<2", "4-10", "low"], ["<5", "<10", "within"], [">5", "0-10", "unknown"], ["1e-3", "0–0.01", "within"],
    ["阴性", "阴性", "within"], ["+", "阴性", "outside"], ["阴性", "0–1", "unknown"], ["3.5 mg", "0–10", "unknown"],
    ["阴", "阴", "within"], ["阴", "阴性(-)", "within"], ["阴性(-)", "阴", "within"],
    ["阳性", "阴", "outside"], ["阴", "阳性", "outside"], ["阴", "0–1", "unknown"],
    ["阴性(-,1:10)", "阴", "unknown"], ["阴性(-)~弱阳性(±)", "阴", "unknown"], ["未记录（原文：.）", "阴", "unknown"],
    ["1.4", "男性0–1；女性0–2", "unknown"], ["1", "", "unknown"], ["7", "10–4", "unknown"],
    ["9007199254740993", "≤9007199254740992", "unknown"], ["1e-324", "0", "unknown"],
    ["1.00000000000000001", "≤1", "unknown"],
  ]) assert.equal(compareReference(value, normal), expected, `${value} vs ${normal}`);
  assert.equal(formatReference({ ...referenceDraft(), min: "0", max: "1" }), "0–1");
  assert.equal(formatReference(referenceDraft("<=5")), "≤5");
  assert.equal(formatReference(referenceDraft("阴性")), "阴性");
  assert.equal(formatReference(referenceDraft("阴")), "阴");
  assert.equal(model.numericValue("阴"), null);
  assert.equal(referenceDraft("男性0–1；女性0–2").mode, "raw");
  assert.throws(() => formatReference({ ...referenceDraft(), min: "1", max: "" }));
  assert.throws(() => formatReference({ ...referenceDraft(), min: "10", max: "4" }));
  assert.throws(() => formatReference({ ...referenceDraft(), min: "−1", max: "−5" }), /下限/);
  assert.equal(localDate(new Date(2026, 8, 3, 0, 5)), "2026-09-03");
});

test("imports retain every valid result, strip secrets and merge without overwriting different reports", () => {
  const raw = { ...fixture, config: { key: "TEST_SECRET_NOT_REAL", base: "https://example.invalid", model: "demo" } };
  const clean = normalizeArchive(raw);
  assert.equal(JSON.stringify(clean).includes("TEST_SECRET_NOT_REAL"), false);
  assert.equal(Object.hasOwn(clean, "config"), false);
  assert.equal(mergeArchive(clean, clean).added, 0);
  const different = structuredClone(clean); different.records[0].items[0].value = "0";
  const merged = mergeArchive(clean, different, false, id);
  assert.equal(merged.added, 1); assert.equal(merged.duplicates, 2); assert.equal(merged.archive.records.length, 4);
  assert.equal(merged.archive.records[0].items[0].value, "6.20");
  assert.equal(merged.archive.records.at(-1).items[0].value, "0");
  assert.throws(() => normalizeArchive({ records: [{ ...clean.records[0], date: "2026-02-30" }] }));
  assert.throws(() => normalizeArchive({ records: [{ ...clean.records[0], items: [{ name: "a", value: null }] }] }));
  assert.throws(() => normalizeArchive({ ...clean, schemaVersion: 99 }));
  for (const type of [["blood"], {}, null, 0, true]) {
    assert.throws(() => normalizeArchive({ records: [{ ...clean.records[0], type }] }), /类型/);
  }
});

test("trends never combine specimens or unequal units, and monthly counts use distinct dates", () => {
  const data = structuredClone(fixture);
  data.records.push({ ...structuredClone(data.records[0]), id: "urine", type: "urine" });
  data.records.push({ ...structuredClone(data.records[0]), id: "different-unit", items: [{ ...data.records[0].items[0], unit: "mg/L" }] });
  const index = buildIndex(data.records), original = metricKey("blood", data.records[0].items[0]);
  assert.equal(index.metrics.get(original).points.length, 3);
  assert.notEqual(original, metricKey("urine", data.records[0].items[0]));
  assert.equal(canonicalUnit("10⁹/L"), canonicalUnit("10^9/L"));
  assert.notEqual(canonicalUnit("mU/L"), canonicalUnit("MU/L"));
  assert.equal(monthlyDays(data.records, 2026)[8], 1);
  assert.throws(() => renameMetric(data, original, "血小板"));
  for (const [a, b] of [["*10^9/l", "10⁹/L"], ["x10^9/L", "10^9/L"], ["umol/l", "µmol/L"], ["ng/ml", "ng/mL"], ["FL", "fL"], ["/uL", "/μL"], ["null", ""]]) assert.equal(canonicalUnit(a), canonicalUnit(b));
  for (const [a, b] of [["IU/L", "U/L"], ["mmol/L", "mg/L"], ["uL", "/uL"], ["mU/L", "MU/L"]]) assert.notEqual(canonicalUnit(a), canonicalUnit(b));
});

test("legacy duplicate metrics remain editable, but introducing another collision is rejected", () => {
  const before = structuredClone(fixture.records[0]);
  before.items.push(structuredClone(before.items[0]));
  const after = structuredClone(before); after.items[0].value = "6.21";
  assert.doesNotThrow(() => model.assertNoNewDuplicateMetrics(before, after));
  after.type = "urine";
  assert.doesNotThrow(() => model.assertNoNewDuplicateMetrics(before, after));
  after.items.push(structuredClone(after.items[0]));
  assert.throws(() => model.assertNoNewDuplicateMetrics(before, after), /重复/);
  assert.throws(() => model.assertNoNewDuplicateMetrics({ ...before, items: [] }, before), /重复/);
});

test("explicit backup corrections update matching IDs without duplicating reports or silently replacing conflicts", () => {
  const incoming = structuredClone(fixture);
  incoming.records[0].items[0].name = "修订后的名称";
  incoming.aliasMap = { "旧名": "修订后的名称" };
  const merged = mergeArchive(fixture, incoming, false, id, true);
  assert.equal(merged.archive.records.length, fixture.records.length);
  assert.equal(merged.updated, 1); assert.equal(merged.added, 0); assert.equal(merged.duplicates, 2);
  assert.equal(merged.archive.records[0].items[0].name, "修订后的名称");
  assert.deepEqual(merged.archive.aliasMap, incoming.aliasMap);
  assert.equal(fixture.records[0].items[0].name, "白细胞");
  const previousCopy = { ...structuredClone(fixture.records[0]), id: "separate-original" };
  const withPrevious = { ...incoming, records: [previousCopy, ...incoming.records] };
  const distinct = mergeArchive(fixture, withPrevious, false, id, true);
  assert.equal(distinct.added, 1); assert.equal(distinct.updated, 1);
  assert.equal(distinct.archive.records.length, fixture.records.length + 1);
  incoming.records[0].date = "2026-09-02";
  assert.throws(() => mergeArchive(fixture, incoming, false, id, true), /日期或类型/);
});

test("failed writes cannot commit state, reopen sees the old snapshot, and concurrent edits serialize", async () => {
  const values = new Map(); let failure = false, writes = 0;
  const storage = { get: async (key) => structuredClone(values.get(key) ?? null), set: async (key, value) => { writes++; if (failure && key === "health.v1.head") throw new Error("simulated write failure"); values.set(key, structuredClone(value)); }, remove: async (key) => values.delete(key), keys: async () => [...values.keys()] };
  const store = createStore(storage, id); await store.load(); await store.update(() => fixture);
  failure = true;
  await assert.rejects(store.update((draft) => { draft.records[0].items[0].value = "999"; }));
  assert.equal(store.value.records[0].items[0].value, "6.20");
  const reopened = createStore(storage, id); assert.equal((await reopened.load()).records.find((r) => r.id === "demo-20260901").items[0].value, "6.20");
  failure = false; writes = 0;
  await Promise.all([store.update((draft) => { draft.profile.age = "30"; }), store.update((draft) => { draft.profile.height = "170"; })]);
  assert.equal(store.value.profile.age, "30"); assert.equal(store.value.profile.height, "170"); assert.equal(writes, 2);
  writes = 0;
  await assert.rejects(store.update((draft) => { draft.records[0].type = ["blood"]; }), /类型/);
  assert.equal(writes, 0);
  const intact = await createStore(storage, id).load();
  assert.equal(intact.records.length, fixture.records.length);
  assert.doesNotThrow(() => buildIndex(intact.records));
  const head = values.get("health.v1.head"), firstBucket = head.buckets.find(Boolean); values.delete(firstBucket);
  await assert.rejects(createStore(storage, id).load());
});

test("legacy and new Excel round trips preserve reports, zeroes, literal text and exclude API keys", () => {
  const data = structuredClone(fixture); data.records[0].items[0].value = "0"; data.profile.history = "=HYPERLINK(\"https://example.invalid\")";
  data.records[0].items.push({ name: "定性测试", value: "阴", unit: "", normal: "阴" });
  data.records[1].items[0].value = "&lt;4.14"; data.records[1].items[0].normal = "&gt;0.9";
  for (const value of ["_x0041_", "_x005F_", "_x000D_", "_x005F_x0041_", "_x0041__x0042_", "_x00aF_", "A\rB"]) {
    data.records[0].items.push({ name: `原文 ${value}`, value, unit: value, normal: value });
    data.lib[value] = { unit: value, normal: value }; data.aliasMap[value] = value;
  }
  const workbook = archiveToWorkbook(data, XLSX);
  assert.equal(workbook.Sheets.UserProfile.B6.t, "s"); assert.equal(workbook.Sheets.UserProfile.B6.f, undefined);
  const originalSheet = structuredClone(workbook.Sheets.HealthData);
  const bytes = encodeWorkbook(workbook, XLSX);
  assert.deepEqual(workbook.Sheets.HealthData, originalSheet);
  checkZipBudget(bytes);
  const restored = workbookToArchive(XLSX.read(bytes, { type: "array" }), XLSX, id);
  assert.deepEqual(restored, data);
  const legacy = archiveToWorkbook(fixture, XLSX);
  legacy.Sheets.Settings = XLSX.utils.json_to_sheet([{ Key: "API Key", Value: "TEST_SECRET_NOT_REAL" }, { Key: "Model Name", Value: "demo" }]);
  assert.equal(JSON.stringify(workbookToArchive(legacy, XLSX, id)).includes("TEST_SECRET_NOT_REAL"), false);
  const corrupt = bytes.slice(); corrupt[corrupt.length - 6] = 255; assert.throws(() => checkZipBudget(corrupt));
});

test("Excel numeric dates respect the workbook epoch and empty sheets still require health headers", () => {
  for (const [date1904, expected] of [[false, "2023-03-15"], [true, "2027-03-16"]]) {
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, XLSX.utils.json_to_sheet([{ 日期: 45000, 类型: "blood", 指标: "合成测试", 数值: "1" }]), "HealthData");
    workbook.Workbook = { WBProps: { date1904 } };
    const actual = XLSX.read(encodeWorkbook(workbook, XLSX), { type: "array", cellDates: false });
    assert.equal(workbookToArchive(actual, XLSX, id).records[0].date, expected);
  }
  const wrong = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wrong, XLSX.utils.aoa_to_sheet([["无关列"]]), "Data");
  assert.throws(() => workbookToArchive(wrong, XLSX, id), /缺少/);
  assert.deepEqual(workbookToArchive(archiveToWorkbook(emptyArchive(), XLSX), XLSX, id), emptyArchive());
});

test("Excel ZIP preflight rejects extended sizes and contradictory directory metadata", () => {
  const bytes = Buffer.from(encodeWorkbook(archiveToWorkbook(fixture, XLSX), XLSX));
  const end = bytes.length - 22, start = bytes.readUInt32LE(end + 16);
  const extraStart = start + 46 + bytes.readUInt16LE(start + 28);
  const extra = Buffer.alloc(12); extra.writeUInt16LE(1); extra.writeUInt16LE(8, 2); extra.writeBigUInt64LE(9n * 1024n * 1024n, 4);
  const extended = Buffer.concat([bytes.subarray(0, extraStart), extra, bytes.subarray(extraStart)]);
  extended.writeUInt16LE(bytes.readUInt16LE(start + 30) + extra.length, start + 30);
  extended.writeUInt32LE(bytes.readUInt32LE(end + 12) + extra.length, end + extra.length + 12);
  assert.throws(() => checkZipBudget(extended), /ZIP64|扩展/);
  const mismatch = Buffer.from(bytes); mismatch.writeUInt32LE(1, start + 24);
  assert.throws(() => checkZipBudget(mismatch), /ZIP|不一致/);
  const split = Buffer.from(bytes); split.writeUInt16LE(1, end + 4);
  assert.throws(() => checkZipBudget(split), /ZIP|分卷/);
  checkZipBudget(bytes);
});

test("AI requests are fixed-origin, key-in-header, bounded and generated changes fail closed", () => {
  const request = makeAiRequest({ model: "test-model" }, "TEST_KEY_NOT_REAL", "Test", { test: true });
  assert.equal(new URL(request.url).hostname, "generativelanguage.googleapis.com");
  assert.equal(request.url.includes("TEST_KEY_NOT_REAL"), false); assert.equal(request.headers["x-goog-api-key"], "TEST_KEY_NOT_REAL");
  assert.throws(() => makeAiRequest({ model: "../foreign" }, "x", "x", {}));
  assert.throws(() => makeAiRequest({ model: "test" }, "", "x", {}));
  assert.throws(() => validateSuggestions({ suggestions: [{ recordId: fixture.records[0].id, type: "urine", reason: "test" }] }, fixture, "classify"));
  assert.throws(() => validateSuggestions({ suggestions: [{ key: "unknown", target: "白细胞", reason: "test" }] }, fixture, "cleanup"));
  assert.throws(() => validateAiReport({ summary: "test", html: "<script>test</script>" }));
  assert.deepEqual(validateAiReport({ summary: "test", sections: [{ title: "<img>", text: "<script>literal</script>" }] }).sections[0].title, "<img>");
});

test("oversized image dimensions are rejected before image decoding", () => {
  const png = new Uint8Array(33), view = new DataView(png.buffer);
  view.setUint32(0, 0x89504e47); view.setUint32(4, 0x0d0a1a0a); view.setUint32(8, 13); view.setUint32(12, 0x49484452);
  view.setUint32(16, 1000); view.setUint32(20, 1200);
  assert.deepEqual(imageDimensions(png, "image/png"), { width: 1000, height: 1200 });
  view.setUint32(16, 100000); assert.throws(() => imageDimensions(png, "image/png"));
  assert.throws(() => imageDimensions(new Uint8Array(10), "image/jpeg"));
});
