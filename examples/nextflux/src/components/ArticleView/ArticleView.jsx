import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useStore } from "@nanostores/react";
import "react-photo-view/dist/react-photo-view.css";
import "./ArticleView.css";
import "@/reading/reading.css";
import "@/reading/typography.css";
import ActionButtons from "@/components/ArticleView/components/ActionButtons.jsx";
import ArticlePageContent from "@/components/ArticleView/components/ArticlePageContent.jsx";
import ContinuousNextUnread from "@/components/ArticleView/components/ContinuousNextUnread.jsx";
import { activeArticle, articleContentRevision, imageGalleryActive, getAcknowledgedArticleState } from "@/stores/articlesStore.js";
import { ScrollShadow } from "@heroui/react";
import EmptyPlaceholder from "@/components/ArticleList/components/EmptyPlaceholder";
import { settingsState } from "@/stores/settingsStore";
import { cn } from "@/lib/utils.js";
import { getArticleById } from "@/db/storage";
import { useIsMobile } from "@/hooks/use-mobile";
import { useArticleSwipeBack } from "@/hooks/useArticleSwipeBack.js";
import {
  NAVIGATION_STATES,
  animateNavigationPush,
  animateNavigationSettle,
  applyNavigationVisual,
  clearNavigationVisual,
  createNavigationTransitionMachine,
  deferAfterPaint,
  prepareNavigationPush,
  restNavigationVisual,
} from "@/motion/navigationTransition.mjs";
import { createArticleScrollReset, startArticleRead } from "@/lib/articleReadingState.js";
import { toast } from "sonner";
import { speechController } from "@/toolbox/speech/speechController.js";

