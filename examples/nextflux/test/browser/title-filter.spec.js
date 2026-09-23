import { test as base, expect } from "@playwright/test";

// Browser plugin not available in Actions; run the existing Playwright setup.
// Unlike prior incremental-package harnesses, this imports production sources
// through Vite and does not depend on any minified symbol or hashed filename.
const test = base.extend({ page: async ({ page }, use) => {
  const errors = [], external = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => { if (message.type() === "error") errors.push(message.text()); });
  await page.route("**/*", async route => {
    if (new URL(route.request().url()).origin === "http://127.0.0.1:4175") await route.continue();
    else { external.push(route.request().url()); await route.abort(); }
  });
  await use(page);
  expect(errors).toEqual([]);
  expect(external).toEqual([]);
} });
const open = async (page, route = "/category/17/article/900") => {
  await page.goto(`/title-filter.html#${route}`);
  await page.waitForFunction(() => Boolean(window.filterFixture));
  await expect(page).toHaveTitle("NextFlux title filter source regression");
  await expect(page.getByRole("heading", { name: "真实源码过滤入口" })).toBeVisible();
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
};
const editor = async page => {
  await page.getByRole("button", { name: "标题过滤", exact: true }).click();
  await expect(page.getByLabel("屏蔽关键词", { exact: true })).toBeVisible();
  return page.getByRole("dialog");
};
const save = page => page.getByRole("dialog").getByRole("button", { name: "保存", exact: true }).click();
const managed = words => `EntryTitle=(?i)(?P<nextflux_title_keywords>${words})`;

test("list and reader share server rules; clear removes only the managed line", async ({ page }) => {
  await open(page, "/feed/42");
  const original = await page.evaluate(() => structuredClone(filterFixture.remote[42]));
  const dialog = await editor(page);
  await expect(page.getByLabel("屏蔽关键词", { exact: true })).toHaveValue("");
  await dialog.locator("summary").click();
  await expect(dialog.locator("[data-rule-field]")).toHaveCount(4);
  await expect(dialog.locator('[data-rule-field="blocklist_rules"] pre')).toHaveText(original.blocklist_rules);
  await page.getByLabel("屏蔽关键词", { exact: true }).fill("广告|C++");
  await save(page);
  await expect(page.locator(".nf-title-filter-toast")).toContainText("已保存 2");
  await page.evaluate(() => filterFixture.go("/category/17/article/900"));
  await editor(page);
  await expect(page.getByLabel("屏蔽关键词", { exact: true })).toHaveValue("广告|C++");
  await page.getByRole("dialog").getByRole("button", { name: "清空", exact: true }).click();
  await save(page);
  await expect.poll(() => page.evaluate(() => filterFixture.remote[42])).toEqual(original);
  const writes = await page.evaluate(() => filterFixture.calls.filter(call => call.method === "PUT"));
  expect(writes).toHaveLength(2);
  for (const write of writes) { expect(write.id).toBe(42); expect(Object.keys(JSON.parse(write.body))).toEqual(["block_filter_entry_rules"]); }
});

