import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import * as ai from "../web/ai.mjs";
import { HealthError, emptyArchive, normalizeArchive, mergeArchive, metricKey } from "../web/model.mjs";
import { archiveToWorkbook, workbookToArchive, encodeWorkbook } from "../web/backup.mjs";

const require = createRequire(import.meta.url), XLSX = require("../web/vendor/xlsx.full.min.js");
const settings = { aiProvider: "minimax", minimaxModel: "MiniMax-M3", model: "test-gemini" };
const image = { mimeType: "image/jpeg", data: "aGVsbG8=" };
const key = "TEST_MINIMAX_KEY_NOT_REAL";
const ocr = { date: "", type: "blood_bio", items: [{ name: "合成指标", value: "5.3%", unit: "", normal: "范围一 4.0–6.0 | 范围二 7.0–9.0" }] };
const envelope = (content = JSON.stringify(ocr), finish_reason = "stop") => ({ choices: [{ finish_reason, message: { role: "assistant", content, reasoning_content: "not part of the final JSON", reasoning_details: [{ text: "ignore reasoning" }] } }], base_resp: { status_code: 0 } });
function fakeApi(body = envelope(), status = 200) {
  const reads = [], requests = [];
  return {
    reads, requests,
    storage: { secure: { get: async (name) => { reads.push(name); return name === "health.minimax.key" ? key : "TEST_GEMINI_KEY_NOT_REAL"; } } },
    network: { request: async (request) => { requests.push(request); return { status, bodyEncoding: "text", body: typeof body === "string" ? body : JSON.stringify(body) }; } },
  };
}

test("MiniMax requests use the documented endpoint, separate key, bounded image payload and final-only output", async () => {
  const api = fakeApi();
  const output = await ai.requestAi(api, settings, ai.OCR_PROMPT, {}, image);
  assert.deepEqual(output, ocr);
  assert.deepEqual(api.reads, ["health.minimax.key"]);
  const request = api.requests[0];
  const body = JSON.parse(request.body);
  assert.equal(request.url, "https://api.minimax.cn/v1/chat/completions");
  assert.equal(request.method, "POST");
  assert.equal(request.headers.Authorization, `Bearer ${key}`);
  assert.equal(request.headers["x-goog-api-key"], undefined);
  assert.equal(request.url.includes(key), false);
  assert.equal(JSON.stringify(request.body).includes(key), false);
  assert.equal(body.model, "MiniMax-M3");
  assert.equal(body.stream, false);
  assert.equal(body.reasoning_split, true);
  assert.deepEqual(body.thinking, { type: "disabled" });
  assert.equal(body.max_completion_tokens, 8192);
  assert.equal(body.response_format, undefined);
  assert.equal(body.messages[0].role, "system");
  assert.deepEqual(body.messages[1].content[1], { type: "image_url", image_url: { url: "data:image/jpeg;base64,aGVsbG8=", detail: "high" } });
  assert.equal(request.timeoutMs, 300000); assert.equal(request.maxResponseBytes, 512 * 1024);
  const draft = ai.validateOcr(output);
  assert.equal(draft.missingDate, true);
  assert.deepEqual(draft.record.items, ocr.items);
  const geminiApi = fakeApi({ candidates: [{ finishReason: "STOP", content: { parts: [{ text: JSON.stringify(ocr) }] } }] });
  assert.deepEqual(await ai.requestAi(geminiApi, { ...settings, aiProvider: "gemini" }, ai.OCR_PROMPT, {}, image), ocr);
  assert.deepEqual(geminiApi.reads, ["health.gemini.key"]);
  assert.equal(new URL(geminiApi.requests[0].url).hostname, "generativelanguage.googleapis.com");
  assert.equal(geminiApi.requests[0].headers["x-goog-api-key"], "TEST_GEMINI_KEY_NOT_REAL");
});

test("AI JSON integer parameters survive the ToolBox message boundary without native re-encoding", () => {
  for (const provider of ["minimax", "gemini"]) {
    const request = ai.makeAiRequest({ ...settings, aiProvider: provider }, key, "JSON only", {}, image);
    assert.equal(typeof request.body, "string", "send pre-encoded JSON so the native Double codec cannot turn integer parameters into decimals");
    const bodyAfterMessage = JSON.parse(JSON.stringify({ params: request })).params.body;
    assert.equal(bodyAfterMessage, request.body);
    assert.equal(request.headers["Content-Type"], "application/json");
    const field = provider === "minimax" ? "max_completion_tokens" : "maxOutputTokens";
    assert.match(bodyAfterMessage, new RegExp(`"${field}":8192[,}]`));
    assert.doesNotMatch(bodyAfterMessage, new RegExp(`"${field}":8192\\.0`));
    assert.equal(bodyAfterMessage.includes(key), false);
  }
});

