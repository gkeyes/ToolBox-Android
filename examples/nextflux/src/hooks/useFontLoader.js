import { useEffect } from "react";
import { useStore } from "@nanostores/react";
import { settingsState } from "@/stores/settingsStore";
import { activeArticle } from "@/stores/articlesStore";
import { loadFont, getFontFamilyValue } from "@/lib/fontLoader";
import { toast } from "sonner";

// Only visible reading text (plus the next screen) selects local unicode ranges.
// Never put article text into Google Fonts text= queries.
function visibleReadingText() {
  let text = "Aa 中文";
  for (const root of document.querySelectorAll(".article-title, .article-content")) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode()) && text.length < 8000) {
      if (!node.textContent.trim() || node.parentElement?.closest("pre, code, script, style")) continue;
      const rect = node.parentElement?.getBoundingClientRect();
      if (rect && rect.height > 0 && rect.bottom >= -300 && rect.top <= window.innerHeight + 500) text += node.textContent.slice(0, 8000 - text.length);
    }
  }
  return [...new Set(text)].join("");
}

export function useFontLoader() {
  const { fontFamily } = useStore(settingsState);
  const article = useStore(activeArticle);
  useEffect(() => {
    if (!fontFamily) return;
    const controller = new AbortController();
    let stopped = false;
    let busy = false;
    let pending = false;
    let timer;
    let previousText = "";
    let reported = false;
    const hydrate = async () => {
      if (stopped) return;
      if (busy) { pending = true; return; }
      const text = visibleReadingText();
      if (text === previousText) return;
      previousText = text;
      busy = true;
      try { await loadFont(fontFamily, text, { signal: controller.signal }); }
      catch (error) {
        if (!stopped && !reported) {
          toast.error(error.message || "在线字体加载失败，当前使用系统字体；可在阅读设置中重试。");
          reported = true;
        }
      } finally {
        busy = false;
        if (pending) { pending = false; schedule(); }
      }
    };
    const schedule = () => {
      clearTimeout(timer);
      timer = setTimeout(hydrate, 600);
    };
    const retry = () => { previousText = ""; reported = false; schedule(); };
    const observer = new MutationObserver(schedule);
    observer.observe(document.body, { childList: true, subtree: true, characterData: true });
    schedule();
    window.addEventListener("nextflux:retry-font", retry);
    document.addEventListener("scroll", schedule, true);
    window.addEventListener("resize", schedule);
    return () => {
      stopped = true;
      controller.abort();
      observer.disconnect();
      window.removeEventListener("nextflux:retry-font", retry);
      clearTimeout(timer);
      document.removeEventListener("scroll", schedule, true);
      window.removeEventListener("resize", schedule);
    };
  }, [fontFamily, article?.id, article?.content]);
  return getFontFamilyValue(fontFamily);
}
export default useFontLoader;