for (const [width, dark, scale] of [[320, false, 1], [393, false, 1], [393, true, 1.5], [820, false, 1]]) {
  test(`reader toast escapes blurred toolbar ${width} dark=${dark} scale=${scale}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 850 });
    await open(page);
    await page.evaluate(({ dark, scale }) => {
      document.documentElement.dataset.theme = dark ? "dark" : "light";
      document.documentElement.classList.toggle("dark", dark);
      document.documentElement.style.fontSize = `${16 * scale}px`;
    }, { dark, scale });
    await expect(page.locator(".nf-title-filter-trigger")).toHaveAttribute("data-feed-id", "42");
    await editor(page);
    await page.getByLabel("屏蔽关键词", { exact: true }).fill("广告|抽奖");
    await save(page);
    const notice = page.locator(".nf-title-filter-toast");
    await expect(notice).toContainText("已保存 2");
    const geometry = await notice.evaluate(el => { const r = el.getBoundingClientRect(); return {
      root: el.parentNode === document.body, top: r.top, bottom: r.bottom, left: r.left, right: r.right,
      pointer: getComputedStyle(el).pointerEvents, viewport: innerHeight, overflow: document.documentElement.scrollWidth > innerWidth + 1,
    }; });
    expect(geometry.root).toBe(true); expect(geometry.pointer).toBe("none");
    expect(geometry.top).toBeGreaterThan(425); expect(geometry.bottom).toBeLessThanOrEqual(geometry.viewport - 20);
    expect(geometry.left).toBeGreaterThanOrEqual(0); expect(geometry.right).toBeLessThanOrEqual(width); expect(geometry.overflow).toBe(false);
    if (width === 393 && !dark) await info.attach("title-filter-bottom-toast.png", { body: await page.screenshot(), contentType: "image/png" });
  });
}

test("late reader request cannot show an old editor or write another source", async ({ page }) => {
  await open(page);
  await page.evaluate(() => filterFixture.mode("slowRead"));
  await page.getByRole("button", { name: "标题过滤", exact: true }).click();
  await expect.poll(() => page.evaluate(() => filterFixture.calls.length)).toBe(1);
  await page.evaluate(() => filterFixture.go("/article/901", { id: 901, feedId: 43, title: "另一篇", status: "read", starred: 1 }));
  await page.evaluate(() => filterFixture.release());
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.locator(".nf-title-filter-trigger")).toHaveAttribute("data-feed-id", "43");
  await editor(page);
  await expect(page.getByRole("dialog").locator(".nf-title-filter-feed")).toHaveText("另一条 RSS");
  expect(await page.evaluate(() => filterFixture.calls.filter(call => call.method === "PUT"))).toEqual([]);
});

test("cancel and denied reads never save or claim success", async ({ page }) => {
  await open(page); await editor(page);
  await page.getByLabel("屏蔽关键词", { exact: true }).fill("unsaved");
  await page.getByRole("dialog").getByRole("button", { name: "取消", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.evaluate(() => filterFixture.mode("denied"));
  await page.getByRole("button", { name: "标题过滤", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("服务器拒绝访问");
  await expect(page.getByRole("dialog").getByRole("button", { name: "保存", exact: true })).toBeDisabled();
  expect(await page.evaluate(() => filterFixture.calls.filter(call => call.method === "PUT"))).toEqual([]);
  await expect(page.locator(".nf-title-filter-toast")).toHaveCount(0);
});

test("conflicting remote keyword edits retain input and require reload", async ({ page }) => {
  await open(page); await editor(page);
  await page.getByLabel("屏蔽关键词", { exact: true }).fill("mine");
  await page.evaluate(rule => { filterFixture.remote[42].block_filter_entry_rules += "\n" + rule; }, managed("other"));
  await save(page);
  await expect(page.getByRole("alert")).toContainText("关键词已在其他页面修改");
  await expect(page.getByLabel("屏蔽关键词", { exact: true })).toHaveValue("mine");
  await expect(page.getByRole("dialog").getByRole("button", { name: "保存", exact: true })).toBeDisabled();
  expect(await page.evaluate(() => filterFixture.calls.filter(call => call.method === "PUT"))).toEqual([]);
});

test("pending write prevents duplicate save and close", async ({ page }) => {
  await open(page); await editor(page);
  await page.getByLabel("屏蔽关键词", { exact: true }).fill("mine");
  await page.evaluate(() => filterFixture.mode("pauseWrite"));
  await save(page);
  await expect.poll(() => page.evaluate(() => filterFixture.calls.filter(call => call.method === "PUT").length)).toBe(1);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByRole("dialog").getByRole("button", { name: "取消", exact: true })).toBeDisabled();
  await page.evaluate(() => filterFixture.release());
  await expect(page.locator(".nf-title-filter-toast")).toContainText("已保存 1");
  expect(await page.evaluate(() => filterFixture.calls.filter(call => call.method === "PUT").length)).toBe(1);
});