test("name cleanup accepts same-specimen synonyms across units without accepting count, method or specimen conflicts", () => {
  const archive = emptyArchive(), row = (name, unit = "") => ({ name, unit, value: "1", normal: "0–2" });
  archive.records = [
    { id: "blood", date: "2026-01-01", type: "blood", items: [row("LH"), row("黄体生成激素", "mIU/mL"), row("细胞计数", "10^9/L"), row("细胞比例", "%"), row("甲（方法一）", "U/L"), row("甲（方法二）", "U/L")] },
    { id: "urine", date: "2026-01-01", type: "urine", items: [row("仅尿样项目")] },
  ];
  const before = structuredClone(archive);
  const validate = (source, target) => ai.validateSuggestions({ suggestions: [{ key: metricKey("blood", source), target, reason: "确认缩写与全称" }] }, archive, "cleanup");
  assert.equal(validate(row("LH"), "黄体生成激素")[0].unit, "");
  assert.throws(() => validate(row("细胞计数", "10^9/L"), "细胞比例"), /不兼容|跨标本/);
  assert.throws(() => validate(row("甲（方法一）", "U/L"), "甲（方法二）"), /不兼容|跨标本/);
  assert.throws(() => validate(row("LH"), "仅尿样项目"), /不兼容|跨标本/);
  assert.deepEqual(archive, before);
});

test("unsupported providers and non-vision MiniMax models fail before reading a key or sending an image", async () => {
  for (const config of [{ ...settings, aiProvider: "foreign" }, { ...settings, aiProvider: ["minimax"] }, { ...settings, minimaxModel: "../foreign" }, { ...settings, minimaxModel: "MiniMax-M2.7" }]) {
    const api = fakeApi();
    await assert.rejects(ai.requestAi(api, config, ai.OCR_PROMPT, {}, image), HealthError);
    assert.deepEqual(api.reads, []); assert.deepEqual(api.requests, []);
  }
  const textRequest = ai.makeAiRequest({ ...settings, minimaxModel: "MiniMax-M2.7" }, key, "JSON only", {});
  assert.equal(JSON.parse(textRequest.body).model, "MiniMax-M2.7");
  assert.equal(JSON.parse(textRequest.body).thinking, undefined);
  assert.throws(() => ai.makeAiRequest(settings, "", "x", {}), /密钥/);
  assert.throws(() => ai.makeAiRequest(settings, "x\nAuthorization: y", "x", {}), /密钥/);
  assert.throws(() => ai.makeAiRequest(settings, key, "x", {}, { mimeType: "image/jpeg", data: "https://example.invalid/photo" }), HealthError);
  assert.throws(() => ai.makeAiRequest(settings, key, "x", { value: "x".repeat(960 * 1024) }), /过大/);
});

test("MiniMax business errors, HTTP errors, truncated output and invalid JSON are not treated as recognition", async () => {
  for (const [body, status, message] of [
    [{ base_resp: { status_code: 1008, status_msg: key } }, 200, /余额/],
    [{ base_resp: { status_code: 2056, status_msg: key } }, 200, /额度/],
    [{ base_resp: { status_code: 1004, status_msg: key } }, 200, /密钥/],
    [{ base_resp: { status_code: 2049, status_msg: key } }, 200, /密钥/],
    [{ error: { type: "authentication_error", message: key } }, 401, /密钥/],
    [{ error: { type: "rate_limit_error", message: key } }, 429, /额度|频率/],
    [{ error: { type: "invalid_request_error", message: key } }, 400, /模型|参数/],
    ["not JSON", 503, /请求失败/],
    [envelope(JSON.stringify(ocr), "length"), 200, /完整|截断/],
    [envelope(JSON.stringify(ocr), "tool_calls"), 200, /完整|截断/],
    [envelope(null), 200, /完整|格式/],
    [envelope("<think>untrusted</think>{\"items\":[]}"), 200, /格式/],
    [envelope("{\"items\":"), 200, /格式/],
    [null, 200, /格式/],
  ]) {
    await assert.rejects(ai.requestAi(fakeApi(body, status), settings, "x", {}), (error) => {
      assert.ok(error instanceof HealthError);
      assert.match(error.message, message);
      assert.equal(error.message.includes(key), false);
      if (status === 400) assert.doesNotMatch(error.message, /权限|需使用 MiniMax-M3/);
      return true;
    });
  }
  assert.deepEqual(await ai.requestAi(fakeApi(envelope("```json\n" + JSON.stringify(ocr) + "\n```")), settings, "x", {}), ocr);
});

test("provider preferences round-trip in JSON and Excel without credentials or changing the active provider on merge", () => {
  const raw = { ...emptyArchive(), settings: { ...emptyArchive().settings, ...settings, minimaxKey: key }, config: { key } };
  const clean = normalizeArchive(raw);
  assert.equal(clean.settings.aiProvider, "minimax");
  assert.equal(clean.settings.minimaxModel, "MiniMax-M3");
  assert.equal(JSON.stringify(clean).includes(key), false);
  assert.deepEqual(normalizeArchive(JSON.parse(JSON.stringify(clean))), clean);
  const bytes = encodeWorkbook(archiveToWorkbook(clean, XLSX), XLSX);
  assert.deepEqual(workbookToArchive(XLSX.read(bytes, { type: "array" }), XLSX, () => "unused"), clean);
  const legacy = normalizeArchive({ records: [], config: { model: "legacy-gemini", key } });
  assert.equal(legacy.settings.aiProvider, "gemini");
  assert.equal(legacy.settings.model, "legacy-gemini");
  assert.equal(mergeArchive(legacy, clean).archive.settings.aiProvider, "gemini");
  assert.throws(() => normalizeArchive({ records: [], settings: { aiProvider: "foreign" } }), HealthError);
});
