// framer-motion presets for design system v2. Values come from spring.mjs so
// CSS (linear() easings) and JS animations share one source of truth.
// Deliberately store-free: callers pass NextFlux's own reduceMotion setting, so
// the module can be imported anywhere (including isolated test fixtures).
import { useEffect, useSyncExternalStore } from "react";
import { SPRINGS, framerSpring } from "./spring.mjs";

export const spring = Object.freeze({
  spatial: framerSpring(SPRINGS.spatial),
  effects: framerSpring(SPRINGS.effects),
  pressIn: framerSpring(SPRINGS.pressIn),
  pressRelease: framerSpring(SPRINGS.pressRelease),
  page: framerSpring(SPRINGS.page),
  sheet: framerSpring(SPRINGS.sheet),
  dialog: framerSpring(SPRINGS.dialog),
});

/** 220ms accelerate curve used by every sheet / dialog exit. */
export const exitTween = Object.freeze({ type: "tween", duration: 0.22, ease: [0.3, 0, 1, 1] });
export const snap = Object.freeze({ duration: 0 });

export const PRESS_SCALE = 0.965;
export const PRESS_SCALE_ROW = 0.985;
export const PAGE_SHIFT = 30;
export const STAGGER_STEP = 0.026;
export const STAGGER_LIMIT = 8;
export const STAGGER_RISE = 8;

/** whileTap / transition props for a pressable element. */
export function pressProps(reduced, { row = false } = {}) {
  if (reduced) return {};
  return {
    whileTap: { scale: row ? PRESS_SCALE_ROW : PRESS_SCALE, transition: spring.pressIn },
    transition: spring.pressRelease,
  };
}

/** Shared X-axis page transition (30px + fade). direction: 1 forward, -1 back. */
export function pageVariants(reduced) {
  if (reduced) return { enter: { opacity: 1, x: 0 }, center: { opacity: 1, x: 0 }, exit: { opacity: 0, transition: snap } };
  return {
    enter: (direction = 1) => ({ opacity: 0, x: PAGE_SHIFT * direction }),
    center: { opacity: 1, x: 0, transition: { x: spring.page, opacity: spring.effects } },
    exit: (direction = 1) => ({ opacity: 0, x: -PAGE_SHIFT * direction, transition: { x: spring.page, opacity: { duration: 0.12 } } }),
  };
}

/** Item i of a staggered entrance; only the first 8 animate. */
export function staggerItem(index, reduced) {
  if (reduced || index >= STAGGER_LIMIT) return { initial: false };
  return {
    initial: { opacity: 0, y: STAGGER_RISE },
    animate: { opacity: 1, y: 0 },
    transition: { ...spring.spatial, delay: index * STAGGER_STEP, opacity: { ...spring.effects, delay: index * STAGGER_STEP } },
  };
}

const playedScreens = new Set();
/** Stagger plays once per screen per process (design system rule). */
export function claimStagger(screen) {
  if (playedScreens.has(screen)) return false;
  playedScreens.add(screen);
  return true;
}

const QUERY = "(prefers-reduced-motion: reduce)";
function subscribeMedia(callback) {
  const media = window.matchMedia?.(QUERY);
  media?.addEventListener?.("change", callback);
  return () => media?.removeEventListener?.("change", callback);
}
const mediaSnapshot = () => Boolean(window.matchMedia?.(QUERY)?.matches);

/** True when either the system or NextFlux's own "reduce motion" preference is on. */
export function useReducedMotionPreference(reduceMotion = false) {
  const system = useSyncExternalStore(subscribeMedia, mediaSnapshot, () => false);
  return Boolean(reduceMotion || system);
}

/** Mirrors the preference onto <html> so CSS tokens snap as well. */
export function useReducedMotionAttribute(reduceMotion = false) {
  useEffect(() => {
    const root = document.documentElement;
    if (reduceMotion) root.dataset.reduceMotion = "true";
    else delete root.dataset.reduceMotion;
  }, [reduceMotion]);
}
