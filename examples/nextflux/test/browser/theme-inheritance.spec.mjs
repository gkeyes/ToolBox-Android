import { test, expect } from "@playwright/test";
import { installFixture, openFixture } from "./fixture.mjs";

test("built NextFlux follows live browser color scheme without losing the document or login", async ({ page }) => {
  const observations = await installFixture(page);
  await page.emulateMedia({ colorScheme: "dark" });
  await openFixture(page);
  await expect(page.locator("html")).toHaveClass(/(?:^|\s)dark(?:\s|$)/);
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.evaluate(() => { window.themeSentinel = "same-document"; });
  await page.emulateMedia({ colorScheme: "light" });
  await expect(page.locator("html")).toHaveClass(/(?:^|\s)light(?:\s|$)/);
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.emulateMedia({ colorScheme: "dark" });
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  expect(await page.evaluate(() => window.themeSentinel)).toBe("same-document");
  await expect(page.locator('[data-article-id="96"]')).toBeVisible();
  expect(observations.pageErrors).toEqual([]);
  expect(observations.blocked).toEqual([]);
});
