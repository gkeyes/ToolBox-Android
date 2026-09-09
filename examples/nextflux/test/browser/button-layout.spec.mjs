import { test, expect } from "@playwright/test";
import { installFixture, openFixture, settleFrames } from "./fixture.mjs";

const toolbar = (page) => page.locator(".action-buttons");
const aiButton = (page) => toolbar(page).getByRole("button", { name: /^(AI 摘要|停止 AI 摘要)$/ });

async function verifyBoundary(page, observations) {
  expect(observations.blocked, "all remote access stays at the fake native boundary").toEqual([]);
  expect(observations.pageErrors).toEqual([]);
  expect(await page.evaluate(() => window.__nextfluxTest.unexpectedNetwork)).toEqual([]);
}

async function setFontScale(page, scale) {
  await page.evaluate((value) => { document.documentElement.style.fontSize = `${16 * value}px`; }, scale);
  await settleFrames(page);
  expect(await page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).fontSize))).toBe(16 * scale);
}

async function openArticle(page) {
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  await expect(page.locator(".article-content")).toContainText("FIXTURE_BODY_96");
  await expect(aiButton(page)).toBeVisible();
  await expect(toolbar(page).getByRole("button", { name: "Save to third-party services", exact: true })).toBeVisible();
}

async function toolbarGeometry(page, scale = 1) {
  await toolbar(page).getByRole("button", { name: "Close", exact: true }).click({ trial: true });
  // Keep HeroUI's press animation: measure its settled result, not a transient
  // pressed scale. A persistent size regression cannot satisfy this condition.
  await expect.poll(() => toolbar(page).locator("button").evaluateAll((elements) => {
    const boxes = elements.map((element) => element.getBoundingClientRect()).filter((box) => box.width && box.height);
    return boxes.length > 0 && boxes.every((box) => Math.abs(box.width - 48) < 0.1 && Math.abs(box.height - 48) < 0.1);
  })).toBe(true);
  const layout = await toolbar(page).evaluate((element) => {
    const box = (node) => {
      const rect = node.getBoundingClientRect();
      return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height, cx: rect.left + rect.width / 2, cy: rect.top + rect.height / 2 };
    };
    return {
      ...box(element), viewport: window.innerWidth, scrollWidth: element.scrollWidth, clientWidth: element.clientWidth,
      buttons: [...element.querySelectorAll("button")].filter((button) => button.getBoundingClientRect().width > 0).map((button) => {
        const icon = button.querySelector('[data-slot="spinner"]') || button.querySelector("svg");
        const style = icon && getComputedStyle(icon);
        return { ...box(button), name: button.getAttribute("aria-label"), icon: icon ? { ...box(icon), width: parseFloat(style.width), height: parseFloat(style.height) } : null };
      }),
    };
  });
  expect(layout.buttons.length).toBeGreaterThanOrEqual(7);
  expect(layout.left).toBeGreaterThanOrEqual(-1);
  expect(layout.right).toBeLessThanOrEqual(layout.viewport + 1);
  expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth + 1);
  for (const button of layout.buttons) {
    expect(button.name, "every toolbar action has an accessible name").toBeTruthy();
    expect(button.width, button.name).toBeGreaterThanOrEqual(47.99);
    expect(button.height, button.name).toBeGreaterThanOrEqual(47.99);
    expect(Math.abs(button.width - button.height), button.name).toBeLessThanOrEqual(0.1);
    expect(button.left, button.name).toBeGreaterThanOrEqual(layout.left - 1);
    expect(button.right, button.name).toBeLessThanOrEqual(layout.right + 1);
    expect(button.top, button.name).toBeGreaterThanOrEqual(layout.top - 1);
    expect(button.bottom, button.name).toBeLessThanOrEqual(layout.bottom + 1);
    expect(button.icon, `${button.name} has a visible icon or spinner`).not.toBeNull();
    expect(button.icon.width, button.name).toBeCloseTo(16 * scale, 1);
    expect(button.icon.height, button.name).toBeCloseTo(16 * scale, 1);
    expect(Math.abs(button.icon.cx - button.cx), button.name).toBeLessThanOrEqual(1);
    expect(Math.abs(button.icon.cy - button.cy), button.name).toBeLessThanOrEqual(1);
  }
  for (let i = 0; i < layout.buttons.length; i += 1) {
    for (const other of layout.buttons.slice(i + 1)) {
      const button = layout.buttons[i];
      const horizontal = Math.min(button.right, other.right) - Math.max(button.left, other.left);
      const vertical = Math.min(button.bottom, other.bottom) - Math.max(button.top, other.top);
      expect(horizontal > 1 && vertical > 1, `${button.name} must not overlap ${other.name}`).toBe(false);
      if (vertical > 1) expect(Math.abs(button.cy - other.cy), `${button.name} and ${other.name} share a row center`).toBeLessThanOrEqual(1);
    }
  }
  return layout;
}

