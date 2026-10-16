import test from "node:test";
import assert from "node:assert/strict";
import {
  createFeedNotifier, feedNotificationId, feedNotificationText, routeForOpen, FEED_NOTIFY_KEY,
} from "../src/toolbox/feed-notify.mjs";

// A stand-in for the Miniflux server and the ToolBox host. The server holds entries; the host
// stores tool data, records posted notifications and turns a tap into a notifications.open event.
function world({ notificationsGranted = true, uncached = new Set() } = {}) {
  const entries = [];
  const failingFeeds = new Set();
  const data = new Map();
  const posted = [];
  const cancelled = [];
  const openListeners = [];
  let foreground = false;
  let systemAllowed = true;
  const denied = () => Object.assign(new Error("denied"), { code: "PERMISSION_DENIED" });
  const server = {
    add(feedId, id, title, status = "unread") { entries.push({ id, feed_id: feedId, title, status }); },
    read(id) { entries.find((entry) => entry.id === id).status = "read"; },
    fail(feedId) { failingFeeds.add(feedId); },
  };
  // The two Miniflux reads NextFlux makes: GET /v1/feeds/{id}/entries?order=id&direction=desc&limit=1
  // and ...&status=unread&after_entry_id={id}.
  const api = {
    async latestEntryId(feedId) {
      if (failingFeeds.has(feedId)) throw Object.assign(new Error("offline"), { code: "NETWORK_UNAVAILABLE" });
      return entries.filter((entry) => entry.feed_id === feedId).reduce((max, entry) => Math.max(max, entry.id), 0) || null;
    },
    async newUnreadEntries(feedId, after) {
      if (failingFeeds.has(feedId)) throw Object.assign(new Error("offline"), { code: "NETWORK_UNAVAILABLE" });
      const found = entries.filter((entry) => entry.feed_id === feedId && entry.status === "unread" && entry.id > after)
        .sort((a, b) => b.id - a.id);
      return { total: found.length, entries: found.slice(0, 20) };
    },
  };
  const toolbox = {
    storage: {
      async get(key) { return data.has(key) ? structuredClone(data.get(key)) : null; },
      async set(key, value) { data.set(key, structuredClone(value)); },
      async remove(key) { data.delete(key); },
    },
    notifications: {
      async post(id, title, body) {
        if (!notificationsGranted) throw denied();
        // Android's own switch: only a post sees it, cancel does not.
        if (!systemAllowed) throw Object.assign(new Error("off"), { code: "SYSTEM_PERMISSION_DENIED" });
        posted.push({ id, title, body }); },
      async cancel(id) { if (!notificationsGranted) throw denied(); cancelled.push(id); },
      onOpen(listener) { openListeners.push(listener); return () => {}; },
    },
  };
  const host = {
    tap(id) { openListeners.forEach((listener) => listener({ id, openedAt: Date.now() })); },
    setForeground(value) { foreground = value; },
    setSystemNotifications(value) { systemAllowed = value; },
  };
  const titles = { 12: "少数派", 13: "Hacker News" };
  const notifier = createFeedNotifier({
    api, notifications: toolbox.notifications, storage: toolbox.storage,
    feedTitle: (feedId) => titles[feedId], isForeground: () => foreground,
    isCached: async (id) => !uncached.has(id),
  });
  return { server, toolbox, host, notifier, posted, data, cancelled };
}

test("the whole chain: watch a feed, background sync finds new entries, notify, tap opens the article", async () => {
  const { server, toolbox, host, notifier, posted } = world();
  server.add(12, 100, "旧文章");
  server.add(13, 150, "别的源");

  // The reader turns on notifications for 少数派; existing entries never notify.
  assert.equal(await notifier.setWatched(12, true), true);
  assert.deepEqual(await notifier.watchedFeedIds(), [12]);
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "none" }]);
  assert.equal(posted.length, 0);

  // NextFlux's tap handler, as registered at start-up.
  const routes = [];
  toolbox.notifications.onOpen((event) => notifier.takeOpen(event).then((route) => route && routes.push(route)));

  // The next background sync: one new entry in the watched feed, one in an unwatched feed.
  server.add(12, 101, "  iOS 27 体验\n报告 ");
  server.add(13, 151, "不该通知");
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "notified", count: 1 }]);
  assert.deepEqual(posted, [{ id: "feed-12", title: "少数派", body: "iOS 27 体验 报告" }]);

  // Nothing new: no second notification.
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "none" }]);
  assert.equal(posted.length, 1);

  // The user taps it; ToolBox replays the tap to the page, which opens that entry once.
  host.tap("feed-12");
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(routes, ["/feed/12/article/101"]);
  host.tap("feed-12");
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(routes, ["/feed/12/article/101"], "A tap on an already handled notification opens nothing new");
});

