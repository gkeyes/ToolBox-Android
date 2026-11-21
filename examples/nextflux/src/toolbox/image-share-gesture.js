const HOLD_MS = 420;
const MOVE_LIMIT = 16;
const FOLLOW_UP_MS = 800;

function articleImage(target) {
  const image = target?.closest?.("img[data-nextflux-image-share]");
  if (!image?.isConnected || !image.complete || !image.naturalWidth) return null;
  if (!image.closest("[data-font-reading-root]") || image.closest(".nextflux-continuous-next-page")) return null;
  return image;
}

// One short-lived gesture per document, independent of the number of images.
// Observe movement without preventing native page scrolling.
export function installImageShareGesture(open) {
  let press = null;
  let finished = null;
  const listener = { capture: true, passive: true };

  function cancel() {
    if (!press) return;
    clearTimeout(press.timer);
    press.cancelled = true;
  }

  function end(cancelled = false) {
    if (!press) return;
    clearTimeout(press.timer);
    finished = { ...press, cancelled: press.cancelled || cancelled, time: performance.now() };
    press = null;
  }

  function start(target, kind, id, x, y) {
    end(true);
    finished = null;
    const image = articleImage(target);
    if (!image) return;
    const timer = setTimeout(() => {
      if (!press || press.cancelled || document.hidden || articleImage(press.image) !== press.image) return;
      press.opened = true;
      open(press.image);
    }, HOLD_MS);
    press = { image, kind, id, x, y, cancelled: false, opened: false, timer };
  }

  function move(x, y) {
    if (Math.hypot(x - press.x, y - press.y) > MOVE_LIMIT) cancel();
  }

  document.addEventListener("pointerdown", event => {
    if (!event.isPrimary) { cancel(); return; }
    if (event.pointerType === "mouse") { end(true); finished = null; return; }
    if (event.button === 0) start(event.target, "pointer", event.pointerId, event.clientX, event.clientY);
  }, listener);
  document.addEventListener("pointermove", event => {
    if (press?.kind === "pointer" && press.id === event.pointerId) move(event.clientX, event.clientY);
  }, listener);
  document.addEventListener("pointerup", event => {
    if (press?.kind === "pointer" && press.id === event.pointerId) end();
  }, listener);
  document.addEventListener("pointercancel", event => {
    if (press?.kind === "pointer" && press.id === event.pointerId) end(true);
  }, listener);

  // Some embedded WebViews deliver touch events without pointer events.
  document.addEventListener("touchstart", event => {
    if (event.touches.length !== 1) { cancel(); return; }
    if (press?.kind === "pointer") return;
    const touch = event.touches[0];
    start(event.target, "touch", touch.identifier, touch.clientX, touch.clientY);
  }, listener);
  const changedTouch = event => press?.kind === "touch" &&
    Array.from(event.changedTouches).find(touch => press.id === touch.identifier);
  document.addEventListener("touchmove", event => {
    const touch = changedTouch(event);
    if (touch) move(touch.clientX, touch.clientY);
  }, listener);
  document.addEventListener("touchend", event => { if (changedTouch(event)) end(); }, listener);
  document.addEventListener("touchcancel", event => { if (changedTouch(event)) end(true); }, listener);
  document.addEventListener("scroll", event => {
    if (press && event.target.contains?.(press.image)) cancel();
  }, listener);
  window.addEventListener("hashchange", () => { end(true); finished = null; });
  window.addEventListener("blur", () => end(true));

  const lastPress = () => press ??
    (finished && performance.now() - finished.time < FOLLOW_UP_MS ? finished : null);

  document.addEventListener("keydown", event => {
    if (event.key !== "ContextMenu" && !(event.key === "F10" && event.shiftKey)) return;
    const image = articleImage(event.target);
    if (!image) return;
    event.preventDefault();
    event.stopPropagation();
    open(image);
  }, true);

  return {
    consumedClick(event) {
      // The hold may have put a dialog button underneath the finger, which
      // retargets the release click even though it began on the article image.
      const previous = lastPress();
      if (event.detail === 0 || !previous?.opened ||
          (event.target !== previous.image && !event.target.closest?.(".toolbox-image-dialog"))) return false;
      event.preventDefault();
      event.stopPropagation();
      finished = null;
      return true;
    },
    consumedMenu(event) {
      const image = articleImage(event.target);
      if (!image) return false;
      event.preventDefault();
      event.stopPropagation();
      const last = lastPress();
      const previous = last?.image === image ? last : null;
      if (previous?.cancelled || previous?.opened) return true;
      if (previous) {
        clearTimeout(previous.timer);
        previous.opened = true;
      }
      open(image);
      return true;
    },
  };
}
