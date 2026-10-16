import test from "node:test";
import assert from "node:assert/strict";
import { arrivedAt, digestMessages, fitArticles, parseDigest, parseTopics, PROMPT_BUDGET, todaysUnread, topicsMessages } from "../src/toolbox/board/board.mjs";

test("today's unread: arrived since local midnight, visible feeds, newest first", () => {
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
  assert.deepEqual(todaysUnread(rows, { now, isVisibleFeed: (id) => id === 1 }).map((row) => row.id), [6, 1, 4]);
});

test("a future timestamp does not make an old article today's, or sort first", () => {
  const now = new Date(2026, 9, 16, 13, 0);
  const at = (h, d = 16) => new Date(2026, 9, d, h, 0).toISOString();
  const rows = [
    { id: 1, feedId: 1, status: "unread", published_at: at(9, 17), created_at: at(9, 15) },
    { id: 2, feedId: 1, status: "unread", published_at: at(23), created_at: at(10) },
    { id: 3, feedId: 1, status: "unread", published_at: at(12), created_at: at(12) },
  ];
  assert.deepEqual(todaysUnread(rows, { now }).map((row) => row.id), [3, 2]);
  assert.equal(arrivedAt(rows[1], now.getTime()), new Date(2026, 9, 16, 10, 0).getTime());
});

test("the prompt stays within its budget on a busy day, keeping the newest articles", () => {
  const many = Array.from({ length: 400 }, (_, i) => ({ id: i + 1, feedTitle: "某个很长的订阅源名字", title: "标题".repeat(40), text: "正文".repeat(400) }));
  const { articles, skipped } = fitArticles(many);
  assert.ok(articles.length > 20 && articles.length <= 150);
  assert.equal(articles[0].id, 1);
  assert.equal(skipped, many.length - articles.length);
  const user = topicsMessages(articles)[1].content;
  assert.ok(user.length <= PROMPT_BUDGET + 600, `prompt ${user.length}`);
});

test("preview text is used as is: code like List<T> survives", () => {
  const { articles } = fitArticles([{ id: 1, feedTitle: "博客", title: "泛型", text: "返回 List<T> 和 a &amp; b" }]);
  assert.equal(articles[0].excerpt, "返回 List<T> 和 a &amp; b");
});

test("topics: real, unique article numbers; empty topics dropped; fenced JSON accepted", () => {
  const text = "```json\n" + JSON.stringify({ topics: [
    { title: "**AI 降价**", gist: "推理成本下降", summary: "多家公司…", articles: [1, 2, 2, 9] },
    { title: "台风", gist: "明晚登陆", articles: [2, 3] },
    { title: "空", articles: [9] },
    { title: "", articles: [4] },
  ] }) + "\n```";
  const topics = parseTopics(text, 4);
  assert.deepEqual(topics.map((topic) => [topic.title, topic.articles]), [["AI 降价", [1, 2]], ["台风", [3]]]);
  assert.throws(() => parseTopics("抱歉，我无法完成", 4), /无法识别/);
  assert.throws(() => parseTopics("{\"topics\":[]}", 4), /没有归出主题/);
});

test("digest: citations become links; text parts are never empty", () => {
  const sections = parseDigest("## 台风\n明晚登陆[1][2]。[9]沿海停课[3]", 3);
  assert.equal(sections.length, 1);
  assert.deepEqual(sections[0].parts, [{ text: "明晚登陆" }, { cite: 1 }, { cite: 2 }, { text: "。" }, { text: "沿海停课" }, { cite: 3 }]);
  assert.ok(sections[0].parts.every((part) => "cite" in part || part.text.length > 0));
});

test("digest prompt carries the topic outline and the numbered articles", () => {
  const { articles } = fitArticles([{ id: 7, feedTitle: "A", title: "一", text: "x" }]);
  const [system, user] = digestMessages([{ title: "主题", gist: "重点", articles: [1] }], articles);
  assert.match(system.content, /## 主题名/);
  assert.match(user.content, /1\. 主题：重点（文章 \[1\]）/);
  assert.match(user.content, /\[1\] A · 一/);
});

test("topics: a bare JSON array and a reply with bracketed remarks are read", () => {
  const item = { title: "主题", gist: "重点", summary: "摘要", articles: [1] };
  assert.equal(parseTopics(JSON.stringify([item]), 3)[0].title, "主题");
  assert.equal(parseTopics(`好的 [1]：\n${JSON.stringify({ topics: [item] })}\n以上 {完}`, 3)[0].title, "主题");
});

test("the whole topics prompt stays within the budget", () => {
  const many = Array.from({ length: 220 }, (_, i) => ({ id: i, feedTitle: "很长的订阅源名字".repeat(4), title: "标题".repeat(60), text: "正文".repeat(400) }));
  const { articles } = fitArticles(many);
  const size = topicsMessages(articles).reduce((sum, message) => sum + message.content.length, 0);
  assert.ok(size <= PROMPT_BUDGET, `${size} > ${PROMPT_BUDGET}`);
});

test("with hideDuplicates, today's unread keeps only the newest copy of a title or URL", () => {
  const now = new Date(2026, 9, 16, 13, 0);
  const at = (h) => new Date(2026, 9, 16, h, 0).toISOString();
  const rows = [
    { id: 1, feedId: 1, status: "unread", title: "同题", url: "https://a.example/1", published_at: at(9), created_at: at(9) },
    { id: 2, feedId: 1, status: "unread", title: "同题", url: "https://a.example/2", published_at: at(11), created_at: at(11) },
    { id: 3, feedId: 1, status: "unread", title: "别的", url: "https://a.example/2", published_at: at(10), created_at: at(10) },
    { id: 4, feedId: 1, status: "unread", title: "第四", url: "https://a.example/4", published_at: at(8), created_at: at(8) },
  ];
  assert.deepEqual(todaysUnread(rows, { now, hideDuplicates: true }).map((row) => row.id), [2, 4]);
  assert.deepEqual(todaysUnread(rows, { now }).map((row) => row.id), [2, 3, 1, 4]);
});