function expectSameGeometry(before, after) {
  expect(after.buttons).toHaveLength(before.buttons.length);
  for (let i = 0; i < before.buttons.length; i += 1) {
    for (const dimension of ["left", "top", "width", "height"]) {
      expect(Math.abs(after.buttons[i][dimension] - before.buttons[i][dimension]), `${before.buttons[i].name}: ${dimension} stays stable`).toBeLessThanOrEqual(1);
    }
  }
}

test("toolbar targets and row alignment survive narrow screens and both responsive boundaries", async ({ page }) => {
  const observations = await installFixture(page, { aiEnabled: true, hasIntegrations: true });
  await openArticle(page);
  for (const width of [320, 390, 639, 640, 641, 767, 768, 769, 1280]) {
    await test.step(`${width}px viewport`, async () => {
      await page.setViewportSize({ width, height: 844 });
      await settleFrames(page);
      const layout = await toolbarGeometry(page);
      expect(layout.buttons).toHaveLength(width >= 768 ? 9 : 7);
      if (width === 320) expect(new Set(layout.buttons.map((button) => Math.round(button.cy))).size).toBeGreaterThan(1);
    });
  }
  await toolbar(page).getByRole("button", { name: "Close", exact: true }).click();
  await expect(page).not.toHaveURL(/\/article\/96/);
  await expect(page.locator(".article-title")).toHaveCount(0);
  await verifyBoundary(page, observations);
});

for (const scenario of [{ width: 390, scale: 1 }, { width: 320, scale: 2 }]) {
  test(`AI waiting, streaming and completion retain button geometry at ${scenario.width}px and ${scenario.scale * 100}% font`, async ({ page }) => {
    await page.setViewportSize({ width: scenario.width, height: 844 });
    const observations = await installFixture(page, { aiEnabled: true, hasIntegrations: true });
    await openArticle(page);
    await setFontScale(page, scenario.scale);
    const normal = await toolbarGeometry(page, scenario.scale);
    await expect(aiButton(page)).toHaveAttribute("aria-label", "AI 摘要");
    await aiButton(page).click();
    const summary = page.locator(".ai-summary");
    await expect(summary).toContainText("Generating summary...");
    await expect(aiButton(page)).toHaveAttribute("aria-label", "停止 AI 摘要");
    await expect(aiButton(page).locator('[data-slot="spinner"]')).toBeVisible();
    await expect.poll(() => page.evaluate(() => window.__nextfluxTest.streams.filter((entry) => entry.method === "readStream").length)).toBe(1);
    expectSameGeometry(normal, await toolbarGeometry(page, scenario.scale));

    await page.evaluate(() => window.__nextfluxTest.emitAIChunk("A streamed fixture summary."));
    await expect(summary).toContainText("A streamed fixture summary.");
    await expect(summary).not.toContainText("Generating summary...");
    await expect(aiButton(page)).toHaveAttribute("aria-label", "停止 AI 摘要");
    await expect(summary.locator(".nextflux-close-button")).toHaveCount(0);
    expectSameGeometry(normal, await toolbarGeometry(page, scenario.scale));

    await page.evaluate(() => window.__nextfluxTest.finishAIStream());
    await expect(aiButton(page)).toHaveAttribute("aria-label", "AI 摘要");
    await expect(aiButton(page).locator('[data-slot="spinner"]')).toHaveCount(0);
    await expect(summary).toContainText("A streamed fixture summary.");
    expectSameGeometry(normal, await toolbarGeometry(page, scenario.scale));
    const close = summary.getByRole("button", { name: "Close AI Summary", exact: true });
    await expectCloseGeometry(close);
    const heading = await summary.locator(":scope > div").first().evaluate((element) => {
      const rect = element.getBoundingClientRect();
      const close = element.querySelector(".nextflux-close-button").getBoundingClientRect();
      const range = document.createRange();
      range.selectNodeContents(element.querySelector("span"));
      const text = range.getBoundingClientRect();
      return { height: rect.height, right: rect.right, textRight: text.right, closeLeft: close.left, closeRight: close.right };
    });
    expect(heading.height).toBeGreaterThanOrEqual(48);
    expect(heading.textRight).toBeLessThanOrEqual(heading.closeLeft + 1);
    expect(heading.closeRight).toBeLessThanOrEqual(heading.right + 1);
    await close.click();
    await expect(summary).toHaveCount(0);
    expect(await page.evaluate(() => window.__nextfluxTest.streams.filter((entry) => entry.method === "openStream").length)).toBe(1);
    expect(await page.evaluate(() => window.__nextfluxTest.streams.filter((entry) => entry.method === "cancelStream").length)).toBe(1);
    if (scenario.scale === 1) {
      await aiButton(page).click();
      await expect(summary).toContainText("Generating summary...");
      await expect.poll(() => page.evaluate(() => window.__nextfluxTest.streams.filter((entry) => entry.method === "openStream").length)).toBe(2);
      await aiButton(page).click();
      await expect(aiButton(page)).toHaveAttribute("aria-label", "AI 摘要");
      await expect(aiButton(page).locator('[data-slot="spinner"]')).toHaveCount(0);
      expectSameGeometry(normal, await toolbarGeometry(page, scenario.scale));
      await expect.poll(() => page.evaluate(() => window.__nextfluxTest.streams.filter((entry) => entry.method === "cancelStream").length)).toBe(2);
      await close.click();
      await expect(summary).toHaveCount(0);
    }
    await verifyBoundary(page, observations);
  });
}

