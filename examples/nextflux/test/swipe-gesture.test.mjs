import assert from "node:assert/strict";
import test from "node:test";
import { attachSwipeGesture, resolveSwipeDirection, shouldExcludeSwipeTarget } from "../src/hooks/swipeGesture.js";

const point = (clientX, clientY, identifier = 0) => ({ clientX, clientY, identifier });

function harness() {
  const document = new EventTarget();
  const gestureRef = { current: null };
  let selected = false;
  let blocked = false;
  let calls = 0;
  let options = { onSwipeRight: () => { calls += 1; } };
  document.getSelection = () => ({ rangeCount: selected ? 1 : 0, isCollapsed: !selected });
  const detach = attachSwipeGesture(document, {
    gestureRef,
    getOptions: () => options,
    isBlocked: () => blocked,
  });
  const dispatch = (type, touches, changedTouches = [], extra = {}) => {
    const event = new Event(type, { cancelable: extra.cancelable ?? true });
    Object.assign(event, { touches, changedTouches });
    if (extra.target) Object.defineProperty(event, "target", { value: extra.target });
    if (extra.prevented) event.preventDefault();
    document.dispatchEvent(event);
    return event;
  };
  return {
    gestureRef, detach, dispatch,
    start: (x = 0, y = 0, extra) => dispatch("touchstart", [point(x, y)], [], extra),
    move: (x, y, extra) => dispatch("touchmove", [point(x, y)], [], extra),
    end: (x, y, extra) => dispatch("touchend", [], [point(x, y)], extra),
    select: (value) => { selected = value; },
    block: (value) => { blocked = value; },
    setOptions: (value) => { options = value; },
    calls: () => calls,
  };
}

test("jitter stays undecided until 8 px; diagonal movement favors scrolling", () => {
  assert.equal(resolveSwipeDirection(7, -7), null);
  assert.equal(resolveSwipeDirection(8, 0), "horizontal");
  assert.equal(resolveSwipeDirection(9, 8), "vertical");
  assert.equal(resolveSwipeDirection(12, 10), "vertical");
  assert.equal(resolveSwipeDirection(13, 10), "horizontal");
});

test("zero coordinates work and the direction-lock event itself stops scrolling", () => {
  const h = harness();
  h.start(0, 0);
  assert.equal(h.move(3, 2).defaultPrevented, false);
  assert.equal(h.move(8, 1).defaultPrevented, true);
  assert.equal(h.move(65, 3).defaultPrevented, true);
  h.end(65, 3);
  h.end(65, 3);
  assert.equal(h.calls(), 1);
  assert.equal(h.gestureRef.current, null);
  h.detach();
});

test("once vertical, even a large later right drift cannot become back", () => {
  const h = harness();
  h.start(20, 20);
  assert.equal(h.move(21, 28).defaultPrevented, false);
  assert.equal(h.move(150, 30).defaultPrevented, false);
  h.end(150, 30);
  assert.equal(h.calls(), 0);
  h.detach();
});

test("horizontal lock survives later vertical drift without a second direction decision", () => {
  const h = harness();
  h.start();
  h.move(9, 0);
  assert.equal(h.move(70, 90).defaultPrevented, true);
  h.end(70, 90);
  assert.equal(h.calls(), 1);
  h.detach();
});

test("taps, release-only movement, left swipes and a drag ending at threshold do not go back", () => {
  for (const scenario of [[], [[60, 0, "end"]], [[-70, 0]], [[50, 0]], [[70, 0], [40, 0]]]) {
    const h = harness();
    h.start();
    for (const [x, y, kind] of scenario) {
      if (kind === "end") h.end(x, y);
      else h.move(x, y);
    }
    const [x = 0, y = 0] = scenario.at(-1) ?? [];
    h.end(x, y);
    assert.equal(h.calls(), 0);
    h.detach();
  }
});

