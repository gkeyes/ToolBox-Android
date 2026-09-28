import test from "node:test";
import assert from "node:assert/strict";
import { CONTINUOUS_PULL_TRIGGER, continuousPageOffset, findNextUnreadArticle } from "../src/toolbox/continuous-reading-motion.mjs";

test("the next page moves continuously before and across the release threshold", () => {
  const height = 844;
  assert.equal(CONTINUOUS_PULL_TRIGGER, 150);
  assert.equal(continuousPageOffset(0, height), height);
  assert.equal(continuousPageOffset(60, height), height - 60);
  assert.equal(continuousPageOffset(149, height) - continuousPageOffset(151, height), 2);
  assert.equal(continuousPageOffset(-10, height), height);
  assert.equal(continuousPageOffset(1000, height), 0);
});

test("next unread selection skips already-read articles after the active article", () => {
  const rows = [{ id: 1, status: "read" }, { id: 2, status: "read" }, { id: 3, status: "unread" }, { id: 4, status: "read" }];
  assert.equal(findNextUnreadArticle(rows, 1)?.id, 3);
  assert.equal(findNextUnreadArticle(rows, "1")?.id, 3);
  assert.equal(findNextUnreadArticle(rows, 3), null);
  assert.equal(findNextUnreadArticle([], 1), null);
});
