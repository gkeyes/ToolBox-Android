import { test, expect } from "@playwright/test";
import { PRODUCTION_URL, STORED_SETTINGS, installNativeBoundary } from "./fixtures/startup-native.js";

// The app, report repository, scheduler and article cache are production code.
// Only native HTTP/AI responses are synthetic, so these tests send no requests
// to a provider or to a real Miniflux account.
async function prepareIntervals(page, { persistStorage = false } = {}) {
  await installNativeBoundary(page, {
    persistStorage,
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "interval-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0", hideDuplicateArticles: false, markAsReadOnScroll: false },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  await expect(page.locator(".article-list-inline-tabs .tabs__list")).toBeVisible();
  await expect.poll(() => page.evaluate(async () => (await import("/src/stores/syncStore.js")).isSyncing.get())).toBe(false);
  await page.evaluate(async () => {
    const { initializeBoardHistory } = await import("/src/toolbox/board/historyStore.js");
    await initializeBoardHistory();
    await (await import("/src/db/storage.js")).addFeeds([{ id: 1, title: "时段订阅", categoryId: 1, hide_globally: false }]);
    const state = window.__intervalTest = { messages: [], reads: [], streams: new Map(), hold: false, fail: false, tick: 0 };
    const network = window.ToolBox.network;
    const originalRequest = network.request;
    network.request = async (payload) => {
      if (payload.method !== "PUT" || new URL(payload.url).pathname !== "/v1/entries") return originalRequest(payload);
      state.reads.push(JSON.parse(payload.body));
      return { status: 204, headers: {}, body: "" };
    };
    network.openStream = async (payload) => {
      const { messages } = JSON.parse(payload.body);
      const merging = messages[0].content.includes("候选主题");
      const writing = !merging && messages[1].content.startsWith("主题");
      const references = [...messages[1].content.matchAll(/^\[(\d+)\] /gm)].map((match) => Number(match[1]));
      state.messages.push({ kind: writing ? "digest" : merging ? "merge" : "articles", references, messages });
      if (state.fail) throw Object.assign(new Error("Synthetic provider unavailable"), { code: "NETWORK_UNAVAILABLE" });
      if (state.hold) await new Promise((resolve) => { state.release = resolve; });
      const reply = writing
        ? `## 本期重点\n这份报告只引用本期最后收录的来源。[${references.at(-1)}]`
        : JSON.stringify({ topics: [{ title: merging ? "本期主题" : "批次主题", gist: "时段内值得关注的事情", summary: "只收录最后一个候选来源。", articles: [references.at(-1)] }] });
      const streamId = `interval-${state.messages.length}`;
      state.streams.set(streamId, `data: ${JSON.stringify({ choices: [{ delta: { content: reply }, finish_reason: "stop" }] })}\n\ndata: [DONE]\n\n`);
      return { status: 200, streamId };
    };
    network.readStream = async (id) => {
      const data = state.streams.get(id) || "";
      state.streams.delete(id);
      return { data: new TextEncoder().encode(data), done: true };
    };
    network.cancelStream = async (id) => { state.streams.delete(id); };
  });
}

async function commitArticles(page, first, count) {
  await page.evaluate(async ({ first, count }) => {
    const now = new Date().toISOString();
    const ids = Array.from({ length: count }, (_, index) => first + index);
    await (await import("/src/db/storage.js")).addArticles(ids.map((id) => ({
      id, feedId: 1, title: `时段文章 ${id}`, content: `<p>来源 ${id} 的独立正文预览。</p>`,
      url: `https://example.test/interval/${id}`, status: "unread", starred: false,
      published_at: now, created_at: now,
    })));
    const committedAt = new Date(Date.now() + ++window.__intervalTest.tick).toISOString();
    (await import("/src/stores/syncStore.js")).syncCommitted.set({
      outcome: "committed", initialSync: false, syncedAt: committedAt, committedAt,
      newEntryIds: ids, newEntryCount: ids.length, updatedCount: 0, unreadCount: first + count - 1,
    });
  }, { first, count });
}

async function seedReport(page, { id = "saved-overview", remaining = 48 * 60 * 60 * 1000, kind = "today", topicCount = 1 } = {}) {
  await page.evaluate(async ({ id, remaining, kind, topicCount }) => {
    const at = Date.now() - 48 * 60 * 60 * 1000 + remaining;
    const { saveBoardSnapshot } = await import("/src/toolbox/board/historyStore.js");
    const articles = Array.from({ length: topicCount + 1 }, (_, index) => ({
      id: 9001 + index,
      title: index === 0 ? "已清理的来源" : index === topicCount ? "未收录的来源" : `历史来源 ${index + 1}`,
      feedTitle: "历史订阅", url: index === 0 ? "https://example.test/saved-source" : index === topicCount ? "https://example.test/omitted" : `https://example.test/saved-source/${index + 1}`,
      text: index === 0 ? "这是已保存的来源预览，<b>这部分保持纯文本</b>。" : index === topicCount ? "未纳入报告。" : "这段文字在生成报告时已保存。",
    }));
    const topics = Array.from({ length: topicCount }, (_, index) => ({
      title: topicCount === 1 ? "之前保存的主题" : `${["1. ", "2️⃣ "][index] || ""}历史主题 ${index + 1}`,
      summary: "这个版本可以继续阅读。", articles: [index + 1],
    }));
    const digest = topicCount === 1 ? "## 已保存的文章\n这段内容来自报告保存的来源。[1]"
      : "这是无标题的导语。\n\n" + topics.map((_, index) => `## ${["1. ", "2️⃣ "][index] || ""}已保存的文章 ${index + 1}\n这段内容来自报告保存的来源。[${index + 1}]`).join("\n\n");
    await saveBoardSnapshot({
      status: "done", reportId: id, kind, at, day: at - 3_600_000,
      periodStart: at - 3_600_000, periodEnd: at, total: articles.length, topics, articles,
    }, digest);
  }, { id, remaining, kind, topicCount });
}

const reportPicker = (page) => page.getByRole("region", { name: "报告历史" }).getByRole("button");

async function chooseSavedReport(page, name) {
  await reportPicker(page).click();
  await page.getByRole("option", { name }).click();
  await expect(page.getByRole("listbox")).toHaveCount(0);
  await expect(reportPicker(page)).toContainText(name);
}

test("151 new unread items start a silent report, with later arrivals kept for the next one", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await prepareIntervals(page);
  await commitArticles(page, 1, 150);
  await expect.poll(() => page.evaluate(async () => (await import("/src/toolbox/board/historyStore.js")).reportHistory.get().pendingCount)).toBe(150);
  expect(await page.evaluate(() => window.__intervalTest.messages.length)).toBe(0);
  await page.evaluate(() => { window.__intervalTest.hold = true; });
  await commitArticles(page, 151, 1);
  await expect.poll(() => page.evaluate(async () => (await import("/src/toolbox/board/historyStore.js")).reportHistory.get().job?.status)).toBe("running");
  await expect.poll(() => page.evaluate(() => Boolean(window.__intervalTest.release))).toBe(true);
  expect(page.url()).not.toContain("#/board");
  expect(await page.evaluate(() => window.__intervalTest.reads)).toEqual([]);
  await commitArticles(page, 152, 10);
  await expect.poll(() => page.evaluate(async () => (await import("/src/toolbox/board/historyStore.js")).reportHistory.get().pendingCount)).toBe(10);
  // Opening the board during the automatic run shows that run, without
  // starting a second, overlapping full-day AI request.
  const callsWhileRunning = await page.evaluate(() => window.__intervalTest.messages.length);
  await page.evaluate(() => { location.hash = "#/board"; });
  await expect(page.getByRole("tab", { name: /历史报告/ })).toHaveAttribute("aria-selected", "true");
  await expect(page.locator(".nf-board-auto-status")).toBeVisible();
  expect(await page.evaluate(() => window.__intervalTest.messages.length)).toBe(callsWhileRunning);
  await page.evaluate(() => { window.__intervalTest.hold = false; window.__intervalTest.release(); });
  await expect.poll(() => page.evaluate(async () => (await import("/src/toolbox/board/historyStore.js")).reportHistory.get().reports.length)).toBe(1);
  const completed = await page.evaluate(async () => {
    const history = (await import("/src/toolbox/board/historyStore.js")).reportHistory.get();
    return { history, calls: window.__intervalTest.messages.length, stages: window.__intervalTest.messages.map((call) => call.kind), reads: window.__intervalTest.reads };
  });
  expect(completed.history.pendingCount).toBe(10);
  expect(completed.history.reports[0]).toMatchObject({ kind: "interval", total: 151, includedCount: 1, unseen: true });
  expect(completed.history.reports[0].expiresAt - completed.history.reports[0].completedAt).toBe(48 * 60 * 60 * 1000);
  expect(completed.reads).toEqual([]);
  expect(completed.stages).toEqual(["articles", "digest"], "the period needs one analysis and one written report, without an unnecessary merge");
  await expect(page.locator(".nf-board-summary")).toHaveCount(0);

  // Completion adds a report to the picker. Choosing it and reading its
  // written form both use storage, without resubmitting it to the provider.
  await chooseSavedReport(page, /151 篇/);
  await expect(page.locator(".nf-board-summary")).toContainText("本时段 151 篇未读已全部参与分析");
  await expect(page.getByRole("tab", { name: /历史报告/ })).toHaveAttribute("aria-selected", "true");
  await expect(page.locator(".nf-board-auto-count")).toContainText("已累计新增未读 10 篇");
  await page.getByRole("button", { name: "完整报告", exact: true }).click();
  await expect(page.locator(".nf-board-digest h4 > span:last-child")).toHaveText("本期重点");
  expect(await page.evaluate(() => window.__intervalTest.messages.length)).toBe(completed.calls);
  expect(await page.evaluate(() => window.__intervalTest.reads)).toEqual([]);
  const selected = await page.evaluate(async () => {
    const { board } = await import("/src/toolbox/board/boardStore.js");
    const { boardArticleIds } = await import("/src/toolbox/board/board.mjs");
    return boardArticleIds(board.get().topics, board.get().articles);
  });
  expect(selected).toHaveLength(1);
  await page.getByRole("button", { name: "看板文章一键已读", exact: true }).click();
  await expect(page.locator(".nf-board-read-result")).toContainText("已将 1 篇");
  expect(await page.evaluate(() => window.__intervalTest.reads)).toEqual([{ entry_ids: selected, status: "read" }]);
  const unread = await page.evaluate(async () => (await (await import("/src/db/storage.js")).getCachedArticleMetadata({ filter: "unread" })).map((row) => row.id));
  expect(unread).toHaveLength(160);
  for (let id = 152; id <= 161; id += 1) expect(unread).toContain(id);
  expect(pageErrors).toEqual([]);
});

