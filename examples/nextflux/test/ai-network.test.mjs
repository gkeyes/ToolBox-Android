import assert from "node:assert/strict";
import test from "node:test";
import { streamChatCompletion } from "../src/toolbox/ai-network.js";

const encode = (value) => new TextEncoder().encode(value);
const turn = () => new Promise((resolve) => setImmediate(resolve));
const contextBody = { error: { code: "context_length_exceeded", message: "private-provider-message" } };

function fixture(t, { status = 400, value = contextBody, bytes = encode(JSON.stringify(value)), chunkSize = 4096 } = {}) {
  const previous = globalThis.window;
  let offset = 0;
  const state = { opens: [], reads: [], cancels: [], content: [], thinking: [] };
  const api = {
    async openStream(request) { state.opens.push(request); return { status, streamId: "ai-error-test" }; },
    async readStream(id, options) {
      state.reads.push({ id, options });
      const size = Math.min(chunkSize, options?.expectedChunkBytes ?? Infinity);
      const data = bytes.subarray(offset, offset + size);
      offset += data.byteLength;
      return { data, done: offset >= bytes.byteLength };
    },
    async cancelStream(id) { state.cancels.push(id); },
  };
  globalThis.window = { ToolBox: { network: api } };
  t.after(() => { if (previous === undefined) delete globalThis.window; else globalThis.window = previous; });
  return {
    api, state,
    run: (options = {}) => streamChatCompletion({
      baseUrl: "https://example.test/v1", apiKey: "synthetic-secret-key",
      body: { model: "synthetic-model", messages: [{ role: "user", content: "private-input" }] },
      onDelta: (text) => state.content.push(text), onReasoning: (text) => state.thinking.push(text),
      ...options,
    }),
  };
}

function assertPrivate(error) {
  assert.doesNotMatch(`${error.message} ${JSON.stringify(error)}`, /private-|synthetic-secret|Authorization/);
  assert.equal(error.raw, undefined);
}

test("a bounded multi-chunk HTTP context error becomes a safe code without exposing the provider body", async (t) => {
  const f = fixture(t, { value: { error: { code: "context_length_exceeded", message: "private-中文正文 synthetic-secret-key" } }, chunkSize: 7 });
  await assert.rejects(f.run(), (error) => {
    assert.equal(error.code, "CONTEXT_LENGTH_EXCEEDED");
    assert.equal(error.status, 400);
    assertPrivate(error);
    return true;
  });
  assert.ok(f.state.reads.length > 1);
  assert.equal(f.state.reads[0].options.expectedChunkBytes, 16 * 1024);
  assert.ok(f.state.reads.at(-1).options.expectedChunkBytes < 16 * 1024);
  assert.deepEqual(f.state.content, []);
  assert.deepEqual(f.state.thinking, []);
  assert.deepEqual(f.state.cancels, ["ai-error-test"]);
});

test("known OpenAI and Gemini input-limit messages require a real numeric overflow", async (t) => {
  const cases = [
    { error: { code: null, type: "invalid_request_error", message: "This model's maximum context length is 128000 tokens. However, you requested 140000 tokens (140000 in the messages)." } },
    { error: { message: "This model's maximum context length is 16,385 tokens. However, your messages resulted in 17,000 tokens." } },
    { error: { code: 400, status: "INVALID_ARGUMENT", message: "The input token count (1050000) exceeds the maximum number of tokens allowed (1048576)." } },
  ];
  for (const [index, value] of cases.entries()) {
    await t.test(`provider shape ${index + 1}`, async (t) => {
      const f = fixture(t, { value });
      await assert.rejects(f.run(), { code: "CONTEXT_LENGTH_EXCEEDED" });
      assert.equal(f.state.opens.length, 1, "transport itself never retries");
    });
  }
});

test("ordinary HTTP errors and ambiguous context text retain their existing safe category", async (t) => {
  const cases = [
    { value: { error: { code: "invalid_api_key", message: "private-invalid-key" } }, status: 401, reads: 0 },
    { value: contextBody, status: 429, reads: 0 },
    { value: contextBody, status: 503, reads: 0 },
    { value: { error: { code: "insufficient_quota", message: "private-quota; maximum context length is 100 tokens. However, you requested 500 tokens." } } },
    { value: { error: { type: "insufficient_quota", message: "private-quota; maximum context length is 100 tokens. However, you requested 500 tokens." } } },
    { value: { error: { code: "invalid_request_error", message: "Please supply context to the request. private-body" } } },
    { value: { error: { message: "maximum context length is 128000 tokens. However, you requested 3000 tokens." } } },
    { value: { error: { code: 400, message: "input token count (10) exceeds the maximum number of tokens allowed (100)" } } },
    { value: { error: "context_length_exceeded private-string" } },
    { bytes: encode('{"error":{"code":"context_length_exceeded"} malformed private-body') },
    { value: null },
  ];
  for (const [index, options] of cases.entries()) {
    await t.test(`non-retryable response ${index + 1}`, async (t) => {
      const f = fixture(t, options);
      await assert.rejects(f.run(), (error) => {
        assert.equal(error.code, "HTTP_ERROR");
        assert.equal(error.status, options.status || 400);
        assertPrivate(error);
        return true;
      });
      if (options.reads === 0) assert.equal(f.state.reads.length, 0);
      assert.deepEqual(f.state.cancels, ["ai-error-test"]);
    });
  }
});

