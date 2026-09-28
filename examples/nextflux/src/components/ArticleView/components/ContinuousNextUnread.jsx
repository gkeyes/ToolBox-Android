import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useNavigate } from "react-router-dom";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";
import { ProgressCircle } from "@heroui/react";
import {
  activeArticle,
  filteredArticles,
  imageGalleryActive,
} from "@/stores/articlesStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import { handleMarkRead } from "@/handlers/articleHandlers.js";
import { getArticleById } from "@/db/storage.js";
import ArticleHeader from "@/components/ArticleView/components/ArticleHeader.jsx";
import {
  CONTINUOUS_BRIDGE_HEIGHT,
  CONTINUOUS_PULL_MAX,
  CONTINUOUS_PULL_TRIGGER,
  continuousPullResistance,
  findNextUnreadArticle,
  springFrame,
} from "@/toolbox/continuous-reading-motion.mjs";

const INTERACTIVE_SELECTOR = "iframe,video,audio,input,textarea,select,[contenteditable='true'],.PhotoView-Slider__BannerWrap";

function previewTextOf(article) {
  const source = article?.contentText ?? article?.summary ?? article?.content ?? "";
  return String(source)
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 150);
}

export default function ContinuousNextUnread({ articleId, scrollAreaRef, surfaceRef, enabled }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const articles = useStore(filteredArticles);
  const galleryActive = useStore(imageGalleryActive);
  const { reduceMotion, maxWidth, fontFamily } = useStore(settingsState);
  const nextUnread = useMemo(
    () => findNextUnreadArticle(articles, articleId),
    [articles, articleId],
  );

  const [progressValue, setProgressValue] = useState(0);
  const [handoffArticle, setHandoffArticle] = useState(null);
  const previewArticle = handoffArticle ?? nextUnread;

  const layerRef = useRef(null);
  const bridgeRef = useRef(null);
  const previewRef = useRef(null);
  const previewBodyRef = useRef(null);
  const springBarRef = useRef(null);
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
  const preparedRef = useRef({ id: null, promise: null });

  useEffect(() => {
    if (!nextUnread?.id) {
      preparedRef.current = { id: null, promise: null };
      return;
    }
    const id = String(nextUnread.id);
    preparedRef.current = {
      id,
      promise: getArticleById(id).catch(() => null),
    };
  }, [nextUnread?.id]);

  const cancelAnimation = () => {
    if (!animationRef.current) return;
    cancelAnimationFrame(animationRef.current);
    animationRef.current = 0;
  };

  const setCopy = (crossed) => {
    const text = bridgeTextRef.current;
    const sub = bridgeSubRef.current;
    if (!text || !sub) return;
    if (!previewArticle) {
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
    const surface = surfaceRef.current;
    const layer = layerRef.current;
    if (!bridge || !preview || !springBar || !layer) return;

    layer.style.opacity = offset > 0 ? "1" : "0";
    bridge.style.transform = `translate3d(0,${-offset}px,0)`;
    preview.style.transform = `translate3d(0,${-offset}px,0)`;
    springBar.style.transform = `scaleX(${1 + ratio * 0.42}) scaleY(${1 - ratio * 0.2})`;
    springBar.style.opacity = crossed ? "0.45" : "0.75";
    preview.style.boxShadow = `0 -18px 44px rgba(0,0,0,${0.035 + Math.min(1, ratio) * 0.055})`;
    if (surface) surface.style.transform = `translate3d(0,${-offset * 0.1}px,0)`;
    setCopy(crossed);
  };

  const applyPull = (rawPull) => {
    const raw = Math.max(0, Math.min(CONTINUOUS_PULL_MAX, rawPull));
    rawPullRef.current = raw;
    const offset = continuousPullResistance(raw);
    const ratio = Math.min(1, raw / CONTINUOUS_PULL_TRIGGER);
    const crossed = raw >= CONTINUOUS_PULL_TRIGGER;

    setProgressValue(Math.round(ratio * 100));

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
    setProgressValue(0);

    const layer = layerRef.current;
    const bridge = bridgeRef.current;
    const preview = previewRef.current;
    const previewBody = previewBodyRef.current;
    const springBar = springBarRef.current;
    const surface = surfaceRef.current;

    if (layer) {
      layer.style.opacity = "0";
      layer.style.transition = "";
    }
    if (bridge) {
      bridge.style.transform = "";
      bridge.style.opacity = "";
      bridge.style.transition = "";
    }
    if (preview) {
      preview.style.transform = "";
      preview.style.boxShadow = "";
      preview.style.background = "";
      preview.style.transition = "";
    }
    if (previewBody) {
      previewBody.style.opacity = "";
      previewBody.style.transform = "";
      previewBody.style.transition = "";
    }
    if (springBar) {
      springBar.style.transform = "";
      springBar.style.opacity = "";
    }
    if (surface) {
      surface.style.transform = "";
      surface.style.opacity = "";
      surface.style.transition = "";
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
    const initialVelocity = Math.max(-320, Math.min(320, -velocityRef.current * 120));
    springTo({
      from,
      to: 0,
      velocity: initialVelocity,
      stiffness: 390,
      damping: 38,
      onUpdate: (x) => {
        const safe = Math.max(0, x);
        const ratio = Math.min(1, safe / continuousPullResistance(CONTINUOUS_PULL_TRIGGER));
        setProgressValue(Math.round(ratio * 100));
        applyVisibleOffset(safe, ratio, false);
      },
      onDone: clearVisualState,
    });
  };

  const resolvePreparedArticle = async (article) => {
    if (!article?.id) return article;
    const id = String(article.id);
    if (preparedRef.current.id === id && preparedRef.current.promise) {
      const prepared = await preparedRef.current.promise;
      if (prepared) return prepared;
    }
    return (await getArticleById(id).catch(() => null)) ?? article;
  };

  const navigatePrepared = async (article) => {
    if (!article) return;
    const prepared = await resolvePreparedArticle(article);
    activeArticle.set(prepared);
    const basePath = (window.location.hash.slice(1).split("?")[0] || "/").split("/article/")[0];
    navigate(`${basePath}/article/${article.id}`);
    if (article.status !== "read") void handleMarkRead(article);
  };

  const finishSharedHandoff = () => {
    const bridge = bridgeRef.current;
    const preview = previewRef.current;
    const previewBody = previewBodyRef.current;

    if (bridge) {
      bridge.style.transition = "opacity 180ms cubic-bezier(.4,0,1,1)";
      bridge.style.opacity = "0";
    }
    if (previewBody) {
      previewBody.style.transition = "opacity 170ms cubic-bezier(.4,0,1,1), transform 220ms cubic-bezier(.2,0,0,1)";
      previewBody.style.opacity = "0";
      previewBody.style.transform = "translate3d(0,-6px,0)";
    }
    if (preview) {
      preview.style.transition = "background-color 360ms cubic-bezier(.2,0,0,1), box-shadow 360ms cubic-bezier(.2,0,0,1)";
      preview.style.background = "transparent";
      preview.style.boxShadow = "none";
    }

    setTimeout(() => {
      clearVisualState();
      setHandoffArticle(null);
      transitioningRef.current = false;
    }, 460);
  };

  const commitNext = () => {
    if (!nextUnread || transitioningRef.current) {
      springBack();
      return;
    }
    transitioningRef.current = true;
    setHandoffArticle(nextUnread);
    setProgressValue(100);

    if (reduceMotion) {
      void navigatePrepared(nextUnread).finally(() => {
        clearVisualState();
        setHandoffArticle(null);
        transitioningRef.current = false;
      });
      return;
    }

    const from = continuousPullResistance(rawPullRef.current);
    const target = window.innerHeight + CONTINUOUS_BRIDGE_HEIGHT;
    const initialVelocity = Math.max(110, Math.min(560, velocityRef.current * 165 + 135));
    const surface = surfaceRef.current;
    const navigationPromise = resolvePreparedArticle(nextUnread);

    springTo({
      from,
      to: target,
      velocity: initialVelocity,
      stiffness: 190,
      damping: 32,
      onUpdate: (x) => {
        const progress = Math.min(1, Math.max(0, (x - from) / Math.max(1, target - from)));
        applyVisibleOffset(x, Math.max(0, 1 - progress) * 0.58, true);
        if (surface) {
          surface.style.transform = `translate3d(0,${-Math.min(72, x * 0.085)}px,0)`;
          surface.style.opacity = String(1 - progress * 0.58);
        }
      },
      onDone: async () => {
        const prepared = await navigationPromise;
        activeArticle.set(prepared ?? nextUnread);
        const basePath = (window.location.hash.slice(1).split("?")[0] || "/").split("/article/")[0];
        navigate(`${basePath}/article/${nextUnread.id}`);
        if (nextUnread.status !== "read") void handleMarkRead(nextUnread);
        if (surface) {
          surface.style.transition = "transform 360ms cubic-bezier(.2,0,0,1), opacity 260ms cubic-bezier(.2,0,0,1)";
          requestAnimationFrame(() => {
            surface.style.transform = "translate3d(0,0,0)";
            surface.style.opacity = "1";
          });
        }
        requestAnimationFrame(() => requestAnimationFrame(finishSharedHandoff));
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
        ? rawPullRef.current + move * 0.82
        : Math.max(0, rawPullRef.current + move * 0.96);
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
      if (!transitioningRef.current) clearVisualState();
    };
  }, [articleId, enabled, galleryActive, nextUnread?.id, reduceMotion]);

  useEffect(() => {
    setCopy(false);
  }, [previewArticle?.id, i18n.language]);

  if (!enabled || typeof document === "undefined") return null;

  const sharedLayoutId = previewArticle ? `nextflux-article-${previewArticle.id}` : undefined;
  const previewText = previewTextOf(previewArticle);

  return createPortal(
    <div ref={layerRef} className="nextflux-continuous-layer" aria-hidden="true">
      <div ref={bridgeRef} className="nextflux-continuous-bridge">
        <div className="nextflux-continuous-progress-wrap">
          <ProgressCircle
            aria-label={t("articleView.continuousReading.pull")}
            value={progressValue}
            className="nextflux-continuous-progress-circle"
          />
          <span className="nextflux-continuous-progress-glyph">
            {progressValue >= 100 ? "✓" : "↑"}
          </span>
        </div>
        <div ref={springBarRef} className="nextflux-continuous-springbar" />
        <div ref={bridgeTextRef} className="nextflux-continuous-label" />
        <div ref={bridgeSubRef} className="nextflux-continuous-hint" />
      </div>

      <section ref={previewRef} className="nextflux-continuous-preview">
        <div
          className="nextflux-continuous-preview-inner"
          style={{ maxWidth: `${maxWidth}ch`, fontFamily }}
        >
          {previewArticle ? (
            <>
              <ArticleHeader
                article={previewArticle}
                layoutId={sharedLayoutId}
                interactive={false}
                className="nextflux-continuous-shared-header"
              />
              <div ref={previewBodyRef} className="nextflux-continuous-preview-body">
                <div className="nextflux-continuous-rule" />
                <p>{previewText || t("articleView.continuousReading.preview")}</p>
              </div>
            </>
          ) : (
            <div ref={previewBodyRef} className="nextflux-continuous-empty">
              {t("articleView.continuousReading.end")}
            </div>
          )}
        </div>
      </section>
    </div>,
    document.body,
  );
}