test("an empty daily overview announces new unread articles and waits for an explicit update", async ({ page }) => {
  await prepareIntervals(page);
  await page.evaluate(() => { location.hash = "#/board"; });
  await expect(page.getByText("今天还没有未读文章。", { exact: true })).toBeVisible();
  await commitArticles(page, 1, 1);
  const update = page.getByRole("region", { name: "今日总览更新" });
  await expect(update).toContainText("又有 1 篇新增未读");
  await expect(page.getByText("今天还没有未读文章。", { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => window.__intervalTest.messages.length)).toBe(0);
  await update.getByRole("button", { name: "更新今日总览" }).click();
  await expect(page.locator(".nf-board-summary")).toContainText("今日 1 篇未读已全部参与分析");
  expect(await page.evaluate(() => window.__intervalTest.messages.filter((message) => message.kind === "articles").length)).toBe(1);
  expect(await page.evaluate(() => window.__intervalTest.reads)).toEqual([]);
});

test("automatic reports can be disabled, and a failed manual report retries without replacing the selected history", async ({ page }) => {
  await prepareIntervals(page);
  await seedReport(page);
  await page.evaluate(() => { location.hash = "#/board"; });
  await expect(page.locator(".nf-board-card h3")).toHaveText("之前保存的主题");
  await page.evaluate(async () => (await import("/src/stores/modalStore.js")).settingsModalOpen.set(true));
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: /AI/ }).first().click();
  const automatic = dialog.getByRole("switch", { name: /自动生成时段报告/ });
  await expect(automatic).toBeChecked();
  await dialog.getByText("自动生成时段报告", { exact: true }).click();
  await expect(automatic).not.toBeChecked();
  await expect(dialog.getByText(/报告从生成完成起保存 48 小时/)).toBeVisible();
  await dialog.getByRole("button", { name: /关闭|Close/ }).first().click();
  await page.evaluate(async () => { await (await import("/src/toolbox/preferences.js")).flushPreferences(); });
  expect(await page.evaluate(() => JSON.parse(window.__startupNative.persistedPreferences().settings).autoBoardEnabled)).toBe(false);

  await commitArticles(page, 1, 12);
  await expect(page.locator(".nf-board-auto-count")).toContainText("已累计新增未读 12 篇");
  await expect(page.locator(".nf-board-auto-count")).toContainText("自动生成已关闭");
  await page.evaluate(() => { window.__intervalTest.fail = true; });
  await page.getByRole("button", { name: "立即生成本时段" }).click();
  await expect(page.getByRole("button", { name: "重试生成" })).toBeVisible();
  await expect(page.locator(".nf-board-auto-status")).toContainText("这期报告尚未完成");
  await expect(page.locator(".nf-board-card h3")).toHaveText("之前保存的主题");
  await page.evaluate(() => { window.__intervalTest.fail = false; });
  await page.getByRole("button", { name: "重试生成" }).click();
  await expect.poll(() => page.evaluate(async () => (await import("/src/toolbox/board/historyStore.js")).reportHistory.get().reports.length)).toBe(2);
  await expect(reportPicker(page)).toContainText("今日总览");
  await expect(page.locator(".nf-board-card h3")).toHaveText("之前保存的主题");
  const completed = await page.evaluate(async () => ({
    report: (await import("/src/toolbox/board/historyStore.js")).reportHistory.get().reports.find((report) => report.kind === "interval"),
    calls: window.__intervalTest.messages.length,
    reads: window.__intervalTest.reads,
  }));
  expect(completed.report).toMatchObject({ total: 12, includedCount: 1 });
  expect(completed.reads).toEqual([]);
  await chooseSavedReport(page, /12 篇/);
  await expect(page.locator(".nf-board-summary")).toContainText("本时段 12 篇未读已全部参与分析");
  await page.getByRole("button", { name: "完整报告", exact: true }).click();
  await expect(page.locator(".nf-board-digest h4 > span:last-child")).toHaveText("本期重点");
  expect(await page.evaluate(() => window.__intervalTest.messages.length)).toBe(completed.calls);
});

