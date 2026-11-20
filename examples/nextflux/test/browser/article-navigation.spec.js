import { test, expect } from "@playwright/test";

const nextArticleUrl = "https://example.invalid/article/2";

async function touch(page, type, x, y, selector = ".article-scroll-area") {
  return page.locator(selector).evaluate((target, { type, x, y }) => {
    const point = new Touch({ identifier: 1, target, clientX: x, clientY: y });
    const event = new TouchEvent(type, {
      touches: type === "touchend" || type === "touchcancel" ? [] : [point],
      changedTouches: [point], bubbles: true, cancelable: true,
    });
    target.dispatchEvent(event);
    return event.defaultPrevented;
  }, { type, x, y });
}

async function openArticle(page) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/article-navigation.html#/article/1");
  await expect(page).toHaveTitle("NextFlux article navigation");
  await expect(page.locator(".article-scroll-area .article-title")).toHaveText("文章 1");
  await expect(page.locator(".article-scroll-area [data-reading-complete]")).toHaveAttribute("data-reading-complete", "true");
  return errors;
}

test("clicking the article RSS source closes the article and restores list interaction", async ({ page }) => {
  const errors = await openArticle(page);
  const articlePage = page.locator(".nextflux-article-page");
  await expect(articlePage).toHaveAttribute("data-navigation-state", "idle");
  await articlePage.locator(".nextflux-article-feed-button").click();
  await expect(page.getByTestId("route")).toHaveText("/feed/1");
  await expect(articlePage).toBeHidden();
  await expect(articlePage).toHaveAttribute("data-navigation-state", "idle");
  await expect(articlePage).not.toHaveAttribute("data-reading-motion");

  await page.getByRole("button", { name: "打开文章 2", exact: true }).click();
  await expect(page.getByTestId("route")).toHaveText("/feed/1/article/2");
  await expect(articlePage.locator(".article-title")).toHaveText("文章 2");
  await expect(articlePage).toHaveAttribute("data-navigation-state", "idle");
  await touch(page, "touchstart", 100, 450);
  await touch(page, "touchmove", 240, 452);
  await touch(page, "touchend", 300, 452);
  await expect(page.getByTestId("route")).toHaveText("/feed/1");
  await expect(articlePage).toBeHidden();
  expect(errors).toEqual([]);
});

test("button navigation away from an article finishes the exit animation", async ({ page }) => {
  const errors = await openArticle(page);
  const articlePage = page.locator(".nextflux-article-page");
  await expect(articlePage).toHaveAttribute("data-navigation-state", "idle");
  await page.getByRole("button", { name: "关闭文章", exact: true }).click();
  await expect(page.getByTestId("route")).toHaveText("/");
  await expect(articlePage).toBeHidden();
  await expect(articlePage).toHaveAttribute("data-navigation-state", "idle");
  await page.getByRole("button", { name: "打开文章 3", exact: true }).click();
  await expect(articlePage.locator(".article-title")).toHaveText("文章 3");
  await expect(articlePage).toHaveAttribute("data-navigation-state", "idle");
  expect(errors).toEqual([]);
});

async function installReadingMessageHold(page, holdSource = null) {
  await page.addInitScript((source) => {
    window.heldReadingMessages = [];
    window.holdReadingMessagesFor = source;
    window.readingWorkerStarts = [];
    const sources = new Map();
    const nativePost = Worker.prototype.postMessage;
    const nativeAdd = Worker.prototype.addEventListener;
    const nativeRemove = Worker.prototype.removeEventListener;
    const wrappedListeners = new WeakMap();
    Worker.prototype.postMessage = function (message, ...options) {
      if (message?.type === "reading:start") {
        sources.set(message.id, message.baseUrl);
        window.readingWorkerStarts.push(message.baseUrl);
      }
      return nativePost.call(this, message, ...options);
    };
    Worker.prototype.addEventListener = function (type, listener, options) {
      if (type !== "message") return nativeAdd.call(this, type, listener, options);
      const wrapped = (event) => {
        const deliver = () => listener.call(this, event);
        const heldSource = window.holdReadingMessagesFor;
        const hold = window.holdReadingMessages || (heldSource !== null && sources.get(event.data?.id) === heldSource);
        if (hold && event.data?.type === "reading:batch") window.heldReadingMessages.push(deliver);
        else deliver();
      };
      wrappedListeners.set(listener, wrapped);
      return nativeAdd.call(this, type, wrapped, options);
    };
    Worker.prototype.removeEventListener = function (type, listener, options) {
      return nativeRemove.call(this, type, wrappedListeners.get(listener) ?? listener, options);
    };
  }, holdSource);
}

