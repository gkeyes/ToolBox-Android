import assert from "node:assert/strict";
import test from "node:test";
import { register } from "node:module";

// Keep only the settings boundary synthetic. The request, think filtering,
// SSE decoding and native stream cleanup all use their production modules.
register("data:text/javascript," + encodeURIComponent(`
  export async function resolve(specifier, context, next) {
    if (specifier === "@/stores/settingsStore.js") return {
      url: "data:text/javascript," + encodeURIComponent(
        "export const settingsState = { get: () => globalThis.__openaiSettings, set: value => { globalThis.__openaiSettings = value; } };"
      ), shortCircuit: true,
    };
    return next(specifier, context);
  }
`));

const { streamChat } = await import("../src/api/openai.js");
const { settingsState } = await import("@/stores/settingsStore.js");
const A = { aiApiKey: "synthetic-key-a", aiBaseUrl: "https://a.example.test/v1", aiModel: "synthetic-large-model" };
const B = { aiApiKey: "synthetic-key-b", aiBaseUrl: "https://b.example.test/compatible/v1", aiModel: "synthetic-small-model" };
const messages = [{ role: "system", content: "分析给出的文章。" }, { role: "user", content: "合成测试文章。" }];

function fixture(t) {
  const previousWindow = globalThis.window;
  const previousSettings = globalThis.__openaiSettings;
  const requests = [];
  const cancelled = [];
  const streams = new Map();
  const reply = [
    { choices: [{ delta: { reasoning_content: "服务端思考。" } }] },
    ...["<thi", "nk>标签内", "推理</th", "ink>\n完成", "分析"].map((content) => ({ choices: [{ delta: { content } }] })),
    { choices: [{ delta: {}, finish_reason: "stop" }] },
  ].map((event) => `data: ${JSON.stringify(event)}\n\n`).join("") + "data: [DONE]\n\n";
  globalThis.window = { ToolBox: { network: {
    async openStream(request) {
      requests.push(request);
      const streamId = `configuration-${requests.length}`;
      streams.set(streamId, new TextEncoder().encode(reply));
      return { status: 200, streamId };
    },
    async readStream(id) {
      const bytes = streams.get(id);
      const data = bytes.subarray(0, 13);
      streams.set(id, bytes.subarray(data.byteLength));
      return { data, done: data.byteLength === bytes.byteLength };
    },
    async cancelStream(id) { cancelled.push(id); streams.delete(id); },
  } } };
  settingsState.set({ ...A });
  t.after(() => {
    if (previousWindow === undefined) delete globalThis.window; else globalThis.window = previousWindow;
    if (previousSettings === undefined) delete globalThis.__openaiSettings; else globalThis.__openaiSettings = previousSettings;
  });
  const run = async (options = {}) => {
    const content = [];
    const thinking = [];
    const result = await streamChat(messages, {
      onChunk: (value) => content.push(value), onThink: (value) => thinking.push(value), ...options,
    });
    return { result, content: content.join(""), thinking: thinking.join("") };
  };
  return { requests, cancelled, run };
}

function assertRequest(request, configuration) {
  assert.equal(request.url, `${configuration.aiBaseUrl}/chat/completions`);
  assert.equal(request.method, "POST");
  assert.deepEqual(request.headers, {
    "Content-Type": "application/json", Authorization: `Bearer ${configuration.aiApiKey}`,
  });
  assert.deepEqual(JSON.parse(request.body), { model: configuration.aiModel, messages, temperature: 0.3, stream: true });
}

test("a captured configuration keeps a running job on its original model, endpoint and key", async (t) => {
  const f = fixture(t);
  const configuration = { ...settingsState.get() };
  settingsState.set({ ...B });
  for (let request = 0; request < 2; request += 1) {
    assert.deepEqual(await f.run({ configuration }), {
      result: { finishReason: "stop" }, content: "完成分析", thinking: "服务端思考。标签内推理",
    });
    assertRequest(f.requests[request], A);
  }
  assert.deepEqual(settingsState.get(), B, "sending with a snapshot does not restore or mutate current settings");
  assert.deepEqual(f.cancelled, ["configuration-1", "configuration-2"]);
});

test("a new request without a captured configuration uses the latest saved settings", async (t) => {
  const f = fixture(t);
  settingsState.set({ ...B });
  assert.deepEqual(await f.run(), {
    result: { finishReason: "stop" }, content: "完成分析", thinking: "服务端思考。标签内推理",
  });
  assertRequest(f.requests[0], B);
  assert.deepEqual(f.cancelled, ["configuration-1"]);
});

test("an explicitly supplied configuration never borrows a key from another active configuration", async (t) => {
  const f = fixture(t);
  settingsState.set({ ...B });
  await assert.rejects(f.run({ configuration: { ...A, aiApiKey: "" } }), /请先填写 AI API Key/);
  assert.equal(f.requests.length, 0);
});
