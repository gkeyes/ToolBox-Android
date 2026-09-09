import { test, expect } from "@playwright/test";
import { installFixture, openFixture } from "./fixture.mjs";

const dialog = (page) => page.getByRole("dialog", { name: "链接操作", exact: true });
const originalUrl = "https://example.org/articles/96";

async function openArticle(page, options) {
  const observations = await installFixture(page, options);
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  await expect(page.locator(".article-title")).toHaveText("Article 96");
  await expect(page.locator(".article-content")).toContainText("FIXTURE_BODY_96");
  return observations;
}

async function verifyBoundary(page, observations) {
  expect(observations.blocked).toEqual([]);
  expect(observations.pageErrors).toEqual([]);
  expect(await page.evaluate(() => window.__nextfluxTest.unexpectedNetwork)).toEqual([]);
}

test("article and body links use the browser action without losing the reader or submitting twice", async ({ page }) => {
  const html = `<p>FIXTURE_BODY_96</p>${"<p>A paragraph before the external link.</p>".repeat(30)}<p><a href="https://example.org/linked-post?from=reader#section">Linked article</a></p>${"<p>A paragraph after the external link.</p>".repeat(30)}`;
  const observations = await openArticle(page, { articleBodies: { 96: html } });
  const anchor = page.locator(".article-content").getByRole("link", { name: "Linked article", exact: true });
  await anchor.scrollIntoViewIfNeeded();
  const before = await page.evaluate(() => ({ url: location.href, scroll: document.querySelector(".article-scroll-area").scrollTop }));
  await anchor.click();
  await expect(dialog(page).getByRole("button")).toHaveText(["浏览器打开", "复制链接", "分享链接", "取消"]);
  await page.evaluate(() => { window.__nextfluxTest.holdBrowser = true; });
  const open = dialog(page).getByRole("button", { name: "浏览器打开", exact: true });
  await open.click();
  await expect(open).toBeDisabled();
  await expect(dialog(page)).toHaveAttribute("aria-busy", "true");
  await open.evaluate((element) => element.click());
  expect(await page.evaluate(() => window.__nextfluxTest.browserCalls)).toEqual(["https://example.org/linked-post?from=reader#section"]);
  await page.evaluate(() => { window.__nextfluxTest.holdBrowser = false; window.__nextfluxTest.releaseBrowser(); });
  await expect(dialog(page)).toHaveCount(0);
  expect(await page.url()).toBe(before.url);
  expect(await page.locator(".article-scroll-area").evaluate((element) => element.scrollTop)).toBeCloseTo(before.scroll, 0);
  await expect(page.locator(".article-title")).toHaveText("Article 96");
  await page.locator(".article-title a").click();
  await expect(dialog(page)).toContainText(originalUrl);
  await dialog(page).getByRole("button", { name: "浏览器打开", exact: true }).click();
  await expect(dialog(page)).toHaveCount(0);
  expect(await page.evaluate(() => window.__nextfluxTest.browserCalls)).toHaveLength(2);
  await verifyBoundary(page, observations);
});

test("browser failures retain the link and allow retry, copy and share", async ({ page }) => {
  const observations = await openArticle(page);
  await page.locator(".article-title a").click();
  const open = dialog(page).getByRole("button", { name: "浏览器打开", exact: true });
  for (const [code, message] of [
    ["PERMISSION_DENIED", "请在小工具权限中开启浏览器打开后重试。"],
    ["UNSUPPORTED", "未找到可用浏览器，请安装或启用浏览器后重试。"],
    ["USER_GESTURE_REQUIRED", "请点击“浏览器打开”后重试。"],
    ["SESSION_ENDED", "当前阅读会话已结束，请重新打开 NextFlux 后重试。"],
  ]) {
    await page.evaluate((failure) => { window.__nextfluxTest.browserFailure = failure; }, code);
    await open.click();
    await expect(page.getByText(message, { exact: true })).toBeVisible();
    await expect(open).toBeEnabled();
    await expect(dialog(page)).toContainText(originalUrl);
  }
  await page.evaluate(() => { window.__nextfluxTest.browserFailure = null; });
  await open.click();
  await expect(dialog(page)).toHaveCount(0);
  await page.locator(".article-title a").click();
  await dialog(page).getByRole("button", { name: "复制链接", exact: true }).click();
  expect(await page.evaluate(() => window.__nextfluxTest.clipboardCalls)).toEqual([originalUrl]);
  await page.locator(".article-title a").click();
  await dialog(page).getByRole("button", { name: "分享链接", exact: true }).click();
  expect(await page.evaluate(() => window.__nextfluxTest.shareCalls)).toEqual([originalUrl]);
  await expect(page).toHaveURL(/\/article\/96/);
  await verifyBoundary(page, observations);
});

