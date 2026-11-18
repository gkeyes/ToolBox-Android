import { test, expect } from "@playwright/test";
import { PRODUCTION_URL, STORED_SETTINGS, installNativeBoundary } from "./fixtures/startup-native.js";

// Exercise the production board, cache, account queue and Miniflux adapter.
// Only the native network boundary is synthetic; no AI/provider request leaves
// this test. A single long-context request nominates the last article. If the
// provider rejects its size, smaller batches merge their local references.
async function prepareBoard(page, { count = 181, holdRead = false, failRead = false, persistStorage = false, rejectAbove = null, contextWindow = "1000000", aiModel = STORED_SETTINGS.aiModel } = {}) {
  await installNativeBoundary(page, {
    persistStorage,
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "board-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0", hideDuplicateArticles: false, markAsReadOnScroll: false, aiContextWindow: contextWindow, aiModel },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  await expect(page.locator(".article-list-inline-tabs .tabs__list")).toBeVisible();
  await expect.poll(() => page.evaluate(async () => (await import("/src/stores/syncStore.js")).isSyncing.get())).toBe(false);
  await page.evaluate(async ({ count, holdRead, failRead, rejectAbove }) => {
    const db = await import("/src/db/storage.js");
    const now = new Date().toISOString();
    await db.addFeeds([{ id: 1, title: "测试订阅", categoryId: 1, hide_globally: false }]);
    await db.addArticles(Array.from({ length: count }, (_, index) => ({
      id: index + 1, feedId: 1, title: `今日文章 ${index + 1}`,
      content: `<p>这是今日文章 ${index + 1} 的独立原文摘要。</p>`,
      url: `https://example.test/articles/${index + 1}`, status: "unread", starred: false,
      published_at: now, created_at: now,
    })));
    const state = window.__boardTest = { messages: [], reads: [], holdRead, failRead, streams: new Map() };
    const network = window.ToolBox.network;
    const originalRequest = network.request;
    network.request = async (payload) => {
      if (payload.method !== "PUT" || new URL(payload.url).pathname !== "/v1/entries") return originalRequest(payload);
      state.reads.push({ method: payload.method, path: new URL(payload.url).pathname, body: JSON.parse(payload.body) });
      if (state.holdRead) await new Promise((resolve) => { state.releaseRead = resolve; });
      if (state.failRead) throw Object.assign(new Error("Synthetic offline read update"), { code: "NETWORK_UNAVAILABLE" });
      return { status: 204, headers: {}, body: "" };
    };
    network.openStream = async (payload) => {
      const body = JSON.parse(payload.body);
      const { messages } = body;
      const merging = messages[0].content.includes("候选主题");
      const writing = !merging && messages[1].content.startsWith("主题");
      const references = [...messages[1].content.matchAll(/^\[(\d+)\] /gm)].map((match) => Number(match[1]));
      const kind = writing ? "digest" : merging ? "merge" : "articles";
      const rejected = rejectAbove !== null && kind === "articles" && references.length > rejectAbove;
      state.messages.push({ kind, messages, references, rejected, body });
      const streamId = `board-test-${state.messages.length}`;
      if (rejected) {
        state.streams.set(streamId, JSON.stringify({ error: { code: "context_length_exceeded", message: "Synthetic smaller provider context" } }));
        return { status: 400, streamId };
      }
      const reply = writing
        ? `## 全日重点\n这是最后一篇文章的内容。[${count}] 无效来源不应成为链接。[1][99999]`
        : JSON.stringify({ topics: [{
          title: "全日重点", gist: "今天值得关注的事情", summary: "主题来自本次输入的最后一篇文章。",
          articles: [references.at(-1)],
        }] });
      state.streams.set(streamId, `data: ${JSON.stringify({ choices: [{ delta: { content: reply }, finish_reason: "stop" }] })}\n\ndata: [DONE]\n\n`);
      return { status: 200, streamId };
    };
    network.readStream = async (streamId) => {
      const data = state.streams.get(streamId) || "";
      state.streams.delete(streamId);
      return { data: new TextEncoder().encode(data), done: true };
    };
    network.cancelStream = async (streamId) => { state.streams.delete(streamId); };
    location.hash = "#/board";
  }, { count, holdRead, failRead, rejectAbove });
  await expect(page.locator(".nf-board-summary")).toContainText(`今日 ${count} 篇未读已全部参与分析`);
  await expect(page.locator(".nf-board-summary")).toContainText("收录 1 篇文章");
}

test("GPT-6.2 auto context and all AI entry points omit unsupported sampling fields", async ({ page }) => {
  await prepareBoard(page, { count: 181, contextWindow: "auto", aiModel: "gpt-6.2-sol" });
  const board = await page.evaluate(() => window.__boardTest.messages);
  expect(board).toHaveLength(1);
  expect(board[0].references).toHaveLength(181);
  expect(board[0].body.model).toBe("gpt-6.2-sol");
  for (const key of ["temperature", "top_p", "logprobs", "top_logprobs"]) expect(board[0].body).not.toHaveProperty(key);

  const actions = await page.evaluate(async () => {
    const requests = [];
    const streams = new Map();
    let reply = "这是生成的文章摘要。";
    window.ToolBox.network.openStream = async (payload) => {
      const body = JSON.parse(payload.body);
      requests.push(body);
      const id = `gpt62-actions-${requests.length}`;
      // Retaining a sampling parameter reproduces the provider's HTTP 400.
      if (["temperature", "top_p", "logprobs", "top_logprobs"].some(key => key in body)) {
        streams.set(id, JSON.stringify({ error: { code: "unsupported_parameter", param: "temperature" } }));
        return { status: 400, streamId: id };
      }
      streams.set(id, `data: ${JSON.stringify({ choices: [{ delta: { content: reply }, finish_reason: "stop" }] })}\n\ndata: [DONE]\n\n`);
      return { status: 200, streamId: id };
    };
    window.ToolBox.network.readStream = async id => ({ data: new TextEncoder().encode(streams.get(id) || ""), done: true });
    window.ToolBox.network.cancelStream = async id => { streams.delete(id); };
    const api = await import("/src/api/openai.js");
    let summary = "", summaryError = null, summaryDone = false;
    await api.summarizeArticleStream({ title: "测试文章", content: "<p>真实 DOM 提取的文章正文。</p>" }, {
      onChunk: text => { summary += text; }, onDone: () => { summaryDone = true; }, onError: error => { summaryError = error.message; },
    });
    reply = '<scraper_rule>article .content</scraper_rule><confidence>0.9</confidence><reason>正文容器</reason>';
    const { suggestScraperRule } = await import("/src/toolbox/fulltext-adapter/fulltext-ai.mjs");
    const settings = (await import("/src/stores/settingsStore.js")).settingsState.get();
    const rule = await suggestScraperRule({
      article: { title: "测试文章", url: "https://article.example.test/story" }, feed: { title: "测试订阅" },
      structure: "<article><div class=content>文章正文</div></article>", settings,
    });
    return { requests, summary, summaryError, summaryDone, rule: rule.rule, streamsRemaining: streams.size };
  });
  expect(actions.summaryError).toBeNull();
  expect(actions.summaryDone).toBe(true);
  expect(actions.summary).toBe("这是生成的文章摘要。");
  expect(actions.rule).toBe("article .content");
  expect(actions.requests).toHaveLength(2);
  expect(actions.streamsRemaining).toBe(0);
  for (const body of actions.requests) {
    expect(body.model).toBe("gpt-6.2-sol");
    expect(body.stream).toBe(true);
    for (const key of ["temperature", "top_p", "logprobs", "top_logprobs"]) expect(body).not.toHaveProperty(key);
  }
});

test("today's whole input is analyzed and one-click read changes only final board sources", async ({ page }) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await prepareBoard(page, { holdRead: true });
  const input = await page.evaluate(() => window.__boardTest.messages);
  const batches = input.filter((message) => message.kind === "articles");
  expect(batches).toHaveLength(1);
  expect(batches.reduce((total, batch) => total + batch.references.length, 0)).toBe(181);
  const sent = batches.map((batch) => batch.messages[1].content).join("\n");
  for (let id = 1; id <= 181; id += 1) expect(sent).toContain(`今日文章 ${id}\n`);
  expect(input.some((message) => message.kind === "merge")).toBe(false);
  for (const { messages } of input) expect(messages.reduce((sum, message) => sum + new TextEncoder().encode(message.content).length, 0)).toBeLessThanOrEqual(798_976);
  await expect(page.getByRole("tablist", { name: "看板范围" })).toHaveCount(1);
  await expect(page.getByRole("tab", { name: "今日", exact: true })).toHaveAttribute("aria-selected", "true");
  await expect(page.locator(".nf-board-card h3")).toHaveText("全日重点");
  await expect(page.locator(".nf-board-card-head > .nf-board-rank")).toHaveText("1");
  // The last local reference must still resolve to the global oldest ID.
  await expect(page.locator('.nf-board-chip[href="#/board/article/1"]')).toHaveText("测试订阅 · 今日文章 1");
  await expect(page.locator(".nf-board-read-help")).toHaveText("仅处理看板收录的 1 篇，其余未读保留。");
  const mark = page.getByRole("button", { name: "看板文章一键已读", exact: true });
  await mark.click();
  await expect(page.getByRole("button", { name: "正在标为已读…" })).toBeDisabled();
  await expect.poll(() => page.evaluate(() => window.__boardTest.reads.length)).toBe(1);
  await page.evaluate(() => { window.__boardTest.holdRead = false; window.__boardTest.releaseRead(); });
  await expect(page.locator(".nf-board-read-result")).toHaveText("已将 1 篇看板文章标为已读。");
  const result = await page.evaluate(async () => ({
    reads: window.__boardTest.reads,
    rows: await (await import("/src/db/storage.js")).getCachedArticleMetadata(),
  }));
  expect(result.reads).toEqual([{ method: "PUT", path: "/v1/entries", body: { entry_ids: [1], status: "read" } }]);
  expect(result.rows.filter((row) => row.status === "read").map((row) => row.id)).toEqual([1]);
  expect(result.rows.filter((row) => row.status === "unread")).toHaveLength(180);
  // A second click is harmless and does not resend an already-read source.
  await mark.click();
  await expect(page.locator(".nf-board-read-result")).toHaveText("看板收录的文章已经是已读。");
  expect(await page.evaluate(() => window.__boardTest.reads.length)).toBe(1);
  // Writing after marking still uses the frozen source text and its original
  // number. Out-of-board and out-of-range references never become links.
  const complete = page.getByRole("button", { name: "完整报告", exact: true });
  await expect(complete).toHaveAttribute("aria-expanded", "false");
  await expect(page.locator(".nf-board-digest")).toHaveCount(0);
  await complete.click();
  await expect(complete).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByRole("button", { name: "生成完整报告", exact: true })).toBeVisible();
  await complete.click();
  await complete.click();
  expect(await page.evaluate(() => window.__boardTest.messages.length)).toBe(input.length);
  await page.getByRole("button", { name: "生成完整报告", exact: true }).click();
  await expect(page.locator(".nf-board-digest h4 > span:last-child")).toHaveText("全日重点");
  await expect(page.locator(".nf-board-digest h4 > .nf-board-rank")).toHaveText("1");
  await expect(page.locator(".nf-board-digest .nf-board-cite")).toHaveCount(1);
  await expect(page.locator(".nf-board-digest .nf-board-cite")).toHaveAttribute("href", "#/board/article/1");
  const digestInput = await page.evaluate(() => window.__boardTest.messages.find((message) => message.kind === "digest").messages);
  expect(digestInput[1].content).toContain("[181]");
  expect(digestInput[1].content).toContain("这是今日文章 1 的独立原文摘要。");
  const completedCalls = await page.evaluate(() => window.__boardTest.messages.length);
  await complete.click();
  await complete.click();
  await expect(page.locator(".nf-board-digest h4 > span:last-child")).toHaveText("全日重点");
  expect(await page.evaluate(() => window.__boardTest.messages.length)).toBe(completedCalls);
  expect(errors).toEqual([]);
});

