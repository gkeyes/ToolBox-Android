import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";
import FeedIcon from "@/components/ui/FeedIcon.jsx";
import { generateReadableDate } from "@/lib/format.js";
import { safeContentUrl } from "@/toolbox/content.js";
import { settingsState } from "@/stores/settingsStore.js";
import { cn } from "@/lib/utils.js";
import { sharedArticleTitleProps } from "@/motion/sharedElement.mjs";

function feedIdOf(article) {
  return article?.feed?.id ?? article?.feedId ?? article?.feed_id ?? null;
}

function feedTitleOf(article, fallback) {
  return article?.feed?.title ?? article?.feedTitle ?? article?.feed_title ?? fallback;
}

export default function ArticleHeader({
  article,
  onFeedClick,
  interactive = true,
  className,
}) {
  const { t } = useTranslation();
  const { fontSize, fontFamily, titleFontSize, titleAlignType } = useStore(settingsState);
  if (!article) return null;

  const feedId = feedIdOf(article);
  const feedTitle = feedTitleOf(article, t("articleView.continuousReading.nextUnread"));
  const title = article.titleText ?? article.title ?? "";
  const safeUrl = safeContentUrl(article.url);
  const alignCenter = titleAlignType === "center";
  const sharedTitle = sharedArticleTitleProps(article.id);

  return (
    <header
      className={cn("article-header nextflux-article-header", className)}
      style={{ textAlign: titleAlignType, fontFamily }}
    >
      <div
        className={cn(
          "nextflux-article-feed text-muted text-sm flex items-center gap-1",
          alignCenter ? "justify-center" : "",
        )}
      >
        {interactive ? (
          <button
            type="button"
            onClick={onFeedClick}
            className="nextflux-article-feed-button flex items-center gap-1 hover:cursor-pointer focus:outline-none"
          >
            <FeedIcon feedId={feedId} />
            <span>{feedTitle}</span>
          </button>
        ) : (
          <>
            <FeedIcon feedId={feedId} />
            <span>{feedTitle}</span>
          </>
        )}
      </div>

      <h1
        {...sharedTitle}
        data-font-block=""
        className="article-title my-2 leading-tight"
        style={{ fontSize: `${titleFontSize * fontSize}px`, ...(sharedTitle.style || {}) }}
      >
        {interactive && safeUrl ? (
          <a
            className="nextflux-article-title-link"
            href={safeUrl}
            rel="noopener noreferrer"
            target="_blank"
          >
            {title}
          </a>
        ) : (
          <span className="nextflux-article-title-link">{title}</span>
        )}
      </h1>

      <div className="nextflux-article-date text-muted opacity-60 text-sm">
        <time dateTime={article.published_at} key={t.language}>
          {generateReadableDate(article.published_at)}
        </time>
      </div>
    </header>
  );
}