test("invalid links and an older host do not navigate the WebView", async ({ page }) => {
  const observations = await openArticle(page);
  for (const value of ["javascript:alert(1)", "intent://example.org", "https://user:secret@example.org", `https://example.org/${"x".repeat(2050)}`]) {
    await page.locator(".article-title a").evaluate((element, href) => { element.href = href; }, value);
    await page.locator(".article-title a").click();
    await expect(dialog(page)).toHaveCount(0);
    await expect(page).toHaveURL(/\/article\/96/);
    expect(await page.evaluate(() => window.__nextfluxTest.browserCalls)).toEqual([]);
  }
  await page.locator(".article-title a").evaluate((element, href) => { element.href = href; }, originalUrl);
  await page.evaluate(() => { delete window.ToolBox.browser; });
  await page.locator(".article-title a").click();
  await dialog(page).getByRole("button", { name: "浏览器打开", exact: true }).click();
  await expect(page.getByText("请先将 ToolBox 升级至 0.6.7 或更新版本，再打开链接。", { exact: true })).toBeVisible();
  await expect(dialog(page)).toContainText(originalUrl);
  await verifyBoundary(page, observations);
});

test("glass dialog dismisses without changing the reader and restores keyboard focus", async ({ page }) => {
  const observations = await openArticle(page);
  const trigger = page.locator(".article-title a");
  for (const dismiss of ["cancel", "escape", "backdrop"]) {
    await trigger.focus();
    await trigger.press("Enter");
    await expect(dialog(page).getByRole("button", { name: "浏览器打开", exact: true })).toBeFocused();
    await dialog(page).getByRole("heading").click();
    await expect(dialog(page)).toBeVisible();
    if (dismiss === "cancel") await dialog(page).getByRole("button", { name: "取消", exact: true }).click();
    if (dismiss === "escape") await page.keyboard.press("Escape");
    if (dismiss === "backdrop") await page.touchscreen.tap(4, 4);
    await expect(dialog(page)).toHaveCount(0);
    await expect(trigger).toBeFocused();
    await expect(page).toHaveURL(/\/article\/96/);
  }
  expect(await page.evaluate(() => window.__nextfluxTest.browserCalls)).toEqual([]);
  await verifyBoundary(page, observations);
});

test("glass dialog fits narrow, large type and dark layouts with one primary action", async ({ page }) => {
  const observations = await openArticle(page);
  await page.emulateMedia({ reducedMotion: "reduce" });
  for (const [width, height, scale, dark] of [[390, 844, 1, false], [320, 720, 2, false], [844, 390, 1, true], [1280, 900, 1, true]]) {
    await page.setViewportSize({ width, height });
    await page.evaluate(({ scale, dark }) => {
      document.documentElement.style.fontSize = `${16 * scale}px`;
      document.documentElement.classList.toggle("dark", dark);
      document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
    }, { scale, dark });
    const trigger = page.locator(".article-title a");
    await trigger.click();
    const layout = await dialog(page).evaluate((element) => {
      const rect = element.getBoundingClientRect();
      const buttons = [...element.querySelectorAll("button")];
      return {
        left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom,
        scrollWidth: element.scrollWidth, clientWidth: element.clientWidth,
        animation: getComputedStyle(element).animationName,
        buttons: buttons.map((button) => ({ height: button.getBoundingClientRect().height, background: getComputedStyle(button).backgroundColor })),
      };
    });
    expect(layout.left).toBeGreaterThanOrEqual(15);
    expect(layout.right).toBeLessThanOrEqual(width - 15);
    expect(layout.top).toBeGreaterThanOrEqual(15);
    expect(layout.bottom).toBeLessThanOrEqual(height - 15);
    expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth + 1);
    expect(layout.animation).toBe("none");
    expect(layout.buttons.every((button) => button.height >= 48)).toBe(true);
    expect(layout.buttons[0].background).not.toBe(layout.buttons[1].background);
    expect(new Set(layout.buttons.slice(1).map((button) => button.background)).size).toBe(1);
    await dialog(page).getByRole("button", { name: "取消", exact: true }).click();
    await expect(dialog(page)).toHaveCount(0);
  }
  await verifyBoundary(page, observations);
});