test("saved daily reports survive reload, preview a cleaned source, and disappear at 48 hours while open", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await page.clock.install();
  await prepareIntervals(page, { persistStorage: true });
  await seedReport(page, { remaining: 60_000 });
  await page.goto(`${PRODUCTION_URL}#/board`);
  await page.reload();
  expect(await page.evaluate(() => typeof window.__intervalTest)).toBe("undefined");
  await expect(page.locator(".nf-board-card h3")).toHaveText("之前保存的主题");
  await expect(page.getByRole("tab", { name: /历史报告/ })).toHaveAttribute("aria-selected", "true");
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "24px";
    window.__sourceLinks = [];
    window.ToolBox.browser = { open: async (url) => { window.__sourceLinks.push(url); } };
  });
  const layout = await page.evaluate(() => {
    const picker = document.querySelector("#nf-board-report-picker").getBoundingClientRect();
    const choices = [...document.querySelectorAll(".nf-board-navigation [role=tab]")].map((button) => {
      const rect = button.getBoundingClientRect();
      return { left: rect.left, right: rect.right, height: rect.height };
    });
    return { width: innerWidth, overflow: document.documentElement.scrollWidth > innerWidth + 1, picker: { left: picker.left, right: picker.right }, choices };
  });
  expect(layout.overflow).toBe(false);
  expect(layout.picker.left).toBeGreaterThanOrEqual(0);
  expect(layout.picker.right).toBeLessThanOrEqual(layout.width);
  for (const choice of layout.choices) {
    expect(choice.left).toBeGreaterThanOrEqual(0);
    expect(choice.right).toBeLessThanOrEqual(layout.width);
    expect(choice.height).toBeGreaterThanOrEqual(44);
  }
  await page.locator('.nf-board-chip[href="#/board/article/9001"]').click();
  const preview = page.getByRole("dialog", { name: "报告来源预览" });
  await expect(preview).toBeVisible();
  await expect(preview.locator(".nf-board-source-text")).toHaveText("这是已保存的来源预览，<b>这部分保持纯文本</b>。");
  await expect(preview.locator(".nf-board-source-text b")).toHaveCount(0);
  await preview.getByRole("button", { name: "打开原文链接" }).click();
  expect(await page.evaluate(() => window.__sourceLinks)).toEqual(["https://example.test/saved-source"]);
  await page.keyboard.press("Escape");
  await expect(preview).not.toBeVisible();
  expect(page.url()).toBe(`${PRODUCTION_URL}#/board`);
  await page.getByRole("button", { name: "完整报告", exact: true }).click();
  await expect(page.locator(".nf-board-digest h4 > span:last-child")).toHaveText("已保存的文章");
  // No AI stub was reinstalled after navigation. The saved digest is enough.
  expect(await page.evaluate(async () => (await import("/src/toolbox/board/boardStore.js")).digest.get().status)).toBe("done");
  await page.clock.fastForward(61_000);
  await expect(page.locator(".nf-board-status")).toContainText("这份报告已超过 48 小时");
  await expect(page.locator(".nf-board-digest")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "看板文章一键已读", exact: true })).toHaveCount(0);
  await expect(reportPicker(page)).toBeDisabled();
  await expect(reportPicker(page)).toHaveText("还没有已生成的报告");
  await expect(page.getByRole("tab", { name: "今日", exact: true })).toBeVisible();
});

