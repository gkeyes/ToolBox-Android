import test from "node:test";
import assert from "node:assert/strict";
import { aiPayload, makeAiRequest, requestAi, reviewSuggestions, validateAiReport, validateOcr } from "../web/ai.mjs";
import { alignRecordNames, buildNameCatalog } from "../web/names.mjs";
import { emptyArchive } from "../web/model.mjs";

const settings = { aiProvider: "minimax", minimaxModel: "MiniMax-M3" };
const schema = mode => JSON.parse(makeAiRequest(settings, "SYNTHETIC_KEY", "合成测试", {}, null, false, mode).body).tools[0].function;
const report = { summary: '保留 "引号"、换行\n及反斜杠\\', sections: [{ title: "合成测试", text: '原文 {"result":"5.3%"}\n下一行' }] };
const frame = (delta, finish_reason = null) => `data: ${JSON.stringify({ choices: [{ index: 0, delta, finish_reason }] })}\n\n`;
function apiReply(message, finish_reason = "tool_calls") {
  return { storage: { secure: { get: async () => "SYNTHETIC_KEY" } }, network: { request: async () => ({ status: 200, bodyEncoding: "text", body: JSON.stringify({ choices: [{ finish_reason, message }] }) }) } };
}
const call = (name, args = JSON.stringify(report)) => ({ type: "function", function: { name, arguments: args } });

test("all AI operations send program-serialized fill-in templates and documented function schemas", () => {
  for (const [mode, keys] of [["summary", ["summary", "sections"]], ["trace", ["summary", "sections"]], ["ocr", ["date", "type", "items"]], ["cleanup", ["suggestions"]], ["classify", ["suggestions"]], ["names", ["matches"]]]) {
    const body = JSON.parse(makeAiRequest(settings, "SYNTHETIC_KEY", "任务规则", {}, null, false, mode).body);
    assert.equal(body.tools.length, 1);
    assert.deepEqual(Object.keys(body.tools[0].function.parameters.properties), keys);
    assert.deepEqual(body.tools[0].function.parameters.required, keys);
    assert.equal(body.tools[0].function.parameters.additionalProperties, false);
    const text = body.messages[1].content[0].text;
    assert.match(text, /填写模板/); assert.match(text, /只填|填写/);
    assert.ok(text.includes(JSON.stringify(mode === "summary" || mode === "trace" ? { summary: "", sections: [{ title: "", text: "" }] } : mode === "ocr" ? { date: "", type: "", items: [{ name: "", value: "", unit: "", normal: "" }] } : mode === "names" ? { matches: [] } : mode === "cleanup" ? { suggestions: [{ sourceId: "", targetId: "", reason: "" }] } : { suggestions: [{ recordId: "", type: "", evidenceItemIds: [], reason: "" }] })));
    for (const unsupported of ["response_format", "tool_choice", "strict"]) assert.equal(body[unsupported], undefined);
  }
});

test("structured result accepts only the one declared formatter and preserves quoted text without repair", async () => {
  const name = schema("trace").name;
  assert.deepEqual(await requestAi(apiReply({ content: "", tool_calls: [call(name)] }), settings, "", {}, null, () => {}, { mode: "trace" }), report);
  assert.deepEqual(await requestAi(apiReply({ content: "untrusted accompanying prose, never parsed", tool_calls: [call(name)] }), settings, "", {}, null, () => {}, { mode: "trace" }), report);
  assert.deepEqual(await requestAi(apiReply({ content: JSON.stringify(report) }, "stop"), settings, "", {}, null, () => {}, { mode: "trace" }), report);
  const ambiguous = apiReply({ tool_calls: [call(name)] }), original = ambiguous.network.request;
  ambiguous.network.request = async () => { const response = await original(), envelope = JSON.parse(response.body); envelope.choices.push(envelope.choices[0]); return { ...response, body: JSON.stringify(envelope) }; };
  await assert.rejects(requestAi(ambiguous, settings, "", {}, null, () => {}, { mode: "trace" }), error => error.code === "AI_INVALID_FORMAT");
  for (const [message, finish] of [
    [{ tool_calls: [call("invented_tool")] }, "tool_calls"],
    [{ tool_calls: [call(name), call(name)] }, "tool_calls"],
    [{ tool_calls: [call(name)] }, "stop"],
    [{ content: JSON.stringify({ invented: "not the template" }) }, "stop"],
    [{ tool_calls: [call(name, '{"summary":"unescaped "quote"","sections":[]}')] }, "tool_calls"],
  ]) await assert.rejects(requestAi(apiReply(message, finish), settings, "", {}, null, () => {}, { mode: "trace" }));
});

