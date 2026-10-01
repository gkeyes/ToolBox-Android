export const PRODUCTION_URL = "http://127.0.0.1:4176/";
export const STORED_SETTINGS = {
  lineHeight: 1.8,
  fontSize: 20,
  maxWidth: 65,
  alignJustify: false,
  fontFamily: "system-ui",
  titleFontSize: 1.6,
  titleAlignType: "left",
  feedIconShape: "square",
  useGrayIcon: false,
  sortDirection: "desc",
  sortField: "published_at",
  showHiddenFeeds: false,
  markAsReadOnScroll: false,
  cardImageSize: "large",
  showFavicon: true,
  titleLines: 2,
  textPreviewLines: 2,
  showReadingTime: true,
  autoHideToolbar: false,
  syncInterval: "30",
  showLineNumbers: false,
  forceDarkCodeTheme: false,
  defaultExpandCategory: false,
  showUnreadByDefault: false,
  reduceMotion: false,
  borderRadius: 0.4,
  interfaceFontSize: "16",
  showIndicator: true,
  floatingSidebar: false,
  aiBaseUrl: "https://api.openai.com/v1",
  aiModel: "startup-regression-model",
  speechRegion: "global",
  speechModel: "speech-2.8-turbo",
  speechVoiceId: "startup-regression-voice",
  speechPlaybackRate: 1,
  speechReadTitle: true,
  speechPrefetch: true,
  aiPrompt: "You are a helpful assistant that summarizes articles concisely. Provide a clear, structured summary in the same language as the article. Format: just plain text, no markdown.",
};
export const STORED_THEME = { themeMode: "system", lightTheme: "light", darkTheme: "dark" };
export const STORED_CATEGORY_EXPANDED = { 7: true };
export const AI_SECRET = "startup-regression-ai-secret";
export const UPDATED_AI_SECRET = "startup-regression-updated-ai-secret";
export const SPEECH_SECRET = "startup-regression-speech-secret";

export async function installNativeBoundary(page, { denySecure = false, failAuthRead = false, url = PRODUCTION_URL } = {}) {
  const allowedOrigin = new URL(url).origin;
  await page.route("**/*", async (route) => {
    if (new URL(route.request().url()).origin === allowedOrigin) {
      await route.continue();
    } else {
      await route.abort("blockedbyclient");
    }
  });
  await page.addInitScript(({ denySecure, failAuthRead, storedSettings, storedTheme, storedCategoryExpanded, aiSecret, speechSecret }) => {
    const preferences = {
      settings: JSON.stringify(storedSettings),
      theme: JSON.stringify(storedTheme),
      categoryExpanded: JSON.stringify(storedCategoryExpanded),
      language: "zh-CN",
    };
    const plain = new Map([["nextflux.preferences.v1", preferences]]);
    const secure = new Map([
      ["nextflux.ai-key.v1", aiSecret],
      ["nextflux.speech-key.v1", speechSecret],
      ["nextflux.auth", null],
    ]);
    const reads = [];
    const writes = [];
    const network = [];
    let flushHandler;
    const delayed = (value) => new Promise((resolve) => setTimeout(() => resolve(value), 20));
    const recordWrite = (operation, key, value) => {
      const prior = plain.get(key);
      const changedPreferenceKeys = key === "nextflux.preferences.v1"
        ? Object.keys({ ...prior, ...value }).filter((name) => prior?.[name] !== value?.[name])
        : [];
      const priorSettings = typeof prior?.settings === "string" ? JSON.parse(prior.settings) : {};
      const nextSettings = typeof value?.settings === "string" ? JSON.parse(value.settings) : {};
      const changedSettingsFields = changedPreferenceKeys.includes("settings")
        ? Object.keys({ ...priorSettings, ...nextSettings }).filter((name) =>
          JSON.stringify(priorSettings[name]) !== JSON.stringify(nextSettings[name]))
        : [];
      const encoded = JSON.stringify(value);
      writes.push({
        operation, key, changedPreferenceKeys, changedSettingsFields,
        plainSecretLeak: encoded?.includes(aiSecret) || encoded?.includes(speechSecret) || false,
      });
    };
    window.__startupNative = {
      reads,
      writes,
      network,
      flush: () => flushHandler?.(),
      persistedPreferences: () => plain.get("nextflux.preferences.v1"),
    };
    window.ToolBox = {
      ready: async () => {},
      runtime: {
        onStateChanged(callback) {
          callback({ generation: 1, revision: 1, foreground: true, closing: false });
          return () => {};
        },
        registerFlushHandler(callback) {
          flushHandler = callback;
          return () => { flushHandler = undefined; };
        },
      },
      storage: {
        async get(key) {
          reads.push(`storage.get:${key}`);
          return delayed(plain.get(key) ?? null);
        },
        async set(key, value) {
          recordWrite("storage.set", key, value);
          plain.set(key, value);
        },
        async remove(key) {
          recordWrite("storage.remove", key);
          plain.delete(key);
        },
        secure: {
          async get(key) {
            reads.push(`secure.get:${key}`);
            await delayed(null);
            if (denySecure) {
              throw Object.assign(new Error("NATIVE_INTERNAL_DETAILS"), { code: "PERMISSION_DENIED" });
            }
            if (failAuthRead && key === "nextflux.auth") {
              throw Object.assign(new Error("NATIVE_INTERNAL_DETAILS"), { code: "INTERNAL_ERROR" });
            }
            return secure.get(key) ?? null;
          },
          async set(key, value) {
            writes.push({ operation: "secure.set", key });
            secure.set(key, value);
          },
          async remove(key) {
            writes.push({ operation: "secure.remove", key });
            secure.delete(key);
          },
        },
      },
      network: {
        async request(payload) {
          network.push(payload.url);
          throw new Error("Unexpected network request during logged-out startup");
        },
      },
    };
  }, {
    denySecure, failAuthRead, storedSettings: STORED_SETTINGS,
    storedTheme: STORED_THEME, storedCategoryExpanded: STORED_CATEGORY_EXPANDED,
    aiSecret: AI_SECRET, speechSecret: SPEECH_SECRET,
  });
}

