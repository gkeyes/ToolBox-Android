import { test, expect } from "@playwright/test";
import { STORED_SETTINGS, installNativeBoundary } from "./fixtures/startup-native.js";

const PACKED_URL = "http://127.0.0.1:4177/";
const SERVER_URL = "https://miniflux.xiaochen.win";
const INTEGRATIONS_URL = `${SERVER_URL}/v1/integrations/status`;

test("packed production entry opens the logged-out page through bundled chunks", async ({ page }) => {
  const pageErrors = [];
  const bundleRequests = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.origin === new URL(PACKED_URL).origin && url.pathname.endsWith(".js")) {
      bundleRequests.push(url.pathname);
    }
  });
  await installNativeBoundary(page, { url: PACKED_URL });
  await page.goto(PACKED_URL);
  await expect(page).toHaveURL(/#\/login$/);
  await expect(page.getByRole("heading", { name: "NextFlux" })).toBeVisible();
  await expect(page.getByRole("button", { name: "登 录" })).toBeVisible();
  await expect(page.locator(".toolbox-start-error")).toHaveCount(0);
  expect(bundleRequests).toContainEqual(expect.stringMatching(/^\/assets\/index-[A-Za-z0-9_-]+\.js$/));
  expect(bundleRequests).toContainEqual(expect.stringMatching(/^\/assets\/render-[A-Za-z0-9_-]+\.js$/));
  expect(pageErrors).toEqual([]);
  const native = await page.evaluate(async () => {
    await window.__startupNative.flush();
    return {
      reads: window.__startupNative.reads,
      writes: window.__startupNative.writes,
      network: window.__startupNative.network,
    };
  });
  expect(native.reads).toEqual(expect.arrayContaining([
    "storage.get:nextflux.preferences.v1",
    "secure.get:nextflux.ai-key.v1",
    "secure.get:nextflux.speech-key.v1",
    "secure.get:nextflux.auth",
  ]));
  expect(native.writes).toEqual([]);
  expect(native.network).toEqual([]);
});

test("packed production entry restores a signed-in account from its v3 cache", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await installNativeBoundary(page, {
    url: PACKED_URL,
    auth: {
      serverUrl: SERVER_URL, username: "startup-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0" },
    // Synthetic account-bound empty root using the v3 schema from 1.0.34.
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: SERVER_URL, userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PACKED_URL);
  await expect(page.locator(".nextflux-article-list-page")).toBeVisible();
  await expect(page.locator(".toolbox-start-error")).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.__startupNative.network)).toContain(INTEGRATIONS_URL);
  const native = await page.evaluate(() => ({
    reads: window.__startupNative.reads,
    network: window.__startupNative.network,
  }));
  expect(native.reads).toContain("storage.getMany:nextflux.cache.v3.root");
  expect(native.network).toEqual([INTEGRATIONS_URL]);
  expect(pageErrors).toEqual([]);
});