test("streamed formatter arguments assemble independently of reasoning and accompanying prose, rejecting unknown calls", async () => {
  const name = schema("summary").name, args = JSON.stringify(report), reasoning = [];
  const text = frame({ reasoning_content: "合成过程文字" }) + frame({ tool_calls: [{ index: 0, id: "synthetic", type: "function", function: { name, arguments: args.slice(0, 31) } }] }) + frame({ tool_calls: [{ index: 0, id: "", type: "", function: { name: "", arguments: args.slice(31) } }] }) + frame({}, "tool_calls");
  const api = wire => { let sent = false; return { storage: { secure: { get: async () => "SYNTHETIC_KEY" } }, network: {
    openStream: async () => ({ streamId: "synthetic", status: 200, headers: { "content-type": "text/event-stream" } }),
    readStream: async () => { assert.equal(sent, false); sent = true; return { data: new TextEncoder().encode(wire), done: true }; }, cancelStream: async () => {},
  } }; };
  assert.deepEqual(await requestAi(api(text), settings, "", {}, null, () => {}, { mode: "summary", onReasoning: value => reasoning.push(value.text) }), report);
  assert.equal(reasoning.at(-1), "合成过程文字");
  assert.deepEqual(await requestAi(api(frame({ content: JSON.stringify(report) }, "stop")), settings, "", {}, null, () => {}, { mode: "summary" }), report);
  await assert.rejects(requestAi(api(frame({ content: '{"invented":true}' }, "stop")), settings, "", {}, null, () => {}, { mode: "summary" }));
  assert.deepEqual(await requestAi(api(frame({ content: "untrusted accompanying prose" }) + text), settings, "", {}, null, () => {}, { mode: "summary" }), report);
  for (const wire of [text.replace(name, "untrusted_tool"), text.replace('"index":0,"id":""', '"index":1,"id":""'), text.replace('"id":""', '"id":"different-call"'), text + frame({ tool_calls: [{ index: 0, function: { arguments: "{}" } }] }), text.replace('"finish_reason":"tool_calls"', '"finish_reason":"length"')]) await assert.rejects(requestAi(api(wire), settings, "", {}, null, () => {}, { mode: "summary" }));
});

test("name-match template prepopulates source IDs and treats explicit empty targets as unresolved", async () => {
  const row = name => ({ name, value: "5.3%", unit: "", normal: "原参考" });
  const archive = { ...emptyArchive(), records: [{ id: "history", date: "2026-01-01", type: "blood_bio", items: [row("谷丙转氨酶")] }] };
  const source = { id: "draft", date: "2026-01-02", type: "blood_bio", items: [row("GPT"), row("未知项目")] }, before = structuredClone(source);
  const result = await alignRecordNames(source, buildNameCatalog(archive), { request: async (_, payload) => {
    const body = JSON.parse(makeAiRequest(settings, "SYNTHETIC_KEY", "", payload, null, false, "names").body);
    assert.ok(body.messages[1].content[0].text.includes(JSON.stringify({ matches: [{ sourceId: "i0", targetId: "" }, { sourceId: "i1", targetId: "" }] })));
    const fields = body.tools[0].function.parameters.properties.matches.items.properties;
    assert.deepEqual(fields.sourceId.enum, ["i0", "i1"]);
    assert.deepEqual(fields.targetId.enum, ["", payload.groups[0].candidates[0].id]);
    return { matches: [{ sourceId: "i0", targetId: payload.groups[0].candidates[0].id }, { sourceId: "i1", targetId: "" }] };
  } });
  assert.equal(result.stats.aiMatches, 1); assert.equal(result.stats.unresolved, 1); assert.equal(result.stats.rejectedMatches, undefined);
  assert.equal(result.review[1].status, "review"); assert.deepEqual(source, before);
  assert.deepEqual(result.record.items.map(({ name, ...rest }) => rest), before.items.map(({ name, ...rest }) => rest));
});

test("report and OCR schemas reject extra fields rather than silently accepting an invented structure", () => {
  assert.throws(() => validateAiReport({ ...report, result: "unexpected" }));
  assert.throws(() => validateAiReport({ ...report, sections: [{ ...report.sections[0], advice: "unexpected" }] }));
  const ocr = { date: "", type: "blood", items: [{ name: "合成项目", value: "5.3%", unit: "", normal: "" }] };
  assert.throws(() => validateOcr({ ...ocr, diagnosis: "unexpected" }));
  assert.throws(() => validateOcr({ ...ocr, items: [{ ...ocr.items[0], result: 5.3 }] }));
});

test("suggestion template field checks isolate malformed entries and Gemini receives the same fill-in format", () => {
  const archive = { ...emptyArchive(), records: [{ id: "synthetic", date: "2026-01-01", type: "blood_bio", items: ["GPT", "谷丙转氨酶", "AST", "谷草转氨酶"].map(name => ({ name, value: "1", unit: "", normal: "" })) }] };
  const payload = aiPayload(archive, "cleanup"), id = name => payload.metrics.find(item => item.name === name).id;
  const reviewed = reviewSuggestions({ suggestions: [
    { sourceId: id("GPT"), targetId: id("谷丙转氨酶"), reason: "合成同义名称", value: "99" },
    { sourceId: id("AST"), targetId: id("谷草转氨酶"), reason: "合成同义名称" },
  ] }, archive, "cleanup");
  assert.equal(reviewed.rejected.length, 1); assert.equal(reviewed.suggestions.length, 1);
  assert.equal(reviewed.suggestions[0].source, "AST");
  assert.throws(() => reviewSuggestions({ suggestions: [], invented: true }, archive, "cleanup"));
  const body = JSON.parse(makeAiRequest({ aiProvider: "gemini", model: "synthetic" }, "SYNTHETIC_KEY", "", payload, null, false, "cleanup").body);
  assert.match(body.contents[0].parts[0].text, /填写模板/);
  assert.equal(body.generationConfig.responseMimeType, "application/json");
  assert.equal(body.tools, undefined);
});
