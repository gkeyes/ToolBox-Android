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

async function installPackedArticles(page, { imageArticle = null } = {}) {
  const auth = { serverUrl: SERVER_URL, userId: 7, token: "fixed-synthetic-test-token", authType: "token" };
  const settings = {
    ...STORED_SETTINGS, syncInterval: "0", autoBoardEnabled: false, showFavicon: false,
    ...(imageArticle && { cardImageSize: "none" }),
  };
  const seed = await page.context().newPage();
  let cacheEntries;
  try {
    await installNativeBoundary(seed, { auth, settings });
    await seed.goto(PRODUCTION_URL);
    await expect(seed.locator(".nextflux-article-list-page")).toBeVisible();
    cacheEntries = await seed.evaluate(async (imageArticle) => {
      const db = await import("/src/db/storage.js");
      const syncedAt = "2026-01-01T00:00:00Z";
      const token = await db.prepareArticleSync({ syncedAt });
      const articles = [1, 2, 3, 4].map((id) => ({
        id, feedId: 1, title: `双栏文章 ${id}`,
        content: id === 4
          ? (imageArticle || "") + "<p>测试滚动正文。</p>".repeat(32) + "<p>正文最后一行。</p>"
          : `<p>双栏正文 ${id}</p>`,
        status: "unread", starred: 0, url: `https://example.test/article/${id}`,
        published_at: new Date(Date.parse(syncedAt) - id * 60_000).toISOString(), changed_at: syncedAt,
      }));
      await db.stageArticleSync(token, articles);
      await db.commitArticleSync(token, { feeds: [{ id: 1, title: "测试订阅" }], categories: [] });
      return window.__startupNative.cacheEntries();
    }, imageArticle);
  } finally {
    await seed.close();
  }

  await installNativeBoundary(page, { url: PACKED_URL, auth, settings, cacheEntries });
}

