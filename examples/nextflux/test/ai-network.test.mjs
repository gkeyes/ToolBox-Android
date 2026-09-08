import test from "node:test";
import assert from "node:assert/strict";
import { assertAiUrl, streamChatCompletion, testAiConnection } from "../src/toolbox/ai-network.js";
import { AI_ALLOWED_ORIGINS } from "../src/toolbox/config.js";

const baseUrl = `${AI_ALLOWED_ORIGINS[0]}/v1`;
const options = { baseUrl, apiKey: "test-only-secret", body: { model: "test-model", messages: [{ role: "user", content: "fixture" }] } };
const encode = (text) => new TextEncoder().encode(text);
function host(network) { globalThis.window = { ToolBox: { network } }; }

test("AI endpoint validation rejects userinfo, IPs, query strings and non-HTTPS before disclosing key", async () => {
  let calls = 0;
  host({ openStream: async () => { calls += 1; }, request: async () => { calls += 1; } });
  for (const url of ["http://api.openai.com/v1", "https://secret@api.openai.com/v1", "https://127.0.0.1/v1", "https://[::1]/v1", "https://0x7f000001/v1", "https://api.openai.com/v1?key=secret", "https://api.openai.com/v1#secret"]) {
    await assert.rejects(streamChatCompletion({ ...options, baseUrl: url, onDelta: () => {} }));
  }
  assert.equal(calls, 0);
  assert.equal(assertAiUrl(baseUrl), `${baseUrl}/chat/completions`);
});

test("AI connection test uses native request and scrubs failed HTTP response", async () => {
  let sent;
  host({ request: async (data) => { sent = data; return { status: 401, body: "sensitive-server-body" }; } });
  await assert.rejects(testAiConnection({ baseUrl, apiKey: "test-only-secret", model: "test-model" }), (error) => {
    assert.equal(error.status, 401);
    assert.doesNotMatch(JSON.stringify(error), /sensitive|test-only-secret/);
    return true;
  });
  assert.equal(sent.headers.Authorization, "Bearer test-only-secret");
  assert.equal(JSON.parse(sent.body).model, "test-model");
});

test("SSE preserves split UTF-8, multiline event data, and stops exactly at DONE", async () => {
  const bytes = encode('data: {"choices":[\ndata: {"delta":{"content":"中文🙂"}}]}\r\n\r\ndata: [DONE]\n\ndata: {"choices":[{"delta":{"content":"ignored"}}]}\n\n');
  let index = 0;
  let cancelled = 0;
  let text = "";
  host({
    openStream: async () => ({ streamId: "s1", status: 200, headers: {} }),
    readStream: async () => ({ data: bytes.slice(index, ++index), done: index === bytes.length }),
    cancelStream: async () => { cancelled += 1; },
  });
  await streamChatCompletion({ ...options, onDelta: (delta) => { text += delta; } });
  assert.equal(text, "中文🙂");
  assert.equal(cancelled, 1);
  assert.ok(index < bytes.length);
});

test("failed stream headers cancel immediately without reading provider body", async () => {
  let reads = 0;
  let cancelled = 0;
  host({
    openStream: async () => ({ streamId: "s1", status: 429, headers: {} }),
    readStream: async () => { reads += 1; },
    cancelStream: async () => { cancelled += 1; },
  });
  await assert.rejects(streamChatCompletion({ ...options, onDelta: () => {} }), { status: 429 });
  assert.equal(reads, 0);
  assert.equal(cancelled, 1);
});

test("abort interrupts a pending read and cancels the native stream once", async () => {
  const controller = new AbortController();
  let cancelled = 0;
  host({
    openStream: async () => ({ streamId: "s1", status: 200, headers: {} }),
    readStream: async () => {
      queueMicrotask(() => controller.abort());
      return new Promise(() => {});
    },
    cancelStream: async () => { cancelled += 1; },
  });
  await assert.rejects(streamChatCompletion({ ...options, signal: controller.signal, onDelta: () => {} }), { code: "CANCELLED" });
  assert.equal(cancelled, 1);
});

