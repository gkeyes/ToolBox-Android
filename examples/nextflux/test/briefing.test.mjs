import test from "node:test";
import assert from "node:assert/strict";
import { briefingMessages, excerptLength, parseBriefing, plainText, todaysUnread } from "../src/toolbox/briefing/briefing.mjs";

test("today's unread: published or fetched since local midnight, visible feeds, newest first", () => {
  const now = new Date(2026, 9, 16, 13, 0);
  const at = (h, d = 16) => new Date(2026, 9, d, h, 0).toISOString();
  const rows = [
    { id: 1, feedId: 1, status: "unread", published_at: at(9), created_at: at(9) },
    { id: 2, feedId: 1, status: "read", published_at: at(10), created_at: at(10) },
    { id: 3, feedId: 2, status: "unread", published_at: at(11), created_at: at(11) },
    { id: 4, feedId: 1, status: "unread", published_at: at(20, 15), created_at: at(8) },
    { id: 5, feedId: 1, status: "unread", published_at: at(20, 15), created_at: at(21, 15) },
    { id: 6, feedId: 1, status: "unread", published_at: at(12), created_at: at(12) },
  ];
  const picked = todaysUnread(rows, { now, isVisibleFeed: (id) => id === 1 });
  assert.deepEqual(picked.map((row) => row.id), [6, 1, 4]);
});

test("excerpts share a budget but every article keeps its title", () => {
  assert.equal(excerptLength(10), 280);
  assert.equal(excerptLength(10000), 60);
  const messages = briefingMessages([{ feedTitle: "Feed", title: "Hello", text: "<p>a &amp; b</p>" }, { title: "", text: "" }]);
  assert.equal(messages[0].role, "system");
  assert.match(messages[1].content, /共 2 篇/);
  assert.match(messages[1].content, /\[1\] Feed · Hello\na & b/);
  assert.match(messages[1].content, /\[2\] （无标题）/);
  assert.equal(plainText("<script>x</script><b>y</b>&nbsp;z"), "y z");
});

test("the briefing parses into sections with citations to real articles only", () => {
  const sections = parseBriefing("## **AI 进展**\n新模型发布了[1][2]，价格下降 [9]。\n\n## 天气\n明天降温", 2);
  assert.equal(sections.length, 2);
  assert.equal(sections[0].title, "AI 进展");
  assert.deepEqual(sections[0].parts, [{ text: "新模型发布了" }, { cite: 1 }, { cite: 2 }, { text: "，价格下降 " }, { text: "。" }]);
  assert.deepEqual(sections[1], { title: "天气", parts: [{ text: "明天降温" }] });
  // A stream cut mid-way still parses.
  assert.deepEqual(parseBriefing("开头没有标题 [1", 1), [{ title: "", parts: [{ text: "开头没有标题 [1" }] }]);
});
