import { test as base, expect } from "@playwright/test";

// Real HeroUI/React Aria buttons with the production touch compatibility layer.
// Some phones deliver a tap as touchstart → touchend → click with no pointer
// events; usePress then ignores the click. Each tap must still press once.
const test = base.extend({ page: async ({ page }, use) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });
  await use(page);
  expect(errors).toEqual([]);
} });

const POINTER_EVENTS = ["pointerdown", "pointerup", "pointermove", "pointercancel", "pointerover", "pointerout", "pointerenter", "pointerleave", "gotpointercapture", "lostpointercapture"];

async function open(page, { withoutPointerEvents = false } = {}) {
  if (withoutPointerEvents) {
    // Registered before the app, so neither the compatibility layer nor React sees them.
    await page.addInitScript((types) => {
      window.blockPointerEvents = true;
      for (const type of types) {
        window.addEventListener(type, (event) => { if (window.blockPointerEvents) event.stopImmediatePropagation(); }, { capture: true });
      }
    }, POINTER_EVENTS);
  }
  await page.goto("/touch-press.html");
  await expect(page.getByRole("button", { name: "更多" })).toBeVisible();
}
const counts = (page) => page.evaluate(() => ({ ...window.pressCounts }));
async function tap(page, name, { settle = 150 } = {}) {
  const box = await page.getByRole("button", { name }).boundingBox();
  await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
  if (settle) await page.waitForTimeout(settle);
}

test("a tap without pointer events presses once", async ({ page }) => {
  await open(page, { withoutPointerEvents: true });
  await tap(page, "更多");
  await expect.poll(() => counts(page)).toEqual({ more: 1, close: 0, disabled: 0 });
  await tap(page, "关闭");
  await tap(page, "更多");
  await expect.poll(() => counts(page)).toEqual({ more: 2, close: 1, disabled: 0 });
});

test("a tap without pointer events right after a normal tap still presses", async ({ page }) => {
  await open(page, { withoutPointerEvents: true });
  await page.evaluate(() => { window.blockPointerEvents = false; });
  await tap(page, "更多", { settle: 0 });
  await page.evaluate(() => { window.blockPointerEvents = true; });
  await tap(page, "关闭");
  await expect.poll(() => counts(page)).toEqual({ more: 1, close: 1, disabled: 0 });
});

test("a normal tap presses once", async ({ page }) => {
  await open(page);
  await tap(page, "更多");
  await tap(page, "关闭");
  await expect.poll(() => counts(page)).toEqual({ more: 1, close: 1, disabled: 0 });
});

test("a disabled button never presses, with or without pointer events", async ({ page }) => {
  await open(page, { withoutPointerEvents: true });
  await tap(page, "不可用");
  await page.goto("about:blank");
  await open(page);
  await tap(page, "不可用");
  expect(await counts(page)).toEqual({ more: 0, close: 0, disabled: 0 });
});

test("mouse and keyboard keep their own path", async ({ page }) => {
  await open(page);
  await page.getByRole("button", { name: "更多" }).click();
  await page.getByRole("button", { name: "关闭" }).focus();
  await page.keyboard.press("Enter");
  await page.keyboard.press("Space");
  await expect.poll(() => counts(page)).toEqual({ more: 1, close: 2, disabled: 0 });
});
