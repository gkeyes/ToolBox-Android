import { test, expect } from "@playwright/test";

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

async function installReadingMessageHold(page) {
  await page.addInitScript(() => {
    window.heldReadingMessages = [];
    const nativeAdd = Worker.prototype.addEventListener;
    const nativeRemove = Worker.prototype.removeEventListener;
    const wrappedListeners = new WeakMap();
    Worker.prototype.addEventListener = function (type, listener, options) {
      if (type !== "message") return nativeAdd.call(this, type, listener, options);
      const wrapped = (event) => {
        const deliver = () => listener.call(this, event);
        if (window.holdReadingMessages && event.data?.type === "reading:batch") window.heldReadingMessages.push(deliver);
        else deliver();
      };
      wrappedListeners.set(listener, wrapped);
      return nativeAdd.call(this, type, wrapped, options);
    };
    Worker.prototype.removeEventListener = function (type, listener, options) {
      return nativeRemove.call(this, type, wrappedListeners.get(listener) ?? listener, options);
    };
  });
}

async function releaseReadingMessages(page) {
  await page.evaluate(() => {
    window.holdReadingMessages = false;
    window.heldReadingMessages.splice(0).forEach((deliver) => deliver());
  });
}

test("next-page preparation mounts only a bounded first screen", async ({ page }) => {
  const errors = await openArticle(page);
  await expect(page.locator(".nextflux-continuous-next-page .article-body p").first()).toBeAttached();
  const paragraphs = await page.locator(".nextflux-continuous-next-page .article-body p").count();
  expect(paragraphs).toBeGreaterThan(0);
  expect(paragraphs).toBeLessThan(24);
  expect(errors).toEqual([]);
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

test("a completed AI summary is already complete on the real page's first ready frame", async ({ page }) => {
  const errors = await openArticle(page);
  const summary = "这是一段已经生成并缓存的摘要，切换后应当保留完整文字和高度。".repeat(24);
  await page.evaluate((summary) => window.navigationFixture.setSummary(2, { loading: false, summary, error: null }), summary);
  await expect(page.locator(".nextflux-continuous-next-page .ai-summary .leading-relaxed")).toHaveText(summary);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await touch(page, "touchstart", 150, 750);
  await touch(page, "touchmove", 151, 520);
  const firstText = await page.evaluate(() => {
    const viewport = document.querySelector(".article-scroll-area");
    const point = new Touch({ identifier: 1, target: viewport, clientX: 151, clientY: 520 });
    viewport.dispatchEvent(new TouchEvent("touchend", { touches: [], changedTouches: [point], bubbles: true, cancelable: true }));
    return new Promise((resolve) => {
      const started = performance.now();
      const frame = () => {
        const body = viewport.querySelector(".article-body");
        if (body?.dataset.fontArticleId === "2" && body.dataset.readingReady === "true") {
          resolve(viewport.querySelector(".ai-summary .leading-relaxed")?.textContent);
        } else if (performance.now() - started > 3000) resolve(null);
        else requestAnimationFrame(frame);
      };
      frame();
    });
  });
  expect(firstText).toBe(summary);
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  expect(errors).toEqual([]);
});

test("a visible AI stream catches up and a restarted stream clears its old summary", async ({ page }) => {
  await openArticle(page);
  const text = "这是正在生成的摘要文字，用于验证流式显示仍可追赶到最新内容。".repeat(3);
  await page.evaluate((summary) => window.navigationFixture.setSummary(1, { loading: true, summary, error: null }), text);
  const summary = page.locator(".article-scroll-area .ai-summary .leading-relaxed");
  await expect(summary).toHaveText(text);
  await page.evaluate(() => window.navigationFixture.setSummary(1, { loading: true, summary: "", error: null }));
  await expect(summary).toHaveCount(0);
  await page.evaluate(() => window.navigationFixture.setSummary(1, { loading: true, summary: "重新生成的新摘要", error: null }));
  await expect(summary).toHaveText("重新生成的新摘要");
  await page.evaluate(() => window.navigationFixture.setSummary(1, { loading: false, summary: "已完成的完整摘要", error: null }));
  await expect(summary).toHaveText("已完成的完整摘要");
});

test("a delayed real-body worker cannot uncover or replace the frozen target", async ({ page }) => {
  await installReadingMessageHold(page);
  const errors = await openArticle(page);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await page.evaluate(() => { window.holdReadingMessages = true; });
  await touch(page, "touchstart", 150, 750);
  await touch(page, "touchmove", 151, 520);
  await touch(page, "touchend", 151, 520);
  await expect(page.getByTestId("route")).toHaveText("/article/2");
  await expect(page.locator(".article-scroll-area .article-body")).toHaveAttribute("data-reading-ready", "false");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "handoff");
  await expect(page.locator(".nextflux-continuous-next-page .article-title")).toHaveText("文章 2");
  expect(await page.locator(".nextflux-continuous-next-page").evaluate((element) => element.getBoundingClientRect().top)).toBeCloseTo(56, 0);
  await releaseReadingMessages(page);
  await expect(page.locator(".article-scroll-area .article-body")).toHaveAttribute("data-reading-ready", "true");
  await expect(page.locator(".nextflux-continuous-layer")).toHaveAttribute("data-phase", "idle");
  expect(errors).toEqual([]);
});

test("resizing a delayed handoff to desktop and back releases the mobile controller", async ({ page }) => {
  await installReadingMessageHold(page);
  const errors = await openArticle(page);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await page.evaluate(() => { window.holdReadingMessages = true; });
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
  await installReadingMessageHold(page);
  const errors = await openArticle(page);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await page.evaluate(() => { window.holdReadingMessages = true; });
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
