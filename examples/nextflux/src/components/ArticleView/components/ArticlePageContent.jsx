import { forwardRef, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { useStore } from "@nanostores/react";
import { PhotoProvider } from "react-photo-view";
import ArticleHeader from "@/components/ArticleView/components/ArticleHeader.jsx";
import ProgressiveArticle from "@/components/ArticleView/components/ProgressiveArticle.jsx";
import Attachments from "@/components/ArticleView/components/Attachments.jsx";
import AISummary from "@/components/ArticleView/components/AISummary.jsx";
import Iframe from "@/components/ArticleView/components/Iframe.jsx";
import { imageGalleryActive } from "@/stores/articlesStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import { currentThemeMode, themeState } from "@/stores/themeStore.js";
import { runtimeForeground } from "@/toolbox/foreground.js";
import { getFontSizeClass, cn } from "@/lib/utils.js";
import { isHlsPlaylist } from "@/reading/media-sources.mjs";

const ArticlePageContent = forwardRef(function ArticlePageContent(
  { article, passive = false, readingPaused = false, className },
  ref,
) {
  const navigate = useNavigate();
  const {
    lineHeight,
    fontSize,
    maxWidth,
    alignJustify,
    fontFamily,
  } = useStore(settingsState);
  const { lightTheme } = useStore(themeState);
  const currentMode = useStore(currentThemeMode);
  const foreground = useStore(runtimeForeground);

  const mediaEnclosures = useMemo(
    () => article?.enclosures?.filter((enclosure) => /^(audio|video)\//.test(enclosure.mime_type || "") || isHlsPlaylist({ url: enclosure.url, type: enclosure.mime_type })) || [],
    [article?.enclosures],
  );

  if (!article) return null;

  const feedId = article?.feed?.id ?? article?.feedId ?? article?.feed_id;
  const isStoneTheme = lightTheme === "stone" && currentMode === "light";

  return (
    <div
      ref={ref}
      className={cn(
        "article-view-content px-5 pt-5 pb-20 w-full mx-auto",
        className,
      )}
      style={{ maxWidth: `${maxWidth}ch`, fontFamily }}
    >
      <ArticleHeader
        article={article}
        interactive={!passive}
        onFeedClick={passive || !feedId ? undefined : () => navigate(`/feed/${feedId}`)}
      />
      <div className="nextflux-article-header-gap" aria-hidden="true" />

      <AISummary articleId={article.id} passive={passive} paused={readingPaused || !foreground} />

      {mediaEnclosures.map((enclosure) => (
        <Iframe
          key={enclosure.url}
          domNode={{
            name: enclosure.mime_type.startsWith("audio/") ? "audio" : "video",
            attribs: { src: enclosure.url },
          }}
          sources={[{ url: enclosure.url, type: enclosure.mime_type }]}
        />
      ))}

      <PhotoProvider
        bannerVisible={!passive}
        onVisibleChange={(visible) => {
          if (!passive) imageGalleryActive.set(visible);
          if (!visible) releaseStuckGallery();
        }}
        maskOpacity={0.8}
        loop={false}
        speed={() => 300}
      >
        <div
          className={cn(
            "article-content prose dark:prose-invert max-w-none",
            "prose-pre:rounded-[var(--ds-radius-control)]",
            "prose-h1:text-[1.5em] prose-h2:text-[1.25em] prose-h3:text-[1.125em] prose-h4:text-[1em]",
            getFontSizeClass(fontSize),
            isStoneTheme ? "prose-stone" : "",
          )}
          style={{
            lineHeight: lineHeight + "em",
            textAlign: alignJustify ? "justify" : "left",
          }}
        >
          <ProgressiveArticle
            preview={passive}
            paused={readingPaused || !foreground}
            articleId={article.id}
            html={article.content}
            baseUrl={article.shownOriginal && article.fullTextBaseUrl || article.url}
            title={article.titleText ?? article.title}
            shownOriginal={Boolean(article.shownOriginal)}
          />
          <Attachments article={article} />
        </div>
      </PhotoProvider>
    </div>
  );
});

// react-photo-view removes its full-screen portal only when the backdrop's
// fade-out fires animationend. If that event is lost (WebView paused the
// animation, the page moved mid-close, ...), an invisible full-screen layer
// stays on top and swallows every tap and the back gesture. After the close
// animation should have finished, deliver the missing event; if the layer is
// somehow still there, make it inert to input and invisible.
const GALLERY_CLOSE_MS = 300;
function releaseStuckGallery() {
  window.setTimeout(() => {
    const backdrops = document.querySelectorAll(".PhotoView-Portal .PhotoView-Slider__Backdrop");
    backdrops.forEach((backdrop) => {
      const event = typeof AnimationEvent === "function"
        ? new AnimationEvent("animationend", { bubbles: true, animationName: "PhotoView__fade" })
        : new Event("animationend", { bubbles: true });
      backdrop.dispatchEvent(event);
    });
    window.setTimeout(() => {
      document.querySelectorAll(".PhotoView-Portal").forEach((portal) => {
        portal.style.pointerEvents = "none";
        portal.style.visibility = "hidden";
      });
      if (!document.querySelector(".PhotoView-Portal:not([style*='visibility: hidden'])")) {
        document.body.style.removeProperty("overflow");
      }
    }, 250);
  }, GALLERY_CLOSE_MS + 400);
}

export default ArticlePageContent;
