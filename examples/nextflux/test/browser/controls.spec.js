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

// Phones reach low-frequency reader actions through the dock's "More" sheet;
// wide screens show them inline. Press whichever surface the layout offers.
async function readerAction(page, name) {
  const direct = page.getByRole("button", { name, exact: true });
  const more = page.getByRole("button", { name: "更多", exact: true });
  await expect(direct.or(more).first()).toBeVisible();
  if (await direct.isVisible()) return direct.click();
  await page.getByRole("button", { name: "更多", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name, exact: true }).click();
  await expect(page.getByRole("dialog").filter({ hasText: "这篇文章" })).toHaveCount(0);
}

// Phones float the reader's close as a 44px glass disc painted by ::before;
// every other close paints a 32px disc. Both keep the 48px target.
async function closeGeometry(button, painted = 32) {
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
      glass: getComputedStyle(el, "::before").backdropFilter.includes("blur"),
      centered: Math.abs(svg.x + svg.width / 2 - (rect.x + rect.width / 2)) < 1 &&
        Math.abs(svg.y + svg.height / 2 - (rect.y + rect.height / 2)) < 1,
    };
  }), { message: `settled close control must keep a 48px target and centered ${painted}px painted disc` }).toEqual({
    width: 48, height: 48, visibleWidth: painted, visibleHeight: painted,
    clip: "content-box", shadow: "none", glass: painted === 44, centered: true,
  });
}