test("packed landscape cards select the last article without a document view transition", async ({ page }) => {
  await page.setViewportSize({ width: 920, height: 420 });
  await installPackedArticles(page);
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

const touchReaderTest = test.extend({ isMobile: true });
const dockLabels = ["阅读模式", "收藏", "AI 摘要", "朗读文章", "分享", "更多"];

const SHARE_IMAGE_SOURCE = "https://reader.example.invalid/proxy/image.png?key=fixture-image";
const SHARE_IMAGE_LINK = "https://article.example.invalid/image-story";
const SHARE_IMAGE_BYTES = "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAECAYAAACzzX7wAAAAEklEQVR4nGNwq131Hx9moL0CANchTYGRtgwQAAAAAElFTkSuQmCC";

async function openPackedImageArticle(page, nativeImages) {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await installPackedArticles(page, { imageArticle: `<p><a href="${SHARE_IMAGE_LINK}"><img src="${SHARE_IMAGE_SOURCE}" alt="可分享测试图片" width="320" height="160"></a></p>` });
  await page.goto(PACKED_URL);
  const card = page.locator('.article-card[data-article-id="4"]');
  await expect(card).toBeVisible();
  await page.evaluate(({ source, data, nativeImages }) => {
    const bytes = Uint8Array.from(atob(data), (char) => char.charCodeAt(0));
    const trace = { mediaRequests: [], shared: [], openedLinks: [] };
    window.__imageShareTrace = trace;
    const network = window.ToolBox.network;
    const request = network.request;
    network.request = (options) => options.method === "PUT" && new URL(options.url).pathname === "/v1/entries"
      ? Promise.resolve({ status: 204, body: "", headers: {} })
      : request(options);
    network.openStream = async ({ url }) => {
      trace.mediaRequests.push(url);
      if (url !== source) throw new Error("Unexpected test media URL");
      return { streamId: "test-share-image", status: 200, headers: { "content-type": "image/png" } };
    };
    network.readStream = async () => ({ data: bytes, done: true });
    network.cancelStream = async () => {};
    window.ToolBox.share = {
      async text(text) { trace.shared.push({ text }); },
      ...(nativeImages && {
        async image(blob) {
          const buffer = new Uint8Array(await blob.arrayBuffer());
          trace.shared.push({ mime: blob.type, data: btoa(String.fromCharCode(...buffer)) });
        },
      }),
    };
    window.ToolBox.browser = { async open(url) { trace.openedLinks.push(url); } };
  }, { source: SHARE_IMAGE_SOURCE, data: SHARE_IMAGE_BYTES, nativeImages });
  await card.tap();
  const reader = page.locator(".article-scroll-area");
  await expect(reader.locator(".article-title")).toHaveText("双栏文章 4");
  await expect(reader.locator(`a[href="${SHARE_IMAGE_LINK}"]`)).toBeVisible();
  const image = reader.getByRole("img", { name: "可分享测试图片" });
  await expect.poll(() => image.evaluate((element) => element.naturalWidth)).toBe(8);
  await image.scrollIntoViewIfNeeded();
  return { reader, image };
}

async function holdImage(page, image, movement = 0) {
  const touch = await page.context().newCDPSession(page);
  const point = await image.evaluate((element) => {
    const { left, top, width, height } = element.getBoundingClientRect();
    const x = left + width / 2;
    const y = Math.min(innerHeight - 100, top + height / 2);
    if (document.elementFromPoint(x, y) !== element) throw new Error("The test image is not reachable");
    return { x, y };
  });
  const dispatch = (type, offset = 0) => touch.send("Input.dispatchTouchEvent", {
    type, touchPoints: type === "touchEnd" ? [] : [{ x: point.x, y: point.y + offset }],
  });
  try {
    await dispatch("touchStart");
    if (movement) await dispatch("touchMove", movement);
    // Cross the hold threshold before releasing, even for the deliberate drag.
    await page.waitForTimeout(650);
    await dispatch("touchEnd");
  } finally {
    await touch.detach();
  }
}

for (const [layout, viewport] of [
  ["portrait", { width: 393, height: 852 }],
  ["landscape", { width: 920, height: 420 }],
]) {
  touchReaderTest(`packed ${layout} long press shares the original linked article image offline`, async ({ page, context }) => {
    await page.setViewportSize(viewport);
    const { reader, image } = await openPackedImageArticle(page, true);
    const trace = () => page.evaluate(() => window.__imageShareTrace);
    expect((await trace()).mediaRequests).toEqual([SHARE_IMAGE_SOURCE]);
    await context.setOffline(true);
    await holdImage(page, image);
    const dialog = page.getByRole("dialog", { name: "图片操作" });
    await expect(dialog).toBeVisible();
    await expect(page.getByRole("dialog", { name: "链接操作" })).toHaveCount(0);
    await expect(page.locator(".PhotoView-Portal")).toHaveCount(0);
    expect((await trace()).shared).toEqual([]);
    await dialog.getByRole("button", { name: "分享图片", exact: true }).tap();
    await expect(dialog).toHaveCount(0);
    expect(await trace()).toEqual({
      mediaRequests: [SHARE_IMAGE_SOURCE],
      shared: [{ mime: "image/png", data: SHARE_IMAGE_BYTES }],
      openedLinks: [],
    });
    await holdImage(page, image, -114);
    await expect.poll(() => reader.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
    await expect(dialog).toHaveCount(0);
  });
}

touchReaderTest("packed older host offers the image link and keeps gallery taps after cancelling", async ({ page }) => {
  await page.setViewportSize({ width: 393, height: 852 });
  const { reader, image } = await openPackedImageArticle(page, false);
  const dialog = page.getByRole("dialog", { name: "图片操作" });
  await holdImage(page, image);
  await expect(dialog.getByRole("button", { name: "分享图片链接", exact: true })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "分享图片", exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => !window.dispatchEvent(new Event("toolboxback", { cancelable: true })))).toBe(true);
  await expect(dialog).toHaveCount(0);
  await image.tap();
  const gallery = page.locator(".PhotoView-Portal");
  await expect(gallery).toBeVisible();
  await gallery.locator(".PhotoView-Slider__toolbarIcon").last().tap();
  await expect(gallery).toHaveCount(0);
  await holdImage(page, image);
  await dialog.getByRole("button", { name: "取消", exact: true }).tap();
  await expect(dialog).toHaveCount(0);
  await image.press("Shift+F10");
  await dialog.getByRole("button", { name: "分享图片链接", exact: true }).tap();
  await expect(dialog).toHaveCount(0);
  const trace = await page.evaluate(() => window.__imageShareTrace);
  expect(trace.shared).toEqual([{ text: SHARE_IMAGE_SOURCE }]);
  expect(trace.openedLinks).toEqual([]);
  await expect(reader.locator(".article-title")).toHaveText("双栏文章 4");
});

async function openPackedReader(page, articleId) {
  await installPackedArticles(page);
  await page.goto(PACKED_URL);
  await page.evaluate(() => {
    const request = window.ToolBox.network.request;
    window.ToolBox.network.request = (options) => options.method === "PUT" && new URL(options.url).pathname === "/v1/entries"
      ? Promise.resolve({ status: 204, body: "", headers: {} })
      : request(options);
  });
  await page.locator(`.article-card[data-article-id="${articleId}"]`).tap();
  await expect(page.locator(".nextflux-continuous-current-surface .article-title")).toHaveText(`双栏文章 ${articleId}`);
}

async function tapEdge(page, button, edge) {
  const { x, y, width, height } = await button.boundingBox();
  await page.touchscreen.tap(edge === "right" ? x + width - 4 : x + 4, y + height / 2);
}

async function withinReader(surface) {
  await expect.poll(() => surface.evaluate((el) => {
    const pane = document.querySelector(".nextflux-article-page").getBoundingClientRect();
    const rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.left >= pane.left - 1 && rect.right <= pane.right + 1 &&
      Math.abs((rect.left + rect.right - pane.left - pane.right) / 2) <= 6;
  }), { message: "the article surface stays centered inside the reader pane" }).toBe(true);
}

touchReaderTest("packed touch reader keeps six glass controls in its pane across rotations and each hit target owns its edges", async ({ page }) => {
  await page.setViewportSize({ width: 920, height: 420 });
  await openPackedReader(page, 2);
  await page.evaluate(() => document.documentElement.style.setProperty("--toolbox-safe-area-top", "32px"));
  const dock = page.locator(".reader-dock");
  for (const [width, height] of [[393, 852], [740, 360], [768, 420], [769, 420], [920, 420], [1024, 724]]) {
    await test.step(`touch ${width}×${height}`, async () => {
      await page.setViewportSize({ width, height });
      await page.emulateMedia({ colorScheme: width === 1024 ? "dark" : "light" });
      const buttons = dock.getByRole("button");
      await expect(buttons).toHaveCount(dockLabels.length);
      await withinReader(dock);
      await expect.poll(() => dock.evaluate((el) => {
        const gap = innerHeight - el.getBoundingClientRect().bottom;
        return gap >= 0 && gap <= 28;
      }), { message: "the whole dock is visible at the bottom" }).toBe(true);
      expect(await dock.evaluate(el => getComputedStyle(el, "::before").backdropFilter)).not.toBe("none");
      const close = page.locator(".reader-topbar .nextflux-close-button");
      expect((await close.boundingBox()).y).toBeGreaterThanOrEqual(32);
      const hitLabels = await buttons.evaluateAll(nodes => nodes.map(el => {
        const rect = el.getBoundingClientRect();
        const hit = x => document.elementFromPoint(x, rect.top + rect.height / 2)?.closest("button")?.getAttribute("aria-label");
        return [hit(rect.left + 4), hit(rect.left + rect.width / 2), hit(rect.right - 4)];
      }));
      expect(hitLabels).toEqual(dockLabels.map(label => [label, label, label]));
      await expect(page.locator(".nextflux-continuous-current-surface .article-title")).toHaveText("双栏文章 2");
    });
  }

  await page.setViewportSize({ width: 768, height: 420 });
  await tapEdge(page, dock.getByRole("button", { name: "更多" }), "right");
  const more = page.getByRole("dialog", { name: "这篇文章" });
  await expect(more).toBeVisible();
  await page.setViewportSize({ width: 769, height: 420 });
  await withinReader(more);
  await expect.poll(async () => {
    const sheet = await more.boundingBox();
    const bar = await dock.boundingBox();
    return Math.round(sheet.y + sheet.height - bar.y);
  }).toBeLessThanOrEqual(1);
  await tapEdge(page, more.getByRole("button", { name: "下一篇" }), "left");
  await expect(more).toHaveCount(0);
  await expect(page.locator(".nextflux-continuous-current-surface .article-title")).toHaveText("双栏文章 3");
  await expect(page.locator('.article-card[data-article-id="2"]')).toHaveAttribute("data-read", "true");

  await tapEdge(page, dock.getByRole("button", { name: "AI 摘要" }), "left");
  const summary = page.getByRole("dialog", { name: "AI 摘要" });
  await expect(summary).toBeVisible();
  for (const [width, height] of [[393, 852], [1024, 724]]) {
    await page.setViewportSize({ width, height });
    await withinReader(summary);
    expect((await summary.boundingBox()).y).toBeGreaterThanOrEqual(32);
  }
  await summary.getByRole("button", { name: "关闭 AI 摘要" }).tap();
  await expect(page.locator(".nextflux-summary-backdrop")).toHaveCount(0);
  await page.locator('.article-card[data-article-id="1"]').tap();
  await expect(page.locator(".nextflux-continuous-current-surface .article-title")).toHaveText("双栏文章 1");
});

for (const width of [768, 920]) {
  touchReaderTest(`packed fresh summary at ${width}px keeps its first paint and close target in the reader pane`, async ({ page }) => {
    await page.setViewportSize({ width, height: 420 });
    await openPackedReader(page, 2);
    // Capture frames from before the lazy dialog mounts; polling settled geometry
    // alone can miss a first-open flash with the close button outside the window.
    await page.evaluate(() => {
      window.firstSummaryFrames = new Promise(resolve => {
        const frames = [];
        const sample = () => {
          const dialog = document.querySelector(".nextflux-summary-dialog");
          if (dialog) {
            const pane = document.querySelector(".nextflux-article-page").getBoundingClientRect();
            const rect = dialog.getBoundingClientRect();
            const close = dialog.querySelector(".nextflux-close-button").getBoundingClientRect();
            frames.push({ pane: [pane.left, pane.right], dialog: [rect.left, rect.right], close: [close.left, close.right] });
          }
          if (frames.length === 6) resolve(frames);
          else requestAnimationFrame(sample);
        };
        requestAnimationFrame(sample);
      });
    });
    await page.getByRole("button", { name: "AI 摘要", exact: true }).tap();
    const dialog = page.getByRole("dialog", { name: "AI 摘要" });
    await expect(dialog).toBeVisible();
    const frames = await page.evaluate(() => window.firstSummaryFrames);
    for (const frame of frames) {
      expect(frame.dialog[0], JSON.stringify(frame)).toBeGreaterThanOrEqual(frame.pane[0] - 1);
      expect(frame.dialog[1], JSON.stringify(frame)).toBeLessThanOrEqual(frame.pane[1] + 1);
      expect(frame.close[0]).toBeGreaterThanOrEqual(frame.dialog[0] - 1);
      expect(frame.close[1]).toBeLessThanOrEqual(frame.dialog[1] + 1);
    }
    await withinReader(dialog);
    const dock = page.locator(".reader-dock");
    const dialogBox = await dialog.boundingBox();
    expect(dialogBox.y + dialogBox.height).toBeLessThanOrEqual((await dock.boundingBox()).y + 1);
    const close = dialog.getByRole("button", { name: "关闭 AI 摘要" });
    await tapEdge(page, close, "right");
    await expect(dialog).toHaveCount(0);
  });
}

touchReaderTest("packed landscape speech controls leave the last line visible and tappable", async ({ page }) => {
  await page.setViewportSize({ width: 920, height: 420 });
  await openPackedReader(page, 4);
  await page.evaluate(() => {
    // Keep the native voice request pending so geometry never depends on external playback.
    Object.assign(window.ToolBox.network, {
      openStream: async () => ({ streamId: "test-speech", status: 200 }),
      readStream: () => new Promise(() => {}),
      cancelStream: async () => {},
    });
  });
  const dock = page.locator(".reader-dock");
  await dock.getByRole("button", { name: "朗读文章" }).tap();
  const player = page.getByRole("region", { name: "文章语音播放器" });
  await expect(player).toBeVisible();
  await withinReader(player);
  await expect(page.getByText("正文最后一行。", { exact: true })).toBeAttached();
  await page.locator(".article-scroll-area").evaluate(el => { el.scrollTop = el.scrollHeight; });
  const lastLine = page.getByText("正文最后一行。", { exact: true });
  const textBox = await lastLine.boundingBox();
  const playerBox = await player.boundingBox();
  expect(textBox.y + textBox.height).toBeLessThanOrEqual(playerBox.y + 1);
  expect(await lastLine.evaluate(el => {
    const box = el.getBoundingClientRect();
    return el.contains(document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2));
  })).toBe(true);
  expect(playerBox.y + playerBox.height).toBeLessThanOrEqual((await dock.boundingBox()).y + 1);
  await player.getByRole("button", { name: "停止朗读" }).tap();
  await expect(player).toHaveCount(0);
});

test.describe("desktop reader", () => {
  test.use({ hasTouch: false, viewport: { width: 920, height: 420 } });
  test("packed mouse layout retains the inline actions", async ({ page }) => {
    await installPackedArticles(page);
    await page.goto(PACKED_URL);
    await page.locator('.article-card[data-article-id="2"]').click();
    await expect(page.locator(".nextflux-continuous-current-surface .article-title")).toHaveText("双栏文章 2");
    await expect(page.getByRole("button", { name: "全文适配", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "更多", exact: true })).toBeHidden();
    const next = page.getByRole("button", { name: "下一篇", exact: true });
    expect((await next.boundingBox()).y).toBeLessThan(64);
    await next.click();
    await expect(page.locator(".nextflux-continuous-current-surface .article-title")).toHaveText("双栏文章 3");

    // At the split boundary the full desktop row overlapped even with a mouse.
    await page.setViewportSize({ width: 768, height: 420 });
    await page.getByRole("button", { name: "更多", exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "上一篇" }).click();
    await expect(page.locator(".nextflux-continuous-current-surface .article-title")).toHaveText("双栏文章 2");
  });
});
