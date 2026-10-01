import { test, expect } from "@playwright/test";

const PRODUCTION_URL = "http://127.0.0.1:4176/";
const STORED_SETTINGS = {
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
const STORED_THEME = { themeMode: "system", lightTheme: "light", darkTheme: "dark" };
const STORED_CATEGORY_EXPANDED = { 7: true };
const AI_SECRET = "startup-regression-ai-secret";
const SPEECH_SECRET = "startup-regression-speech-secret";

async function installNativeBoundary(page, { denySecure = false, failAuthRead = false } = {}) {
  await page.route("**/*", async (route) => {
    if (new URL(route.request().url()).origin === "http://127.0.0.1:4176") {
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

test("production startup restores persisted settings and secure keys without writing", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await installNativeBoundary(page);
  await page.goto(PRODUCTION_URL);
  await expect(page).toHaveURL(/#\/login$/);
  await expect(page.getByRole("heading", { name: "NextFlux" })).toBeVisible();
  await expect(page.getByRole("button", { name: "登 录" })).toBeVisible();
  await expect(page.locator(".toolbox-start-error")).toHaveCount(0);

  const restored = await page.evaluate(async () => {
    const [{ settingsState }, { getPreference }] = await Promise.all([
      import("/src/stores/settingsStore.js"),
      import("/src/toolbox/preferences.js"),
    ]);
    await window.__startupNative.flush();
    return {
      settings: settingsState.get(),
      preference: JSON.parse(getPreference("settings")),
      theme: JSON.parse(getPreference("theme")),
      categoryExpanded: JSON.parse(getPreference("categoryExpanded")),
      persistedPreferences: window.__startupNative.persistedPreferences(),
      reads: window.__startupNative.reads,
      writes: window.__startupNative.writes,
      network: window.__startupNative.network,
    };
  });
  expect(restored.preference).toMatchObject({
    ...STORED_SETTINGS,
    aiApiKey: AI_SECRET,
    speechApiKey: SPEECH_SECRET,
  });
  expect(restored.settings).toMatchObject({
    ...STORED_SETTINGS,
    aiApiKey: AI_SECRET,
    speechApiKey: SPEECH_SECRET,
  });
  expect(restored.theme).toEqual(STORED_THEME);
  expect(restored.categoryExpanded).toEqual(STORED_CATEGORY_EXPANDED);
  expect(JSON.parse(restored.persistedPreferences.settings)).toEqual(STORED_SETTINGS);
  expect(restored.persistedPreferences.settings).not.toContain(AI_SECRET);
  expect(restored.persistedPreferences.settings).not.toContain(SPEECH_SECRET);
  expect(restored.reads).toEqual(expect.arrayContaining([
    "storage.get:nextflux.preferences.v1",
    "secure.get:nextflux.ai-key.v1",
    "secure.get:nextflux.speech-key.v1",
    "secure.get:nextflux.auth",
  ]));
  expect(restored.writes).toEqual([]);
  expect(restored.network).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test("secure-storage permission denial reports the denied permission, not device space", async ({ page }) => {
  await installNativeBoundary(page, { denySecure: true });
  await page.goto(PRODUCTION_URL);
  const startupError = page.locator(".toolbox-start-error");
  await expect(startupError).toBeVisible();
  const message = await startupError.locator("p").innerText();
  expect(message).toContain("安全存储");
  expect(message).not.toContain("设备剩余空间");
  expect(message).not.toContain("NATIVE_INTERNAL_DETAILS");
  const writes = await page.evaluate(() => window.__startupNative.writes);
  expect(writes).toEqual([]);
});

test("an internal error restoring authentication is identified without blaming permissions or space", async ({ page }) => {
  await installNativeBoundary(page, { failAuthRead: true });
  await page.goto(PRODUCTION_URL);
  const startupError = page.locator(".toolbox-start-error");
  await expect(startupError).toBeVisible();
  const message = await startupError.locator("p").innerText();
  expect(message).toContain("登录信息");
  expect(message).toContain("INTERNAL_ERROR");
  expect(message).not.toContain("权限");
  expect(message).not.toContain("设备剩余空间");
  expect(message).not.toContain("NATIVE_INTERNAL_DETAILS");
  const native = await page.evaluate(() => ({
    reads: window.__startupNative.reads,
    writes: window.__startupNative.writes,
  }));
  expect(native.reads).toContain("secure.get:nextflux.auth");
  expect(native.writes).toEqual([]);
});
