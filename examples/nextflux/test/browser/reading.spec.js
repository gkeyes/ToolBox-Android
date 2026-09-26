import { test as base, expect } from "@playwright/test";
import { createReadingParser } from "../../src/reading/parser.js";

const SOURCE = "https://reader.example.invalid/proxy/image?signature=a";
const OTHER_SOURCE = "https://reader.example.invalid/proxy/image?signature=b";

const test = base.extend({
  page: async ({ page }, use, testInfo) => {
    const pageErrors = [];
    const externalRequests = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await page.route("**/*", async (route) => {
      const url = new URL(route.request().url());
      if (url.origin === "http://127.0.0.1:4175") await route.continue();
      else {
        externalRequests.push(url.href);
        await route.abort("blockedbyclient");
      }
    });
    await use(page);
    await testInfo.attach("browser-diagnostics", {
      body: JSON.stringify({ pageErrors, externalRequests }),
      contentType: "application/json",
    });
    expect(pageErrors, "uncaught errors in the mounted production components").toEqual([]);
    expect(externalRequests, "fixture must never contact an external server").toEqual([]);
  },
});

async function openFixture(page) {
  await page.goto("/");
  await page.waitForFunction(() => Boolean(window.readingFixture));
  await expect(page.getByTestId("returns")).toHaveText("0");
}

async function pending(page, source = SOURCE) {
  await expect.poll(() => page.evaluate((value) => window.readingFixture.pendingRequests(value), source)).toBe(1);
}

async function resolveImage(page, source = SOURCE, options = {}) {
  await pending(page, source);
  await page.evaluate(({ source, options }) => window.readingFixture.settleImage(source, options), { source, options });
}

async function decodedImage(page, width, height) {
  const image = page.getByTestId("image-fixture").locator("img");
  await expect(image).toBeVisible();
  await expect.poll(() => image.evaluate((element) => [element.complete, element.naturalWidth, element.naturalHeight])).toEqual([true, width, height]);
}

async function geometry(page) {
  const box = await page.getByTestId("image-fixture").locator(":scope > div").boundingBox();
  const paragraph = await page.getByTestId("after-image").boundingBox();
  expect(box).not.toBeNull();
  expect(paragraph).not.toBeNull();
  return { width: box.width, height: box.height, followingTop: paragraph.y };
}

async function loadingGeometry(page) {
  const fixture = page.getByTestId("image-fixture");
  await expect(fixture.locator("img")).toHaveCount(0);
  await expect(fixture.getByRole("status")).toHaveText("图片加载中…");
  return geometry(page);
}

for (const [orientation, width, height] of [["landscape", 1200, 800], ["portrait", 800, 1200]]) {
  test(`authored ${orientation} dimensions reserve real layout before decoding`, async ({ page }) => {
    await openFixture(page);
    await page.evaluate(({ source, width, height }) => window.readingFixture.setImage({ src: source, width: String(width), height: String(height) }), { source: SOURCE, width, height });
    await pending(page);
    const before = await loadingGeometry(page);
    expect(before.width).toBeCloseTo(400, 0);
    expect(before.height).toBeCloseTo(400 * height / width, 0);

    await resolveImage(page, SOURCE, { width, height });
    await decodedImage(page, width, height);
    const after = await geometry(page);
    expect(Math.abs(after.height - before.height)).toBeLessThanOrEqual(1);
    expect(Math.abs(after.followingTop - before.followingTop)).toBeLessThanOrEqual(1);
  });
}

test("an unknown image learns its decoded dimensions and reserves them after Blob eviction and remount", async ({ page }) => {
  await page.clock.install();
  await openFixture(page);
  const unknown = await loadingGeometry(page);
  expect(unknown.height).toBeCloseTo(48, 0);
  await resolveImage(page);
  await decodedImage(page, 640, 320);
  const learned = await geometry(page);
  expect(learned.height).toBeCloseTo(200, 0);
  expect(learned.followingTop - unknown.followingTop).toBeGreaterThan(100);

  await page.evaluate(() => window.readingFixture.unmountImage());
  await expect(page.getByTestId("image-fixture")).toHaveAttribute("data-mounted", "false");
  // Exercise the actual 30 s idle Blob expiration, preserving dimension memory.
  await page.clock.fastForward(31_000);
  await page.evaluate(() => window.readingFixture.mountImage());
  await pending(page);
  await expect.poll(() => page.evaluate((source) => window.readingFixture.requestCount(source), SOURCE)).toBe(2);
  const remembered = await loadingGeometry(page);
  expect(remembered.height).toBeCloseTo(learned.height, 0);
  expect(Math.abs(remembered.followingTop - learned.followingTop)).toBeLessThanOrEqual(1);
  await resolveImage(page);
  await decodedImage(page, 640, 320);
  expect(Math.abs((await geometry(page)).followingTop - remembered.followingTop)).toBeLessThanOrEqual(1);
});

