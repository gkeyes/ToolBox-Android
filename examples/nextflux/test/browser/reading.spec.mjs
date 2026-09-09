import { test, expect } from "@playwright/test";
import { installFixture, openFixture, scrollList, settleFrames } from "./fixture.mjs";

const descending = (top, length) => Array.from({ length }, (_, index) => top - index);

async function verifyBoundary(page, observations) {
  expect(observations.blocked, "the browser must never bypass the fake native network boundary").toEqual([]);
  expect(observations.pageErrors).toEqual([]);
  expect(await page.evaluate(() => window.__nextfluxTest.unexpectedNetwork)).toEqual([]);
  const workers = await page.evaluate(() => ({ urls: window.__nextfluxTest.workerUrls, origin: location.origin }));
  expect(workers.urls.length).toBeGreaterThan(0);
  for (const url of workers.urls) {
    expect(new URL(url).origin).toBe(workers.origin);
    expect(new URL(url).pathname).toMatch(/^\/assets\/.+\.js$/);
  }
}

test("real Worker migration and unread pagination keep a fixed sequence and discard an old filter response", async ({ page }) => {
  const observations = await installFixture(page, { markAsReadOnScroll: true });
  await openFixture(page);
  await expect(page.getByRole("tab", { name: "Unread", exact: true })).toHaveAttribute("aria-selected", "true");
  const root = await page.evaluate(() => window.__nextfluxTest.storageRoot());
  expect(root.version).toBe(3);
  expect(root.lastSyncTime).toBeTruthy();
  expect(await page.evaluate(() => window.__nextfluxTest.storageCalls.some((call) => call.method === "apply"))).toBe(true);

  await page.locator('[data-article-id="95"]').evaluate((element) => { window.__nextfluxTest.originalCard = element; });
  await page.locator('[data-article-id="95"]').click({ button: "right" });
  await page.getByText("Mark as Read", { exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__nextfluxTest.results.filter((result) => result.method === "patchState" && result.ok).length)).toBeGreaterThan(0);
  expect(await page.evaluate(() => window.__nextfluxTest.originalCard === document.querySelector('[data-article-id="95"]'))).toBe(true);

  await page.evaluate(() => window.__nextfluxTest.holdNext("readQueryPage", { page: 2 }));
  await scrollList(page, 500);
  await expect.poll(() => page.evaluate(() => window.__nextfluxTest.held.length)).toBe(1);
  const queryId = await page.evaluate(() => window.__nextfluxTest.held[0].args[0]);
  // The pending prefetch overlaps actual end-of-list events before React can
  // clear loading state. This must still be one request for page two.
  await scrollList(page);
  await page.locator(".v-list").evaluate((element) => {
    element.dispatchEvent(new Event("scroll"));
    element.dispatchEvent(new Event("scroll"));
  });
  await settleFrames(page);
  expect(await page.evaluate((id) => window.__nextfluxTest.calls.filter((call) => call.method === "readQueryPage" && call.args[0] === id && call.args[1] === 2).length, queryId)).toBe(1);
  const pages = await page.evaluate((id) => window.__nextfluxTest.results.filter((result) => result.method === "readQueryPage" && result.args[0] === id && result.ok), queryId);
  expect(pages.find((result) => result.args[1] === 1).ids).toEqual(descending(96, 30));
  expect(pages.find((result) => result.args[1] === 2).ids).toEqual(descending(66, 30));
  await page.evaluate(() => window.__nextfluxTest.releaseHeld());
  await expect(page.locator('[data-article-id="66"]')).toBeAttached();

  await page.evaluate(() => window.__nextfluxTest.holdNext("readQueryPage", { page: 3 }));
  await scrollList(page, 500);
  await expect.poll(() => page.evaluate(() => window.__nextfluxTest.held.length)).toBe(1);
  await page.getByRole("tab", { name: "Starred", exact: true }).click();
  await expect(page.getByRole("tab", { name: "Starred", exact: true })).toHaveAttribute("aria-selected", "true");
  await expect.poll(() => page.evaluate(() => window.__nextfluxTest.results.filter((result) => result.method === "readQueryPage" && result.args[1] === 1 && result.total === 24 && result.ok).length)).toBeGreaterThan(0);
  await page.evaluate(() => window.__nextfluxTest.releaseHeld());
  await settleFrames(page);
  await expect.poll(() => page.locator("[data-article-id]").evaluateAll((elements) => elements.length > 0 && elements.every((element) => Number(element.dataset.articleId) % 4 === 0))).toBe(true);
  expect(await page.evaluate(() => window.__nextfluxTest.calls.filter((call) => call.method === "readArticle").length)).toBe(0);
  await verifyBoundary(page, observations);
});

