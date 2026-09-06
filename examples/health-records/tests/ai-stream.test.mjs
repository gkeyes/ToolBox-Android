import test from "node:test";
import assert from "node:assert/strict";
import * as ai from "../web/ai.mjs";

const originalTimeout = globalThis.setTimeout;
// Fast-forward only the transport's pacing delay; timeout/cancellation tests retain their clocks.
test.before(() => { globalThis.setTimeout = (callback, delay, ...args) => originalTimeout(callback, delay === 100 ? 0 : delay, ...args); });
test.after(() => { globalThis.setTimeout = originalTimeout; });

const settings = { aiProvider: "minimax", minimaxModel: "MiniMax-M3" };
const answer = { summary: "合成中文资料", sections: [] };
const frame = (delta, finish_reason = null) => `data: ${JSON.stringify({ choices: [{ index: 0, delta, finish_reason }] })}\r\n\r\n`;
const responseText = () => ": keepalive\r\n\r\n" + frame({ role: "assistant", content: "" }, "") + frame({ reasoning_content: "同一段", reasoning_details: [{ text: "同一段" }] }) + frame({ content: '{"summary":"合成' }) + frame({ content: '中文资料","sections":[]}' }, "stop");
function streamApi(text, width = 7, status = 200) {
  const bytes = new TextEncoder().encode(text), calls = [], cancelled = []; let at = 0;
  return { calls, cancelled,
    storage: { secure: { get: async () => "SYNTHETIC_KEY" } },
    network: {
      request: async () => { throw new Error("Stream must not send a second request"); },
      openStream: async (request, options) => { calls.push({ request, options }); return { streamId: "synthetic", status, headers: { "content-type": status === 200 ? "text/event-stream" : "application/json" } }; },
      readStream: async () => { const data = bytes.slice(at, at + width); at += data.length; return { data, done: at === bytes.length, receivedBytes: at }; },
      cancelStream: async id => { cancelled.push(id); },
    },
  };
}

test("MiniMax streams incremental UTF-8 reasoning separately and enables thinking without an extra request", async () => {
  for (const width of [1, 7, 1024]) {
    const api = streamApi(responseText(), width), reasoning = [], stages = [];
    const output = await ai.requestAi(api, settings, "", {}, null, stage => stages.push(stage), { onReasoning: value => reasoning.push(value) });
    assert.deepEqual(output, answer);
    assert.equal(api.calls.length, 1);
    const body = JSON.parse(api.calls[0].request.body);
    assert.equal(body.stream, true); assert.deepEqual(body.thinking, { type: "adaptive" }); assert.equal(body.reasoning_split, true);
    assert.equal(body.max_completion_tokens, 131072);
    assert.equal(api.calls[0].request.timeoutMs, 300000);
    assert.equal(api.calls[0].request.maxResponseBytes, 4 * 1024 * 1024);
    assert.equal(reasoning.at(-1).text, "同一段");
    assert.equal(JSON.stringify(output).includes("同一段"), false);
    assert.equal(api.cancelled.length, 1);
  }
});

test("stream content is incremental even when successive chunks repeat, and DONE is optional only after stop", async () => {
  const text = frame({ content: '{"summary":"' }) + frame({ content: "哈" }) + frame({ content: "哈" }) + frame({ content: '","sections":[]}' }, "stop");
  for (const suffix of ["", "data: [DONE]\n\n"]) assert.deepEqual(await ai.requestAi(streamApi(text + suffix), settings, "", {}), { summary: "哈哈", sections: [] });
  for (const text of [frame({ content: JSON.stringify(answer) }), frame({ content: JSON.stringify(answer) }, "length"), "data: [DONE]\n\n", responseText() + "data: {broken}\n\n", responseText() + "data: {unfinished"]) await assert.rejects(ai.requestAi(streamApi(text), settings, "", {}));
});

