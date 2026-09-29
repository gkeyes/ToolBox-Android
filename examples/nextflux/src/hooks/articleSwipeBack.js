import { attachSwipeGesture } from "./swipeGesture.js";
import {
  NAVIGATION_STATES,
  animateNavigationSettle,
  applyNavigationVisual,
  clearNavigationVisual,
  restNavigationVisual,
} from "../motion/navigationTransition.mjs";

const SYSTEM_EDGE_PX = 10;

export function shouldFinishArticleSwipe(distance, width, velocity) {
  if (distance <= 0 || width <= 0) return false;
  if (distance >= width * 0.10) return true;
  if (velocity < -120) return false;
  return distance >= 24 && velocity >= 150;
}

function translateX(value) {
  if (!value || value === "none") return 0;
  const translate = value.match(/^translate3d\(\s*(-?\d+(?:\.\d+)?)px\s*,\s*0(?:px)?\s*,\s*0(?:px)?\s*\)$/);
  if (translate) return Number(translate[1]);
  const matrix = value.match(/^matrix(3d)?\(([^)]+)\)$/);
  if (!matrix) return null;
  const entries = matrix[2].split(",").map(Number);
  if (entries.some((entry) => !Number.isFinite(entry))) return null;
  return matrix[1] ? entries[12] : entries[4];
}

function isRestingTransform(value) {
  if (!value || value === "none") return true;
  if (/^translate(?:3d|Z)\(\s*0(?:px)?(?:\s*,\s*0(?:px)?){0,2}\s*\)$/.test(value)) return true;
  const match = value.match(/^matrix(3d)?\(([^)]+)\)$/);
  if (!match) return false;
  const entries = match[2].split(",").map(Number);
  const identity = match[1] ? [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1] : [1, 0, 0, 1, 0, 0];
  return entries.length === identity.length && entries.every((entry, index) => entry === identity[index]);
}

