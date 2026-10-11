import { shareText } from "@/toolbox/actions.js";
import ArticleTitleFilterButton from "./ArticleTitleFilterButton.jsx";
import FullTextAdaptButton from "./FullTextAdaptButton.jsx";
import { ArticleSpeechBar, ArticleSpeechButton } from "./ArticleSpeech.jsx";
import {
  ArrowLeft,
  ArrowRight,
} from "lucide-react";
import {
  ReadStateEmptyIcon as Circle,
  ReadStateIcon as CircleDot,
  FullTextIcon as FileText,
  ShareIcon as Share,
  StarIcon as Star,
  SaveIcon as CloudUpload,
  AISummaryIcon as Sparkles,
  MoreIcon as Ellipsis,
  TitleFilterIcon as Funnel,
  AdaptIcon as WandSparkles,
} from "@/components/ui/icons.jsx";
import MobileSheet from "@/components/ui/MobileSheet.jsx";
import {
  handleMarkStatus,
  handleMarkRead,
  handleToggleStar,
  handleToggleContent,
} from "@/handlers/articleHandlers.js";
import { Button, CloseButton, cn, Spinner, Tooltip } from "@heroui/react";
import { useNavigate } from "react-router-dom";
import { useStore } from "@nanostores/react";
import {
  activeArticle,
  filteredArticles,
  loadingOriginContent,
} from "@/stores/articlesStore";
import Confetti from "@/components/ui/Confetti";
import { useEffect, useRef, useState } from "react";
import minifluxAPI from "@/api/miniflux";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { hasIntegrations } from "@/stores/basicInfoStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import {
  aiSummaries,
  setSummaryLoading,
  appendSummaryChunk,
  setSummaryDone,
  setSummaryError,
} from "@/stores/aiStore.js";
import { summarizeArticleStream } from "@/api/openai.js";

