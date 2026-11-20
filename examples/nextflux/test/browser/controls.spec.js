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

async function openSummary(page) {
  await page.getByRole("button", { name: "AI 摘要", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "AI 摘要", exact: true });
  await expect(dialog).toBeVisible();
  // Measure the settled surface, not HeroUI's short entrance zoom.
  await expect(page.locator('.nextflux-summary-backdrop[data-entering="true"], .nextflux-summary-container[data-entering="true"]')).toHaveCount(0);
  return dialog;
}

async function closeSummary(page) {
  await page.getByRole("dialog", { name: "AI 摘要", exact: true }).getByRole("button", { name: "关闭 AI 摘要", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "AI 摘要", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "AI 摘要", exact: true })).toBeFocused();
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
    await closeGeometry(page.locator(".action-buttons .nextflux-close-button"), 44);
    await bounds(page);
    await page.getByRole("button", { name: "更多", exact: true }).click();
    await expect(page.getByRole("dialog").getByRole("button", { name: "全文适配", exact: true })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.locator("main .ai-summary")).toHaveCount(0);
    const summary = await openSummary(page);
    await expect(summary.locator(".nextflux-summary-text")).toHaveText("Fixture summary.");
    const summaryClose = summary.getByRole("button", { name: "关闭 AI 摘要", exact: true });
    await expect(summaryClose).toBeVisible();
    const close = await summaryClose.boundingBox();
    expect(close.width).toBeGreaterThanOrEqual(32);
    expect(close.height).toBeGreaterThanOrEqual(32);
    expect(close.x).toBeGreaterThanOrEqual(0);
    expect(close.x + close.width).toBeLessThanOrEqual(width + 1);
    if (width === 393 && scale === 1) await info.attach(`toolbar-${theme}.png`, {
      body: await page.screenshot(), contentType: "image/png",
    });
    await closeSummary(page);
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
  await expect(page.getByRole("button", { name: "Stop waiting for full text", exact: true })).toBeEnabled();
  expect((await page.locator(".action-buttons").boundingBox()).height).toBe(before.height);
  await bounds(page);
});

test("toolbar actions work and closing an AI summary preserves its cached result", async ({ page }) => {
  await open(page);
  await readerAction(page, "Unread");
  await page.getByRole("button", { name: "Share", exact: true }).click();
  expect(await page.evaluate(() => window.controlsFixture.calls.map(([name]) => name))).toEqual(["status", "share"]);
  const before = await page.getByTestId("reader-body").boundingBox();
  const summary = await openSummary(page);
  await expect(summary.locator(".nextflux-summary-text")).toHaveText("Fixture summary.");
  const after = await page.getByTestId("reader-body").boundingBox();
  expect(after.y).toBeCloseTo(before.y, 0);
  expect(after.height).toBeCloseTo(before.height, 0);
  await closeSummary(page);
  expect(await page.evaluate(() => window.controlsFixture.summary.state().summary)).toBe("Fixture summary.");
  await openSummary(page);
  await expect(summary.locator(".nextflux-summary-text")).toHaveText("Fixture summary.");
  expect(await page.evaluate(() => window.controlsFixture.summary.requests())).toEqual([]);
  await closeSummary(page);
});

for (const method of ["close button", "backdrop", "system back"]) {
  test(`AI summary clears its entire overlay on ${method} even if the WebView pauses animations`, async ({ page }) => {
    await open(page, 393, "dark");
    const trigger = page.getByRole("button", { name: "AI 摘要", exact: true });
    await trigger.tap();
    const dialog = page.getByRole("dialog", { name: "AI 摘要" });
    await expect(dialog.locator(".nextflux-summary-text")).toHaveText("Fixture summary.");
    await expect(page.locator(".nextflux-summary-container[data-entering]")).toHaveCount(0);
    await page.addStyleTag({ content: `
      :is(.nextflux-summary-backdrop, .nextflux-summary-container)[data-exiting] {
        animation-play-state: paused !important;
      }` });
    // Inspect physical portal elements, including visually present aria-hidden nodes.
    await page.evaluate(() => {
      const button = document.querySelector('.reader-dock [aria-label="AI 摘要"]');
      window.firstClosedFrame = new Promise(resolve => {
        function sample() {
          if (button.getAttribute("aria-expanded") !== "false") return requestAnimationFrame(sample);
          const layers = document.querySelectorAll(".nextflux-summary-backdrop, .nextflux-summary-container, .nextflux-summary-dialog");
          resolve(Array.from(layers, layer => layer.dataset.slot));
        }
        requestAnimationFrame(sample);
      });
    });
    if (method === "close button") await dialog.getByRole("button", { name: "关闭 AI 摘要" }).tap();
    if (method === "backdrop") await page.touchscreen.tap(5, 5);
    if (method === "system back") {
      expect(await page.evaluate(() => !window.dispatchEvent(new Event("toolboxback", { cancelable: true })))).toBe(true);
    }
    expect(await page.evaluate(() => window.firstClosedFrame)).toEqual([]);
    await expect(trigger).toBeFocused();
    await expect(page.getByRole("tooltip")).toHaveCount(0);
    await trigger.tap();
    await expect(dialog.locator(".nextflux-summary-text")).toHaveText("Fixture summary.");
    expect(await page.evaluate(() => window.controlsFixture.summary.requests())).toEqual([]);
  });
}

test("system back closes the AI summary in one press with its regenerate tooltip open", async ({ page }) => {
  await open(page);
  // Open as on the phone. A mouse click leaves a hovering pointer on the
  // toolbar button, which legitimately shows its desktop hover tooltip.
  const trigger = page.getByRole("button", { name: "AI 摘要", exact: true });
  await trigger.tap();
  const dialog = page.getByRole("dialog", { name: "AI 摘要" });
  await expect(dialog.locator(".nextflux-summary-text")).toHaveText("Fixture summary.");
  await expect(page.locator(".nextflux-summary-container[data-entering]")).toHaveCount(0);
  await dialog.getByRole("button", { name: "关闭 AI 摘要" }).focus();
  await page.keyboard.press("Shift+Tab");
  await expect(page.getByRole("tooltip", { name: "重新生成" })).toBeVisible();
  expect(await page.evaluate(() => !window.dispatchEvent(new Event("toolboxback", { cancelable: true })))).toBe(true);
  await expect(page.locator(".nextflux-summary-backdrop")).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect(page.getByRole("tooltip")).toHaveCount(0);
  await expect(page.locator("html")).toHaveAttribute("data-nf-input", "touch");
  await expect(page.getByTestId("route")).toHaveText("/feed/1/article/42");
});

