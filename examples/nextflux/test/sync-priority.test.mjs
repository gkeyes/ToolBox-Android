import test from "node:test";
import assert from "node:assert/strict";
import { runPrioritizedArticleSync } from "../src/toolbox/sync-priority.mjs";

test("incremental sync finishes unread before read-state reconciliation", async () => {
  const events = [];
  let releaseUnread;
  const unreadGate = new Promise((resolve) => { releaseUnread = resolve; });
  const operation = runPrioritizedArticleSync({
    initial: false,
    setProgress: (message) => events.push(["progress", message]),
    unread: async () => { events.push(["start", "unread"]); await unreadGate; events.push(["done", "unread"]); },
    readChanged: async () => { events.push(["start", "read"]); },
    starred: async () => assert.fail("starred is initial-sync only"),
  });
  await Promise.resolve();
  assert.deepEqual(events.map(([, value]) => value), ["正在优先同步未读文章…", "unread"]);
  releaseUnread();
  await operation;
  const doneUnread = events.findIndex((event) => event[0] === "done" && event[1] === "unread");
  const startRead = events.findIndex((event) => event[0] === "start" && event[1] === "read");
  assert.ok(doneUnread >= 0 && startRead > doneUnread);
});

test("initial sync waits for unread before read bookmarks", async () => {
  const events = [];
  await runPrioritizedArticleSync({
    initial: true,
    setProgress: (message) => events.push(message),
    unread: async () => events.push("unread"),
    starred: async () => events.push("starred"),
    readChanged: async () => assert.fail("readChanged is incremental only"),
  });
  assert.deepEqual(events, ["正在优先同步未读文章…", "unread", "正在同步已读收藏…", "starred"]);
});

test("unread failure stops all later phases", async () => {
  let later = 0;
  await assert.rejects(runPrioritizedArticleSync({
    initial: false,
    unread: async () => { throw new Error("unread failed"); },
    readChanged: async () => { later += 1; },
    starred: async () => { later += 1; },
  }), /unread failed/);
  assert.equal(later, 0);
});
