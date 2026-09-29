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
import { animateReadingValue } from "@/toolbox/reading-motion.mjs";
import { createArticleScrollReset, startArticleRead } from "@/lib/articleReadingState.js";
import { toast } from "sonner";

const ArticleView = () => {
  const { articleId } = useParams();
  const navigate = useNavigate();
  const [error, setError] = useState(null);
  const [continuousHandoff, setContinuousHandoff] = useState(false);
  const [pageShown, setPageShown] = useState(Boolean(articleId));
  const [pageMoving, setPageMoving] = useState(Boolean(articleId));
  const pageRef = useRef(null);
  const lastArticleRef = useRef(null);
  const wasOpenRef = useRef(false);
  const shellMovingRef = useRef(false);
  const shellMotionCancelRef = useRef(null);
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
  // Store publication and Router's transition may commit separately. Keep the
  // current surface mounted under the frozen preview until the target route
  // catches up; otherwise this one-frame gap destroys the handoff controller.
  const presentedArticle = displayedArticle ??
    (continuousHandoff || (!articleId && pageShown) ? lastArticleRef.current : null);
  const isArticleVisible = Boolean(presentedArticle) && !error;
  const resetScroll = useMemo(() => createArticleScrollReset(), []);
  const isOpen = Boolean(articleId);

  useLayoutEffect(() => {
    resetScroll(scrollAreaRef.current, displayedArticle?.id ?? null);
  }, [displayedArticle?.id, resetScroll]);

  useLayoutEffect(() => {
    const page = pageRef.current;
    const wasOpen = wasOpenRef.current;
    wasOpenRef.current = isOpen;
    if (!page) return;
    if (!isMedium) {
      shellMotionCancelRef.current?.();
      shellMotionCancelRef.current = null;
      shellMovingRef.current = false;
      page.style.transform = "";
      page.style.willChange = "";
      delete page.dataset.readingMotion;
      setPageShown(isOpen);
      setPageMoving(false);
      return;
    }
    if (isOpen === wasOpen && !shellMovingRef.current) return;
    const width = page.clientWidth || window.innerWidth;
    // A completed interactive swipe has already taken this shell offscreen.
    // A toolbar/system return uses the same response from the current position.
    const from = isOpen && !wasOpen ? width : new DOMMatrix(getComputedStyle(page).transform).m41;
    if (!isOpen && from >= width - 1) {
      shellMovingRef.current = false;
      setPageShown(false);
      setPageMoving(false);
      return;
    }
    setPageShown(true);
    setPageMoving(true);
    shellMovingRef.current = true;
    page.dataset.readingMotion = isOpen ? "entrance" : "release";
    page.style.willChange = "transform";
    let completed = false;
    const cancel = animateReadingValue({
      from, to: isOpen ? 0 : width, reduceMotion,
      onUpdate: (x) => { page.style.transform = `translate3d(${x}px,0,0)`; },
      onDone: () => {
        completed = true;
        shellMotionCancelRef.current = null;
        shellMovingRef.current = false;
        page.style.willChange = "";
        delete page.dataset.readingMotion;
        if (isOpen) page.style.transform = "";
        else setPageShown(false);
        setPageMoving(false);
      },
    });
    if (!completed) shellMotionCancelRef.current = cancel;
    return () => {
      if (shellMotionCancelRef.current === cancel) shellMotionCancelRef.current = null;
      cancel();
    };
  }, [isOpen, isMedium, reduceMotion]);

  const handleBack = useCallback(() => {
    const basePath = (window.location.hash.slice(1).split("?")[0] || "/").split("/article/")[0];
    navigate(basePath || "/");
  }, [navigate]);
  const handleTakeoverEntrance = useCallback(() => {
    const page = pageRef.current;
    if (!page || page.dataset.readingMotion !== "entrance") return false;
    shellMotionCancelRef.current?.();
    shellMotionCancelRef.current = null;
    shellMovingRef.current = false;
    page.style.willChange = "";
    setPageMoving(false);
    return true;
  }, []);
  useArticleSwipeBack({ pageRef, enabled: isMedium && isOpen && isArticleVisible && !continuousHandoff,
    onBack: handleBack, onTakeoverEntrance: handleTakeoverEntrance, reduceMotion });
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
  useEffect(() => () => { imageGalleryActive.set(false); }, [articleId]);

  return <div ref={pageRef} className={cn(
    "nextflux-article-page min-w-0 flex-1 p-0 h-dvh fixed md:static inset-0 z-20 overflow-hidden",
    !isOpen && !pageShown ? "hidden md:block" : "",
    floatingSidebar ? "" : "md:pr-2 md:py-2",
  )}>
    {!isArticleVisible ? <EmptyPlaceholder /> : <ScrollShadow ref={scrollAreaRef} isEnabled={false} className={cn(
      "article-scroll-area h-full bg-background md:bg-transparent relative",
      floatingSidebar ? "md:bg-transparent" : "md:bg-overlay md:shadow-custom md:rounded-2xl",
    )}>
      <ActionButtons />
      <ArticlePageContent ref={articleSurfaceRef} article={presentedArticle} readingPaused={pageMoving}
        className="nextflux-continuous-current-surface" />
      <ContinuousNextUnread articleId={articleId} scrollAreaRef={scrollAreaRef} surfaceRef={articleSurfaceRef}
        enabled={isMedium && isOpen && isArticleVisible && !pageMoving} reduceMotion={reduceMotion}
        onTransitionStateChange={handleContinuousState} />
    </ScrollShadow>}
  </div>;
};
export default memo(ArticleView);
