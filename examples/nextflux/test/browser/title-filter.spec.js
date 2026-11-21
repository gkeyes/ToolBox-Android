import { test as base, expect } from "@playwright/test";
import { PRODUCTION_URL, STORED_SETTINGS, installNativeBoundary } from "./fixtures/startup-native.js";

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

// Phones reach low-frequency reader actions through the dock's "More" sheet;
// wide screens show them inline. Press whichever surface the layout offers.
async function readerAction(page, name) {
  const direct = page.getByRole("button", { name, exact: true });
  const more = page.getByRole("button", { name: "更多", exact: true });
  await expect(direct.or(more).first()).toBeVisible();
  if (await direct.isVisible()) return direct.click();
  await page.getByRole("button", { name: "更多", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name, exact: true }).click();
  // Phones have no hover. Left in place, the mouse ends up over a dock button
  // once the compact sheet closes and opens its desktop tooltip, which would
  // take the next Escape meant for the editor.
  await page.mouse.move(0, 0);
  await expect(page.getByRole("dialog").filter({ hasText: "这篇文章" })).toHaveCount(0);
}
const editor = async page => {
  await readerAction(page, "标题过滤");
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
  // Wait for the reader to replace the list header before reaching its controls.
  await expect(page.locator(".action-buttons")).toBeVisible();
  await expect(page.locator("header.article-list-header")).toHaveCount(0);
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
    expect(geometry.top).toBeGreaterThan(425); expect(geometry.bottom).toBeLessThanOrEqual(geometry.viewport - 16);
    expect(geometry.left).toBeGreaterThanOrEqual(0); expect(geometry.right).toBeLessThanOrEqual(width); expect(geometry.overflow).toBe(false);
    if (width === 393 && !dark) await info.attach("title-filter-bottom-toast.png", { body: await page.screenshot(), contentType: "image/png" });
  });
}

test("late reader request cannot show an old editor or write another source", async ({ page }) => {
  await open(page);
  await page.evaluate(() => filterFixture.mode("slowRead"));
  await readerAction(page, "标题过滤");
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
  await readerAction(page, "标题过滤");
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

// Regression: nothing may stay over the page after the editor closes, and a
// drag dismissal the editor refuses (pending save) must bring the sheet back
// instead of parking it off-screen above a live backdrop that eats every tap.
async function expectUncovered(page) {
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => {
    const points = [[innerWidth / 2, innerHeight / 2], [24, 160], [innerWidth - 24, 160], [innerWidth / 2, innerHeight - 160]];
    return points.flatMap(([x, y]) => {
      const hit = document.elementFromPoint(x, y);
      const cover = hit?.closest(".drawer__backdrop, .modal__backdrop, .nextflux-modal-backdrop, [data-slot='backdrop'], [inert], [role='dialog']");
      return !hit || cover ? [`${x},${y}`] : [];
    }).concat(document.querySelector("#root[inert], #root[aria-hidden='true']") ? ["root"] : []);
  })).toEqual([]);
}
async function dragSheetDown(page, distance = 260) {
  const box = await page.getByRole("dialog").locator(".nextflux-sheet-handle-zone").boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + distance, { steps: 4 });
  await page.mouse.up();
  // The release point can sit over a dock button once the sheet is gone; a
  // phone has no hover, so do not leave a desktop tooltip open there.
  await page.mouse.move(0, 0);
}