const ArticleView = () => {
  const { articleId } = useParams();
  const navigate = useNavigate();
  const [error, setError] = useState(null);
  const [continuousHandoff, setContinuousHandoff] = useState(false);
  const [pageShown, setPageShown] = useState(Boolean(articleId));
  const [pageMoving, setPageMoving] = useState(Boolean(articleId));
  const [readingPaused, setReadingPaused] = useState(false);
  const pageRef = useRef(null);
  const lastArticleRef = useRef(null);
  const wasOpenRef = useRef(false);
  const shellMovingRef = useRef(false);
  const shellMotionCancelRef = useRef(null);
  const deferredResumeCancelRef = useRef(null);
  const navigationMachineRef = useRef(null);
  if (!navigationMachineRef.current) {
    navigationMachineRef.current = createNavigationTransitionMachine({
      onChange: (state) => {
        const page = pageRef.current;
        if (page) page.dataset.navigationState = state;
      },
    });
  }

  const $activeArticle = useStore(activeArticle);
  const revisionKeys = useMemo(() => [String(articleId)], [articleId]);
  const revisions = useStore(articleContentRevision, { keys: revisionKeys });
  const contentRevision = revisions[String(articleId)];
  const { reduceMotion, floatingSidebar } = useStore(settingsState);
  const scrollAreaRef = useRef(null);
  const articleSurfaceRef = useRef(null);
  const { isMedium } = useIsMobile();
  const displayedArticle = String($activeArticle?.id) === articleId ? $activeArticle : null;
  if (displayedArticle) lastArticleRef.current = displayedArticle;
  const presentedArticle = displayedArticle ??
    (continuousHandoff || (!articleId && pageShown) ? lastArticleRef.current : null);
  const isArticleVisible = Boolean(presentedArticle) && !error;
  const resetScroll = useMemo(() => createArticleScrollReset(), []);
  const isOpen = Boolean(articleId);

  const setNavigationState = useCallback((next, force = false) => {
    const machine = navigationMachineRef.current;
    if (!machine.transition(next) && force) machine.reset(next);
  }, []);

  const cancelDeferredResume = useCallback(() => {
    deferredResumeCancelRef.current?.();
    deferredResumeCancelRef.current = null;
  }, []);

  const resumeReadingAfterPaint = useCallback(() => {
    cancelDeferredResume();
    deferredResumeCancelRef.current = deferAfterPaint(() => {
      deferredResumeCancelRef.current = null;
      if (navigationMachineRef.current.state === NAVIGATION_STATES.IDLE) setReadingPaused(false);
    }, 2);
  }, [cancelDeferredResume]);

  useLayoutEffect(() => {
    resetScroll(scrollAreaRef.current, displayedArticle?.id ?? null);
  }, [displayedArticle?.id, resetScroll]);

  const startEntrance = useCallback(() => {
    const page = pageRef.current;
    const machine = navigationMachineRef.current;
    if (!page || !isMedium || !isOpen || machine.state !== NAVIGATION_STATES.PREPARING) return false;
    const width = page.clientWidth || window.innerWidth;
    if (!width) return false;
    const from = new DOMMatrix(getComputedStyle(page).transform).m41 || width;

    shellMotionCancelRef.current?.();
    shellMotionCancelRef.current = null;
    cancelDeferredResume();
    setNavigationState(NAVIGATION_STATES.ENTERING, true);
    shellMovingRef.current = true;
    setPageShown(true);
    setPageMoving(true);
    setReadingPaused(true);
    page.dataset.readingMotion = "entrance";
    page.style.willChange = "transform";

    let completed = false;
    const cancel = animateNavigationPush(page, {
      from,
      width,
      reduceMotion,
      onDone: () => {
        if (page.dataset.readingMotion !== "entrance") return;
        completed = true;
        shellMotionCancelRef.current = null;
        shellMovingRef.current = false;
        delete page.dataset.readingMotion;
        setNavigationState(NAVIGATION_STATES.IDLE, true);
        setPageMoving(false);
        deferAfterPaint(() => { if (pageRef.current === page) page.style.willChange = ""; }, 1);
        resumeReadingAfterPaint();
      },
    });
    if (!completed) shellMotionCancelRef.current = cancel;
    return true;
  }, [cancelDeferredResume, isMedium, isOpen, reduceMotion, resumeReadingAfterPaint, setNavigationState]);

  useLayoutEffect(() => {
    const page = pageRef.current;
    const wasOpen = wasOpenRef.current;
    wasOpenRef.current = isOpen;
    if (!page) return;

    if (!isMedium) {
      shellMotionCancelRef.current?.();
      shellMotionCancelRef.current = null;
      cancelDeferredResume();
      shellMovingRef.current = false;
      page.style.transform = "";
      page.style.willChange = "";
      clearNavigationVisual(page);
      delete page.dataset.readingMotion;
      navigationMachineRef.current.reset(NAVIGATION_STATES.IDLE);
      setPageShown(isOpen);
      setPageMoving(false);
      setReadingPaused(false);
      return;
    }

    if (isOpen && !wasOpen) {
      shellMotionCancelRef.current?.();
      shellMotionCancelRef.current = null;
      cancelDeferredResume();
      shellMovingRef.current = false;
      const width = page.clientWidth || window.innerWidth;
      setPageShown(true);
      setPageMoving(true);
      setReadingPaused(false);
      setNavigationState(NAVIGATION_STATES.PREPARING, true);
      page.dataset.readingMotion = "preparing";
      page.style.willChange = "transform";
      prepareNavigationPush(page, width);
      return;
    }

    if (!isOpen && wasOpen) {
      shellMotionCancelRef.current?.();
      shellMotionCancelRef.current = null;
      cancelDeferredResume();
      const width = page.clientWidth || window.innerWidth;
      const from = new DOMMatrix(getComputedStyle(page).transform).m41;
      if (from >= width - 1) {
        clearNavigationVisual(page);
        page.style.willChange = "";
        delete page.dataset.readingMotion;
        navigationMachineRef.current.reset(NAVIGATION_STATES.IDLE);
        shellMovingRef.current = false;
        setPageShown(false);
        setPageMoving(false);
        setReadingPaused(false);
        return;
      }

      setNavigationState(NAVIGATION_STATES.EXITING, true);
      setPageShown(true);
      setPageMoving(true);
      setReadingPaused(true);
      shellMovingRef.current = true;
      page.dataset.readingMotion = "release";
      page.style.willChange = "transform";
      let completed = false;
      const cancel = animateNavigationSettle(page, {
        from,
        to: width,
        width,
        reduceMotion,
        onDone: () => {
          if (page.dataset.readingMotion !== "release") return;
          completed = true;
          shellMotionCancelRef.current = null;
          shellMovingRef.current = false;
          clearNavigationVisual(page);
          page.style.willChange = "";
          delete page.dataset.readingMotion;
          navigationMachineRef.current.reset(NAVIGATION_STATES.IDLE);
          setPageShown(false);
          setPageMoving(false);
          setReadingPaused(false);
        },
      });
      if (!completed) shellMotionCancelRef.current = cancel;
    }
  }, [cancelDeferredResume, isOpen, isMedium, reduceMotion, setNavigationState]);

  useEffect(() => {
    if (!isMedium || !isOpen) return;
    const page = pageRef.current;
    if (!page) return;

    const currentRoot = () => articleSurfaceRef.current?.querySelector?.(
      `[data-font-article-id="${String(articleId).replaceAll('"', '\\"')}"]`,
    );
    const tryStart = (event) => {
      if (event) {
        if (String(event.detail?.articleId) !== String(articleId)) return;
        const root = event.detail?.root;
        if (!root || root.closest?.(".nextflux-continuous-next-page")) return;
        if (root.closest?.(".nextflux-article-page") !== page) return;
      }
      const root = currentRoot();
      if (root?.dataset.readingReady === "true") startEntrance();
    };

    window.addEventListener("nextflux:article-ready", tryStart);
    tryStart();
    return () => window.removeEventListener("nextflux:article-ready", tryStart);
  }, [articleId, displayedArticle?.id, isMedium, isOpen, startEntrance]);

  useEffect(() => {
    if (!reduceMotion) return;
    const page = pageRef.current;
    if (!page || navigationMachineRef.current.state !== NAVIGATION_STATES.ENTERING) return;
    shellMotionCancelRef.current?.();
    shellMotionCancelRef.current = null;
    shellMovingRef.current = false;
    const width = page.clientWidth || window.innerWidth;
    restNavigationVisual(page, width);
    page.style.willChange = "";
    delete page.dataset.readingMotion;
    navigationMachineRef.current.reset(NAVIGATION_STATES.IDLE);
    setPageMoving(false);
    setReadingPaused(false);
  }, [reduceMotion]);

  const handleBack = useCallback(() => {
    const basePath = (window.location.hash.slice(1).split("?")[0] || "/").split("/article/")[0];
    navigate(basePath || "/");
  }, [navigate]);

  const handleTakeoverEntrance = useCallback(() => {
    const page = pageRef.current;
    if (!page || page.dataset.readingMotion !== "entrance") return false;
    const width = page.clientWidth || window.innerWidth;
    const current = new DOMMatrix(getComputedStyle(page).transform).m41;
    shellMotionCancelRef.current?.();
    shellMotionCancelRef.current = null;
    applyNavigationVisual(page, current, width);
    shellMovingRef.current = false;
    setNavigationState(NAVIGATION_STATES.INTERACTIVE_POP, true);
    setPageMoving(false);
    setReadingPaused(true);
    return true;
  }, [setNavigationState]);

  const handleSwipeActive = useCallback((active) => {
    if (active) {
      cancelDeferredResume();
      setReadingPaused(true);
      return;
    }
    if (navigationMachineRef.current.state === NAVIGATION_STATES.IDLE) resumeReadingAfterPaint();
  }, [cancelDeferredResume, resumeReadingAfterPaint]);

  useArticleSwipeBack({
    pageRef,
    enabled: isMedium && isOpen && isArticleVisible && !continuousHandoff,
    onBack: handleBack,
    onTakeoverEntrance: handleTakeoverEntrance,
    onActiveChange: handleSwipeActive,
    transitionMachine: navigationMachineRef.current,
    reduceMotion,
  });

  const handleContinuousState = useCallback((active) => {
    setContinuousHandoff(active);
    const page = pageRef.current;
    if (active && page) page.dataset.readingMotion = "continuous";
    else if (page?.dataset.readingMotion === "continuous") delete page.dataset.readingMotion;
  }, []);

  useEffect(() => {
    setError(null);
    if (!articleId) { activeArticle.set(null); return; }
    const request = startArticleRead(articleId, {
      load: getArticleById,
      getCurrent: () => activeArticle.get(),
      getAcknowledgedState: getAcknowledgedArticleState,
      publish: (article) => activeArticle.set(article),
      notFound: () => { activeArticle.set(null); setError("请选择要阅读的文章"); },
      onError: (err) => {
        const message = err.message || "加载文章失败，请刷新后重试。";
        setError(message);
        if (err.code !== "ACCOUNT_CHANGED") toast.error(message);
      },
    });
    return () => request.cancel();
  }, [articleId, contentRevision]);

  useEffect(() => () => {
    // Route changes must leave the new exit animation running until it finishes.
    cancelDeferredResume();
    shellMotionCancelRef.current?.();
    shellMotionCancelRef.current = null;
  }, [cancelDeferredResume]);

  useEffect(() => () => {
    imageGalleryActive.set(false);
    speechController.stop();
  }, [articleId]);

  return <>
    <div className="nextflux-transition-effect" aria-hidden="true">
      <div className="nextflux-transition-shadow" />
    </div>
    <div ref={pageRef} className={cn(
      "nextflux-article-page min-w-0 flex-1 p-0 h-dvh fixed md:static inset-0 z-20 overflow-hidden",
      !isOpen && !pageShown ? "hidden md:block" : "",
      floatingSidebar ? "" : "md:pr-2 md:py-2",
    )}>
      {!isArticleVisible ? <EmptyPlaceholder /> : <ScrollShadow ref={scrollAreaRef} isEnabled={false} className={cn(
        "article-scroll-area h-full bg-background md:bg-transparent relative",
        floatingSidebar ? "md:bg-transparent" : "md:bg-overlay md:shadow-custom md:rounded-2xl",
      )}>
        <ActionButtons />
        <ArticlePageContent
          ref={articleSurfaceRef}
          article={presentedArticle}
          readingPaused={readingPaused}
          className="nextflux-continuous-current-surface"
        />
        <ContinuousNextUnread
          articleId={articleId}
          scrollAreaRef={scrollAreaRef}
          surfaceRef={articleSurfaceRef}
          enabled={isMedium && isOpen && isArticleVisible && !pageMoving}
          reduceMotion={reduceMotion}
          onTransitionStateChange={handleContinuousState}
        />
      </ScrollShadow>}
    </div>
  </>;
};

export default memo(ArticleView);
