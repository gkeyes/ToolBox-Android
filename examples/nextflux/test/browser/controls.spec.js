import { test as base, expect } from "@playwright/test";

// Browser plugin is not available in Actions; use the repository's Playwright runner.
// Real production toolbar, summary, settings shell and dialogs; no live accounts/network.
const test = base.extend({
  page: async ({ page }, use, info) => {
    const errors = [];
    const external = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });
    await page.route("**/*", async (route) => {
      if (new URL(route.request().url()).origin === "http://127.0.0.1:4175") await route.continue();
      else { external.push(route.request().url()); await route.abort("blockedbyclient"); }
    });
    await use(page);
    await info.attach("controls-diagnostics", { body: JSON.stringify({ errors, external }), contentType: "application/json" });
    expect(errors, "production controls must render without runtime or console errors").toEqual([]);
    expect(external, "isolated fixture must not contact live services").toEqual([]);
  },
});

async function open(page, width = 393, theme = "light", scale = 1) {
  await page.setViewportSize({ width, height: 850 });
  await page.goto(`/controls.html?theme=${theme}#/feed/1/article/42`);
  await page.waitForFunction(() => Boolean(window.controlsFixture));
  if (scale !== 1) await page.evaluate((value) => { document.documentElement.style.fontSize = `${16 * value}px`; }, scale);
  await expect(page).toHaveTitle("NextFlux control regression");
  await expect(page.locator(".action-buttons")).toBeVisible();
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
}

async function closeGeometry(button) {
  await expect(button).toBeVisible();
  // HeroUI dialogs briefly scale during entrance. Visibility alone does not
  // mean that geometry is settled. Poll the same geometry assertions rather
  // than disabling the animation or accepting its transient larger bounds.
  await expect.poll(async () => button.evaluate((el) => {
    const rect = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    const svg = el.querySelector("svg").getBoundingClientRect();
    const rounded = (value) => Math.round(value * 100) / 100;
    return {
      width: rounded(rect.width), height: rounded(rect.height),
      visibleWidth: rounded(rect.width - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)),
      visibleHeight: rounded(rect.height - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom)),
      clip: style.backgroundClip, shadow: style.boxShadow,
      centered: Math.abs(svg.x + svg.width / 2 - (rect.x + rect.width / 2)) < 1 &&
        Math.abs(svg.y + svg.height / 2 - (rect.y + rect.height / 2)) < 1,
    };
  }), { message: "settled close control must keep a 48px target and centered 32px painted disc" }).toEqual({
    width: 48, height: 48, visibleWidth: 32, visibleHeight: 32,
    clip: "content-box", shadow: "none", centered: true,
  });
}

async function bounds(page) {
  const items = await page.locator(".action-buttons button:visible").evaluateAll((elements) => elements.map((el) => {
    const { x, y, width, height } = el.getBoundingClientRect(); return { x, y, width, height };
  }));
  for (let i = 0; i < items.length; i++) {
    const a = items[i];
    expect(a.width).toBe(48); expect(a.height).toBe(48);
    expect(a.x).toBeGreaterThanOrEqual(0);
    expect(a.x + a.width).toBeLessThanOrEqual(page.viewportSize().width + 1);
    for (const b of items.slice(i + 1)) {
      expect(a.x + a.width <= b.x + 1 || b.x + b.width <= a.x + 1 || a.y + a.height <= b.y + 1 || b.y + b.height <= a.y + 1,
        "actual hit rectangles must not overlap").toBe(true);
    }
  }
}

