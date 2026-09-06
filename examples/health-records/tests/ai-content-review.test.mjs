import test from "node:test";
import assert from "node:assert/strict";
import { reviewSuggestions, validateSuggestions, aiPayload } from "../web/ai.mjs";
import { alignRecordNames, buildNameCatalog, applyNameSuggestions } from "../web/names.mjs";
import { emptyArchive } from "../web/model.mjs";

const item = name => ({ name, value: "12", normal: "原参考", unit: "" });
const record = (id, name, type = "blood_bio") => ({ id, date: "2026-09-01", type, items: [item(name)] });
const archiveOf = (...records) => ({ ...emptyArchive(), records });

test("classification displays model reasoning and original evidence without a medical whitelist or literal-name veto", () => {
  const archive = archiveOf(record("a", "未收录项目（方法标注）")), before = structuredClone(archive);
  const proposal = { recordId: "a", type: "blood", evidenceItemIds: ["i0"], reason: "AI 的分类解释，由用户核对，不逐字复述名称。" };
  const review = reviewSuggestions({ suggestions: [proposal] }, archive, "classify");
  assert.equal(review.suggestions.length, 1); assert.equal(review.rejected.length, 0);
  assert.equal(review.suggestions[0].reason, proposal.reason);
  assert.deepEqual(review.suggestions[0].evidenceNames, [archive.records[0].items[0].name]);
  assert.deepEqual(archive, before);
});

test("format, real evidence IDs, duplicate writes and the user blood/urine boundary still block application, not inspection", () => {
  const archive = archiveOf(record("a", "白细胞"));
  const proposal = { recordId: "a", type: "blood", evidenceItemIds: ["i0"], reason: "AI 原始理由" };
  for (const change of [{ evidenceItemIds: ["i99"] }, { recordId: "unknown" }, { type: "unknown" }, { type: "urine" }, { reason: "" }, { evidenceItemIds: [] }]) {
    const value = { ...proposal, ...change }, review = reviewSuggestions({ suggestions: [value] }, archive, "classify");
    assert.equal(review.suggestions.length, 0); assert.equal(review.rejected.length, 1);
    assert.deepEqual(review.rejected[0].proposal, value, "rejected payload remains available for human inspection");
  }
  assert.throws(() => validateSuggestions({ suggestions: [proposal, proposal] }, archive, "classify"));
});

test("name heuristics advise instead of censoring AI choices; explicit application changes only names", async () => {
  for (const [source, target] of [["NEUT#", "中性粒细胞百分比"], ["葡萄糖（Method A）", "葡萄糖（Method B）"]]) {
    const archive = archiveOf(record("a", source), record("b", target)), before = structuredClone(archive);
    const metrics = aiPayload(archive, "cleanup").metrics;
    const review = reviewSuggestions({ suggestions: [{ sourceId: metrics.find(m => m.name === source).id, targetId: metrics.find(m => m.name === target).id, reason: "AI 建议原文" }] }, archive, "cleanup");
    assert.equal(review.suggestions.length, 1); assert.equal(review.rejected.length, 0);
    assert.match(review.suggestions[0].notice, /核对/); assert.deepEqual(archive, before);
    applyNameSuggestions(archive, review.suggestions);
    assert.equal(archive.records[0].items[0].name, target);
    assert.deepEqual(archive.records.map(r => r.items.map(({ name, ...rest }) => rest)), before.records.map(r => r.items.map(({ name, ...rest }) => rest)));
    const aligned = await alignRecordNames(before.records[0], buildNameCatalog(archiveOf(record("target", target))), { request: async (_, payload) => ({ matches: [{ sourceId: "i0", targetId: payload.groups[0].candidates[0].id }] }) });
    assert.equal(aligned.stats.aiRequests, 1); assert.equal(aligned.record.items[0].name, target);
    assert.match(aligned.review[0].detail, /核对/); assert.deepEqual(before.records[0].items[0].name, source);
  }
});
