import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "@nanostores/react";
import { PhotoProvider } from "react-photo-view";
import "react-photo-view/dist/react-photo-view.css";
import "./ArticleView.css";
import ActionButtons from "@/components/ArticleView/components/ActionButtons.jsx";
import { generateReadableDate } from "@/lib/format.js";
import {
  activeArticle,
  articleContentRevision,
  imageGalleryActive,
} from "@/stores/articlesStore.js";
import { Separator, ScrollShadow } from "@heroui/react";
import EmptyPlaceholder from "@/components/ArticleList/components/EmptyPlaceholder";
import { getFontSizeClass } from "@/lib/utils";
import { safeContentUrl } from "@/toolbox/content.js";
import { showLinkActions } from "@/toolbox/actions.js";
import { settingsState } from "@/stores/settingsStore";
import { AnimatePresence, motion, MotionConfig } from "framer-motion";
import { currentThemeMode, themeState } from "@/stores/themeStore.js";
import ProgressiveArticle from "@/components/ArticleView/components/ProgressiveArticle.jsx";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils.js";
import FeedIcon from "@/components/ui/FeedIcon.jsx";
import { getArticleById } from "@/db/storage";
import Attachments from "@/components/ArticleView/components/Attachments.jsx";
import AISummary from "@/components/ArticleView/components/AISummary.jsx";
import Iframe from "@/components/ArticleView/components/Iframe.jsx";
import { useIsMobile } from "@/hooks/use-mobile";
import { createArticleScrollReset, startArticleRead } from "@/lib/articleReadingState.js";
import { toast } from "sonner";

const ArticleView = () => {
  const { t } = useTranslation();
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
    titleFontSize,
    titleAlignType,
    reduceMotion,
    floatingSidebar,
  } = useStore(settingsState);
  const { lightTheme } = useStore(themeState);
  const $currentThemeMode = useStore(currentThemeMode);
  const scrollAreaRef = useRef(null);
  const { isMedium } = useIsMobile();
  const displayedArticle = String($activeArticle?.id) === articleId ? $activeArticle : null;
  const isArticleVisible = Boolean(displayedArticle) && !error;
  const resetScroll = useMemo(() => createArticleScrollReset(), []);
  // 判断当前是否实际使用了stone主题
  const isStoneTheme = () => {
    return lightTheme === "stone" && $currentThemeMode === "light";
  };

  // Reset before the first paint of the newly loaded article. State changes,
  // font settings and original-content toggles never schedule a later reset.
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

  const mediaEnclosures = useMemo(() => displayedArticle?.enclosures?.filter((enclosure) => /^(audio|video)\//.test(enclosure.mime_type || "")) || [], [displayedArticle?.enclosures]);

  const navigate = useNavigate();

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
                  initial={reduceMotion ? {} : { y: 50, opacity: 0 }}
                  animate={{
                    y: 0,
                    opacity: 1,
                    transition: {
                      opacity: { delay: 0.05 },
                    },
                  }}
                  exit={reduceMotion ? {} : { y: -50, opacity: 0 }}
                  transition={{ bounce: 0, ease: "easeInOut" }}
                  className="article-view-content px-5 pt-5 pb-20 w-full mx-auto"
                  style={{
                    maxWidth: `${maxWidth}ch`,
                    fontFamily: fontFamily,
                  }}
                >
                  <header
                    className="article-header"
                    style={{ textAlign: titleAlignType }}
                  >
                    <button
                      type="button"
                      onClick={() =>
                        navigate(`/feed/${$activeArticle?.feed?.id}`)
                      }
                      onKeyDown={(e) => {
                        if (e.key === "Enter" || e.key === " ")
                          navigate(`/feed/${$activeArticle?.feed?.id}`);
                      }}
                      className={cn(
                        "text-muted text-sm flex items-center gap-1 hover:cursor-pointer focus:outline-none",
                        titleAlignType === "center" ? "justify-center" : "",
                      )}
                    >
                      <FeedIcon feedId={$activeArticle?.feed?.id} />
                      {$activeArticle?.feed?.title}
                    </button>
                    <h1
                      data-font-block=""
                      className="article-title font-semibold my-2 hover:cursor-pointer leading-tight"
                      style={{
                        fontSize: `${titleFontSize * fontSize}px`,
                      }}
                    >
                      <a
                        href={safeContentUrl($activeArticle?.url) || undefined}
                        onClick={(event) => { event.preventDefault(); showLinkActions($activeArticle?.url); }}
                        rel="noopener noreferrer"
                        target="_blank"
                      >
                        {displayedArticle.titleText ?? displayedArticle.title}
                      </a>
                    </h1>
                    <div className="text-muted opacity-60 text-sm">
                      <time
                        dateTime={$activeArticle?.published_at}
                        key={t.language}
                      >
                        {generateReadableDate($activeArticle?.published_at)}
                      </time>
                    </div>
                  </header>
                  <Separator className="my-4" />
                  <AISummary articleId={$activeArticle?.id} />
                  {mediaEnclosures.map((enclosure) => <Iframe key={enclosure.url} domNode={{ name: enclosure.mime_type.startsWith("audio/") ? "audio" : "video", attribs: { src: enclosure.url } }} />)}
                  <PhotoProvider
                    bannerVisible={true}
                    onVisibleChange={(visible) =>
                      imageGalleryActive.set(visible)
                    }
                    maskOpacity={0.8}
                    loop={false}
                    speed={() => 300}
                  >
                    <div
                      onClick={(event) => {
                        const anchor = event.target.closest?.("a[href]");
                        if (anchor && event.currentTarget.contains(anchor)) {
                          if (anchor.hasAttribute("data-reading-local-anchor")) return;
                          event.preventDefault();
                          showLinkActions(anchor.getAttribute("href"));
                        }
                      }}
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
                      <ProgressiveArticle articleId={displayedArticle.id} html={displayedArticle.content} baseUrl={displayedArticle.url} shownOriginal={Boolean(displayedArticle.shownOriginal)} />
                      <Attachments article={$activeArticle} />
                    </div>
                  </PhotoProvider>
                </motion.div>
              </AnimatePresence>
            </ScrollShadow>
          )}
        </motion.div>
      </AnimatePresence>
    </MotionConfig>
  );
};

export default memo(ArticleView);
