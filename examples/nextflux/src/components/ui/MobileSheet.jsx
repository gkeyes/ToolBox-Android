import { startTransition, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Button, Drawer, cn } from "@heroui/react";
import { hadPointerDownFor } from "@/toolbox/touch-drag.js";

// Release motion: settle back on the sheet spring, dismiss on the 220ms
// accelerate curve (design system v2). Durations come from spring.mjs.
const SETTLE_EASING = "var(--ds-ease-sheet, cubic-bezier(0.32, 0.72, 0, 1))";
const FLICK_MIN_DISTANCE = 56;

function resolveEasing(element, easing) {
  if (!easing.startsWith("var(")) return easing;
  const name = easing.slice(4, easing.indexOf(","));
  const value = getComputedStyle(element).getPropertyValue(name).trim();
  return value || easing.slice(easing.indexOf(",") + 1, -1).trim();
}

function prefersReducedMotion() {
  return Boolean(window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches)
    || document.documentElement.dataset.reduceMotion === "true";
}

function animateTranslate(element, from, to, duration, onDone, easing = SETTLE_EASING) {
  // Only used to settle back to rest; dismissal uses HeroUI's exit transition.
  if (!element?.animate || prefersReducedMotion()) {
    if (element) {
      element.style.translate = "";
      element.style.transition = "";
    }
    onDone?.();
    return;
  }

  // Direct drag owns the inline translate. Once the finger releases, clear
  // that ownership and let a temporary compositor animation bridge from the
  // exact drag distance to the target. No fill is retained after completion,
  // so HeroUI can resume its own settled geometry without a stale subpixel.
  element.style.translate = "";
  const animation = element.animate(
    [{ translate: `0 ${from}px` }, { translate: `0 ${to}px` }],
    { duration, easing: resolveEasing(element, easing), fill: "none" },
  );
  let finished = false;
  animation.onfinish = () => {
    if (finished) return;
    finished = true;
    element.style.translate = "";
    element.style.transition = "";
    onDone?.();
  };
  animation.oncancel = () => {
    element.style.translate = "";
    element.style.transition = "";
  };
}

