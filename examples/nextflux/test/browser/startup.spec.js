import { test, expect } from "@playwright/test";
import {
  AI_SECRET, PRODUCTION_URL, SPEECH_SECRET, STORED_CATEGORY_EXPANDED, STORED_SETTINGS,
  STORED_THEME, UPDATED_AI_SECRET, installNativeBoundary,
} from "./fixtures/startup-native.js";

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

  const updated = await page.evaluate(async ({ fontSize, aiApiKey }) => {
    const { settingsState, updateSettings } = await import("/src/stores/settingsStore.js");
    await updateSettings({ fontSize, aiApiKey });
    await window.__startupNative.flush();
    return {
      settings: settingsState.get(),
      persistedPreferences: window.__startupNative.persistedPreferences(),
      aiSecret: await window.ToolBox.storage.secure.get("nextflux.ai-key.v1"),
      speechSecret: await window.ToolBox.storage.secure.get("nextflux.speech-key.v1"),
      writes: window.__startupNative.writes,
      network: window.__startupNative.network,
    };
  }, { fontSize: 22, aiApiKey: UPDATED_AI_SECRET });
  expect(updated.settings).toMatchObject({
    ...STORED_SETTINGS,
    fontSize: 22,
    aiApiKey: UPDATED_AI_SECRET,
    speechApiKey: SPEECH_SECRET,
  });
  expect(updated.aiSecret).toBe(UPDATED_AI_SECRET);
  expect(updated.speechSecret).toBe(SPEECH_SECRET);
  expect(JSON.parse(updated.persistedPreferences.settings)).toEqual({ ...STORED_SETTINGS, fontSize: 22 });
  const ordinaryStorage = JSON.stringify(updated.persistedPreferences);
  expect(ordinaryStorage).not.toContain(AI_SECRET);
  expect(ordinaryStorage).not.toContain(UPDATED_AI_SECRET);
  expect(ordinaryStorage).not.toContain(SPEECH_SECRET);
  expect(updated.writes).toEqual([
    { operation: "secure.set", key: "nextflux.ai-key.v1" },
    {
      operation: "storage.set", key: "nextflux.preferences.v1",
      changedPreferenceKeys: ["settings"], changedSettingsFields: ["fontSize"], plainSecretLeak: false,
    },
  ]);
  expect(updated.network).toEqual([]);
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
