import test from "node:test";
import assert from "node:assert/strict";
import { buildNameCatalog, alignRecordNames, confirmedNameAliases, manualNameCandidates, NAME_BATCH_BYTES, MAX_NAME_BATCHES } from "../web/names.mjs";
import { emptyArchive, metricKey, byteSize, normalizeArchive } from "../web/model.mjs";

const item = (name, unit = "U/L") => ({ name, unit, value: "1", normal: "0–2" });
const report = (items, type = "blood", id = "synthetic") => ({ id, date: "2026-01-31", type, items });
function archiveOf(items) { const archive = emptyArchive(); archive.records = [report(items, "blood", "history")]; return archive; }
const matchByName = (payload, pairs) => ({ matches: payload.groups.flatMap((group) => group.items.filter((source) => pairs[source.name]).map((source) => ({ sourceId: source.id, targetId: group.candidates.find((candidate) => candidate.name === pairs[source.name]).id }))) });

test("canonical directory resolves confirmed aliases without resurrecting obsolete library names or sharing history fields", async () => {
  const archive = archiveOf([item("白细胞", "10^9/L"), item("WBC", "10^9/L"), item("糖化血红蛋白", "%"), item("钾", "mmol/L"), item("免疫球蛋白G", "g/L")]);
  archive.records[0].items[0].value = "HISTORY_RESULT_PRIVATE"; archive.records[0].items[0].normal = "HISTORY_REFERENCE_PRIVATE";
  archive.records.push(report([item("白细胞", "/HPF")], "urine", "urine-history"));
  archive.profile.history = "HISTORY_TEXT_PRIVATE";
  archive.lib = { WBC: { unit: "10^9/L", normal: "0–2" }, "Hb-A": { unit: "%", normal: "0–2" }, "仅在指标库": { unit: "mg/L", normal: "0–2" } };
  archive.aliasMap = { [metricKey("blood", item("WBC", "10^9/L"))]: "白细胞", GLY: "Hb-A", "Hb-A": "糖化血红蛋白" };
  const before = structuredClone(archive), catalog = buildNameCatalog(archive);
  assert.equal(catalog.candidates.filter((candidate) => candidate.name === "白细胞").length, 2);
  assert.equal(catalog.candidates.some((candidate) => ["WBC", "GLY", "Hb-A"].includes(candidate.name)), false);
  assert.equal(catalog.candidates.find((candidate) => candidate.name === "仅在指标库").specimen, "");
  assert.ok(manualNameCandidates(catalog, "blood", "/HPF").every((candidate) => candidate.specimen !== "尿样"));
  assert.ok(manualNameCandidates(catalog, "blood", "").some((candidate) => candidate.name === "糖化血红蛋白"));
  const source = report([item("WBC", "10^9/l"), item("GLY", "%"), item("K", "mmol/L"), item("IgG", "g/L"), item("未知项目", "mg/dL"), item("HbA1c", "")]);
  const original = structuredClone(source); let calls = 0;
  const aligned = await alignRecordNames(source, catalog, { request: async (prompt, payload) => {
    calls++; const text = JSON.stringify(payload);
    for (const privateField of ["HISTORY_RESULT_PRIVATE", "HISTORY_REFERENCE_PRIVATE", "HISTORY_TEXT_PRIVATE", "2026-01-31", "WBC", "GLY"]) assert.equal(text.includes(privateField), false);
    assert.deepEqual(Object.keys(payload), ["groups"]);
    for (const group of payload.groups) {
      assert.deepEqual(Object.keys(group).sort(), ["candidates", "items", "specimen", "unit"]);
      for (const entry of group.items) assert.deepEqual(Object.keys(entry).sort(), ["id", "name"]);
      for (const entry of group.candidates) assert.deepEqual(Object.keys(entry).sort(), ["id", "name", "unit"]);
    }
    return matchByName(payload, { K: "钾", IgG: "免疫球蛋白G", HbA1c: "糖化血红蛋白" });
  } });
  assert.equal(calls, 1);
  assert.deepEqual(aligned.record.items.map((entry) => entry.name), ["白细胞", "糖化血红蛋白", "钾", "免疫球蛋白G", "未知项目", "糖化血红蛋白"]);
  assert.deepEqual(aligned.record.items.map(({ name, ...rest }) => rest), source.items.map(({ name, ...rest }) => rest));
  assert.equal(aligned.record.date, source.date); assert.equal(aligned.record.type, source.type); assert.equal(aligned.record.id, source.id);
  assert.deepEqual(archive, before); assert.deepEqual(source, original);
  assert.equal(aligned.review[5].status, "ai");
  assert.deepEqual(aligned.stats, { aiRequests: 1, localMatches: 2, aiMatches: 3, unresolved: 1 });
  const local = await alignRecordNames(report(source.items.slice(0, 2)), catalog, { request: async () => { calls++; throw new Error("must not call"); } });
  assert.equal(calls, 1); assert.deepEqual(local.record.items.map((entry) => entry.name), ["白细胞", "糖化血红蛋白"]);
  const none = await alignRecordNames(source, buildNameCatalog(emptyArchive()), { request: async () => { calls++; } });
  assert.equal(calls, 1); assert.deepEqual(none.record, source);
});

