import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useNavigate } from "react-router-dom";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";
import { activeArticle, filteredArticles, imageGalleryActive } from "@/stores/articlesStore.js";
import { isModalOpen } from "@/stores/modalStore.js";
import { handleMarkRead } from "@/handlers/articleHandlers.js";
import { getArticleById } from "@/db/storage.js";
import ArticlePageContent from "./ArticlePageContent.jsx";
import { resolveSwipeDirection, shouldExcludeSwipeTarget } from "@/hooks/swipeGesture.js";
import { animateReadingValue } from "@/toolbox/reading-motion.mjs";
import { CONTINUOUS_MAX_EXTRA, CONTINUOUS_PULL_TRIGGER, continuousPageOffset, findNextUnreadArticle } from "@/toolbox/continuous-reading-motion.mjs";

export default function ContinuousNextUnread({ articleId, scrollAreaRef, surfaceRef, enabled, reduceMotion, onTransitionStateChange }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const articles = useStore(filteredArticles);
  const nextUnread = useMemo(() => findNextUnreadArticle(articles, articleId), [articles, articleId]);
  const [preparedArticle, setPreparedArticle] = useState(null);
  const [handoffArticle, setHandoffArticle] = useState(null);
  const layerRef = useRef(null);
  const nextPageRef = useRef(null);
  const dockRef = useRef(null);
  const textRef = useRef(null);
  const hintStateRef = useRef(null);
  const rawRef = useRef(0);
  const heightRef = useRef(0);
  const gestureRef = useRef(null);
  const transitionRef = useRef(null);
  const preparedRef = useRef({ id: null, promise: null });
  const springRef = useRef(null);
  const fadeRef = useRef(null);
  const paintRafRef = useRef(0);
  const latestRef = useRef(null);

  const visibleNextArticle = handoffArticle ??
    (String(preparedArticle?.id) === String(nextUnread?.id) ? preparedArticle : nextUnread);

  useEffect(() => {
    if (!enabled || handoffArticle) return;
    let cancelled = false;
    setPreparedArticle(null);
    if (!nextUnread?.id) {
      preparedRef.current = { id: null, promise: null };
      return;
    }
    const id = String(nextUnread.id);
    const promise = getArticleById(id).catch(() => null).then((article) => {
      if (!cancelled && article) setPreparedArticle(article);
      return article;
    });
    preparedRef.current = { id, promise };
    return () => { cancelled = true; };
  }, [enabled, nextUnread?.id, handoffArticle]);

  const cancelPaint = () => {
    if (paintRafRef.current) cancelAnimationFrame(paintRafRef.current);
    paintRafRef.current = 0;
  };
  const stopSpring = () => { springRef.current?.(); springRef.current = null; };
  const setProgress = (progress) => {
    const dock = dockRef.current;
    if (!dock) return;
    const ready = progress >= 1;
    const state = !latestRef.current?.nextUnread ? "end" : ready ? "dockReady" : "nextUnread";
    if (hintStateRef.current !== state) {
      hintStateRef.current = state;
      dock.classList.toggle("ready", ready);
      if (textRef.current) textRef.current.textContent = t(`articleView.continuousReading.${state}`);
    }
    dock.style.opacity = String(Math.min(1, progress * 2));
  };
  const paint = () => {
    const page = nextPageRef.current;
    if (page) page.style.transform = `translate3d(0,${continuousPageOffset(rawRef.current, heightRef.current)}px,0)`;
    setProgress(Math.min(1, rawRef.current / CONTINUOUS_PULL_TRIGGER));
  };
  const schedulePaint = () => {
    if (paintRafRef.current) return;
    paintRafRef.current = requestAnimationFrame(() => { paintRafRef.current = 0; latestRef.current.paint(); });
  };
  const clearVisuals = () => {
    cancelPaint();
    rawRef.current = 0;
    gestureRef.current = null;
    if (layerRef.current) layerRef.current.dataset.phase = "idle";
    if (nextPageRef.current) {
      nextPageRef.current.style.transform = "translate3d(0,100%,0)";
      nextPageRef.current.style.opacity = "";
    }
    setProgress(0);
    if (!transitionRef.current) onTransitionStateChange?.(false);
  };
  const cancelPull = () => {
    cancelPaint();
    stopSpring();
    springRef.current = animateReadingValue({
      from: rawRef.current, to: 0, reduceMotion,
      onUpdate: (raw) => { rawRef.current = raw; latestRef.current.paint(); },
      onDone: clearVisuals,
    });
  };
  const finishHandoff = () => {
    if (!transitionRef.current) return;
    transitionRef.current = null;
    fadeRef.current = null;
    clearVisuals();
    setHandoffArticle(null);
    onTransitionStateChange?.(false);
  };
  const abortHandoff = () => {
    stopSpring();
    cancelPaint();
    fadeRef.current?.cancel();
    fadeRef.current = null;
    transitionRef.current = null;
    clearVisuals();
    setHandoffArticle(null);
  };
  const revealActualPage = () => {
    if (!transitionRef.current || transitionRef.current.revealing) return;
    transitionRef.current.revealing = true;
    // Retain this exact target through the real first-screen React commit.
    // Only the bounded preview fades; no guessed two-frame readiness timeout.
    paintRafRef.current = requestAnimationFrame(() => {
      paintRafRef.current = 0;
      if (!transitionRef.current) return;
      if (reduceMotion || !nextPageRef.current?.animate) { finishHandoff(); return; }
      const fade = nextPageRef.current.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 90, fill: "forwards" });
      fadeRef.current = fade;
      fade.finished.then(() => {
        if (fadeRef.current === fade) { fade.cancel(); latestRef.current.finishHandoff(); }
      }).catch(() => {});
    });
  };
  const commit = () => {
    const article = visibleNextArticle;
    if (!article?.id || transitionRef.current) { cancelPull(); return; }
    cancelPaint();
    stopSpring();
    paint();
    const target = { id: String(article.id), sourceId: String(articleId), article, revealing: false };
    transitionRef.current = target;
    setHandoffArticle(article);
    layerRef.current.dataset.phase = "handoff";
    onTransitionStateChange?.(true);
    const prepared = preparedRef.current.id === target.id ? preparedRef.current.promise : getArticleById(target.id).catch(() => null);
    springRef.current = animateReadingValue({
      from: continuousPageOffset(rawRef.current, heightRef.current), to: 0,
      velocity: -(gestureRef.current?.velocity ?? 0) * 1000, reduceMotion,
      onUpdate: (y) => { if (nextPageRef.current) nextPageRef.current.style.transform = `translate3d(0,${y}px,0)`; },
      onDone: async () => {
        const resolved = await prepared;
        if (transitionRef.current !== target) return;
        const next = resolved ?? article;
        activeArticle.set(next);
        const basePath = (window.location.hash.slice(1).split("?")[0] || "/").split("/article/")[0];
        navigate(`${basePath === "/" ? "" : basePath}/article/${target.id}`);
        if (next.status !== "read") void handleMarkRead(next);
      },
    });
  };

  useLayoutEffect(() => {
    latestRef.current = { nextUnread, paint, clearVisuals, cancelPull, commit, finishHandoff, abortHandoff, revealActualPage };
  });

  useLayoutEffect(() => {
    const target = transitionRef.current;
    if (target && String(articleId) !== target.id && String(articleId) !== target.sourceId) {
      latestRef.current.abortHandoff();
    }
  }, [articleId]);

  useEffect(() => {
    if (!transitionRef.current || String(articleId) !== transitionRef.current.id) return;
    const checkReady = () => {
      const root = surfaceRef.current?.querySelector(".article-body");
      if (root?.dataset.fontArticleId === transitionRef.current?.id && root.dataset.readingReady === "true") {
        latestRef.current.revealActualPage();
      }
    };
    window.addEventListener("nextflux:article-ready", checkReady);
    checkReady();
    return () => window.removeEventListener("nextflux:article-ready", checkReady);
  }, [articleId, surfaceRef]);

  useEffect(() => {
    if (!enabled) return;
    const viewport = scrollAreaRef.current;
    if (!viewport) return;
    let ignoreUntilEnd = false;
    const syncInset = () => {
      const layer = layerRef.current;
      if (!layer) return;
      const bottom = viewport.querySelector(".action-buttons")?.getBoundingClientRect().bottom ?? 0;
      layer.style.top = `${Math.max(0, bottom)}px`;
      heightRef.current = layer.clientHeight;
    };
    const blocked = (event) => {
      const selection = document.getSelection();
      return event.defaultPrevented || isModalOpen.get() || imageGalleryActive.get() ||
        Boolean(selection?.rangeCount && !selection.isCollapsed);
    };
    const resetGesture = () => {
      gestureRef.current = null;
      if (!transitionRef.current && rawRef.current > 0) latestRef.current.cancelPull();
    };
    const onStart = (event) => {
      if (transitionRef.current) return;
      if (event.touches.length > 1) { ignoreUntilEnd = true; resetGesture(); return; }
      if (ignoreUntilEnd) return;
      const motion = viewport.closest(".nextflux-article-page")?.dataset.readingMotion;
      if (motion && motion !== "continuous") return;
      if (event.touches.length !== 1 || blocked(event) || shouldExcludeSwipeTarget(event.target)) { resetGesture(); return; }
      stopSpring();
      syncInset(); // Cache geometry once. Moves write transforms only.
      const touch = event.touches[0];
      gestureRef.current = { id: touch.identifier, x: touch.clientX, y: touch.clientY,
        lastY: touch.clientY, lastT: performance.now(), direction: null, velocity: 0, pulling: rawRef.current > 0 };
    };
    const onMove = (event) => {
      const gesture = gestureRef.current;
      if (!gesture || transitionRef.current) return;
      const touch = event.touches[0];
      if (event.touches.length !== 1 || touch?.identifier !== gesture.id || blocked(event)) {
        if (event.touches.length > 1) ignoreUntilEnd = true;
        resetGesture(); return;
      }
      gesture.direction ??= resolveSwipeDirection(touch.clientX - gesture.x, touch.clientY - gesture.y);
      if (gesture.direction === "horizontal") {
        if (rawRef.current > 0) latestRef.current.clearVisuals();
        return;
      }
      if (gesture.direction !== "vertical") return;
      if (!event.cancelable) { resetGesture(); return; }
      const now = performance.now();
      const move = gesture.lastY - touch.clientY;
      gesture.velocity = gesture.velocity * 0.58 + (move / Math.max(1, now - gesture.lastT)) * 0.42;
      gesture.lastY = touch.clientY;
      gesture.lastT = now;
      if (!gesture.pulling) {
        if (move <= 0 || viewport.scrollTop + viewport.clientHeight < viewport.scrollHeight - 2) return;
        gesture.pulling = true;
        layerRef.current.dataset.phase = "pulling";
        onTransitionStateChange?.(true);
      }
      rawRef.current = Math.max(0, Math.min(CONTINUOUS_PULL_TRIGGER + CONTINUOUS_MAX_EXTRA, rawRef.current + move * 0.86));
      event.preventDefault();
      schedulePaint();
    };
    const onEnd = (event) => {
      if (ignoreUntilEnd) {
        if (!event.touches.length) ignoreUntilEnd = false;
        resetGesture(); return;
      }
      const gesture = gestureRef.current;
      if (!gesture || transitionRef.current) return;
      const touch = Array.from(event.changedTouches).find((item) => item.identifier === gesture.id);
      if (event.touches.length || !touch || blocked(event)) { resetGesture(); return; }
      if (performance.now() - gesture.lastT > 80) gesture.velocity = 0;
      if (gesture.direction === "vertical" && gesture.pulling && rawRef.current >= CONTINUOUS_PULL_TRIGGER && latestRef.current.nextUnread) latestRef.current.commit();
      else if (rawRef.current > 0) latestRef.current.cancelPull();
      gestureRef.current = null;
    };
    syncInset();
    window.addEventListener("resize", syncInset);
    viewport.addEventListener("touchstart", onStart, { passive: true });
    viewport.addEventListener("touchmove", onMove, { passive: false });
    viewport.addEventListener("touchend", onEnd);
    const onCancel = () => { ignoreUntilEnd = false; resetGesture(); };
    viewport.addEventListener("touchcancel", onCancel);
    return () => {
      window.removeEventListener("resize", syncInset);
      viewport.removeEventListener("touchstart", onStart);
      viewport.removeEventListener("touchmove", onMove);
      viewport.removeEventListener("touchend", onEnd);
      viewport.removeEventListener("touchcancel", onCancel);
      abortHandoff();
    };
  }, [enabled, scrollAreaRef]);

  if (!enabled || typeof document === "undefined") return null;
  return createPortal(<div ref={layerRef} className="nextflux-continuous-layer"
    data-phase={handoffArticle ? "handoff" : rawRef.current > 0 ? "pulling" : "idle"} aria-hidden="true">
    <section ref={nextPageRef} className="nextflux-continuous-next-page">
      {visibleNextArticle && <ArticlePageContent article={visibleNextArticle} passive className="nextflux-continuous-next-page-inner" />}
    </section>
    <div ref={dockRef} className="nextflux-continuous-progress-dock">
      <span className="nextflux-continuous-progress-icon">↑</span>
      <span ref={textRef}>{t("articleView.continuousReading.nextUnread")}</span>
    </div>
  </div>, document.body);
}
