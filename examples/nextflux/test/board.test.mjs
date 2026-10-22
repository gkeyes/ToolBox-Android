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

// Replies that are not valid JSON still give their topics: a stray " inside a
// value, a trailing comma, numbers written as strings, a reply cut off mid-way.
test("parseTopics reads topics from replies that are not valid JSON", () => {
  const stray = '{"topics":[{"title":"苹果发布"新品"","gist":"一句","summary":"他说"好"。","articles":[1,2]},{"title":"B","gist":"g","summary":"s","articles":[3]}]}';
  assert.deepEqual(parseTopics(stray, 5).map((topic) => [topic.title, topic.articles]), [["苹果发布\"新品\"", [1, 2]], ["B", [3]]]);
  assert.deepEqual(parseTopics('{"topics":[{"title":"A","gist":"g","summary":"s","articles":[1,2],},]}', 5).map((topic) => topic.articles), [[1, 2]]);
  const cut = '```json\n{"topics":[{"title":"A","gist":"g","summary":"s","articles":["1","[2]"]},{"title":"B","gist":"g","summary":"s","articles":[3';
  assert.deepEqual(parseTopics(cut, 5).map((topic) => [topic.title, topic.articles]), [["A", [1, 2]]]);
  assert.throws(() => parseTopics("今天没有什么值得看的。", 5), /无法识别/);
});

