import test from "node:test";
import assert from "node:assert/strict";
import { normalizeSpeechText, splitSpeechText } from "../src/toolbox/speech/articleSpeech.mjs";
import { hexToBytes, hexToBytesAsync, isOfficialMiniMaxBase, parseSpeechPayload, speechEndpoint, synthesizeSpeechSegment } from "../src/toolbox/speech/minimaxSpeech.mjs";

test("speech text normalization keeps paragraph boundaries", () => {
  assert.equal(normalizeSpeechText("  第一段。  \n\n  第二段！  "), "第一段。\n第二段！");
});

test("speech splitter follows sentence boundaries for long articles", () => {
  const sentence = "这是用于验证自然句边界的文章内容，每一句都应该完整保留并按需要切分。";
  const text = Array.from({ length: 12 }, (_, index) => `${index + 1}。${sentence}`).join("");
  const chunks = splitSpeechText(text, { targetChars: 120, maxChars: 160 });
  assert.ok(chunks.length >= 2);
  assert.equal(chunks.join(" ").replaceAll(" ", ""), text);
  assert.ok(chunks.every((item) => item.length <= 160));
});

test("speech splitter hard-splits oversized sentences", () => {
  const chunks = splitSpeechText("这是一段".repeat(80), { targetChars: 120, maxChars: 160 });
  assert.ok(chunks.length > 1);
  assert.ok(chunks.every((item) => item.length <= 160));
});

test("MiniMax speech endpoints only accept official AI hosts for key reuse", () => {
  assert.equal(isOfficialMiniMaxBase("https://api.minimax.io/v1"), true);
  assert.equal(isOfficialMiniMaxBase("https://api.minimaxi.com/v1"), true);
  assert.equal(isOfficialMiniMaxBase("https://api.minimax.cn/v1"), true);
  assert.equal(isOfficialMiniMaxBase("https://minimax.io.evil.example/v1"), false);
  assert.equal(speechEndpoint("global"), "https://api.minimax.io/v1/t2a_v2");
  assert.equal(speechEndpoint("cn"), "https://api.minimaxi.com/v1/t2a_v2");
});

test("MiniMax hex audio decoder rejects malformed payloads", () => {
  assert.deepEqual([...hexToBytes("00ff10")], [0, 255, 16]);
  assert.throws(() => hexToBytes("0fg1"), /音频数据无效/);
  assert.throws(() => hexToBytes("abc"), /音频数据无效/);
});

const randomBytes = (length) => Uint8Array.from({ length }, (_, index) => (index * 2654435761) >>> 24);
const toHex = (bytes) => Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");

test("hex decoder accepts both cases and rejects non-hex characters", () => {
  assert.deepEqual([...hexToBytes("0aFf7B")], [10, 255, 123]);
  for (const bad of ["zz", "0\u00e9", " 0", "0x"]) assert.throws(() => hexToBytes(bad), /音频数据无效/);
});

// Regression: a 3-minute MiniMax segment is ~6 MB of hex. Decoding it must
// yield to the event loop in short slices so taps (and "stop") still run.
test("async hex decode matches the sync decoder and yields in short slices", async () => {
  const bytes = randomBytes(3 * 1024 * 1024);
  const hex = toHex(bytes);
  let yields = 0;
  let longest = 0;
  let last = performance.now();
  const decoded = await hexToBytesAsync(hex, {
    yieldControl: async () => {
      const now = performance.now();
      longest = Math.max(longest, now - last);
      yields += 1;
      await new Promise((resolve) => setTimeout(resolve, 0));
      last = performance.now();
    },
  });
  assert.equal(decoded.length, bytes.length);
  assert.equal(decoded[123457], bytes[123457]);
  assert.deepEqual(decoded.subarray(-16), bytes.subarray(-16));
  assert.ok(yields >= 40, `decode yielded ${yields} times`);
  assert.ok(longest < 100, `longest uninterrupted decode slice ${Math.round(longest)}ms`);
});

test("async hex decode stops as soon as the run is cancelled", async () => {
  const controller = new AbortController();
  const hex = toHex(randomBytes(1024 * 1024));
  let yields = 0;
  await assert.rejects(hexToBytesAsync(hex, {
    signal: controller.signal,
    yieldControl: async () => { yields += 1; if (yields === 2) controller.abort(); },
  }), (error) => error.code === "CANCELLED");
  assert.equal(yields, 2);
});

test("speech payload parsing lifts the audio hex out of the JSON envelope", () => {
  const audio = toHex(randomBytes(64));
  const payload = parseSpeechPayload(JSON.stringify({ data: { audio, status: 2 }, extra_info: { audio_length: 1200 }, base_resp: { status_code: 0 } }));
  assert.equal(payload.data.audio, audio);
  assert.equal(payload.extra_info.audio_length, 1200);
  const nested = parseSpeechPayload(JSON.stringify({ extra_info: { audio: "x" }, data: { audio: "abcd" } }));
  assert.equal(nested.data.audio, "abcd");
  assert.equal(nested.extra_info.audio, "x");
  assert.equal(parseSpeechPayload('{"base_resp":{"status_code":1004,"status_msg":"auth"}}').base_resp.status_code, 1004);
  assert.throws(() => parseSpeechPayload("{"), /有效 JSON/);
});

function fakeNetwork({ body, holdRead = false }) {
  const calls = [];
  const bytes = new TextEncoder().encode(body);
  let offset = 0;
  return {
    calls,
    openStream: async (request) => { calls.push(["open", request.url]); return { streamId: "s1", status: 200 }; },
    readStream: (streamId) => {
      calls.push(["read", streamId]);
      if (holdRead) return new Promise(() => {}); // a native read that never answers
      const data = bytes.subarray(offset, offset + 65536);
      offset += data.length;
      return Promise.resolve({ data, done: offset >= bytes.length });
    },
    cancelStream: (streamId) => { calls.push(["cancel", streamId]); return new Promise(() => {}); },
  };
}
const speechSettings = { speechApiKey: "fixture-key", speechRegion: "global" };

test("stopping while the native read hangs settles the segment promptly", async () => {
  const network = fakeNetwork({ body: "", holdRead: true });
  globalThis.window = { ToolBox: { network } };
  try {
    const controller = new AbortController();
    const pending = synthesizeSpeechSegment("你好", speechSettings, { signal: controller.signal });
    await new Promise((resolve) => setTimeout(resolve, 10));
    const started = performance.now();
    controller.abort();
    await assert.rejects(pending, (error) => error.code === "CANCELLED");
    assert.ok(performance.now() - started < 200, "stop must not wait on the hung read or the cancel call");
    assert.ok(network.calls.some(([name]) => name === "cancel"), "the native stream is cancelled");
  } finally { delete globalThis.window; }
});

test("a full MiniMax response decodes to the original audio bytes", async () => {
  const bytes = randomBytes(200 * 1024);
  const network = fakeNetwork({ body: JSON.stringify({ data: { audio: toHex(bytes), status: 2 }, extra_info: { audio_length: 4200 }, base_resp: { status_code: 0 } }) });
  globalThis.window = { ToolBox: { network } };
  try {
    const result = await synthesizeSpeechSegment("你好", speechSettings);
    assert.equal(result.durationMs, 4200);
    assert.deepEqual(result.bytes, bytes);
    assert.equal(network.calls.some(([name]) => name === "cancel"), false);
  } finally { delete globalThis.window; }
});