test("several new entries make one notification that opens the feed", async () => {
  const { server, notifier, posted } = world();
  server.add(12, 100, "旧");
  await notifier.setWatched(12, true);
  for (let id = 101; id <= 105; id += 1) server.add(12, id, `第 ${id} 篇`);

  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "notified", count: 5 }]);
  assert.deepEqual(posted, [{ id: "feed-12", title: "少数派", body: "5 篇新文章：第 105 篇；第 104 篇；第 103 篇…" }]);
  assert.equal(await notifier.takeOpen({ id: "feed-12" }), "/feed/12");
});

test("entries read before the next sync do not notify, and the cursor only moves forward", async () => {
  const { server, notifier, posted } = world();
  server.add(12, 100, "旧");
  await notifier.setWatched(12, true);
  server.add(12, 101, "已在前台读过");
  server.read(101);
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "none" }]);
  server.add(12, 102, "新的");
  await notifier.check();
  assert.deepEqual(posted.map((item) => item.body), ["新的"]);
});

test("while the reader is on screen new entries advance the cursor without a notification", async () => {
  const { server, host, notifier, posted } = world();
  server.add(12, 100, "旧");
  await notifier.setWatched(12, true);
  host.setForeground(true);
  server.add(12, 101, "前台看到");
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "foreground", count: 1 }]);
  host.setForeground(false);
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "none" }]);
  assert.equal(posted.length, 0);
});

test("an empty feed notifies its first entry", async () => {
  const { server, notifier, posted } = world();
  await notifier.setWatched(12, true);
  server.add(12, 1, "第一篇");
  await notifier.check();
  assert.deepEqual(posted, [{ id: "feed-12", title: "少数派", body: "第一篇" }]);
});

test("one unreachable feed does not stop the others", async () => {
  const { server, notifier, posted } = world();
  await notifier.setWatched(12, true);
  await notifier.setWatched(13, true);
  server.fail(12);
  server.add(13, 7, "照常通知");
  assert.deepEqual(await notifier.check(), [
    { feedId: 12, outcome: "failed", code: "NETWORK_UNAVAILABLE" },
    { feedId: 13, outcome: "notified", count: 1 },
  ]);
  assert.deepEqual(posted.map((item) => item.id), ["feed-13"]);
});

test("turning a feed on without the notification permission fails and saves nothing", async () => {
  const { notifier, data } = world({ notificationsGranted: false });
  await assert.rejects(notifier.setWatched(12, true), (error) => error.code === "PERMISSION_DENIED");
  assert.equal(data.has(FEED_NOTIFY_KEY), false);
});

test("a refused post keeps the entries for the next sync and the toggle reports it", async () => {
  const { server, host, notifier, posted } = world();
  await notifier.setWatched(12, true);
  host.setSystemNotifications(false);
  server.add(12, 1, "一篇");
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "failed", code: "SYSTEM_PERMISSION_DENIED" }]);
  assert.equal(await notifier.setWatched(12, true), "blocked");

  host.setSystemNotifications(true);
  server.add(12, 2, "两篇");
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "notified", count: 2 }]);
  assert.deepEqual(posted.map((item) => item.body), ["2 篇新文章：两篇；一篇"]);
  assert.equal(await notifier.setWatched(12, true), true);
});

test("turning a feed off forgets it and its pending tap", async () => {
  const { server, notifier } = world();
  await notifier.setWatched(12, true);
  server.add(12, 1, "一篇");
  await notifier.check();
  assert.equal(await notifier.setWatched(12, false), false);
  assert.deepEqual(await notifier.watchedFeedIds(), []);
  assert.equal(await notifier.takeOpen({ id: "feed-12" }), null);
});

