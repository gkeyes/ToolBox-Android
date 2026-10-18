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
  await expect(paused).toHaveCount(0);

  await page.evaluate(() => window.sidebarNotificationsFixture.setSync(false));
  await expect(paused).toBeVisible();
  await row.click();
  await expect(row).toHaveAttribute("aria-expanded", "true");
  await expect(details).toBeVisible();
  await expect(details.getByRole("listitem")).toHaveText(["关注的订阅一", "关注的订阅二"]);
  await expect(details.getByText("接收通知的订阅源", { exact: true })).toBeVisible();
  await expect(details.getByText("只通知开启之后新到的未读文章；你正在看 NextFlux 时不弹通知。", { exact: true })).toBeVisible();

  await page.evaluate(() => window.sidebarNotificationsFixture.setSync(true));
  await expect(paused).toHaveCount(0);
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