test("provider error events are sanitized and truncated streams do not report completion", async () => {
  for (const [body, code] of [
    ['data: {"error":{"message":"test-only-secret"}}\n\n', "INVALID_RESPONSE"],
    ['data: {"choices":[{"delta":{"content":"partial"}}]}\n\n', "INCOMPLETE_STREAM"],
  ]) {
    host({ openStream: async () => ({ streamId: "s1", status: 200 }), readStream: async () => ({ data: encode(body), done: true }), cancelStream: async () => {} });
    await assert.rejects(streamChatCompletion({ ...options, onDelta: () => {} }), (error) => {
      assert.equal(error.code, code);
      assert.doesNotMatch(error.message, /test-only-secret/);
      return true;
    });
  }
});

test("finish_reason completes compatible streams without a DONE sentinel", async () => {
  let text = "";
  host({ openStream: async () => ({ streamId: "s1", status: 200 }), readStream: async () => ({ data: encode('data: {"choices":[{"delta":{"content":"summary"},"finish_reason":"stop"}]}\n\n'), done: true }), cancelStream: async () => {} });
  await streamChatCompletion({ ...options, onDelta: (delta) => { text += delta; } });
  assert.equal(text, "summary");
});


test("custom endpoint approval completes before a key-bearing test request", async () => {
  const calls = [];
  const custom = "https://custom.provider.test/api/v1";
  host({
    authorizeDomain: async (domain) => { calls.push(["approve", domain]); return true; },
    request: async (data) => { calls.push(["request", data]); return { status: 200 }; },
  });
  await testAiConnection({ baseUrl: custom, apiKey: "test-only-secret", model: "custom-model" });
  assert.deepEqual(calls[0], ["approve", "custom.provider.test"]);
  assert.equal(calls[1][1].url, `${custom}/chat/completions`);
  assert.equal(calls[1][1].headers.Authorization, "Bearer test-only-secret");
});

test("denied or unsupported custom authorization sends no credentials or requests", async () => {
  for (const authorizeDomain of [undefined, async () => false, async () => { throw { code: "USER_GESTURE_REQUIRED", message: "secret-native-error" }; }]) {
    let requests = 0;
    host({ authorizeDomain, request: async () => { requests += 1; } });
    await assert.rejects(testAiConnection({ baseUrl: "https://custom.provider.test/v1", apiKey: "test-only-secret", model: "test" }), (error) => {
      assert.doesNotMatch(error.message, /secret-native-error|test-only-secret/);
      return true;
    });
    assert.equal(requests, 0);
  }
});

test("streaming uses a current custom grant silently and preserves the selected exact origin", async () => {
  let sent;
  let approvals = 0;
  host({
    listDomains: async () => ["custom.provider.test"],
    authorizeDomain: async () => { approvals += 1; return true; },
    openStream: async (data) => { sent = data; return { streamId: "s1", status: 200 }; },
    readStream: async () => ({ data: encode("data: [DONE]\n\n"), done: true }),
    cancelStream: async () => {},
  });
  await streamChatCompletion({ ...options, baseUrl: "https://custom.provider.test/path/v1", onDelta: () => {} });
  assert.equal(sent.url, "https://custom.provider.test/path/v1/chat/completions");
  assert.equal(approvals, 0);
});

test("revoked, unavailable and hostname-mismatched grants prevent stream credential disclosure", async () => {
  for (const listDomains of [undefined, async () => [], async () => ["custom.provider.test.evil.test"]]) {
    let calls = 0;
    host({ listDomains, openStream: async () => { calls += 1; } });
    await assert.rejects(streamChatCompletion({ ...options, baseUrl: "https://custom.provider.test/v1", onDelta: () => {} }));
    assert.equal(calls, 0);
  }
});
