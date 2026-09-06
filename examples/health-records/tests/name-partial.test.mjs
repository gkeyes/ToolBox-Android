import test from "node:test";
import assert from "node:assert/strict";
import { alignRecordNames, buildNameCatalog } from "../web/names.mjs";
import { emptyArchive } from "../web/model.mjs";

const item = (name, unit = "", value = "12") => ({ name, unit, value, normal: "0–20" });
const report = (items, id = "draft") => ({ id, date: "2026-09-01", type: "blood_bio", items });
function fixture(items = [item("GPT", "U/L"), item("GLU", "", "5"), item("LH", "U/mL", "2"), item("未匹配项目")]) {
  const archive = { ...emptyArchive(), records: [report([
    item("谷丙转氨酶", "U/L"), item("葡萄糖", "mmol/L"), item("黄体生成激素", "mIU/mL"), item("中性粒细胞百分比", "%"),
  ], "history")] };
  return { source: report(items), catalog: buildNameCatalog(archive) };
}
function match(payload, sourceId, name) {
  return { sourceId, targetId: payload.groups.flatMap((group) => group.candidates).find((candidate) => candidate.name === name).id };
}

test("OCR keeps a valid match when a different source has an unknown target or extra fields", async () => {
  for (const invalid of [() => ({ sourceId: "i1", targetId: "unknown" }), (payload) => ({ ...match(payload, "i1", "葡萄糖"), value: "99" })]) {
    for (const reverse of [false, true]) {
      const { source, catalog } = fixture(), before = structuredClone(source);
      const result = await alignRecordNames(source, catalog, { request: async (_, payload) => {
        const matches = [match(payload, "i0", "谷丙转氨酶"), invalid(payload)];
        return { matches: reverse ? matches.reverse() : matches };
      } });
      assert.equal(result.record.items[0].name, "谷丙转氨酶");
      assert.equal(result.record.items[1].name, "GLU");
      assert.equal(result.review[0].status, "ai");
      assert.equal(result.review[1].status, "failed");
      assert.equal(result.stats.rejectedMatches, 1);
      assert.deepEqual(result.record.items.map(({ name, ...rest }) => rest), before.items.map(({ name, ...rest }) => rest));
      assert.deepEqual(source, before);
    }
  }
});

test("OCR retains AI quantity-conflicting choices with a human-review notice and unchanged measurements", async () => {
  const { source, catalog } = fixture([item("GPT"), item("NEUT#"), item("未匹配项目")]);
  const result = await alignRecordNames(source, catalog, { request: async (_, payload) => ({ matches: [
    match(payload, "i0", "谷丙转氨酶"), match(payload, "i1", "中性粒细胞百分比"),
  ] }) });
  assert.deepEqual(result.record.items.map((row) => row.name), ["谷丙转氨酶", "中性粒细胞百分比", "未匹配项目"]);
  assert.equal(result.review[1].status, "ai"); assert.match(result.review[1].detail, /核对提示.*数量/);
  assert.equal(result.stats.aiMatches, 2); assert.equal(result.stats.rejectedMatches, undefined);
  assert.deepEqual(result.record.items.map(({ name, ...rest }) => rest), source.items.map(({ name, ...rest }) => rest));
});

test("OCR rejects every proposal for a repeated source while retaining independent matches in either order", async () => {
  for (const reverse of [false, true]) {
    const { source, catalog } = fixture();
    const result = await alignRecordNames(source, catalog, { request: async (_, payload) => {
      const matches = [match(payload, "i1", "葡萄糖"), match(payload, "i0", "谷丙转氨酶"), match(payload, "i1", "黄体生成激素")];
      return { matches: reverse ? matches.reverse() : matches };
    } });
    assert.equal(result.record.items[0].name, "谷丙转氨酶");
    assert.equal(result.record.items[1].name, "GLU");
    assert.equal(result.review[1].status, "failed");
    assert.equal(result.stats.rejectedMatches, 2);
  }
});

test("OCR counts an unlocatable invalid source without losing valid or already-local matches", async () => {
  const { source, catalog } = fixture([item("GPT"), item("GLU"), item("谷丙转氨酶", "U/L")]);
  const result = await alignRecordNames(source, catalog, { request: async (_, payload) => ({ matches: [
    match(payload, "i1", "葡萄糖"), match(payload, "i999", "谷丙转氨酶"),
  ] }) });
  assert.equal(result.record.items[1].name, "葡萄糖");
  assert.equal(result.review[1].status, "ai");
  assert.equal(result.review[2].status, "exact");
  assert.equal(result.stats.localMatches, 1);
  assert.equal(result.stats.rejectedMatches, 1);
});

test("OCR distinguishes wholly rejected matches from an empty successful response", async () => {
  const { source, catalog } = fixture();
  const rejected = await alignRecordNames(source, catalog, { request: async () => ({ matches: [{ sourceId: "i0", targetId: "unknown" }] }) });
  const empty = await alignRecordNames(source, catalog, { request: async () => ({ matches: [] }) });
  assert.deepEqual(rejected.record, source);
  assert.equal(rejected.stats.rejectedMatches, 1);
  assert.ok(rejected.review.every((row) => row.status === "failed"));
  assert.ok(empty.review.every((row) => row.status === "review"));
  assert.notEqual(rejected.review[0].detail, rejected.review[1].detail);
  assert.notEqual(rejected.review[1].detail, empty.review[1].detail);
});

test("OCR rejects the whole response when the matches array is absent or exceeds the source budget", async () => {
  const { source, catalog } = fixture();
  for (const makeOutput of [() => ({}), () => ({ matches: "invalid" }), (payload) => ({ matches: Array.from({ length: source.items.length + 1 }, () => match(payload, "i0", "谷丙转氨酶")) })]) {
    const result = await alignRecordNames(source, catalog, { request: async (_, payload) => makeOutput(payload) });
    assert.deepEqual(result.record, source);
    assert.ok(result.review.every((row) => row.status === "failed"));
  }
});
