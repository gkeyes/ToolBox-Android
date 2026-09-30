import test from "node:test";
import assert from "node:assert/strict";
import { normalizeSpeechText, splitSpeechText } from "../src/toolbox/speech/articleSpeech.mjs";
import { hexToBytes, isOfficialMiniMaxBase, speechEndpoint } from "../src/toolbox/speech/minimaxSpeech.mjs";

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
