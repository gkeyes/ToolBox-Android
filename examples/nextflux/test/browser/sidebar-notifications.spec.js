import { test as base, expect } from "@playwright/test";

const test = base.extend({ page: async ({ page }, use) => {
  const errors = [], external = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });
  await page.route("**/*", async (route) => {
    if (new URL(route.request().url()).origin === "http://127.0.0.1:4175") await route.continue();
    else { external.push(route.request().url()); await route.abort("blockedbyclient"); }
  });
  await use(page);
  expect(errors, "the real notification row must render without errors").toEqual([]);
  expect(external, "the fixture must not contact live services").toEqual([]);
} });

test("sidebar notifications count existing watches, reflect sync state and expand their feed list", async ({ page }) => {
  await page.goto("/sidebar-notifications.html");
  await page.waitForFunction(() => Boolean(window.sidebarNotificationsFixture));
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  const row = page.getByRole("button", { name: /更新通知/ });
  const details = page.getByRole("region", { name: "更新通知", exact: true });
  const paused = row.getByText("同步已关闭", { exact: false });
  // Two watched catalog entries, one unwatched entry and one stale watched id.
  await expect(row).toBeEnabled();
  await expect(row.getByText("已关注 2 个订阅源", { exact: true })).toBeVisible();
  await expect(row).toHaveAttribute("aria-expanded", "false");
  await expect(details).toHaveCount(0);
  await expect(paused).toBeHidden();

  await page.evaluate(() => window.sidebarNotificationsFixture.setSync(false));
  await expect(paused).toBeVisible();
  await row.click();
  await expect(row).toHaveAttribute("aria-expanded", "true");
  await expect(details).toBeVisible();
  await expect(details.getByRole("listitem")).toHaveText(["关注的订阅一", "关注的订阅二"]);
  await expect(details.getByText("接收通知的订阅源", { exact: true })).toBeVisible();
  await expect(details.getByText("只通知开启之后新到的未读文章；你正在看 NextFlux 时不弹通知。", { exact: true })).toBeVisible();

  await page.evaluate(() => window.sidebarNotificationsFixture.setSync(true));
  await expect(paused).toBeHidden();
  await expect(details).toBeVisible();
  await row.click();
  await expect(row).toHaveAttribute("aria-expanded", "false");
  await expect(details).toHaveCount(0);

  // Losing the last watch also closes an already open list and disables its row.
  await row.click();
  await expect(details).toBeVisible();
  await page.evaluate(() => {
    window.sidebarNotificationsFixture.setSync(false);
    window.sidebarNotificationsFixture.setWatched([]);
  });
  await expect(row).toBeDisabled();
  await expect(row.getByText("长按订阅源即可开启", { exact: true })).toBeVisible();
  await expect(row).toHaveAttribute("aria-expanded", "false");
  await expect(details).toHaveCount(0);
  await expect(paused).toHaveCount(0);
});

test("the notification row title wraps instead of truncating at 2x text on a 320 px phone", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto("/sidebar-notifications.html");
  await page.waitForFunction(() => Boolean(window.sidebarNotificationsFixture));
  // The real sidebar card at 320 px is 232 px wide, narrower than this fixture's default.
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "32px";
    document.querySelector("main").style.width = "248px";
  });
  // Located by class: renaming the title also renames the button.
  const title = page.locator(".sidebar-pref .ds-body").first();
  await expect(title).toHaveText("更新通知");
  await title.evaluate((node) => { node.lang = "en"; node.textContent = "Update notifications"; });
  const metrics = await title.evaluate((node) => ({
    clipped: node.scrollWidth > node.clientWidth || node.scrollHeight > node.clientHeight + 1,
    lines: Math.round(node.getBoundingClientRect().height / parseFloat(getComputedStyle(node).lineHeight)),
  }));
  expect(metrics.clipped, "the whole title shows").toBe(false);
  expect(metrics.lines, "it takes a second line").toBe(2);
  // Its own line above the caption, so "notifications" is not split mid-word.
  expect(await title.evaluate((node) => {
    const range = document.createRange();
    range.setStart(node.firstChild, 7);
    range.setEnd(node.firstChild, 20);
    return new Set([...range.getClientRects()].map((rect) => Math.round(rect.top))).size;
  }), "\"notifications\" stays on one line").toBe(1);
  // A longer title still shows whole over three lines.
  await title.evaluate((node) => { node.textContent = "Notifications de mise à jour"; });
  expect(await title.evaluate((node) => node.scrollHeight > node.clientHeight + 1), "a three-line title is not cut").toBe(false);
});