test("article references keep their numbers: lists, ranges and 2.0 are not glued together", async () => {
  const { articleNumbers, parseTopics, salvageTopics } = await import("../src/toolbox/board/board.mjs");
  assert.deepEqual(articleNumbers("1-3", 9), [1, 2, 3]);
  assert.deepEqual(articleNumbers("1,2", 9), [1, 2]);
  assert.deepEqual(articleNumbers("1、2", 9), [1, 2]);
  assert.deepEqual(articleNumbers("2.0", 9), [2]);
  assert.deepEqual(articleNumbers("[4]", 9), [4]);
  assert.deepEqual(articleNumbers(2.5, 9), []);
  assert.deepEqual(articleNumbers("第三篇", 9), []);
  // A range one past the last article is trimmed, not dropped; one longer
  // than the board by more ("1-50" of 12) is junk.
  assert.deepEqual(articleNumbers("2-10", 9), [2, 3, 4, 5, 6, 7, 8, 9]);
  assert.deepEqual(articleNumbers("8-10", 9), [8, 9]);
  assert.deepEqual(articleNumbers("8-100", 9), []);
  assert.deepEqual(articleNumbers("1-50", 12), []);
  assert.deepEqual(articleNumbers("1-13", 12).length, 12);
  assert.deepEqual(articleNumbers("12-1", 12).length, 12);
  assert.deepEqual(articleNumbers("1-3", 3), [1, 2, 3]);
  // Ranges over the whole board give up only what other topics claim; no article drops off.
  for (const range of ['"1-12"', '"12-1"', '"1-13"', '"1-6","7-12"', '"1-6,7-12"']) {
    const whole = parseTopics(`{"topics":[{"title":"全部","articles":[${range}]},{"title":"台风","articles":[4,5]}]}`, 12);
    assert.deepEqual(whole.map((topic) => [topic.title, topic.articles]), [["全部", [1, 2, 3, 6, 7, 8, 9, 10, 11, 12]], ["台风", [4, 5]]], range);
  }
  // Ranges over nearly the whole board only give up what another topic claims, whichever comes first.
  for (const [reply, count, expected] of [
    ['{"topics":[{"title":"全部","articles":["1-11"]},{"title":"台风","articles":[4,5]}]}', 12, [["全部", [1, 2, 3, 6, 7, 8, 9, 10, 11]], ["台风", [4, 5]]]],
    ['{"topics":[{"title":"全部","articles":["2-12"]},{"title":"台风","articles":[4,5]}]}', 12, [["全部", [2, 3, 6, 7, 8, 9, 10, 11, 12]], ["台风", [4, 5]]]],
    ['{"topics":[{"title":"大","articles":["1-11"]},{"title":"小","articles":[11,12]}]}', 12, [["大", [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]], ["小", [11, 12]]]],
    ['{"topics":[{"title":"小","articles":[11]},{"title":"大","articles":["1-11"]}]}', 12, [["小", [11]], ["大", [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]]]],
    ['{"topics":[{"title":"大","articles":["1-11"]},{"title":"小","articles":[12]},{"title":"丙","articles":[3]}]}', 12, [["大", [1, 2, 4, 5, 6, 7, 8, 9, 10, 11]], ["小", [12]], ["丙", [3]]]],
    ['{"topics":[{"title":"大","articles":["1-18"]},{"title":"小","articles":["18-20"]}]}', 20, [["大", Array.from({ length: 17 }, (_, i) => i + 1)], ["小", [18, 19, 20]]]],
    ['{"topics":[{"title":"大","articles":["1-9"]},{"title":"小","articles":[9,10]}]}', 10, [["大", [1, 2, 3, 4, 5, 6, 7, 8]], ["小", [9, 10]]]],
  ]) assert.deepEqual(parseTopics(reply, count).map((topic) => [topic.title, topic.articles]), expected, reply);
  // However a whole-board topic is written, it gives up only the overlap (recheck-147 F1, F2).
  for (const range of ['"1-11",12', '"1-10",11,12', '"1-10,11,12"', '"1-12"']) {
    const whole = parseTopics(`{"topics":[{"title":"全部","articles":[${range}]},{"title":"乙","articles":[4]}]}`, 12);
    assert.deepEqual(whole.map((topic) => [topic.title, topic.articles]), [["全部", [1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12]], ["乙", [4]]], range);
  }
  assert.deepEqual(parseTopics('{"topics":[{"title":"甲","articles":[1]},{"title":"全部","articles":["1-7",8]}]}', 8).map((topic) => [topic.title, topic.articles]), [["甲", [1]], ["全部", [2, 3, 4, 5, 6, 7, 8]]]);
  // Salvaged from broken JSON, the same.
  const broken = parseTopics('{"topics":[{"title":"全部","summary":"有"引号"","articles":["1-12"]},{"title":"台风","summary":"s","articles":[4,5]}]}', 12);
  assert.deepEqual(broken.map((topic) => [topic.title, topic.articles]), [["全部", [1, 2, 3, 6, 7, 8, 9, 10, 11, 12]], ["台风", [4, 5]]]);
  // An explicit number beside such a range is kept the same way; a range over part of the board stays.
  const mixed = parseTopics('{"topics":[{"title":"全部","articles":["1-12", 3]},{"title":"台风","articles":[4,5]}]}', 12);
  assert.deepEqual(mixed.map((topic) => [topic.title, topic.articles]), [["全部", [1, 2, 3, 6, 7, 8, 9, 10, 11, 12]], ["台风", [4, 5]]]);
  const part = parseTopics('{"topics":[{"title":"前半","articles":["1-10"]},{"title":"台风","articles":[11,12]}]}', 12);
  assert.deepEqual(part.map((topic) => topic.articles.length), [10, 2]);
  // A board whose only topic is all of it keeps it, as does one listing the articles one by one.
  assert.deepEqual(parseTopics('{"topics":[{"title":"全部","articles":["1-12"]}]}', 12)[0].articles.length, 12);
  assert.deepEqual(parseTopics('{"topics":[{"title":"全部","articles":[1,2,3,4,5,6,7,8,9,10,11,12]},{"title":"乙","articles":[1]}]}', 12)[0].articles.length, 12);
  // A second topic with no real article does not count as another grouping; nor does a second catch-all.
  assert.deepEqual(parseTopics('{"topics":[{"title":"全部","articles":["1-12"]},{"title":"坏","articles":[99]}]}', 12).map((topic) => [topic.title, topic.articles.length]), [["全部", 12]]);
  assert.deepEqual(parseTopics('{"topics":[{"title":"全部","articles":["1-12"]},{"title":"又全部","articles":["1-11"]}]}', 12).map((topic) => [topic.title, topic.articles.length]), [["全部", 12]]);
  // A near-whole range beside a topic of the rest splits the board in two: both stay.
  for (const [reply, count, sizes] of [
    ['{"topics":[{"title":"大","articles":["1-11"]},{"title":"小","articles":[12]}]}', 12, [11, 1]],
    ['{"topics":[{"title":"大","articles":["1-9"]},{"title":"小","articles":[10]}]}', 10, [9, 1]],
    ['{"topics":[{"title":"大","articles":["1-18"]},{"title":"小","articles":["19-20"]}]}', 20, [18, 2]],
  ]) assert.deepEqual(parseTopics(reply, count).map((topic) => topic.articles.length), sizes, reply);
  // A rival the 7-topic cap leaves out claims nothing from the catch-all (recheck-154 N1).
  const singles = Array.from({ length: 7 }, (_, i) => `{"title":"单${i + 1}","articles":[${i + 1}]}`).join(",");
  const capped = parseTopics(`{"topics":[{"title":"全部","articles":["1-12"]},${singles}]}`, 12);
  assert.equal(capped.length, 7);
  assert.deepEqual(capped[0].articles, [7, 8, 9, 10, 11, 12]);
  assert.equal(new Set(capped.flatMap((topic) => topic.articles)).size, 12);
  // A catch-all left with under half of its articles is the rest, not 「全部」 (recheck-154 N2).
  const rest = parseTopics('{"topics":[{"title":"全部","gist":"今天的全部新闻","summary":"全部","articles":["1-12"]},{"title":"乙","articles":[1,2,3,4,5,6,7,8,9,10,11]}]}', 12);
  assert.deepEqual(rest.map((topic) => [topic.title, topic.gist, topic.articles]), [["其他", "", [12]], ["乙", "", [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]]]);
  // A small board may be one topic.
  assert.deepEqual(parseTopics('{"topics":[{"title":"全部","articles":["1-3"]},{"title":"乙","articles":[1]}]}', 3)[0].articles, [1, 2, 3]);
  assert.deepEqual(articleNumbers("1-14", 12), []);
  const fifty = parseTopics('{"topics":[{"title":"一到五十","articles":["1-50"]},{"title":"台风","articles":[4,5]}]}', 12);
  assert.deepEqual(fifty.map((topic) => [topic.title, topic.articles]), [["台风", [4, 5]]]);
  assert.deepEqual(articleNumbers("12-20", 9), []);
  // A range longer than 100 is junk and names nothing, so later topics keep their articles.
  assert.deepEqual(articleNumbers("1-99999", 9), []);
  assert.deepEqual(articleNumbers("1-99999999", Infinity), []);
  assert.deepEqual(articleNumbers("1-100", 120).length, 100);
  const junk = parseTopics('{"topics":[{"title":"甲","articles":["1-99999"]},{"title":"乙","articles":[2,3]}]}', 9);
  assert.deepEqual(junk.map((topic) => [topic.title, topic.articles]), [["乙", [2, 3]]]);
  // Spaces between numbers separate them; around a range dash they do not.
  assert.deepEqual(articleNumbers("1 2", 9), [1, 2]);
  assert.deepEqual(articleNumbers("第3篇 第5篇", 9), [3, 5]);
  assert.deepEqual(articleNumbers("1 - 3", 9), [1, 2, 3]);
  assert.deepEqual(articleNumbers("1〜3", 9), [1, 2, 3]);
  assert.deepEqual(articleNumbers("1−3", 9), [1, 2, 3]);
  assert.deepEqual(articleNumbers("3-1", 9), [1, 2, 3]);
  assert.deepEqual(articleNumbers("1,2-3", 9), [1, 2, 3]);
  assert.deepEqual(articleNumbers("３、４", 9), [3, 4]);
  assert.deepEqual(articleNumbers("第4篇", 9), [4]);
  assert.deepEqual(articleNumbers("[1,2]", 9), [1, 2]);
  const cut = parseTopics('{"topics":[{"title":"丁","summary":"s","articles":["1-4", 2]', 3);
  assert.deepEqual(cut.map((topic) => topic.articles), [[1, 2, 3]]);
  const bracketed = salvageTopics('{"topics":[{"title":"戊","articles":["[3]", [4], 5]} oops', 9);
  assert.deepEqual(bracketed[0].articles, [3, 4, 5]);
  const topics = parseTopics('{"topics":[{"title":"甲","articles":["1-3","5、6","2.0"]},{"title":"乙","articles":["13", 7]}]}', 9);
  assert.deepEqual(topics.map((topic) => topic.articles), [[1, 2, 3, 5, 6], [7]]);
  const salvaged = salvageTopics('{"topics":[{"title":"丙","gist":"x","summary":"s","articles":["1-2", 2.0, "4、5"]');
  assert.deepEqual(salvaged[0].articles, [1, 2, 2, 4, 5]);
  const closed = salvageTopics('{"title":"丙","gist":"he said "hi"","articles":["1-2", 3.0, "4、5"]}');
  assert.deepEqual(closed[0].articles, [1, 2, 3, 4, 5]);
});

