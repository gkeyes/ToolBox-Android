// Touch drags that arrive without pointer events.
//
// Some phones deliver a touch to the WebView as touch events only (see
// touch-press.js). React Aria's slider and the sheet's drag-to-dismiss both
// follow pointer events, so on those phones a slider cannot be dragged and a
// sheet cannot be pulled down. These helpers follow the touch itself, and only
// when no pointerdown came for it, so normal touch, mouse and keyboard keep
// their own path.

import { useEffect, useRef, useState } from "react";

// pointerdown normally fires just before touchstart for the same contact.
const POINTER_LEAD_MS = 300;

let lastPointerDown = -Infinity;
if (typeof window !== "undefined") {
  window.addEventListener("pointerdown", (event) => { lastPointerDown = event.timeStamp; }, { capture: true, passive: true });
}

export const hadPointerDownFor = (event) => event.timeStamp - lastPointerDown < POINTER_LEAD_MS;

export function sliderValueAt(clientX, rect, { min, max, step }) {
  if (!rect.width) return min;
  const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
  const raw = min + ratio * (max - min);
  const snapped = step > 0 ? min + Math.round((raw - min) / step) * step : raw;
  const decimals = String(step).split(".")[1]?.length ?? 0;
  return Math.min(max, Math.max(min, Number(snapped.toFixed(decimals))));
}

// Drives the HeroUI/React Aria slider inside the element given the returned
// callback ref from touch events.
export function useTouchSliderFallback({ min, max, step = 1, disabled = false, onChange, onChangeEnd }) {
  const [container, setContainer] = useState(null);
  const latest = useRef({});
  latest.current = { min, max, step, disabled, onChange, onChangeEnd };
  useEffect(() => {
    const track = container?.querySelector(".slider__track");
    if (!track) return undefined;
    let active = null;
    const valueFor = (touch) => sliderValueAt(touch.clientX, track.getBoundingClientRect(), latest.current);
    const onStart = (event) => {
      if (latest.current.disabled || event.touches.length !== 1 || hadPointerDownFor(event)) return;
      const touch = event.touches[0];
      active = { id: touch.identifier, value: valueFor(touch) };
      latest.current.onChange?.(active.value);
    };
    const find = (list) => (active ? Array.from(list).find((touch) => touch.identifier === active.id) : null);
    const onMove = (event) => {
      const touch = find(event.changedTouches);
      if (!touch) return;
      if (event.cancelable) event.preventDefault();
      active.value = valueFor(touch);
      latest.current.onChange?.(active.value);
    };
    const onEnd = (event) => {
      if (!find(event.changedTouches)) return;
      const { value } = active;
      active = null;
      latest.current.onChangeEnd?.(value);
    };
    track.addEventListener("touchstart", onStart, { passive: true });
    track.addEventListener("touchmove", onMove, { passive: false });
    track.addEventListener("touchend", onEnd);
    track.addEventListener("touchcancel", onEnd);
    return () => {
      track.removeEventListener("touchstart", onStart);
      track.removeEventListener("touchmove", onMove);
      track.removeEventListener("touchend", onEnd);
      track.removeEventListener("touchcancel", onEnd);
    };
  }, [container]);
  return setContainer;
}