async function releaseReadingMessages(page) {
  await page.evaluate(() => {
    window.holdReadingMessages = false;
    window.holdReadingMessagesFor = null;
    window.heldReadingMessages.splice(0).forEach((deliver) => deliver());
  });
}

function nextArticleWorkerStarts(page) {
  return page.evaluate((url) => window.readingWorkerStarts.filter((source) => source === url).length, nextArticleUrl);
}

test("next-page preparation mounts only a bounded first screen", async ({ page }) => {
  const errors = await openArticle(page);
  await expect(page.locator(".nextflux-continuous-next-page .article-body p").first()).toBeAttached();
  const paragraphs = await page.locator(".nextflux-continuous-next-page .article-body p").count();
  expect(paragraphs).toBeGreaterThan(0);
  expect(paragraphs).toBeLessThan(24);
  expect(errors).toEqual([]);
});

test("the same next unread ID is prepared again only when its content changes", async ({ page }) => {
  await installReadingMessageHold(page);
  const errors = await openArticle(page);
  const preview = page.locator(".nextflux-continuous-next-page");
  await expect(preview.locator(".article-body p").first()).toContainText("文章 2 段落 1");
  const nextArticleReads = () => page.evaluate(async () => {
    const { articleReads } = await import("./article-navigation-state.js");
    return articleReads.filter((id) => id === 2).length;
  });
  expect(await nextArticleReads()).toBe(1);
  expect(await nextArticleWorkerStarts(page)).toBe(1);

  await page.evaluate(async () => {
    const { filteredArticles } = await import("./article-navigation-state.js");
    filteredArticles.set(filteredArticles.get().map((article) =>
      article.id === 2 ? { ...article, starred: 1 } : article));
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
  expect(await nextArticleReads()).toBe(1);
  expect(await nextArticleWorkerStarts(page)).toBe(1);

  await page.evaluate(async () => {
    const { filteredArticles, rows } = await import("./article-navigation-state.js");
    const changed = { ...rows[1], bodyDigest: "fixture-body-2-updated", content: "<p>同步修订后的正文。</p>" };
    rows[1] = changed;
    // List rows carry a digest, not the full body loaded separately by the reader.
    const { content, ...metadata } = changed;
    filteredArticles.set(filteredArticles.get().map((article) => article.id === 2 ? metadata : article));
  });
  await expect(preview.locator(".article-body p").first()).toHaveText("同步修订后的正文。");
  await expect(preview.locator(".article-body")).toHaveAttribute("data-reading-complete", "true");
  expect(await nextArticleReads()).toBe(2);
  expect(await nextArticleWorkerStarts(page)).toBe(2);

  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 750);
  await touch(page, "touchmove", 151, 520);
  await touch(page, "touchend", 151, 520);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect(page.locator(".article-scroll-area .article-body p").first()).toHaveText("同步修订后的正文。");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  expect(await nextArticleWorkerStarts(page)).toBe(2);
  expect(errors).toEqual([]);
});

test("handoff continues a prepared next article without starting its parser again", async ({ page }) => {
  await installReadingMessageHold(page);
  const errors = await openArticle(page);
  const preview = page.locator(".nextflux-continuous-next-page");
  const area = page.locator(".article-scroll-area");
  await expect(preview.locator(".article-body")).toHaveAttribute("data-reading-ready", "true");
  await expect(preview.locator(".article-body")).toHaveAttribute("data-reading-complete", "false");
  const wordCount = await preview.locator(".nextflux-article-word-count").textContent();
  expect(await nextArticleWorkerStarts(page)).toBe(1);

  await area.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 750);
  await touch(page, "touchmove", 151, 520);
  await touch(page, "touchend", 151, 520);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect(area.locator(".article-body")).toHaveAttribute("data-reading-complete", "true");
  await expect(area.locator(".article-body p")).toHaveCount(240);
  await expect(area.locator(".article-body p").last()).toContainText("文章 2 段落 240");
  await expect(area.locator(".nextflux-article-word-count")).toHaveText(wordCount);
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  expect(await nextArticleWorkerStarts(page)).toBe(1);

  await area.locator(".nextflux-article-feed-button").click();
  await expect(page.getByTestId("route")).toHaveText("/feed/1");
  expect(errors).toEqual([]);
});

