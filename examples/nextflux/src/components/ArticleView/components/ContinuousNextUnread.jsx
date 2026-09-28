import { useEffect, useMemo, useRef } from "react";
import { createPortal } from "react-dom";
import { useNavigate } from "react-router-dom";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";
import { filteredArticles, imageGalleryActive } from "@/stores/articlesStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import { handleMarkRead } from "@/handlers/articleHandlers.js";
import FeedIcon from "@/components/ui/FeedIcon.jsx";
import { generateReadableDate } from "@/lib/format.js";
import {
  CONTINUOUS_BRIDGE_HEIGHT,
  CONTINUOUS_PULL_MAX,
  CONTINUOUS_PULL_TRIGGER,
  continuousPullResistance,
  findNextUnreadArticle,
  springFrame,
} from "@/toolbox/continuous-reading-motion.mjs";

const INTERACTIVE_SELECTOR = "iframe,video,audio,input,textarea,select,[contenteditable='true'],.PhotoView-Slider__BannerWrap";

export default function ContinuousNextUnread({ articleId, scrollAreaRef, surfaceRef, enabled }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const articles = useStore(filteredArticles);
  const galleryActive = useStore(imageGalleryActive);
  const { reduceMotion } = useStore(settingsState);
  const nextUnread = useMemo(
    () => findNextUnreadArticle(articles, articleId),
    [articles, articleId],
  );

  const layerRef = useRef(null);
  const bridgeRef = useRef(null);
  const previewRef = useRef(null);
  const springBarRef = useRef(null);
  const meterRef = useRef(null);
  const bridgeTextRef = useRef(null);
  const bridgeSubRef = useRef(null);

  const rawPullRef = useRef(0);
  const pullingRef = useRef(false);
  const transitioningRef = useRef(false);
  const lastYRef = useRef(0);
  const lastTRef = useRef(0);
  const velocityRef = useRef(0);
  const animationRef = useRef(0);
  const crossedRef = useRef(false);

  const cancelAnimation = () => {
    if (!animationRef.current) return;
    cancelAnimationFrame(animationRef.current);
    animationRef.current = 0;
  };

  const setCopy = (crossed) => {
    const text = bridgeTextRef.current;
    const sub = bridgeSubRef.current;
    if (!text || !sub) return;
    if (!nextUnread) {
      text.textContent = t("articleView.continuousReading.end");
      sub.textContent = t("articleView.continuousReading.releaseBack");
      return;
    }
    text.textContent = crossed
      ? t("articleView.continuousReading.release")
      : t("articleView.continuousReading.pull");
    sub.textContent = crossed
      ? t("articleView.continuousReading.crossed")
      : t("articleView.continuousReading.hint");
  };

  const applyVisibleOffset = (offset, ratio, crossed = false) => {
    const bridge = bridgeRef.current;
    const preview = previewRef.current;
    const springBar = springBarRef.current;
    const meter = meterRef.current;
    const surface = surfaceRef.current;
    const layer = layerRef.current;
    if (!bridge || !preview || !springBar || !meter || !layer) return;

    layer.style.opacity = offset > 0 ? "1" : "0";
    bridge.style.transform = `translate3d(0,${-offset}px,0)`;
    preview.style.transform = `translate3d(0,${-offset}px,0)`;
    springBar.style.transform = `scaleX(${1 + ratio * 0.72}) scaleY(${1 - ratio * 0.38})`;
    springBar.style.background = crossed ? "color-mix(in srgb, var(--accent) 34%, var(--muted))" : "";
    meter.style.width = `${Math.min(100, ratio * 100)}%`;
    preview.style.boxShadow = `0 -18px 44px rgba(0,0,0,${0.04 + Math.min(1, ratio) * 0.08})`;
    if (surface) surface.style.transform = `translate3d(0,${-offset * 0.13}px,0)`;
    setCopy(crossed);
  };

  const applyPull = (rawPull) => {
    const raw = Math.max(0, Math.min(CONTINUOUS_PULL_MAX, rawPull));
    rawPullRef.current = raw;
    const offset = continuousPullResistance(raw);
    const ratio = Math.min(1, raw / CONTINUOUS_PULL_TRIGGER);
    const crossed = raw >= CONTINUOUS_PULL_TRIGGER;

    if (crossed && !crossedRef.current) {
      crossedRef.current = true;
      globalThis.navigator?.vibrate?.(7);
    } else if (!crossed) {
      crossedRef.current = false;
    }
    applyVisibleOffset(offset, ratio, crossed);
  };

  const clearVisualState = () => {
    rawPullRef.current = 0;
    pullingRef.current = false;
    crossedRef.current = false;
    const layer = layerRef.current;
    const bridge = bridgeRef.current;
    const preview = previewRef.current;
    const springBar = springBarRef.current;
    const meter = meterRef.current;
    const surface = surfaceRef.current;
    if (layer) layer.style.opacity = "0";
    if (bridge) bridge.style.transform = "";
    if (preview) {
      preview.style.transform = "";
      preview.style.boxShadow = "";
    }
    if (springBar) {
      springBar.style.transform = "";
      springBar.style.background = "";
    }
    if (meter) meter.style.width = "0";
    if (surface) {
      surface.style.transform = "";
      surface.style.opacity = "";
    }
    setCopy(false);
  };

  const springTo = ({ from, to, velocity = 0, stiffness, damping, onUpdate, onDone }) => {
    cancelAnimation();
    let x = from;
    let v = velocity;
    let last = performance.now();

    const frame = (now) => {
      const dt = Math.min(0.032, Math.max(0, (now - last) / 1000));
      last = now;
      const next = springFrame({ x, velocity: v, target: to, dt, stiffness, damping });
      x = next.x;
      v = next.velocity;
      onUpdate(x, v);
      if (Math.abs(v) < 0.45 && Math.abs(x - to) < 0.45) {
        onUpdate(to, 0);
        animationRef.current = 0;
        onDone?.();
        return;
      }
      animationRef.current = requestAnimationFrame(frame);
    };
    animationRef.current = requestAnimationFrame(frame);
  };

  const springBack = () => {
    const from = continuousPullResistance(rawPullRef.current);
    rawPullRef.current = 0;
    crossedRef.current = false;
    if (reduceMotion) {
      clearVisualState();
      return;
    }
    const initialVelocity = Math.max(-420, Math.min(420, -velocityRef.current * 160));
    springTo({
      from,
      to: 0,
      velocity: initialVelocity,
      stiffness: 420,
      damping: 36,
      onUpdate: (x) => {
        const safe = Math.max(0, x);
        applyVisibleOffset(safe, Math.min(1, safe / 57.18), false);
      },
      onDone: clearVisualState,
    });
  };

  const navigateToNext = () => {
    if (!nextUnread) {
      springBack();
      return;
    }
    const basePath = (window.location.hash.slice(1).split("?")[0] || "/").split("/article/")[0];
    navigate(`${basePath}/article/${nextUnread.id}`);
    if (nextUnread.status !== "read") void handleMarkRead(nextUnread);
  };

  const commitNext = () => {
    if (!nextUnread || transitioningRef.current) {
      springBack();
      return;
    }
    transitioningRef.current = true;

    if (reduceMotion) {
      navigateToNext();
      clearVisualState();
      transitioningRef.current = false;
      return;
    }

    const from = continuousPullResistance(rawPullRef.current);
    const target = window.innerHeight + CONTINUOUS_BRIDGE_HEIGHT;
    const initialVelocity = Math.max(160, Math.min(920, velocityRef.current * 260 + 220));
    const surface = surfaceRef.current;

    springTo({
      from,
      to: target,
      velocity: initialVelocity,
      stiffness: 250,
      damping: 28,
      onUpdate: (x) => {
        const progress = Math.min(1, Math.max(0, (x - from) / Math.max(1, target - from)));
        applyVisibleOffset(x, Math.max(0, 1 - progress) * 0.78, true);
        if (surface) {
          surface.style.transform = `translate3d(0,${-Math.min(115, x * 0.15)}px,0)`;
          surface.style.opacity = String(1 - progress * 0.82);
        }
      },
      onDone: () => {
        navigateToNext();
        const layer = layerRef.current;
        if (layer) {
          layer.style.transition = "opacity 160ms ease";
          requestAnimationFrame(() => { layer.style.opacity = "0"; });
        }
        setTimeout(() => {
          if (layer) layer.style.transition = "";
          clearVisualState();
          transitioningRef.current = false;
        }, 180);
      },
    });
  };

  useEffect(() => {
    if (!enabled) return undefined;
    const viewport = scrollAreaRef.current;
    if (!viewport) return undefined;

    const atBottom = () =>
      viewport.scrollTop + viewport.clientHeight >= viewport.scrollHeight - 2;

    const blockedTarget = (target) =>
      target instanceof Element && Boolean(target.closest(INTERACTIVE_SELECTOR));

    const onTouchStart = (event) => {
      if (transitioningRef.current || galleryActive || event.touches.length !== 1 || blockedTarget(event.target)) return;
      cancelAnimation();
      lastYRef.current = event.touches[0].clientY;
      lastTRef.current = performance.now();
      velocityRef.current = 0;
      pullingRef.current = false;
      rawPullRef.current = 0;
    };

    const onTouchMove = (event) => {
      if (transitioningRef.current || galleryActive || event.touches.length !== 1) return;
      const y = event.touches[0].clientY;
      const now = performance.now();
      const move = lastYRef.current - y;
      const dt = Math.max(1, now - lastTRef.current);
      lastYRef.current = y;
      lastTRef.current = now;
      velocityRef.current = move / dt;

      if (!pullingRef.current) {
        if (!(move > 0 && atBottom())) return;
        pullingRef.current = true;
      }
      if (event.cancelable) event.preventDefault();

      const nextRaw = move > 0
        ? rawPullRef.current + move
        : Math.max(0, rawPullRef.current + move * 1.05);
      applyPull(nextRaw);
      if (nextRaw <= 0 && move < 0) {
        pullingRef.current = false;
        clearVisualState();
      }
    };

    const finish = () => {
      if (!pullingRef.current || transitioningRef.current) return;
      pullingRef.current = false;
      if (rawPullRef.current >= CONTINUOUS_PULL_TRIGGER && nextUnread) commitNext();
      else springBack();
    };

    viewport.addEventListener("touchstart", onTouchStart, { passive: true });
    viewport.addEventListener("touchmove", onTouchMove, { passive: false });
    viewport.addEventListener("touchend", finish, { passive: true });
    viewport.addEventListener("touchcancel", finish, { passive: true });
    return () => {
      viewport.removeEventListener("touchstart", onTouchStart);
      viewport.removeEventListener("touchmove", onTouchMove);
      viewport.removeEventListener("touchend", finish);
      viewport.removeEventListener("touchcancel", finish);
      cancelAnimation();
      clearVisualState();
    };
  }, [articleId, enabled, galleryActive, nextUnread?.id, reduceMotion]);

  useEffect(() => {
    setCopy(false);
  }, [nextUnread?.id, i18n.language]);

  if (!enabled || typeof document === "undefined") return null;

  return createPortal(
    <div ref={layerRef} className="nextflux-continuous-layer" aria-hidden="true">
      <div ref={bridgeRef} className="nextflux-continuous-bridge">
        <div ref={springBarRef} className="nextflux-continuous-springbar" />
        <div ref={bridgeTextRef} className="nextflux-continuous-label" />
        <div ref={bridgeSubRef} className="nextflux-continuous-hint" />
        <div ref={meterRef} className="nextflux-continuous-meter" />
      </div>
      <section ref={previewRef} className="nextflux-continuous-preview">
        <div className="nextflux-continuous-preview-inner">
          {nextUnread ? (
            <>
              <div className="nextflux-continuous-feed">
                <FeedIcon feedId={nextUnread.feed?.id ?? nextUnread.feedId} />
                <span>{nextUnread.feed?.title || t("articleView.continuousReading.nextUnread")}</span>
              </div>
              <h2>{nextUnread.titleText ?? nextUnread.title}</h2>
              <div className="nextflux-continuous-date">
                {generateReadableDate(nextUnread.published_at)}
              </div>
              <div className="nextflux-continuous-rule" />
              <p>{t("articleView.continuousReading.preview")}</p>
            </>
          ) : (
            <div className="nextflux-continuous-empty">
              {t("articleView.continuousReading.end")}
            </div>
          )}
        </div>
      </section>
    </div>,
    document.body,
  );
}
