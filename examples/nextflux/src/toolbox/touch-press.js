// Touch taps that arrive without pointer events.
//
// On some phones a real tap reaches the WebView as touchstart → touchend →
// click with no pointerdown/pointerup. React Aria's usePress tracks presses
// through pointer events and only accepts a lone click when it looks virtual
// (detail 0), so such a tap is silently ignored: "More", "Close" and every other
// onPress button stop responding. A programmatic click is treated as virtual and
// works, so for exactly that case we swallow the real click and replay it as
// HTMLElement#click(). Normal touch (with pointer events), mouse, keyboard and
// programmatic clicks keep their own path untouched.

// usePress stamps every element it manages with data-react-aria-pressable;
// data-nextflux-press opts in any other control that needs the same handling.
const PRESSABLE = '[data-react-aria-pressable="true"], [data-nextflux-press]';
// A tap's click follows its touchend closely; older touches are not this tap.
// (Measured from the release, so a slow press still counts.)
const GESTURE_MS = 1000;
// pointerdown normally fires just before touchstart for the same contact.
const POINTER_LEAD_MS = 300;

const isDisabled = (element) =>
  element.matches(":disabled") ||
  element.getAttribute("aria-disabled") === "true" ||
  element.hasAttribute("data-disabled");

export function installTouchPressCompat(target = window) {
  let touchAt = -Infinity;
  let touchEndAt = -Infinity;
  let pointerAt = -Infinity;

  const onPointerDown = (event) => { pointerAt = event.timeStamp; };
  const onTouchStart = (event) => { touchAt = event.timeStamp; };
  const onTouchEnd = (event) => { touchEndAt = event.timeStamp; };
  const forget = () => { touchAt = -Infinity; touchEndAt = -Infinity; pointerAt = -Infinity; };
  const onClick = (event) => {
    // Untrusted (programmatic) and keyboard/virtual clicks already work.
    if (!event.isTrusted || event.detail === 0) return;
    const fromTouch = event.pointerType === "touch" || event.timeStamp - touchEndAt < GESTURE_MS;
    const gestureStart = Number.isFinite(touchAt) ? touchAt : event.timeStamp - GESTURE_MS;
    const hadPointerDown = pointerAt >= gestureStart - POINTER_LEAD_MS;
    // Each tap is judged once; a finished or cancelled gesture leaves nothing
    // behind, so the next tap never borrows this one's pointerdown.
    forget();
    if (!fromTouch || hadPointerDown) return;
    const element = event.target instanceof Element ? event.target.closest(PRESSABLE) : null;
    if (!element || isDisabled(element)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    HTMLElement.prototype.click.call(element);
  };

  const capture = { capture: true, passive: true };
  target.addEventListener("pointerdown", onPointerDown, capture);
  target.addEventListener("touchstart", onTouchStart, capture);
  target.addEventListener("touchend", onTouchEnd, capture);
  target.addEventListener("touchcancel", forget, capture);
  target.addEventListener("pointercancel", forget, capture);
  target.addEventListener("click", onClick, { capture: true });
  return () => {
    target.removeEventListener("pointerdown", onPointerDown, capture);
    target.removeEventListener("touchstart", onTouchStart, capture);
    target.removeEventListener("touchend", onTouchEnd, capture);
    target.removeEventListener("touchcancel", forget, capture);
    target.removeEventListener("pointercancel", forget, capture);
    target.removeEventListener("click", onClick, { capture: true });
  };
}