test("article length appears beside the date and follows the displayed body", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReadingMessageHold(page);
  await openArticle(page);
  const date = page.locator(".article-scroll-area .nextflux-article-date time");
  const badge = date.locator("+ .nextflux-article-word-count");
  const preview = page.locator(".nextflux-continuous-next-page");
  // All 240 paragraphs count, including those the preview has yet to mount.
  const total = (6 + 5 * "这是用于检验文章转场和正文分批渲染的测试内容".length) * 240;
  const formatted = total.toLocaleString("zh-CN") + " 字";
  await expect(badge).toHaveText(formatted);
  await expect(badge).toBeInViewport();
  await expect(preview.locator(".nextflux-article-word-count")).toHaveClass(/ds-tone-success/);
  await expect(preview.locator(".nextflux-article-word-count")).toHaveText(formatted);
  await expect(preview.locator(".article-body")).toHaveAttribute("data-reading-complete", "false");
  const timeBox = await date.boundingBox();
  const badgeBox = await badge.boundingBox();
  expect(badgeBox.x).toBeGreaterThanOrEqual(timeBox.x + timeBox.width);
  expect(badgeBox.y).toBeLessThan(timeBox.y + timeBox.height);
  await page.evaluate(() => {
    window.holdReadingMessages = true;
    window.navigationFixture.showFullText("<p>中文 NextFlux</p>");
  });
  await expect(badge).toHaveCount(0);
  await releaseReadingMessages(page);
  await expect(badge).toHaveText("3 字");
  await expect(badge).not.toHaveClass(/ds-tone-success/);
  for (const length of [500, 501, 499]) {
    await page.evaluate((length) => window.navigationFixture.showFullText("文".repeat(length)), length);
    await expect(badge).toHaveText(length + " 字");
    if (length === 501) {
      await expect(badge).toHaveClass(/ds-tone-success/);
    } else {
      await expect(badge).not.toHaveClass(/ds-tone-success/);
    }
  }
  await page.evaluate(() => window.navigationFixture.showFullText(null));
  await expect(badge).toHaveText(formatted);
});

test("a narrow reader shows the entire unique RSS title and only clamps a repeated title", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 780 });
  await openArticle(page);
  const title = "🖼️ tinybrain：从中文语料开始训练一个小模型，直到可以在本地推理";
  const header = page.locator(".article-scroll-area .article-title");
  const body = page.locator(".article-scroll-area .article-body");
  await page.evaluate(title => {
    window.navigationFixture.setTitle(title);
    window.navigationFixture.showFullText("<p>免费开源的中文模型训练项目，包含自建分词器和训练语料处理。</p>");
  }, title);
  await expect(body).toContainText("自建分词器");
  await expect(header).toHaveText(title);
  const visibleHeight = () => header.evaluate(el => el.clientHeight);
  const lineHeight = await header.evaluate(el => parseFloat(getComputedStyle(el).lineHeight));
  const clippedHeight = () => header.evaluate(el => el.scrollHeight - el.clientHeight);
  expect(await visibleHeight()).toBeGreaterThan(Math.ceil(lineHeight));
  expect(await clippedHeight()).toBeLessThanOrEqual(1);

  await page.evaluate(title => window.navigationFixture.showFullText(`<h2>${title}</h2><p>正文。</p>`), title);
  await expect(body.locator("h2")).toHaveText(title);
  await expect.poll(visibleHeight).toBeLessThanOrEqual(Math.ceil(lineHeight));
  await page.evaluate(() => window.navigationFixture.showFullText("<p>🖼️ tinybrain：从中文语料开始训练，此处只有标题的一部分。</p>"));
  await expect(body).toContainText("只有标题的一部分");
  await expect.poll(visibleHeight).toBeGreaterThan(Math.ceil(lineHeight));
  expect(await clippedHeight()).toBeLessThanOrEqual(1);
});

test("the next page follows the first pull before the release threshold", async ({ page }) => {
  await openArticle(page);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 750);
  await touch(page, "touchmove", 151, 690);
  await expect.poll(() => page.locator(".nextflux-continuous-next-page").evaluate((element) =>
    new DOMMatrix(getComputedStyle(element).transform).m42 < element.clientHeight - 20)).toBe(true);
  await touch(page, "touchcancel", 151, 690);
  await expect(page.getByTestId("route")).toHaveText("/article/1");
});

