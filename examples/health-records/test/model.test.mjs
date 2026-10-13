import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  HealthError, normalizeArchive, normalizeRecord, emptyArchive, compareReference, numericValue, canonicalUnit, metricKey,
  mergeArchive, buildIndex, assertNoNewDuplicateMetrics, draftRecord, restoreReferenceDraft, monthlyDays, formatReference, referenceDraft,
} from "../web/model.mjs";
import { createStore, createDraftSlot } from "../web/store.mjs";
import { APP_VERSION } from "../web/version.mjs";

let counter = 0;
const makeId = () => `new-${++counter}`;
const record = (id, date, type, items) => ({ id, date, type, items: items.map(([name, value, unit = "", normal = ""]) => ({ name, value, unit, normal })) });
const archiveWith = (...records) => normalizeArchive({ ...emptyArchive(), records }, makeId);

test("version shown in the app matches the manifest", () => {
  const manifest = JSON.parse(readFileSync(new URL("../web/manifest.json", import.meta.url), "utf8"));
  assert.equal(APP_VERSION, manifest.version);
});

test("records are validated and duplicate ids are reassigned on load", () => {
  assert.throws(() => normalizeRecord({ date: "2024-02-30", type: "blood", items: [{ name: "WBC", value: "5" }] }), HealthError);
  assert.throws(() => normalizeRecord({ date: "2024-02-01", type: "blood", items: [] }), HealthError);
  assert.throws(() => normalizeRecord({ date: "2024-02-01", type: "x", items: [{ name: "WBC", value: "5" }] }), HealthError);
  const archive = archiveWith(record("a", "2024-01-01", "blood", [["WBC", "5"]]), record("a", "2024-01-02", "blood", [["WBC", 6]]));
  assert.notEqual(archive.records[0].id, archive.records[1].id);
  assert.equal(archive.records[1].items[0].value, "6");
  assert.throws(() => normalizeArchive({ records: [], schemaVersion: 2 }), HealthError);
  assert.throws(() => normalizeArchive({ records: [], settings: { aiProvider: "other" } }), HealthError);
});

test("reference comparison handles ranges, one-sided limits and qualitative results", () => {
  assert.equal(compareReference("5.2", "3.5-9.5"), "within");
  assert.equal(compareReference("10", "3.5–9.5"), "high");
  assert.equal(compareReference("3", "3.5~9.5"), "low");
  assert.equal(compareReference("0.4", "<0.5"), "within");
  assert.equal(compareReference("0.5", "<0.5"), "high");
  assert.equal(compareReference("0.5", "≤0.5"), "within");
  assert.equal(compareReference("阴性", "阴性"), "within");
  assert.equal(compareReference("+", "阴性"), "outside");
  assert.equal(compareReference("<5", "3-10"), "unknown");
  assert.equal(compareReference("见备注", "3-10"), "unknown");
  assert.equal(numericValue("1.2345678901234567"), null);
  assert.equal(numericValue("1e3"), 1000);
});

test("reference drafts round-trip through the editor format", () => {
  assert.equal(formatReference(referenceDraft("3.5-9.5")), "3.5–9.5");
  assert.equal(formatReference(referenceDraft("<=0.5")), "≤0.5");
  assert.equal(referenceDraft("见报告").mode, "raw");
  assert.throws(() => formatReference({ ...referenceDraft(""), min: "5", max: "1" }), HealthError);
});

test("metric keys ignore unit spelling but keep blood and urine apart", () => {
  assert.equal(canonicalUnit("10⁹/l"), "10^9/L");
  assert.equal(canonicalUnit("umol/l"), "μmol/L");
  assert.equal(metricKey("blood", { name: " WBC", unit: "10^9/L" }), metricKey("blood", { name: "wbc", unit: "10⁹/l" }));
  assert.notEqual(metricKey("blood_bio", { name: "蛋白", unit: "" }), metricKey("urine", { name: "蛋白", unit: "" }));
});

