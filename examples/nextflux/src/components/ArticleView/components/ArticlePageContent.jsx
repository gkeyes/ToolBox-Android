import { forwardRef, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { useStore } from "@nanostores/react";
import { PhotoProvider } from "react-photo-view";
import { cancelGalleryRelease, releaseStuckGallery } from "@/toolbox/galleryRelease.js";
import ArticleHeader from "@/components/ArticleView/components/ArticleHeader.jsx";
import ProgressiveArticle from "@/components/ArticleView/components/ProgressiveArticle.jsx";
import Attachments from "@/components/ArticleView/components/Attachments.jsx";
import Iframe from "@/components/ArticleView/components/Iframe.jsx";
import { MediaPlaybackContext } from "./MediaPlaybackContext.js";
import { imageGalleryActive } from "@/stores/articlesStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import { currentThemeMode, themeState } from "@/stores/themeStore.js";
import { runtimeForeground } from "@/toolbox/foreground.js";
import { getFontSizeClass, cn } from "@/lib/utils.js";
import { enclosurePosterSources, isHlsPlaylist } from "@/reading/media-sources.mjs";
import { ShareIcon } from "@/components/ui/icons.jsx";
import { imageShareAction } from "@/toolbox/actions.js";

function GalleryShareButton({ image }) {
  const current = useRef(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => () => current.current?.abort(), []);
  const share = imageShareAction(image);
  if (!share) return null;

  async function send(event) {
    event.stopPropagation();
    if (current.current) return;
    const controller = new AbortController();
    current.current = controller;
    setPending(true);
    setError("");
    try {
      await share.action(controller.signal, setError);
    } finally {
      current.current = null;
      if (!controller.signal.aborted) setPending(false);
    }
  }

  return <div className="nextflux-gallery-share">
    <button className="nextflux-gallery-share-button" aria-label={share.label} title={share.label}
      type="button" aria-busy={pending || undefined} disabled={pending} onClick={send}><ShareIcon /></button>
    {error && <p className="nextflux-gallery-share-error" role="alert">{error}</p>}
  </div>;
}

function galleryShareToolbar({ images, index, visible }) {
  const photo = images[index];
  return visible && photo ? <GalleryShareButton key={photo.key} image={photo.originRef?.current} /> : null;
}

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

  const articleId = article?.id;
  const html = article?.content;
  const title = article?.titleText ?? article?.title;
  const baseUrl = (article?.shownOriginal && article?.fullTextBaseUrl) || article?.url;
  const shownOriginal = Boolean(article?.shownOriginal);
  const source = useMemo(() => ({ articleId, html, title, baseUrl, shownOriginal }),
    [articleId, html, title, baseUrl, shownOriginal]);
  const [analyzed, setAnalyzed] = useState(null);
  const onMetadata = useCallback((metadata) => setAnalyzed({ source, ...metadata }), [source]);
  const metadata = analyzed?.source === source ? analyzed : null;

  if (!article) return null;

  const feedId = article?.feed?.id ?? article?.feedId ?? article?.feed_id;
  const isStoneTheme = lightTheme === "stone" && currentMode === "light";

  return (
    <MediaPlaybackContext.Provider value={!passive && !readingPaused && foreground}>
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
        wordCount={metadata?.wordCount}
        titleInBody={metadata?.titleInBody}
        interactive={!passive}
        onFeedClick={passive || !feedId ? undefined : () => navigate(`/feed/${feedId}`)}
      />
      <div className="nextflux-article-header-gap" aria-hidden="true" />

      {mediaEnclosures.map((enclosure) => (
        <Iframe
          key={enclosure.url}
          domNode={{
            name: enclosure.mime_type.startsWith("audio/") ? "audio" : "video",
            attribs: { src: enclosure.url },
          }}
          sources={[{ url: enclosure.url, type: enclosure.mime_type }]}
          posterSources={enclosurePosterSources(enclosure, mediaEnclosures, article)}
        />
      ))}

      <PhotoProvider
        bannerVisible={!passive}
        toolbarRender={passive ? undefined : galleryShareToolbar}
        onVisibleChange={(visible) => {
          if (!passive) imageGalleryActive.set(visible);
          if (visible) cancelGalleryRelease();
          else releaseStuckGallery(() => imageGalleryActive.get());
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
            {...source}
            onMetadata={onMetadata}
          />
          <Attachments article={article} />
        </div>
      </PhotoProvider>
    </div>
    </MediaPlaybackContext.Provider>
  );
});

export default ArticlePageContent;