test("name alignment rejects invented identifiers, extra fields, repeated sources and cross-context or method suggestions", async () => {
  const catalog = buildNameCatalog(archiveOf([item("标准甲", "10^9/L"), item("标准乙", "%"), item("标准丙（方法甲）")]));
  const source = report([item("原名甲", "10^9/L"), item("原名乙", "%"), item("原名丙（方法甲）")]);
  const invalid = [
    () => null,
    () => ({ matches: [{ sourceId: "i999", targetId: "c0" }] }),
    () => ({ matches: [{ sourceId: "i0", targetId: "invented" }] }),
    (payload) => { const valid = matchByName(payload, { "原名甲": "标准甲" }).matches[0]; return { matches: [valid, valid] }; },
    (payload) => { const valid = matchByName(payload, { "原名甲": "标准甲" }).matches[0]; return { matches: [{ ...valid, name: "新造名称" }] }; },
    (payload) => ({ matches: [{ sourceId: "i0", targetId: payload.groups.flatMap((group) => group.candidates).find((entry) => entry.name === "标准乙").id }] }),
    (payload) => ({ matches: [{ sourceId: "i0", targetId: payload.groups.flatMap((group) => group.candidates).find((entry) => entry.name === "标准丙（方法甲）").id }] }),
  ];
  for (const makeOutput of invalid) {
    const result = await alignRecordNames(source, catalog, { request: async (_, payload) => makeOutput(payload) });
    assert.deepEqual(result.record, source); assert.ok(result.review.every((entry) => entry.status === "failed"));
  }
  const uncertain = report([item("某细胞计数", "%"), item("标准丙（方法乙）"), item("标准甲", "")]);
  const guarded = buildNameCatalog(archiveOf([item("某细胞比例", "%"), item("标准丙（方法甲）"), item("标准甲")]));
  let called = false;
  const result = await alignRecordNames(uncertain, guarded, { request: async () => { called = true; } });
  assert.equal(called, false); assert.deepEqual(result.record, uncertain);
});

test("local and AI matches never merge rows or introduce duplicate targets; timeouts and stale results preserve drafts", async () => {
  const archive = archiveOf([item("标准名称")]);
  archive.aliasMap[metricKey("blood", item("别名"))] = "标准名称";
  const catalog = buildNameCatalog(archive);
  for (const existing of [false, true]) {
    const source = report([item("原名甲"), item("原名乙"), ...(existing ? [item("标准名称")] : [])]);
    const result = await alignRecordNames(source, catalog, { request: async (_, payload) => matchByName(payload, { "原名甲": "标准名称", "原名乙": "标准名称" }) });
    assert.deepEqual(result.record, source); assert.ok(result.review.slice(0, 2).every((entry) => entry.detail.includes("重复")));
  }
  const local = report([item("别名"), item("标准名称")]); let called = false;
  const result = await alignRecordNames(local, catalog, { request: async () => { called = true; } });
  assert.equal(called, false); assert.deepEqual(result.record, local);
  const source = report([item("异名")]);
  const failed = await alignRecordNames(source, catalog, { request: async () => { throw { code: "TIMEOUT" }; } });
  assert.deepEqual(failed.record, source); assert.equal(failed.review[0].status, "failed");
  let current = true;
  assert.equal(await alignRecordNames(source, catalog, { isCurrent: () => current, request: async (_, payload) => { current = false; return matchByName(payload, { "异名": "标准名称" }); } }), null);
  assert.equal((await alignRecordNames(report([item("异名")], "urine"), catalog, { request: async () => { throw new Error("cross-specimen request"); } })).review[0].status, "review");
});