test("changing only the signed source query does not reuse the previous image or its aspect ratio", async ({ page }) => {
  await openFixture(page);
  await resolveImage(page);
  await decodedImage(page, 640, 320);
  const originalBlob = await page.getByTestId("image-fixture").locator("img").getAttribute("src");
  await page.evaluate((source) => window.readingFixture.setImage({ src: source }), OTHER_SOURCE);
  await pending(page, OTHER_SOURCE);
  expect((await loadingGeometry(page)).height).toBeCloseTo(48, 0);
  await resolveImage(page, OTHER_SOURCE, { width: 320, height: 640 });
  await decodedImage(page, 320, 640);
  expect((await geometry(page)).height).toBeCloseTo(640, 0);
  expect(await page.getByTestId("image-fixture").locator("img").getAttribute("src")).not.toBe(originalBlob);
});

test("the logout media cleanup invalidates learned geometry in retained and newly mounted components", async ({ page }) => {
  await openFixture(page);
  await resolveImage(page);
  await decodedImage(page, 640, 320);
  expect((await geometry(page)).height).toBeCloseTo(200, 0);
  await page.evaluate(() => window.readingFixture.clearSessionMedia());
  await expect(page.locator("#reading-surface")).toHaveAttribute("data-account-epoch", "1");
  expect((await loadingGeometry(page)).height).toBeCloseTo(48, 0);
  expect(await page.evaluate((source) => window.readingFixture.dimensions(source), SOURCE)).toBeNull();

  await page.evaluate(() => window.readingFixture.mountImage());
  await pending(page);
  expect((await loadingGeometry(page)).height).toBeCloseTo(48, 0);
  await resolveImage(page, SOURCE, { width: 320, height: 640 });
  await decodedImage(page, 320, 640);
  expect((await geometry(page)).height).toBeCloseTo(640, 0);
});

for (const failure of ["network", "decode"]) {
  test(`${failure} failure can retry through the real image button and media lease`, async ({ page }) => {
    await openFixture(page);
    await resolveImage(page, SOURCE, { failure });
    const retry = page.getByRole("button", { name: "重试图片" });
    await expect(retry).toBeVisible();
    await retry.click();
    await pending(page);
    await expect(retry).toHaveCount(0);
    await resolveImage(page);
    await decodedImage(page, 640, 320);
    expect(await page.evaluate((source) => window.readingFixture.requestCount(source), SOURCE)).toBe(2);
  });
}

async function touch(page, selector, type, points, changed = points, cancelable = true) {
  return page.locator(selector).evaluate((target, { type, points, changed, cancelable }) => {
    const touches = (values) => values.map(([clientX, clientY, identifier = 1]) => new Touch({
      identifier, target, clientX, clientY, pageX: clientX, pageY: clientY, screenX: clientX, screenY: clientY,
    }));
    const event = new TouchEvent(type, { touches: touches(points), targetTouches: touches(points), changedTouches: touches(changed), bubbles: true, cancelable });
    target.dispatchEvent(event);
    return event.defaultPrevented;
  }, { type, points, changed, cancelable });
}

async function swipe(page, selector = "#reading-surface", points = [[60, 100], [80, 102], [140, 103]]) {
  await touch(page, selector, "touchstart", [points[0]]);
  const prevented = [];
  for (const point of points.slice(1)) prevented.push(await touch(page, selector, "touchmove", [point]));
  await touch(page, selector, "touchend", [], [points.at(-1)]);
  return prevented;
}

test("the dead zone leaves small motion alone and a vertical decision stays native", async ({ page }) => {
  await openFixture(page);
  expect(await swipe(page, "#reading-surface", [[60, 100], [63, 103], [66, 106]])).toEqual([false, false]);
  await expect(page.getByTestId("returns")).toHaveText("0");
  expect(await swipe(page, "#reading-surface", [[60, 100], [64, 114], [180, 160]])).toEqual([false, false]);
  await expect(page.getByTestId("returns")).toHaveText("0");
});