test("system back after touch returns to an unadorned AI summary button; keyboard focus remains visible", async ({ page }) => {
  await open(page, 393, "dark");
  const trigger = page.getByRole("button", { name: "AI 摘要", exact: true });
  const dialog = page.getByRole("dialog", { name: "AI 摘要" });
  await trigger.tap();
  await expect(dialog.locator(".nextflux-summary-text")).toHaveText("Fixture summary.");
  await expect(page.locator('.nextflux-summary-container[data-entering]')).toHaveCount(0);
  expect(await page.evaluate(() => !window.dispatchEvent(new Event("toolboxback", { cancelable: true })))).toBe(true);
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect(page.getByRole("tooltip")).toHaveCount(0);
  expect(await page.locator("html").getAttribute("data-nf-input")).toBe("touch");
  const ringPainted = () => trigger.evaluate(el => getComputedStyle(el).outlineStyle !== "none" ||
    /rgb\(|rgba\([^)]*, 1\)/.test(getComputedStyle(el).boxShadow));
  expect(await ringPainted()).toBe(false);
  await page.keyboard.press("Enter");
  await expect(dialog).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect.poll(ringPainted).toBe(true);
});

test("AI summary close, Escape, backdrop and system back stop the stream and retain received text", async ({ page }) => {
  await open(page);
  await page.evaluate(() => window.controlsFixture.summary.hold(true));
  for (const method of ["close", "escape", "backdrop", "system back"]) {
    const index = await page.evaluate(() => {
      window.controlsFixture.summary.clear();
      return window.controlsFixture.summary.requests().length;
    });
    const dialog = await openSummary(page);
    const text = `已收到的摘要：${method}`;
    await page.evaluate(({ index, text }) => window.controlsFixture.summary.chunk(index, text), { index, text });
    await expect(dialog.locator(".nextflux-summary-text")).toHaveText(text);
    if (method === "close") await closeSummary(page);
    else if (method === "escape") await page.keyboard.press("Escape");
    else if (method === "backdrop") await page.mouse.click(5, 5);
    else expect(await page.evaluate(() => {
      const event = new Event("toolboxback", { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    })).toBe(true);
    await expect(dialog).toHaveCount(0);
    await expect(page.getByTestId("route")).toHaveText("/feed/1/article/42");
    expect(await page.evaluate((index) => window.controlsFixture.summary.requests()[index].aborted, index)).toBe(true);
    expect(await page.evaluate(() => window.controlsFixture.summary.state())).toEqual({ loading: false, summary: text, error: null });
    await openSummary(page);
    await expect(dialog.locator(".nextflux-summary-text")).toHaveText(text);
    expect(await page.evaluate(() => window.controlsFixture.summary.requests().length)).toBe(index + 1);
    await closeSummary(page);
  }
  await expectUncovered(page);
});

test("AI summary stop keeps partial text and explicit regeneration can recover from an error", async ({ page }) => {
  await open(page);
  await page.evaluate(() => { window.controlsFixture.summary.clear(); window.controlsFixture.summary.hold(true); });
  const dialog = await openSummary(page);
  await page.evaluate(() => window.controlsFixture.summary.chunk(0, "已经生成的第一部分。"));
  await expect(dialog.locator(".nextflux-summary-text")).toHaveText("已经生成的第一部分。");
  await dialog.getByRole("button", { name: "停止生成", exact: true }).click();
  await expect(dialog).toBeVisible();
  await expect(dialog.locator(".nextflux-summary-text")).toHaveText("已经生成的第一部分。");
  expect(await page.evaluate(() => window.controlsFixture.summary.requests()[0].aborted)).toBe(true);
  await dialog.getByRole("button", { name: "重新生成", exact: true }).click();
  expect(await page.evaluate(() => window.controlsFixture.summary.requests().length)).toBe(2);
  await expect(dialog.locator(".nextflux-summary-text")).toHaveCount(0);
  await page.evaluate(() => window.controlsFixture.summary.fail(1, "Synthetic summary failure"));
  await expect(dialog).toContainText("Synthetic summary failure");
  await dialog.getByRole("button", { name: /^(重试|生成摘要)$/ }).click();
  expect(await page.evaluate(() => window.controlsFixture.summary.requests().length)).toBe(3);
  await page.evaluate(() => {
    window.controlsFixture.summary.chunk(2, "重新生成后的完整摘要。");
    window.controlsFixture.summary.done(2);
  });
  await expect(dialog.locator(".nextflux-summary-text")).toHaveText("重新生成后的完整摘要。");
  await expect(dialog).not.toContainText("Synthetic summary failure");
  await closeSummary(page);
});

test("AI summary switches articles without reopening or accepting a cancelled run's late callbacks", async ({ page }) => {
  await open(page);
  await page.evaluate(() => {
    window.controlsFixture.summary.clear();
    window.controlsFixture.summary.hold(true);
    window.controlsFixture.summary.set(43, { loading: false, summary: "另一篇文章已有的摘要。", error: null });
  });
  const dialog = await openSummary(page);
  await page.evaluate(() => window.controlsFixture.summary.chunk(0, "旧请求的内容。"));
  await expect(dialog.locator(".nextflux-summary-text")).toHaveText("旧请求的内容。");
  await page.evaluate(() => window.controlsFixture.setArticle(43));
  await expect(page.getByTestId("route")).toHaveText("/feed/1/article/43");
  await expect(dialog).toHaveCount(0);
  expect(await page.evaluate(() => window.controlsFixture.summary.state(42))).toBeNull();
  expect(await page.evaluate(() => window.controlsFixture.summary.requests()[0].aborted)).toBe(true);
  await page.evaluate(() => window.controlsFixture.setArticle(42));
  await expect(page.getByTestId("route")).toHaveText("/feed/1/article/42");
  await expect(dialog).toHaveCount(0);
  await openSummary(page);
  expect(await page.evaluate(() => window.controlsFixture.summary.requests().length)).toBe(2);
  await page.evaluate(() => {
    window.controlsFixture.summary.chunk(1, "新请求的内容。");
    window.controlsFixture.summary.chunk(0, "不应显示的晚到文本。");
    window.controlsFixture.summary.done(0);
    window.controlsFixture.summary.fail(0, "Late cancelled failure");
  });
  await expect(dialog.locator(".nextflux-summary-text")).toHaveText("新请求的内容。");
  expect(await page.evaluate(() => window.controlsFixture.summary.state())).toEqual({ loading: true, summary: "新请求的内容。", error: null });
  await page.evaluate(() => window.controlsFixture.summary.done(1));
  await expect(dialog.getByRole("button", { name: "重新生成", exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.controlsFixture.summary.state(43).summary)).toBe("另一篇文章已有的摘要。");
  await closeSummary(page);
});

test("AI summary Markdown renders cached formatting and reopens without another AI request", async ({ page }, info) => {
  await open(page, 393, "dark");
  const markdown = [
    "# 今日摘要", "", "**核心结论**与*补充说明*。", "", "## 主要进展", "",
    "- 第一项进展", "- 第二项进展", "", "### 影响", "", "1. 第一项影响", "2. 第二项影响", "",
    "#### 背景", "", "> 需要保留的引用。", "", "##### 示例", "", "使用 `cache` 保存结果。", "",
    "```js", "const value = 42;", "```", "", "###### 注意", "", "~~已过时的说法~~", "",
    "| 项目 | 结果 |", "| --- | --- |", "| 摘要 | 已完成 |",
  ].join("\n");
  await page.evaluate((summary) => window.controlsFixture.summary.set(42, { loading: false, summary, error: null }), markdown);
  const dialog = await openSummary(page);
  const content = dialog.locator(".nextflux-summary-text");
  for (const [index, title] of ["今日摘要", "主要进展", "影响", "背景", "示例", "注意"].entries()) {
    await expect(content.getByRole("heading", { level: index + 1 })).toHaveText(title);
  }
  await expect(content.locator("strong")).toHaveText("核心结论");
  await expect(content.locator("em")).toHaveText("补充说明");
  await expect(content.locator("ul > li")).toHaveText(["第一项进展", "第二项进展"]);
  await expect(content.locator("ol > li")).toHaveText(["第一项影响", "第二项影响"]);
  await expect(content.locator("blockquote")).toHaveText("需要保留的引用。");
  await expect(content.locator("p code")).toHaveText("cache");
  await expect(content.locator("pre code")).toContainText("const value = 42;");
  await expect(content.locator("del")).toHaveText("已过时的说法");
  await expect(content.getByRole("table").getByRole("columnheader")).toHaveText(["项目", "结果"]);
  await expect(content.getByRole("table").getByRole("cell")).toHaveText(["摘要", "已完成"]);
  await info.attach("ai-summary-markdown-dark.png", { body: await page.screenshot(), contentType: "image/png" });
  await closeSummary(page);
  expect(await page.evaluate(() => window.controlsFixture.summary.state().summary)).toBe(markdown);
  await openSummary(page);
  await expect(content.getByRole("heading", { level: 1 })).toHaveText("今日摘要");
  await expect(content.locator("strong")).toHaveText("核心结论");
  expect(await page.evaluate(() => window.controlsFixture.summary.requests())).toEqual([]);
  await closeSummary(page);
});

test("AI summary Markdown bold renders cached Chinese quotes with padded closing markers", async ({ page }, info) => {
  await open(page, 393, "light");
  const first = "疗程完成度不等于因果证据。";
  const second = "PEACE-3 回答的是“在恩扎卢胺基础上加用 Ra-223 有何收益”，不是“Ra-223 应排在化疗之前还是之后”。";
  const markdown = [
    `> **${first} **能完成 ≥5 个周期的患者，往往本就有更好的体能、骨髓储备，且必须存活到后续给药时间点。真实世界数据支持尽早评估治疗窗口，但不能单凭这些关联断定前移用药或增加周期本身带来了全部生存差异。`,
    ">",
    `> **${second} **对既往 ARPi 治疗后进展的患者，不能把联合方案的生存增益直接外推为优先使用 Ra-223 的依据。`,
    "", "中文**“相邻标点”**也应正常加粗。",
  ].join("\n");
  await page.evaluate((summary) => window.controlsFixture.summary.set(42, { loading: false, summary, error: null }), markdown);
  const dialog = await openSummary(page);
  const content = dialog.locator(".nextflux-summary-text");
  await expect(content.locator("blockquote strong")).toHaveText([first, second]);
  await expect(content.locator("strong")).toHaveText([first, second, "“相邻标点”"]);
  await expect(content).not.toContainText("**");
  const weights = await content.locator("strong").evaluateAll((elements) => elements.map((element) => Number(getComputedStyle(element).fontWeight)));
  expect(weights.every((weight) => weight >= 600), "parsed bold text must also have a visibly bold font weight").toBe(true);
  await info.attach("ai-summary-bold-chinese-393-light.png", { body: await page.screenshot(), contentType: "image/png" });
  await closeSummary(page);
  await openSummary(page);
  await expect(content.locator("blockquote strong")).toHaveText([first, second]);
  expect(await page.evaluate(() => window.controlsFixture.summary.state().summary)).toBe(markdown);
  expect(await page.evaluate(() => window.controlsFixture.summary.requests())).toEqual([]);
  await closeSummary(page);
});

test("AI summary Markdown bold completes streamed closing markers without changing code or escaped stars", async ({ page }) => {
  await open(page, 393, "dark");
  await page.evaluate(() => { window.controlsFixture.summary.clear(); window.controlsFixture.summary.hold(true); });
  const dialog = await openSummary(page);
  const content = dialog.locator(".nextflux-summary-text");
  const first = "> **流式结论。 *";
  const second = [
    "*后续说明。", "", "`**代码里的星号。 **`", "",
    "```md", "**代码块里的星号。 **", "```", "", String.raw`\*\*转义星号。 \*\*`,
  ].join("\n");
  await page.evaluate((text) => window.controlsFixture.summary.chunk(0, text), first);
  await expect(content).toContainText("流式结论。");
  await expect(content.locator("strong")).toHaveCount(0);
  await page.evaluate((text) => {
    window.controlsFixture.summary.chunk(0, text);
    window.controlsFixture.summary.done(0);
  }, second);
  await expect(content.locator("strong")).toHaveText("流式结论。");
  await expect(content.locator("blockquote")).toContainText("后续说明。");
  await expect(content.locator("blockquote")).not.toContainText("**");
  await expect(content.locator("p code")).toHaveText("**代码里的星号。 **");
  await expect(content.locator("pre code")).toHaveText("**代码块里的星号。 **\n");
  await expect(content).toContainText("**转义星号。 **");
  await expect(dialog.getByRole("button", { name: "重新生成", exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.controlsFixture.summary.state().summary)).toBe(first + second);
  expect(await page.evaluate(() => window.controlsFixture.summary.requests().length)).toBe(1);
  await closeSummary(page);
});

test("AI summary Markdown handles split syntax, stopped partial output and a completed stream", async ({ page }) => {
  await open(page);
  await page.evaluate(() => { window.controlsFixture.summary.clear(); window.controlsFixture.summary.hold(true); });
  const dialog = await openSummary(page);
  const content = dialog.locator(".nextflux-summary-text");
  const first = "## 分析要点\n\n**关键";
  const second = "结论**：保持耐心。\n\n- 第一项\n- 第二项\n\n```js\nconst answer = 42;";
  await page.evaluate((text) => window.controlsFixture.summary.chunk(0, text), first);
  await expect(content.getByRole("heading", { level: 2 })).toHaveText("分析要点");
  await expect(content).toContainText("关键");
  await page.evaluate((text) => window.controlsFixture.summary.chunk(0, text), second);
  await expect(content.locator("strong")).toHaveText("关键结论");
  await expect(content.locator("ul > li")).toHaveText(["第一项", "第二项"]);
  await expect(content.locator("pre code")).toContainText("const answer = 42;");
  await dialog.getByRole("button", { name: "停止生成", exact: true }).click();
  expect(await page.evaluate(() => window.controlsFixture.summary.state())).toEqual({ loading: false, summary: first + second, error: null });
  await closeSummary(page);
  await openSummary(page);
  await expect(content.locator("strong")).toHaveText("关键结论");
  await expect(content.locator("pre code")).toContainText("const answer = 42;");
  expect(await page.evaluate(() => window.controlsFixture.summary.requests().length)).toBe(1);
  await dialog.getByRole("button", { name: "重新生成", exact: true }).click();
  await expect(content).toHaveCount(0);
  await page.evaluate(() => window.controlsFixture.summary.chunk(1, "# 完成结果\n\n**完"));
  await expect(content.getByRole("heading", { level: 1 })).toHaveText("完成结果");
  await page.evaluate(() => {
    window.controlsFixture.summary.chunk(1, "整结论**\n\n> 最终引用\n\n```js\nconst answer = 43;\n```");
    window.controlsFixture.summary.done(1);
  });
  await expect(content.locator("strong")).toHaveText("完整结论");
  await expect(content.locator("blockquote")).toHaveText("最终引用");
  await expect(content.locator("pre code")).toContainText("const answer = 43;");
  await expect(dialog.getByRole("button", { name: "重新生成", exact: true })).toBeVisible();
  await closeSummary(page);
});

test("AI summary Markdown keeps wide tables and code locally scrollable on a 320px large-text phone", async ({ page }, info) => {
  await open(page, 320, "light", 1.5);
  const wide = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(6);
  const markdown = [
    "## 代码与表格", "", "```js", `const value = \"${wide}\";`, "```", "",
    "| 项目 | 当前内容 | 下一步 | 备注 |", "| --- | --- | --- | --- |",
    `| 原始记录 | ${wide} | ${wide} | ${wide} |`,
  ].join("\n");
  await page.evaluate((summary) => window.controlsFixture.summary.set(42, { loading: false, summary, error: null }), markdown);
  const dialog = await openSummary(page);
  const content = dialog.locator(".nextflux-summary-text");
  for (const selector of ["pre", "table"]) {
    const scroll = await content.locator(selector).evaluate((target) => {
      const root = target.closest(".nextflux-summary-text");
      for (let element = target; element && element !== root; element = element.parentElement) {
        if (/(auto|scroll)/.test(getComputedStyle(element).overflowX) && element.scrollWidth > element.clientWidth + 1) {
          element.scrollLeft = element.scrollWidth;
          return { left: element.scrollLeft, width: element.clientWidth, contentWidth: element.scrollWidth };
        }
      }
      return null;
    });
    expect(scroll, `${selector} has its own horizontal scroller`).not.toBeNull();
    expect(scroll.left).toBeGreaterThan(0);
    expect(scroll.contentWidth).toBeGreaterThan(scroll.width);
  }
  const dimensions = await dialog.evaluate((element) => {
    const content = element.querySelector(".nextflux-summary-text");
    const body = element.querySelector(".nextflux-summary-body");
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, contentWidth: content.scrollWidth, contentBox: content.clientWidth, bodyWidth: body.scrollWidth, bodyBox: body.clientWidth };
  });
  expect(dimensions.left).toBeGreaterThanOrEqual(0);
  expect(dimensions.right).toBeLessThanOrEqual(321);
  expect(dimensions.top).toBeGreaterThanOrEqual(0);
  expect(dimensions.bottom).toBeLessThanOrEqual(851);
  expect(dimensions.contentWidth).toBeLessThanOrEqual(dimensions.contentBox + 1);
  expect(dimensions.bodyWidth).toBeLessThanOrEqual(dimensions.bodyBox + 1);
  await expect(dialog.getByRole("button", { name: "关闭 AI 摘要", exact: true })).toBeVisible();
  await info.attach("ai-summary-markdown-320-large-text.png", { body: await page.screenshot(), contentType: "image/png" });
  await closeSummary(page);
});

test("AI summary Markdown treats HTML as text and sends only safe links through ToolBox", async ({ page }) => {
  await open(page);
  await page.evaluate(() => {
    window.summaryScriptExecuted = 0;
    window.summaryOpenedLinks = [];
    window.ToolBox = { ...window.ToolBox, browser: { open: async (url) => { window.summaryOpenedLinks.push(url); } } };
    window.controlsFixture.summary.installLinks();
    window.controlsFixture.summary.set(42, { loading: false, error: null, summary: [
      '<script>window.summaryScriptExecuted = 1</script>',
      '<img src="https://summary.example.invalid/tracker" onerror="window.summaryScriptExecuted = 2">',
      '<iframe src="https://summary.example.invalid/frame"></iframe>',
      '<svg onload="window.summaryScriptExecuted = 3"></svg>',
      '', '[脚本链接](javascript:alert(1))', '', '[数据链接](data:text/html,unsafe)', '',
      '[有效来源](https://summary.example.invalid/report?x=1&y=2#section)', '', '[相对来源](../source?via=summary)',
    ].join("\n") });
  });
  const dialog = await openSummary(page);
  const content = dialog.locator(".nextflux-summary-text");
  await expect(content.locator("script, img, iframe, svg, object, embed, [onclick], [onerror], [onload]")).toHaveCount(0);
  await expect(content).toContainText("<script>");
  await expect(content).toContainText("脚本链接");
  await expect(content.getByRole("link", { name: "脚本链接", exact: true })).toHaveCount(0);
  await expect(content.getByRole("link", { name: "数据链接", exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => window.summaryScriptExecuted)).toBe(0);
  const before = page.url();
  const source = content.getByRole("link", { name: "有效来源", exact: true });
  await expect(source).toHaveAttribute("href", "https://summary.example.invalid/report?x=1&y=2#section");
  await source.click();
  const relative = content.getByRole("link", { name: "相对来源", exact: true });
  await expect(relative).toHaveAttribute("href", "https://example.invalid/source?via=summary");
  await relative.click();
  expect(await page.evaluate(() => window.summaryOpenedLinks)).toEqual([
    "https://summary.example.invalid/report?x=1&y=2#section", "https://example.invalid/source?via=summary",
  ]);
  expect(page.url()).toBe(before);
  await expect(dialog).toBeVisible();
  await closeSummary(page);
});

for (const [width, theme, scale] of [[320, "light", 1.5], [820, "dark", 1]]) {
  test(`AI summary long content scrolls inside its floating panel at ${width}px`, async ({ page }, info) => {
    await open(page, width, theme, scale);
    await page.evaluate(() => {
      window.controlsFixture.summary.setForeground(true);
      window.controlsFixture.summary.set(42, {
        loading: false,
        summary: Array.from({ length: 36 }, (_, index) => `${index + 1}. 这是摘要的重要内容，长段落与较大的字体仍应当在卡片内部滚动，不挤压文章正文。`).join("\n\n"),
        error: null,
      });
    });
    const dialog = await openSummary(page);
    const close = dialog.getByRole("button", { name: "关闭 AI 摘要", exact: true });
    await expect(close).toBeVisible();
    await expect.poll(async () => dialog.evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      return bounds.left >= 0 && bounds.top >= 0 && bounds.right <= innerWidth + 1 && bounds.bottom <= innerHeight + 1;
    })).toBe(true);
    const before = await close.boundingBox();
    const scroll = await dialog.locator(".nextflux-summary-text").evaluate((text) => {
      const dialog = text.closest('[role="dialog"]');
      for (let element = text; element && element !== dialog; element = element.parentElement) {
        if (/(auto|scroll)/.test(getComputedStyle(element).overflowY) && element.scrollHeight > element.clientHeight + 1) {
          element.scrollTop = element.scrollHeight;
          return { top: element.scrollTop, height: element.clientHeight, width: element.clientWidth, contentWidth: element.scrollWidth };
        }
      }
      return null;
    });
    expect(scroll, "summary has its own usable scroll area").not.toBeNull();
    expect(scroll.top).toBeGreaterThan(0);
    expect(scroll.height).toBeGreaterThan(40);
    expect(scroll.contentWidth).toBeLessThanOrEqual(scroll.width + 1);
    for (const foreground of [false, true]) {
      // Let React commit each host foreground update before probing the live
      // element; reading a detached old scroller could hide a remount/reset.
      await page.evaluate(async (value) => {
        window.controlsFixture.summary.setForeground(value);
        await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      }, foreground);
      const text = dialog.locator(".nextflux-summary-text");
      await expect(text).toContainText("这是摘要的重要内容");
      const top = await text.evaluate((text) => {
        const dialog = text.closest('[role="dialog"]');
        for (let element = text; element && element !== dialog; element = element.parentElement) {
          if (/(auto|scroll)/.test(getComputedStyle(element).overflowY) && element.scrollHeight > element.clientHeight + 1) return element.scrollTop;
        }
        return null;
      });
      expect(top, `foreground=${foreground} preserves the summary reading position`).toBe(scroll.top);
    }
    await expect(close).toBeVisible();
    expect((await close.boundingBox()).y).toBeCloseTo(before.y, 0);
    await info.attach(`ai-summary-${width}-${theme}.png`, { body: await page.screenshot(), contentType: "image/png" });
    await closeSummary(page);
    await expectUncovered(page);
  });
}

for (const width of [393, 820]) {
  test(`shared modal and settings close controls ${width}px`, async ({ page }, info) => {
    await open(page, width);
    for (const trigger of ["Open modal", "Open settings"]) {
      await page.getByRole("button", { name: trigger, exact: true }).click();
      const dialog = page.getByRole("dialog");
      const close = dialog.locator(".nextflux-close-button");
      if (trigger === "Open settings") {
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
  const clipped = await alert.locator(".nextflux-modal-footer button").evaluateAll((nodes) => nodes
    .filter((node) => node.scrollWidth > node.clientWidth).map((node) => node.textContent));
  expect(clipped, "no label overflows its button").toEqual([]);
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

test("sheet footers keep 取消 and 保存更改 in one row of equal buttons at 2x text", async ({ page }) => {
  await open(page, 390, "light", 2);
  await page.getByRole("button", { name: "Open sheet with footer", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("button", { name: "保存更改", exact: true })).toBeVisible();
  const boxes = await dialog.locator(".nextflux-modal-footer button").evaluateAll((nodes) => nodes.map((node) => {
    const rect = node.getBoundingClientRect();
    return { top: Math.round(rect.top), width: Math.round(rect.width) };
  }));
  expect(boxes).toHaveLength(2);
  expect(boxes[0].top, "one row").toBe(boxes[1].top);
  expect(Math.abs(boxes[0].width - boxes[1].width), "equal widths").toBeLessThanOrEqual(1);
});

test("alert buttons with short labels stay side by side at 2x text", async ({ page }) => {
  await open(page, 390, "light", 2);
  await page.getByRole("button", { name: "Open alert", exact: true }).click();
  const alert = page.getByRole("alertdialog");
  await alert.getByRole("button", { name: "Cancel", exact: true }).evaluate((node) => { node.lastChild.textContent = "取消"; });
  await alert.getByRole("button", { name: "Confirm", exact: true }).evaluate((node) => { node.lastChild.textContent = "退出"; });
  const tops = await alert.locator(".nextflux-modal-footer button").evaluateAll((nodes) => nodes.map((node) => Math.round(node.getBoundingClientRect().top)));
  expect(tops[0], "both buttons share one row").toBe(tops[1]);
});

test("the alert's more-below shade is dark in dark mode", async ({ page }) => {
  await open(page, 320, "dark", 2);
  await page.getByRole("button", { name: "Open alert", exact: true }).click();
  const body = page.getByRole("alertdialog").locator(".nextflux-alert-body");
  const image = await body.evaluate((node) => getComputedStyle(node, "::after").backgroundImage);
  // The end stop, not the transparent start (which is rgba(0, 0, 0, 0) too).
  expect(image).toMatch(/rgba\(0, 0, 0, 0\.2\d*\)\)$/);
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

async function openCachePreferences(page, width = 393) {
  await page.setViewportSize({ width, height: 850 });
  await page.goto("/controls.html?sidebar=1");
  await page.waitForFunction(() => Boolean(window.controlsSidebarFixture));
  await expect(page).toHaveTitle("NextFlux control regression");
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  const group = page.locator("section.settings-group").filter({ has: page.getByRole("heading", { name: "离线缓存", exact: true }) });
  await expect(group).toBeVisible();
  return group;
}

async function chooseCacheLimit(page, group, value) {
  await group.getByRole("button", { name: /^最近文章/ }).click();
  await page.getByRole("menuitemradio", { name: value === "0" ? "关闭额外缓存" : `${value} 条`, exact: true }).click();
}

for (const width of [393, 1280]) {
  test(`offline cache exposes all five limits and persists selection ${width}px`, async ({ page }, info) => {
    const group = await openCachePreferences(page, width);
    await expect(group.getByRole("button", { name: /^最近文章/ })).toContainText("200 条");
    await expect(group).toContainText("未读和收藏额外保留");
    await expect(group).toContainText("图片和音视频仍需联网");
    if (width === 393) {
      await group.getByRole("button", { name: /^最近文章/ }).click();
      await expect(page.getByRole("menuitemradio")).toHaveCount(6);
      await info.attach("offline-cache-393-options.png", { body: await page.screenshot(), contentType: "image/png" });
      await page.keyboard.press("Escape");
    }
    for (const value of ["50", "100", "500", "1000", "200"]) {
      await chooseCacheLimit(page, group, value);
      await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.persistedCacheLimit())).toBe(value);
      await expect(group.getByRole("button", { name: /^最近文章/ })).toContainText(`${value} 条`);
    }
    await chooseCacheLimit(page, group, "500");
    await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.persistedCacheLimit())).toBe("500");
    await page.reload();
    await page.waitForFunction(() => Boolean(window.controlsSidebarFixture));
    await expect(group.getByRole("button", { name: /^最近文章/ })).toContainText("500 条");
    expect(await page.evaluate(() => window.controlsSidebarFixture.syncCalls)).toEqual([]);
    await group.scrollIntoViewIfNeeded();
    const geometry = await group.boundingBox();
    expect(geometry.x).toBeGreaterThanOrEqual(0);
    expect(geometry.x + geometry.width).toBeLessThanOrEqual(width + 1);
    await info.attach(`offline-cache-${width}.png`, { body: await page.screenshot(), contentType: "image/png" });
  });
}

test("offline cache waits for durable settings and freezes its limit during sync", async ({ page }) => {
  const group = await openCachePreferences(page);
  await page.evaluate(() => window.controlsSidebarFixture.holdWrites());
  await chooseCacheLimit(page, group, "100");
  await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.pendingWrites())).toBe(1);
  await expect(group.getByRole("button", { name: "立即缓存", exact: true })).toBeDisabled();
  await expect(group.getByRole("status")).toHaveText("正在保存缓存设置…");
  expect(await page.evaluate(() => window.controlsSidebarFixture.syncCalls)).toEqual([]);
  await page.evaluate(() => window.controlsSidebarFixture.finishWrites());
  await expect(group.getByRole("button", { name: "立即缓存", exact: true })).toBeEnabled();
  await group.getByRole("button", { name: "立即缓存", exact: true }).click();
  await expect(group.getByRole("status")).toContainText("正在缓存最近文章 · 100");
  expect(await page.evaluate(() => window.controlsSidebarFixture.syncCalls)).toEqual([{ mode: "foreground", limit: "100", persistedLimit: "100" }]);
  await expect(group.getByRole("button", { name: "立即缓存", exact: true })).toBeDisabled();
  await group.getByRole("button", { name: /^最近文章/ }).click();
  for (const value of ["50", "100", "200", "500", "1000"]) {
    await expect(page.getByRole("menuitemradio", { name: `${value} 条`, exact: true })).toBeDisabled();
  }
  await page.keyboard.press("Escape");
  await page.evaluate(() => window.controlsSidebarFixture.finishSync());
  await expect(page.getByText("最近文章缓存已更新", { exact: true })).toBeVisible();
  await expect(group.getByRole("button", { name: "立即缓存", exact: true })).toBeEnabled();
});

test("offline cache keeps the last saved limit when preference storage fails", async ({ page }) => {
  const group = await openCachePreferences(page);
  await chooseCacheLimit(page, group, "50");
  await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.persistedCacheLimit())).toBe("50");
  await page.evaluate(() => window.controlsSidebarFixture.failNextWrite());
  await chooseCacheLimit(page, group, "1000");
  await expect(page.getByText("缓存设置保存失败，请重试。", { exact: true })).toBeVisible();
  await expect(group.getByRole("button", { name: /^最近文章/ })).toContainText("50 条");
  await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.persistedCacheLimit())).toBe("50");
  expect(await page.evaluate(() => window.controlsSidebarFixture.syncCalls)).toEqual([]);
});

