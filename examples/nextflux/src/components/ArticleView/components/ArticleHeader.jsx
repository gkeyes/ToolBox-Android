import { motion } from "framer-motion";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";
import FeedIcon from "@/components/ui/FeedIcon.jsx";
import { generateReadableDate } from "@/lib/format.js";
import { safeContentUrl } from "@/toolbox/content.js";
import { settingsState } from "@/stores/settingsStore.js";
import { cn } from "@/lib/utils.js";

const sharedTransition = {
  layout: {
    type: "spring",
    stiffness: 280,
    damping: 34,
    mass: 0.9,
  },
};

function feedIdOf(article) {
  return article?.feed?.id ?? article?.feedId ?? article?.feed_id ?? null;
}

function feedTitleOf(article, fallback) {
  return article?.feed?.title ?? article?.feedTitle ?? article?.feed_title ?? fallback;
}

export default function ArticleHeader({
  article,
  layoutId,
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

  return (
    <motion.header
      layoutId={layoutId ? `${layoutId}-header` : undefined}
      transition={sharedTransition}
      className={cn("article-header nextflux-shared-article-header", className)}
      style={{ textAlign: titleAlignType, fontFamily }}
    >
      <motion.div
        layoutId={layoutId ? `${layoutId}-feed` : undefined}
        transition={sharedTransition}
        className={cn(
          "nextflux-shared-feed text-muted text-sm flex items-center gap-1",
          alignCenter ? "justify-center" : "",
        )}
      >
        {interactive ? (
          <button
            type="button"
            onClick={onFeedClick}
            className="nextflux-shared-feed-button flex items-center gap-1 hover:cursor-pointer focus:outline-none"
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
      </motion.div>

      <motion.h1
        layoutId={layoutId ? `${layoutId}-title` : undefined}
        transition={sharedTransition}
        data-font-block=""
        className="article-title my-2 leading-tight"
        style={{ fontSize: `${titleFontSize * fontSize}px` }}
      >
        {interactive && safeUrl ? (
          <a
            className="nextflux-shared-title-link"
            href={safeUrl}
            rel="noopener noreferrer"
            target="_blank"
          >
            {title}
          </a>
        ) : (
          <span className="nextflux-shared-title-link">{title}</span>
        )}
      </motion.h1>

      <motion.div
        layoutId={layoutId ? `${layoutId}-date` : undefined}
        transition={sharedTransition}
        className="nextflux-shared-date text-muted opacity-60 text-sm"
      >
        <time dateTime={article.published_at} key={t.language}>
          {generateReadableDate(article.published_at)}
        </time>
      </motion.div>
    </motion.header>
  );
}