test("touchcancel drops an active swipe and the next gesture starts cleanly", () => {
  const h = harness();
  h.start();
  h.move(80, 0);
  h.dispatch("touchcancel", [], [point(80, 0)]);
  h.end(80, 0);
  assert.equal(h.calls(), 0);
  h.start();
  h.move(80, 0);
  h.end(80, 0);
  assert.equal(h.calls(), 1);
  h.detach();
});

test("a second finger cancels until all fingers lift, even when the first finger remains", () => {
  for (const type of ["touchstart", "touchmove"]) {
    const h = harness();
    h.start();
    h.move(60, 0);
    h.dispatch(type, [point(60, 0), point(80, 20, 1)]);
    h.dispatch("touchend", [point(60, 0)], [point(80, 20, 1)]);
    assert.equal(h.move(100, 0).defaultPrevented, false);
    h.end(100, 0);
    assert.equal(h.calls(), 0);
    h.detach();
  }
});

test("touch identity changes and a different finger's release cannot complete a swipe", () => {
  for (const type of ["touchmove", "touchend"]) {
    const h = harness();
    h.start();
    h.move(60, 0);
    h.dispatch(type, type === "touchmove" ? [point(100, 0, 1)] : [], [point(100, 0, 1)]);
    h.end(100, 0);
    assert.equal(h.calls(), 0);
    h.detach();
  }
});

test("an excluded start clears any old active gesture", () => {
  const h = harness();
  h.start();
  h.move(70, 0);
  const target = { parentElement: { matches: () => true } };
  h.start(10, 0, { target });
  assert.equal(h.move(90, 0).defaultPrevented, false);
  h.end(90, 0);
  assert.equal(h.calls(), 0);
  h.detach();
});

test("nested horizontal scrollers are excluded; clipped or vertical content is not", () => {
  const scroller = {
    scrollWidth: 600, clientWidth: 300,
    ownerDocument: { defaultView: { getComputedStyle: () => ({ overflowX: "auto" }) } },
  };
  assert.equal(shouldExcludeSwipeTarget({ nodeType: 3, parentElement: scroller }), true);
  scroller.ownerDocument.defaultView.getComputedStyle = () => ({ overflowX: "hidden" });
  assert.equal(shouldExcludeSwipeTarget({ parentElement: scroller }), false);
  scroller.scrollWidth = 300;
  assert.equal(shouldExcludeSwipeTarget({ parentElement: scroller }), false);
});

test("selection and modal/gallery blockers cancel at start, move and release", () => {
  for (const blocker of ["select", "block"]) {
    for (const phase of ["start", "move", "end"]) {
      const h = harness();
      if (phase === "start") h[blocker](true);
      h.start();
      if (phase === "move") h[blocker](true);
      const move = h.move(80, 0);
      if (phase !== "end") assert.equal(move.defaultPrevented, false);
      if (phase === "end") h[blocker](true);
      h.end(80, 0);
      assert.equal(h.calls(), 0);
      h[blocker](false);
      h.end(90, 0);
      assert.equal(h.calls(), 0);
      h.detach();
    }
  }
});

test("browser-owned scroll and events already handled by a control cancel navigation", () => {
  for (const extra of [{ cancelable: false }, { prevented: true }]) {
    const h = harness();
    h.start();
    h.move(80, 0, extra);
    h.end(80, 0);
    assert.equal(h.calls(), 0);
    h.detach();
  }
});

test("current callback and threshold apply without listener rebinding; teardown removes handlers", () => {
  const h = harness();
  let currentCalls = 0;
  h.start();
  h.move(60, 0);
  h.setOptions({ threshold: 75, onSwipeRight: () => { currentCalls += 1; } });
  h.end(60, 0);
  h.start();
  h.move(80, 0);
  h.end(80, 0);
  assert.equal(h.calls(), 0);
  assert.equal(currentCalls, 1);
  h.detach();
  h.start();
  assert.equal(h.move(90, 0).defaultPrevented, false);
  h.end(90, 0);
  assert.equal(currentCalls, 1);
  assert.equal(h.gestureRef.current, null);
});