test("offline cache explains offline availability and can disable additional caching", async ({ page }) => {
  const group = await openCachePreferences(page);
  await page.evaluate(() => window.controlsSidebarFixture.setOnline(false));
  await expect(group.getByRole("status")).toContainText("已缓存的正文仍可阅读");
  await expect(group.getByRole("button", { name: "立即缓存", exact: true })).toBeDisabled();
  await chooseCacheLimit(page, group, "0");
  await expect.poll(() => page.evaluate(() => window.controlsSidebarFixture.persistedCacheLimit())).toBe("0");
  await page.evaluate(() => window.controlsSidebarFixture.setOnline(true));
  await expect(group.getByRole("button", { name: "立即缓存", exact: true })).toBeDisabled();
  await page.reload();
  await page.waitForFunction(() => Boolean(window.controlsSidebarFixture));
  await expect(group.getByRole("button", { name: /^最近文章/ })).toContainText("关闭额外缓存");
  await expect(group.getByRole("button", { name: "立即缓存", exact: true })).toBeDisabled();
  expect(await page.evaluate(() => window.controlsSidebarFixture.syncCalls)).toEqual([]);
});

test("sidebar keeps feed visibility in the feed list and groups sync notifications", async ({ page }) => {
  await openSidebarPreferences(page);
  const hidden = page.getByRole("switch", { name: "显示隐藏订阅", exact: true });
  const background = page.getByRole("switch", { name: "后台同步", exact: true });
  await expect(background).toBeVisible();
  await expect(hidden).not.toBeChecked();
  expect(await hidden.evaluate((node) => Boolean(node.closest(".sidebar-feed-options")))).toBe(true);
  expect(await hidden.evaluate((node) => Boolean(node.closest('[data-sidebar="footer"]')))).toBe(false);
  await expect(page.locator(".sidebar-sync-card").getByRole("switch", { name: "后台同步" })).toBeVisible();
  await expect(page.locator('[data-sidebar="footer"] [role="switch"]')).toHaveCount(1);
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

async function openTallFooter(page) {
  await openSidebarPreferences(page, 320);
  await page.setViewportSize({ width: 320, height: 640 });
  await page.evaluate(() => { document.documentElement.style.fontSize = "32px"; });
  // The expanded list with its real class (and its height cap and overscroll rule), plus
  // enough under it that the footer has to scroll; the marker plays the account row.
  await page.evaluate(() => {
    const card = document.querySelector('[data-sidebar="footer"] .sidebar-sync-card');
    const details = Object.assign(document.createElement("div"), { className: "sidebar-notify-details", id: "notify-list" });
    const list = Object.assign(document.createElement("ul"), { className: "sidebar-notify-list" });
    for (let index = 0; index < 30; index += 1) list.append(Object.assign(document.createElement("li"), { textContent: `订阅 ${index}` }));
    details.append(list);
    card.append(details, Object.assign(document.createElement("div"), { style: "block-size: 400px" }));
    document.querySelector('[data-sidebar="footer"]').append(Object.assign(document.createElement("div"), { id: "footer-end", textContent: "end", style: "flex-shrink: 0" }));
  });
  const footer = page.locator('[data-sidebar="footer"]');
  const viewport = page.viewportSize().height;
  const footerBox = await footer.boundingBox();
  expect(footerBox.y + footerBox.height, "the footer ends on screen").toBeLessThanOrEqual(viewport + 1);
}

test("a drag that reaches the end of the notification list carries on into the footer", async ({ page }) => {
  await openTallFooter(page);
  const viewport = page.viewportSize().height;
  // Bring the list into view, then wheel over it only: once it is at its end the wheel must
  // move the footer on, not stop at the list.
  await page.locator("#notify-list").scrollIntoViewIfNeeded();
  const box = await page.locator("#notify-list").boundingBox();
  await page.mouse.move(box.x + box.width / 2, Math.max(box.y + 10, Math.min(box.y + box.height / 2, viewport - 10)));
  for (let step = 0; step < 40; step += 1) await page.mouse.wheel(0, 300);
  await expect.poll(async () => {
    const end = await page.locator("#footer-end").boundingBox();
    return end.y + end.height <= viewport + 1;
  }, { message: "the account row is reachable by scrolling over the list" }).toBe(true);
});

test("a finger drag over the notification list also carries on into the footer", async ({ page }) => {
  await openTallFooter(page);
  const viewport = page.viewportSize().height;
  // The wheel test cannot see touch-only breaks (touch-action, a touchmove handler that
  // cancels), so drag with real touch input, always starting on the list while it shows:
  // if the list kept its drags the footer would never move.
  await page.locator("#notify-list").scrollIntoViewIfNeeded();
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 1 });
  const touch = (type, x, y) => cdp.send("Input.dispatchTouchEvent", { type, touchPoints: type === "touchEnd" ? [] : [{ x, y }] });
  const reached = async () => {
    const end = await page.locator("#footer-end").boundingBox();
    return end.y + end.height <= viewport + 1;
  };
  for (let drag = 0; drag < 16 && !(await reached()); drag += 1) {
    const box = await page.locator("#notify-list").boundingBox();
    const clip = await page.locator('[data-sidebar="footer"]').boundingBox();
    const top = Math.max(box.y, clip.y), bottom = Math.min(box.y + box.height, clip.y + clip.height);
    // Once the list has scrolled away, the rest of the footer is dragged as a whole.
    const onList = bottom - top >= 20;
    const x = box.x + box.width / 2, y = onList ? Math.min(bottom - 10, top + 160) : clip.y + clip.height - 10;
    await touch("touchStart", x, y);
    for (let step = 1; step <= 8; step += 1) await touch("touchMove", x, y - step * 18);
    await touch("touchEnd");
    await page.waitForTimeout(150);
  }
  await expect.poll(reached, { message: "the account row is reachable by dragging over the list" }).toBe(true);
});

