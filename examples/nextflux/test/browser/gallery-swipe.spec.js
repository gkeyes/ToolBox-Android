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
    const ev = new TouchEvent(type, { touches: type === "touchend" || type === "touchcancel" ? [] : [point], targetTouches: type === "touchend" || type === "touchcancel" ? [] : [point], changedTouches: [point], bubbles: true, cancelable: true, composed: true });
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
// `images` article images: nf1, nf2, nf3 (true means two).
async function openArticleWithImage(page, images = 1) {
  const count = images === true ? 2 : Number(images) || 1;
  const sources = Array.from({ length: count }, (_, i) => (i ? `${IMG}&b=${i + 1}` : IMG));
  await page.goto("/article-navigation.html#/");
  await page.evaluate(async (sources) => {
    const { rows } = await import("/article-navigation-state.js");
    rows[0].content = "<p>前言段落。</p>" + sources.map((src, i) => `<p><img src="${src}" alt="nf${i + 1}" width="640" height="320"></p>`).join("") + rows[0].content;
  }, sources);
  await page.getByRole("button", { name: "打开文章 1", exact: true }).click();
  await expect(page.locator(".article-scroll-area .article-title")).toHaveText("文章 1");
  await expect(page.locator(".nextflux-article-page")).toHaveAttribute("data-navigation-state", "idle");
  for (const [i, src] of sources.entries()) {
    await expect.poll(() => page.evaluate(async (s) => (await import("/media-transport.js")).pendingRequests(s), src)).toBe(1);
    await page.evaluate(async (s) => (await import("/media-transport.js")).settleImage(s), src);
    await expect(page.locator(`.article-scroll-area img[alt=nf${i + 1}]`)).toBeVisible();
  }
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

// A screenshot gesture cancels the touch mid-drag: the viewer must not stay
// locked to that drag (NF gallery after screenshot).
test("a cancelled drag in the viewer still lets the next swipe change image, and the article scrolls after closing", async ({ page }) => {
  await openArticleWithImage(page, true);
  await openImage(page);
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  await page.waitForTimeout(1000);
  await touchAt(page, "touchstart", 200, 400);
  for (const y of [430, 470]) { await touchAt(page, "touchmove", 200, y); await page.waitForTimeout(30); }
  await touchAt(page, "touchcancel", 200, 470);
  await page.waitForTimeout(500);
  await touchAt(page, "touchstart", 300, 420);
  for (const x of [280, 240, 180, 120, 80]) { await touchAt(page, "touchmove", x, 421); await page.waitForTimeout(30); }
  await touchAt(page, "touchend", 80, 421);
  await expect(page.locator(".PhotoView-Slider__Counter")).toHaveText("2 / 2");
  await closeGallery(page);
  await page.waitForTimeout(1500);
  expect((await state(page)).galleryActive).toBe(false);
  await touchAt(page, "touchstart", 200, 600);
  expect((await touchAt(page, "touchmove", 201, 560)).prevented, "the article scrolls again").toBe(false);
  await touchAt(page, "touchend", 201, 560);
});

async function openTwoImageViewer(page) {
  await openArticleWithImage(page, true);
  await openImage(page);
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  await page.waitForTimeout(1000);
}
async function drag(page, from, points) {
  await touchAt(page, "touchstart", ...from);
  for (const point of points) { await touchAt(page, "touchmove", ...point); await page.waitForTimeout(30); }
}
const counter = (page) => page.locator(".PhotoView-Slider__Counter");
// The middle of three images, so a wrong release could move either way.
async function openMiddleViewer(page) {
  await openArticleWithImage(page, 3);
  await page.locator(".article-scroll-area img[alt=nf2]").evaluate((el) => el.click());
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  await page.waitForTimeout(1000);
  await expect(counter(page)).toHaveText("2 / 3");
}

// A cancelled gesture must snap back, not finish: a three-finger screenshot
// swipe must not close the viewer or change the image behind it.
test("a cancelled long pull-down leaves the viewer open", async ({ page }) => {
  await openTwoImageViewer(page);
  await drag(page, [200, 300], [[200, 330], [200, 380], [200, 450], [200, 520]]);
  await touchAt(page, "touchcancel", 200, 520);
  await page.waitForTimeout(1200);
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  expect((await state(page)).galleryActive).toBe(true);
  await expect(counter(page)).toHaveText("1 / 2");
});

// The viewer measures a drag from its first move past 20px, so the first
// move may already be far from where the finger landed.
for (const [name, from, moves] of [
  ["a cancelled sideways drag keeps the middle image", [300, 420], [[280, 421], [240, 421], [180, 421], [120, 421]]],
  ["a cancelled drag whose first move is large keeps the image (left)", [300, 420], [[200, 421], [150, 421]]],
  ["a cancelled drag whose first move is large keeps the image (right)", [100, 420], [[200, 421], [250, 421]]],
]) test(name, async ({ page }) => {
  await openMiddleViewer(page);
  await drag(page, from, moves);
  await touchAt(page, "touchcancel", ...moves.at(-1));
  await page.waitForTimeout(800);
  await expect(counter(page)).toHaveText("2 / 3");
});

test("a cancelled pull whose first move is large leaves the viewer open", async ({ page }) => {
  await openMiddleViewer(page);
  await drag(page, [200, 300], [[200, 430], [200, 480]]);
  await touchAt(page, "touchcancel", 200, 480);
  await page.waitForTimeout(1200);
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  expect((await state(page)).galleryActive).toBe(true);
  await expect(counter(page)).toHaveText("2 / 3");
});

// A touchend that never arrived must not leave its start for the next gesture.
test("a lost touchend does not move the release point of the next cancelled drag", async ({ page }) => {
  await openMiddleViewer(page);
  await touchAt(page, "touchstart", 60, 420);
  await page.waitForTimeout(100);
  await drag(page, [300, 420], [[280, 421], [240, 421], [180, 421]]);
  await touchAt(page, "touchcancel", 180, 421);
  await page.waitForTimeout(800);
  await expect(counter(page)).toHaveText("2 / 3");
});

// The page can be hidden or lose focus mid-drag without any touchcancel.
for (const lost of ["hidden", "blur"]) test(`a drag interrupted by ${lost} releases the viewer`, async ({ page }) => {
  await openTwoImageViewer(page);
  const photo = page.locator(".PhotoView__PhotoBox").first();
  const rest = await photo.evaluate((el) => el.style.transform);
  await drag(page, [200, 400], [[200, 430], [200, 470]]);
  expect(await photo.evaluate((el) => el.style.transform), "the drag moved the image").not.toBe(rest);
  await page.evaluate((lost) => {
    if (lost === "hidden") {
      Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true });
      document.dispatchEvent(new Event("visibilitychange"));
    } else window.dispatchEvent(new Event("blur"));
  }, lost);
  await page.waitForTimeout(500);
  await page.evaluate(() => { delete document.visibilityState; });
  await expect.poll(() => photo.evaluate((el) => el.style.transform), { message: "the image snapped back" }).toBe(rest);
  await drag(page, [300, 420], [[280, 421], [240, 421], [180, 421], [120, 421], [80, 421]]);
  await touchAt(page, "touchend", 80, 421);
  await expect(counter(page)).toHaveText("2 / 2");
});
