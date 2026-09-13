import { createHighlighterCore } from "shiki/core";
import { createJavaScriptRegexEngine } from "shiki/engine/javascript";
import { bundledLanguages } from "shiki/langs";
import latte from "shiki/themes/catppuccin-latte.mjs";
import githubDark from "shiki/themes/github-dark.mjs";

// Reuse one fine-grained highlighter per reading session; languages remain lazy.
// https://shiki.style/guide/best-performance
let highlighterPromise;
self.addEventListener("message", async ({ data }) => {
  if (data.type !== "highlight:run") return;
  try {
    highlighterPromise ||= createHighlighterCore({ themes: [latte, githubDark], langs: [], engine: createJavaScriptRegexEngine({ forgiving: true }) });
    const highlighter = await highlighterPromise;
    const lang = Object.hasOwn(bundledLanguages, data.language) ? data.language : "text";
    if (lang !== "text" && !highlighter.getLoadedLanguages().includes(lang)) await highlighter.loadLanguage(bundledLanguages[lang]);
    const html = highlighter.codeToHtml(data.code, { lang, themes: { light: "catppuccin-latte", dark: "github-dark" } }).replace('class="shiki', 'class="overflow-x-auto pt-12 shiki');
    self.postMessage({ type: "highlight:result", id: data.id, html });
  } catch { self.postMessage({ type: "highlight:error", id: data.id }); }
});