test("the background sync title wraps in the real sidebar column at 2x text", async ({ page }) => {
  await openSidebarPreferences(page, 320);
  await page.setViewportSize({ width: 320, height: 640 });
  await page.evaluate(() => { document.documentElement.style.fontSize = "32px"; });
  const title = page.locator(".sidebar-sync-card .sidebar-pref .ds-body").first();
  await expect(title).toHaveText("后台同步");
  for (const text of ["Background sync", "Synchronisation en arrière-plan"]) {
    await title.evaluate((node, value) => { node.lang = "en"; node.textContent = value; }, text);
    const clipped = await title.evaluate((node) => node.scrollWidth > node.clientWidth + 1 || node.scrollHeight > node.clientHeight + 1);
    expect(clipped, `the whole title shows: ${text}`).toBe(false);
  }
  // The column between icon and switch is narrower than "Background" here, so the title
  // has to take its own line rather than break the word.
  await title.evaluate((node) => { node.lang = "en"; node.textContent = "Background sync"; });
  expect(await renderedLines(title)).toEqual(["Background ", "sync"]);
  await expect(page.getByRole("switch", { name: "后台同步", exact: true })).toBeVisible();
});

test("a Chinese sync title that fits keeps the one-row layout at 1.3x text", async ({ page }) => {
  await openSidebarPreferences(page, 320);
  await page.evaluate(() => { document.documentElement.style.fontSize = "20.8px"; });
  const title = page.locator(".sidebar-sync-card .sidebar-pref-title").first();
  await expect(title).toHaveText("后台同步");
  const layout = () => title.evaluate((node) => getComputedStyle(node.parentElement).display);
  expect(await layout(), "the title sits between icon and switch").not.toBe("contents");
  expect(await renderedLines(title)).toEqual(["后台同步"]);
  // The same width is too narrow for an English title, which takes its own line.
  await title.evaluate((node) => { node.lang = "en"; node.textContent = "Background sync"; });
  expect(await layout()).toBe("contents");
});