test("preview geometry and close controls are prepared only for a bottom pull", async ({ page }) => {
  const errors = await openArticle(page);
  await expect(page.locator(".nextflux-article-page")).toHaveAttribute("data-navigation-state", "idle");
  const area = page.locator(".article-scroll-area");
  const layer = page.locator(".nextflux-continuous-layer");
  const closeCopy = layer.locator(".nextflux-continuous-chrome .nextflux-close-button");
  const realClose = area.locator(".nextflux-close-button");
  const measureCount = () => page.evaluate(() => window.previewMeasureCount);
  await area.evaluate((viewport) => {
    // The fixture uses a minimal toolbar. Add the real toolbar's structural
    // selectors so the continuous reader exercises its close-button copying.
    const bar = viewport.querySelector(".action-buttons");
    const button = bar.querySelector("button");
    const row = document.createElement("div");
    row.className = "reader-topbar-row";
    button.classList.add("nextflux-close-button");
    row.append(button);
    bar.append(row);
    window.previewMeasureCount = 0;
    for (const element of [viewport, viewport.querySelector(".nextflux-continuous-current-surface")]) {
      const measure = element.getBoundingClientRect.bind(element);
      element.getBoundingClientRect = () => {
        window.previewMeasureCount++;
        return measure();
      };
    }
    viewport.scrollTop = 230;
  });
  for (let attempt = 0; attempt < 3; attempt++) {
    await touch(page, "touchstart", 150, 600);
    await touch(page, "touchmove", 151, 555);
    await touch(page, "touchend", 151, 555);
  }
  await expect(layer).toHaveAttribute("data-phase", "idle");
  await expect(closeCopy).toHaveCount(0);
  expect(await measureCount()).toBe(0);

  await area.evaluate((viewport) => { viewport.scrollTop = viewport.scrollHeight; });
  await expect(area).toHaveAttribute("data-chrome", "shown");
  await expect.poll(() => realClose.evaluate((button) => button.getBoundingClientRect().top)).toBeGreaterThanOrEqual(0);
  await touch(page, "touchstart", 150, 750);
  expect(await measureCount()).toBe(0);
  await touch(page, "touchmove", 151, 690);
  await expect(layer).toHaveAttribute("data-phase", "pulling");
  await expect(closeCopy).toBeVisible();
  await expect(realClose).toHaveCSS("opacity", "0");
  expect(await measureCount()).toBe(2);

  await page.setViewportSize({ width: 394, height: 858 });
  await expect.poll(measureCount).toBe(4);
  await expect(closeCopy).toBeVisible();
  await expect(realClose).toHaveCSS("opacity", "0");

  await area.evaluate((viewport) => {
    const point = new Touch({ identifier: 1, target: viewport, clientX: 151, clientY: 690 });
    viewport.dispatchEvent(new TouchEvent("touchend", { touches: [], changedTouches: [point], bubbles: true, cancelable: true }));
    viewport.dispatchEvent(new TouchEvent("touchstart", { touches: [point], changedTouches: [point], bubbles: true, cancelable: true }));
  });
  await expect(layer).toHaveAttribute("data-phase", "pulling");
  expect(await measureCount()).toBe(4);
  await closeCopy.click();
  await expect(page.getByTestId("route")).toHaveText("/");
  await expect(page.getByRole("button", { name: "打开文章 1" })).toBeVisible();
  expect(errors).toEqual([]);
});

test("a long pull keeps the next page under the finger up to the top", async ({ page }) => {
  await openArticle(page);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 800);
  // Past the old 390px cap: the page must keep rising, not stop part-way up.
  for (let y = 760; y >= 160; y -= 40) await touch(page, "touchmove", 151, y);
  await expect.poll(() => page.locator(".nextflux-continuous-next-page").evaluate((element) =>
    new DOMMatrix(getComputedStyle(element).transform).m42 < element.clientHeight - 450)).toBe(true);
  await touch(page, "touchcancel", 151, 160);
  await expect(page.getByTestId("route")).toHaveText("/article/1");
});

test("handoff keeps the target page until the real first screen is ready", async ({ page }) => {
  const errors = await openArticle(page);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 750);
  for (const y of [720, 680, 640, 590, 530]) await touch(page, "touchmove", 152, y);
  const samples = await page.evaluate(() => {
    const viewport = document.querySelector(".article-scroll-area");
    const point = new Touch({ identifier: 1, target: viewport, clientX: 152, clientY: 530 });
    viewport.dispatchEvent(new TouchEvent("touchend", { touches: [], changedTouches: [point], bubbles: true, cancelable: true }));
    return new Promise((resolve) => {
      const samples = [];
      const started = performance.now();
      const frame = () => {
        const overlay = document.querySelector(".nextflux-continuous-next-page");
        if (overlay && overlay.getBoundingClientRect().top < innerHeight - 1 && getComputedStyle(overlay).visibility !== "hidden") {
          samples.push({ title: overlay.querySelector(".article-title")?.textContent, route: location.hash,
            actualReady: document.querySelector(".article-scroll-area .article-body")?.dataset.readingReady });
        }
        if ((samples.length && document.querySelector(".nextflux-continuous-layer")?.dataset.phase === "idle" && location.hash.endsWith("/article/2")) || performance.now() - started > 3000) resolve(samples);
        else requestAnimationFrame(frame);
      };
      frame();
    });
  });
  expect(samples.length).toBeGreaterThan(0);
  expect(samples.filter((sample) => sample.title !== "文章 2")).toEqual([]);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect(page.locator(".article-scroll-area .article-title")).toHaveText("文章 2");
  expect(await page.locator(".article-scroll-area").evaluate((element) => element.scrollTop)).toBe(0);
  expect(errors).toEqual([]);
});