async function bounds(page) {
  const items = await page.locator(".action-buttons button:visible").evaluateAll((elements) => elements.map((el) => {
    const { x, y, width, height } = el.getBoundingClientRect(); return { x, y, width, height };
  }));
  for (let i = 0; i < items.length; i++) {
    const a = items[i];
    expect(a.height).toBe(48);
    if (page.viewportSize().width <= 640) {
      expect(a.width).toBeGreaterThanOrEqual(26);
      expect(a.width).toBeLessThanOrEqual(48);
    } else {
      expect(a.width).toBe(48);
    }
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
    await closeGeometry(page.locator(".action-buttons .nextflux-close-button"), width < 768 ? 44 : 32);
    await bounds(page);
    if (width < 768) {
      await page.getByRole("button", { name: "更多", exact: true }).click();
      await expect(page.getByRole("dialog").getByRole("button", { name: "全文适配", exact: true })).toBeVisible();
      await page.keyboard.press("Escape");
      await expect(page.getByRole("dialog")).toHaveCount(0);
    } else {
      await expect(page.getByRole("button", { name: "全文适配", exact: true })).toBeVisible();
    }
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

test("all optional controls fit one row without clipping and loading does not grow the bar", async ({ page }) => {
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
  await readerAction(page, "Unread");
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
      if (width < 768 && trigger === "Open settings") {
        // The tall settings sheet paints its own close button, shaped like the
        // back button, and keeps a single close target.
        await expect(dialog.getByRole("button", { name: /close|关闭/i })).toHaveCount(1);
        const visible = dialog.locator(".nextflux-close-header").getByRole("button", { name: /close|关闭/i });
        const box = await visible.boundingBox();
        expect(box.width).toBeGreaterThanOrEqual(40);
        await info.attach(`settings-${width}.png`, { body: await page.screenshot(), contentType: "image/png" });
        await visible.click();
        await expect(dialog).toHaveCount(0);
        // A touch without pointer events (some phone WebViews) still drags it closed.
        await page.getByRole("button", { name: trigger, exact: true }).click();
        await page.waitForTimeout(400);
        await dialog.locator(".nextflux-close-header").evaluate(async (el) => {
          const b = el.getBoundingClientRect();
          const at = (y) => new Touch({ identifier: 9, target: el, clientX: b.left + 40, clientY: y });
          const fire = (type, t, list) => el.dispatchEvent(new TouchEvent(type, { bubbles: true, cancelable: true, touches: list, changedTouches: [t] }));
          let t = at(b.top + 10);
          fire("touchstart", t, [t]);
          for (let i = 1; i <= 10; i++) { await new Promise((r) => setTimeout(r, 16)); t = at(b.top + 10 + i * 30); fire("touchmove", t, [t]); }
          fire("touchend", t, []);
        });
        await expect(dialog).toHaveCount(0);
        // With pointer events, the title row drags it closed too.
        await page.getByRole("button", { name: trigger, exact: true }).click();
        await page.waitForTimeout(400);
        const title = await dialog.locator(".nextflux-close-header [role=heading], .nextflux-close-header h2").first().boundingBox();
        await page.mouse.move(title.x + 10, title.y + 10);
        await page.mouse.down();
        for (let i = 1; i <= 10; i++) await page.mouse.move(title.x + 10, title.y + 10 + i * 30);
        await page.mouse.up();
        await expect(dialog).toHaveCount(0);
        continue;
      }
      if (width < 768) {
        // A phone sheet closes by dragging down; its close button is only for
        // screen readers and must not paint or take space in the title row.
        const box = await close.boundingBox();
        expect(box.width * box.height).toBeLessThanOrEqual(1);
        await expect(dialog.getByRole("button", { name: /close|关闭/i })).toHaveCount(1);
        const header = await dialog.locator(".nextflux-close-header").first().evaluate((el) => getComputedStyle(el).paddingInlineEnd);
        expect(parseFloat(header)).toBeLessThanOrEqual(16);
        if (trigger === "Open settings") await info.attach(`settings-${width}.png`, { body: await page.screenshot(), contentType: "image/png" });
        // Keyboard focus reveals it, so focus never lands on an invisible target.
        await close.evaluate((el) => el.focus({ focusVisible: true }));
        await page.keyboard.press("Shift");
        const focused = await close.evaluate((el) => el.matches(":focus-visible") ? el.getBoundingClientRect().width : -1);
        expect(focused).toBeGreaterThanOrEqual(44);
        await close.dispatchEvent("click");
        await expect(dialog).toHaveCount(0);
        continue;
      }
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

test("alert buttons stack at 2x text on a 320 px phone instead of breaking labels per character", async ({ page }, info) => {
  await open(page, 320, "light", 2);
  await page.getByRole("button", { name: "Open alert", exact: true }).click();
  const alert = page.getByRole("alertdialog");
  await expect(alert).toBeVisible();
  // The longest real label (the feed-notification prompt's confirm) at the largest system text size.
  await alert.getByRole("button", { name: "Confirm", exact: true }).evaluate((node) => { node.lastChild.textContent = "开启并通知"; });
  const boxes = await alert.locator(".nextflux-modal-footer button").evaluateAll((nodes) => nodes.map((node) => {
    const rect = node.getBoundingClientRect();
    return { width: rect.width, height: rect.height, line: parseFloat(getComputedStyle(node).lineHeight), text: node.textContent };
  }));
  const dialog = await alert.boundingBox();
  for (const box of boxes) {
    expect(box.height, `${box.text} stays on one line`).toBeLessThan(box.line * 1.5 + 24);
    expect(box.width, `${box.text} fits the dialog`).toBeLessThanOrEqual(dialog.width);
  }
  // A body taller than the dialog shows the "more below" shade until it is scrolled to the end.
  const body = alert.locator(".nextflux-alert-body");
  const shade = () => body.evaluate((node) => Number(getComputedStyle(node, "::after").opacity));
  expect(await shade(), "short text has no shade").toBe(0);
  await body.evaluate((node) => { node.querySelector("p").textContent = "说明文字。".repeat(60); });
  await expect.poll(shade).toBeGreaterThan(0.5);
  await body.evaluate((node) => { node.scrollTop = node.scrollHeight; });
  await expect.poll(shade).toBe(0);
  await info.attach("alert-320-z2.png", { body: await page.screenshot(), contentType: "image/png" });
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


test("mobile sheet keeps a small drag and dismisses a deliberate downward drag", async ({ page }) => {
  await open(page, 393);
  await page.getByRole("button", { name: "Open modal", exact: true }).click();
  let dialog = page.getByRole("dialog");
  const handle = dialog.locator(".nextflux-sheet-handle-zone");
  await expect(handle).toBeVisible();
  const restingTop = (await dialog.boundingBox()).y;

  let box = await handle.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + 36, { steps: 3 });
  await page.mouse.up();
  await expect(dialog).toBeVisible();
  await expect.poll(async () => {
    const geometry = await dialog.boundingBox();
    const owned = await dialog.evaluate((element) => ({
      translate: element.style.translate,
      willChange: element.style.willChange,
    }));
    return {
      settled: Math.abs(geometry.y - restingTop) < 1,
      translate: owned.translate,
      willChange: owned.willChange,
    };
  }).toEqual({ settled: true, translate: "", willChange: "" });

  box = await handle.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + 240, { steps: 4 });
  await page.mouse.up();
  await expect(dialog).toHaveCount(0);
});

async function openSidebarPreferences(page, width = 1280) {
  await page.setViewportSize({ width, height: 850 });
  await page.goto("/controls.html?sidebar=1");
  await page.waitForFunction(() => Boolean(window.controlsSidebarFixture));
  if (width <= 640) await page.locator('[data-sidebar="trigger"]').click();
  await expect(page.getByRole("switch", { name: "显示隐藏订阅", exact: true })).toBeVisible();
}

test("sidebar hidden feeds is a first-level peer of background sync and leaves General", async ({ page }) => {
  await openSidebarPreferences(page);
  const hidden = page.getByRole("switch", { name: "显示隐藏订阅", exact: true });
  const background = page.getByRole("switch", { name: "后台同步", exact: true });
  await expect(background).toBeVisible();
  await expect(hidden).not.toBeChecked();
  const positions = await hidden.evaluate((element) => {
    const footer = element.closest('[data-sidebar="footer"]');
    const peer = [...footer?.querySelectorAll('[role="switch"]') || []].find((node) => node.getAttribute("aria-label") === "后台同步");
    const row = (node) => [...footer?.children || []].find((child) => child.contains(node));
    return { hasFooter: Boolean(footer), sameLevel: Boolean(peer && row(element)?.parentElement === row(peer)?.parentElement), distinctRows: row(element) !== row(peer) };
  });
  expect(positions).toEqual({ hasFooter: true, sameLevel: true, distinctRows: true });
  await expect(page.getByTestId("sidebar-general").getByText("显示隐藏订阅", { exact: true })).toHaveCount(0);
  await expect(page.getByTestId("sidebar-general").getByText("默认展开分类", { exact: true })).toBeVisible();
  await hidden.locator('xpath=ancestor::*[@data-slot="switch"][1]').click();
  await expect(hidden).toBeChecked();
  await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.persistedHidden())).toBe(true);
  expect(await page.evaluate(() => window.controlsSidebarFixture.feedLoads)).toEqual([false, true]);
  await page.reload();
  await page.waitForFunction(() => Boolean(window.controlsSidebarFixture));
  await expect(page.getByRole("switch", { name: "显示隐藏订阅", exact: true })).toBeChecked();
  await page.getByRole("switch", { name: "显示隐藏订阅", exact: true }).locator('xpath=ancestor::*[@data-slot="switch"][1]').click();
  await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.persistedHidden())).toBe(false);
});