test("a thousand-article overview uses one long-context request in the production app", async ({ page }) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await prepareBoard(page, { count: 1000 });
  const calls = await page.evaluate(() => window.__boardTest.messages);
  expect(calls).toHaveLength(1);
  expect(calls[0].kind).toBe("articles");
  expect(calls[0].references).toHaveLength(1000);
  await expect(page.locator('.nf-board-chip[href="#/board/article/1"]')).toHaveText("测试订阅 · 今日文章 1");
  await expect(page.locator(".nf-board-read-help")).toHaveText("仅处理看板收录的 1 篇，其余未读保留。");
  expect(errors).toEqual([]);
});

test("a provider context rejection splits only pending input and still maps the final source correctly", async ({ page }) => {
  await prepareBoard(page, { rejectAbove: 100 });
  const calls = await page.evaluate(() => window.__boardTest.messages);
  expect(calls.map((call) => ({ kind: call.kind, size: call.references.length, rejected: call.rejected }))).toEqual([
    { kind: "articles", size: 181, rejected: true },
    { kind: "articles", size: 91, rejected: false },
    { kind: "articles", size: 90, rejected: false },
    { kind: "merge", size: 2, rejected: false },
  ]);
  await expect(page.locator('.nf-board-chip[href="#/board/article/1"]')).toBeVisible();
  await page.getByRole("button", { name: "看板文章一键已读", exact: true }).click();
  await expect(page.locator(".nf-board-read-result")).toContainText("已将 1 篇");
  expect(await page.evaluate(() => window.__boardTest.reads.map((read) => read.body))).toEqual([{ entry_ids: [1], status: "read" }]);
});

