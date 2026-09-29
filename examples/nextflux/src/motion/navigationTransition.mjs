import { animateReadingEntrance, animateReadingValue } from "../toolbox/reading-motion.mjs";

const LIST_SELECTOR = ".nextflux-article-list-page";
const COVER_SELECTOR = ".nextflux-list-transition-cover";
const EFFECT_SELECTOR = ".nextflux-transition-effect";
const SHADOW_SELECTOR = ".nextflux-transition-shadow";

export const NAVIGATION_STATES = Object.freeze({
  IDLE: "idle",
  PREPARING: "preparing",
  ENTERING: "entering",
  INTERACTIVE_POP: "interactive-pop",
  SETTLING_POP: "settling-pop",
  EXITING: "exiting",
});

const allowed = {
  [NAVIGATION_STATES.IDLE]: new Set([NAVIGATION_STATES.PREPARING, NAVIGATION_STATES.INTERACTIVE_POP, NAVIGATION_STATES.EXITING]),
  [NAVIGATION_STATES.PREPARING]: new Set([NAVIGATION_STATES.ENTERING, NAVIGATION_STATES.EXITING, NAVIGATION_STATES.IDLE]),
  [NAVIGATION_STATES.ENTERING]: new Set([NAVIGATION_STATES.IDLE, NAVIGATION_STATES.INTERACTIVE_POP, NAVIGATION_STATES.EXITING]),
  [NAVIGATION_STATES.INTERACTIVE_POP]: new Set([NAVIGATION_STATES.SETTLING_POP, NAVIGATION_STATES.IDLE, NAVIGATION_STATES.EXITING]),
  [NAVIGATION_STATES.SETTLING_POP]: new Set([NAVIGATION_STATES.IDLE, NAVIGATION_STATES.EXITING]),
  [NAVIGATION_STATES.EXITING]: new Set([NAVIGATION_STATES.IDLE]),
};

export function createNavigationTransitionMachine({ initial = NAVIGATION_STATES.IDLE, onChange } = {}) {
  let state = initial;
  return {
    get state() { return state; },
    can(next) { return next === state || allowed[state]?.has(next) === true; },
    transition(next) {
      if (next === state) return true;
      if (!allowed[state]?.has(next)) return false;
      const previous = state;
      state = next;
      onChange?.(state, previous);
      return true;
    },
    reset(next = NAVIGATION_STATES.IDLE) {
      const previous = state;
      state = next;
      if (previous !== next) onChange?.(state, previous);
    },
  };
}

const clamp01 = (value) => Math.max(0, Math.min(1, value));
const easeOutCubic = (value) => 1 - Math.pow(1 - value, 3);

function nodes(page) {
  const parent = page?.parentElement;
  const list = parent?.querySelector?.(LIST_SELECTOR) ?? page?.ownerDocument?.querySelector?.(LIST_SELECTOR) ?? null;
  return {
    list,
    cover: list?.querySelector?.(COVER_SELECTOR) ?? null,
    effect: parent?.querySelector?.(EFFECT_SELECTOR) ?? page?.ownerDocument?.querySelector?.(EFFECT_SELECTOR) ?? null,
    shadow: parent?.querySelector?.(SHADOW_SELECTOR) ?? page?.ownerDocument?.querySelector?.(SHADOW_SELECTOR) ?? null,
  };
}

export function navigationVisualState(offset, width) {
  const safeWidth = Math.max(0, Number(width) || 0);
  const progress = safeWidth > 0 ? clamp01((Number(offset) || 0) / safeWidth) : 0;
  const backgroundProgress = easeOutCubic(progress);
  const reveal = clamp01(progress / 0.045);
  const rawListX = -0.23 * safeWidth * (1 - backgroundProgress);
  return {
    progress,
    pageX: safeWidth * progress,
    listX: Math.abs(rawListX) < 1e-9 ? 0 : rawListX,
    coverOpacity: 0.025 * (1 - backgroundProgress),
    shadowOpacity: reveal * (0.68 - 0.12 * progress),
  };
}

export function applyNavigationVisual(page, offset, width) {
  if (!page) return null;
  const state = navigationVisualState(offset, width);
  const { list, cover, effect, shadow } = nodes(page);
  page.style.transform = `translate3d(${state.pageX}px,0,0)`;
  if (list?.style) list.style.transform = `translate3d(${state.listX}px,0,0)`;
  if (cover?.style) cover.style.opacity = state.coverOpacity.toFixed(4);
  if (effect?.style) effect.style.transform = `translate3d(${state.pageX}px,0,0)`;
  if (shadow?.style) shadow.style.opacity = state.shadowOpacity.toFixed(4);
  return state;
}

