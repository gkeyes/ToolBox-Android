import { test, expect } from "@playwright/test";
import { PRODUCTION_URL, STORED_SETTINGS, installNativeBoundary } from "./fixtures/startup-native.js";

// Exercise the production board, cache, account queue and Miniflux adapter.
// Only the native network boundary is synthetic; no AI/provider request leaves
// this test. Each analysis batch nominates its last article, then the merge
// keeps only its last candidate. Earlier candidates must remain unread too.
async function prepareBoard(page, { count = 181, holdRead = false, failRead = false } = {}) {
  await installNativeBoundary(page, {
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "board-test-user", password: "", userId: 7,
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
  await page.evaluate(async ({ count, holdRead, failRead }) => {
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
      const { messages } = JSON.parse(payload.body);
      const merging = messages[0].content.includes("候选主题");
      const writing = !merging && messages[1].content.startsWith("主题");
      const references = [...messages[1].content.matchAll(/^\[(\d+)\] /gm)].map((match) => Number(match[1]));
      const kind = writing ? "digest" : merging ? "merge" : "articles";
      state.messages.push({ kind, messages, references });
      const reply = writing
        ? `## 全日重点\n这是最后一篇文章的内容。[${count}] 无效来源不应成为链接。[1][99999]`
        : JSON.stringify({ topics: [{
          title: merging ? "全日重点" : "批次重点", gist: "今天值得关注的事情", summary: "主题来自本批最后一篇文章。",
          articles: [references.at(-1)],
        }] });
      const streamId = `board-test-${state.messages.length}`;
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
  }, { count, holdRead, failRead });
  await expect(page.locator(".nf-board-summary")).toContainText(`今日 ${count} 篇未读已全部参与分析`);
  await expect(page.locator(".nf-board-summary")).toContainText("收录 1 篇文章");
}

test("today's whole input is analyzed and one-click read changes only final board sources", async ({ page }) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await prepareBoard(page, { holdRead: true });
  const input = await page.evaluate(() => window.__boardTest.messages);
  const batches = input.filter((message) => message.kind === "articles");
  expect(batches.length).toBeGreaterThan(1);
  expect(batches.reduce((total, batch) => total + batch.references.length, 0)).toBe(181);
  const sent = batches.map((batch) => batch.messages[1].content).join("\n");
  for (let id = 1; id <= 181; id += 1) expect(sent).toContain(`今日文章 ${id}\n`);
  expect(input.some((message) => message.kind === "merge")).toBe(true);
  for (const { messages } of input) expect(messages.reduce((sum, message) => sum + message.content.length, 0)).toBeLessThanOrEqual(24_000);
  // The final batch's local reference must resolve to the global oldest ID.
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
  await page.getByRole("tab", { name: "文章", exact: true }).click();
  await expect(page.locator(".nf-board-digest h4")).toHaveText("全日重点");
  await expect(page.locator(".nf-board-digest .nf-board-cite")).toHaveCount(1);
  await expect(page.locator(".nf-board-digest .nf-board-cite")).toHaveAttribute("href", "#/board/article/1");
  const digestInput = await page.evaluate(() => window.__boardTest.messages.find((message) => message.kind === "digest").messages);
  expect(digestInput[1].content).toContain("[181]");
  expect(digestInput[1].content).toContain("这是今日文章 1 的独立原文摘要。");
  expect(errors).toEqual([]);
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
