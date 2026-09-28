import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { useStore } from "@nanostores/react";
import "react-photo-view/dist/react-photo-view.css";
import "./ArticleView.css";
import "@/reading/reading.css";
import "@/reading/typography.css";
import ActionButtons from "@/components/ArticleView/components/ActionButtons.jsx";
import ArticlePageContent from "@/components/ArticleView/components/ArticlePageContent.jsx";
import ContinuousNextUnread from "@/components/ArticleView/components/ContinuousNextUnread.jsx";
import {
  activeArticle,
  articleContentRevision,
  imageGalleryActive,
  getAcknowledgedArticleState,
} from "@/stores/articlesStore.js";
import { ScrollShadow } from "@heroui/react";
import EmptyPlaceholder from "@/components/ArticleList/components/EmptyPlaceholder";
import { settingsState } from "@/stores/settingsStore";
import { AnimatePresence, motion, MotionConfig } from "framer-motion";
import { cn } from "@/lib/utils.js";
import { getArticleById } from "@/db/storage";
import { useIsMobile } from "@/hooks/use-mobile";
import { createArticleScrollReset, startArticleRead } from "@/lib/articleReadingState.js";
import { toast } from "sonner";

const ArticleView = () => {
  const { articleId } = useParams();
  const [error, setError] = useState(null);
  const [continuousHandoff, setContinuousHandoff] = useState(false);
  const $activeArticle = useStore(activeArticle);
  const revisionKeys = useMemo(() => [String(articleId)], [articleId]);
  const revisions = useStore(articleContentRevision, { keys: revisionKeys });
  const contentRevision = revisions[String(articleId)];
  const { reduceMotion, floatingSidebar } = useStore(settingsState);
  const scrollAreaRef = useRef(null);
  const articleSurfaceRef = useRef(null);
  const { isMedium } = useIsMobile();
  const displayedArticle = String($activeArticle?.id) === articleId ? $activeArticle : null;
  const isArticleVisible = Boolean(displayedArticle) && !error;
  const resetScroll = useMemo(() => createArticleScrollReset(), []);

  useLayoutEffect(() => {
    resetScroll(scrollAreaRef.current, displayedArticle?.id ?? null);
  }, [displayedArticle?.id, resetScroll]);

  useEffect(() => {
    setError(null);
    if (!articleId) {
      activeArticle.set(null);
      return;
    }
    const request = startArticleRead(articleId, {
      load: getArticleById,
      getCurrent: () => activeArticle.get(),
      getAcknowledgedState: getAcknowledgedArticleState,
      publish: (article) => activeArticle.set(article),
      notFound: () => {
        activeArticle.set(null);
        setError("请选择要阅读的文章");
      },
      onError: (err) => {
        const message = err.message || "加载文章失败，请刷新后重试。";
        setError(message);
        if (err.code !== "ACCOUNT_CHANGED") toast.error(message);
      },
    });
    return () => request.cancel();
  }, [articleId, contentRevision]);

  useEffect(() => () => {
    imageGalleryActive.set(false);
    setContinuousHandoff(false);
  }, [articleId, isArticleVisible]);

  return (
    <MotionConfig reducedMotion={reduceMotion ? "always" : "never"}>
      <AnimatePresence mode={isMedium ? "wait" : "popLayout"} initial={false}>
        <motion.div
          key={articleId ? "content" : "empty"}
          className={cn(
            "min-w-0 flex-1 p-0 h-screen fixed md:static inset-0 z-20",
            !articleId ? "hidden md:flex md:flex-1" : "",
            floatingSidebar ? "" : "md:pr-2 md:py-2",
          )}
          initial={
            articleId
              ? { opacity: 1, x: "100vw" }
              : { opacity: 0, x: 0, scale: 0.8 }
          }
          animate={{ opacity: 1, x: 0, scale: 1 }}
          exit={
            !articleId && isMedium
              ? false
              : articleId
                ? { opacity: 1, x: "100vw", scale: 1 }
                : { opacity: 0, x: 0, scale: 0.8 }
          }
          transition={{
            duration: 0.5,
            type: "spring",
            bounce: 0,
            ease: "easeInOut",
          }}
        >
          {!isArticleVisible ? (
            <EmptyPlaceholder />
          ) : (
            <ScrollShadow
              ref={scrollAreaRef}
              isEnabled={false}
              className={cn(
                "article-scroll-area h-full bg-background md:bg-transparent relative",
                floatingSidebar
                  ? "md:bg-transparent"
                  : "md:bg-overlay md:shadow-custom md:rounded-2xl",
              )}
            >
              <ActionButtons />

              <AnimatePresence mode="wait" initial={false}>
                <motion.div
                  key={articleId}
                  initial={
                    continuousHandoff || reduceMotion
                      ? false
                      : { opacity: 0, y: 10, scale: 0.997 }
                  }
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={
                    continuousHandoff || reduceMotion
                      ? { opacity: 1, y: 0, scale: 1 }
                      : { opacity: 0, y: -5, scale: 0.998 }
                  }
                  transition={
                    continuousHandoff || reduceMotion
                      ? { duration: 0 }
                      : {
                          opacity: { duration: 0.18, ease: [0.2, 0, 0, 1] },
                          y: { duration: 0.24, ease: [0.2, 0, 0, 1] },
                          scale: { duration: 0.24, ease: [0.2, 0, 0, 1] },
                        }
                  }
                >
                  <ArticlePageContent
                    ref={articleSurfaceRef}
                    article={displayedArticle}
                    className="nextflux-continuous-current-surface"
                  />
                </motion.div>
              </AnimatePresence>

              <ContinuousNextUnread
                articleId={articleId}
                scrollAreaRef={scrollAreaRef}
                surfaceRef={articleSurfaceRef}
                enabled={isMedium && isArticleVisible}
                onTransitionStateChange={setContinuousHandoff}
              />
            </ScrollShadow>
          )}
        </motion.div>
      </AnimatePresence>
    </MotionConfig>
  );
};

export default memo(ArticleView);