test("sidebar hidden feeds disables its mobile switch until the native preference write completes", async ({ page }) => {
  await openSidebarPreferences(page, 393);
  const hidden = page.getByRole("switch", { name: "显示隐藏订阅", exact: true });
  const baseline = await page.evaluate(() => ({
    persisted: window.controlsSidebarFixture.persistedHidden(),
    writes: window.controlsSidebarFixture.writes.length,
  }));
  await page.evaluate(() => window.controlsSidebarFixture.holdWrites());
  await hidden.locator('xpath=ancestor::*[@data-slot="switch"][1]').click();
  await expect(hidden).toBeChecked();
  await expect(hidden).toBeDisabled();
  await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.pendingWrites())).toBe(1);
  expect(await page.evaluate((start) => window.controlsSidebarFixture.writes.slice(start), baseline.writes)).toEqual([true]);
  expect(await page.evaluate(() => window.controlsSidebarFixture.persistedHidden())).toBe(baseline.persisted);
  await page.evaluate(() => window.controlsSidebarFixture.finishWrites());
  await expect(hidden).toBeEnabled();
  await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.persistedHidden())).toBe(true);
});

test("sidebar hidden feeds reports a native persistence failure and remains usable", async ({ page }) => {
  await openSidebarPreferences(page);
  const hidden = page.getByRole("switch", { name: "显示隐藏订阅", exact: true });
  const persistedBefore = await page.evaluate(() => window.controlsSidebarFixture.persistedHidden());
  await page.evaluate(() => window.controlsSidebarFixture.failNextWrite());
  await hidden.locator('xpath=ancestor::*[@data-slot="switch"][1]').click();
  await expect(page.getByText("Fixture preference storage unavailable", { exact: true })).toBeVisible();
  await expect(hidden).toBeEnabled();
  expect(await page.evaluate(() => window.controlsSidebarFixture.persistedHidden())).toBe(persistedBefore);
  await hidden.locator('xpath=ancestor::*[@data-slot="switch"][1]').click();
  await expect(hidden).not.toBeChecked();
  await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.persistedHidden())).toBe(false);
});

