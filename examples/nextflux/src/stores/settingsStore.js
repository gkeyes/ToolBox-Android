import { persistentAtom } from "@nanostores/persistent";
import { deferPreferenceWrites, flushPreferences } from "../toolbox/preferences.js";

const defaultValue = {
  lineHeight: 1.8,
  fontSize: 16,
  maxWidth: 65, // 单位为ch
  alignJustify: false,
  fontFamily: "system-ui",
  titleFontSize: 1.6, // 标题相对于正文大小的倍数
  titleAlignType: "left",
  articleTitleLines: 5, // 正文页标题最多显示的行数（1–5）
  feedIconShape: "square", // circle, square
  useGrayIcon: false,
  sortDirection: "desc", // asc, desc
  sortField: "published_at", // published_at, created_at
  showHiddenFeeds: false,
  hideDuplicateArticles: true, // 标题完全相同或原文链接相同的文章只显示第一条
  markAsReadOnScroll: false,
  cardImageSize: "large", // none, small, large
  articleListStyle: "lines", // lines (style 1: hairlines + day headers), cards (style 2: paper cards)
  showFavicon: true,
  titleLines: 2,
  textPreviewLines: 2,
  listFontScale: 1, // 文章列表字号倍数（0.85–1.3）
  feedFontScale: 1, // 订阅源列表（侧边栏）字号倍数（0.5–1.5）
  showReadingTime: true,
  autoHideToolbar: false,
  syncInterval: "15", // 添加同步间隔设置，默认15分钟
  showLineNumbers: false,
  forceDarkCodeTheme: false,
  defaultExpandCategory: false, // 默认展开分类
  showUnreadByDefault: false,
  reduceMotion: false,
  borderRadius: 0.4, // 0-0.5rem
  interfaceFontSize: "16",
  showIndicator: true,
  floatingSidebar: false,
  aiApiKey: "",
  aiBaseUrl: "https://api.openai.com/v1",
  aiModel: "gpt-4o-mini",
  speechApiKey: "",
  speechRegion: "global",
  speechModel: "speech-2.8-turbo",
  speechVoiceId: "presenter_male",
  speechPlaybackRate: 1,
  speechReadTitle: true,
  speechPrefetch: true,
  aiPrompt:
    "You are a helpful assistant that summarizes articles concisely. Provide a clear, structured summary in the same language as the article. Format: just plain text, no markdown.",
};

// A damaged stored value must not stop the app from starting: fall back to the
// defaults for anything that is not a plain JSON object.
export function decodeSettings(str) {
  let storedValue;
  try { storedValue = JSON.parse(str); } catch { return { ...defaultValue }; }
  if (!storedValue || typeof storedValue !== "object" || Array.isArray(storedValue)) return { ...defaultValue };
  return { ...defaultValue, ...storedValue };
}

export const settingsState = persistentAtom("settings", defaultValue, {
  encode: (value) => {
    const filteredValue = Object.keys(value).reduce((acc, key) => {
      if (key in defaultValue) {
        acc[key] = value[key];
      }
      return acc;
    }, {});
    return JSON.stringify(filteredValue);
  },
  decode: decodeSettings,
});

// immediate: false batches the write with other deferred changes for a short
// pause (rapid slider commits); otherwise the returned promise is the write.
export const updateSettings = (settingsChanges, { immediate = true } = {}) => {
  const apply = () => settingsState.set({ ...settingsState.get(), ...settingsChanges });
  if (!immediate) {
    deferPreferenceWrites(apply);
    return Promise.resolve();
  }
  apply();
  return flushPreferences();
};

export const resetSettings = () => {
  // 定义阅读相关的设置项
  const readingSettings = [
    "lineHeight",
    "fontSize",
    "maxWidth",
    "alignJustify",
    "fontFamily",
    "titleFontSize",
    "titleAlignType",
    "articleTitleLines",
    "autoHideToolbar",
    "showLineNumbers",
    "forceDarkCodeTheme",
  ];
  const currentSettings = settingsState.get();
  const newSettings = { ...currentSettings };

  // 只重置阅读相关的设置
  readingSettings.forEach((key) => {
    newSettings[key] = defaultValue[key];
  });

  settingsState.set(newSettings);
};