test("cached AI summaries do not enter the article or continuous-preview layout", async ({ page }) => {
  const errors = await openArticle(page);
  await expect(page.locator(".nextflux-article-page")).toHaveAttribute("data-navigation-state", "idle");
  const current = page.locator(".article-scroll-area .article-body p").first();
  const preview = page.locator(".nextflux-continuous-next-page .article-body p").first();
  await expect(preview).toBeAttached();
  const before = [(await current.boundingBox()).y, await preview.evaluate((element) => element.getBoundingClientRect().y)];
  const summary = "这是一段已经生成并缓存的摘要，应该只在用户打开弹窗时展示。".repeat(24);
  await page.evaluate((summary) => {
    for (const id of [1, 2]) window.navigationFixture.setSummary(id, { loading: false, summary, error: null });
  }, summary);
  await expect(page.locator(".ai-summary")).toHaveCount(0);
  expect((await current.boundingBox()).y).toBeCloseTo(before[0], 0);
  expect(await preview.evaluate((element) => element.getBoundingClientRect().y)).toBeCloseTo(before[1], 0);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 750);
  await touch(page, "touchmove", 151, 520);
  await touch(page, "touchend", 151, 520);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect(page.locator(".article-scroll-area .article-title")).toHaveText("文章 2");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  await expect(page.locator(".ai-summary")).toHaveCount(0);
  await expect(page.getByRole("dialog", { name: "AI 摘要", exact: true })).toHaveCount(0);
  expect(await page.locator(".article-scroll-area").evaluate((element) => element.scrollTop)).toBe(0);
  expect(errors).toEqual([]);
});

test("AI stream updates never shift reading content or open a summary without its toolbar", async ({ page }) => {
  const errors = await openArticle(page);
  await expect(page.locator(".nextflux-article-page")).toHaveAttribute("data-navigation-state", "idle");
  const firstParagraph = page.locator(".article-scroll-area .article-body p").first();
  const before = await firstParagraph.boundingBox();
  for (const state of [
    { loading: true, summary: "这是正在生成的摘要文字。".repeat(12), error: null },
    { loading: true, summary: "", error: null },
    { loading: false, summary: "已完成的完整摘要。".repeat(24), error: null },
  ]) {
    await page.evaluate((state) => window.navigationFixture.setSummary(1, state), state);
    await expect(page.locator(".ai-summary")).toHaveCount(0);
    await expect(page.getByRole("dialog", { name: "AI 摘要", exact: true })).toHaveCount(0);
    const after = await firstParagraph.boundingBox();
    expect(after.y).toBeCloseTo(before.y, 0);
    expect(after.height).toBeCloseTo(before.height, 0);
  }
  expect(errors).toEqual([]);
});

test("a delayed real-body worker cannot uncover or replace the frozen target", async ({ page }) => {
  // Keep the next article cold from its initial preview. A ready preview can
  // hand off without another worker, so delaying only after the pull races it.
  await installReadingMessageHold(page, nextArticleUrl);
  const errors = await openArticle(page);
  await expect.poll(() => nextArticleWorkerStarts(page)).toBe(1);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 750);
  await touch(page, "touchmove", 151, 520);
  await touch(page, "touchend", 151, 520);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect.poll(() => nextArticleWorkerStarts(page)).toBe(2);
  await expect(page.locator(".article-scroll-area .article-body")).toHaveAttribute("data-reading-ready", "false");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "handoff");
  await expect(page.locator(".nextflux-continuous-next-page .article-title")).toHaveText("文章 2");
  // The frozen preview covers the title bar too, so its header slides in rather than switching at the handoff.
  expect(await page.locator(".nextflux-continuous-next-page").evaluate((element) => element.getBoundingClientRect().top)).toBeCloseTo(0, 0);
  await releaseReadingMessages(page);
  await expect(page.locator(".article-scroll-area .article-body")).toHaveAttribute("data-reading-ready", "true");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  expect(errors).toEqual([]);
});

