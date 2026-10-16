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
// closed it. Turn the cancel into the touchend the viewer waits for, nudged
// one pixel so it never counts as a tap (a tap would zoom or close).
const OPEN_PORTAL = ".PhotoView-Portal:not([style*='visibility: hidden'])";

function finishCancelledGalleryTouch(event) {
  if (!document.querySelector(OPEN_PORTAL)) return;
  const last = event.changedTouches?.[0];
  if (!last) return;
  const x = last.clientX + 1;
  const y = last.clientY;
  let end;
  try {
    const touch = new Touch({ identifier: last.identifier, target: last.target, clientX: x, clientY: y, pageX: x, pageY: y, screenX: x, screenY: y });
    end = new TouchEvent("touchend", { changedTouches: [touch], cancelable: true });
  } catch {
    end = new Event("touchend", { cancelable: true });
    Object.defineProperty(end, "changedTouches", { value: [{ identifier: last.identifier, clientX: x, clientY: y }] });
  }
  window.dispatchEvent(end);
}

if (typeof window !== "undefined") {
  window.addEventListener("touchcancel", finishCancelledGalleryTouch, true);
}
