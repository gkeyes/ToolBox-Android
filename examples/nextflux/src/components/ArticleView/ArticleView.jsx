import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "@nanostores/react";
import { PhotoProvider } from "react-photo-view";
import "react-photo-view/dist/react-photo-view.css";
import "./ArticleView.css";
import "@/reading/reading.css";
import "@/reading/typography.css";
import ActionButtons from "@/components/ArticleView/components/ActionButtons.jsx";
import ArticleHeader from "@/components/ArticleView/components/ArticleHeader.jsx";
import {
  activeArticle,
  articleContentRevision,
  imageGalleryActive,
  getAcknowledgedArticleState,
} from "@/stores/articlesStore.js";
import { Separator, ScrollShadow } from "@heroui/react";
import EmptyPlaceholder from "@/components/ArticleList/components/EmptyPlaceholder";
import { getFontSizeClass } from "@/lib/utils";
import { settingsState } from "@/stores/settingsStore";
import { AnimatePresence, LayoutGroup, motion, MotionConfig } from "framer-motion";
import { currentThemeMode, themeState } from "@/stores/themeStore.js";
import ProgressiveArticle from "@/components/ArticleView/components/ProgressiveArticle.jsx";
import ContinuousNextUnread from "@/components/ArticleView/components/ContinuousNextUnread.jsx";
import { cn } from "@/lib/utils.js";
import { getArticleById } from "@/db/storage";
import Attachments from "@/components/ArticleView/components/Attachments.jsx";
import AISummary from "@/components/ArticleView/components/AISummary.jsx";
import Iframe from "@/components/ArticleView/components/Iframe.jsx";
import { useIsMobile } from "@/hooks/use-mobile";
import { createArticleScrollReset, startArticleRead } from "@/lib/articleReadingState.js";
import { toast } from "sonner";

const ArticleView = () => {
  const { articleId } = useParams();
  const [error, setError] = useState(null);
  const $activeArticle = useStore(activeArticle);
  const revisionKeys = useMemo(() => [String(articleId)], [articleId]);
  const revisions = useStore(articleContentRevision, { keys: revisionKeys });
  const contentRevision = revisions[String(articleId)];
  const {
    lineHeight,
    fontSize,
    maxWidth,
    alignJustify,
    fontFamily,
    reduceMotion,
    floatingSidebar,
  } = useStore(settingsState);
  const { lightTheme } = useStore(themeState);
  const $currentThemeMode = useStore(currentThemeMode);
  const scrollAreaRef = useRef(null);
  const articleSurfaceRef = useRef(null);
  const { isMedium } = useIsMobile();
  const displayedArticle = String($activeArticle?.id) === articleId ? $activeArticle : null;
  const isArticleVisible = Boolean(displayedArticle) && !error;
  const resetScroll = useMemo(() => createArticleScrollReset(), []);

  const isStoneTheme = () => {
    return lightTheme === "stone" && $currentThemeMode === "light";
  };

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

  useEffect(() => () => { imageGalleryActive.set(false); }, [articleId, isArticleVisible]);

  const mediaEnclosures = useMemo(
    () => displayedArticle?.enclosures?.filter((enclosure) => /^(audio|video)\//.test(enclosure.mime_type || "")) || [],
    [displayedArticle?.enclosures],
  );

  const navigate = useNavigate();
  const sharedLayoutId = displayedArticle ? `nextflux-article-${displayedArticle.id}` : undefined;

  return (
    <MotionConfig reducedMotion={reduceMotion ? "always" : "never"}>
      <LayoutGroup id="nextflux-article-reading">
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

                <div
                  ref={articleSurfaceRef}
                  className="nextflux-continuous-current-surface article-view-content px-5 pt-5 pb-20 w-full mx-auto"
                  style={{
                    maxWidth: `${maxWidth}ch`,
                    fontFamily,
                  }}
                >
                  <ArticleHeader
                    article={displayedArticle}
                    layoutId={sharedLayoutId}
                    onFeedClick={() => navigate(`/feed/${displayedArticle?.feed?.id ?? displayedArticle?.feedId ?? displayedArticle?.feed_id}`)}
                  />
                  <Separator className="my-4" />

                  <AnimatePresence mode="sync" initial={false}>
                    <motion.div
                      key={articleId}
                      className="nextflux-article-fade-through"
                      initial={reduceMotion ? {} : { opacity: 0, y: 12, scale: 0.995 }}
                      animate={
                        reduceMotion
                          ? {}
                          : {
                              opacity: 1,
                              y: 0,
                              scale: 1,
                              transition: {
                                opacity: { delay: 0.08, duration: 0.22, ease: [0.2, 0, 0, 1] },
                                y: { delay: 0.06, duration: 0.28, ease: [0.2, 0, 0, 1] },
                                scale: { delay: 0.06, duration: 0.28, ease: [0.2, 0, 0, 1] },
                              },
                            }
                      }
                      exit={
                        reduceMotion
                          ? {}
                          : {
                              opacity: 0,
                              y: -6,
                              scale: 0.997,
                              transition: { duration: 0.16, ease: [0.4, 0, 1, 1] },
                            }
                      }
                    >
                      <AISummary articleId={displayedArticle.id} />
                      {mediaEnclosures.map((enclosure) => (
                        <Iframe
                          key={enclosure.url}
                          domNode={{
                            name: enclosure.mime_type.startsWith("audio/") ? "audio" : "video",
                            attribs: { src: enclosure.url },
                          }}
                        />
                      ))}
                      <PhotoProvider
                        bannerVisible={true}
                        onVisibleChange={(visible) => imageGalleryActive.set(visible)}
                        maskOpacity={0.8}
                        loop={false}
                        speed={() => 300}
                      >
                        <div
                          className={cn(
                            "article-content prose dark:prose-invert max-w-none",
                            "prose-pre:rounded-lg prose-pre:shadow-small",
                            "prose-h1:text-[1.5em] prose-h2:text-[1.25em] prose-h3:text-[1.125em] prose-h4:text-[1em]",
                            getFontSizeClass(fontSize),
                            isStoneTheme() ? "prose-stone" : "",
                          )}
                          style={{
                            lineHeight: lineHeight + "em",
                            textAlign: alignJustify ? "justify" : "left",
                          }}
                        >
                          <ProgressiveArticle
                            articleId={displayedArticle.id}
                            html={displayedArticle.content}
                            baseUrl={displayedArticle.url}
                            title={displayedArticle.titleText ?? displayedArticle.title}
                            shownOriginal={Boolean(displayedArticle.shownOriginal)}
                          />
                          <Attachments article={displayedArticle} />
                        </div>
                      </PhotoProvider>
                    </motion.div>
                  </AnimatePresence>
                </div>

                <ContinuousNextUnread
                  articleId={articleId}
                  scrollAreaRef={scrollAreaRef}
                  surfaceRef={articleSurfaceRef}
                  enabled={isMedium && isArticleVisible}
                />
              </ScrollShadow>
            )}
          </motion.div>
        </AnimatePresence>
      </LayoutGroup>
    </MotionConfig>
  );
};

export default memo(ArticleView);