for (const start of [[60, 100], [0, 0]]) {
  test(`right swipe from ${start.join(",")} cancels the first accepted move and returns exactly once`, async ({ page }) => {
    await openFixture(page);
    const [x, y] = start;
    expect(await swipe(page, "#reading-surface", [[x, y], [x + 3, y + 2], [x + 20, y + 3], [x + 80, y + 4]])).toEqual([false, true, true]);
    await expect(page.getByTestId("returns")).toHaveText("1");
    await touch(page, "#reading-surface", "touchend", [], [[x + 90, y + 4]]);
    await expect(page.getByTestId("returns")).toHaveText("1");
  });
}

test("adding a second finger cancels navigation until all fingers end", async ({ page }) => {
  await openFixture(page);
  await touch(page, "#reading-surface", "touchstart", [[60, 100]]);
  await touch(page, "#reading-surface", "touchmove", [[80, 102]]);
  await touch(page, "#reading-surface", "touchstart", [[80, 102], [120, 102, 2]], [[120, 102, 2]]);
  await touch(page, "#reading-surface", "touchend", [[80, 102]], [[120, 102, 2]]);
  expect(await touch(page, "#reading-surface", "touchmove", [[160, 103]])).toBe(false);
  await touch(page, "#reading-surface", "touchend", [], [[160, 103]]);
  await expect(page.getByTestId("returns")).toHaveText("0");
  await swipe(page);
  await expect(page.getByTestId("returns")).toHaveText("1");
});

test("touchcancel clears an accepted swipe without poisoning the next gesture", async ({ page }) => {
  await openFixture(page);
  await touch(page, "#reading-surface", "touchstart", [[60, 100]]);
  expect(await touch(page, "#reading-surface", "touchmove", [[80, 102]])).toBe(true);
  await touch(page, "#reading-surface", "touchcancel", [], [[80, 102]]);
  await touch(page, "#reading-surface", "touchend", [], [[160, 103]]);
  await expect(page.getByTestId("returns")).toHaveText("0");
  await swipe(page);
  await expect(page.getByTestId("returns")).toHaveText("1");
});

test("code, range controls and actual horizontal overflow retain their gestures", async ({ page }) => {
  await openFixture(page);
  expect(await page.locator("#horizontal-container").evaluate((element) => element.scrollWidth > element.clientWidth && getComputedStyle(element).overflowX === "auto")).toBe(true);
  for (const selector of ["#code-target", "#range-target", "#horizontal-target"]) {
    expect(await swipe(page, selector)).toEqual([false, false]);
    await expect(page.getByTestId("returns")).toHaveText("0");
  }
});

test("selected article text blocks navigation and clearing the selection restores it", async ({ page }) => {
  await openFixture(page);
  await page.locator("#selectable-text").evaluate((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
  });
  expect(await swipe(page)).toEqual([false, false]);
  await expect(page.getByTestId("returns")).toHaveText("0");
  await page.evaluate(() => window.getSelection().removeAllRanges());
  await swipe(page);
  await expect(page.getByTestId("returns")).toHaveText("1");
});

test("opening the real photo gallery blocks the article return gesture", async ({ page }) => {
  await openFixture(page);
  await resolveImage(page);
  await decodedImage(page, 640, 320);
  await page.getByTestId("image-fixture").locator("img").click();
  await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  await expect(page.locator("body")).toHaveCSS("overflow", "hidden");
  expect(await page.evaluate(async () => (await import("/stores.js")).imageGalleryActive.get())).toBe(true);
  await page.evaluate(async () => {
    const { imageGalleryActive } = await import("/stores.js");
    window.galleryMovePhases = [];
    const observe = (event) => {
      if (event.target.id !== "reading-surface") return;
      window.galleryMovePhases.push({
        at: event.currentTarget === document ? "document" : "window",
        cancelled: event.defaultPrevented,
        target: event.target.id,
        x: event.touches[0].clientX,
        galleryActive: imageGalleryActive.get(),
      });
    };
    // Registered after the Hook and gallery effects, both in the bubble phase.
    document.addEventListener("touchmove", observe);
    window.addEventListener("touchmove", observe);
    window.removeGalleryMoveObservers = () => {
      document.removeEventListener("touchmove", observe);
      window.removeEventListener("touchmove", observe);
    };
  });
  try {
    // PhotoBox 1.2.7 prevents touchmove on window. The document Hook must leave
    // these behind-portal events alone before the gallery receives them.
    expect(await swipe(page)).toEqual([true, true]);
    const phases = await page.evaluate(() => window.galleryMovePhases);
    expect(phases.map(({ at, cancelled, x }) => [at, cancelled, x])).toEqual([
      ["document", false, 80], ["window", true, 80],
      ["document", false, 140], ["window", true, 140],
    ]);
    expect(phases.every(({ target, galleryActive }) => target === "reading-surface" && galleryActive)).toBe(true);
    await expect(page.getByTestId("returns")).toHaveText("0");
    await expect(page.locator(".PhotoView-Portal")).toBeVisible();
  } finally {
    await page.evaluate(() => window.removeGalleryMoveObservers());
  }
});

