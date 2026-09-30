import { forwardRef, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { useStore } from "@nanostores/react";
import { PhotoProvider } from "react-photo-view";
import { Separator } from "@heroui/react";
import ArticleHeader from "@/components/ArticleView/components/ArticleHeader.jsx";
import ProgressiveArticle from "@/components/ArticleView/components/ProgressiveArticle.jsx";
import Attachments from "@/components/ArticleView/components/Attachments.jsx";
import AISummary from "@/components/ArticleView/components/AISummary.jsx";
import Iframe from "@/components/ArticleView/components/Iframe.jsx";
import { imageGalleryActive } from "@/stores/articlesStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import { currentThemeMode, themeState } from "@/stores/themeStore.js";
import { getFontSizeClass, cn } from "@/lib/utils.js";

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

  const mediaEnclosures = useMemo(
    () => article?.enclosures?.filter((enclosure) => /^(audio|video)\//.test(enclosure.mime_type || "")) || [],
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
      <Separator className="my-4" />

      <AISummary articleId={article.id} passive={passive} paused={readingPaused} />

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
        bannerVisible={!passive}
        onVisibleChange={(visible) => {
          if (!passive) imageGalleryActive.set(visible);
        }}
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
            isStoneTheme ? "prose-stone" : "",
          )}
          style={{
            lineHeight: lineHeight + "em",
            textAlign: alignJustify ? "justify" : "left",
          }}
        >
          <ProgressiveArticle
            preview={passive}
            paused={readingPaused}
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

export default ArticlePageContent;