export default function ActionButtons() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const $articles = useStore(filteredArticles);
  const $activeArticle = useStore(activeArticle);
  const buttonRef = useRef(null);
  const fetchLoading = useStore(loadingOriginContent);
  const [saveLoading, setSaveLoading] = useState(false);
  const $hasIntegrations = useStore(hasIntegrations);
  const { aiApiKey, floatingSidebar } = useStore(settingsState);
  const $aiSummaries = useStore(aiSummaries);
  const currentSummaryState = $aiSummaries[$activeArticle?.id];
  const [moreOpen, setMoreOpen] = useState(false);
  const rootRef = useRef(null);
  // The title filter applies to the article's own feed.
  const hasTitleFilter = Boolean($activeArticle?.feedId);

  // Close the sheet first, then press the real control so only one dialog is
  // ever open and the control keeps its own dialog/notice state.
  const pressAfterSheet = (selector) => {
    setMoreOpen(false);
    setTimeout(() => {
      rootRef.current?.querySelector(`.reader-secondary ${selector}`)?.click();
    }, 260);
  };

  // 获取当前文章在列表中的索引
  const currentIndex = $articles.findIndex((a) => a.id === $activeArticle?.id);

  // 获取当前路径并去掉 article 部分
  const basePath = (window.location.hash.slice(1).split("?")[0] || "/").split("/article/")[0];

  // 处理关闭按钮点击
  const handleClose = () => {
    navigate(basePath || "/");
  };

  // 处理上一篇按钮点击
  const handlePrevious = async () => {
    if (currentIndex > 0) {
      const prevArticle = $articles[currentIndex - 1];
      navigate(`${basePath}/article/${prevArticle.id}`);
      if (prevArticle.status !== "read") {
        await handleMarkRead(prevArticle);
      }
    }
  };

  // 处理下一篇按钮点击
  const handleNext = async () => {
    if (currentIndex < $articles.length - 1) {
      const nextArticle = $articles[currentIndex + 1];
      navigate(`${basePath}/article/${nextArticle.id}`);
      if (nextArticle.status !== "read") {
        await handleMarkRead(nextArticle);
      }
    }
  };

  const handleShare = async () => {
    if (!$activeArticle) return;
    await shareText(`${$activeArticle.title}\n${$activeArticle.url}`);
  };

  const summaryController = useRef(null);
  useEffect(() => () => summaryController.current?.abort(), [$activeArticle?.id]);

  // 处理AI总结
  const handleAISummarize = () => {
    if (!$activeArticle) return;
    const articleId = $activeArticle.id;
    if (currentSummaryState?.loading) {
      summaryController.current?.abort();
      return;
    }
    summaryController.current = new AbortController();
    setSummaryLoading(articleId);

    let rafId = null;
    let pendingText = "";

    const flush = () => {
      if (pendingText) {
        appendSummaryChunk(articleId, pendingText);
        pendingText = "";
      }
      rafId = null;
    };

    summarizeArticleStream($activeArticle, {
      signal: summaryController.current.signal,
      onChunk: (chunk) => {
        pendingText += chunk;
        if (!rafId) rafId = requestAnimationFrame(flush);
      },
      onDone: () => {
        if (rafId) cancelAnimationFrame(rafId);
        flush();
        setSummaryDone(articleId);
      },
      onError: (error) => {
        if (rafId) cancelAnimationFrame(rafId);
        setSummaryError(articleId, error.message);
        toast.error(error.message);
      },
    });
  };

  // 处理保存到第三方服务
  const handleSaveToThirdParty = async () => {
    if (!$activeArticle) return;
    setSaveLoading(true);
    try {
      await minifluxAPI.saveToThirdParty($activeArticle.id);
      toast.success(t("common.success"));
    } catch (error) {
      console.error("保存到第三方服务失败:", error);
    } finally {
      setSaveLoading(false);
    }
  };

  return (
    <div
      ref={rootRef}
      className={cn(
        "action-buttons reader-topbar py-2 standalone:pt-safe-or-2.5 px-2 sticky top-0 z-50",
        floatingSidebar ? "" : "reader-topbar-card",
      )}
    >
      <div className="reader-topbar-row flex flex-nowrap items-center gap-0 overflow-hidden">
        <Tooltip
          content={t("common.close")}
          classNames={{ content: "shadow-custom!" }}
        >
          <CloseButton onPress={handleClose} className="nextflux-close-button shrink-0" aria-label={t("common.close")} />
          <Tooltip.Content showArrow>
            <Tooltip.Arrow />
            {t("common.close")}
          </Tooltip.Content>
        </Tooltip>
        <div className="hidden shrink-0 items-center gap-1 md:flex">
          <Tooltip delay={0}>
            <Button
              className="nextflux-toolbar-button"
              aria-label={t("common.previous")}
              onPress={handlePrevious}
              isDisabled={currentIndex <= 0}
              isIconOnly
              size="sm"
              variant="ghost"
            >
              <ArrowLeft className="h-4 w-4 text-muted" />
            </Button>
            <Tooltip.Content showArrow>
              <Tooltip.Arrow />
              {t("common.previous")}
            </Tooltip.Content>
          </Tooltip>
          <Tooltip delay={0}>
            <Button
              className="nextflux-toolbar-button"
              aria-label={t("common.next")}
              onPress={handleNext}
              isDisabled={currentIndex >= $articles.length - 1}
              isIconOnly
              size="sm"
              variant="ghost"
            >
              <ArrowRight className="h-4 w-4 text-muted" />
            </Button>
            <Tooltip.Content showArrow>
              <Tooltip.Arrow />
              {t("common.next")}
            </Tooltip.Content>
          </Tooltip>
        </div>
        <div className="nextflux-toolbar-groups ml-auto min-w-0 flex-1">
          {/* Lower-frequency actions: inline on wide screens; on phones these
              buttons are hidden and reached through the "More" sheet, which
              presses them so their own dialogs and notices keep working. */}
          <div className="reader-secondary nextflux-toolbar-group" aria-label="更多功能">
            <Tooltip delay={0}>
              <Button
                className="nextflux-toolbar-button"
                aria-label={$activeArticle?.status === "read" ? t("common.unread") : t("common.read")}
                onPress={() => handleMarkStatus($activeArticle)}
                variant="ghost"
                isIconOnly
                size="sm"
              >
                {$activeArticle?.status === "unread" ? (
                  <CircleDot className="size-4 text-muted" />
                ) : (
                  <Circle className="size-4 text-muted" />
                )}
              </Button>
              <Tooltip.Content showArrow>
                <Tooltip.Arrow />
                {$activeArticle?.status === "read" ? t("common.unread") : t("common.read")}
              </Tooltip.Content>
            </Tooltip>
            <ArticleTitleFilterButton />
            <FullTextAdaptButton />
            {$hasIntegrations && (
              <Tooltip delay={0}>
                <Button
                  className="nextflux-toolbar-button"
                  aria-label={t("articleView.saveToThirdParty")}
                  variant="ghost"
                  isIconOnly
                  size="sm"
                  onPress={handleSaveToThirdParty}
                  isPending={saveLoading}
                >
                  {saveLoading ? (
                    <Spinner color="current" size="sm" />
                  ) : (
                    <CloudUpload className="size-4 text-muted" />
                  )}
                </Button>
                <Tooltip.Content showArrow>
                  <Tooltip.Arrow />
                  {t("articleView.saveToThirdParty")}
                </Tooltip.Content>
              </Tooltip>
            )}
          </div>
        </div>
      </div>
      {/* Reader dock: the six high-frequency actions. On phones it floats at
          the bottom with text labels; on wide screens it sits in this bar. */}
      <div className="reader-dock" role="toolbar" aria-label="文章操作">
        <div className="nextflux-toolbar-group nextflux-toolbar-group-native" aria-label="默认功能">
          <Tooltip delay={0}>
            <Button
              className="nextflux-toolbar-button"
              aria-label={$activeArticle?.shownOriginal ? t("articleView.showSummary") : t("articleView.getFullText")}
              onPress={() => handleToggleContent($activeArticle)}
              variant="ghost"
              isIconOnly
              size="sm"
              isPending={fetchLoading}
            >
              {fetchLoading ? (
                <Spinner color="current" size="sm" />
              ) : (
                <FileText
                  className={cn(
                    "size-4",
                    $activeArticle?.shownOriginal ? "text-accent" : "text-muted",
                  )}
                />
              )}
            </Button>
            <Tooltip.Content showArrow>
              <Tooltip.Arrow />
              {$activeArticle?.shownOriginal ? t("articleView.showSummary") : t("articleView.getFullText")}
            </Tooltip.Content>
          </Tooltip>

          <Tooltip delay={0}>
            <Button
              className="nextflux-toolbar-button"
              aria-label={$activeArticle?.starred === 1 ? t("common.unstar") : t("common.star")}
              ref={buttonRef}
              variant="ghost"
              isIconOnly
              size="sm"
              onPress={() => {
                $activeArticle?.starred === 0 && Confetti(buttonRef);
                handleToggleStar($activeArticle);
              }}
            >
              <Star className={`size-4 ${$activeArticle?.starred === 1 ? "fill-current text-accent" : "text-muted"}`} />
            </Button>
            <Tooltip.Content showArrow>
              <Tooltip.Arrow />
              {$activeArticle?.starred === 1 ? t("common.unstar") : t("common.star")}
            </Tooltip.Content>
          </Tooltip>
        </div>

        <div className="nextflux-toolbar-group nextflux-toolbar-group-ai" aria-label="AI 功能">
          {aiApiKey && (
            <Tooltip delay={0}>
              <Button
                className="nextflux-toolbar-button"
                onPress={handleAISummarize}
                variant="ghost"
                isIconOnly
                size="sm"
                aria-label={currentSummaryState?.loading ? "停止 AI 摘要" : "AI 摘要"}
              >
                {currentSummaryState?.loading ? (
                  <Spinner color="current" size="sm" />
                ) : (
                  <Sparkles
                    className={`size-4 ${currentSummaryState?.summary ? "text-accent" : "text-muted"}`}
                  />
                )}
              </Button>
              <Tooltip.Content showArrow>
                <Tooltip.Arrow />
                {currentSummaryState?.loading ? "停止 AI 摘要" : t("articleView.aiSummarize")}
              </Tooltip.Content>
            </Tooltip>
          )}
          <ArticleSpeechButton article={$activeArticle} />
        </div>

        <div className="nextflux-toolbar-group nextflux-toolbar-group-system" aria-label="系统功能">
          <Tooltip delay={0}>
            <Button
              className="nextflux-toolbar-button"
              aria-label={t("common.share")}
              variant="ghost"
              isIconOnly
              size="sm"
              onPress={handleShare}
            >
              <Share className="size-4 text-muted" />
            </Button>
            <Tooltip.Content showArrow>
              <Tooltip.Arrow />
              {t("common.share")}
            </Tooltip.Content>
          </Tooltip>
          <Button
            className="nextflux-toolbar-button reader-more-button"
            aria-label="更多"
            aria-haspopup="dialog"
            variant="ghost"
            isIconOnly
            size="sm"
            onPress={() => setMoreOpen(true)}
          >
            <Ellipsis className="size-4 text-muted" />
          </Button>
        </div>
      </div>

      <div className="reader-speech-slot">
        <ArticleSpeechBar article={$activeArticle} />
      </div>
      <MobileSheet
        open={moreOpen}
        onOpenChange={setMoreOpen}
        title="这篇文章"
        closeLabel={t("common.close")}
      >
        <div className="reader-more-grid">
          <MoreItem
            label={$activeArticle?.status === "read" ? t("common.unread") : t("common.read")}
            icon={$activeArticle?.status === "unread" ? <CircleDot /> : <Circle />}
            onPress={() => { handleMarkStatus($activeArticle); setMoreOpen(false); }}
          />
          {hasTitleFilter && (
            <MoreItem label="标题过滤" icon={<Funnel />} onPress={() => pressAfterSheet(".nf-title-filter-trigger")} />
          )}
          <MoreItem label="全文适配" icon={<WandSparkles />} onPress={() => pressAfterSheet('[aria-label="全文适配"]')} />
          {$hasIntegrations && (
            <MoreItem
              label={t("articleView.saveToThirdParty")}
              icon={saveLoading ? <Spinner color="current" size="sm" /> : <CloudUpload />}
              onPress={() => { handleSaveToThirdParty(); setMoreOpen(false); }}
            />
          )}
          <MoreItem
            label={t("common.previous")}
            icon={<ArrowLeft />}
            isDisabled={currentIndex <= 0}
            onPress={() => { setMoreOpen(false); handlePrevious(); }}
          />
          <MoreItem
            label={t("common.next")}
            icon={<ArrowRight />}
            isDisabled={currentIndex >= $articles.length - 1}
            onPress={() => { setMoreOpen(false); handleNext(); }}
          />
        </div>
      </MobileSheet>
    </div>
  );
}

function MoreItem({ label, icon, onPress, isDisabled = false }) {
  return (
    <Button
      variant="ghost"
      className="reader-more-item"
      aria-label={label}
      onPress={onPress}
      isDisabled={isDisabled}
    >
      <span className="reader-more-icon" aria-hidden="true">{icon}</span>
      <span className="reader-more-label">{label}</span>
    </Button>
  );
}

