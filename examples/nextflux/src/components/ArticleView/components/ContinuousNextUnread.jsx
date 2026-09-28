import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useNavigate } from "react-router-dom";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";
import {
  activeArticle,
  filteredArticles,
  imageGalleryActive,
} from "@/stores/articlesStore.js";
import { handleMarkRead } from "@/handlers/articleHandlers.js";
import { getArticleById } from "@/db/storage.js";
import ArticlePageContent from "@/components/ArticleView/components/ArticlePageContent.jsx";
import {
  CONTINUOUS_MAX_EXTRA,
  CONTINUOUS_MOTION_SPEED,
  CONTINUOUS_PAGE_START,
  CONTINUOUS_PULL_TRIGGER,
  continuousRubberBand,
  findNextUnreadArticle,
} from "@/toolbox/continuous-reading-motion.mjs";

const INTERACTIVE_SELECTOR =
  "iframe,video,audio,input,textarea,select,[contenteditable='true'],.PhotoView-Slider__BannerWrap";
const RING_RADIUS = 19;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

export default function ContinuousNextUnread({
  articleId,
  scrollAreaRef,
  surfaceRef,
  enabled,
  onTransitionStateChange,
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const articles = useStore(filteredArticles);
  const galleryActive = useStore(imageGalleryActive);
  const nextUnread = useMemo(
    () => findNextUnreadArticle(articles, articleId),
    [articles, articleId],
  );

  const [preparedArticle, setPreparedArticle] = useState(null);

  const layerRef = useRef(null);
  const nextPageRef = useRef(null);
  const dockRef = useRef(null);
  const fillRef = useRef(null);
  const textRef = useRef(null);
  const washRef = useRef(null);
  const toastRef = useRef(null);

  const rawRef = useRef(0);
  const pullingRef = useRef(false);
  const touchingRef = useRef(false);
  const transitionLockedRef = useRef(false);
  const handoffTargetRef = useRef(null);
  const lastYRef = useRef(0);
  const lastTRef = useRef(0);
  const velocityRef = useRef(0);
  const springRafRef = useRef(0);
  const paintRafRef = useRef(0);
  const needsPaintRef = useRef(false);
  const preparedRef = useRef({ id: null, promise: null });

  const visibleNextArticle =
    preparedArticle && String(preparedArticle.id) === String(nextUnread?.id)
      ? preparedArticle
      : nextUnread;

  useEffect(() => {
    let cancelled = false;
    setPreparedArticle(null);

    if (!nextUnread?.id) {
      preparedRef.current = { id: null, promise: null };
      return () => {
        cancelled = true;
      };
    }

    const id = String(nextUnread.id);
    const promise = getArticleById(id)
      .catch(() => null)
      .then((article) => {
        if (!cancelled && article) setPreparedArticle(article);
        return article;
      });

    preparedRef.current = { id, promise };

    return () => {
      cancelled = true;
    };
  }, [nextUnread?.id]);

  const stopSpring = () => {
    if (!springRafRef.current) return;
    cancelAnimationFrame(springRafRef.current);
    springRafRef.current = 0;
  };

  const setProgress = (progress) => {
    const clamped = Math.max(0, Math.min(1, progress));
    const fill = fillRef.current;
    const dock = dockRef.current;
    const wash = washRef.current;
    const text = textRef.current;
    if (!fill || !dock || !wash || !text) return;

    fill.style.strokeDashoffset = String(RING_CIRCUMFERENCE * (1 - clamped));
    dock.style.opacity = String(Math.min(1, clamped * 1.35));
    dock.style.transform = `translate3d(-50%,${18 * (1 - clamped)}px,0)`;
    wash.style.opacity = String(clamped * 0.62);

    const ready = clamped >= 0.999;
    dock.classList.toggle("ready", ready);
    text.textContent = !nextUnread
      ? t("articleView.continuousReading.end")
      : ready
        ? t("articleView.continuousReading.dockReady")
        : t("articleView.continuousReading.nextUnread");
  };

  const paint = () => {
    const surface = surfaceRef.current;
    const nextPage = nextPageRef.current;
    const layer = layerRef.current;
    if (!surface || !nextPage || !layer) return;

    const raw = rawRef.current;
    const progress = Math.min(1, raw / CONTINUOUS_PULL_TRIGGER);
    const elastic = continuousRubberBand(Math.min(raw, CONTINUOUS_PULL_TRIGGER));
    const extra = Math.max(0, raw - CONTINUOUS_PAGE_START);
    const extraRatio = Math.min(1, extra / CONTINUOUS_MAX_EXTRA);

    surface.style.transform = `translate3d(0,${-elastic}px,0)`;

    if (extra > 0 && nextUnread) {
      const y = layer.clientHeight * (1 - extraRatio);
      nextPage.style.transform = `translate3d(0,${y}px,0)`;
    } else {
      nextPage.style.transform = "translate3d(0,100%,0)";
    }

    setProgress(progress);
  };

  const schedulePaint = () => {
    needsPaintRef.current = true;
    if (paintRafRef.current) return;
    paintRafRef.current = requestAnimationFrame(() => {
      paintRafRef.current = 0;
      if (!needsPaintRef.current) return;
      needsPaintRef.current = false;
      paint();
    });
  };

  const spring = ({ from, to, velocity = 0, stiffness = 240, damping = 32, onUpdate, onDone }) => {
    stopSpring();

    let x = from;
    let v = velocity;
    let last = performance.now();

    const frame = (now) => {
      let dt = Math.min(0.028, Math.max(0, (now - last) / 1000));
      last = now;
      dt *= CONTINUOUS_MOTION_SPEED;

      const acceleration = -stiffness * (x - to) - damping * v;
      v += acceleration * dt;
      x += v * dt;
      onUpdate(x, v);

      if (Math.abs(v) < 0.55 && Math.abs(x - to) < 0.55) {
        onUpdate(to, 0);
        springRafRef.current = 0;
        onDone?.();
        return;
      }
      springRafRef.current = requestAnimationFrame(frame);
    };

    springRafRef.current = requestAnimationFrame(frame);
  };

  const clearPullVisuals = () => {
    rawRef.current = 0;
    pullingRef.current = false;
    const surface = surfaceRef.current;
    const nextPage = nextPageRef.current;
    const wash = washRef.current;

    if (surface) surface.style.transform = "";
    if (nextPage) nextPage.style.transform = "translate3d(0,100%,0)";
    if (wash) wash.style.opacity = "0";
    setProgress(0);
  };

  const cancelPull = () => {
    const startRaw = rawRef.current;
    rawRef.current = 0;

    const startElastic = continuousRubberBand(
      Math.min(startRaw, CONTINUOUS_PULL_TRIGGER),
    );
    const startExtra = Math.min(
      1,
      Math.max(0, startRaw - CONTINUOUS_PAGE_START) / CONTINUOUS_MAX_EXTRA,
    );

    spring({
      from: startElastic,
      to: 0,
      velocity: Math.max(-240, Math.min(240, -velocityRef.current * 92)),
      stiffness: 355,
      damping: 40,
      onUpdate: (y) => {
        const safe = Math.max(0, y);
        const surface = surfaceRef.current;
        const nextPage = nextPageRef.current;
        const layer = layerRef.current;

        if (surface) surface.style.transform = `translate3d(0,${-safe}px,0)`;
        setProgress(
          Math.min(
            1,
            safe / Math.max(1, continuousRubberBand(CONTINUOUS_PULL_TRIGGER)),
          ),
        );

        if (startExtra > 0 && nextPage && layer) {
          const y2 = layer.clientHeight * (1 - startExtra * Math.min(1, safe / Math.max(1, startElastic)));
          nextPage.style.transform = `translate3d(0,${y2}px,0)`;
        }
      },
      onDone: clearPullVisuals,
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

  const finishHandoff = () => {
    if (!transitionLockedRef.current) return;

    const surface = surfaceRef.current;
    const nextPage = nextPageRef.current;
    const wash = washRef.current;
    const viewport = scrollAreaRef.current;

    if (surface) surface.style.transform = "";
    if (viewport) viewport.scrollTop = 0;
    if (nextPage) nextPage.style.transform = "translate3d(0,100%,0)";
    if (wash) wash.style.opacity = "0";

    rawRef.current = 0;
    pullingRef.current = false;
    transitionLockedRef.current = false;
    handoffTargetRef.current = null;
    setProgress(0);
    onTransitionStateChange?.(false);

    const toast = toastRef.current;
    if (toast) {
      toast.classList.add("show");
      setTimeout(() => toast.classList.remove("show"), 650);
    }
  };

  const commit = () => {
    if (!nextUnread || transitionLockedRef.current) {
      cancelPull();
      return;
    }

    const nextPage = nextPageRef.current;
    const layer = layerRef.current;
    const surface = surfaceRef.current;
    if (!nextPage || !layer || !surface) {
      cancelPull();
      return;
    }

    transitionLockedRef.current = true;
    pullingRef.current = false;
    handoffTargetRef.current = String(nextUnread.id);
    onTransitionStateChange?.(true);

    const extra = Math.max(0, rawRef.current - CONTINUOUS_PAGE_START);
    const ratio = Math.min(1, extra / CONTINUOUS_MAX_EXTRA);
    const fromY = layer.clientHeight * (1 - ratio);
    const preparedPromise = resolvePreparedArticle(nextUnread);

    spring({
      from: fromY,
      to: 0,
      velocity: Math.max(
        -760,
        Math.min(-150, -velocityRef.current * 220 - 160),
      ),
      stiffness: 245,
      damping: 35,
      onUpdate: (y) => {
        nextPage.style.transform = `translate3d(0,${Math.max(0, y)}px,0)`;
        surface.style.transform = `translate3d(0,${-continuousRubberBand(CONTINUOUS_PULL_TRIGGER)}px,0)`;
      },
      onDone: async () => {
        const prepared = await preparedPromise;
        activeArticle.set(prepared ?? nextUnread);

        const basePath = (window.location.hash.slice(1).split("?")[0] || "/")
          .split("/article/")[0];
        navigate(`${basePath}/article/${nextUnread.id}`);

        if (nextUnread.status !== "read") void handleMarkRead(nextUnread);
      },
    });
  };

  useEffect(() => {
    if (
      transitionLockedRef.current &&
      handoffTargetRef.current &&
      String(articleId) === handoffTargetRef.current
    ) {
      requestAnimationFrame(() => {
        requestAnimationFrame(finishHandoff);
      });
    }
  }, [articleId]);

  useEffect(() => {
    if (!enabled) return undefined;
    const viewport = scrollAreaRef.current;
    if (!viewport) return undefined;

    const syncPersistentChromeInset = () => {
      const layer = layerRef.current;
      if (!layer) return;
      const toolbar = document.querySelector(".action-buttons");
      const toolbarBottom = toolbar?.getBoundingClientRect?.().bottom ?? 0;
      layer.style.top = `${Math.max(0, Math.round(toolbarBottom))}px`;
    };

    const atBottom = () =>
      viewport.scrollTop + viewport.clientHeight >= viewport.scrollHeight - 2;

    const blockedTarget = (target) =>
      target instanceof Element && Boolean(target.closest(INTERACTIVE_SELECTOR));

    const consume = (move) => {
      if (transitionLockedRef.current) return;

      if (move > 0) {
        if (!pullingRef.current) {
          if (!atBottom()) return;
          pullingRef.current = true;
        }
        rawRef.current = Math.min(
          CONTINUOUS_PULL_TRIGGER + CONTINUOUS_MAX_EXTRA,
          rawRef.current + move * 0.86,
        );
      } else if (pullingRef.current) {
        const down = -move;
        rawRef.current = Math.max(0, rawRef.current - down);
        if (rawRef.current <= 0) {
          pullingRef.current = false;
          clearPullVisuals();
        }
      }

      if (pullingRef.current) schedulePaint();
    };

    const onTouchStart = (event) => {
      if (
        transitionLockedRef.current ||
        galleryActive ||
        event.touches.length !== 1 ||
        blockedTarget(event.target)
      ) {
        return;
      }

      stopSpring();
      syncPersistentChromeInset();
      touchingRef.current = true;
      lastYRef.current = event.touches[0].clientY;
      lastTRef.current = performance.now();
      velocityRef.current = 0;
    };

    const onTouchMove = (event) => {
      if (
        !touchingRef.current ||
        transitionLockedRef.current ||
        event.touches.length !== 1
      ) {
        return;
      }

      const y = event.touches[0].clientY;
      const now = performance.now();
      const move = lastYRef.current - y;
      const dt = Math.max(1, now - lastTRef.current);
      const instant = move / dt;
      velocityRef.current = velocityRef.current * 0.58 + instant * 0.42;

      lastYRef.current = y;
      lastTRef.current = now;

      const wasPulling = pullingRef.current;
      consume(move);

      if ((wasPulling || pullingRef.current) && event.cancelable) {
        event.preventDefault();
      }
    };

    const onTouchEnd = () => {
      if (!touchingRef.current || transitionLockedRef.current) return;
      touchingRef.current = false;

      if (rawRef.current >= CONTINUOUS_PULL_TRIGGER) commit();
      else if (rawRef.current > 0) cancelPull();
    };

    const onTouchCancel = () => {
      touchingRef.current = false;
      if (rawRef.current > 0 && !transitionLockedRef.current) cancelPull();
    };

    syncPersistentChromeInset();
    globalThis.window?.addEventListener("resize", syncPersistentChromeInset);
    viewport.addEventListener("touchstart", onTouchStart, { passive: true });
    viewport.addEventListener("touchmove", onTouchMove, { passive: false });
    viewport.addEventListener("touchend", onTouchEnd, { passive: true });
    viewport.addEventListener("touchcancel", onTouchCancel, { passive: true });

    return () => {
      globalThis.window?.removeEventListener("resize", syncPersistentChromeInset);
      viewport.removeEventListener("touchstart", onTouchStart);
      viewport.removeEventListener("touchmove", onTouchMove);
      viewport.removeEventListener("touchend", onTouchEnd);
      viewport.removeEventListener("touchcancel", onTouchCancel);
      stopSpring();
      if (paintRafRef.current) cancelAnimationFrame(paintRafRef.current);
      if (!transitionLockedRef.current) clearPullVisuals();
    };
  }, [enabled, galleryActive, nextUnread?.id]);

  if (!enabled || typeof document === "undefined") return null;

  return createPortal(
    <div ref={layerRef} className="nextflux-continuous-layer" aria-hidden="true">
      <section ref={nextPageRef} className="nextflux-continuous-next-page">
        {visibleNextArticle ? (
          <ArticlePageContent
            article={visibleNextArticle}
            passive
            className="nextflux-continuous-next-page-inner"
          />
        ) : (
          <div className="nextflux-continuous-next-empty">
            {t("articleView.continuousReading.end")}
          </div>
        )}
      </section>

      <div ref={washRef} className="nextflux-continuous-bottom-wash" />

      <div ref={dockRef} className="nextflux-continuous-progress-dock">
        <div className="nextflux-continuous-progress-button">
          <svg
            className="nextflux-continuous-progress-svg"
            viewBox="0 0 48 48"
            aria-hidden="true"
          >
            <circle
              className="nextflux-continuous-progress-track"
              cx="24"
              cy="24"
              r={RING_RADIUS}
            />
            <circle
              ref={fillRef}
              className="nextflux-continuous-progress-fill"
              cx="24"
              cy="24"
              r={RING_RADIUS}
            />
          </svg>

          <div className="nextflux-continuous-progress-icon" aria-hidden="true">
            <svg viewBox="0 0 18 18">
              <g className="nextflux-continuous-arrow-glyph">
                <path
                  className="nextflux-continuous-arrow-stem"
                  d="M9 14.2V4.9"
                />
                <path
                  className="nextflux-continuous-arrow-head"
                  d="M5.6 8.1L9 4.7l3.4 3.4"
                />
              </g>
              <path
                className="nextflux-continuous-ready-check"
                d="M4.4 9.4l2.8 2.8 6.3-6.4"
              />
            </svg>
          </div>
        </div>
        <div ref={textRef} className="nextflux-continuous-progress-text">
          {t("articleView.continuousReading.nextUnread")}
        </div>
      </div>

      <div ref={toastRef} className="nextflux-continuous-toast">
        {t("articleView.continuousReading.nextUnread")}
      </div>
    </div>,
    document.body,
  );
}
