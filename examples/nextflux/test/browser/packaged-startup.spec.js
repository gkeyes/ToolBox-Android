import { test, expect } from "@playwright/test";
import { PRODUCTION_URL, STORED_SETTINGS, installNativeBoundary } from "./fixtures/startup-native.js";

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

test("packed landscape cards select the last article without a document view transition", async ({ page, context }) => {
  const auth = { serverUrl: SERVER_URL, userId: 7, token: "fixed-synthetic-test-token", authType: "token" };
  const settings = { ...STORED_SETTINGS, syncInterval: "0", autoBoardEnabled: false, showFavicon: false };
  const seed = await context.newPage();
  let cacheEntries;
  try {
    await installNativeBoundary(seed, { auth, settings });
    await seed.goto(PRODUCTION_URL);
    await expect(seed.locator(".nextflux-article-list-page")).toBeVisible();
    cacheEntries = await seed.evaluate(async () => {
      const db = await import("/src/db/storage.js");
      const syncedAt = "2026-01-01T00:00:00Z";
      const token = await db.prepareArticleSync({ syncedAt });
      const articles = [1, 2, 3].map((id) => ({
        id, feedId: 1, title: `双栏文章 ${id}`, content: `<p>双栏正文 ${id}</p>`,
        status: "unread", starred: 0, url: `https://example.test/article/${id}`,
        published_at: new Date(Date.parse(syncedAt) - id * 60_000).toISOString(), changed_at: syncedAt,
      }));
      await db.stageArticleSync(token, articles);
      await db.commitArticleSync(token, { feeds: [{ id: 1, title: "测试订阅" }], categories: [] });
      return window.__startupNative.cacheEntries();
    });
  } finally {
    await seed.close();
  }

  await page.setViewportSize({ width: 920, height: 420 });
  await installNativeBoundary(page, { url: PACKED_URL, auth, settings, cacheEntries });
  await page.addInitScript(() => {
    const start = document.startViewTransition;
    window.documentViewTransitionCalls = 0;
    if (typeof start === "function") document.startViewTransition = function (...args) {
      window.documentViewTransitionCalls++;
      return start.apply(this, args);
    };
  });
  await page.goto(PACKED_URL);
  const list = page.locator(".nextflux-article-list-page");
  const reader = page.locator(".article-scroll-area");
  const card = (id) => list.locator(`.article-card[data-article-id="${id}"]`);
  await expect(card(1)).toBeVisible();
  expect(await page.evaluate(() => typeof document.startViewTransition)).toBe("function");
  await page.evaluate(() => {
    const request = window.ToolBox.network.request;
    window.ToolBox.network.request = (options) => options.method === "PUT" && new URL(options.url).pathname === "/v1/entries"
      ? Promise.resolve({ status: 204, body: "", headers: {} })
      : request(options);
  });

  await card(1).tap();
  await expect(reader.locator(".article-title")).toHaveText("双栏文章 1");
  expect(await page.evaluate(() => window.documentViewTransitionCalls)).toBe(0);
  const listBox = await list.boundingBox();
  const readerBox = await reader.boundingBox();
  expect(readerBox.x).toBeGreaterThanOrEqual(listBox.x + listBox.width);
  await expect(reader.locator(".article-body")).toHaveText("双栏正文 1");
  await expect(card(1)).toHaveAttribute("data-read", "true");

  const points = await Promise.all([2, 1, 3].map(async (id) => {
    const { x, y, width, height } = await card(id).boundingBox();
    return { x: x + width / 2, y: y + height / 2 };
  }));
  for (const { x, y } of points) await page.touchscreen.tap(x, y);
  await expect(reader.locator(".article-title")).toHaveText("双栏文章 3");
  await expect(reader.locator(".article-body")).toHaveText("双栏正文 3");
  await expect(list.locator(".article-card.is-active")).toHaveAttribute("data-article-id", "3");
  await expect(card(3)).toHaveAttribute("data-read", "true");
  expect(await page.evaluate(() => window.documentViewTransitionCalls)).toBe(0);
});