test("More sheet hands off to the editor and every close path frees the page", async ({ page }) => {
  await open(page);
  await editor(page);
  await page.getByRole("dialog").locator(".nextflux-close-button").dispatchEvent("click");
  await page.mouse.move(0, 0); // the dock under a closed sheet must not keep a hover tooltip
  await expectUncovered(page);
  await editor(page);
  await dragSheetDown(page);
  await expectUncovered(page);
  await editor(page);
  await page.keyboard.press("Escape");
  await expectUncovered(page);
  // A real tap still reaches the reader.
  await page.getByRole("button", { name: "更多", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expectUncovered(page);
});

test("a drag dismissal refused by a pending save returns the sheet", async ({ page }) => {
  await open(page);
  const dialog = await editor(page);
  await page.getByLabel("屏蔽关键词", { exact: true }).fill("mine");
  const restingTop = (await dialog.boundingBox()).y;
  await page.evaluate(() => filterFixture.mode("pauseWrite"));
  await save(page);
  await expect.poll(() => page.evaluate(() => filterFixture.calls.filter(call => call.method === "PUT").length)).toBe(1);
  await dragSheetDown(page);
  await expect(dialog).toBeVisible();
  await expect.poll(async () => {
    const box = await dialog.boundingBox();
    const owned = await dialog.evaluate(element => ({ translate: element.style.translate, animations: element.getAnimations().length }));
    return { settled: Boolean(box) && Math.abs(box.y - restingTop) < 24, ...owned };
  }).toEqual({ settled: true, translate: "", animations: 0 });
  await page.evaluate(() => filterFixture.release());
  await expect(page.locator(".nf-title-filter-toast")).toContainText("已保存 1");
  await expectUncovered(page);
  expect(await page.evaluate(() => filterFixture.calls.filter(call => call.method === "PUT").length)).toBe(1);
});

// Exercise the real reader entry point, matcher, adapter, account queue and
// cache. Only the ToolBox native network/storage boundary is synthetic.
const entry = (id, title, fields = {}) => ({ id, title, feed_id: 42, status: "unread", starred: false, ...fields });

async function openBackfill(page, { entries, cached = [], rules = "", failWrite = 0, viewport } = {}) {
  if (viewport) await page.setViewportSize(viewport);
  await page.emulateMedia({ reducedMotion: "reduce" });
  const serverUrl = "https://miniflux.xiaochen.win";
  await installNativeBoundary(page, {
    auth: { serverUrl, username: "filter-test", userId: 7, token: "fixture-token", authType: "token" },
    settings: { ...STORED_SETTINGS, syncInterval: "0", markAsReadOnScroll: false, autoBoardEnabled: false },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl, userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  await expect(page.locator(".article-list-inline-tabs .tabs__list")).toBeVisible();
  await expect.poll(() => page.evaluate(async () => (await import("/src/stores/syncStore.js")).isSyncing.get())).toBe(false);
  await page.evaluate(async ({ entries, cached, rules, failWrite }) => {
    const db = await import("/src/db/storage.js");
    const feed = { id: 42, title: "测试订阅", categoryId: 1, block_filter_entry_rules: rules };
    const other = { id: 43, title: "另一条订阅", categoryId: 1, block_filter_entry_rules: "EntryTitle=推广" };
    await db.addFeeds([feed, other]);
    const now = new Date().toISOString();
    if (cached.length) await db.addArticles(entries.filter((article) => cached.includes(article.id)).map((article) => ({
      ...article, feedId: article.feed_id, url: `https://example.test/backfill/${article.id}`,
      content: "<p>来自本机缓存的预览。</p>", created_at: now, published_at: now,
    })));
    (await import("/src/stores/feedsStore.js")).feeds.set([feed, other]);
    const state = window.__titleBackfill = {
      entries, feeds: { 42: feed, 43: other }, scans: [], writes: [], ruleWrites: [], unexpected: [], failWrite,
    };
    const reply = (body, status = 200) => ({ status, headers: {}, body: body === undefined ? "" : JSON.stringify(body) });
    window.ToolBox.network.request = async ({ url, method, body }) => {
      const target = new URL(url);
      const match = target.pathname.match(/^\/v1\/feeds\/(\d+)(\/entries)?$/);
      if (match) {
        const [, id, scans] = match;
        if (!state.feeds[id]) return reply({}, 404);
        if (!scans && method === "GET") return reply(state.feeds[id]);
        if (!scans && method === "PUT") {
          const patch = JSON.parse(body);
          state.ruleWrites.push({ id: Number(id), patch });
          Object.assign(state.feeds[id], patch);
          return reply(state.feeds[id], 201);
        }
        if (scans && method === "GET") {
          const parameters = Object.fromEntries(target.searchParams);
          state.scans.push({ feedId: Number(id), ...parameters });
          const unread = state.entries.filter((article) =>
            article.feed_id === Number(id) && article.status === "unread" &&
            (!parameters.before_entry_id || article.id < Number(parameters.before_entry_id)) &&
            (parameters.starred === undefined || article.starred === (parameters.starred === "true")));
          unread.sort((a, b) => b.id - a.id);
          return reply({ total: unread.length, entries: unread.slice(0, Number(parameters.limit) || 200) });
        }
      }
      if (method === "GET" && /^\/v1\/entries\/\d+$/.test(target.pathname)) {
        const article = state.entries.find((value) => value.id === Number(target.pathname.split("/").at(-1)));
        return article ? reply(article) : reply({}, 404);
      }
      if (method === "GET" && /^\/v1\/feeds\/\d+\/icon$/.test(target.pathname)) return reply({}, 404);
      if (method === "PUT" && target.pathname === "/v1/entries") {
        const update = JSON.parse(body);
        state.writes.push(update);
        if (state.writes.length === state.failWrite) {
          throw Object.assign(new Error("Synthetic disconnected status write"), { code: "NETWORK_UNAVAILABLE" });
        }
        for (const article of state.entries) if (update.entry_ids.includes(article.id)) article.status = update.status;
        return reply(undefined, 204);
      }
      state.unexpected.push({ path: target.pathname, method });
      return reply({}, 404);
    };
    location.hash = "#/feed/42";
  }, { entries, cached, rules, failWrite });
  return editor(page);
}

base("saving a title rule previews remote unread and applies only its revalidated candidates", async ({ page }) => {
  const dialog = await openBackfill(page, {
    viewport: { width: 926, height: 428 },
    rules: "EntryContent=正文词",
    cached: [2, 4, 5],
    entries: [
      entry(1, "普通报道"),
      entry(2, "推广：手机有缓存"),
      entry(3, "推广：手机从未缓存"),
      entry(4, "推广：已经收藏", { starred: true }),
      entry(5, "推广：另一订阅", { feed_id: 43 }),
      entry(6, "推广：早已看过", { status: "read" }),
      entry(7, "推广：预览后收藏"),
      entry(8, "推广：预览后改标题"),
    ],
  });
  await page.getByLabel("屏蔽关键词", { exact: true }).fill("推广");
  await save(page);
  await expect.poll(() => page.evaluate(() => __titleBackfill.ruleWrites.length)).toBe(1);
  await expect(dialog).toContainText("规则已保存。");
  await expect(dialog.getByText("推广：手机从未缓存", { exact: true })).toBeVisible();
  await expect(dialog).toContainText(/扫描\D*6\D+命中\D*4\D+跳过\D*1/);
  await expect(dialog).toContainText("正文屏蔽规则");
  await expect(dialog.getByText("推广：已经收藏", { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => __titleBackfill.writes)).toEqual([]);
  expect(await page.evaluate(async () => (await import("/src/db/storage.js")).getArticleIds({ ids: [3] }))).toEqual([]);
  await page.evaluate(() => {
    const { entries } = __titleBackfill;
    entries.find(({ id }) => id === 7).starred = true;
    entries.find(({ id }) => id === 8).title = "普通更正";
    entries.push({ id: 9, feed_id: 42, title: "推广：预览后抵达", status: "unread", starred: false });
  });
  await dialog.getByRole("button", { name: "将 4 篇标为已读", exact: true }).click();
  await expect(dialog).toContainText(/已将\s*2\s*篇标为已读/);
  const result = await page.evaluate(async () => {
    const state = __titleBackfill;
    const { getArticleIds } = await import("/src/db/storage.js");
    return {
      reads: state.entries.filter((article) => article.status === "read").map(({ id }) => id),
      localUnread: await getArticleIds({ ids: [2, 4, 5], filter: "unread" }),
      writes: state.writes,
      scans: state.scans,
      ruleWrites: state.ruleWrites,
      unexpected: state.unexpected,
    };
  });
  expect(result.writes).toHaveLength(1);
  expect(result.writes[0].status).toBe("read");
  expect(result.writes[0].entry_ids.sort((a, b) => a - b)).toEqual([2, 3]);
  expect(result.reads).toEqual([2, 3, 6]);
  expect(result.localUnread.sort((a, b) => a - b)).toEqual([4, 5]);
  expect(result.scans.length).toBeGreaterThan(1);
  expect(result.scans.every(({ feedId, status }) => feedId === 42 && status === "unread")).toBe(true);
  expect(result.ruleWrites).toEqual([{ id: 42, patch: { block_filter_entry_rules: `EntryContent=正文词\n${managed("推广")}` } }]);
  expect(result.unexpected).toEqual([]);
});

base("an existing Miniflux title regex can be scanned but stale preview cannot write", async ({ page }) => {
  const dialog = await openBackfill(page, {
    rules: String.raw`EntryTitle=(?i:^sale\s+\d+%)`,
    entries: [entry(1, "SALE 20% for today"), entry(2, "Research memo"), entry(3, "Editorial roundup")],
  });
  await dialog.getByRole("button", { name: "扫描现有未读", exact: true }).click();
  await expect(dialog.getByText("SALE 20% for today", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => [__titleBackfill.ruleWrites, __titleBackfill.writes])).toEqual([[], []]);
  await page.evaluate(() => { __titleBackfill.feeds[42].block_filter_entry_rules = "EntryTitle=^Research"; });
  await dialog.getByRole("button", { name: "将 1 篇标为已读", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText(/规则.*变|规则.*更新/);
  expect(await page.evaluate(() => __titleBackfill.writes)).toEqual([]);
  await dialog.getByRole("button", { name: "重新扫描", exact: true }).click();
  await expect(dialog.getByText("Research memo", { exact: true })).toBeVisible();
  await dialog.getByRole("button", { name: "将 1 篇标为已读", exact: true }).click();
  await expect(dialog).toContainText(/已将\s*1\s*篇标为已读/);
  expect(await page.evaluate(() => [__titleBackfill.ruleWrites, __titleBackfill.writes])).toEqual([[], [{ entry_ids: [2], status: "read" }]]);
});

base("retry after a disconnected batch preserves acknowledged progress and rescans the remainder", async ({ page }) => {
  const dialog = await openBackfill(page, {
    rules: "EntryTitle=^测试推广",
    entries: Array.from({ length: 257 }, (_, index) => entry(index + 1, `测试推广 ${index + 1}`)),
    failWrite: 2,
  });
  await dialog.getByRole("button", { name: "扫描现有未读", exact: true }).click();
  await dialog.getByRole("button", { name: "将 257 篇标为已读", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText("256");
  const beforeRetry = await page.evaluate(() => ({ writes: __titleBackfill.writes, scans: __titleBackfill.scans.length }));
  expect(beforeRetry.writes.map(({ entry_ids }) => entry_ids.length)).toEqual([256, 1]);
  await dialog.getByRole("button", { name: "重试标记", exact: true }).click();
  await expect.poll(() => page.evaluate(() => __titleBackfill.writes.length)).toBe(3);
  const afterRetry = await page.evaluate(() => ({
    writes: __titleBackfill.writes,
    scans: __titleBackfill.scans,
    unread: __titleBackfill.entries.filter(({ status }) => status === "unread").length,
  }));
  expect(afterRetry.writes[2]).toEqual(afterRetry.writes[1]);
  expect(afterRetry.scans.length).toBeGreaterThan(beforeRetry.scans);
  expect(afterRetry.scans.some(({ before_entry_id }) => Boolean(before_entry_id))).toBe(true);
  expect(afterRetry.unread).toBe(0);
});
