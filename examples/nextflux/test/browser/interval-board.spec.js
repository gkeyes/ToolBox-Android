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

async function seedReport(page, { id = "saved-overview", remaining = 48 * 60 * 60 * 1000, kind = "today" } = {}) {
  await page.evaluate(async ({ id, remaining, kind }) => {
    const at = Date.now() - 48 * 60 * 60 * 1000 + remaining;
    const { saveBoardSnapshot } = await import("/src/toolbox/board/historyStore.js");
    await saveBoardSnapshot({
      status: "done", reportId: id, kind, at, day: at - 3_600_000,
      periodStart: at - 3_600_000, periodEnd: at, total: 2,
      topics: [{ title: "之前保存的主题", summary: "这个版本可以继续阅读。", articles: [1] }],
      articles: [
        { id: 9001, title: "已清理的来源", feedTitle: "历史订阅", url: "https://example.test/saved-source", text: "这是已保存的来源预览，<b>这部分保持纯文本</b>。" },
        { id: 9002, title: "未收录的来源", feedTitle: "历史订阅", url: "https://example.test/omitted", text: "未纳入报告。" },
      ],
    }, "## 已保存的文章\n这段内容来自报告保存的来源。[1]");
  }, { id, remaining, kind });
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
  await expect(page.getByRole("button", { name: /48 小时报告/ })).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator(".nf-board-auto-status")).toBeVisible();
  expect(await page.evaluate(() => window.__intervalTest.messages.length)).toBe(callsWhileRunning);
  await page.evaluate(() => { window.__intervalTest.hold = false; window.__intervalTest.release(); });
  await expect.poll(() => page.evaluate(async () => (await import("/src/toolbox/board/historyStore.js")).reportHistory.get().reports.length)).toBe(1);
  const completed = await page.evaluate(async () => {
    const history = (await import("/src/toolbox/board/historyStore.js")).reportHistory.get();
    return { history, calls: window.__intervalTest.messages.length, reads: window.__intervalTest.reads };
  });
  expect(completed.history.pendingCount).toBe(10);
  expect(completed.history.reports[0]).toMatchObject({ kind: "interval", total: 151, includedCount: 1, unseen: true });
  expect(completed.history.reports[0].expiresAt - completed.history.reports[0].completedAt).toBe(48 * 60 * 60 * 1000);
  expect(completed.reads).toEqual([]);
  await expect(page.locator(".nf-board-summary")).toHaveCount(0);

  // Completion adds a report to the picker. Choosing it and reading its
  // written form both use storage, without resubmitting it to the provider.
  await page.locator("#nf-board-report-picker").selectOption(completed.history.reports[0].id);
  await expect(page.locator(".nf-board-summary")).toContainText("本时段 151 篇未读已全部参与分析");
  await expect(page.getByRole("button", { name: /48 小时报告/ })).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator(".nf-board-auto-count")).toContainText("已累计新增未读 10 篇");
  await page.getByRole("tab", { name: "文章", exact: true }).click();
  await expect(page.locator(".nf-board-digest h4")).toHaveText("本期重点");
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
  await expect(page.locator("#nf-board-report-picker")).toHaveValue("saved-overview");
  await expect(page.locator(".nf-board-card h3")).toHaveText("之前保存的主题");
  const completed = await page.evaluate(async () => ({
    report: (await import("/src/toolbox/board/historyStore.js")).reportHistory.get().reports.find((report) => report.kind === "interval"),
    calls: window.__intervalTest.messages.length,
    reads: window.__intervalTest.reads,
  }));
  expect(completed.report).toMatchObject({ total: 12, includedCount: 1 });
  expect(completed.reads).toEqual([]);
  await page.locator("#nf-board-report-picker").selectOption(completed.report.id);
  await expect(page.locator(".nf-board-summary")).toContainText("本时段 12 篇未读已全部参与分析");
  await page.getByRole("tab", { name: "文章", exact: true }).click();
  await expect(page.locator(".nf-board-digest h4")).toHaveText("本期重点");
  expect(await page.evaluate(() => window.__intervalTest.messages.length)).toBe(completed.calls);
});

test("saved daily reports survive reload, preview a cleaned source, and disappear at 48 hours while open", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await page.clock.install();
  await prepareIntervals(page, { persistStorage: true });
  await seedReport(page, { remaining: 60_000 });
  await page.goto(`${PRODUCTION_URL}#/board`);
  await expect(page.locator(".nf-board-card h3")).toHaveText("之前保存的主题");
  await expect(page.getByRole("button", { name: /48 小时报告/ })).toHaveAttribute("aria-pressed", "true");
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "24px";
    window.__sourceLinks = [];
    window.ToolBox.browser = { open: async (url) => { window.__sourceLinks.push(url); } };
  });
  const layout = await page.evaluate(() => {
    const picker = document.querySelector("#nf-board-report-picker").getBoundingClientRect();
    const choices = [...document.querySelectorAll(".nf-board-scope button")].map((button) => {
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
  await page.getByRole("tab", { name: "文章", exact: true }).click();
  await expect(page.locator(".nf-board-digest h4")).toHaveText("已保存的文章");
  // No AI stub was reinstalled after navigation. The saved digest is enough.
  expect(await page.evaluate(async () => (await import("/src/toolbox/board/boardStore.js")).digest.get().status)).toBe("done");
  await page.clock.fastForward(61_000);
  await expect(page.locator(".nf-board-status")).toContainText("这份报告已超过 48 小时");
  await expect(page.locator(".nf-board-digest")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "看板文章一键已读", exact: true })).toHaveCount(0);
  await expect(page.locator("#nf-board-report-picker option")).toHaveCount(1);
  await expect(page.getByRole("button", { name: "今日总览", exact: true })).toBeVisible();
});
