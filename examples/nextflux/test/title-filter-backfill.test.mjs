import assert from "node:assert/strict";
import test from "node:test";
import { applyUnreadTitleFilter, previewUnreadTitleFilter } from "../src/toolbox/title-filter/title-filter-backfill.mjs";
import { createTitleRule } from "../src/toolbox/title-filter/title-filter-rules.mjs";

const feedId = 42;
const feedWithRules = (rules, extra) => ({ id: feedId, block_filter_entry_rules: rules, ...extra });
const entry = (id, title, extra) => ({ id, title, feed_id: feedId, status: "unread", starred: false, ...extra });

function fixtures(rules, pages, extra) {
  const state = { feed: feedWithRules(rules, extra), pages, readCount: 0, writes: [], cursors: [] };
  const readFeed = async () => {
    state.readCount++;
    return state.feed;
  };
  const fetchPages = async ({ feedId: scope, beforeEntryId, check, onPage }) => {
    assert.equal(scope, feedId);
    state.cursors.push(beforeEntryId);
    for (const page of state.pages) {
      await check();
      await onPage(page.filter(row => beforeEntryId == null || row.id < beforeEntryId));
    }
  };
  const markAsRead = async (potentialIds, revalidate, context) => {
    const ids = await revalidate(async () => { await context(); });
    assert.ok(ids.every(id => potentialIds.includes(id)));
    state.writes.push(...ids);
    return ids.length;
  };
  return { feedId, state, readFeed, fetchPages, markAsRead };
}

test("managed keywords and Miniflux EntryTitle patterns match only the current feed and show why", async () => {
  const rules = [
    createTitleRule(["优惠", "a+b"]),
    "  EntryTitle = (?i:^sponsor[[:digit:]]+\\s*:) \r",
    "EntryTitle=^⭐(?P<letter>K)$",
    "EntryContent=勿扫正文",
  ].join("\n");
  const fx = fixtures(rules, [
    [entry(50, "今日优惠"), entry(49, "a+b is literal"), entry(48, "SPONSOR9 : sale")],
    [entry(40, "⭐K"), entry(39, "aab without a literal plus"), entry(38, "优惠收藏", { starred: true })],
    [entry(21, "勿扫正文", { content: "今日优惠" }), entry(20, "本地没缓存过的优惠")],
  ], { blocklist_rules: "legacy", keep_filter_entry_rules: "EntryAuthor=abc" });
  const progress = [];
  const p = await previewUnreadTitleFilter({ ...fx, onProgress: n => progress.push(n) });
  assert.equal(p.scanned, 8);
  assert.equal(p.hasRules, true);
  assert.equal(p.skippedStarred, 1);
  assert.equal(p.beforeEntryId, 51);
  assert.deepEqual(p.matches.map(x => x.id), [50, 49, 48, 40, 20]);
  assert.match(p.matches[0].reason, /标题关键词「优惠」/u);
  assert.match(p.matches[2].reason, /标题规则第 2 行/u);
  assert.deepEqual(p.skippedRules, ["旧式屏蔽规则", "条目保留规则", "正文屏蔽规则"]);
  assert.deepEqual(progress.at(-1), { scanned: 8, skippedStarred: 1, matchedCount: 5 });
  assert.deepEqual(fx.state.writes, []);
});

test("unsupported patterns are named; no matches are fabricated from empty or non-Go patterns", async () => {
  const rules = ["EntryTitle=", "EntryTitle=(?<=a)b", "EntryTitle=(a)\\1", "EntryTitle=\\p{Assigned}", "PostedDate=future"].join("\n");
  const fx = fixtures(rules, [[entry(1, "ab")]], { keeplist_rules: "legacy" });
  const preview = await previewUnreadTitleFilter(fx);
  assert.deepEqual(preview.matches, []);
  assert.equal(preview.hasRules, false);
  assert.equal(preview.scanned, 0);
  assert.deepEqual(fx.state.cursors, []);
  assert.deepEqual(preview.skippedRules, ["旧式保留规则", "标题规则第 1 行（规则为空或过长）", "标题规则第 2 行（正则无效或不支持）", "标题规则第 3 行（正则无效或不支持）", "标题规则第 4 行（正则无效或不支持）", "无法识别的条目屏蔽规则"]);
});