test("context capacity uses the settings menu, saves its draft, and does not regenerate a finished overview", async ({ page }, testInfo) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await prepareBoard(page, { contextWindow: "auto" });
  const before = await page.evaluate(() => window.__boardTest.messages.length);
  await page.evaluate(async () => (await import("/src/stores/modalStore.js")).settingsModalOpen.set(true));
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: /AI/ }).first().click();
  const context = dialog.getByRole("button").filter({ hasText: "模型上下文" });
  await expect(context).toContainText("未识别模型");
  await context.click();
  await expect(page.getByRole("menu", { name: /模型上下文/ })).toBeVisible();
  await expect(page.getByRole("menuitemradio")).toHaveCount(6);
  await page.getByRole("menuitemradio", { name: "1M", exact: true }).click();
  await expect(context).toContainText("按 1M tokens");
  expect(await page.evaluate(async () => (await import("/src/stores/settingsStore.js")).settingsState.get().aiContextWindow)).toBe("auto");
  await dialog.getByRole("button", { name: /保存|Save/ }).click();
  await expect.poll(() => page.evaluate(() => JSON.parse(window.__startupNative.persistedPreferences().settings).aiContextWindow)).toBe("1000000");
  await context.scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath("ai-context-settings-phone.png") });
  await dialog.getByRole("button", { name: /关闭|Close/ }).first().click();
  await expect(page.locator(".nf-board-summary")).toContainText("今日 181 篇未读已全部参与分析");
  expect(await page.evaluate(() => window.__boardTest.messages.length)).toBe(before);
  expect(errors).toEqual([]);
});