export function prepareNavigationPush(page, width) {
  if (!page) return;
  const safeWidth = Math.max(0, Number(width) || 0);
  const { list, cover, effect, shadow } = nodes(page);
  page.style.transform = `translate3d(${safeWidth}px,0,0)`;
  if (list?.style) list.style.transform = "translate3d(0,0,0)";
  if (cover?.style) cover.style.opacity = "0";
  if (effect?.style) effect.style.transform = `translate3d(${safeWidth}px,0,0)`;
  if (shadow?.style) shadow.style.opacity = "0";
}

export function restNavigationVisual(page, width) {
  if (!page) return;
  const safeWidth = Math.max(0, Number(width) || 0);
  const { list, cover, effect, shadow } = nodes(page);
  page.style.transform = "";
  if (list?.style) list.style.transform = `translate3d(${-0.23 * safeWidth}px,0,0)`;
  if (cover?.style) cover.style.opacity = "0.025";
  if (effect?.style) effect.style.transform = "translate3d(0,0,0)";
  if (shadow?.style) shadow.style.opacity = "0";
}

export function clearNavigationVisual(page) {
  if (!page) return;
  const { list, cover, effect, shadow } = nodes(page);
  page.style.transform = "";
  if (list?.style) list.style.transform = "";
  if (cover?.style) cover.style.opacity = "";
  if (effect?.style) effect.style.transform = "";
  if (shadow?.style) shadow.style.opacity = "";
}

function animateNode(node, keyframes, options) {
  if (!node?.animate) return null;
  try { return node.animate(keyframes, options); } catch { return null; }
}

export function animateNavigationPush(page, {
  from,
  width,
  reduceMotion = false,
  duration = 360,
  easing = "cubic-bezier(0.32, 0.72, 0, 1)",
  onDone,
} = {}) {
  const safeWidth = Math.max(0, Number(width) || page?.clientWidth || 0);
  const start = Math.max(0, Math.min(safeWidth, Number(from) || safeWidth));
  if (!page || reduceMotion || !safeWidth) {
    restNavigationVisual(page, safeWidth);
    onDone?.();
    return () => {};
  }

  const { list, cover, effect, shadow } = nodes(page);
  const options = { duration, easing, fill: "both" };
  const pageAnimation = animateNode(page, [
    { transform: `translate3d(${start}px,0,0)` },
    { transform: "translate3d(0,0,0)" },
  ], options);
  const listAnimation = animateNode(list, [
    { transform: "translate3d(0,0,0)" },
    { transform: `translate3d(${-0.23 * safeWidth}px,0,0)` },
  ], options);
  const coverAnimation = animateNode(cover, [{ opacity: 0 }, { opacity: 0.025 }], options);
  const effectAnimation = animateNode(effect, [
    { transform: `translate3d(${start}px,0,0)` },
    { transform: "translate3d(0,0,0)" },
  ], options);
  const shadowAnimation = animateNode(shadow, [
    { opacity: 0.16, offset: 0 },
    { opacity: 0.62, offset: 0.22 },
    { opacity: 0.50, offset: 1 },
  ], options);
  const animations = [pageAnimation, listAnimation, coverAnimation, effectAnimation, shadowAnimation].filter(Boolean);

  if (!pageAnimation) {
    return animateReadingEntrance({
      from: start,
      to: 0,
      reduceMotion,
      duration,
      onUpdate: (value) => applyNavigationVisual(page, value, safeWidth),
      onDone: () => {
        restNavigationVisual(page, safeWidth);
        onDone?.();
      },
    });
  }

  let cancelled = false;
  pageAnimation.finished.then(() => {
    if (cancelled) return;
    restNavigationVisual(page, safeWidth);
    for (const animation of animations) animation.cancel();
    onDone?.();
  }).catch(() => {});
  return () => {
    cancelled = true;
    for (const animation of animations) animation.cancel();
  };
}

export function animateNavigationSettle(page, {
  from,
  to,
  width,
  velocity = 0,
  reduceMotion = false,
  onDone,
} = {}) {
  const safeWidth = Math.max(0, Number(width) || page?.clientWidth || 0);
  return animateReadingValue({
    from,
    to,
    velocity,
    reduceMotion,
    onUpdate: (value) => applyNavigationVisual(page, value, safeWidth),
    onDone: () => {
      if (to <= 0) restNavigationVisual(page, safeWidth);
      else applyNavigationVisual(page, safeWidth, safeWidth);
      onDone?.();
    },
  });
}

export function deferAfterPaint(callback, frames = 2) {
  let cancelled = false;
  let handle = null;
  const step = (remaining) => {
    if (cancelled) return;
    if (remaining <= 0) { callback(); return; }
    handle = requestAnimationFrame(() => step(remaining - 1));
  };
  step(Math.max(0, frames));
  return () => {
    cancelled = true;
    if (handle != null) cancelAnimationFrame(handle);
  };
}
