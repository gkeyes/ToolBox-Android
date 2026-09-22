const EXCLUDED_SELECTOR = [
  ".audio-player-slider", ".video-player", ".code-block", "iframe",
  "audio", "video", "input", "textarea", "select", '[role="slider"]',
  '[contenteditable]:not([contenteditable="false"])',
  "dialog", '[role="dialog"]', '[aria-modal="true"]', ".PhotoView-Portal",
].join(",");

export function resolveSwipeDirection(deltaX, deltaY) {
  if (Math.abs(deltaX) < 8 && Math.abs(deltaY) < 8) return null;
  return Math.abs(deltaX) > Math.abs(deltaY) * 1.2 ? "horizontal" : "vertical";
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

// The supplied refs and current callbacks let moves avoid React renders and
// document listener replacement. This module needs no React or app-store runtime.
export function attachSwipeGesture(document, { gestureRef, getOptions, isBlocked = () => false }) {
  const reset = () => { gestureRef.current = null; };
  const blocked = (event) => {
    const selection = document.getSelection?.();
    return event.defaultPrevented || isBlocked() ||
      Boolean(selection?.rangeCount && !selection.isCollapsed);
  };

  const handleTouchStart = (event) => {
    // A second finger or an excluded new start invalidates the entire old gesture.
    reset();
    if (event.touches.length !== 1 || blocked(event) || shouldExcludeSwipeTarget(event.target)) return;
    const touch = event.touches[0];
    gestureRef.current = {
      identifier: touch.identifier,
      startX: touch.clientX,
      startY: touch.clientY,
      direction: null,
    };
  };

  const handleTouchMove = (event) => {
    const gesture = gestureRef.current;
    if (!gesture) return;
    const touch = event.touches[0];
    if (event.touches.length !== 1 || touch.identifier !== gesture.identifier || blocked(event)) {
      reset();
      return;
    }
    if (gesture.direction === null) {
      gesture.direction = resolveSwipeDirection(
        touch.clientX - gesture.startX,
        touch.clientY - gesture.startY,
      );
    }
    if (gesture.direction !== "horizontal") return;
    // The browser may already own a scroll. Do not turn its final touchend into back.
    if (!event.cancelable) {
      reset();
      return;
    }
    event.preventDefault();
  };

  const handleTouchEnd = (event) => {
    const gesture = gestureRef.current;
    reset();
    if (!gesture || event.touches.length || blocked(event)) return;
    const touch = Array.from(event.changedTouches).find((item) => item.identifier === gesture.identifier);
    if (!touch || gesture.direction !== "horizontal") return;
    const { onSwipeRight, threshold = 50 } = getOptions();
    if (touch.clientX - gesture.startX > threshold) onSwipeRight?.();
  };

  document.addEventListener("touchstart", handleTouchStart, { passive: true });
  document.addEventListener("touchmove", handleTouchMove, { passive: false });
  document.addEventListener("touchend", handleTouchEnd);
  document.addEventListener("touchcancel", reset);

  return () => {
    reset();
    document.removeEventListener("touchstart", handleTouchStart);
    document.removeEventListener("touchmove", handleTouchMove);
    document.removeEventListener("touchend", handleTouchEnd);
    document.removeEventListener("touchcancel", reset);
  };
}
