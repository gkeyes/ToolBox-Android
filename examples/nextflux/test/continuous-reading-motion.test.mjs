import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  CONTINUOUS_MAX_EXTRA,
  CONTINUOUS_MOTION_SPEED,
  CONTINUOUS_PAGE_START,
  CONTINUOUS_PULL_TRIGGER,
  continuousRubberBand,
  findNextUnreadArticle,
} from "../src/toolbox/continuous-reading-motion.mjs";

test("continuous reading keeps the V4 trigger distance and iOS-style rubber band", () => {
  assert.equal(CONTINUOUS_PULL_TRIGGER, 150);
  assert.equal(CONTINUOUS_PAGE_START, 150);
  assert.equal(CONTINUOUS_MAX_EXTRA, 240);
  assert.equal(CONTINUOUS_MOTION_SPEED, 1.25);

  assert.equal(continuousRubberBand(0), 0);
  assert.ok(continuousRubberBand(30) > 0);
  assert.ok(continuousRubberBand(60) > continuousRubberBand(30));
  assert.ok(continuousRubberBand(150) > continuousRubberBand(60));
  assert.ok(continuousRubberBand(300) < 78);
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

test("continuous reader pre-renders a real next page and uses the polished SVG progress dock", async () => {
  const [reader, page, header, view] = await Promise.all([
    readFile(new URL("../src/components/ArticleView/components/ContinuousNextUnread.jsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/ArticleView/components/ArticlePageContent.jsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/ArticleView/components/ArticleHeader.jsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/ArticleView/ArticleView.jsx", import.meta.url), "utf8"),
  ]);

  assert.ok(reader.includes("nextflux-continuous-next-page"));
  assert.ok(reader.includes("<ArticlePageContent"));
  assert.ok(reader.includes("RING_CIRCUMFERENCE"));
  assert.ok(reader.includes("nextflux-continuous-ready-check"));
  assert.ok(reader.includes("continuousRubberBand"));
  assert.ok(reader.includes("CONTINUOUS_MOTION_SPEED"));
  assert.ok(reader.includes("rawRef.current + move * 0.86"));
  assert.ok(reader.includes('gestureDirectionRef.current === "horizontal"'));
  assert.ok(reader.includes('Math.abs(totalX) > Math.abs(totalY) * 1.05'));
  assert.ok(reader.includes('document.querySelector(".action-buttons")'));
  assert.ok(reader.includes("activeArticle.set(prepared ?? nextUnread)"));
  assert.ok(page.includes("<ProgressiveArticle"));
  assert.ok(page.includes("<ArticleHeader"));
  assert.ok(!header.includes("layoutId"));
  assert.ok(view.includes("onTransitionStateChange={setContinuousHandoff}"));
  assert.ok(view.includes("continuousHandoff || reduceMotion"));
  assert.ok(view.includes('type: "tween"'));
  assert.ok(!view.includes('type: "spring",\n            bounce: 0'));
});