test("switching sync on or off keeps the row height so the switch above stays put", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/sidebar-notifications.html");
  await page.waitForFunction(() => Boolean(window.sidebarNotificationsFixture));
  // The real sidebar card at 390 px is 302 px wide; at 2x the paused note takes a line.
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "32px";
    document.querySelector("main").style.width = "318px";
  });
  const row = page.locator(".sidebar-pref").first();
  const height = () => row.evaluate((node) => node.getBoundingClientRect().height);
  await page.evaluate(() => window.sidebarNotificationsFixture.setSync(false));
  await expect(row.getByText("同步已关闭", { exact: false })).toBeVisible();
  const off = await height();
  await page.evaluate(() => window.sidebarNotificationsFixture.setSync(true));
  await expect(row.getByText("同步已关闭", { exact: false })).toBeHidden();
  expect(await height()).toBe(off);
});

test("the watched-feeds caption wraps instead of being cut at 2x text on a 320 px phone", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto("/sidebar-notifications.html");
  await page.waitForFunction(() => Boolean(window.sidebarNotificationsFixture));
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "32px";
    document.querySelector("main").style.width = "248px";
  });
  const caption = page.getByText("已关注 2 个订阅源", { exact: true });
  await expect(caption).toBeVisible();
  expect(await caption.evaluate((node) => node.scrollWidth > node.clientWidth + 1), "the caption shows whole").toBe(false);
});

test("a finger drag over the real feed list scrolls it and then the page", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto("/sidebar-notifications.html");
  await page.waitForFunction(() => Boolean(window.sidebarNotificationsFixture));
  // Enough watched feeds to make the list scroll, and a page taller than the screen: a
  // touch handler in the row or list that cancels moves would stop both.
  await page.evaluate(() => {
    const list = Array.from({ length: 30 }, (_, index) => ({ id: String(index + 1), title: `订阅 ${index + 1}` }));
    window.sidebarNotificationsFixture.setFeeds(list);
    window.sidebarNotificationsFixture.setWatched(list.map((feed) => Number(feed.id)));
    document.body.append(Object.assign(document.createElement("div"), { style: "block-size: 2000px" }));
  });
  await page.getByRole("button", { name: /更新通知/ }).click();
  const list = page.locator(".sidebar-notify-details");
  await expect(list).toBeVisible();
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 1 });
  const touch = (type, x, y) => cdp.send("Input.dispatchTouchEvent", { type, touchPoints: type === "touchEnd" ? [] : [{ x, y }] });
  const state = () => list.evaluate((node) => ({
    end: node.scrollTop >= node.scrollHeight - node.clientHeight - 1,
    page: window.scrollY,
  }));
  for (let drag = 0; drag < 12 && (await state()).page === 0; drag += 1) {
    const box = await list.boundingBox();
    const x = box.x + box.width / 2, y = Math.min(box.y + box.height - 10, 600);
    await touch("touchStart", x, y);
    for (let step = 1; step <= 8; step += 1) await touch("touchMove", x, y - step * 18);
    await touch("touchEnd");
    await page.waitForTimeout(150);
  }
  const end = await state();
  expect(end.end, "the list scrolled to its end").toBe(true);
  expect(end.page, "the drag carried on to the page").toBeGreaterThan(0);
});