test("a modal appearing during a swipe cancels its pending navigation", async ({ page }) => {
  await openFixture(page);
  await touch(page, "#reading-surface", "touchstart", [[60, 100]]);
  expect(await touch(page, "#reading-surface", "touchmove", [[80, 102]])).toBe(true);
  await page.evaluate(() => window.readingFixture.setModalOpen(true));
  await touch(page, "#reading-surface", "touchend", [], [[160, 103]]);
  await expect(page.getByTestId("returns")).toHaveText("0");
  await page.evaluate(() => window.readingFixture.setModalOpen(false));
  await swipe(page);
  await expect(page.getByTestId("returns")).toHaveText("1");
});


base("live NYT Miniflux outerHTML probe", async ({ browser }) => {
  const target = "https://cn.nytimes.com/world/20260925/trump-xi-state-dinner-menu/";
  const page = await browser.newPage({
    locale: "zh-CN",
    userAgent: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
  });
  let response = null;
  let navigationError = null;
  try {
    response = await page.goto(target, { waitUntil: "domcontentloaded", timeout: 25_000 });
  } catch (error) {
    navigationError = String(error?.message || error);
  }

  const matchCount = await page.locator("article.article-content").count().catch(() => 0);
  const probe = {
    url: page.url(),
    status: response?.status?.() ?? null,
    navigationError,
    matchCount,
  };

  if (matchCount > 0) {
    const locator = page.locator("article.article-content").first();
    const outerHTML = await locator.evaluate((root) => root.outerHTML);
    Object.assign(probe, await locator.evaluate((root) => {
      const compact = (value) => String(value || "").replace(/\s+/g, " ").trim();
      const tagCounts = {};
      for (const node of root.querySelectorAll("*")) {
        const tag = node.tagName.toLowerCase();
        tagCounts[tag] = (tagCounts[tag] || 0) + 1;
      }
      const shape = (node) => ({
        tag: node.tagName.toLowerCase(),
        classes: [...node.classList].slice(0, 8),
        childTags: [...node.children].slice(0, 12).map((child) => child.tagName.toLowerCase()),
        childCount: node.children.length,
        textLength: compact(node.textContent).length,
      });
      const directChildren = [...root.children].slice(0, 30).map(shape);
      const emphasizedContainers = [...root.querySelectorAll("div,p,span,section")]
        .filter((node) => {
          const children = [...node.children];
          if (children.length !== 1) return false;
          const child = children[0];
          if (!["STRONG", "B"].includes(child.tagName)) return false;
          const own = compact(node.textContent);
          const emphasized = compact(child.textContent);
          return own && emphasized && emphasized.length >= Math.max(1, Math.floor(own.length * .8));
        })
        .slice(0, 30)
        .map(shape);
      return {
        rootClasses: [...root.classList],
        outerHTMLLength: root.outerHTML.length,
        tagCounts,
        directChildren,
        emphasizedContainers,
      };
    }));

    const parser = createReadingParser(outerHTML, target);
    const operations = [];
    for (;;) {
      const batch = parser.next();
      operations.push(...batch.operations);
      if (batch.done) break;
    }
    probe.analysis = {
      textChars: parser.analysis.textChars,
      paragraphs: parser.analysis.paragraphs,
      divs: parser.analysis.divs,
      headings: parser.analysis.headings,
      listItems: parser.analysis.listItems,
      semanticBlocks: parser.analysis.semanticBlocks,
      structureSparse: parser.analysis.structureSparse,
    };
    probe.semanticOperations = operations
      .filter((operation) => operation.type === "blockify" && operation.role)
      .map(({ tag, role }) => ({ tag, role }));
  }

  console.log("NYT_LIVE_PROBE=" + JSON.stringify(probe));
  await page.close();

  expect(navigationError, "live NYT page should be reachable from CI").toBeNull();
  expect(response?.status(), "live NYT response should be successful").toBeLessThan(400);
  expect(matchCount, "Miniflux rule article.article-content must match the live page").toBeGreaterThan(0);
  expect(probe.outerHTMLLength, "matched outerHTML must contain a real article subtree").toBeGreaterThan(1000);\n  expect(probe.rendered?.roles?.length, "production renderer must retain inferred hierarchy roles").toBeGreaterThan(0);
});