test("oversized and invalid UTF-8 error bodies are not retained or partially classified", async (t) => {
  for (const [index, bytes] of [
    encode(JSON.stringify({ error: { ...contextBody.error, message: "private-".repeat(5000) } })),
    new Uint8Array([0xff, 0xfe, 0xfd]),
  ].entries()) {
    await t.test(`invalid body ${index + 1}`, async (t) => {
      const f = fixture(t, { bytes });
      await assert.rejects(f.run(), { code: "HTTP_ERROR", status: 400 });
      assert.ok(f.state.reads.length <= 4);
      assert.deepEqual(f.state.content, []);
      assert.equal(f.state.cancels.length, 1);
    });
  }
});

test("a failed error-body read preserves the HTTP error instead of reporting a context limit", async (t) => {
  const f = fixture(t);
  f.api.readStream = async () => { throw Object.assign(new Error("private-transport"), { code: "NETWORK_TIMEOUT" }); };
  await assert.rejects(f.run(), (error) => {
    assert.equal(error.code, "HTTP_ERROR");
    assert.equal(error.status, 400);
    assertPrivate(error);
    return true;
  });
  assert.equal(f.state.cancels.length, 1);
});

test("an unending error-body read stops at the shared two-second deadline and ignores late data", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const f = fixture(t);
  let release;
  let entered;
  const reading = new Promise((resolve) => { entered = resolve; });
  let reads = 0;
  f.api.readStream = () => {
    reads += 1;
    entered();
    return new Promise((resolve) => { release = resolve; });
  };
  const failure = assert.rejects(f.run(), { code: "HTTP_ERROR", status: 400 });
  await reading;
  t.mock.timers.tick(2000);
  await failure;
  assert.equal(f.state.cancels.length, 1);
  release({ data: encode(JSON.stringify(contextBody)), done: false });
  await turn();
  assert.equal(reads, 1, "a late response cannot start another read after timeout");
  assert.deepEqual(f.state.content, []);
});

test("immediate empty error chunks have a call limit as well as a time limit", async (t) => {
  const f = fixture(t);
  let reads = 0;
  f.api.readStream = async () => { reads += 1; return { data: new Uint8Array(), done: false }; };
  await assert.rejects(f.run(), { code: "HTTP_ERROR", status: 400 });
  assert.equal(reads, 64);
  assert.equal(f.state.cancels.length, 1);
});

test("cancelling while inspecting an error closes its stream and does not wait for the body", async (t) => {
  const controller = new AbortController();
  const f = fixture(t);
  let release;
  let entered;
  const reading = new Promise((resolve) => { entered = resolve; });
  let reads = 0;
  f.api.readStream = () => {
    reads += 1;
    entered();
    return new Promise((resolve) => { release = resolve; });
  };
  const failure = assert.rejects(f.run({ signal: controller.signal }), { code: "CANCELLED" });
  await reading;
  controller.abort();
  await failure;
  assert.deepEqual(f.state.cancels, ["ai-error-test"]);
  release({ data: encode(JSON.stringify(contextBody)), done: false });
  await turn();
  assert.equal(reads, 1);
});

test("SSE provider errors share the narrow classifier without emitting their content", async (t) => {
  for (const [index, value] of [contextBody, { error: { code: "insufficient_quota", message: "private-quota" } }].entries()) {
    await t.test(`stream error ${index + 1}`, async (t) => {
      const f = fixture(t, { status: 200, bytes: encode(`data: ${JSON.stringify(value)}\n\n`), chunkSize: 11 });
      await assert.rejects(f.run(), (error) => {
        assert.equal(error.code, index === 0 ? "CONTEXT_LENGTH_EXCEEDED" : "INVALID_RESPONSE");
        assertPrivate(error);
        return true;
      });
      assert.deepEqual(f.state.content, []);
      assert.deepEqual(f.state.thinking, []);
      assert.equal(f.state.cancels.length, 1);
    });
  }
});

test("successful SSE text and reasoning still decode normally across UTF-8 chunks", async (t) => {
  const events = [
    { choices: [{ delta: { reasoning_content: "先核对" } }] },
    { choices: [{ delta: { content: "完成分析" }, finish_reason: "stop" }] },
  ].map((value) => `data: ${JSON.stringify(value)}\n\n`).join("") + "data: [DONE]\n\n";
  const f = fixture(t, { status: 200, bytes: encode(events), chunkSize: 7 });
  assert.deepEqual(await f.run(), { finishReason: "stop" });
  assert.deepEqual(f.state.content, ["完成分析"]);
  assert.deepEqual(f.state.thinking, ["先核对"]);
  assert.equal(f.state.cancels.length, 1);
});
