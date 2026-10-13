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
// A tap's click follows its touchstart closely; older touches are not this tap.
const GESTURE_MS = 1000;
// pointerdown normally fires just before touchstart for the same contact.
const POINTER_LEAD_MS = 300;

const isDisabled = (element) =>
  element.matches(":disabled") ||
  element.getAttribute("aria-disabled") === "true" ||
  element.hasAttribute("data-disabled");

export function installTouchPressCompat(target = window) {
  let touchAt = -Infinity;
  let pointerAt = -Infinity;

  const onPointerDown = (event) => { pointerAt = event.timeStamp; };
  const onTouchStart = (event) => { touchAt = event.timeStamp; };
  const onTouchCancel = () => { touchAt = -Infinity; };
  const onClick = (event) => {
    // Untrusted (programmatic) and keyboard/virtual clicks already work.
    if (!event.isTrusted || event.detail === 0) return;
    const fromTouch = event.pointerType === "touch" || event.timeStamp - touchAt < GESTURE_MS;
    const gestureStart = Number.isFinite(touchAt) ? touchAt : event.timeStamp - GESTURE_MS;
    const hadPointerDown = pointerAt >= gestureStart - POINTER_LEAD_MS;
    // Each tap is judged once; a cancelled or finished gesture leaves nothing behind.
    touchAt = -Infinity;
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
  target.addEventListener("touchcancel", onTouchCancel, capture);
  target.addEventListener("click", onClick, { capture: true });
  return () => {
    target.removeEventListener("pointerdown", onPointerDown, capture);
    target.removeEventListener("touchstart", onTouchStart, capture);
    target.removeEventListener("touchcancel", onTouchCancel, capture);
    target.removeEventListener("click", onClick, { capture: true });
  };
}
