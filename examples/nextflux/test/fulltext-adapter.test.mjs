import test from "node:test";
import assert from "node:assert/strict";
import { contentMetrics, testScraperRule, applyScraperRule, replayScraperRollbacks } from "../src/toolbox/fulltext-adapter/fulltext-server.mjs";
import { createRollbackJournal } from "../src/toolbox/fulltext-adapter/rollback-journal.mjs";
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


test("full-text metrics flag long sparse newline content but not structured HTML", () => {
  const sparse = "<div>" + Array.from({ length: 8 }, (_, index) => ("第" + index + "段 " + "正文".repeat(45))).join("\n") + "</div>";
  const a = contentMetrics(sparse);
  assert.equal(a.needsRepair, true);
  assert.ok(a.meaningfulNewlines >= 3);
  const structured = Array.from({ length: 8 }, (_, index) => "<p>第" + index + "段 " + "正文".repeat(45) + "</p>").join("");
  const b = contentMetrics(structured);
  assert.equal(b.needsRepair, false);
  assert.equal(b.paragraphs, 8);
});

function memoryStorage() {
  const data = new Map();
  return { get: async (k) => structuredClone(data.get(k)), set: async (k, v) => { data.set(k, structuredClone(v)); } };
}

test("a test cut short leaves a rollback that the next start replays", async () => {
  const storage = memoryStorage();
  const a = api();
  let release;
  a.fetchEntryContent = () => new Promise((resolve) => { release = resolve; });
  const journal = createRollbackJournal({ storage, account: "s#1" });
  testScraperRule({ feedId: 1, entryId: 9, candidate: "article", baselineRule: ".old", api: a, journal });
  await new Promise((r) => setTimeout(r, 10));
  // The page is gone mid-fetch: the server holds the candidate, the journal the way back.
  assert.equal((await a.getFeed()).scraper_rules, "article");
  const next = createRollbackJournal({ storage, account: "s#1" });
  assert.deepEqual(await next.list(), [{ feedId: 1, candidate: "article", baselineRule: ".old" }]);
  assert.deepEqual(await createRollbackJournal({ storage, account: "s#2" }).list(), []);
  await replayScraperRollbacks({ api: a, journal: next });
  assert.equal((await a.getFeed()).scraper_rules, ".old");
  assert.deepEqual(await next.list(), []);
  release("<p>late</p>");
});

test("a finished test clears its rollback; a failed rollback keeps it", async () => {
  const storage = memoryStorage();
  const journal = createRollbackJournal({ storage, account: "s#1" });
  await testScraperRule({ feedId: 1, entryId: 9, candidate: "article", baselineRule: ".old", api: api(), journal });
  assert.deepEqual(await journal.list(), []);
  const b = api();
  const update = b.updateFeed;
  b.updateFeed = async (id, patch) => { if (patch.scraper_rules === ".old") throw new Error("offline"); return update(id, patch); };
  await assert.rejects(testScraperRule({ feedId: 1, entryId: 9, candidate: "article", baselineRule: ".old", api: b, journal }));
  assert.equal((await journal.list()).length, 1);
});

test("replay leaves a rule someone else changed, and apply drops an old rollback", async () => {
  const storage = memoryStorage();
  const journal = createRollbackJournal({ storage, account: "s#1" });
  const a = api();
  await journal.save({ feedId: 1, candidate: "article", baselineRule: ".old" });
  await a.updateFeed(1, { scraper_rules: ".mine" });
  await replayScraperRollbacks({ api: a, journal });
  assert.equal((await a.getFeed()).scraper_rules, ".mine");
  assert.deepEqual(await journal.list(), []);
  await journal.save({ feedId: 1, candidate: "article", baselineRule: ".mine" });
  await applyScraperRule({ feedId: 1, candidate: "article", baselineRule: ".mine", api: a, journal });
  assert.deepEqual(await journal.list(), []);
});

test("a rollback that could not be replayed blocks a new test of that feed", async () => {
  const storage = memoryStorage();
  const journal = createRollbackJournal({ storage, account: "s#1" });
  const a = api();
  await a.updateFeed(1, { scraper_rules: "article" });
  await journal.save({ feedId: 1, candidate: "article", baselineRule: ".old" });
  const offline = { ...a, getFeed: async () => { throw new Error("offline"); } };
  assert.equal((await replayScraperRollbacks({ api: offline, journal })).length, 1);
  await assert.rejects(testScraperRule({ feedId: 1, entryId: 9, candidate: ".new", baselineRule: "article", api: a, journal }), { code: "ROLLBACK_PENDING" });
  assert.deepEqual(await journal.list(), [{ feedId: 1, candidate: "article", baselineRule: ".old" }]);
  assert.deepEqual(await replayScraperRollbacks({ api: a, journal }), []);
  assert.equal((await a.getFeed()).scraper_rules, ".old");
});