test("matches Go-style NEL whitespace, keeps literal BOM and equals and uses Unicode simple folding", async () => {
  const fx = fixtures("\x85 EntryTitle \x85=\x85(?i:^k=)$\x85\r\nEntryTitle=\ufeffz", [[
    entry(8, "K="), entry(7, "ABCk="), entry(6, "z"), entry(5, "\ufeffz"),
  ]]);
  const result = await previewUnreadTitleFilter(fx);
  assert.deepEqual(result.matches.map(match => match.id), [8, 5]);
});

test("confirmation rewrites only previewed IDs that remain unread, unstarred, and matching", async () => {
  const fx = fixtures("EntryTitle=过滤", [[
    entry(80, "过滤"), entry(79, "过滤"), entry(70, "过滤"), entry(69, "保留"), entry(3, "旧的过滤"),
  ]]);
  const preview = await previewUnreadTitleFilter(fx);
  fx.state.pages = [[entry(100, "过滤"), entry(80, "过滤", { starred: true }), entry(79, "过滤", { status: "read" })],
    [entry(70, "改成保留"), entry(69, "后来才过滤"), entry(3, "旧的过滤")]];
  assert.deepEqual(await applyUnreadTitleFilter({ ...fx, preview }), { markedCount: 1, skippedCount: 3 });
  assert.deepEqual(fx.state.writes, [3]);
  assert.deepEqual(fx.state.cursors, [undefined, 81]);
});

test("source or rule changes invalidate confirmation before any write", async () => {
  const fx = fixtures("EntryTitle=过滤", [[entry(80, "过滤")]]);
  const preview = await previewUnreadTitleFilter(fx);
  fx.state.feed = feedWithRules("EntryTitle=过滤\nEntryTitle=保留");
  await assert.rejects(applyUnreadTitleFilter({ ...fx, preview }), { code: "BACKFILL_RULES_CHANGED" });
  fx.state.feed = feedWithRules("EntryTitle=过滤");
  const fetchPages = async options => {
    await fx.fetchPages(options);
    fx.state.feed = feedWithRules("EntryTitle=变更");
  };
  await assert.rejects(applyUnreadTitleFilter({ ...fx, preview, fetchPages }), { code: "BACKFILL_RULES_CHANGED" });
  fx.state.feed = feedWithRules("EntryTitle=过滤");
  fx.state.pages = [[entry(80, "过滤", { feed_id: 99 })]];
  await assert.rejects(applyUnreadTitleFilter({ ...fx, preview }), { code: "INVALID_RESPONSE" });
  assert.deepEqual(fx.state.writes, []);
});

test("mid-scan failure and canceled context never expose writable partial previews", async () => {
  const fx = fixtures("EntryTitle=过滤", [[entry(80, "过滤")]]);
  const fetchPages = async options => {
    await fx.fetchPages(options);
    throw new Error("offline");
  };
  await assert.rejects(previewUnreadTitleFilter({ ...fx, fetchPages }), /offline/);
  let live = true;
  const check = () => live;
  await assert.rejects(previewUnreadTitleFilter({ ...fx, check, onProgress: () => { live = false; } }), { code: "CANCELLED" });
  assert.deepEqual(fx.state.writes, []);
});

test("partial failures preserve confirmed count so retry skips articles already read", async () => {
  const fx = fixtures("EntryTitle=过滤", [[entry(80, "过滤"), entry(79, "过滤")]]);
  const preview = await previewUnreadTitleFilter(fx);
  const markAsRead = async (_ids, revalidate) => {
    assert.deepEqual(await revalidate(() => {}), [80, 79]);
    fx.state.pages[0][0].status = "read";
    throw Object.assign(new Error("offline"), { markedCount: 1 });
  };
  await assert.rejects(applyUnreadTitleFilter({ ...fx, preview, markAsRead }), { markedCount: 1 });
  assert.deepEqual(await applyUnreadTitleFilter({ ...fx, preview }), { markedCount: 1, skippedCount: 1 });
  assert.deepEqual(fx.state.writes, [79]);
});