test("saved report menus follow the theme, fit large phone text, and keep reading free of AI or read updates", async ({ page }, testInfo) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() !== "error") return;
    const text = message.text();
    // This fixture deliberately rejects the startup integration-status probe;
    // its API and store both log that expected offline result.
    if (text.startsWith("检查第三方集成状态失败: Error: 无法连接服务器，请检查网络后重试。") && text.includes("/src/stores/basicInfoStore.js")) return;
    errors.push(text);
  });
  await prepareIntervals(page);
  await commitArticles(page, 9001, 4);
  await seedReport(page, { remaining: 47 * 60 * 60 * 1000 });
  await seedReport(page, { id: "saved-period", kind: "interval", topicCount: 3 });
  await page.evaluate(() => { location.hash = "#/board"; });
  await expect(page).toHaveURL(`${PRODUCTION_URL}#/board`);
  await expect(page).toHaveTitle(/NextFlux/i);
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "历史报告", exact: true })).toBeVisible();
  await expect(page.getByRole("tablist", { name: "看板范围" })).toHaveCount(1);
  await expect(page.getByRole("tablist", { name: "看板范围" }).getByRole("tab")).toHaveCount(2);
  await expect(page.locator(".nf-board-card h3")).toHaveText(["历史主题 1", "历史主题 2", "历史主题 3"]);
  await expect(page.locator(".nf-board-card-head > .nf-board-rank")).toHaveText(["1", "2", "3"]);
  const picker = reportPicker(page);
  const complete = page.getByRole("button", { name: "完整报告", exact: true });
  await expect(picker).toHaveAccessibleName(/报告时间/);
  await expect(complete).toHaveAttribute("aria-expanded", "false");
  await expect(page.locator(".nf-board-digest")).toHaveCount(0);
  const initialCalls = await page.evaluate(() => window.__intervalTest.messages.length);

  await chooseSavedReport(page, /2 篇/);
  await expect(page.locator(".nf-board-card h3")).toHaveText("之前保存的主题");
  await complete.click();
  await expect(page.locator(".nf-board-digest h4 > span:last-child")).toHaveText("已保存的文章");
  await complete.click();
  await complete.click();
  await expect(page.locator(".nf-board-digest h4 > span:last-child")).toHaveText("已保存的文章");
  await chooseSavedReport(page, /4 篇/);
  await expect(complete).toHaveAttribute("aria-expanded", "false");
  await complete.click();
  // An untitled introduction does not consume 1, and old saved content gets
  // the same display numbering as a newly generated report.
  await expect(page.locator(".nf-board-digest h4 > span:last-child")).toHaveText(["已保存的文章 1", "已保存的文章 2", "已保存的文章 3"]);
  await expect(page.locator(".nf-board-digest h4 > .nf-board-rank")).toHaveText(["1", "2", "3"]);
  const badgeStyles = await page.locator(".nf-board-card-head > .nf-board-rank, .nf-board-digest h4 > .nf-board-rank").evaluateAll((badges) => badges.map((badge) => {
    const style = getComputedStyle(badge);
    const rect = badge.getBoundingClientRect();
    return { radius: Number.parseFloat(style.borderRadius), background: style.backgroundColor, width: rect.width, height: rect.height };
  }));
  for (const badge of badgeStyles) {
    expect(badge.radius).toBeGreaterThanOrEqual(badge.height / 2);
    expect(badge.width).toBeCloseTo(badge.height, 0);
  }
  expect(badgeStyles.slice(0, 3)).toEqual(badgeStyles.slice(3));
  expect(badgeStyles[0].background).not.toBe(badgeStyles[1].background);
  expect(badgeStyles[1].background).not.toBe(badgeStyles[2].background);
  await expect(page.locator(".nf-board-digest .nf-board-cite")).toHaveCount(3);
  expect(await page.locator(".nf-board-digest .nf-board-cite").evaluateAll((links) => links.map((link) => link.getAttribute("href")))).toEqual([
    "#/board/article/9001", "#/board/article/9002", "#/board/article/9003",
  ]);
  await page.setViewportSize({ width: 400, height: 1200 });
  await expect.poll(() => page.locator(".nf-board-complete").evaluate((element) => element.getAnimations({ subtree: true }).some((animation) => animation.playState === "running"))).toBe(false);
  await page.locator(".nf-board-digest").screenshot({ path: testInfo.outputPath("saved-digest-numbering.png") });
  await complete.click();

  const backgrounds = {};
  const menuStyles = {};
  for (const view of [
    { name: "phone-dark", theme: "dark", width: 320, fontSize: "24px" },
    { name: "phone-light", theme: "light", width: 320, fontSize: "24px" },
    { name: "desktop-light", theme: "light", width: 1200, fontSize: "16px" },
  ]) {
    await page.setViewportSize({ width: view.width, height: 900 });
    await page.evaluate(async ({ theme, fontSize }) => {
      (await import("/src/stores/themeStore.js")).setTheme(theme);
      document.documentElement.style.fontSize = fontSize;
    }, view);
    await picker.click();
    const menu = page.getByRole("listbox", { name: /历史报告/ });
    await expect(menu).toBeVisible();
    const popover = page.locator(".nf-board-report-popover");
    await expect(popover).not.toHaveAttribute("data-entering", "true");
    await expect(popover).toHaveCSS("opacity", "1");
    await expect.poll(() => popover.evaluate((element) => element.getAnimations({ subtree: true }).some((animation) => animation.playState === "running"))).toBe(false);
    await expect(menu.getByRole("option")).toHaveCount(2);
    await expect(menu.getByRole("option", { name: /4 篇/ })).toHaveAttribute("aria-selected", "true");
    await expect(menu.getByRole("option", { name: /2 篇/ })).toHaveAttribute("aria-selected", "false");
    const layout = await menu.evaluate((element) => {
      const bounds = (node) => {
        const rect = node.getBoundingClientRect();
        return { left: rect.left, right: rect.right, height: rect.height, overflow: node.scrollWidth > node.clientWidth + 1 };
      };
      const style = getComputedStyle(element.closest(".nf-board-report-popover"));
      return {
        viewport: innerWidth, overflow: document.documentElement.scrollWidth > innerWidth + 1,
        menu: bounds(element), options: [...element.querySelectorAll('[role="option"]')].map(bounds),
        tabs: [...document.querySelectorAll('.nf-board-navigation [role="tab"]')].map(bounds),
        background: style.backgroundColor, backdropFilter: style.backdropFilter, opacity: style.opacity,
      };
    });
    expect(layout.overflow).toBe(false);
    for (const item of [layout.menu, ...layout.options, ...layout.tabs]) {
      expect(item.left).toBeGreaterThanOrEqual(0);
      expect(item.right).toBeLessThanOrEqual(layout.viewport);
      expect(item.overflow).toBe(false);
    }
    for (const tab of layout.tabs) expect(tab.height).toBeGreaterThanOrEqual(44);
    backgrounds[view.name] = layout.background;
    menuStyles[view.name] = { background: layout.background, backdropFilter: layout.backdropFilter, opacity: layout.opacity };
    await page.screenshot({ path: testInfo.outputPath(`history-${view.name}.png`), fullPage: false });
    await expect.poll(() => menu.evaluate((element) => element.contains(document.activeElement))).toBe(true);
    await page.keyboard.press("Escape");
    await expect(menu).toHaveCount(0);
    await expect(picker).toBeFocused();
  }
  await testInfo.attach("report-menu-styles", { body: JSON.stringify(menuStyles, null, 2), contentType: "application/json" });
  expect(backgrounds["phone-dark"]).not.toBe(backgrounds["phone-light"]);
  // The page remains interactive after the final portal closes.
  await complete.click();
  await expect(page.locator(".nf-board-digest h4")).toHaveCount(3);
  expect(await page.evaluate(() => window.__intervalTest.messages.length)).toBe(initialCalls);
  expect(await page.evaluate(() => window.__intervalTest.reads)).toEqual([]);
  expect(await page.evaluate(async () => (await (await import("/src/db/storage.js")).getCachedArticleMetadata({ filter: "unread" })).map((article) => article.id).sort())).toEqual([9001, 9002, 9003, 9004]);
  expect(errors).toEqual([]);
});