test("reader and third-party loading spinners keep the same targets as normal toolbar icons", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  const observations = await installFixture(page, { aiEnabled: true, hasIntegrations: true });
  await openArticle(page);
  for (const action of [
    { name: "Reader view", path: "/v1/entries/96/fetch-content" },
    { name: "Save to third-party services", path: "/v1/entries/96/save" },
  ]) {
    const normal = await toolbarGeometry(page);
    const button = toolbar(page).getByRole("button", { name: action.name, exact: true });
    await page.evaluate((path) => window.__nextfluxTest.holdNetworkNext(path), action.path);
    await button.click();
    await expect.poll(() => page.evaluate(() => window.__nextfluxTest.heldNetwork.length)).toBe(1);
    await expect(button.locator('[data-slot="spinner"]')).toBeVisible();
    expectSameGeometry(normal, await toolbarGeometry(page));
    await page.evaluate(() => window.__nextfluxTest.releaseNetwork());
    await expect(toolbar(page).locator('[data-slot="spinner"]')).toHaveCount(0);
    expectSameGeometry(normal, await toolbarGeometry(page));
  }
  await expect(page.locator(".article-content")).toContainText("FIXTURE_ORIGINAL_96");
  await verifyBoundary(page, observations);
});

async function expectCloseGeometry(close) {
  await expect(close).toBeVisible();
  await close.click({ trial: true });
  await expect.poll(() => close.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return Math.abs(rect.width - 48) < 0.1 && Math.abs(rect.height - 48) < 0.1;
  })).toBe(true);
  const geometry = await close.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const svg = element.querySelector("svg").getBoundingClientRect();
    const style = getComputedStyle(element);
    return { width: rect.width, height: rect.height, radius: style.borderTopLeftRadius, cx: rect.left + rect.width / 2, cy: rect.top + rect.height / 2, iconX: svg.left + svg.width / 2, iconY: svg.top + svg.height / 2 };
  });
  expect(geometry.width).toBeGreaterThanOrEqual(47.99);
  expect(geometry.height).toBeGreaterThanOrEqual(47.99);
  const radius = parseFloat(geometry.radius) * (geometry.radius.endsWith("%") ? geometry.width / 100 : 1);
  expect(radius).toBeGreaterThanOrEqual(geometry.width / 2 - 0.1);
  expect(Math.abs(geometry.cx - geometry.iconX)).toBeLessThanOrEqual(1);
  expect(Math.abs(geometry.cy - geometry.iconY)).toBeLessThanOrEqual(1);
}

