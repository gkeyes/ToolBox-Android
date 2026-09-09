import { createHighlighterCore } from "shiki/core";
import { createJavaScriptRegexEngine } from "shiki/engine/javascript";
import { bundledLanguages } from "shiki/langs";
import latte from "shiki/themes/catppuccin-latte.mjs";
import githubDark from "shiki/themes/github-dark.mjs";
import { themeState } from "@/stores/themeStore.js";

let highlighterPromise;
function getHighlighter() {
  highlighterPromise ||= createHighlighterCore({ themes: [latte, githubDark], langs: [], engine: createJavaScriptRegexEngine({ forgiving: true }) });
  return highlighterPromise;
}
import { useEffect, useRef, useState } from "react";
import { Button } from "@heroui/react";
import { Check, Copy } from "lucide-react";
import { settingsState } from "@/stores/settingsStore.js";
import { useStore } from "@nanostores/react";
import { cn } from "@/lib/utils.js";
import { useTranslation } from "react-i18next";

export default function CodeBlock({ code, language = "text" }) {
  const { t } = useTranslation();
  const [highlight, setHighlight] = useState(null);
  const [isCopied, setIsCopied] = useState(false);
  const [error, setError] = useState("");
  const timer = useRef(null);
  const { showLineNumbers, forceDarkCodeTheme } = useStore(settingsState);
  const { darkTheme } = useStore(themeState);
  useEffect(() => {
    let active = true;
    async function renderCode() {
      try {
        const highlighter = await getHighlighter();
        const lang = bundledLanguages[language] ? language : "text";
        if (lang !== "text" && !highlighter.getLoadedLanguages().includes(lang)) await highlighter.loadLanguage(bundledLanguages[lang]);
        const html = highlighter.codeToHtml(code, { lang, themes: { light: "catppuccin-latte", dark: "github-dark" } });
        if (active) setHighlight({ code, language, html });
      } catch { if (active) setHighlight(null); }
    }
    renderCode();
    return () => { active = false; };
  }, [code, language]);
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
  return (
    <div className={cn("code-block relative group", forceDarkCodeTheme ? `${darkTheme} force-dark-code-theme` : "", showLineNumbers ? "line-numbers" : "")}>
      {language !== "text" && <span className="text-xs absolute left-3 top-2 text-muted">{language}</span>}
      <Button className="nextflux-compact-touch-target absolute right-2 top-2" size="sm" isDisabled={isCopied} variant="ghost" isIconOnly aria-label={t("common.copy")} onPress={handleCopy}>
        {isCopied ? <Check className="size-4" /> : <Copy className="size-4" />}
      </Button>
      {highlight?.code === code && highlight?.language === language ? <div className="pt-10" dangerouslySetInnerHTML={{ __html: highlight.html }} /> : <pre className="overflow-x-auto pt-12"><code>{code}</code></pre>}
      {error && <p role="status" className="text-sm text-danger">{error}</p>}
    </div>
  );
}