test("stream rejects service errors, tool calls, malformed UTF-8 and oversized replies without leaking bodies", async () => {
  const secret = "SYNTHETIC_PRIVATE_MARKER";
  for (const [api, pattern] of [
    [streamApi(JSON.stringify({ error: { message: secret } }), 20, 401), /密钥|拒绝/],
    [streamApi(`data: ${JSON.stringify({ base_resp: { status_code: 1008, status_msg: secret } })}\n\n`), /余额/],
    [streamApi(`data: ${JSON.stringify({ base_resp: { status_code: 1008, status_msg: secret } })}\n\ndata: [DONE]\n\n`), /余额/],
    [streamApi(frame({ content: JSON.stringify(answer), tool_calls: [{}] }, "stop")), /完整|截断/],
    [streamApi(frame({ content: JSON.stringify(answer), refusal: secret }, "stop")), /完整|截断/],
    [streamApi(`: ${"x".repeat(4 * 1024 * 1024)}\n\n`, 16384), /过大|上限|超/],
  ]) {
    await assert.rejects(ai.requestAi(api, settings, "", {}), error => { assert.match(error.message, pattern); assert.equal(error.message.includes(secret), false); return true; });
    assert.equal(api.cancelled.length, 1);
  }
  const api = streamApi(""); api.network.readStream = async () => ({ data: new Uint8Array([0xff]), done: true, receivedBytes: 1 });
  await assert.rejects(ai.requestAi(api, settings, "", {}));
  assert.equal(api.cancelled.length, 1);
});

test("aborting a pending read or late open closes the stream and rejects without returning partial results", async () => {
  for (const phase of ["open", "read"]) {
    const api = streamApi(responseText()), controller = new AbortController(); let entered, release;
    const waiting = new Promise(resolve => { entered = resolve; });
    const pending = new Promise(resolve => { release = resolve; });
    if (phase === "open") api.network.openStream = async () => { entered(); await pending; return { streamId: "synthetic", status: 200, headers: {} }; };
    else api.network.readStream = async () => { entered(); await pending; return { data: new Uint8Array(), done: true, receivedBytes: 0 }; };
    api.network.cancelStream = async id => { api.cancelled.push(id); release(); };
    const result = ai.requestAi(api, settings, "", {}, null, () => {}, { signal: controller.signal });
    await waiting; controller.abort(); release();
    await assert.rejects(result, error => error.code === "CANCELLED");
    assert.equal(api.cancelled.length, 1);
  }
});

test("HTTP 200 JSON service errors are mapped even when a streamed request was requested", async () => {
  const api = streamApi(JSON.stringify({ base_resp: { status_code: 2013, status_msg: "SYNTHETIC_PRIVATE_MARKER" } }));
  api.network.openStream = async () => ({ streamId: "synthetic", status: 200, headers: { "Content-Type": "application/json; charset=utf-8" } });
  await assert.rejects(ai.requestAi(api, settings, "", {}), error => error.code === "AI_HTTP_ERROR" && /2013/.test(error.message) && !/SYNTHETIC_PRIVATE_MARKER/.test(error.message));
});

test("token truncation, missing terminal frame, empty final content and invalid JSON have distinct safe errors", async () => {
  for (const streaming of [true, false]) {
    for (const [content, finish, code] of [
      [JSON.stringify(answer), "length", "AI_OUTPUT_LIMIT"],
      [JSON.stringify(answer), null, "AI_INCOMPLETE_RESPONSE"],
      ["", "stop", "AI_EMPTY_RESULT"],
      ['{"summary":', "stop", "AI_INVALID_JSON"],
    ]) {
      const api = streaming ? streamApi(frame({ content }, finish), 4096) : {
        storage: { secure: { get: async () => "SYNTHETIC_KEY" } },
        network: { request: async () => ({ status: 200, bodyEncoding: "text", body: JSON.stringify({ choices: [{ finish_reason: finish, message: { content } }] }) }) },
      };
      await assert.rejects(ai.requestAi(api, settings, "", {}), error => error.code === code);
    }
  }
});
