// react-photo-view removes its full-screen portal only when the backdrop's
// fade-out fires animationend. If that event is lost (WebView paused the
// animation, the page moved mid-close, ...), an invisible full-screen layer
// stays on top and swallows every tap and the back gesture. After the close
// animation should have finished, deliver the missing event; if the layer is
// somehow still there, make it inert to input and invisible.
const GALLERY_CLOSE_MS = 300;
export function releaseStuckGallery() {
  window.setTimeout(() => {
    const backdrops = document.querySelectorAll(".PhotoView-Portal .PhotoView-Slider__Backdrop");
    backdrops.forEach((backdrop) => {
      const event = typeof AnimationEvent === "function"
        ? new AnimationEvent("animationend", { bubbles: true, animationName: "PhotoView__fade" })
        : new Event("animationend", { bubbles: true });
      backdrop.dispatchEvent(event);
    });
    window.setTimeout(() => {
      document.querySelectorAll(".PhotoView-Portal").forEach((portal) => {
        portal.style.pointerEvents = "none";
        portal.style.visibility = "hidden";
      });
      if (!document.querySelector(".PhotoView-Portal:not([style*='visibility: hidden'])")) {
        document.body.style.removeProperty("overflow");
      }
    }, 250);
  }, GALLERY_CLOSE_MS + 400);
}

