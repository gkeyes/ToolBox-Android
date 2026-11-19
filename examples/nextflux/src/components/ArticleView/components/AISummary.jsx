import { useMemo } from "react";
import { useStore } from "@nanostores/react";
import { Button, Modal, Spinner, Tooltip } from "@heroui/react";
import { RotateCcw, Square } from "lucide-react";
import { AISummaryIcon as Sparkles } from "@/components/ui/icons.jsx";
import { aiSummaries } from "@/stores/aiStore.js";
import { renderSummaryMarkdown } from "@/toolbox/summary-markdown.mjs";
import { ArticleSpeechBar } from "./ArticleSpeech.jsx";
import "./AISummary.css";

// Reuse the speech surface inside the existing accessible Modal primitive.
// Its portal keeps summaries out of article layouts and passive previews.
export default function AISummary({ article, onClose, onGenerate, onStop }) {
  const articleId = article?.id;
  const summaries = useStore(aiSummaries);
  const state = summaries[articleId];
  const fullText = state?.summary || "";
  const formatted = useMemo(() => renderSummaryMarkdown(fullText, article?.url), [fullText, article?.url]);

  const action = state?.loading ? "停止生成" : state?.error ? "重试" : fullText ? "重新生成" : "生成摘要";

  return (
    <Modal.Backdrop
      variant="transparent"
      className="nextflux-summary-backdrop"
      isOpen
      onOpenChange={(next) => { if (!next) onClose(); }}
    >
      <Modal.Container placement="bottom" scroll="inside" className="action-buttons nextflux-summary-container">
        <Modal.Dialog className="reader-speech-slot nextflux-summary-dialog" aria-label="AI 摘要">
          <div className="nextflux-speech-bar ai-summary">
            <Modal.Header className="nextflux-summary-header">
              <Tooltip delay={0}>
                <Button
                  isIconOnly
                  size="sm"
                  variant="primary"
                  className="nextflux-summary-primary size-10 min-w-10 rounded-full"
                  aria-label={action}
                  onPress={state?.loading ? onStop : onGenerate}
                >
                  {state?.loading ? <Square className="size-4" /> : fullText || state?.error
                    ? <RotateCcw className="size-4" /> : <Sparkles className="size-4" />}
                </Button>
                <Tooltip.Content showArrow><Tooltip.Arrow />{action}</Tooltip.Content>
              </Tooltip>
              <div className="nextflux-summary-heading-group">
                <Modal.Heading className="nextflux-summary-heading">AI 摘要</Modal.Heading>
                <p className="nextflux-summary-article">{article?.titleText || article?.title}</p>
              </div>
              <Modal.CloseTrigger className="nextflux-close-button" aria-label="关闭 AI 摘要" data-nf-back-close />
            </Modal.Header>
            <Modal.Body className="nextflux-summary-body">
              {state?.loading && <div className="nextflux-summary-status" role="status">
                <Spinner size="sm" color="current" /><span>正在生成摘要…</span>
              </div>}
              {state?.error && <p className="ds-banner ds-tone-danger" role="alert">{state.error}</p>}
              {fullText && <div className="nextflux-summary-text">{formatted}</div>}
              {!state?.loading && !state?.error && !fullText && <p className="nextflux-summary-empty">点击生成按钮，提炼这篇文章的要点。</p>}
            </Modal.Body>
          </div>
          {/* Keep active audio controls in the same surface, without overlap. */}
          <ArticleSpeechBar article={article} />
        </Modal.Dialog>
      </Modal.Container>
    </Modal.Backdrop>
  );
}