export function attachArticleSwipeBack(page, { getOptions, isBlocked = () => false }) {
  const document = page.ownerDocument;
  const view = document.defaultView;
  const gestureRef = { current: null };
  let phase = "idle";
  let width = 0;
  let offset = 0;
  let startOffset = 0;
  let saved = null;
  let ownedStyles = null;
  let cancelMotion = null;
  let motionId = 0;
  let disposed = false;
  let active = false;
  let suppressClickUntil = 0;

  const machine = () => getOptions().transitionMachine;
  const moveState = (next) => {
    const controller = machine();
    if (!controller) return;
    if (!controller.transition(next)) controller.reset(next);
  };
  const enabled = () => {
    const value = getOptions().enabled;
    return typeof value === "function" ? value() : value !== false;
  };
  const unavailable = () => {
    const selection = document.getSelection?.();
    return !enabled() || isBlocked() || Boolean(selection?.rangeCount && !selection.isCollapsed);
  };
  const currentWidth = () => Math.max(0, page.clientWidth || view?.innerWidth || 0);
  const setOffset = (value) => {
    offset = value;
    applyNavigationVisual(page, value, width || currentWidth());
    if (ownedStyles) ownedStyles.transform = page.style.transform;
  };
  const setActive = (value) => {
    if (active === value) return;
    active = value;
    getOptions().onActiveChange?.(value);
  };
  const restore = () => {
    const marker = page.dataset.readingMotion;
    const ownsMarker = marker === "swipe" || marker === "swipe-release";
    if (ownsMarker) restNavigationVisual(page, width || currentWidth());
    if (saved) {
      if (ownsMarker || !marker) {
        for (const property of ["transition", "willChange"]) {
          if (page.style[property] === ownedStyles?.[property]) page.style[property] = saved[property];
        }
      }
      if (ownsMarker) {
        if (saved.readingMotion === undefined) delete page.dataset.readingMotion;
        else page.dataset.readingMotion = saved.readingMotion;
      }
      saved = null;
      ownedStyles = null;
    }
    offset = 0;
    startOffset = 0;
    phase = "idle";
    if (machine()?.state !== NAVIGATION_STATES.EXITING) moveState(NAVIGATION_STATES.IDLE);
    setActive(false);
  };
  const ownsMotion = () => page.dataset.readingMotion === "swipe" || page.dataset.readingMotion === "swipe-release";
  const widthChanged = () => Math.abs(currentWidth() - width) > 0.5;
  const stopOwnedMotion = () => {
    motionId += 1;
    gestureRef.current = null;
    cancelMotion?.();
    cancelMotion = null;
    restore();
  };

  const settle = (complete, velocity = 0) => {
    if (disposed || !saved) return;
    if (!ownsMotion()) { stopOwnedMotion(); return; }
    if (complete && widthChanged()) complete = false;
    cancelMotion?.();
    cancelMotion = null;
    const id = ++motionId;
    phase = "settling";
    moveState(NAVIGATION_STATES.SETTLING_POP);
    page.dataset.readingMotion = "swipe-release";
    suppressClickUntil = performance.now() + 250;
    const cancel = animateNavigationSettle(page, {
      from: offset,
      to: complete ? width : 0,
      width,
      velocity,
      reduceMotion: Boolean(getOptions().reduceMotion),
      shouldApply: () => ownsMotion(),
      onDone: () => {
        if (disposed || id !== motionId) return;
        cancelMotion = null;
        if (!ownsMotion()) {
          stopOwnedMotion();
        } else if (complete && (unavailable() || widthChanged())) {
          settle(false);
        } else if (complete) {
          phase = "complete";
          moveState(NAVIGATION_STATES.EXITING);
          getOptions().onBack?.();
          setActive(false);
        } else {
          restore();
        }
      },
    });
    if (id === motionId && phase === "settling") cancelMotion = cancel;
    else cancel();
  };
  const cancelOnResize = () => {
    if (disposed || !saved || (phase !== "tracking" && phase !== "settling") || !widthChanged()) return false;
    gestureRef.current = null;
    settle(false);
    return true;
  };

  const detachGesture = attachSwipeGesture(document, {
    gestureRef,
    isBlocked: (event) => {
      if (unavailable() || Boolean(event && !page.contains(event.target))) return true;
      if (event?.type !== "touchstart") return false;
      const x = event.touches[0]?.clientX;
      const viewportWidth = view?.innerWidth || page.clientWidth;
      return x < SYSTEM_EDGE_PX || x > viewportWidth - SYSTEM_EDGE_PX;
    },
    getOptions: () => ({
      enabled: () => {
        if (disposed || (phase !== "idle" && phase !== "tracking")) return false;
        const marker = page.dataset.readingMotion;
        if (phase === "idle" && marker && marker !== "entrance") return false;
        return enabled();
      },
      onStart: ({ deltaX }) => {
        if (deltaX <= 0) return false;
        const takingEntrance = page.dataset.readingMotion === "entrance";
        const takeoverEntrance = getOptions().onTakeoverEntrance;
        if (takingEntrance && (typeof takeoverEntrance !== "function" || takeoverEntrance() === false)) return false;
        const transform = page.style.transform || view?.getComputedStyle?.(page)?.transform;
        if (!takingEntrance && !isRestingTransform(transform)) return false;
        const inheritedOffset = takingEntrance ? translateX(transform) : 0;
        if (takingEntrance && inheritedOffset === null) return false;
        width = currentWidth();
        if (!width) return false;
        startOffset = Math.min(width, Math.max(0, inheritedOffset || 0));
        offset = startOffset;
        saved = {
          transition: page.style.transition,
          willChange: takingEntrance ? "" : page.style.willChange,
          readingMotion: takingEntrance ? undefined : page.dataset.readingMotion,
        };
        page.style.transition = "none";
        page.style.willChange = "transform";
        ownedStyles = {
          transform: page.style.transform,
          transition: page.style.transition,
          willChange: page.style.willChange,
        };
        page.dataset.readingMotion = "swipe";
        phase = "tracking";
        moveState(NAVIGATION_STATES.INTERACTIVE_POP);
        setActive(true);
      },
      onMove: ({ deltaX }) => setOffset(Math.min(width, Math.max(0, startOffset + deltaX))),
      onEnd: ({ deltaX, velocityX }) => {
        if (cancelOnResize()) return;
        setOffset(Math.min(width, Math.max(0, startOffset + deltaX)));
        settle(shouldFinishArticleSwipe(offset, width, velocityX), velocityX);
      },
      onCancel: () => settle(false),
    }),
  });

  const stopReleaseTouch = (event) => {
    cancelOnResize();
    if ((phase === "settling" || phase === "complete") && event.cancelable) event.preventDefault();
  };
  const stopSwipeClick = (event) => {
    if (phase !== "idle" || performance.now() < suppressClickUntil) {
      event.preventDefault();
      event.stopPropagation();
    }
  };
  page.addEventListener("touchstart", stopReleaseTouch, { passive: false });
  page.addEventListener("click", stopSwipeClick, true);
  view?.addEventListener?.("resize", cancelOnResize);

  return () => {
    disposed = true;
    motionId += 1;
    detachGesture();
    cancelMotion?.();
    cancelMotion = null;
    page.removeEventListener("touchstart", stopReleaseTouch);
    page.removeEventListener("click", stopSwipeClick, true);
    view?.removeEventListener?.("resize", cancelOnResize);
    const clearOwnedVisual = ownsMotion() || !page.dataset.readingMotion;
    restore();
    // A parent entrance/release/continuous transition may already own both the
    // page transform and the shared depth layers. Never erase those on passive
    // hook cleanup; only clear visuals that still belong to this controller.
    if (clearOwnedVisual) clearNavigationVisual(page);
  };
}
