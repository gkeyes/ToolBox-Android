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
