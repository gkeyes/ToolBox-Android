import assert from "node:assert/strict";
import test from "node:test";
import { streamChatCompletion } from "../src/toolbox/ai-network.js";

const encode = (value) => new TextEncoder().encode(value);
const turn = () => new Promise((resolve) => setImmediate(resolve));
const contextBody = { error: { code: "context_length_exceeded", message: "private-provider-message" } };
const successStream = encode(`data: ${JSON.stringify({ choices: [{ delta: { content: "完成" }, finish_reason: "stop" }] })}\n\ndata: [DONE]\n\n`);

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

test("GPT-6.2 requests omit unsupported sampling fields without changing the caller or other request fields", async (t) => {
  for (const model of ["gpt-6.2-sol", "gpt-6.2-astra", "openai/gpt-6.2-sol", "openai/gpt-6.2-astra"]) {
    await t.test(model, async (t) => {
      const f = fixture(t, { status: 200, bytes: successStream });
      const messages = Object.freeze([Object.freeze({ role: "user", content: "合成测试文章" })]);
      const body = Object.freeze({ model, messages, temperature: 0.3, top_p: 0.8, logprobs: true,
        top_logprobs: 3, max_completion_tokens: 2048, reasoning_effort: "medium", stream: false });
      const before = JSON.stringify(body);
      assert.deepEqual(await f.run({ body }), { finishReason: "stop" });
      assert.equal(f.state.opens.length, 1, "compatibility is applied before the first request, not by retrying");
      const request = f.state.opens[0];
      assert.equal(request.url, "https://example.test/v1/chat/completions");
      assert.equal(request.headers.Authorization, "Bearer synthetic-secret-key");
      assert.deepEqual(JSON.parse(request.body), { model, messages, max_completion_tokens: 2048, reasoning_effort: "medium", stream: true });
      assert.equal(JSON.stringify(body), before);
      assert.deepEqual(f.state.content, ["完成"]);
      assert.equal(f.state.cancels.length, 1);
    });
  }
});

test("other providers and nearby model IDs keep their requested temperature and sampling fields", async (t) => {
  for (const model of ["gpt-4o-mini", "gemini-2.5-pro", "custom-model", "gpt-6.2-sol-custom", "vendor/gpt-6.2-sol"]) {
    await t.test(model, async (t) => {
      const f = fixture(t, { status: 200, bytes: successStream });
      const body = { model, messages: [{ role: "user", content: "合成输入" }], temperature: 0.3, top_p: 0.9, logprobs: true, top_logprobs: 2 };
      const before = structuredClone(body);
      await f.run({ body });
      assert.deepEqual(JSON.parse(f.state.opens[0].body), { ...before, stream: true });
      assert.deepEqual(body, before);
      assert.equal(f.state.opens.length, 1);
    });
  }
});

test("known unsupported parameters use only local messages and never become context retries", async (t) => {
  for (const [code, param, expected] of [
    ["unsupported_parameter", "temperature", "UNSUPPORTED_PARAMETER"],
    ["unsupported_value", "top_p", "UNSUPPORTED_VALUE"],
    ["unsupported_parameter", "logprobs", "UNSUPPORTED_PARAMETER"],
    ["unsupported_parameter", "top_logprobs", "UNSUPPORTED_PARAMETER"],
    ["unsupported_parameter", "max_tokens", "UNSUPPORTED_PARAMETER"],
  ]) {
    await t.test(`${code}: ${param}`, async (t) => {
      const value = { error: { code, param, message: "private-provider-message synthetic-secret-key; maximum context length is 1 tokens. However, you requested 2 tokens." } };
      const f = fixture(t, { value });
      await assert.rejects(f.run(), (error) => {
        assert.equal(error.code, expected);
        assert.equal(error.status, 400);
        assert.ok(error.message.includes(param));
        assert.match(error.message, /检查 AI 模型配置/);
        assertPrivate(error);
        return true;
      });
      assert.equal(f.state.opens.length, 1);
      assert.deepEqual(f.state.content, []);
      assert.equal(f.state.cancels.length, 1);
    });
  }
});

test("unsupported errors with absent or unrecognised parameter names do not echo untrusted fields", async (t) => {
  const parameters = [undefined, "private-param-synthetic-secret-key", "temperature[private-value]", { private: "synthetic-secret-key" }];
  for (const [index, param] of parameters.entries()) {
    await t.test(`unrecognised parameter ${index + 1}`, async (t) => {
      const f = fixture(t, { value: { error: { code: "unsupported_parameter", param, message: "private-provider-message" } } });
      await assert.rejects(f.run(), (error) => {
        assert.equal(error.code, "HTTP_ERROR");
        assert.match(error.message, /HTTP 400.*模型名称.*请求参数/);
        assert.doesNotMatch(error.message, /稍后重试/);
        assertPrivate(error);
        return true;
      });
    });
  }
});

test("model_not_found reports model or account access from bounded 404 and 400 bodies", async (t) => {
  for (const status of [404, 400]) {
    await t.test(`HTTP ${status}`, async (t) => {
      const f = fixture(t, { status, chunkSize: 9, value: { error: {
        code: "model_not_found", param: "model", message: "private-model-name synthetic-secret-key",
      } } });
      await assert.rejects(f.run(), (error) => {
        assert.equal(error.code, "MODEL_NOT_FOUND");
        assert.equal(error.status, status);
        assert.match(error.message, /模型名称和账号权限/);
        assertPrivate(error);
        return true;
      });
      assert.ok(f.state.reads.length > 1);
      assert.equal(f.state.reads[0].options.expectedChunkBytes, 16 * 1024);
      assert.equal(f.state.opens.length, 1);
      assert.equal(f.state.cancels.length, 1);
    });
  }
});

test("unknown 400, 404 and 429 responses give actionable local guidance without a retry", async (t) => {
  for (const [status, guidance] of [
    [400, /模型名称.*请求参数/], [404, /服务地址和模型设置/], [429, /限流.*额度/],
  ]) {
    await t.test(`HTTP ${status}`, async (t) => {
      const f = fixture(t, { status, value: { error: { code: "unknown_error", message: "private-provider-message" } } });
      await assert.rejects(f.run(), (error) => {
        assert.equal(error.code, "HTTP_ERROR");
        assert.equal(error.status, status);
        assert.match(error.message, guidance);
        assertPrivate(error);
        return true;
      });
      assert.equal(f.state.opens.length, 1);
      if (status === 429) assert.equal(f.state.reads.length, 0);
    });
  }
});

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
    { value: contextBody, status: 404 },
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
  const cases = [
    [contextBody, "CONTEXT_LENGTH_EXCEEDED"],
    [{ error: { code: "insufficient_quota", message: "private-quota" } }, "INVALID_RESPONSE"],
    [{ error: { code: "unsupported_parameter", param: "temperature", message: "private-provider-message" } }, "UNSUPPORTED_PARAMETER"],
    [{ error: { code: "unsupported_value", param: "top_p", message: "private-provider-message" } }, "UNSUPPORTED_VALUE"],
    [{ error: { code: "model_not_found", message: "private-model-name" } }, "MODEL_NOT_FOUND"],
    [{ error: { code: "unsupported_value", param: "private-param", message: "private-provider-message" } }, "INVALID_RESPONSE"],
  ];
  for (const [index, [value, expected]] of cases.entries()) {
    await t.test(`stream error ${index + 1}`, async (t) => {
      const f = fixture(t, { status: 200, bytes: encode(`data: ${JSON.stringify(value)}\n\n`), chunkSize: 11 });
      await assert.rejects(f.run(), (error) => {
        assert.equal(error.code, expected);
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
