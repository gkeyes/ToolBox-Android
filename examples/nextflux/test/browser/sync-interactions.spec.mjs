import { test, expect } from "@playwright/test";
import { installFixture, openFixture } from "./fixture.mjs";

test("read and starred changes finish during a paused sync and survive its older snapshot", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const observations = await installFixture(page);
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  const toolbar = page.locator(".action-buttons");
  await expect(toolbar.getByRole("button", { name: "Unread", exact: true })).toBeVisible();
  await expect(toolbar.getByRole("button", { name: "Unstar", exact: true })).toBeVisible();
  await page.evaluate(() => {
    window.__nextfluxTest.holdNetworkNext("/v1/entries");
    window.__nextfluxTest.holdNetworkNext("/v1/entries");
  });
  const sync = page.getByRole("button", { name: "立即同步", exact: true });
  await sync.click();
  await expect.poll(() => page.evaluate(() => window.__nextfluxTest.heldNetwork.length)).toBe(2);
  await expect(sync).toBeDisabled();

  await toolbar.getByRole("button", { name: "Unstar", exact: true }).click();
  await expect(toolbar.getByRole("button", { name: "Star", exact: true })).toBeVisible();
  await toolbar.getByRole("button", { name: "Unread", exact: true }).click();
  await expect(toolbar.getByRole("button", { name: "Read", exact: true })).toBeVisible();
  // The network response is still held. These acknowledgements cannot come
  // from letting sync finish first or from replacing the app's state in a test.
  expect(await page.evaluate(() => window.__nextfluxTest.heldNetwork.length)).toBe(2);
  expect(await page.evaluate(() => window.__nextfluxTest.network.some((call) => call.path === "/v1/entries" && call.method === "PUT" && call.status === "unread" && call.ids.includes(96)))).toBe(true);

  await page.evaluate(() => window.__nextfluxTest.releaseNetwork());
  await expect(sync).toBeEnabled();
  await expect(toolbar.getByRole("button", { name: "Star", exact: true })).toBeVisible();
  await expect(toolbar.getByRole("button", { name: "Read", exact: true })).toBeVisible();
  await toolbar.getByRole("button", { name: "Close", exact: true }).click();
  await expect(page.locator('[data-article-id="96"]')).toBeVisible();
  expect(observations.blocked).toEqual([]);
  expect(observations.pageErrors).toEqual([]);
  expect(await page.evaluate(() => window.__nextfluxTest.unexpectedNetwork)).toEqual([]);
});
