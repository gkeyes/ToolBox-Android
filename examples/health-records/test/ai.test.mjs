import test from "node:test";
import assert from "node:assert/strict";
import { HealthError, normalizeArchive, emptyArchive } from "../web/model.mjs";
import { requestAi, untilAborted, AI_RESPONSE_TIMEOUT_MS, validateAiReport, validateOcr, validateSuggestions, reviewSuggestions, aiPayload } from "../web/ai.mjs";
import { reportImage } from "../web/io.mjs";

const settings = { aiProvider: "gemini", model: "gemini-test" };
const geminiReply = (text) => ({ status: 200, bodyEncoding: "text", headers: {}, body: JSON.stringify({ candidates: [{ finishReason: "STOP", content: { parts: [{ text }] } }] }) });
const host = (request) => ({ storage: { secure: { get: async () => "test-key" } }, network: { request } });
const archive = normalizeArchive({ ...emptyArchive(), records: [
  { id: "r1", date: "2024-01-01", type: "blood", items: [{ name: "WBC", value: "5", unit: "10^9/L" }, { name: "白细胞", value: "6", unit: "10^9/L" }] },
  { id: "r2", date: "2024-01-02", type: "urine", items: [{ name: "蛋白", value: "阴性" }] },
] });

test("cancel stops waiting for a non-streamed reply that never arrives", async () => {
  const sent = [];
  const api = host((request) => { sent.push(request); return new Promise(() => {}); });
  const controller = new AbortController();
  const pending = requestAi(api, settings, "p", {}, null, () => {}, { signal: controller.signal, mode: "summary" });
  await new Promise(setImmediate);
  controller.abort();
  await assert.rejects(pending, { code: "CANCELLED" });
  assert.equal(sent[0].timeoutMs, AI_RESPONSE_TIMEOUT_MS);
  assert.ok(AI_RESPONSE_TIMEOUT_MS >= 10 * 60_000, "the deadline must not cut off long legitimate replies");
});

test("a request with no answer ends with a clear timeout message", async () => {
  const api = host(async () => { throw Object.assign(new Error("deadline"), { code: "NETWORK_TIMEOUT" }); });
  await assert.rejects(requestAi(api, settings, "p", {}, null, () => {}, { mode: "summary" }), (error) => error.code === "AI_TIMEOUT" && /超时/.test(error.message));
});

test("untilAborted passes results through and rejects at once when already aborted", async () => {
  assert.equal(await untilAborted(Promise.resolve(3), new AbortController().signal), 3);
  const controller = new AbortController(); controller.abort();
  await assert.rejects(untilAborted(Promise.resolve(3), controller.signal), { code: "CANCELLED" });
});

test("a valid non-streamed reply is parsed; duplicate keys and wrong fields are rejected", async () => {
  const ok = await requestAi(host(async () => geminiReply('{"summary":"s","sections":[{"title":"t","text":"x"}]}')), settings, "p", {}, null, () => {}, { mode: "summary" });
  assert.deepEqual(validateAiReport(ok), { summary: "s", sections: [{ title: "t", text: "x" }] });
  await assert.rejects(requestAi(host(async () => geminiReply('{"summary":"a","summary":"b","sections":[]}')), settings, "p", {}, null, () => {}, { mode: "summary" }), { code: "AI_INVALID_JSON" });
  await assert.rejects(requestAi(host(async () => geminiReply('{"summary":"a","sections":[],"extra":1}')), settings, "p", {}, null, () => {}, { mode: "summary" }), { code: "AI_INVALID_FORMAT" });
  await assert.rejects(requestAi(host(async () => ({ status: 429, bodyEncoding: "text", headers: {}, body: "{}" })), settings, "p", {}, null, () => {}, { mode: "summary" }), (e) => e.code === "AI_HTTP_ERROR" && /频率/.test(e.message));
});

test("report validator requires exact string fields", () => {
  assert.throws(() => validateAiReport({ summary: "s", sections: [{ title: "t" }] }), HealthError);
  assert.throws(() => validateAiReport({ summary: 1, sections: [] }), HealthError);
  assert.throws(() => validateAiReport({ summary: "s", sections: [] }, { minSections: 1 }), HealthError);
  assert.deepEqual(validateAiReport({ summary: "s", sections: [] }), { summary: "s", sections: [] });
});

test("OCR validator keeps strings, blanks missing values and flags a missing date", () => {
  const { record, missingDate } = validateOcr({ date: "", type: "blood", items: [{ name: "WBC", value: "", unit: "", normal: "3.5-9.5" }] });
  assert.equal(missingDate, true);
  assert.equal(record.items[0].value, "");
  assert.equal(record.items[0].normal, "3.5-9.5");
  assert.throws(() => validateOcr({ date: "2024-01-01", type: "blood", items: [{ name: "WBC", value: 5, unit: "", normal: "" }] }), HealthError);
  assert.throws(() => validateOcr({ date: "2024-01-01", type: "blood", items: [] }), HealthError);
  assert.throws(() => validateOcr({ date: "2024-01-01", type: "serum", items: [{ name: "a", value: "1", unit: "", normal: "" }] }), HealthError);
});