for (const [width, theme, scale] of [[320, "light", 1], [393, "light", 1], [393, "dark", 1], [820, "light", 1], [393, "dark", 1.5]]) {
  test(`compact toolbar ${width}px ${theme} font ${scale}`, async ({ page }, info) => {
    await open(page, width, theme, scale);
    await closeGeometry(page.locator(".action-buttons .nextflux-close-button"));
    await bounds(page);
    await expect(page.getByRole("button", { name: "全文适配", exact: true })).toBeVisible();
    await closeGeometry(page.locator(".ai-summary .nextflux-close-button"));
    if (width === 393 && scale === 1) await info.attach(`toolbar-${theme}.png`, {
      body: await page.screenshot(), contentType: "image/png",
    });
    // The outer part of the target is still active although it is no longer painted.
    await page.locator(".action-buttons .nextflux-close-button").click({ position: { x: 2, y: 24 } });
    await expect(page.getByTestId("article-list")).toBeVisible();
    await expect(page.getByTestId("route")).toHaveText("/feed/1");
  });
}

test("all optional controls wrap without clipping and loading does not grow the bar", async ({ page }) => {
  await open(page, 320);
  await page.evaluate(() => window.controlsFixture.setIntegrations(true));
  await bounds(page);
  const before = await page.locator(".action-buttons").boundingBox();
  await page.evaluate(() => window.controlsFixture.setBusy(true));
  await expect(page.locator(".nextflux-toolbar-button[data-pending=\"true\"]")).toHaveCount(1);
  expect((await page.locator(".action-buttons").boundingBox()).height).toBe(before.height);
  await bounds(page);
});

test("toolbar actions and AI close still dispatch their existing behavior", async ({ page }) => {
  await open(page);
  await page.getByRole("button", { name: "Unread", exact: true }).click();
  await page.getByRole("button", { name: "Share", exact: true }).click();
  expect(await page.evaluate(() => window.controlsFixture.calls.map(([name]) => name))).toEqual(["status", "share"]);
  await page.locator(".ai-summary .nextflux-close-button").click();
  await expect(page.locator(".ai-summary")).toHaveCount(0);
});

for (const width of [393, 820]) {
  test(`shared modal and settings close controls ${width}px`, async ({ page }, info) => {
    await open(page, width);
    for (const trigger of ["Open modal", "Open settings"]) {
      await page.getByRole("button", { name: trigger, exact: true }).click();
      const dialog = page.getByRole("dialog");
      const close = dialog.locator(".nextflux-close-button");
      await closeGeometry(close);
      const header = dialog.locator(".nextflux-close-header").first();
      const overlap = await header.evaluate((el) => {
        const title = el.querySelector("h2,h3,[role=heading]");
        const close = el.closest('[role="dialog"]').querySelector(".nextflux-close-button");
        const a = title.getBoundingClientRect(); const b = close.getBoundingClientRect();
        return a.right > b.left && b.right > a.left && a.bottom > b.top && b.bottom > a.top;
      });
      expect(overlap, "heading must not intrude into the close target").toBe(false);
      if (trigger === "Open settings") await info.attach(`settings-${width}.png`, { body: await page.screenshot(), contentType: "image/png" });
      await close.click();
      await expect(dialog).toHaveCount(0);
    }
  });
}

test("alert close preserves pending-operation guard", async ({ page }) => {
  await open(page);
  await page.getByRole("button", { name: "Open alert", exact: true }).click();
  const alert = page.getByRole("alertdialog");
  const close = alert.locator(".nextflux-close-button");
  await closeGeometry(close);
  await alert.getByRole("button", { name: "Confirm", exact: true }).click();
  await expect(close).toBeDisabled();
  await page.keyboard.press("Escape");
  await expect(alert).toBeVisible();
  await expect(page.getByTestId("confirmations")).toHaveText("1");
  await page.evaluate(() => window.controlsFixture.pending());
  await expect(alert).toHaveCount(0);
});

test("keyboard focus remains visible and Escape still dismisses a modal", async ({ page }) => {
  await open(page);
  const close = page.locator(".action-buttons .nextflux-close-button");
  await page.keyboard.press("Tab");
  await expect(close).toBeFocused();
  await expect.poll(() => close.evaluate((el) => getComputedStyle(el).outlineStyle)).toBe("solid");
  await page.getByRole("button", { name: "Open modal", exact: true }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
