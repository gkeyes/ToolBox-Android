import test from "node:test";
import assert from "node:assert/strict";
import { contentMetrics, testScraperRule, applyScraperRule } from "../src/toolbox/fulltext-adapter/fulltext-server.mjs";
import { isMiniMaxProvider, parseScraperCandidate } from "../src/toolbox/fulltext-adapter/fulltext-ai.mjs";

const base = { id: 1, scraper_rules: ".old" };
function api() {
  let feed = { ...base }, writes = [];
  return {
    writes,
    getFeed: async () => ({ ...feed }),
    updateFeed: async (_, patch) => { writes.push(patch); feed = { ...feed, ...patch }; return { ...feed }; },
    fetchEntryContent: async () => "<article><p>Hello world</p><img></article>",
  };
}

test("AI parser accepts strict JSON and rejects multiline selectors", () => {
  assert.equal(parseScraperCandidate('{"scraper_rules":"article .content","confidence":0.8,"reason":"ok"}').rule, "article .content");
  assert.throws(() => parseScraperCandidate('{"scraper_rules":"article\\nbody"}'));
});

test("AI parser accepts prose around fenced JSON", () => {
  const result = parseScraperCandidate('分析完成。\n\`\`\`json\n{"scraper_rules":"main article","confidence":0.86,"reason":"正文容器"}\n\`\`\`\n请测试。');
  assert.equal(result.rule, "main article");
  assert.equal(result.confidence, 0.86);
});

test("MiniMax tagged result survives thinking text", () => {
  const result = parseScraperCandidate('<think>reasoning that must be ignored</think>\n<scraper_rule>article.post-content</scraper_rule>\n<confidence>0.92</confidence>\n<reason>稳定正文节点</reason>', { provider: "minimax" });
  assert.equal(result.rule, "article.post-content");
  assert.equal(result.confidence, 0.92);
  assert.equal(result.reason, "稳定正文节点");
});

test("MiniMax provider detection accepts host or model", () => {
  assert.equal(isMiniMaxProvider({ aiBaseUrl: "https://api.minimax.io/v1", aiModel: "MiniMax-M3" }), true);
  assert.equal(isMiniMaxProvider({ aiBaseUrl: "https://example.com/v1", aiModel: "MiniMax-M2.7" }), true);
  assert.equal(isMiniMaxProvider({ aiBaseUrl: "https://example.com/v1", aiModel: "gpt-x" }), false);
});

test("test writes candidate, fetches, then restores baseline", async () => {
  const a = api();
  const r = await testScraperRule({ feedId: 1, entryId: 9, candidate: "article", baselineRule: ".old", api: a });
  assert.deepEqual(a.writes, [{ scraper_rules: "article" }, { scraper_rules: ".old" }]);
  assert.ok(r.metrics.textChars > 0);
  assert.equal((await a.getFeed()).scraper_rules, ".old");
});

test("test failure still rolls back", async () => {
  const a = api(); a.fetchEntryContent = async () => { throw new Error("fetch"); };
  await assert.rejects(testScraperRule({ feedId: 1, entryId: 9, candidate: "article", baselineRule: ".old", api: a }));
  assert.equal((await a.getFeed()).scraper_rules, ".old");
});

test("concurrent baseline change refuses temporary write", async () => {
  const a = api(); await a.updateFeed(1, { scraper_rules: ".other" }); a.writes.length = 0;
  await assert.rejects(testScraperRule({ feedId: 1, entryId: 9, candidate: "article", baselineRule: ".old", api: a }), { code: "CONFLICT" });
  assert.equal(a.writes.length, 0);
});

test("apply persists only after explicit confirmation", async () => {
  const a = api(); await applyScraperRule({ feedId: 1, candidate: "article", baselineRule: ".old", api: a });
  assert.equal((await a.getFeed()).scraper_rules, "article");
});

test("metrics are bounded plain text", () => {
  const m = contentMetrics("<p>A <b>B</b></p><p>C</p><img>");
  assert.equal(m.images, 1); assert.equal(m.paragraphs, 2); assert.match(m.sample, /A B C/);
});


test("reasoning stream extractor accepts MiniMax string and block formats without leaking metadata", async () => {
  const { extractReasoningText, extractContentText } = await import("../src/toolbox/ai-network.js");
  assert.equal(extractReasoningText({ delta: { reasoning_content: "先找正文" } }), "先找正文");
  assert.equal(extractReasoningText({ delta: { reasoning_details: [{ type: "reasoning.text", text: "分析结构" }, { signature: "secret-metadata" }] } }), "分析结构");
  assert.equal(extractContentText({ delta: { content: [{ type: "text", text: "<scraper_rule>article</scraper_rule>" }] } }), "<scraper_rule>article</scraper_rule>");
});