test("buildIndex groups series, counts types and sorts newest first", () => {
  const archive = archiveWith(
    record("a", "2024-01-01", "blood", [["WBC", "5", "10^9/L", "3.5-9.5"]]),
    record("b", "2024-03-01", "blood", [["WBC", "11", "10⁹/L", "3.5-9.5"], ["HGB", "130", "g/L"]]),
    record("c", "2024-02-01", "urine", [["蛋白", "阴性"]]),
  );
  const index = buildIndex(archive.records);
  assert.deepEqual(index.sorted.map((r) => r.id), ["b", "c", "a"]);
  assert.equal(index.counts.blood, 2); assert.equal(index.counts.urine, 1);
  const wbc = index.metrics.get(metricKey("blood", { name: "WBC", unit: "10^9/L" }));
  assert.deepEqual(wbc.points.map((p) => p.value), ["11", "5"]);
  assert.equal(index.attention.length, 1);
  assert.deepEqual(monthlyDays(archive.records, 2024).slice(0, 3), [1, 1, 1]);
});

test("mergeArchive skips exact duplicates, keeps conflicting ids and revises only on request", () => {
  const current = archiveWith(record("a", "2024-01-01", "blood", [["WBC", "5"]]));
  current.profile.history = "本机病史";
  const incoming = archiveWith(record("a", "2024-01-01", "blood", [["WBC", "5"]]), record("a2", "2024-01-01", "blood", [["WBC", "6"]]));
  incoming.records[1].id = "a";
  incoming.profile.history = "备份病史";
  const merged = mergeArchive(current, incoming, false, makeId);
  assert.equal(merged.added, 1); assert.equal(merged.duplicates, 1); assert.equal(merged.updated, 0);
  assert.equal(merged.archive.records.length, 2);
  assert.equal(new Set(merged.archive.records.map((r) => r.id)).size, 2);
  assert.equal(merged.archive.profile.history, "本机病史");
  assert.equal(mergeArchive(current, incoming, true, makeId).archive.profile.history, "备份病史");

  const revised = archiveWith(record("a", "2024-01-01", "blood", [["WBC", "7"]]));
  const update = mergeArchive(current, revised, false, makeId, true);
  assert.equal(update.updated, 1); assert.equal(update.archive.records.length, 1);
  assert.equal(update.archive.records[0].items[0].value, "7");
  const moved = archiveWith(record("a", "2024-01-09", "blood", [["WBC", "7"]]));
  assert.throws(() => mergeArchive(current, moved, false, makeId, true), HealthError);
});

test("edits cannot introduce a new duplicate metric in one report", () => {
  const before = record("a", "2024-01-01", "blood", [["WBC", "5"], ["HGB", "130"]]);
  assert.throws(() => assertNoNewDuplicateMetrics(before, record("a", "2024-01-01", "blood", [["WBC", "5"], ["wbc", "6"]])), HealthError);
  const existing = record("a", "2024-01-01", "blood", [["WBC", "5"], ["WBC", "6"]]);
  assert.doesNotThrow(() => assertNoNewDuplicateMetrics(existing, existing));
});

test("unsaved editor drafts are rebuilt defensively", () => {
  assert.equal(draftRecord(null), null);
  assert.equal(draftRecord({ draft: { items: "x" } }), null);
  const draft = draftRecord({ draft: { id: "r1", date: "2024-02-30", type: "nope", items: [{ name: "WBC", value: 5, unit: "g" }, null] } });
  assert.equal(draft.id, "r1"); assert.equal(draft.type, "blood");
  assert.match(draft.date, /^\d{4}-\d{2}-\d{2}$/);
  assert.deepEqual(draft.items, [{ name: "WBC", value: "", unit: "g", normal: "" }]);
});

test("a half-edited reference range survives a draft round-trip", () => {
  // Lower bound changed, upper bound cleared: not formattable yet, but must not revert.
  const saved = { mode: "range", min: "4.0", max: "", operator: "≤", limit: "", qualitative: "阴性", raw: "3.5-9.5" };
  const draft = draftRecord(JSON.parse(JSON.stringify({ draft: { id: "r", date: "2024-01-01", type: "blood", items: [{ name: "WBC", value: "5", unit: "", normal: "3.5-9.5", referenceDraft: saved }] } })));
  assert.deepEqual(draft.items[0].referenceDraft, saved);
  assert.throws(() => formatReference(draft.items[0].referenceDraft), HealthError);
  assert.deepEqual(restoreReferenceDraft({ mode: "single", operator: "≥", limit: "7" }, "").mode, "single");
  assert.equal(restoreReferenceDraft({ mode: "single", operator: "≥", limit: "7" }, "").limit, "7");
  assert.equal(restoreReferenceDraft({ mode: "evil", min: "1" }, "3-5").min, "3");
  assert.equal(restoreReferenceDraft({ mode: "single", operator: "!=", limit: 7 }, "").operator, "≤");
  assert.equal(restoreReferenceDraft(null, "<=0.5").limit, "0.5");
});

