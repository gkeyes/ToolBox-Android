import assert from "node:assert/strict";
import test from "node:test";
import { attachArticleSwipeBack, shouldFinishArticleSwipe } from "../src/hooks/articleSwipeBack.js";
import { withAnimationClock } from "./helpers/animation-clock.mjs";

const point = (clientX, clientY = 100, identifier = 0) => ({ clientX, clientY, identifier });

function harness(clock, extraOptions = {}) {
  const document = new EventTarget();
  const page = new EventTarget();
  let selected = false;
  let blocked = false;
  let backs = 0;
  const active = [];
  let options = {
    enabled: true, onBack: () => { backs += 1; }, onActiveChange: (value) => active.push(value), ...extraOptions,
  };
  Object.assign(page, {
    ownerDocument: document, clientWidth: 360, dataset: {},
    style: { transform: "", transition: "", willChange: "" },
    contains: (target) => target === page || target.parentElement === page,
  });
  document.getSelection = () => ({ rangeCount: selected ? 1 : 0, isCollapsed: !selected });
  document.defaultView = new EventTarget();
  Object.assign(document.defaultView, { innerWidth: 360, getComputedStyle: () => ({ transform: page.style.transform || "none" }) });
  const detach = attachArticleSwipeBack(page, { getOptions: () => options, isBlocked: () => blocked });
  const dispatch = (type, touches, changedTouches = [], extra = {}) => {
    const event = new Event(type, { cancelable: extra.cancelable ?? true });
    const timeStamp = extra.timeStamp ?? clock.now();
    clock.set(timeStamp);
    Object.assign(event, { touches, changedTouches });
    Object.defineProperty(event, "timeStamp", { value: timeStamp });
    Object.defineProperty(event, "target", { value: extra.target ?? page });
    if (type === "touchstart" && page.contains(event.target)) page.dispatchEvent(event);
    document.dispatchEvent(event);
    return event;
  };
  return {
    page, active, detach, dispatch,
    start: (x = 40, y = 100, extra) => dispatch("touchstart", [point(x, y)], [], extra),
    move: (x, y = 100, extra) => dispatch("touchmove", [point(x, y)], [], extra),
    end: (x, y = 100, extra) => dispatch("touchend", [], [point(x, y)], extra),
    block: (value) => { blocked = value; },
    select: (value) => { selected = value; },
    setOptions: (value) => { options = { ...options, ...value }; },
    resize: (width, notify = true) => {
      page.clientWidth = width;
      document.defaultView.innerWidth = width;
      if (notify) document.defaultView.dispatchEvent(new Event("resize"));
    },
    backs: () => backs,
    offset: () => page.style.transform ? Number(page.style.transform.match(/^translate3d\(([^p]+)/)?.[1] ?? 0) : 0,
  };
}

// Keep the slow article-to-list return boundary at 10% of the page width.
test("article back decision uses distance, recent velocity and explicit reversal", () => {
  assert.equal(shouldFinishArticleSwipe(36, 360, 0), true);
  assert.equal(shouldFinishArticleSwipe(35, 360, 0), false);
  assert.equal(shouldFinishArticleSwipe(50, 360, 700), true);
  assert.equal(shouldFinishArticleSwipe(20, 360, 1500), false);
  assert.equal(shouldFinishArticleSwipe(35, 360, 0), false);
  assert.equal(shouldFinishArticleSwipe(200, 360, -500), false);
  assert.equal(shouldFinishArticleSwipe(0, 360, 1500), false);
});

test("right drag follows the full shell and slow short release restores its original styles", () => {
  withAnimationClock((clock) => {
    const h = harness(clock);
    h.page.style.transition = "opacity 100ms";
    h.page.style.willChange = "opacity";
    h.start();
    assert.equal(h.move(45).defaultPrevented, false);
    assert.deepEqual(h.active, []);
    assert.equal(h.move(70, 100, { timeStamp: 100 }).defaultPrevented, true);
    assert.equal(h.offset(), 30);
    assert.equal(h.page.dataset.readingMotion, "swipe");
    assert.deepEqual(h.active, [true]);
    h.end(70, 100, { timeStamp: 200 });
    assert.equal(h.backs(), 0);
    clock.step(16);
    assert.ok(h.offset() < 30 && h.offset() > 0);
    clock.settle();
    assert.equal(h.offset(), 0);
    assert.equal(h.page.style.transform, "");
    assert.equal(h.page.style.transition, "opacity 100ms");
    assert.equal(h.page.style.willChange, "opacity");
    assert.equal(h.page.dataset.readingMotion, undefined);
    assert.deepEqual(h.active, [true, false]);
    assert.equal(h.backs(), 0);
    h.detach();
  });
});

test("an entrance can hand off to swipe-back without a jump or stuck motion", () => {
  for (const [entranceOffset, expectBack] of [[15, false], [120, true]]) {
    withAnimationClock((clock) => {
      const h = harness(clock);
      let takeovers = 0;
      h.page.style.transform = `translate3d(${entranceOffset}px,0,0)`;
      h.page.style.willChange = "transform";
      h.page.dataset.readingMotion = "entrance";
      h.setOptions({
        onTakeoverEntrance: () => {
          takeovers += 1;
          h.page.style.willChange = "";
          return true;
        },
      });
      h.start();
      h.move(55, 100, { timeStamp: 20 });
      assert.equal(takeovers, 1);
      assert.equal(h.offset(), entranceOffset + 15);
      assert.equal(h.page.dataset.readingMotion, "swipe");
      h.end(55, 100, { timeStamp: 200 });
      clock.settle();
      assert.equal(h.backs(), expectBack ? 1 : 0);
      assert.equal(h.offset(), expectBack ? 360 : 0);
      if (!expectBack) {
        assert.equal(h.page.style.transform, "");
        assert.equal(h.page.style.willChange, "");
        assert.equal(h.page.dataset.readingMotion, undefined);
      }
      h.detach();
    });
  }
});

test("a fast short swipe navigates only after the shell is completely offscreen", () => {
  withAnimationClock((clock) => {
    const h = harness(clock);
    h.start();
    h.move(80, 100, { timeStamp: 20 });
    h.end(90, 100, { timeStamp: 30 });
    assert.equal(h.page.dataset.readingMotion, "swipe-release");
    assert.equal(h.offset(), 50);
    assert.equal(h.backs(), 0);
    clock.step(16);
    assert.ok(h.offset() > 50 && h.offset() < 360);
    assert.equal(h.backs(), 0);
    clock.settle();
    assert.equal(h.offset(), 360);
    assert.equal(h.backs(), 1);
    assert.deepEqual(h.active, [true, false]);
    clock.step(500);
    assert.equal(h.backs(), 1);
    h.detach();
    assert.equal(h.page.style.transform, "");
  });
});

test("resizing during a drag cancels it and a fresh gesture uses the new shell width", () => {
  withAnimationClock((clock) => {
    const h = harness(clock);
    h.start();
    h.move(220, 100, { timeStamp: 20 });
    h.resize(700);
    h.end(320, 100, { timeStamp: 30 });
    clock.settle();
    assert.equal(h.offset(), 0);
    assert.equal(h.backs(), 0);
    assert.equal(h.page.dataset.readingMotion, undefined);
    assert.deepEqual(h.active, [true, false]);
    h.start();
    h.move(400, 100, { timeStamp: clock.now() + 20 });
    h.end(400, 100, { timeStamp: clock.now() + 100 });
    clock.settle();
    assert.equal(h.offset(), 700);
    assert.equal(h.backs(), 1);
    h.detach();
  });
});

test("a resized release returns to zero and an unchanged-size resize still completes", () => {
  for (const width of [320, 640, 360]) {
    withAnimationClock((clock) => {
      const h = harness(clock);
      h.start();
      h.move(220, 100, { timeStamp: 20 });
      h.end(220, 100, { timeStamp: 30 });
      clock.step(16);
      h.resize(width);
      clock.settle();
      assert.equal(h.backs(), width === 360 ? 1 : 0);
      assert.equal(h.offset(), width === 360 ? 360 : 0);
      assert.deepEqual(h.active, [true, false]);
      h.detach();
    });
  }
});

test("a new touch or release detects a shell-width change even without a resize event", () => {
  for (const phase of ["tracking", "settling"]) {
    withAnimationClock((clock) => {
      const h = harness(clock);
      h.start();
      h.move(220, 100, { timeStamp: 20 });
      if (phase === "settling") h.end(220, 100, { timeStamp: 30 });
      h.resize(640, false);
      if (phase === "settling") h.start();
      else h.end(220, 100, { timeStamp: 40 });
      clock.settle();
      assert.equal(h.backs(), 0);
      assert.equal(h.offset(), 0);
      assert.deepEqual(h.active, [true, false]);
      h.detach();
    });
  }
});

test("cleanup and pending frames preserve a parent entrance/exit that has taken ownership", () => {
  for (const marker of ["entrance", "release", "continuous"]) {
    for (const detachFirst of [false, true]) {
      withAnimationClock((clock) => {
        const h = harness(clock);
        h.start();
        h.move(220, 100, { timeStamp: 20 });
        h.end(220, 100, { timeStamp: 30 });
        const parentStyles = { transform: "translate3d(260px, 0, 0)", transition: "transform 180ms", willChange: "transform, opacity" };
        Object.assign(h.page.style, parentStyles);
        h.page.dataset.readingMotion = marker;
        if (detachFirst) h.detach();
        clock.settle();
        assert.deepEqual(h.page.style, parentStyles);
        assert.equal(h.page.dataset.readingMotion, marker);
        assert.equal(h.backs(), 0);
        assert.deepEqual(h.active, [true, false]);
        h.detach();
        h.resize(700);
        assert.equal(clock.pending(), 0);
        assert.deepEqual(h.page.style, parentStyles);
      });
    }
  }
});

test("cleanup restores only matching owned values after a desktop layout clears the marker", () => {
  withAnimationClock((clock) => {
    const h = harness(clock);
    h.page.style.transition = "opacity 100ms";
    h.page.style.willChange = "opacity";
    h.start();
    h.move(220, 100, { timeStamp: 20 });
    h.page.style.transform = "";
    delete h.page.dataset.readingMotion;
    h.detach();
    assert.equal(h.page.style.transform, "");
    assert.equal(h.page.style.transition, "opacity 100ms");
    assert.equal(h.page.style.willChange, "opacity");
    assert.equal(h.page.dataset.readingMotion, undefined);
  });
});

test("holding a short flick before release expires velocity and cancels back", () => {
  withAnimationClock((clock) => {
    const h = harness(clock);
    h.start();
    h.move(70, 100, { timeStamp: 20 });
    h.end(70, 100, { timeStamp: 200 });
    clock.settle();
    assert.equal(h.backs(), 0);
    assert.equal(h.offset(), 0);
    h.detach();
  });
});

test("a long held drag still completes by distance; a deliberate reversal returns to the article", () => {
  for (const reverse of [false, true]) {
    withAnimationClock((clock) => {
      const h = harness(clock);
      h.start();
      h.move(240, 100, { timeStamp: 20 });
      if (reverse) h.move(180, 100, { timeStamp: 40 });
      h.end(reverse ? 180 : 240, 100, { timeStamp: reverse ? 50 : 200 });
      clock.settle();
      assert.equal(h.backs(), reverse ? 0 : 1);
      assert.equal(h.offset(), reverse ? 0 : 360);
      h.detach();
    });
  }
});

test("vertical and left gestures preserve native scrolling and never activate the shell", () => {
  for (const [x, y] of [[41, 120], [-40, 100], [49, 107]]) {
    withAnimationClock((clock) => {
      const h = harness(clock);
      h.start();
      assert.equal(h.move(x, y, { timeStamp: 20 }).defaultPrevented, false);
      h.end(200, y, { timeStamp: 40 });
      clock.settle();
      assert.equal(h.backs(), 0);
      assert.deepEqual(h.active, []);
      assert.equal(h.page.style.transform, "");
      h.detach();
    });
  }
});

test("touchcancel and a second finger animate back without a navigation", () => {
  for (const multi of [false, true]) {
    withAnimationClock((clock) => {
      const h = harness(clock);
      h.start();
      h.move(220, 100, { timeStamp: 20 });
      h.dispatch(multi ? "touchstart" : "touchcancel", multi ? [point(220), point(200, 120, 1)] : [], [point(220)], { timeStamp: 30 });
      h.end(240, 100, { timeStamp: 40 });
      clock.settle();
      assert.equal(h.offset(), 0);
      assert.equal(h.backs(), 0);
      assert.deepEqual(h.active, [true, false]);
      h.detach();
    });
  }
});

test("selection/modal/gallery blockers at release and during settle cannot navigate", () => {
  for (const blocker of ["select", "block"]) {
    for (const afterRelease of [false, true]) {
      withAnimationClock((clock) => {
        const h = harness(clock);
        h.start();
        h.move(220, 100, { timeStamp: 20 });
        if (!afterRelease) h[blocker](true);
        h.end(220, 100, { timeStamp: 30 });
        if (afterRelease) h[blocker](true);
        clock.settle();
        assert.equal(h.backs(), 0);
        assert.equal(h.offset(), 0);
        assert.deepEqual(h.active, [true, false]);
        h.detach();
      });
    }
  }
});

test("a disabled route, other page, nested horizontal control, or existing motion does not claim touch", () => {
  for (const reason of ["disabled", "outside", "control", "continuous", "entrance", "release", "offset"]) {
    withAnimationClock((clock) => {
      const h = harness(clock, { enabled: reason !== "disabled" });
      if (["continuous", "entrance", "release"].includes(reason)) h.page.dataset.readingMotion = reason;
      if (reason === "offset") h.page.style.transform = "translate3d(20px, 0, 0)";
      const target = reason === "outside" ? {} : reason === "control" ? { parentElement: h.page, matches: () => true } : h.page;
      h.start(40, 100, { target });
      assert.equal(h.move(220, 100, { timeStamp: 20, target }).defaultPrevented, false);
      h.end(220, 100, { timeStamp: 30, target });
      clock.settle();
      assert.equal(h.backs(), 0);
      assert.deepEqual(h.active, []);
      h.detach();
    });
  }
});

test("reduced motion finishes the same decision immediately and teardown cancels pending completion", () => {
  for (const reduceMotion of [false, true]) {
    withAnimationClock((clock) => {
      const h = harness(clock, { reduceMotion });
      h.start();
      h.move(220, 100, { timeStamp: 20 });
      h.end(220, 100, { timeStamp: 30 });
      assert.equal(h.backs(), reduceMotion ? 1 : 0);
      h.detach();
      clock.settle();
      assert.equal(h.backs(), reduceMotion ? 1 : 0);
      assert.equal(h.page.style.transform, "");
      assert.equal(h.page.style.willChange, "");
    });
  }
});

test("system-edge starts remain available to native back; zero identity transforms allow interior drag", () => {
  for (const x of [8, 352]) {
    withAnimationClock((clock) => {
      const h = harness(clock);
      h.start(x);
      assert.equal(h.move(x + 80, 100, { timeStamp: 20 }).defaultPrevented, false);
      h.end(x + 80, 100, { timeStamp: 30 });
      assert.deepEqual(h.active, []);
      assert.equal(h.backs(), 0);
      h.detach();
    });
  }
  withAnimationClock((clock) => {
    const h = harness(clock);
    h.page.style.transform = "matrix3d(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)";
    h.start();
    assert.equal(h.move(80, 100, { timeStamp: 20 }).defaultPrevented, true);
    assert.equal(h.offset(), 40);
    h.detach();
  });
});
