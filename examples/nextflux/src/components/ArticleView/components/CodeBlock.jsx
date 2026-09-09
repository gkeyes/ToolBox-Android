import { themeState } from "@/stores/themeStore.js";
import { observeCodeVisibility } from "@/lib/reading-client.js";
import { MAX_HIGHLIGHT_CODE_CHARACTERS } from "@/lib/highlight-limits.js";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Button } from "@heroui/react";
import { Check, Copy } from "lucide-react";
import { settingsState } from "@/stores/settingsStore.js";
import { useStore } from "@nanostores/react";
import { cn } from "@/lib/utils.js";
import { useTranslation } from "react-i18next";

// The SAX reader owns plainElement and all its lines. This portal only owns the
// controls and the bounded, optional highlighted view; large plaintext is never
// handed back to React for a second full render.
export default function CodeBlock({ code, language = "text", highlights, plainElement, container }) {
  const { t } = useTranslation();
  const [highlight, setHighlight] = useState(null);
  const [isCopied, setIsCopied] = useState(false);
  const [error, setError] = useState("");
  const timer = useRef(null);
  const { showLineNumbers, forceDarkCodeTheme } = useStore(settingsState);
  const { darkTheme } = useStore(themeState);
  const highlighted = highlight?.code === code && highlight?.language === language;
  useLayoutEffect(() => {
    container.className = cn("code-block relative group", forceDarkCodeTheme ? `${darkTheme} force-dark-code-theme` : "", showLineNumbers ? "line-numbers" : "");
    container.dataset.highlighted = String(highlighted);
    plainElement.hidden = highlighted;
    return () => { plainElement.hidden = false; };
  }, [container, plainElement, highlighted, forceDarkCodeTheme, darkTheme, showLineNumbers]);
  useEffect(() => {
    setHighlight(null);
    if (!highlights || code.length > MAX_HIGHLIGHT_CODE_CHARACTERS) return;
    let pending = null;
    const publishWhenUnselected = () => {
      if (!pending) return;
      const selection = container.ownerDocument.getSelection();
      if (selection?.rangeCount && !selection.isCollapsed && selection.getRangeAt(0).intersectsNode(container)) return;
      setHighlight({ code, language, html: pending });
      pending = null;
    };
    container.ownerDocument.addEventListener("selectionchange", publishWhenUnselected);
    const stop = observeCodeVisibility(container, (completed) => highlights.enqueue(code, language, (html) => {
      pending = html;
      publishWhenUnselected();
      completed();
    }));
    return () => { stop(); pending = null; container.ownerDocument.removeEventListener("selectionchange", publishWhenUnselected); };
  }, [code, language, highlights, container]);
  useEffect(() => () => clearTimeout(timer.current), []);
  const handleCopy = async () => {
    setError("");
    try {
      if (!window.ToolBox?.clipboard?.writeText) throw new Error("clipboard unavailable");
      await window.ToolBox.clipboard.writeText(code);
      setIsCopied(true);
      timer.current = setTimeout(() => setIsCopied(false), 3000);
    } catch { setError("复制失败，请检查小工具的剪贴板写入权限后重试。"); }
  };
  return <>
    {language !== "text" && <span className="text-xs absolute left-3 top-2 text-muted">{language}</span>}
    <Button className="nextflux-compact-touch-target absolute right-2 top-2" size="sm" isDisabled={isCopied} variant="ghost" isIconOnly aria-label={t("common.copy")} onPress={handleCopy}>
      {isCopied ? <Check className="size-4" /> : <Copy className="size-4" />}
    </Button>
    {highlighted && <div dangerouslySetInnerHTML={{ __html: highlight.html }} />}
    {error && <p role="status" className="text-sm text-danger">{error}</p>}
  </>;
}