test("a broken reply keeps each topic's own articles, at every cut point and in either key order", async () => {
  const { parseTopics } = await import("../src/toolbox/board/board.mjs");
  const topics = [["台风登陆", [1, 2]], ["AI 模型降价潮", [3, 4]], ["央行降息", [5, 6]]];
  for (const articlesFirst of [false, true]) {
    const reply = JSON.stringify({ topics: topics.map(([title, articles]) => articlesFirst
      ? { articles, title, gist: "要点", summary: "摘要" }
      : { title, gist: "要点", summary: "摘要", articles }) });
    for (let cut = 1; cut < reply.length; cut += 1) {
      let got;
      try { got = parseTopics(reply.slice(0, cut), 9); } catch { continue; }
      for (const topic of got) {
        const want = topics.find(([title]) => title === topic.title);
        assert.ok(want, `cut ${cut}: unknown topic ${topic.title}`);
        assert.ok(topic.articles.every((n) => want[1].includes(n)), `cut ${cut}, articles first ${articlesFirst}: ${topic.title} got ${topic.articles}`);
      }
    }
  }
});

test("an object-like fragment inside a summary does not split its topic", async () => {
  const { salvageTopics } = await import("../src/toolbox/board/board.mjs");
  const got = salvageTopics('{"topics":[{"title":"甲","summary":"他说 {"gist":"inner"} 然后","articles":[1,2]},{"articles":[3],"title":"乙"},{"title":"丙","articles":[4', 9);
  assert.deepEqual(got.map((topic) => [topic.title, topic.articles]), [["甲", [1, 2]], ["乙", [3]]]);
  const comma = salvageTopics('{"topics":[{"title":"甲","summary":"a, {"gist":"inner"} b","articles":[1,2]},{"title":"乙","articles":[3]} oops', 9);
  assert.deepEqual(comma.map((topic) => [topic.title, topic.articles]), [["甲", [1, 2]], ["乙", [3]]]);
});