test("text and route helpers", () => {
  assert.equal(feedNotificationId(12), "feed-12");
  assert.deepEqual(feedNotificationText("", [{ title: "" }]), { title: "订阅源更新", body: "无标题" });
  assert.deepEqual(feedNotificationText("A", [{ title: "x" }, { title: "y" }]), { title: "A", body: "2 篇新文章：x；y" });
  assert.equal(routeForOpen({ feedId: 3, entryIds: [9] }), "/feed/3/article/9");
  assert.equal(routeForOpen({ feedId: 3, entryIds: [9, 8] }), "/feed/3");
  // One entry listed but several announced (the rest were past the page): open the feed.
  assert.equal(routeForOpen({ feedId: 3, entryIds: [9], total: 4 }), "/feed/3");
  assert.equal(routeForOpen(null), null);
});

test("an entry the committed sync has not cached waits for the sync that caches it", async () => {
  const uncached = new Set([201]);
  const { server, notifier, posted } = world({ uncached });
  await notifier.setWatched(12, true);
  server.add(12, 200, "已缓存");
  server.add(12, 201, "同步之后才到");
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "notified", count: 1 }]);
  assert.match(posted.at(-1).body, /已缓存/);
  assert.doesNotMatch(posted.at(-1).body, /同步之后才到/);
  uncached.clear();
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "notified", count: 1 }]);
  assert.match(posted.at(-1).body, /同步之后才到/);
});

test("clear forgets every watch and withdraws posted notices", async () => {
  const { server, notifier, data, cancelled } = world();
  await notifier.setWatched(12, true);
  await notifier.setWatched(13, true);
  server.add(12, 300, "新");
  await notifier.check();
  cancelled.length = 0;
  await notifier.clear();
  assert.equal(data.has(FEED_NOTIFY_KEY), false);
  assert.deepEqual(await notifier.watchedFeedIds(), []);
  assert.deepEqual(cancelled.sort(), [feedNotificationId(12), feedNotificationId(13)].sort());
});

test("notification text follows the strings NextFlux passes for its language", () => {
  const strings = {
    untitledFeed: "Feed update", untitled: "Untitled", one: "1 new entry", separator: "; ",
    many: (count, titles) => `${count} new entries: ${titles}`,
  };
  assert.deepEqual(feedNotificationText("", [{ title: "A" }, { title: "" }], 2, strings),
    { title: "Feed update", body: "2 new entries: A; Untitled" });
  assert.deepEqual(feedNotificationText("HN", [], 1, strings), { title: "HN", body: "1 new entry" });
});

test("turning a feed off and on again after allowing notifications clears the blocked mark", async () => {
  const { server, host, notifier } = world();
  await notifier.setWatched(12, true);
  host.setSystemNotifications(false);
  server.add(12, 1, "一篇");
  await notifier.check();
  assert.equal(await notifier.setWatched(12, true), "blocked");
  host.setSystemNotifications(true);
  await notifier.setWatched(12, false);
  assert.equal(await notifier.setWatched(12, true), true);
});

test("a foreground check only moves the cursor", async () => {
  const { server, notifier, posted } = world();
  await notifier.setWatched(12, true);
  server.add(12, 1, "一篇");
  assert.deepEqual(await notifier.check({ foreground: true }), [{ feedId: 12, outcome: "foreground", count: 1 }]);
  assert.deepEqual(await notifier.check(), [{ feedId: 12, outcome: "none" }]);
  assert.equal(posted.length, 0);
});

test("a failed storage read aborts the check and keeps the watches", async () => {
  const { toolbox, notifier, data } = world();
  await notifier.setWatched(12, true);
  const saved = structuredClone(data.get(FEED_NOTIFY_KEY));
  const get = toolbox.storage.get;
  toolbox.storage.get = async () => { throw Object.assign(new Error("io"), { code: "IO" }); };
  await assert.rejects(notifier.check(), (error) => error.code === "IO");
  toolbox.storage.get = get;
  assert.deepEqual(data.get(FEED_NOTIFY_KEY), saved);
  assert.deepEqual(await notifier.watchedFeedIds(), [12]);
});