test("board scope tabs keep one pill without overlap while touching, switching, and using large text", async ({ page }, testInfo) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await prepareIntervals(page);
  await page.evaluate(() => { location.hash = "#/board"; });
  await expect(page.getByText("今天还没有未读文章。", { exact: true })).toBeVisible();
  await seedReport(page, { id: "scope-style-report", kind: "interval" });
  await page.evaluate(async () => {
    const manager = await (await import("/src/toolbox/board/historyStore.js")).initializeBoardHistory();
    const report = await manager.readReport("scope-style-report");
    await manager.saveReport({ ...report, unseen: true });
  });
  const navigation = page.locator(".nf-board-navigation");
  const today = navigation.getByRole("tab", { name: "今日", exact: true });
  const reports = navigation.getByRole("tab", { name: /历史报告/ });
  await expect(navigation.locator(".nf-board-history-dot")).toBeVisible();
  const calls = await page.evaluate(() => window.__intervalTest.messages.length);

  const settled = () => expect.poll(() => navigation.evaluate((element) =>
    element.getAnimations({ subtree: true }).some((animation) => animation.playState === "running"))).toBe(false);
  const onePill = async () => {
    await expect(navigation.locator('.tabs__tab[aria-selected="true"]')).toHaveCount(1);
    await expect(navigation.locator(".tabs__indicator")).toHaveCount(1);
    const layout = await navigation.evaluate((element) => {
      const box = (node) => {
        const rect = node.getBoundingClientRect();
        return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom,
          height: rect.height, overflow: node.scrollWidth > node.clientWidth + 1 };
      };
      return {
        viewport: innerWidth, pageOverflow: document.documentElement.scrollWidth > innerWidth + 1,
        tabs: [...element.querySelectorAll('[role="tab"]')].map(box),
        selected: box(element.querySelector('[role="tab"][aria-selected="true"]')),
        indicator: box(element.querySelector(".tabs__indicator")),
        tabPaint: [...element.querySelectorAll('[role="tab"]')].map((node) => {
          const style = getComputedStyle(node);
          return { image: style.backgroundImage, color: style.backgroundColor, scale: style.scale };
        }),
      };
    });
    expect(layout.pageOverflow).toBe(false);
    expect(layout.tabs[0].right).toBeLessThanOrEqual(layout.tabs[1].left + 1);
    for (const tab of layout.tabs) {
      expect(tab.left).toBeGreaterThanOrEqual(0);
      expect(tab.right).toBeLessThanOrEqual(layout.viewport);
      expect(tab.height).toBeGreaterThanOrEqual(44);
      expect(tab.overflow).toBe(false);
    }
    expect(layout.indicator.left).toBeGreaterThanOrEqual(layout.selected.left - 1);
    expect(layout.indicator.right).toBeLessThanOrEqual(layout.selected.right + 1);
    expect(layout.indicator.top).toBeGreaterThanOrEqual(layout.selected.top - 1);
    expect(layout.indicator.bottom).toBeLessThanOrEqual(layout.selected.bottom + 1);
    expect(layout.tabPaint).toEqual([
      { image: "none", color: "rgba(0, 0, 0, 0)", scale: "1" },
      { image: "none", color: "rgba(0, 0, 0, 0)", scale: "1" },
    ]);
  };

  for (const view of [
    { name: "phone-light", theme: "light", width: 400, fontSize: "16px" },
    { name: "phone-dark", theme: "dark", width: 400, fontSize: "16px" },
    { name: "large-light", theme: "light", width: 320, fontSize: "24px" },
    { name: "large-dark", theme: "dark", width: 320, fontSize: "24px" },
  ]) {
    await test.step(view.name, async () => {
      await page.setViewportSize({ width: view.width, height: 900 });
      await page.evaluate(async ({ theme, fontSize }) => {
        (await import("/src/stores/themeStore.js")).setTheme(theme);
        document.documentElement.style.fontSize = fontSize;
      }, view);
      await today.click();
      await expect(today).toHaveAttribute("aria-selected", "true");
      await settled();
      await onePill();

      // React Aria commits touch selection on release. Holding the other tab
      // must not paint a second blue pill beside the existing white one.
      const bounds = await reports.boundingBox();
      const pointer = { pointerId: 71, pointerType: "touch", isPrimary: true, button: 0,
        clientX: bounds.x + bounds.width / 2, clientY: bounds.y + bounds.height / 2,
        width: 8, height: 8, pressure: 0.5, detail: 1 };
      await reports.dispatchEvent("pointerdown", { ...pointer, buttons: 1 });
      await expect(reports).toHaveAttribute("data-nf-pressed", "true");
      await expect(today).toHaveAttribute("aria-selected", "true");
      await expect(reports).toHaveAttribute("aria-selected", "false");
      await onePill();
      await navigation.screenshot({ path: testInfo.outputPath(`board-scope-held-${view.name}.png`) });
      await reports.dispatchEvent("pointerup", { ...pointer, buttons: 0, pressure: 0 });
      await reports.dispatchEvent("click", { detail: 1, button: 0 });
      await expect(reports).toHaveAttribute("aria-selected", "true");
      await expect(reports).not.toHaveAttribute("data-nf-pressed", "true");
      await settled();
      await onePill();

      await reports.focus();
      await page.keyboard.press("ArrowLeft");
      await expect(today).toHaveAttribute("aria-selected", "true");
      await expect(today).toBeFocused();
      await settled();

      // Some Android WebViews expose only touch events to the glass helper.
      // Exercise that supported path without fabricating selected CSS flags.
      await reports.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        const touch = new Touch({ identifier: 72, target: element,
          clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2 });
        element.dispatchEvent(new TouchEvent("touchstart", { bubbles: true, touches: [touch], targetTouches: [touch], changedTouches: [touch] }));
      });
      await expect(reports).toHaveAttribute("data-nf-pressed", "true");
      await onePill();
      await reports.dispatchEvent("touchcancel", { touches: [], targetTouches: [], changedTouches: [] });
      await expect(reports).not.toHaveAttribute("data-nf-pressed", "true");
    });
  }
  expect(await page.evaluate(() => window.__intervalTest.messages.length)).toBe(calls);
  expect(await page.evaluate(() => window.__intervalTest.reads)).toEqual([]);
  expect(errors).toEqual([]);
});