test("today restores its saved report after reload and does not regenerate while switching history", async ({ page }) => {
  await prepareBoard(page, { count: 12, persistStorage: true });
  const heading = await page.locator(".nf-board-card h3").textContent();
  await expect.poll(() => page.evaluate(async () => (await import("/src/toolbox/board/historyStore.js")).reportHistory.get().reports.length)).toBe(1);
  await page.reload();
  expect(await page.evaluate(() => typeof window.__boardTest)).toBe("undefined");
  await expect(page.locator(".nf-board-card h3")).toHaveText(heading);
  const today = page.getByRole("tab", { name: "今日", exact: true });
  const history = page.getByRole("tab", { name: /历史报告/ });
  await expect(history).toHaveAttribute("aria-selected", "true");
  await page.evaluate(() => {
    window.__reloadedAiCalls = [];
    window.ToolBox.network.openStream = async (payload) => {
      window.__reloadedAiCalls.push(payload.url);
      throw new Error("Opening a saved daily report must not request AI");
    };
  });
  for (let visit = 0; visit < 2; visit += 1) {
    await today.click();
    await expect(today).toHaveAttribute("aria-selected", "true");
    await expect(page.locator(".nf-board-card h3")).toHaveText(heading);
    await expect(page.locator(".nf-board-summary")).toContainText("今日 12 篇未读已全部参与分析");
    await expect(page.locator(".nf-board-inline-error")).toHaveCount(0);
    expect(await page.evaluate(() => window.__reloadedAiCalls)).toEqual([]);
    if (visit === 0) {
      await history.click();
      await expect(history).toHaveAttribute("aria-selected", "true");
    }
  }
});

