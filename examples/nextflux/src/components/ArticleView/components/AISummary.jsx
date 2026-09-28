import { useEffect, useState } from "react";
import { useStore } from "@nanostores/react";
import { aiSummaries, clearSummary } from "@/stores/aiStore.js";
import { CloseButton, Spinner } from "@heroui/react";
import { Sparkles } from "lucide-react";
import { useTranslation } from "react-i18next";
import BorderBeam from "border-beam";
import { currentThemeMode } from "@/stores/themeStore.js";

const TICK_MS = 16;
const CHARS_STREAMING = 5;

export default function AISummary({ articleId, passive = false, paused = false }) {
  const { t } = useTranslation();
  const $aiSummaries = useStore(aiSummaries);
  const $currentThemeMode = useStore(currentThemeMode);
  const state = $aiSummaries[articleId];

  const [streamed, setStreamed] = useState({ articleId, text: "" });
  const fullText = state?.summary || "";
  const animateText = Boolean(state?.loading && !passive && !paused);
  // Completed summaries keep identical geometry in the preview and real page.
  // Only a visible live stream schedules character work; idle articles and
  // offscreen previews have no polling timer or border animation.
  const displayedText = animateText
    ? (streamed.articleId === articleId ? streamed.text : "").slice(0, fullText.length)
    : fullText;
  useEffect(() => {
    if (!animateText || !fullText) {
      setStreamed((previous) => previous.articleId === articleId && previous.text === fullText
        ? previous : { articleId, text: fullText });
      return;
    }
    if (displayedText.length >= fullText.length) return;
    const timer = setTimeout(() => {
      setStreamed({ articleId, text: fullText.slice(0, displayedText.length + CHARS_STREAMING) });
    }, TICK_MS);
    return () => clearTimeout(timer);
  }, [articleId, animateText, fullText, displayedText.length]);

  if (!state) return null;

  const isTyping =
    state.loading || displayedText.length < (state.summary?.length ?? 0);
  const isWaiting = state.loading && !state.summary;

  return (
    <BorderBeam
      active={!passive && !paused && (isWaiting || isTyping)}
      size="line"
      theme={$currentThemeMode}
    >
      <div className="ai-summary p-4 bg-background rounded-2xl">
        <div className="flex min-h-[48px] items-center gap-2">
          <div className="flex min-w-0 items-center gap-1">
            <Sparkles className="size-4 text-accent shrink-0" />
            <span className="nextflux-summary-title text-sm font-medium text-accent">
              {t("articleView.aiSummary")}
            </span>
          </div>
          {!isTyping && (
            <CloseButton
              onPress={() => clearSummary(articleId)}
              className="nextflux-close-button ml-auto"
              aria-label={`${t("common.close")} ${t("articleView.aiSummary")}`}
            />
          )}
        </div>

        {isWaiting && (
          <div className="flex items-center gap-2 text-sm text-muted py-2">
            <Spinner size="sm" color="current" />
            <span>{t("articleView.aiSummaryGenerating")}</span>
          </div>
        )}

        {state.error && <p className="text-sm text-danger">{state.error}</p>}

        {displayedText && (
          <div className="text-sm text-muted leading-relaxed">
            {displayedText}
            {animateText && isTyping && (
              <span className="inline-block w-0.5 h-4 bg-accent ml-0.5 animate-pulse align-middle" />
            )}
          </div>
        )}
      </div>
    </BorderBeam>
  );
}