async function openSidebar(page) {
  const profile = page.locator(".profile-button button");
  const insideViewport = await profile.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return rect.left >= 0 && rect.right <= window.innerWidth && rect.width > 0;
  });
  if (!insideViewport) await page.locator('[data-sidebar="trigger"]').click();
  await expect(profile).toBeInViewport();
  return profile;
}

async function openProfileAction(page, name) {
  const profile = await openSidebar(page);
  await profile.click();
  await page.getByRole("menuitem", { name, exact: true }).click();
}

async function verifyDialogAndClose(page, dialog, scale) {
  await expect(dialog).toBeVisible();
  await setFontScale(page, scale);
  const close = dialog.locator(".nextflux-close-button");
  await expectCloseGeometry(close);
  const layout = await dialog.evaluate((element) => {
    const rect = (node) => {
      const box = node.getBoundingClientRect();
      return { left: box.left, right: box.right, top: box.top, bottom: box.bottom };
    };
    return { dialog: rect(element), close: rect(element.querySelector(".nextflux-close-button")), viewport: window.innerWidth,
      headings: [...element.querySelectorAll("h2, h3, [role=heading]")].filter((node) => node.getBoundingClientRect().width > 0).map((node) => {
        const range = document.createRange();
        range.selectNodeContents(node);
        return rect(range);
      }) };
  });
  expect(layout.dialog.left).toBeGreaterThanOrEqual(-1);
  expect(layout.dialog.right).toBeLessThanOrEqual(layout.viewport + 1);
  expect(layout.close.left).toBeGreaterThanOrEqual(layout.dialog.left);
  expect(layout.close.right).toBeLessThanOrEqual(layout.dialog.right + 1);
  expect(layout.headings.length).toBeGreaterThan(0);
  for (const heading of layout.headings) {
    expect(heading.right).toBeLessThanOrEqual(layout.dialog.right + 1);
    const horizontal = Math.min(heading.right, layout.close.right) - Math.max(heading.left, layout.close.left);
    const vertical = Math.min(heading.bottom, layout.close.bottom) - Math.max(heading.top, layout.close.top);
    expect(horizontal > 1 && vertical > 1, "dialog heading reserves space for its close button").toBe(false);
  }
  await close.click();
  await expect(dialog).toHaveCount(0);
  await setFontScale(page, 1);
}

for (const width of [390, 1280]) {
  test(`shared dialogs and Settings close correctly without title overlap at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const observations = await installFixture(page);
    await openFixture(page);
    const profile = await openSidebar(page);
    const profileBox = await profile.boundingBox();
    expect(profileBox.width).toBeGreaterThan(profileBox.height);
    if (width === 390) expect(profileBox.height).toBeGreaterThanOrEqual(44);
    const add = page.getByRole("button", { name: "添加订阅", exact: true });
    if (width === 390) expect((await add.boundingBox()).height).toBeGreaterThanOrEqual(44);
    await add.click();
    const category = page.getByRole("menuitem", { name: "New category", exact: true });
    await expect(category).toBeVisible();
    const menuBox = await category.boundingBox();
    expect(menuBox.width).toBeGreaterThan(menuBox.height);
    await category.click();
    const newCategory = page.getByRole("dialog", { name: "New category", exact: true });
    await expect(newCategory).toBeVisible();
    const cancel = await newCategory.getByRole("button", { name: "Cancel", exact: true }).boundingBox();
    expect(cancel.width).toBeGreaterThan(cancel.height);
    await verifyDialogAndClose(page, newCategory, 1);

    await openProfileAction(page, "Settings");
    const settings = page.getByRole("dialog").filter({ has: page.locator(".settings-content") });
    await verifyDialogAndClose(page, settings, 1);
    await openProfileAction(page, "Settings");
    await verifyDialogAndClose(page, settings, 2);

    await openSidebar(page);
    await add.click();
    await page.getByRole("menuitem", { name: "New category", exact: true }).click();
    await verifyDialogAndClose(page, newCategory, 2);
    await openProfileAction(page, "Logout");
    await verifyDialogAndClose(page, page.getByRole("alertdialog", { name: "Logout", exact: true }), 2);
    await expect(page.locator('[data-article-id="96"]')).toBeAttached();
    await verifyBoundary(page, observations);
  });
}
