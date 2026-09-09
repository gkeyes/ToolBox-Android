import { useEffect } from "react";
import { computed } from "nanostores";
import { useStore } from "@nanostores/react";
import { settingsState } from "@/stores/settingsStore";
import { activeArticle } from "@/stores/articlesStore";
import { getFontFamilyValue } from "@/lib/fontLoader";
import { startReadingFontLoader } from "@/lib/readingFontLoader";
import { toast } from "sonner";

// Read/star/sync updates do not resubscribe or rerender the loader. Body text is
// selected by reference; it is never copied into a key or provider URL.
const selectedFont = computed(settingsState, (settings) => settings.fontFamily);
const selectedArticleId = computed(activeArticle, (article) => article?.id ?? null);
const selectedArticleContent = computed(activeArticle, (article) => article?.content ?? null);

export function useFontLoader() {
  const fontFamily = useStore(selectedFont);
  const articleId = useStore(selectedArticleId);
  const content = useStore(selectedArticleContent);
  useEffect(() => startReadingFontLoader(fontFamily, {
    articleId,
    onError: (error) => toast.error(error.message || "在线字体加载失败，当前使用系统字体；可在阅读设置中重试。"),
  }), [fontFamily, articleId, content]);
  return getFontFamilyValue(fontFamily);
}
export default useFontLoader;