test("resizing a delayed handoff to desktop and back releases the mobile controller", async ({ page }) => {
  await installReadingMessageHold(page, nextArticleUrl);
  const errors = await openArticle(page);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 750);
  await touch(page, "touchmove", 151, 520);
  await touch(page, "touchend", 151, 520);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "handoff");
  await page.setViewportSize({ width: 1100, height: 900 });
  await expect(page.locator(".nextflux-continuous-layer")).toHaveCount(0);
  await releaseReadingMessages(page);
  await expect(page.locator(".article-scroll-area .article-body")).toHaveAttribute("data-reading-complete", "true");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  await touch(page, "touchstart", 60, 250);
  await touch(page, "touchmove", 280, 252);
  await touch(page, "touchend", 280, 252);
  await expect(page.getByTestId("route")).toHaveText("/");
  expect(errors).toEqual([]);
});

test("navigating to another article while its predecessor is loading clears the frozen handoff", async ({ page }) => {
  await installReadingMessageHold(page, nextArticleUrl);
  const errors = await openArticle(page);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 750);
  await touch(page, "touchmove", 151, 520);
  await touch(page, "touchend", 151, 520);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "handoff");
  await page.evaluate(() => { location.hash = "#/article/3"; });
  await expect(page.getByTestId("route")).toHaveText("/article/3");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  await releaseReadingMessages(page);
  await expect(page.locator(".article-scroll-area .article-title")).toHaveText("文章 3");
  await expect(page.locator(".article-scroll-area .article-body")).toHaveAttribute("data-reading-complete", "true");
  expect(errors).toEqual([]);
});

test("article entry stays offscreen until the first reading screen is ready", async ({ page }) => {
  await installReadingMessageHold(page);
  await page.goto("/article-navigation.html#/");
  await page.evaluate(() => {
    window.holdReadingMessages = true;
    document.querySelector("[data-testid=list] button").click();
  });
  await expect(page.getByTestId("route")).toHaveText("/article/1");
  await expect(page.locator(".nextflux-article-page")).toHaveAttribute("data-reading-motion", "preparing");
  const prepared = await page.locator(".nextflux-article-page").evaluate((element) => ({
    left: element.getBoundingClientRect().left,
    width: element.getBoundingClientRect().width,
  }));
  expect(prepared.left).toBeGreaterThanOrEqual(prepared.width - 1);

  await releaseReadingMessages(page);
  await expect(page.locator(".article-scroll-area .article-body")).toHaveAttribute("data-reading-ready", "true");
  await expect.poll(() => page.locator(".nextflux-article-page").evaluate((element) => ({
    left: element.getBoundingClientRect().left,
    motion: element.dataset.readingMotion,
  }))).toEqual({ left: 0, motion: undefined });
  await expect(page.locator(".article-scroll-area [role=status]")).toHaveCount(0);
});

test("enabling reduced motion during entry finishes and releases the article shell", async ({ page }) => {
  await page.goto("/article-navigation.html#/");
  const interrupted = await page.evaluate(() => {
    document.querySelector("[data-testid=list] button").click();
    return new Promise((resolve) => {
      const started = performance.now();
      const frame = () => {
        if (document.querySelector(".nextflux-article-page")?.dataset.readingMotion === "entrance") {
          window.navigationFixture.setReducedMotion(true);
          resolve(true);
        } else if (performance.now() - started > 2000) resolve(false);
        else requestAnimationFrame(frame);
      };
      frame();
    });
  });
  expect(interrupted).toBe(true);
  await expect(page.locator(".article-scroll-area .article-body")).toHaveAttribute("data-reading-complete", "true");
  expect(await page.locator(".nextflux-article-page").evaluate((element) => ({
    transform: getComputedStyle(element).transform, motion: element.dataset.readingMotion, willChange: element.style.willChange,
  }))).toEqual({ transform: "none", motion: undefined, willChange: "" });
  await touch(page, "touchstart", 60, 250);
  await touch(page, "touchmove", 280, 252);
  await touch(page, "touchend", 280, 252);
  await expect(page.getByTestId("route")).toHaveText("/");
});

test("narrow-screen reduced-motion reading still switches and returns correctly", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 760 });
  const errors = await openArticle(page);
  await page.evaluate(() => window.navigationFixture.setReducedMotion(true));
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 130, 650);
  await touch(page, "touchmove", 131, 440);
  await touch(page, "touchend", 131, 440);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  await touch(page, "touchstart", 50, 240);
  await touch(page, "touchmove", 210, 242);
  await touch(page, "touchend", 210, 242);
  await expect(page.getByTestId("route")).toHaveText("/");
  expect(errors).toEqual([]);
});