// Regression: after a sheet closes, nothing may be left covering the page.
// A backdrop stranded in its exit state swallows every tap ("UI frozen").
async function expectPageInteractive(page) {
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => {
    const hit = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    const covered = Boolean(hit?.closest(".drawer__backdrop, .modal__backdrop, .nextflux-modal-backdrop, [data-slot='backdrop']"));
    const inertRoot = Boolean(document.querySelector("#root[inert], #root[aria-hidden='true']"));
    return { covered, inertRoot, bodyBlocked: getComputedStyle(document.body).pointerEvents === "none" };
  })).toEqual({ covered: false, inertRoot: false, bodyBlocked: false });
  // A real tap must still reach the page: reopening proves it.
  await page.getByRole("button", { name: "Open modal", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
}

for (const reduced of [false, true]) {
  test(`page stays interactive after every sheet close path${reduced ? " (reduced motion)" : ""}`, async ({ page }) => {
    if (reduced) await page.emulateMedia({ reducedMotion: "reduce" });
    await open(page, 393);
    const openButton = page.getByRole("button", { name: "Open modal", exact: true });

    await openButton.click();
    await page.getByRole("dialog").locator(".nextflux-close-button").dispatchEvent("click");
    await expectPageInteractive(page);

    await openButton.click();
    await page.keyboard.press("Escape");
    await expectPageInteractive(page);

    await openButton.click();
    const handle = page.getByRole("dialog").locator(".nextflux-sheet-handle-zone");
    const box = await handle.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + 260, { steps: 4 });
    await page.mouse.up();
    await expectPageInteractive(page);
  });
}

// Nothing may cover the page at any of several points, and no ancestor of the
// hit target may be inert: a stranded backdrop, scrim or React Aria inert root
// swallows every tap while the page looks idle ("UI frozen").
async function expectUncovered(page) {
  await expect.poll(() => page.evaluate(() => {
    const points = [
      [innerWidth / 2, innerHeight / 2], [24, 120], [innerWidth - 24, 120],
      [24, innerHeight - 140], [innerWidth - 24, innerHeight / 3],
    ];
    const blocked = [];
    for (const [x, y] of points) {
      const hit = document.elementFromPoint(x, y);
      const cover = hit?.closest(".drawer__backdrop, .modal__backdrop, .alert-dialog__backdrop, .nextflux-modal-backdrop, [data-slot='backdrop'], .sidebar-scrim.is-open, [inert], [role='dialog']");
      if (!hit || cover) blocked.push(`${Math.round(x)},${Math.round(y)}:${cover?.className || "none"}`);
    }
    return {
      blocked,
      inertRoot: Boolean(document.querySelector("#root[inert], #root[aria-hidden='true']")),
      bodyBlocked: getComputedStyle(document.body).pointerEvents === "none",
    };
  })).toEqual({ blocked: [], inertRoot: false, bodyBlocked: false });
}

async function dragSheetDown(page, distance = 260) {
  const handle = page.getByRole("dialog").locator(".nextflux-sheet-handle-zone");
  const box = await handle.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + distance, { steps: 4 });
  await page.mouse.up();
  // The release point can sit over a dock button once the sheet is gone; a
  // phone has no hover, so do not leave a desktop tooltip open there.
  await page.mouse.move(0, 0);
}

async function openFromMore(page, name, heading) {
  await page.getByRole("button", { name: "更多", exact: true }).click();
  // Tap, as on a phone: a mouse click leaves the pointer resting where the
  // compact sheet was, over a dock button whose desktop hover tooltip would
  // then take the next Escape meant for the second sheet.
  await page.getByRole("dialog").getByRole("button", { name, exact: true }).tap();
  // The second sheet opens only after the More sheet has gone.
  await expect(page.getByRole("dialog").filter({ hasText: "这篇文章" })).toHaveCount(0);
  await expect(page.getByRole("dialog").filter({ hasText: heading })).toBeVisible();
}