test("cleanup suggestions must use catalog ids and stay within one specimen", () => {
  const ids = aiPayload(archive, "cleanup").metrics;
  const wbc = ids.find((m) => m.name === "WBC").id, cn = ids.find((m) => m.name === "白细胞").id, urine = ids.find((m) => m.name === "蛋白").id;
  const review = reviewSuggestions({ suggestions: [
    { sourceId: wbc, targetId: cn, reason: "同义" },
    { sourceId: urine, targetId: cn, reason: "跨标本" },
    { sourceId: "m99", targetId: cn, reason: "未知" },
    { sourceId: wbc, reason: "缺字段" },
  ] }, archive, "cleanup");
  assert.equal(review.received, 4);
  assert.equal(review.suggestions.length, 0, "a source with conflicting entries is blocked entirely");
  assert.equal(review.rejected.length, 4);
  const clean = reviewSuggestions({ suggestions: [{ sourceId: wbc, targetId: cn, reason: "同义" }] }, archive, "cleanup");
  assert.deepEqual(clean.suggestions.map((s) => [s.source, s.target]), [["WBC", "白细胞"]]);
  assert.throws(() => reviewSuggestions({ suggestions: [], extra: 1 }, archive, "cleanup"), HealthError);
});

test("classify suggestions cannot cross specimens and must cite items of the same record", () => {
  const ok = validateSuggestions({ suggestions: [{ recordId: "r1", type: "blood_bio", evidenceItemIds: ["i0"], reason: "生化" }] }, archive, "classify");
  assert.deepEqual(ok[0].evidenceNames, ["WBC"]);
  assert.throws(() => validateSuggestions({ suggestions: [{ recordId: "r1", type: "urine", evidenceItemIds: ["i0"], reason: "x" }] }, archive, "classify"), HealthError);
  assert.throws(() => validateSuggestions({ suggestions: [{ recordId: "r1", type: "blood_bio", evidenceItemIds: ["i9"], reason: "x" }] }, archive, "classify"), { code: "AI_EVIDENCE_ID" });
  assert.throws(() => validateSuggestions({ suggestions: [{ recordId: "r1", type: "blood_bio", evidenceItemIds: ["i0"], reason: " " }] }, archive, "classify"), HealthError);
});

function png(width, height) {
  const bytes = new Uint8Array(45), view = new DataView(bytes.buffer);
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  view.setUint32(8, 13); bytes.set([0x49, 0x48, 0x44, 0x52], 12); view.setUint32(16, width); view.setUint32(20, height);
  view.setUint32(33, 0); bytes.set([0x49, 0x45, 0x4e, 0x44], 37);
  return bytes;
}

function fakeCanvas(draws) {
  globalThis.createImageBitmap = async () => ({ width: 4000, height: 3000, close() {} });
  globalThis.OffscreenCanvas = class {
    constructor(width, height) { this.width = width; this.height = height; }
    getContext() { return { fillRect() {}, drawImage: (_bitmap, _x, _y, w, h) => draws.push([w, h]) }; }
    async convertToBlob({ type }) { return new Blob([new Uint8Array(Math.ceil(this.width * this.height * (type === "image/png" ? 1 : 0.25)))], { type }); }
  };
}

test("report images are re-encoded at full resolution so EXIF/GPS is not sent", async (t) => {
  t.after(() => { delete globalThis.createImageBitmap; delete globalThis.OffscreenCanvas; });
  const draws = []; fakeCanvas(draws);
  const image = await reportImage(png(4000, 3000), "image/png", 64 * 1024 * 1024);
  assert.deepEqual(draws, [[4000, 3000]]);
  assert.equal(image.reencoded, true); assert.equal(image.compressed, false);
  assert.equal(image.mimeType, "image/png"); assert.equal(image.outputBytes, 12_000_000);
  assert.equal(image.width, 4000);
});

test("only an image too large for the provider is converted and scaled down", async (t) => {
  t.after(() => { delete globalThis.createImageBitmap; delete globalThis.OffscreenCanvas; });
  const draws = []; fakeCanvas(draws);
  const image = await reportImage(png(4000, 3000), "image/png", 2_000_000);
  assert.equal(image.compressed, true); assert.equal(image.mimeType, "image/jpeg");
  assert.ok(image.outputBytes <= 2_000_000);
  assert.ok(image.width < 4000 && image.originalWidth === 4000);
});

test("without OffscreenCanvas the original is sent and reported truthfully", async (t) => {
  t.after(() => { delete globalThis.createImageBitmap; });
  globalThis.createImageBitmap = async () => ({ width: 10, height: 10, close() {} });
  const bytes = png(10, 10);
  const image = await reportImage(bytes, "image/png");
  assert.equal(image.reencoded, false); assert.equal(image.compressed, false);
  assert.equal(image.data, Buffer.from(bytes).toString("base64"));
});
