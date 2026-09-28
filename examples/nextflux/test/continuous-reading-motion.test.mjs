import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  CONTINUOUS_PULL_MAX,
  CONTINUOUS_PULL_TRIGGER,
  continuousPullResistance,
  findNextUnreadArticle,
  springFrame,
} from "../src/toolbox/continuous-reading-motion.mjs";

test("continuous reading resistance is smooth, monotonic and requires a deliberate pull", () => {
  assert.equal(CONTINUOUS_PULL_TRIGGER, 132);
  assert.equal(CONTINUOUS_PULL_MAX, 260);
  const samples = [0, 8, 24, 32, 48, 72, 96, 112, CONTINUOUS_PULL_TRIGGER, 180, 260]
    .map((value) => continuousPullResistance(value));
  assert.equal(samples[0], 0);
  for (let i = 1; i < samples.length; i += 1) assert.ok(samples[i] > samples[i - 1]);
  assert.ok(continuousPullResistance(32) / 32 > continuousPullResistance(CONTINUOUS_PULL_TRIGGER) / CONTINUOUS_PULL_TRIGGER);
  assert.ok(continuousPullResistance(CONTINUOUS_PULL_MAX) < 100);
});

test("next unread selection skips already-read articles after the active article", () => {
  const rows = [
    { id: 1, status: "read" },
    { id: 2, status: "read" },
    { id: 3, status: "unread" },
    { id: 4, status: "read" },
  ];
  assert.equal(findNextUnreadArticle(rows, 1)?.id, 3);
  assert.equal(findNextUnreadArticle(rows, 3), null);
});

test("spring solver advances toward the target with bounded dt", () => {
  const first = springFrame({ x: 0, velocity: 0, target: 100, dt: 1 / 60, stiffness: 210, damping: 31 });
  assert.ok(first.x > 0);
  assert.ok(first.velocity > 0);
  const bounded = springFrame({ x: 0, velocity: 0, target: 100, dt: 1, stiffness: 210, damping: 31 });
  assert.ok(Number.isFinite(bounded.x));
  assert.ok(bounded.x < 30);
});

test("continuous reader uses existing progress and shared-layout primitives", async () => {
  const [reader, header, view] = await Promise.all([
    readFile(new URL("../src/components/ArticleView/components/ContinuousNextUnread.jsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/ArticleView/components/ArticleHeader.jsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/ArticleView/ArticleView.jsx", import.meta.url), "utf8"),
  ]);
  assert.ok(reader.includes("ProgressCircle"));
  assert.ok(reader.includes("value={progressValue}"));
  assert.ok(reader.includes("setHandoffArticle(nextUnread)"));
  assert.ok(reader.includes("activeArticle.set(prepared ?? nextUnread)"));
  assert.ok(header.includes("${layoutId}-title"));
  assert.ok(view.includes("<LayoutGroup id=\"nextflux-article-reading\">"));
  assert.ok(view.includes("nextflux-article-fade-through"));
});
