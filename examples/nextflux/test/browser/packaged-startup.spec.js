import { test, expect } from "@playwright/test";
import { installNativeBoundary } from "./fixtures/startup-native.js";

const PACKED_URL = "http://127.0.0.1:4177/";

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