test("right-swipe can interrupt article entrance and return immediately", async ({ page }) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/article-navigation.html#/");
  await expect(page).toHaveTitle("NextFlux article navigation");
  const entrance = await page.evaluate(() => new Promise((resolve) => {
    document.querySelector("[data-testid=list] button").click();
    const started = performance.now();
    const frame = () => {
      const shell = document.querySelector(".nextflux-article-page");
      const viewport = document.querySelector(".article-scroll-area");
      if (shell?.dataset.readingMotion === "entrance" && viewport) {
        resolve({ left: shell.getBoundingClientRect().left });
      } else if (performance.now() - started > 2000) resolve(null);
      else requestAnimationFrame(frame);
    };
    frame();
  }));
  expect(entrance).not.toBeNull();
  expect(entrance.left).toBeGreaterThan(0);
  await page.evaluate(() => new Promise(requestAnimationFrame));
  await touch(page, "touchstart", 60, 250);
  await touch(page, "touchmove", 140, 252);
  await touch(page, "touchend", 140, 252);
  await expect(page.getByTestId("route")).toHaveText("/");
  expect(errors).toEqual([]);
});

test("desktop reading uses the normal article layout without a mobile overlay", async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 900 });
  const errors = await openArticle(page);
  await expect(page.locator(".nextflux-continuous-layer")).toHaveCount(0);
  expect(await page.locator(".article-scroll-area").evaluate((element) => getComputedStyle(element.parentElement).transform)).toBe("none");
  expect(errors).toEqual([]);
});

test("article push uses compositor animations and a dedicated navigation state", async ({ page }) => {
  await page.goto("/article-navigation.html#/");
  const sample = await page.evaluate(() => new Promise((resolve) => {
    document.querySelector("[data-testid=list] button").click();
    const started = performance.now();
    const frame = () => {
      const shell = document.querySelector(".nextflux-article-page");
      const list = document.querySelector("[data-testid=list]");
      if (shell?.dataset.navigationState === "entering") {
        resolve({
          shellAnimations: shell.getAnimations().length,
          listAnimations: list.getAnimations().length,
          boxShadow: getComputedStyle(shell).boxShadow,
        });
      } else if (performance.now() - started > 2500) resolve(null);
      else requestAnimationFrame(frame);
    };
    frame();
  }));
  expect(sample).not.toBeNull();
  expect(sample.shellAnimations).toBeGreaterThan(0);
  expect(sample.listAnimations).toBeGreaterThan(0);
  expect(sample.boxShadow).toBe("none");
  await expect.poll(() => page.locator(".nextflux-article-page").getAttribute("data-navigation-state")).toBe("idle");
});

test("article swipe-back drives list parallax, cover opacity and a compositor shadow layer", async ({ page }) => {
  await openArticle(page);
  const resting = await page.getByTestId("list").evaluate((element) => ({
    x: new DOMMatrix(getComputedStyle(element).transform).m41,
    dim: Number(getComputedStyle(element.querySelector(".nextflux-list-transition-cover")).opacity),
  }));
  expect(resting.x).toBeLessThan(-70);
  expect(resting.dim).toBeGreaterThan(0.015);
  expect(resting.dim).toBeLessThan(0.04);

  await touch(page, "touchstart", 60, 250);
  await touch(page, "touchmove", 180, 252);
  const during = await page.evaluate(() => {
    const list = document.querySelector("[data-testid=list]");
    const shell = document.querySelector(".nextflux-article-page");
    return {
      listX: new DOMMatrix(getComputedStyle(list).transform).m41,
      dim: Number(getComputedStyle(list.querySelector(".nextflux-list-transition-cover")).opacity),
      shadowOpacity: Number(getComputedStyle(document.querySelector(".nextflux-transition-shadow")).opacity),
      boxShadow: getComputedStyle(shell).boxShadow,
      articleX: shell.getBoundingClientRect().left,
    };
  });
  expect(during.articleX).toBeGreaterThan(100);
  expect(during.listX).toBeGreaterThan(resting.x);
  expect(during.listX).toBeLessThan(0);
  expect(during.dim).toBeLessThan(resting.dim);
  expect(during.shadowOpacity).toBeGreaterThan(0.2);
  expect(during.boxShadow).toBe("none");

  await touch(page, "touchend", 180, 252);
  await expect(page.getByTestId("route")).toHaveText("/");
  await expect.poll(() => page.getByTestId("list").evaluate((element) =>
    new DOMMatrix(getComputedStyle(element).transform).m41)).toBeCloseTo(0, 0);
});

