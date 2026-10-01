import { test, expect } from "@playwright/test";

const PRODUCTION_URL = "http://127.0.0.1:4176/";
const STORED_SETTINGS = {
  fontSize: 20,
  syncInterval: "30",
  aiModel: "startup-regression-model",
  speechVoiceId: "startup-regression-voice",
};
const AI_SECRET = "startup-regression-ai-secret";
const SPEECH_SECRET = "startup-regression-speech-secret";

async function installNativeBoundary(page, { denySecure = false } = {}) {
  await page.route("**/*", async (route) => {
    if (new URL(route.request().url()).origin === "http://127.0.0.1:4176") {
      await route.continue();
    } else {
      await route.abort("blockedbyclient");
    }
  });
  await page.addInitScript(({ denySecure, storedSettings, aiSecret, speechSecret }) => {
    const preferences = {
      settings: JSON.stringify(storedSettings),
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
    window.__startupNative = {
      reads,
      writes,
      network,
      flush: () => flushHandler?.(),
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
          writes.push(`storage.set:${key}`);
          plain.set(key, value);
        },
        async remove(key) {
          writes.push(`storage.remove:${key}`);
          plain.delete(key);
        },
        secure: {
          async get(key) {
            reads.push(`secure.get:${key}`);
            await delayed(null);
            if (denySecure) {
              throw Object.assign(new Error("NATIVE_INTERNAL_DETAILS"), { code: "PERMISSION_DENIED" });
            }
            return secure.get(key) ?? null;
          },
          async set(key, value) {
            writes.push(`secure.set:${key}`);
            secure.set(key, value);
          },
          async remove(key) {
            writes.push(`secure.remove:${key}`);
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
  }, { denySecure, storedSettings: STORED_SETTINGS, aiSecret: AI_SECRET, speechSecret: SPEECH_SECRET });
}

test("production startup restores persisted settings and secure keys without writing", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await installNativeBoundary(page);
  await page.goto(PRODUCTION_URL);
  await expect(page).toHaveURL(/#\/login$/);
  await expect(page.getByRole("heading", { name: "NextFlux" })).toBeVisible();
  await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
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