test("candidate groups are complete and bounded, with explicit review when a group or call budget is exceeded", async () => {
  function largeArchive(groups, perGroup) {
    const archive = emptyArchive();
    for (let g = 0; g < groups; g++) for (let start = 0; start < perGroup; start += 100) archive.records.push(report(Array.from({ length: Math.min(100, perGroup - start) }, (_, i) => item(`标准${g}-${start + i}${"检验".repeat(40)}（方法${g}）`, `unit${g}`)), "blood", `history-${g}-${start}`));
    return normalizeArchive(archive);
  }
  let calls = 0;
  const big = await alignRecordNames(report([item("异名（方法0）", "unit0")]), buildNameCatalog(largeArchive(1, 300)), { request: async () => { calls++; } });
  assert.equal(calls, 0); assert.ok(big.review[0].detail.includes("目录过大"));
  const limited = await alignRecordNames(report(Array.from({ length: 6 }, (_, g) => item(`异名${g}（方法${g}）`, `unit${g}`))), buildNameCatalog(largeArchive(6, 180)), { request: async (_, payload) => {
    calls++; assert.ok(byteSize(payload) <= NAME_BATCH_BYTES);
    assert.equal(payload.groups.length, 1); assert.equal(payload.groups[0].candidates.length, 180);
    return { matches: [] };
  } });
  assert.equal(calls, MAX_NAME_BATCHES); assert.equal(limited.review.filter((entry) => entry.detail.includes("调用上限")).length, 2);
});

test("only explicit context-preserving choices can become aliases and conflicting or cyclic rules fail closed", () => {
  const catalog = buildNameCatalog(archiveOf([item("标准甲"), item("标准乙")]));
  const review = { originalName: "原名", specimen: "血样", unit: "U/L" };
  assert.deepEqual(confirmedNameAliases(catalog, "blood", [{ item: item("标准甲"), review }]), { [metricKey("blood", item("原名"))]: "标准甲" });
  for (const [type, row, metadata] of [["urine", item("标准甲"), review], ["blood", item("标准甲", ""), review], ["blood", item("手工新增名"), review], ["blood", item("标准甲"), { ...review, unit: "" }]]) assert.deepEqual(confirmedNameAliases(catalog, type, [{ item: row, review: metadata }]), {});
  assert.throws(() => confirmedNameAliases(catalog, "blood", [{ item: item("标准甲"), review }, { item: item("标准乙"), review }]), /不同标准名称/);
  assert.throws(() => confirmedNameAliases(catalog, "blood", [{ item: item("标准乙"), review: { ...review, originalName: "标准甲" } }, { item: item("标准甲"), review: { ...review, originalName: "标准乙" } }]), /循环/);
  const cyclic = archiveOf([item("甲"), item("乙")]); cyclic.aliasMap = { [metricKey("blood", item("甲"))]: "乙", [metricKey("blood", item("乙"))]: "甲" };
  assert.equal(buildNameCatalog(cyclic).candidates.length, 0);
});

test("missing or unequal units still call AI, confirmed legacy aliases remain local, and remembered mappings keep their source context", async () => {
  const archive = archiveOf([item("黄体生成激素", "mIU/mL"), item("旧LH", ""), item("糖化血红蛋白", "%")]);
  archive.aliasMap = { "旧LH": "黄体生成激素" };
  const catalog = buildNameCatalog(archive);
  assert.equal(catalog.candidates.some((candidate) => candidate.name === "旧LH"), false);
  const source = report([item("LH", "U/mL"), item("HbA1c", ""), item("旧LH", "")]);
  let calls = 0;
  const result = await alignRecordNames(source, catalog, { request: async (_, payload) => {
    calls++;
    assert.ok(payload.groups.find((group) => group.unit === "U/mL").candidates.some((candidate) => candidate.name === "黄体生成激素" && candidate.unit === "mIU/mL"));
    return matchByName(payload, { LH: "黄体生成激素", HbA1c: "糖化血红蛋白" });
  } });
  assert.equal(calls, 1);
  assert.deepEqual(result.record.items.map((row) => row.name), ["黄体生成激素", "糖化血红蛋白", "黄体生成激素"]);
  assert.deepEqual(result.record.items.map(({ name, ...rest }) => rest), source.items.map(({ name, ...rest }) => rest));
  assert.deepEqual(result.stats, { aiRequests: 1, localMatches: 1, aiMatches: 2, unresolved: 0 });
  const aliases = confirmedNameAliases(catalog, source.type, result.record.items.map((row, i) => ({ item: row, review: result.review[i] })));
  assert.equal(aliases[metricKey("blood", item("HbA1c", ""))], "糖化血红蛋白");
  assert.equal(aliases[metricKey("blood", item("LH", "U/mL"))], "黄体生成激素");
  const reloaded = buildNameCatalog({ ...archive, aliasMap: { ...archive.aliasMap, ...aliases } });
  const again = await alignRecordNames(source, reloaded, { request: async () => { throw new Error("already confirmed locally"); } });
  assert.deepEqual(again.record, result.record); assert.equal(again.stats.aiRequests, 0);
  const urine = await alignRecordNames(report([item("LH", "U/mL")], "urine"), reloaded, { request: async () => { throw new Error("must not cross specimen"); } });
  assert.equal(urine.record.items[0].name, "LH"); assert.equal(urine.stats.aiRequests, 0);
});