async function renderedLines(locator) {
  return locator.evaluate((node) => {
    const text = node.firstChild, lines = [];
    let top = null;
    for (let index = 0; index < text.length; index += 1) {
      const range = document.createRange();
      range.setStart(text, index);
      range.setEnd(text, index + 1);
      const rect = range.getClientRects()[0];
      if (top === null || Math.abs(rect.top - top) > 4) lines.push("");
      top = rect.top;
      lines[lines.length - 1] += text.data[index];
    }
    return lines;
  });
}

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
async function installSpeech(page, { holdRead, seconds = 6 }) {
  await page.addInitScript(() => {
    window.speechAudios = new Set();
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function (...args) { window.speechAudios.add(this); return play.apply(this, args); };
  });
  await open(page, 393);
  await page.evaluate(({ hold, seconds }) => {
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
      data: { audio: wav(seconds), status: 2 }, extra_info: { audio_length: seconds * 1000 }, base_resp: { status_code: 0 },
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
  }, { hold: holdRead, seconds });
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

test("AI summary shares the read-aloud floating surface without covering or stopping playback", async ({ page }, info) => {
  await installSpeech(page, { holdRead: false, seconds: 30 });
  await page.getByRole("button", { name: "朗读文章", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.controlsFixture.speech.phase()), { timeout: 15_000 }).toBe("playing");
  const player = page.getByRole("region", { name: "文章语音播放器", exact: true });
  await expect(player).toBeVisible();
  const before = await player.evaluate((element) => {
    const surface = element.closest(".reader-speech-slot");
    return { bottom: Math.round(surface.getBoundingClientRect().bottom), radius: getComputedStyle(surface).borderRadius };
  });
  const requests = await page.evaluate(() => window.speechNet.opened);
  const dialog = await openSummary(page);
  await expect(dialog.getByRole("region", { name: "文章语音播放器", exact: true })).toBeVisible();
  await expect(player).toHaveCount(1);
  await expect.poll(async () => dialog.evaluate((element) => ({
    bottom: Math.round(element.getBoundingClientRect().bottom), radius: getComputedStyle(element).borderRadius,
  }))).toEqual(before);
  const content = await dialog.locator(".nextflux-summary-body").boundingBox();
  const audio = await player.boundingBox();
  expect(content.y + content.height).toBeLessThanOrEqual(audio.y + 1);
  expect(await page.evaluate(() => window.controlsFixture.speech.phase())).toBe("playing");
  await info.attach("ai-summary-with-speech.png", { body: await page.screenshot(), contentType: "image/png" });
  await closeSummary(page);
  await expect(player).toBeVisible();
  expect(await page.evaluate(() => window.controlsFixture.speech.phase())).toBe("playing");
  expect(await page.evaluate(() => window.speechNet.opened)).toBe(requests);
  await page.getByRole("button", { name: "停止朗读", exact: true }).click();
  await expectSpeechStopped(page);
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

