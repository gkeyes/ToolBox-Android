import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";
import FeedIcon from "@/components/ui/FeedIcon.jsx";
import { generateReadableDate } from "@/lib/format.js";
import { safeContentUrl } from "@/toolbox/content.js";
import { settingsState } from "@/stores/settingsStore.js";
import { cn } from "@/lib/utils.js";

function feedIdOf(article) {
  return article?.feed?.id ?? article?.feedId ?? article?.feed_id ?? null;
}

function feedTitleOf(article, fallback) {
  return article?.feed?.title ?? article?.feedTitle ?? article?.feed_title ?? fallback;
}

export default function ArticleHeader({
  article,
  wordCount,
  titleInBody,
  onFeedClick,
  interactive = true,
  className,
}) {
  const { t } = useTranslation();
  const { fontSize, fontFamily, titleFontSize, titleAlignType, articleTitleLines } = useStore(settingsState);
  if (!article) return null;

  const feedId = feedIdOf(article);
  const feedTitle = feedTitleOf(article, t("articleView.continuousReading.nextUnread"));
  const title = article.titleText ?? article.title ?? "";
  const safeUrl = safeContentUrl(article.url);
  const alignCenter = titleAlignType === "center";
  const suggestSummary = wordCount > 500;

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
        data-font-block=""
        className={cn("article-title my-2 leading-tight", titleInBody && "nf-article-title-clamp")}
        style={{
          fontSize: `${titleFontSize * fontSize}px`,
          "--nf-article-title-lines": Math.min(5, Math.max(1, Math.round(Number(articleTitleLines) || 5))),
        }}
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

      <div className="nextflux-article-date text-sm">
        <time dateTime={article.published_at} key={t.language}>
          {generateReadableDate(article.published_at)}
        </time>
        {wordCount != null && (
          <span
            className={cn("nextflux-article-word-count", suggestSummary ? "ds-tone-success" : "bg-[var(--ds-state-layer)]")}
            title={suggestSummary ? t("articleView.aiSummary") : undefined}
          >
            {t("articleView.wordCount", { count: wordCount })}
          </span>
        )}
      </div>
    </header>
  );
}
