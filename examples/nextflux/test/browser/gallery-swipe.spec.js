import { test, expect } from "@playwright/test";
// Regressions found by the independent audit (NF-1, NF-2): gestures are
// touch-only, like the phone WebView that sends no pointer events.
const IMG = "https://reader.example.invalid/proxy/image?signature=nf1";

// Touch-only: hit-test the point like a real phone, dispatch TouchEvent, no pointer/mouse events.
async function touchAt(page, type, x, y) {
  return page.evaluate(({ type, x, y }) => {
    window.__nf1Target = type === "touchstart" ? document.elementFromPoint(x, y) : window.__nf1Target;
    const target = window.__nf1Target;
    const point = new Touch({ identifier: 1, target, clientX: x, clientY: y, pageX: x, pageY: y, screenX: x, screenY: y });
    const ev = new TouchEvent(type, { touches: type === "touchend" ? [] : [point], targetTouches: type === "touchend" ? [] : [point], changedTouches: [point], bubbles: true, cancelable: true, composed: true });
    target.dispatchEvent(ev);
    return { prevented: ev.defaultPrevented, target: target.className?.toString?.().slice(0, 60) || target.tagName };
  }, { type, x, y });
}
async function swipeBack(page) {
  const start = await touchAt(page, "touchstart", 60, 450);
  for (const x of [70, 90, 130, 180, 240, 280]) await touchAt(page, "touchmove", x, 452);
  await touchAt(page, "touchend", 280, 452);
  return start.target;
}
async function state(page) {
  return page.evaluate(async () => {
    const { imageGalleryActive } = await import("/article-navigation-state.js");
    return {
      route: location.hash,
      galleryActive: imageGalleryActive.get(),
      portals: [...document.querySelectorAll(".PhotoView-Portal")].map((p) => ({ vis: p.style.visibility, pe: p.style.pointerEvents })),
      bodyOverflow: getComputedStyle(document.body).overflow,
      hit: document.elementFromPoint(200, 450)?.className?.toString().slice(0, 50),
    };
  });
}
async function openArticleWithImage(page) {
  await page.goto("/article-navigation.html#/");
  await page.evaluate(async (src) => {
    const { rows } = await import("/article-navigation-state.js");
    rows[0].content = `<p>前言段落。</p><p><img src="${src}" alt="nf1" width="640" height="320"></p>` + rows[0].content;
  }, IMG);
  await page.getByRole("button", { name: "打开文章 1", exact: true }).click();
  await expect(page.locator(".article-scroll-area .article-title")).toHaveText("文章 1");
  await expect(page.locator(".nextflux-article-page")).toHaveAttribute("data-navigation-state", "idle");
  await expect.poll(() => page.evaluate(async (s) => (await import("/media-transport.js")).pendingRequests(s), IMG)).toBe(1);
  await page.evaluate(async (s) => (await import("/media-transport.js")).settleImage(s), IMG);
  await expect(page.locator(".article-scroll-area img[alt=nf1]")).toBeVisible();
}
// Clicks via element.click(): a click event only, no pointer events.
const openImage = (page) => page.locator(".article-scroll-area img[alt=nf1]").evaluate((el) => el.click());
const closeGallery = (page) => page.locator(".PhotoView-Slider__toolbarIcon").last().evaluate((el) => el.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true })));

test("after viewing an image, swipe back still leaves the article", async ({ page }) => {
  await openArticleWithImage(page);
  await openImage(page);
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  await closeGallery(page);
  await page.waitForTimeout(1500);
  await swipeBack(page);
  await expect.poll(() => page.evaluate(() => location.hash)).toBe("#/");
});

for (const gap of [100, 400, 800]) test(`reopening an image ${gap}ms after closing keeps the viewer and swipe back`, async ({ page }) => {
  await openArticleWithImage(page);
  await openImage(page);
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  await page.waitForTimeout(500);
  await closeGallery(page);
  await page.waitForTimeout(gap);
  await openImage(page);
  // Past the old stuck-gallery timer: the reopened viewer must still be shown.
  await page.waitForTimeout(1500);
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  expect((await state(page)).portals.every(({ vis }) => vis !== "hidden")).toBe(true);
  await closeGallery(page);
  await page.waitForTimeout(1500);
  const before = await state(page);
  expect(before.galleryActive).toBe(false);
  await touchAt(page, "touchstart", 200, 600);
  expect((await touchAt(page, "touchmove", 201, 560)).prevented, "the article scrolls again").toBe(false);
  await touchAt(page, "touchend", 201, 560);
  await swipeBack(page);
  await expect.poll(() => page.evaluate(() => location.hash)).toBe("#/");
});

test("a pull for the next article dragged back below its start still allows swipe back", async ({ page }) => {
  await page.goto("/article-navigation.html#/article/1");
  await expect(page.locator(".article-scroll-area [data-reading-complete]")).toHaveAttribute("data-reading-complete", "true");
  await expect(page.locator(".nextflux-article-page")).toHaveAttribute("data-navigation-state", "idle");
  await page.locator(".article-scroll-area").evaluate((el) => { el.scrollTop = el.scrollHeight; });
  await page.waitForTimeout(300);
  await touchAt(page, "touchstart", 150, 700);
  for (const y of [680, 660, 700, 760, 800]) await touchAt(page, "touchmove", 151, y);
  await touchAt(page, "touchend", 151, 800);
  await expect.poll(() => page.locator(".nextflux-article-page").evaluate((el) => el.dataset.readingMotion ?? null)).toBe(null);
  await swipeBack(page);
  await expect.poll(() => page.evaluate(() => location.hash)).toBe("#/");
});
