// react-photo-view removes its full-screen portal only when the backdrop's
// fade-out fires animationend. If that event is lost (WebView paused the
// animation, the page moved mid-close, ...), an invisible full-screen layer
// stays on top and swallows every tap and the back gesture. After the close
// animation should have finished, deliver the missing event; if the layer is
// somehow still there, make it inert to input and invisible.
//
// Only a layer that is still closing (its backdrop carries the fade-out class)
// is touched. Opening the gallery again cancels a pending release, and it never
// runs while the gallery is open: reopening an image within a second used to
// hide the new viewer and leave the reader locked.
const GALLERY_CLOSE_MS = 300;
const CLOSING_BACKDROP = ".PhotoView-Slider__Backdrop.PhotoView-Slider__fadeOut";
let generation = 0;

export function cancelGalleryRelease() {
  generation += 1;
}

export function releaseStuckGallery(isOpen = () => false) {
  const id = ++generation;
  const current = () => id === generation && !isOpen();
  window.setTimeout(() => {
    if (!current()) return;
    document.querySelectorAll(CLOSING_BACKDROP).forEach((backdrop) => {
      const event = typeof AnimationEvent === "function"
        ? new AnimationEvent("animationend", { bubbles: true, animationName: "PhotoView__fade" })
        : new Event("animationend", { bubbles: true });
      backdrop.dispatchEvent(event);
    });
    window.setTimeout(() => {
      if (!current()) return;
      document.querySelectorAll(CLOSING_BACKDROP).forEach((backdrop) => {
        const portal = backdrop.closest(".PhotoView-Portal");
        if (!portal) return;
        portal.style.pointerEvents = "none";
        portal.style.visibility = "hidden";
      });
      if (!document.querySelector(".PhotoView-Portal:not([style*='visibility: hidden'])")) {
        document.body.style.removeProperty("overflow");
      }
    }, 250);
  }, GALLERY_CLOSE_MS + 400);
}

// react-photo-view finishes a drag only on touchend; it ignores touchcancel.
// The system cancels the touch when a screenshot gesture (three fingers, or
// the region-select overlay) takes over, so the viewer kept the half-finished
// gesture: swipes stayed locked to the old direction and a pull no longer
// closed it. The page can also be hidden or lose focus mid-drag with no
// cancel at all. In each case give the viewer the touchend it waits for, at
// the point its drag is measured from, so a cancelled pull or swipe snaps back
// instead of closing the viewer or changing the image. The viewer measures a
// drag from the first move more than 20px from where the touch started (its
// direction lock), not from the start itself; until such a move, the start.
// The release is nudged one pixel so it never counts as a tap (a tap would
// zoom or close).
const OPEN_PORTAL = ".PhotoView-Portal:not([style*='visibility: hidden'])";
const LOCK_DISTANCE = 20;
let touchStart = null;

// Every one-finger touch starts a fresh record, so a touchend that never
// arrived cannot leave an old start behind for the next gesture.
function rememberTouchStart(event) {
  const first = event.touches?.[0];
  if (!first) return;
  if (event.touches.length === 1 || !touchStart) {
    touchStart = { identifier: first.identifier, target: first.target, x: first.clientX, y: first.clientY, anchor: null };
  }
}

function rememberDragAnchor(event) {
  if (!touchStart || touchStart.anchor) return;
  const touch = [...(event.touches || [])].find((item) => item.identifier === touchStart.identifier);
  if (!touch) return;
  if (Math.abs(touch.clientX - touchStart.x) > LOCK_DISTANCE || Math.abs(touch.clientY - touchStart.y) > LOCK_DISTANCE) {
    touchStart.anchor = { x: touch.clientX, y: touch.clientY };
  }
}

function forgetTouchStart(event) {
  if (!event.touches?.length) touchStart = null;
}

function releaseGalleryTouch() {
  const start = touchStart;
  touchStart = null;
  if (!start || !document.querySelector(OPEN_PORTAL)) return;
  const from = start.anchor || start;
  const x = from.x + 1;
  const y = from.y;
  let end;
  try {
    const touch = new Touch({ identifier: start.identifier, target: start.target, clientX: x, clientY: y, pageX: x, pageY: y, screenX: x, screenY: y });
    end = new TouchEvent("touchend", { changedTouches: [touch], cancelable: true });
  } catch {
    end = new Event("touchend", { cancelable: true });
    Object.defineProperty(end, "changedTouches", { value: [{ identifier: start.identifier, clientX: x, clientY: y }] });
  }
  window.dispatchEvent(end);
}

if (typeof window !== "undefined") {
  window.addEventListener("touchstart", rememberTouchStart, true);
  window.addEventListener("touchmove", rememberDragAnchor, { capture: true, passive: true });
  window.addEventListener("touchend", forgetTouchStart, true);
  window.addEventListener("touchcancel", releaseGalleryTouch, true);
  window.addEventListener("blur", releaseGalleryTouch);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") releaseGalleryTouch();
  });
}