test("a nested list inside a broken reply still salvages the topics", async () => {
  const { parseTopics } = await import("../src/toolbox/board/board.mjs");
  const got = parseTopics('{"topics":[{"title":"甲","gist":"g","summary":"s","articles":[[3],4,5]},{"title":"乙","articles":[6', 9);
  assert.deepEqual(got.map((topic) => [topic.title, topic.articles]), [["甲", [3, 4, 5]]]);
});

test("the trailing-comma repair leaves commas inside text alone", async () => {
  const { parseTopics } = await import("../src/toolbox/board/board.mjs");
  const [topic] = parseTopics('{"topics":[{"title":"甲","gist":"A, }","summary":"x, ]","articles":[1,],},]}', 3);
  assert.equal(topic.gist, "A, }");
  assert.equal(topic.summary, "x, ]");
  assert.deepEqual(topic.articles, [1]);
});

test("a cut reply's half-arrived last topic is left out, and quoted numbers in an inner list are read", async () => {
  const { parseTopics, readTopics, salvageTopics } = await import("../src/toolbox/board/board.mjs");
  const whole = '{"title":"甲","articles":[1,2],"gist":"g","summary":"s"}';
  // Title and articles in, cut before the summary: not whole.
  for (const tail of ['{"title":"乙","articles":[3],"gist":"g', '{"articles":[3],"title":"乙","gist":"g","sum', '{"title":"乙","articles":[3]']) {
    const topics = parseTopics(`{"topics":[${whole},${tail}`, 9);
    assert.deepEqual(topics.map((topic) => topic.title), ["甲"], tail);
  }
  // Its object closed: kept, even with no summary.
  assert.deepEqual(salvageTopics(`{"topics":[${whole},{"title":"乙","articles":[3]} oops`, 9).map((topic) => topic.title), ["甲", "乙"]);
  // Only the last piece can be half-arrived.
  assert.deepEqual(salvageTopics('[{"title":"甲","articles":[1]},{"title":"乙","summary":"s","articles":[2]', 9).map((topic) => topic.title), ["甲", "乙"]);
  assert.deepEqual(salvageTopics('{"topics":[{"title":"甲","summary":"s","articles":[["3"],4]}, oops', 9)[0].articles, [3, 4]);
  // A whole JSON reply is whole; a salvaged one is not.
  assert.equal(readTopics(`{"topics":[${whole}]}`, 9).whole, true);
  assert.equal(readTopics(`{"topics":[${whole},{"title":"乙"`, 9).whole, false);
});