test("right-swipe tracks the finger and a short paused release cancels", async ({ page }) => {
  await openArticle(page);
  await touch(page, "touchstart", 60, 250);
  await touch(page, "touchmove", 95, 252);
  await expect.poll(() => page.locator(".article-scroll-area").evaluate((element) =>
    element.parentElement.getBoundingClientRect().left)).toBeCloseTo(35, 0);
  await page.waitForTimeout(160); // Model a stationary finger before release.
  await touch(page, "touchend", 95, 252);
  await expect.poll(() => page.locator(".article-scroll-area").evaluate((element) =>
    element.parentElement.getBoundingClientRect().left)).toBeCloseTo(0, 0);
  await expect(page.getByTestId("route")).toHaveText("/article/1");
});

test("a deliberate long right-swipe returns once and preserves the source list", async ({ page }) => {
  await openArticle(page);
  await touch(page, "touchstart", 60, 250);
  await touch(page, "touchmove", 80, 252);
  await touch(page, "touchmove", 280, 254);
  await touch(page, "touchend", 280, 254);
  await expect(page.getByTestId("route")).toHaveText("/");
  await expect(page.getByRole("button", { name: "打开文章 1" })).toBeVisible();
});

test("diagonal scrolling, modal and gallery interaction cannot trigger back", async ({ page }) => {
  await openArticle(page);
  for (const blocker of [null, "setModal", "setGallery"]) {
    if (blocker) await page.evaluate((name) => window.navigationFixture[name](true), blocker);
    await touch(page, "touchstart", 60, 250);
    await touch(page, "touchmove", 100, 210);
    await touch(page, "touchmove", 260, 200);
    await touch(page, "touchend", 260, 200);
    await expect(page.getByTestId("route")).toHaveText("/article/1");
    if (blocker) await page.evaluate((name) => window.navigationFixture[name](false), blocker);
  }
});

test("browser-delivered touch input pulls the next article and swipes back", async ({ page }) => {
  const errors = await openArticle(page);
  const input = await page.context().newCDPSession(page);
  const move = async (type, x, y) => {
    await input.send("Input.dispatchTouchEvent", {
      type, touchPoints: type === "touchEnd" ? [] : [{ x, y, id: 1 }],
    });
    await page.evaluate(() => new Promise(requestAnimationFrame));
  };
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await move("touchStart", 150, 750);
  await move("touchMove", 151, 690);
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "pulling");
  for (const y of [650, 610, 560, 510]) await move("touchMove", 151, y);
  await move("touchEnd", 151, 510);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  await move("touchStart", 60, 250);
  await move("touchMove", 110, 252);
  await move("touchMove", 280, 254);
  await expect.poll(() => page.locator(".nextflux-article-page").evaluate((element) => element.getBoundingClientRect().left)).toBeGreaterThan(150);
  await move("touchEnd", 280, 254);
  await expect(page.getByTestId("route")).toHaveText("/");
  await input.detach();
  expect(errors).toEqual([]);
});

// Cheap performance guard: once the article is mounted, scrolling it (which
// drives the immersive bars through a scroll listener) must not produce long
// main-thread tasks. A forced-layout or re-render-per-scroll regression on a
// 240-paragraph article shows up here long before it is visible on a phone.
test("reading scroll stays within a long-task budget and still toggles the bars", async ({ page }) => {
  const errors = await openArticle(page);
  const area = page.locator(".article-scroll-area");
  await page.evaluate(() => {
    window.longTasks = [];
    try {
      new PerformanceObserver((list) => { for (const entry of list.getEntries()) window.longTasks.push(Math.round(entry.duration)); })
        .observe({ type: "longtask", buffered: false });
    } catch { /* longtask unsupported: the budget below is then vacuous */ }
  });
  const box = await area.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  for (let step = 0; step < 24; step += 1) {
    await page.mouse.wheel(0, 240);
    await page.waitForTimeout(16);
  }
  await expect(area).toHaveAttribute("data-chrome", "hidden");
  for (let step = 0; step < 4; step += 1) {
    await page.mouse.wheel(0, -120);
    await page.waitForTimeout(16);
  }
  await expect(area).toHaveAttribute("data-chrome", "shown");
  const tasks = await page.evaluate(() => window.longTasks);
  expect(Math.max(0, ...tasks), `long tasks during reading scroll: ${tasks.join(", ")}`).toBeLessThan(200);
  expect(errors).toEqual([]);
});
