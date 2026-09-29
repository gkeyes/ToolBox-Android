const EXCLUDED_SELECTOR = [
  ".audio-player-slider", ".video-player", ".code-block", "iframe",
  "audio", "video", "canvas", "input", "textarea", "select", '[role="slider"]',
  '[contenteditable]:not([contenteditable="false"])',
  "dialog", '[role="dialog"]', '[aria-modal="true"]', ".PhotoView-Portal",
  "[data-swipe-exclude]",
].join(",");

export const SWIPE_DIRECTION_LOCK = 8;
export const SWIPE_HORIZONTAL_RATIO = 1.25;
const VELOCITY_WINDOW_MS = 80;

export function resolveSwipeDirection(deltaX, deltaY) {
  if (Math.abs(deltaX) < SWIPE_DIRECTION_LOCK && Math.abs(deltaY) < SWIPE_DIRECTION_LOCK) return null;
  return Math.abs(deltaX) > Math.abs(deltaY) * SWIPE_HORIZONTAL_RATIO ? "horizontal" : "vertical";
}

export function shouldExcludeSwipeTarget(target) {
  let element = target?.nodeType === 3 ? target.parentElement : target;
  while (element) {
    if (element.matches?.(EXCLUDED_SELECTOR)) return true;
    // Preserve nested horizontal scrolling (tables, carousels and custom controls).
    // Read layout only at touchstart, never on every touchmove.
    if (element.scrollWidth > element.clientWidth) {
      const style = element.ownerDocument?.defaultView?.getComputedStyle(element);
      if (style?.overflowX === "auto" || style?.overflowX === "scroll") return true;
    }
    element = element.parentElement;
  }
  return false;
}

// onStart runs only when horizontal ownership is acquired, and may return false
// to preserve native touch behavior. onMove/onEnd/onCancel receive px and px/s.
// Current options avoid React renders and document listener replacement.
export function attachSwipeGesture(document, { gestureRef, getOptions, isBlocked = () => false }) {
  let ignoreUntilEnd = false;
  const enabled = (event) => {
    const value = getOptions().enabled;
    return typeof value === "function" ? value(event) : value !== false;
  };
  const blocked = (event) => {
    const selection = document.getSelection?.();
    return event.defaultPrevented || !enabled(event) || isBlocked(event) ||
      Boolean(selection?.rangeCount && !selection.isCollapsed);
  };
  const updateSample = (gesture, touch, event) => {
    const last = gesture.samples.at(-1);
    const timeStamp = Math.max(last.timeStamp, Number(event.timeStamp) || 0);
    const travelDirection = Math.sign(touch.clientX - last.x);
    // A reversal must not inherit a fast forward swipe from earlier in the drag.
    if (travelDirection && gesture.travelDirection && travelDirection !== gesture.travelDirection) {
      gesture.samples = [last];
    }
    if (travelDirection) gesture.travelDirection = travelDirection;
    gesture.samples.push({ x: touch.clientX, y: touch.clientY, timeStamp });
    gesture.samples = gesture.samples.filter((sample) => sample.timeStamp >= timeStamp - VELOCITY_WINDOW_MS);
    gesture.x = touch.clientX;
    gesture.y = touch.clientY;
  };
  const detail = (gesture, event, reason) => {
    const first = gesture.samples[0];
    const last = gesture.samples.at(-1);
    const seconds = (last.timeStamp - first.timeStamp) / 1000;
    return {
      event, reason, startX: gesture.startX, startY: gesture.startY,
      x: gesture.x, y: gesture.y,
      deltaX: gesture.x - gesture.startX, deltaY: gesture.y - gesture.startY,
      velocityX: seconds > 0 ? (last.x - first.x) / seconds : 0,
      velocityY: seconds > 0 ? (last.y - first.y) / seconds : 0,
      timeStamp: last.timeStamp,
    };
  };
  const cancel = (event, reason) => {
    const gesture = gestureRef.current;
    gestureRef.current = null;
    if (gesture?.started) getOptions().onCancel?.(detail(gesture, event, reason));
  };

  const handleTouchStart = (event) => {
    // A second finger or an excluded new start invalidates the entire old gesture.
    cancel(event, "restart");
    if (event.touches.length > 1) ignoreUntilEnd = true;
    if (ignoreUntilEnd) return;
    if (event.touches.length !== 1 || blocked(event) || shouldExcludeSwipeTarget(event.target)) return;
    const touch = event.touches[0];
    gestureRef.current = {
      identifier: touch.identifier,
      startX: touch.clientX,
      startY: touch.clientY,
      x: touch.clientX,
      y: touch.clientY,
      direction: null,
      started: false,
      travelDirection: 0,
      samples: [{ x: touch.clientX, y: touch.clientY, timeStamp: Number(event.timeStamp) || 0 }],
    };
  };

  const handleTouchMove = (event) => {
    const gesture = gestureRef.current;
    if (!gesture) return;
    const touch = event.touches[0];
    if (event.touches.length !== 1 || touch?.identifier !== gesture.identifier || blocked(event)) {
      if (event.touches.length > 1) ignoreUntilEnd = true;
      cancel(event, "blocked");
      return;
    }
    if (gesture.direction === "vertical") return;
    updateSample(gesture, touch, event);
    if (gesture.direction === null) {
      gesture.direction = resolveSwipeDirection(
        touch.clientX - gesture.startX,
        touch.clientY - gesture.startY,
      );
    }
    if (gesture.direction !== "horizontal") return;
    // The browser may already own a scroll. Do not turn its final touchend into back.
    if (!event.cancelable) {
      cancel(event, "browser-scroll");
      return;
    }
    if (!gesture.started) {
      if (getOptions().onStart?.(detail(gesture, event)) === false) {
        cancel(event, "rejected");
        return;
      }
      gesture.started = true;
    }
    event.preventDefault();
    getOptions().onMove?.(detail(gesture, event));
  };

  const handleTouchEnd = (event) => {
    const gesture = gestureRef.current;
    if (!event.touches.length) ignoreUntilEnd = false;
    if (!gesture) return;
    if (event.touches.length || blocked(event)) {
      cancel(event, "blocked");
      return;
    }
    const touch = Array.from(event.changedTouches).find((item) => item.identifier === gesture.identifier);
    if (!touch) {
      cancel(event, "touch-identity");
      return;
    }
    updateSample(gesture, touch, event);
    gestureRef.current = null;
    if (!gesture.started || gesture.direction !== "horizontal") return;
    const { onSwipeRight, onEnd, threshold = 50 } = getOptions();
    onEnd?.(detail(gesture, event));
    if (touch.clientX - gesture.startX > threshold) onSwipeRight?.();
  };
  const handleTouchCancel = (event) => {
    ignoreUntilEnd = Boolean(event.touches.length);
    cancel(event, "touchcancel");
  };

  document.addEventListener("touchstart", handleTouchStart, { passive: true });
  document.addEventListener("touchmove", handleTouchMove, { passive: false });
  document.addEventListener("touchend", handleTouchEnd);
  document.addEventListener("touchcancel", handleTouchCancel);

  return () => {
    cancel(undefined, "detach");
    document.removeEventListener("touchstart", handleTouchStart);
    document.removeEventListener("touchmove", handleTouchMove);
    document.removeEventListener("touchend", handleTouchEnd);
    document.removeEventListener("touchcancel", handleTouchCancel);
  };
}