test("a failed read update remains retryable without marking omitted articles", async ({ page }) => {
  await prepareBoard(page, { count: 12, failRead: true });
  const mark = page.getByRole("button", { name: "看板文章一键已读", exact: true });
  await mark.click();
  await expect(page.locator(".nf-board-read-action [role=alert]")).toBeVisible();
  await expect(mark).toBeEnabled();
  expect(await page.evaluate(async () => (await (await import("/src/db/storage.js")).getCachedArticleMetadata({ filter: "unread" })).length)).toBe(12);
  await page.evaluate(() => { window.__boardTest.failRead = false; });
  await mark.click();
  await expect(page.locator(".nf-board-read-result")).toHaveText("已将 1 篇看板文章标为已读。");
  expect(await page.evaluate(async () => (await (await import("/src/db/storage.js")).getCachedArticleMetadata({ filter: "unread" })).length)).toBe(11);
});

test("board controls fit a narrow phone with enlarged text and show batch progress", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await prepareBoard(page, { count: 12 });
  await page.evaluate(() => { document.documentElement.style.fontSize = "24px"; });
  const mark = page.getByRole("button", { name: "看板文章一键已读", exact: true });
  await expect(mark).toBeVisible();
  const layout = await page.evaluate(() => {
    const button = document.querySelector(".nf-board-read-action button").getBoundingClientRect();
    const helper = document.querySelector(".nf-board-read-help").getBoundingClientRect();
    return { width: innerWidth, button: { left: button.left, right: button.right, bottom: button.bottom }, helperTop: helper.top, overflow: document.documentElement.scrollWidth > innerWidth + 1 };
  });
  expect(layout.overflow).toBe(false);
  expect(layout.button.left).toBeGreaterThanOrEqual(0);
  expect(layout.button.right).toBeLessThanOrEqual(layout.width);
  expect(layout.helperTop).toBeGreaterThanOrEqual(layout.button.bottom);
  await page.evaluate(async () => {
    const { board, boardStream } = await import("/src/toolbox/board/boardStore.js");
    board.set({ ...board.get(), updating: true });
    boardStream.set({ stage: "articles", batchIndex: 2, batchCount: 3, processed: 150, total: 400,
      sent: [{ title: "下一批文章", feedTitle: "测试订阅" }], thinking: "", text: "", skipped: 0 });
  });
  await expect(mark).toBeDisabled();
  await expect(page.locator(".nf-board-live-head h3").first()).toHaveText("第 2 / 3 批 · 1 篇未读");
  await expect(page.locator(".nf-board-live-progress")).toHaveText("今日共 400 篇未读，已分析 150 篇。");
  await page.evaluate(async () => {
    const { boardStream } = await import("/src/toolbox/board/boardStore.js");
    boardStream.set({ ...boardStream.get(), stage: "merge", batchIndex: 1, batchCount: 1, processed: 400, round: 2,
      sent: [{ title: "待归并的主题", feedTitle: "" }] });
  });
  await expect(page.locator(".nf-board-live-head h3").first()).toHaveText("第 1 / 1 批 · 1 个候选主题");
  await expect(page.locator(".nf-board-live-progress")).toHaveText("今日 400 篇未读已分析，正在合并主题（第 2 轮）。");
});