function memoryStorage() {
  const data = new Map();
  return {
    data, failNext: null,
    async get(key) { return data.has(key) ? structuredClone(data.get(key)) : null; },
    async set(key, value) { if (this.failNext?.(key)) { this.failNext = null; throw Object.assign(new Error("disk full"), { code: "QUOTA_EXCEEDED" }); } data.set(key, structuredClone(value)); },
    async remove(key) { data.delete(key); },
    async keys() { return [...data.keys()]; },
  };
}

test("store commits buckets atomically and reloads the same archive", async () => {
  const storage = memoryStorage();
  let n = 0; const store = createStore(storage, () => `id${++n}`);
  await store.load();
  await store.update((draft) => { draft.records.push(record("r1", "2024-01-01", "blood", [["WBC", "5"]])); });
  assert.equal(store.revision, 1);
  const reopened = createStore(storage, () => `x${++n}`);
  const loaded = await reopened.load();
  assert.deepEqual(loaded.records.map((r) => r.id), ["r1"]);
  assert.ok([...storage.data.keys()].every((key) => key === "health.v1.head" || key.startsWith("health.v1.bucket.")));
});

test("a failed head write rolls back staged buckets and keeps the previous state", async () => {
  const storage = memoryStorage();
  let n = 0; const store = createStore(storage, () => `id${++n}`);
  await store.load();
  await store.update((draft) => { draft.records.push(record("r1", "2024-01-01", "blood", [["WBC", "5"]])); });
  const before = new Map(storage.data);
  storage.failNext = (key) => key === "health.v1.head";
  await assert.rejects(store.update((draft) => { draft.records.push(record("r2", "2024-01-02", "blood", [["WBC", "6"]])); }), { code: "QUOTA_EXCEEDED" });
  assert.deepEqual([...storage.data.keys()].sort(), [...before.keys()].sort());
  assert.deepEqual(store.value.records.map((r) => r.id), ["r1"]);
  assert.equal(store.revision, 1);
  await assert.rejects(store.update(() => {}, { expectedRevision: 0 }), { code: "STALE_EDIT" });
  await store.idle();
});

test("a corrupt head is reported without creating a new empty archive", async () => {
  const storage = memoryStorage();
  storage.data.set("health.v1.head", { format: 1, buckets: ["health.v1.bucket.missing", null, null, null, null, null, null, null], meta: {} });
  await assert.rejects(createStore(storage).load(), { code: "CORRUPT_STORAGE" });
  assert.ok(storage.data.has("health.v1.head"));
});

test("a draft flushed by a cancelled close is cleared when the editor is later saved or discarded", async () => {
  const data = new Map(), calls = [];
  let releaseSet;
  const storage = {
    async set(key, value) { calls.push(["set", key]); await new Promise((resolve) => { releaseSet = resolve; }); data.set(key, value); },
    async remove(key) { calls.push(["remove", key]); data.delete(key); },
  };
  const slot = createDraftSlot(storage, "draft");
  await slot.clear();
  assert.deepEqual(calls, [], "leaving an editor that was never flushed writes nothing");
  const writing = slot.write({ draft: 1 });
  await new Promise((resolve) => setImmediate(resolve));
  const clearing = slot.clear();
  releaseSet(); await writing; await clearing;
  assert.equal(data.has("draft"), false, "clear waits for the in-flight flush, then removes it");
  assert.deepEqual(calls, [["set", "draft"], ["remove", "draft"]]);
  const kept = slot.write({ draft: 2 });
  await new Promise((resolve) => setImmediate(resolve)); releaseSet(); await kept;
  assert.deepEqual(data.get("draft"), { draft: 2 }, "an actual close keeps the flushed draft");
});