test("entering an article does not undo immediate scrolling; status changes reuse content and late navigation reads are ignored", async ({ page }) => {
  const observations = await installFixture(page, { reduceMotion: false });
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  await page.waitForFunction(() => {
    const viewport = document.querySelector(".article-scroll-area");
    const image = document.querySelector('.article-content img[alt="Fixture image 96"]');
    return viewport?.scrollHeight > 2000 && image?.complete && image.naturalWidth > 0;
  });
  const started = await page.locator(".article-scroll-area").evaluate((element) => {
    element.scrollTop = 240;
    window.__nextfluxTest.originalContentNode = document.querySelector(".article-content p");
    return performance.now();
  });
  // This includes the old 300 ms reset window, with real animation settings.
  await page.waitForFunction((time) => performance.now() - time >= 450, started);
  expect(await page.locator(".article-scroll-area").evaluate((element) => element.scrollTop)).toBeGreaterThanOrEqual(200);
  const before = await page.evaluate(() => ({
    reads: window.__nextfluxTest.calls.filter((call) => call.method === "readArticle").length,
    preparations: window.__nextfluxTest.readingRequests.filter((request) => request.baseUrl === "https://example.org/articles/96").length,
    patches: window.__nextfluxTest.results.filter((result) => result.method === "patchState" && result.ok).length,
  }));
  await page.locator(".action-buttons button").filter({ has: page.locator("svg.lucide-star") }).click();
  await expect.poll(() => page.evaluate(() => window.__nextfluxTest.results.filter((result) => result.method === "patchState" && result.ok).length)).toBeGreaterThan(before.patches);
  await settleFrames(page);
  const after = await page.evaluate(() => ({
    reads: window.__nextfluxTest.calls.filter((call) => call.method === "readArticle").length,
    preparations: window.__nextfluxTest.readingRequests.filter((request) => request.baseUrl === "https://example.org/articles/96").length,
    sameNode: window.__nextfluxTest.originalContentNode === document.querySelector(".article-content p"),
  }));
  expect(after.reads).toBe(before.reads);
  expect(before.preparations).toBeGreaterThan(0);
  expect(after.preparations).toBe(before.preparations);
  expect(after.sameNode).toBe(true);

  const readerToggle = page.locator(".action-buttons button").filter({ has: page.locator("svg.lucide-file-text") });
  await readerToggle.click();
  await expect(page.locator(".article-content")).toContainText("FIXTURE_ORIGINAL_96");
  await readerToggle.click();
  await expect(page.locator(".article-content")).toContainText("FIXTURE_BODY_96");
  await expect(page.locator(".article-content")).not.toContainText("FIXTURE_ORIGINAL_96");

  await page.evaluate(() => {
    window.__nextfluxTest.holdNext("readArticle", { articleId: 95 });
    location.hash = "#/article/95";
  });
  await expect.poll(() => page.evaluate(() => window.__nextfluxTest.held.length)).toBe(1);
  await page.evaluate(() => { location.hash = "#/article/94"; });
  await expect(page.locator(".article-title")).toHaveText("Article 94");
  await page.evaluate(() => window.__nextfluxTest.releaseHeld());
  await settleFrames(page);
  await expect(page.locator(".article-title")).toHaveText("Article 94");
  await expect(page.locator(".article-content")).toContainText("FIXTURE_BODY_94");
  await verifyBoundary(page, observations);
});

test("offscreen images keep their layout, reuse a Blob, and stay leased while the gallery is open", async ({ page }) => {
  await page.clock.install();
  const observations = await installFixture(page);
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  await expect(page.locator(".article-body")).toHaveAttribute("data-reading-complete", "true");
  const image = page.locator('.article-content img[alt="Fixture image 96"]');
  await expect.poll(() => image.evaluate((element) => element.complete && element.naturalWidth === 640 && Boolean(element.closest('div[style*="aspect-ratio"]')))).toBe(true);
  const before = await image.evaluate((element) => {
    const box = element.closest('div[style*="aspect-ratio"]');
    window.__nextfluxTest.imageBox = box;
    return {
      url: element.src, height: box.getBoundingClientRect().height,
      scrollHeight: document.querySelector(".article-scroll-area").scrollHeight,
    };
  });
  expect(before.height).toBeGreaterThan(150);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await expect(image).toHaveCount(0);
  const offscreen = await page.evaluate(() => ({
    height: window.__nextfluxTest.imageBox.getBoundingClientRect().height,
    scrollHeight: document.querySelector(".article-scroll-area").scrollHeight,
  }));
  expect(Math.abs(offscreen.height - before.height)).toBeLessThanOrEqual(1);
  expect(Math.abs(offscreen.scrollHeight - before.scrollHeight)).toBeLessThanOrEqual(1);
  await page.setViewportSize({ width: 430, height: 844 });
  const resized = await page.evaluate(() => {
    const rect = window.__nextfluxTest.imageBox.getBoundingClientRect();
    return { width: rect.width, height: rect.height };
  });
  expect(resized.width / resized.height).toBeCloseTo(640 / 360, 2);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = 0; });
  await expect(image).toHaveAttribute("src", before.url);
  expect(await page.evaluate(() => window.__nextfluxTest.network.filter((call) => call.path === "/proxy/fixture/image96").length)).toBe(1);

  await image.click();
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await settleFrames(page);
  await page.clock.fastForward(31_000);
  await expect(image).toHaveAttribute("src", before.url);
  expect(await page.evaluate((url) => window.__nextfluxTest.revoked.includes(url), before.url)).toBe(false);
  expect(await page.evaluate((url) => new Promise((resolve) => {
    const probe = new Image();
    probe.onload = () => resolve(probe.naturalWidth === 640);
    probe.onerror = () => resolve(false);
    probe.src = url;
  }), before.url)).toBe(true);

  await page.keyboard.press("Escape");
  await expect(page.locator(".PhotoView-Portal")).toHaveCount(0);
  await expect(image).toHaveCount(0);
  await page.clock.fastForward(31_000);
  expect(await page.evaluate((url) => window.__nextfluxTest.revoked.includes(url), before.url)).toBe(true);
  await verifyBoundary(page, observations);
});