export default function MobileSheet({
  open,
  onOpenChange,
  title,
  closeLabel = "Close",
  fixedHeight = false,
  dialogClassName,
  bodyClassName,
  headerStart,
  // Painted at the end of the title row. A sheet that passes its own visible
  // close button here drops the hidden one, so there is a single close target.
  headerEnd,
  // A short action sheet can drop the visible title row too; it stays for
  // assistive tech. The built-in close button is never painted: sheets close
  // by dragging down, the backdrop, Escape or their own headerEnd button, and
  // the hidden button stays for screen readers.
  hideHeader = false,
  // See CustomModal: marks a sheet that refuses to close for system back.
  locked = false,
  children,
  footer,
}) {
  const dialogRef = useRef(null);
  const dragRef = useRef(null);
  // A drag dismissal parks the sheet off-screen before asking the owner to
  // close. The owner may refuse (a pending save or AI run), or reopen the
  // sheet while its exit is still running (React Aria then reuses the same
  // element). Either way the sheet must come back: an off-screen sheet over a
  // live backdrop looks like nothing is open while every tap is swallowed.
  const dismissRef = useRef(null);
  const [dismissCheck, setDismissCheck] = useState(0);

  useLayoutEffect(() => {
    const pending = dismissRef.current;
    if (!open || !pending) return;
    dismissRef.current = null;
    pending.animation?.cancel();
    const dialog = dialogRef.current;
    if (!dialog || dialog !== pending.dialog || !dialog.isConnected) return;
    dialog.style.transition = "";
    animateTranslate(dialog, pending.offscreen, 0, 440, () => { dialog.style.willChange = ""; });
  }, [open, dismissCheck]);

  // A drag is followed by pointer events, or by touch events on phones whose
  // WebView sends no pointer events for a touch (see touch-drag.js).
  const startDrag = (id, clientY, timeStamp) => {
    const dialog = dialogRef.current;
    if (!dialog) return false;
    dragRef.current = {
      id,
      startY: clientY,
      lastY: clientY,
      lastTime: timeStamp,
      velocity: 0,
      distance: 0,
    };
    // The finger owns the position: no CSS transition may lag behind it.
    dialog.style.transition = "none";
    dialog.style.willChange = "translate";
    return true;
  };

  const moveDrag = (id, clientY, timeStamp) => {
    const drag = dragRef.current;
    const dialog = dialogRef.current;
    if (!drag || drag.id !== id || !dialog) return false;
    const distance = Math.max(0, clientY - drag.startY);
    const dt = Math.max(1, timeStamp - drag.lastTime);
    drag.velocity = ((clientY - drag.lastY) / dt) * 1000;
    drag.lastY = clientY;
    drag.lastTime = timeStamp;
    drag.distance = distance;
    dialog.style.translate = `0 ${distance}px`;
    return true;
  };

  // Buttons in the title row (back, close) keep their taps; a drag starts
  // anywhere else in the handle or title row.
  const startsOnControl = (target) => Boolean(target?.closest?.("button, a, input, [role='button']"));

  const onPointerDown = (event) => {
    if (event.button !== 0 || startsOnControl(event.target)) return;
    if (startDrag(`p${event.pointerId}`, event.clientY, event.timeStamp)) {
      event.currentTarget.setPointerCapture?.(event.pointerId);
    }
  };

  const onPointerMove = (event) => {
    if (moveDrag(`p${event.pointerId}`, event.clientY, event.timeStamp)) event.preventDefault();
  };

  const onPointerEnd = (event, cancelled = false) => {
    if (dragRef.current?.id !== `p${event.pointerId}`) return;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    finishDrag(`p${event.pointerId}`, cancelled);
  };

  function finishDrag(id, cancelled = false) {
    const drag = dragRef.current;
    const dialog = dialogRef.current;
    if (!drag || drag.id !== id || !dialog) return;
    dragRef.current = null;
    const threshold = Math.max(FLICK_MIN_DISTANCE, Math.min(140, dialog.getBoundingClientRect().height * 0.22));
    // A flick dismisses only after a deliberate travel; tiny fast drags settle back.
    const dismiss = !cancelled && (drag.distance >= threshold || (drag.velocity >= 720 && drag.distance >= FLICK_MIN_DISTANCE));
    const from = drag.distance;
    if (dismiss) {
      // Finish the slide ourselves from the finger position, then close with
      // the sheet already off-screen. Closing must never depend on a CSS
      // transition firing after inline style changes: if the overlay's exit
      // never completes, its backdrop stays mounted and swallows every tap.
      // Floating sheets sit above a bottom margin; travel past that too.
      const offscreen = dialog.getBoundingClientRect().height + 32 + (parseFloat(getComputedStyle(dialog).marginBottom) || 0);
      const pending = { dialog, offscreen, animation: null };
      dismissRef.current = pending;
      const close = () => {
        if (dismissRef.current !== pending) return;
        dialog.style.willChange = "";
        onOpenChange(false);
        // Re-check after the owner's update has rendered (a transition renders
        // after ordinary and store updates). Still open means it refused.
        startTransition(() => setDismissCheck((value) => value + 1));
      };
      if (!dialog.animate || prefersReducedMotion()) {
        dialog.style.transition = "";
        dialog.style.translate = `0 ${offscreen}px`;
        close();
        return;
      }
      dialog.style.translate = "";
      dialog.style.transition = "";
      const animation = dialog.animate(
        [{ translate: `0 ${from}px` }, { translate: `0 ${offscreen}px` }],
        { duration: 220, easing: "cubic-bezier(0.3, 0, 1, 1)", fill: "forwards" },
      );
      pending.animation = animation;
      let done = false;
      const finish = () => { if (!done) { done = true; close(); } };
      animation.onfinish = finish;
      animation.oncancel = finish;
      // Watchdog: a throttled WebView may never deliver onfinish.
      setTimeout(finish, 400);
    } else {
      animateTranslate(dialog, from, 0, 440, () => { dialog.style.willChange = ""; });
    }
  }

  // Touch fallback for the handle and the title row. Native listeners: the
  // move handler must be able to cancel scrolling, which React's passive
  // touch listeners cannot.
  const handlers = useRef({});
  handlers.current = { startDrag, moveDrag, finishDrag, startsOnControl };
  const [handleZone, setHandleZone] = useState(null);
  const [headerZone, setHeaderZone] = useState(null);
  useEffect(() => {
    const zones = [handleZone, headerZone].filter(Boolean);
    if (!zones.length) return undefined;
    let touchId = null;
    const find = (list) => Array.from(list).find((touch) => `t${touch.identifier}` === touchId);
    const onStart = (event) => {
      if (touchId || event.touches.length !== 1 || hadPointerDownFor(event) || handlers.current.startsOnControl(event.target)) return;
      const touch = event.touches[0];
      if (handlers.current.startDrag(`t${touch.identifier}`, touch.clientY, event.timeStamp)) touchId = `t${touch.identifier}`;
    };
    const onMove = (event) => {
      const touch = touchId && find(event.changedTouches);
      if (!touch) return;
      if (handlers.current.moveDrag(touchId, touch.clientY, event.timeStamp) && event.cancelable) event.preventDefault();
    };
    const onEnd = (event) => {
      if (!touchId || !find(event.changedTouches)) return;
      const id = touchId;
      touchId = null;
      handlers.current.finishDrag(id, event.type === "touchcancel");
    };
    for (const zone of zones) {
      zone.addEventListener("touchstart", onStart, { passive: true });
      zone.addEventListener("touchmove", onMove, { passive: false });
      zone.addEventListener("touchend", onEnd);
      zone.addEventListener("touchcancel", onEnd);
    }
    return () => {
      for (const zone of zones) {
        zone.removeEventListener("touchstart", onStart);
        zone.removeEventListener("touchmove", onMove);
        zone.removeEventListener("touchend", onEnd);
        zone.removeEventListener("touchcancel", onEnd);
      }
    };
  }, [handleZone, headerZone]);

  return (
    <Drawer>
      <Button className="hidden" />
      <Drawer.Backdrop className="nextflux-modal-backdrop" isOpen={open} onOpenChange={onOpenChange}>
        <Drawer.Content>
          <Drawer.Dialog
            ref={dialogRef}
            data-nf-locked={locked || undefined}
            className={cn(
              "nextflux-modal-surface nextflux-mobile-sheet nextflux-floating-sheet p-0",
              fixedHeight && "h-4/5",
              dialogClassName,
            )}
          >
            <div
              ref={setHandleZone}
              className="nextflux-sheet-handle-zone"
              onPointerDown={onPointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={(event) => onPointerEnd(event)}
              onPointerCancel={(event) => onPointerEnd(event, true)}
              onLostPointerCapture={(event) => onPointerEnd(event, true)}
            >
              <Drawer.Handle className="p-1" />
            </div>
            {!headerEnd && <Drawer.CloseTrigger className="nextflux-close-button sr-only" aria-label={closeLabel} />}
            <Drawer.Header
              ref={setHeaderZone}
              onPointerDown={onPointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={(event) => onPointerEnd(event)}
              onPointerCancel={(event) => onPointerEnd(event, true)}
              onLostPointerCapture={(event) => onPointerEnd(event, true)}
              className={cn("nextflux-close-header px-4 pt-0 pb-1 flex flex-row items-center gap-2", headerEnd && "has-header-end", hideHeader && "sr-only")}
            >
              {headerStart}
              <Drawer.Heading className={cn(headerEnd && "min-w-0 flex-1")}>{title}</Drawer.Heading>
              {headerEnd}
            </Drawer.Header>
            <Drawer.Body className={cn("nextflux-modal-body m-0 p-0", bodyClassName)}>{children}</Drawer.Body>
            {footer && <Drawer.Footer className="nextflux-modal-footer p-4 m-0">{footer}</Drawer.Footer>}
          </Drawer.Dialog>
        </Drawer.Content>
      </Drawer.Backdrop>
    </Drawer>
  );
}