for (const reduced of [false, true]) {
  test(`More sheet hand-off to a second sheet leaves the page interactive${reduced ? " (reduced motion)" : ""}`, async ({ page }) => {
    if (reduced) await page.emulateMedia({ reducedMotion: "reduce" });
    await open(page, 393);

    // The full-text adapter fails fast in this fixture (no server), so its
    // sheet is closable; the title filter path is covered in title-filter.spec.
    await openFromMore(page, "全文适配", "AI 全文适配");
    await page.getByRole("dialog").locator(".nextflux-close-button").dispatchEvent("click");
    await page.mouse.move(0, 0); // the dock under a closed sheet must not keep a hover tooltip
    await expectUncovered(page);

    await openFromMore(page, "全文适配", "AI 全文适配");
    await dragSheetDown(page);
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expectUncovered(page);

    await openFromMore(page, "全文适配", "AI 全文适配");
    await page.keyboard.press("Escape");
    await expectUncovered(page);
    await expectPageInteractive(page);
  });
}

test("a route change with the More sheet open releases the page", async ({ page }) => {
  await open(page, 393);
  await page.getByRole("button", { name: "更多", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.evaluate(() => { location.hash = "#/feed/1"; });
  await expect(page.getByTestId("article-list")).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expectUncovered(page);
  await expectPageInteractive(page);
  // And back into the reader: the dock and its sheet still work.
  await page.evaluate(() => { location.hash = "#/feed/1/article/42"; });
  await page.getByRole("button", { name: "更多", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expectUncovered(page);
});

test("rapid open and close of sheets never strands a backdrop", async ({ page }) => {
  await open(page, 393);
  const more = page.getByRole("button", { name: "更多", exact: true });
  for (let round = 0; round < 3; round += 1) {
    await more.click();
    await page.keyboard.press("Escape");
  }
  await expectUncovered(page);
  // Close by tapping the backdrop while the sheet is still entering.
  await more.click();
  await page.mouse.click(200, 40);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expectUncovered(page);
  // Open a sheet, then another one from the page before the first finished closing.
  await page.getByRole("button", { name: "Open modal", exact: true }).click();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Open settings", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: /close|关闭/i }).first().dispatchEvent("click");
  await expectUncovered(page);
  await expectPageInteractive(page);
});

test("mobile sidebar closes from its scrim and leaves the page tappable", async ({ page }) => {
  await openSidebarPreferences(page, 393);
  const panel = page.locator('[data-sidebar="sidebar"][data-mobile="true"]');
  await expect(panel).toHaveAttribute("data-open", "true");
  await page.mouse.click(370, 400);
  await expect(panel).toHaveAttribute("data-open", "false");
  await expect.poll(() => panel.evaluate((element) => element.getBoundingClientRect().right <= 0)).toBe(true);
  await expectUncovered(page);
  // A real tap on the page reaches its control again.
  await page.locator("main [data-sidebar='trigger']").click();
  await expect(panel).toHaveAttribute("data-open", "true");
  await page.mouse.click(370, 400);
  await expect(panel).toHaveAttribute("data-open", "false");
});

test("the full-text adapter can be cancelled while it is still reading", async ({ page }) => {
  await open(page, 393);
  // Hold both reads so the adapter stays in its first, server-neutral phase.
  await page.evaluate(() => {
    window.holdFullTextRead = true;
    window.ToolBox = { network: { request: () => new Promise(() => {}) } };
  });
  await openFromMore(page, "全文适配", "AI 全文适配");
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("status")).toContainText("正在读取");
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expectUncovered(page);

  await openFromMore(page, "全文适配", "AI 全文适配");
  await expect(dialog.getByRole("status")).toContainText("正在读取");
  await dragSheetDown(page);
  await expect(dialog).toHaveCount(0);
  await expectUncovered(page);
  await expectPageInteractive(page);
});

// AI read-aloud: "stop" must end every phase, nothing may restart afterwards,
// and the page must keep taking taps. The native stream bridge is a fixture;
// the speech controller, MiniMax client and player UI are production code.
async function installSpeech(page, { holdRead }) {
  await page.addInitScript(() => {
    window.speechAudios = new Set();
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function (...args) { window.speechAudios.add(this); return play.apply(this, args); };
  });
  await open(page, 393);
  await page.evaluate((hold) => {
    const wav = (seconds = 6, rate = 8000) => {
      const count = seconds * rate;
      const view = new DataView(new ArrayBuffer(44 + count));
      const ascii = (offset, text) => { for (let i = 0; i < text.length; i += 1) view.setUint8(offset + i, text.charCodeAt(i)); };
      ascii(0, "RIFF"); view.setUint32(4, 36 + count, true); ascii(8, "WAVE"); ascii(12, "fmt ");
      view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
      view.setUint32(24, rate, true); view.setUint32(28, rate, true); view.setUint16(32, 1, true); view.setUint16(34, 8, true);
      ascii(36, "data"); view.setUint32(40, count, true);
      for (let i = 0; i < count; i += 1) view.setUint8(44 + i, 128 + Math.round(40 * Math.sin((i / rate) * 2 * Math.PI * 440)));
      return Array.from(new Uint8Array(view.buffer), (byte) => byte.toString(16).padStart(2, "0")).join("");
    };
    const body = new TextEncoder().encode(JSON.stringify({
      data: { audio: wav(), status: 2 }, extra_info: { audio_length: 6000 }, base_resp: { status_code: 0 },
    }));
    const net = window.speechNet = { opened: 0, cancelled: 0 };
    const offsets = new Map();
    window.ToolBox = { network: {
      openStream: async () => { net.opened += 1; const id = `speech-${net.opened}`; offsets.set(id, 0); return { streamId: id, status: 200 }; },
      readStream: async (id) => {
        if (hold) return new Promise(() => {});
        const offset = offsets.get(id);
        const data = body.subarray(offset, offset + 65536);
        offsets.set(id, offset + data.length);
        return { data, done: offset + data.length >= body.length };
      },
      cancelStream: async () => { net.cancelled += 1; },
    } };
    window.controlsFixture.speech.configure();
  }, holdRead);
}

async function expectSpeechStopped(page) {
  await expect(page.locator(".nextflux-speech-bar")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "朗读文章", exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => ({
    phase: window.controlsFixture.speech.phase(),
    playing: [...window.speechAudios].filter((audio) => !audio.paused).length,
  }))).toEqual({ phase: "idle", playing: 0 });
  // Nothing late (a held read, a pending play(), prefetch) may restart it.
  await page.waitForTimeout(800);
  expect(await page.evaluate(() => window.controlsFixture.speech.phase())).toBe("idle");
  await expect(page.locator(".nextflux-speech-bar")).toHaveCount(0);
  await expectUncovered(page);
  await expectPageInteractive(page);
}

test("read-aloud stops while it is still preparing, from the bar and from the dock", async ({ page }) => {
  await installSpeech(page, { holdRead: true });
  await page.getByRole("button", { name: "朗读文章", exact: true }).click();
  await expect(page.locator(".nextflux-speech-bar")).toContainText("正在准备");
  await page.getByRole("button", { name: "停止朗读", exact: true }).click();
  await expectSpeechStopped(page);
  expect(await page.evaluate(() => window.speechNet.cancelled)).toBeGreaterThan(0);

  await page.getByRole("button", { name: "朗读文章", exact: true }).click();
  await page.getByRole("button", { name: "停止语音生成", exact: true }).click();
  await expectSpeechStopped(page);
});

test("read-aloud stops while it is playing and the page stays interactive", async ({ page }) => {
  await installSpeech(page, { holdRead: false });
  await page.getByRole("button", { name: "朗读文章", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.controlsFixture.speech.phase()), { timeout: 15_000 }).toBe("playing");
  await expect(page.getByRole("button", { name: "暂停朗读", exact: true }).first()).toBeVisible();
  await page.getByRole("button", { name: "停止朗读", exact: true }).click();
  await expectSpeechStopped(page);
  expect(await page.evaluate(() => [...window.speechAudios].every((audio) => !audio.getAttribute("src")))).toBe(true);
});

// Overlay janitor: a layer left over the page after its close path broke is
// released, a stale inert root is restored, and a legitimately open sheet is
// never touched.
test("the overlay janitor releases a stranded layer and a stale inert root", async ({ page }) => {
  const warnings = [];
  page.on("console", (message) => { if (message.type() === "warning") warnings.push(message.text()); });
  await open(page, 393);
  await page.evaluate(() => {
    const layer = document.createElement("div");
    layer.className = "stranded-fixture-layer";
    layer.style.cssText = "position:fixed;inset:0;z-index:9999;background:transparent";
    document.body.append(layer);
    document.getElementById("root").inert = true;
  });
  // The symptom: a tap lands on the stranded layer and nothing happens.
  await page.mouse.click(200, 400);
  await expect.poll(() => page.evaluate(() => ({
    released: document.querySelector(".stranded-fixture-layer")?.dataset.nextfluxReleased ?? null,
    inert: document.getElementById("root").inert,
  }))).toEqual({ released: "true", inert: false });
  expect(warnings.join("\n")).toContain("released a stranded overlay");
  await expectUncovered(page);
  await expectPageInteractive(page);

  // A stale inert root alone is restored on the next route change.
  await page.evaluate(() => { document.getElementById("root").inert = true; location.hash = "#/feed/1/article/42?janitor=1"; });
  await expect.poll(() => page.evaluate(() => document.getElementById("root").inert)).toBe(false);
});

test("the overlay janitor leaves an open long-press menu and its scrim alone", async ({ page }) => {
  await open(page, 393);
  // Same portal shape as ContextMenu.jsx: a full-screen scrim beside the menu.
  await page.evaluate(() => {
    const scrim = document.createElement("div");
    scrim.className = "nextflux-context-scrim";
    scrim.style.cssText = "position:fixed;inset:0;z-index:9998";
    scrim.addEventListener("click", () => { scrim.remove(); document.querySelector("body > .nextflux-context-menu")?.remove(); });
    const menu = document.createElement("div");
    menu.className = "nextflux-context-menu";
    menu.style.cssText = "position:fixed;left:20px;top:200px;width:220px;height:120px;z-index:9999";
    menu.append(Object.assign(document.createElement("button"), { textContent: "标为已读" }));
    document.body.append(scrim, menu);
  });
  expect(await page.evaluate(() => window.controlsOverlayJanitor.check())).toEqual([]);
  await page.waitForTimeout(3000);
  expect(await page.evaluate(() => document.querySelector("[data-nextflux-released]"))).toBe(null);
  await page.mouse.click(300, 600); // a tap outside still closes the menu
  await expect(page.locator(".nextflux-context-scrim")).toHaveCount(0);
});

test("a leftover empty long-press menu no longer shields a stranded scrim", async ({ page }) => {
  await open(page, 393);
  await page.evaluate(() => {
    const scrim = document.createElement("div");
    scrim.className = "nextflux-context-scrim";
    scrim.style.cssText = "position:fixed;inset:0;z-index:9998";
    const menu = document.createElement("div");
    menu.className = "nextflux-context-menu";
    // Like the real portal emptied: it keeps its min width and padding.
    menu.style.cssText = "position:fixed;left:20px;top:200px;min-width:220px;padding:6px";
    document.body.append(scrim, menu);
  });
  const released = await page.evaluate(() => window.controlsOverlayJanitor.check());
  expect(released.join(" ")).toContain("nextflux-context-scrim");
  await expectUncovered(page);
});

test("the overlay janitor never touches an open sheet", async ({ page }) => {
  await open(page, 393);
  await page.getByRole("button", { name: "更多", exact: true }).click();
  const sheet = page.getByRole("dialog");
  await expect(sheet).toBeVisible();
  await page.mouse.click(200, 40); // a tap on the live backdrop closes the sheet
  await expect(sheet).toHaveCount(0);
  await page.getByRole("button", { name: "更多", exact: true }).click();
  await expect(sheet).toBeVisible();
  expect(await page.evaluate(() => window.controlsOverlayJanitor.check())).toEqual([]);
  await page.waitForTimeout(1500);
  await expect(sheet).toBeVisible();
  expect(await page.evaluate(() => document.querySelector("[data-nextflux-released]"))).toBe(null);
  await sheet.getByRole("button", { name: "全文适配", exact: true }).click();
  await page.mouse.move(0, 0); // no hover tooltip on the dock below (see openFromMore)
  await expect(page.getByRole("dialog").filter({ hasText: "AI 全文适配" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expectUncovered(page);
});

