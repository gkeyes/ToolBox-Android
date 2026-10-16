import { test, expect } from "@playwright/test";
import {
  AI_SECRET, PRODUCTION_URL, SPEECH_SECRET, STORED_CATEGORY_EXPANDED, STORED_SETTINGS,
  STORED_THEME, UPDATED_AI_SECRET, installNativeBoundary,
} from "./fixtures/startup-native.js";

test("production startup restores persisted settings and secure keys without writing", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await installNativeBoundary(page);
  await page.goto(PRODUCTION_URL);
  await expect(page).toHaveURL(/#\/login$/);
  await expect(page.getByRole("heading", { name: "NextFlux" })).toBeVisible();
  await expect(page.getByRole("button", { name: "登 录" })).toBeVisible();
  await expect(page.locator(".toolbox-start-error")).toHaveCount(0);

  const restored = await page.evaluate(async () => {
    const [{ settingsState }, { getPreference }] = await Promise.all([
      import("/src/stores/settingsStore.js"),
      import("/src/toolbox/preferences.js"),
    ]);
    await window.__startupNative.flush();
    return {
      settings: settingsState.get(),
      preference: JSON.parse(getPreference("settings")),
      theme: JSON.parse(getPreference("theme")),
      categoryExpanded: JSON.parse(getPreference("categoryExpanded")),
      persistedPreferences: window.__startupNative.persistedPreferences(),
      reads: window.__startupNative.reads,
      writes: window.__startupNative.writes,
      network: window.__startupNative.network,
    };
  });
  expect(restored.preference).toMatchObject({
    ...STORED_SETTINGS,
    aiApiKey: AI_SECRET,
    speechApiKey: SPEECH_SECRET,
  });
  expect(restored.settings).toMatchObject({
    ...STORED_SETTINGS,
    aiApiKey: AI_SECRET,
    speechApiKey: SPEECH_SECRET,
  });
  expect(restored.theme).toEqual(STORED_THEME);
  expect(restored.categoryExpanded).toEqual(STORED_CATEGORY_EXPANDED);
  expect(JSON.parse(restored.persistedPreferences.settings)).toEqual(STORED_SETTINGS);
  expect(restored.persistedPreferences.settings).not.toContain(AI_SECRET);
  expect(restored.persistedPreferences.settings).not.toContain(SPEECH_SECRET);
  expect(restored.reads).toEqual(expect.arrayContaining([
    "storage.get:nextflux.preferences.v1",
    "secure.get:nextflux.ai-key.v1",
    "secure.get:nextflux.speech-key.v1",
    "secure.get:nextflux.auth",
  ]));
  expect(restored.writes).toEqual([]);
  expect(restored.network).toEqual([]);
  expect(pageErrors).toEqual([]);

  const updated = await page.evaluate(async ({ fontSize, aiApiKey }) => {
    const { settingsState, updateSettings } = await import("/src/stores/settingsStore.js");
    await updateSettings({ fontSize, aiApiKey });
    await window.__startupNative.flush();
    return {
      settings: settingsState.get(),
      persistedPreferences: window.__startupNative.persistedPreferences(),
      aiSecret: await window.ToolBox.storage.secure.get("nextflux.ai-key.v1"),
      speechSecret: await window.ToolBox.storage.secure.get("nextflux.speech-key.v1"),
      writes: window.__startupNative.writes,
      network: window.__startupNative.network,
    };
  }, { fontSize: 22, aiApiKey: UPDATED_AI_SECRET });
  expect(updated.settings).toMatchObject({
    ...STORED_SETTINGS,
    fontSize: 22,
    aiApiKey: UPDATED_AI_SECRET,
    speechApiKey: SPEECH_SECRET,
  });
  expect(updated.aiSecret).toBe(UPDATED_AI_SECRET);
  expect(updated.speechSecret).toBe(SPEECH_SECRET);
  expect(JSON.parse(updated.persistedPreferences.settings)).toEqual({ ...STORED_SETTINGS, fontSize: 22 });
  const ordinaryStorage = JSON.stringify(updated.persistedPreferences);
  expect(ordinaryStorage).not.toContain(AI_SECRET);
  expect(ordinaryStorage).not.toContain(UPDATED_AI_SECRET);
  expect(ordinaryStorage).not.toContain(SPEECH_SECRET);
  expect(updated.writes).toEqual([
    { operation: "secure.set", key: "nextflux.ai-key.v1" },
    {
      operation: "storage.set", key: "nextflux.preferences.v1",
      changedPreferenceKeys: ["settings"], changedSettingsFields: ["fontSize"], plainSecretLeak: false,
    },
  ]);
  expect(updated.network).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test("secure-storage permission denial reports the denied permission, not device space", async ({ page }) => {
  await installNativeBoundary(page, { denySecure: true });
  await page.goto(PRODUCTION_URL);
  const startupError = page.locator(".toolbox-start-error");
  await expect(startupError).toBeVisible();
  const message = await startupError.locator("p").innerText();
  expect(message).toContain("安全存储");
  expect(message).not.toContain("设备剩余空间");
  expect(message).not.toContain("NATIVE_INTERNAL_DETAILS");
  const writes = await page.evaluate(() => window.__startupNative.writes);
  expect(writes).toEqual([]);
});

test("an internal error restoring authentication is identified without blaming permissions or space", async ({ page }) => {
  await installNativeBoundary(page, { failAuthRead: true });
  await page.goto(PRODUCTION_URL);
  const startupError = page.locator(".toolbox-start-error");
  await expect(startupError).toBeVisible();
  const message = await startupError.locator("p").innerText();
  expect(message).toContain("登录信息");
  expect(message).toContain("INTERNAL_ERROR");
  expect(message).not.toContain("权限");
  expect(message).not.toContain("设备剩余空间");
  expect(message).not.toContain("NATIVE_INTERNAL_DETAILS");
  const native = await page.evaluate(() => ({
    reads: window.__startupNative.reads,
    writes: window.__startupNative.writes,
  }));
  expect(native.reads).toContain("secure.get:nextflux.auth");
  expect(native.writes).toEqual([]);
});

// The list header floats three pieces (sidebar button, filter capsule, actions)
// over the list. On narrow screens, large display sizes or large text with a
// long unread count, the capsule must step down (or scroll) instead of
// running under them.
for (const [width, scale] of [[300, 1], [320, 1.3], [320, 2], [320, 3], [360, 1], [393, 1.5], [430, 1]]) {
  test(`list header pieces stay apart at ${width}px, text x${scale}`, async ({ page }) => {
    const pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await page.setViewportSize({ width, height: 800 });
    await installNativeBoundary(page, {
      auth: {
        serverUrl: "https://miniflux.xiaochen.win", username: "layout-test-user", password: "", userId: 7,
        token: "fixed-synthetic-test-token", authType: "token",
      },
      settings: { ...STORED_SETTINGS, syncInterval: "0" },
      cacheRoot: {
        version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
        transaction: null, lastSyncTime: null,
        tables: { articles: null, feeds: null, categories: null, feedIcons: null },
      },
    });
    await page.goto(PRODUCTION_URL);
    await expect(page.locator(".article-list-inline-tabs .tabs__list")).toBeVisible();
    await page.evaluate((scale) => {
      document.documentElement.style.fontSize = `${16 * scale}px`;
      for (const count of document.querySelectorAll(".article-tab-count")) count.textContent = "4262";
    }, scale);
    await expect(page.locator(".article-list-inline-tabs")).toHaveAttribute("data-fit", /^(full|compact|tight|small|minimal|bare)$/);
    await page.waitForTimeout(100);
    const layout = await page.evaluate(() => {
      const box = (selector) => {
        const rect = document.querySelector(selector).getBoundingClientRect();
        return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom };
      };
      const list = document.querySelector(".article-list-inline-tabs .tabs__list");
      return {
        trigger: box('.article-list-header > [data-sidebar="trigger"]'),
        list: box(".article-list-inline-tabs .tabs__list"),
        actions: box(".article-list-header .article-list-actions"),
        clipped: list.scrollWidth > list.clientWidth + 1,
        scrollable: getComputedStyle(list).overflowX === "auto",
        overflow: document.documentElement.scrollWidth > innerWidth + 1,
      };
    });
    const apart = (a, b) => a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top;
    expect(apart(layout.list, layout.trigger)).toBe(true);
    expect(apart(layout.list, layout.actions)).toBe(true);
    expect(layout.list.left).toBeGreaterThanOrEqual(0);
    expect(layout.list.right).toBeLessThanOrEqual(width);
    expect(layout.actions.right).toBeLessThanOrEqual(width);
    // Past the smallest density the capsule scrolls instead of hiding a tab.
    if (layout.clipped) expect(layout.scrollable).toBe(true);
    expect(layout.overflow).toBe(false);
    expect(pageErrors).toEqual([]);
  });
}

// The smart-view top bar is one row whichever tab is selected and however
// large the counts: sidebar, filter capsule, actions (NextFlux 1.15.2).
for (const [width, scale, count] of [[400, 1, "5,203"], [320, 1, "5,203"], [320, 1, "12,345"], [390, 1, "5,203"], [390, 1, "12,345"], [320, 1.3, "12,345"], [390, 1.5, "12,345"], [320, 2, "12,345"]]) {
  for (const tab of ["收藏", "未读"]) test(`top bar stays one row at ${width}px, text x${scale}, ${count}, ${tab} selected`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await installNativeBoundary(page, {
      auth: {
        serverUrl: "https://miniflux.xiaochen.win", username: "layout-test-user", password: "", userId: 7,
        token: "fixed-synthetic-test-token", authType: "token",
      },
      settings: { ...STORED_SETTINGS, syncInterval: "0" },
      cacheRoot: {
        version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
        transaction: null, lastSyncTime: null,
        tables: { articles: null, feeds: null, categories: null, feedIcons: null },
      },
    });
    await page.goto(PRODUCTION_URL);
    await expect(page.locator(".article-list-inline-tabs .tabs__list")).toBeVisible();
    await page.locator(".article-list-inline-tabs .tabs__tab", { hasText: tab }).evaluate((el) => el.click());
    await page.evaluate(({ scale, count }) => {
      // Measured mid-press: a pressed tab and pill grow, which must not count
      // as the capsule being too wide (N5b).
      document.head.appendChild(Object.assign(document.createElement("style"), {
        textContent: ".article-list-inline-tabs .tabs__tab { transform: scale(1.04); } .article-list-inline-tabs .tabs__indicator { scale: 1.1; }",
      }));
      document.documentElement.style.fontSize = `${16 * scale}px`;
      for (const label of document.querySelectorAll(".article-list-inline-tabs .article-tab-label")) {
        const old = label.querySelector(".article-tab-count");
        const span = old || label.appendChild(Object.assign(document.createElement("span"), { className: "article-tab-count ds-tnum" }));
        span.textContent = count;
      }
    }, { scale, count });
    await page.waitForTimeout(150);
    const layout = await page.evaluate(() => {
      const box = (el) => { const r = el.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom }; };
      const q = (selector) => box(document.querySelector(selector));
      const list = document.querySelector(".article-list-inline-tabs .tabs__list");
      return {
        trigger: q('.article-list-header > [data-sidebar="trigger"]'),
        list: q(".article-list-inline-tabs .tabs__list"),
        actions: q(".article-list-header .article-list-actions"),
        clipped: list.scrollWidth > list.clientWidth + 1,
        scrollable: getComputedStyle(list).overflowX === "auto",
        overflow: document.documentElement.scrollWidth > innerWidth + 1,
      };
    });
    if (scale === 1 && width >= 390) expect(await page.locator(".article-list-inline-tabs").getAttribute("data-fit")).not.toMatch(/minimal|bare/);
    await page.screenshot({ path: `test-results/topbar-${width}-${scale}-${count}-${tab}.png`, clip: { x: 0, y: 0, width, height: 120 } });
    const pieces = [layout.trigger, layout.list, layout.actions];
    // One row: every piece shares the trigger's band and they run left to right.
    const middle = (r) => (r.top + r.bottom) / 2;
    for (const piece of pieces) expect(Math.abs(middle(piece) - middle(layout.trigger))).toBeLessThan(2);
    for (let i = 1; i < pieces.length; i += 1) expect(pieces[i].left).toBeGreaterThanOrEqual(pieces[i - 1].right - 0.5);
    expect(layout.actions.right).toBeLessThanOrEqual(width);
    if (layout.clipped) expect(layout.scrollable).toBe(true);
    expect(layout.overflow).toBe(false);
  });
}

// 今日看板 is a page of its own, opened from the sidebar; the list's top bar
// has no board button and Back from the board returns to the list.
test("the board opens as its own page from the sidebar and Back returns to the list", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.setViewportSize({ width: 390, height: 800 });
  await installNativeBoundary(page, {
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "layout-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0", aiApiKey: undefined },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  await expect(page.locator(".article-list-inline-tabs .tabs__list")).toBeVisible();
  await expect(page.locator(".article-list-header [aria-label='今日看板']")).toHaveCount(0);
  await page.locator('.article-list-header > [data-sidebar="trigger"]').evaluate((el) => el.click());
  const entry = page.locator('a[href="#/board"]');
  await expect(entry).toBeVisible();
  await entry.evaluate((el) => el.click());
  await expect.poll(() => page.evaluate(() => location.hash)).toBe("#/board");
  await expect(page.locator(".nf-board-page h1")).toHaveText("今日看板");
  // Content starts under the floating header, not behind it.
  const gap = await page.evaluate(() => {
    const head = document.querySelector(".nf-board-page .article-list-header-wrap").getBoundingClientRect();
    const first = document.querySelector(".nf-board-scroll > *").getBoundingClientRect();
    return first.top - head.bottom;
  });
  expect(gap).toBeGreaterThanOrEqual(0);
  expect(gap).toBeLessThan(40);
  const handled = await page.evaluate(() => {
    const event = new Event("toolboxback", { cancelable: true });
    window.dispatchEvent(event);
    return event.defaultPrevented;
  });
  expect(handled).toBe(true);
  await expect.poll(() => page.evaluate(() => location.hash)).toBe("#/");
  expect(pageErrors).toEqual([]);
});

// Switching tabs reuses the mounted list instead of rebuilding it, an empty
// list draws no footer, and the glass drops its edge bending only while
// something scrolls.
test("tab switches keep the list mounted and scrolling pauses the edge lens", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.setViewportSize({ width: 400, height: 870 });
  await installNativeBoundary(page, {
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "layout-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0" },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  await expect(page.locator(".article-list-content [data-virtuoso-scroller]")).toBeVisible();
  await page.evaluate(() => { window.__scroller = document.querySelector(".article-list-content [data-virtuoso-scroller]"); });
  const tabs = page.locator(".article-list-inline-tabs .tabs__tab");
  for (const index of [1, 0, 2]) {
    await tabs.nth(index).evaluate((el) => el.click());
    await expect(tabs.nth(index)).toHaveAttribute("aria-selected", "true");
    await expect(page.locator(".article-list-empty")).toBeVisible();
    expect(await page.evaluate(() => document.querySelector(".article-list-content [data-virtuoso-scroller]") === window.__scroller)).toBe(true);
    await expect(page.locator(".vlist-footer")).toHaveCount(0);
  }
  // A scroll empties the header's lens filters (blur stays, no bending) and
  // restyles nothing on the page; they fill again once scrolling stops.
  const bending = () => page.evaluate(() => [...document.querySelectorAll("svg[data-nf-lens] filter")]
    .map((filter) => Boolean(filter.querySelector("feDisplacementMap"))));
  await expect.poll(async () => (await bending()).length).toBeGreaterThan(0);
  expect((await bending()).every(Boolean)).toBe(true);
  const scrolling = await page.evaluate(() => {
    window.__scroller.dispatchEvent(new Event("scroll"));
    return [...document.querySelectorAll("svg[data-nf-lens] filter")].map((filter) => filter.querySelector("feDisplacementMap") === null);
  });
  expect(scrolling.every(Boolean)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.hasAttribute("data-nf-scrolling"))).toBe(false);
  await expect.poll(async () => (await bending()).every(Boolean)).toBe(true);
  expect(pageErrors).toEqual([]);
});

// A tap leaves no focus ring behind; a key press brings rings back.
test("a tapped control shows no focus ring until a key is pressed", async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 870 });
  await installNativeBoundary(page, {
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "layout-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0" },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  const trigger = page.locator('.article-list-header > [data-sidebar="trigger"]');
  await expect(trigger).toBeVisible();
  await page.keyboard.press("Shift");
  await trigger.focus();
  await page.evaluate(() => document.activeElement.dispatchEvent(new TouchEvent("touchstart", { bubbles: true, touches: [] })));
  expect(await page.evaluate(() => document.documentElement.dataset.nfInput)).toBe("touch");
  await expect.poll(() => trigger.evaluate((el) => getComputedStyle(el).outlineStyle)).not.toBe("solid");
  // HeroUI draws its ring as a box-shadow on tabs: none after a touch either.
  const tab = page.locator('.article-list-inline-tabs [role="tab"][aria-selected="true"]');
  await page.keyboard.press("Shift");
  await tab.focus();
  // Strongest colour in the element's box-shadow (0 when nothing shows).
  const ringAlpha = () => tab.evaluate((el) => Math.max(0, ...[...getComputedStyle(el).boxShadow.matchAll(/rgba?\(([^)]*)\)/g)]
    .map((match) => { const parts = match[1].split(",").map(Number); return parts.length > 3 ? parts[3] : 1; })));
  await expect.poll(ringAlpha).toBeGreaterThan(0.9);
  await page.evaluate(() => document.activeElement.dispatchEvent(new TouchEvent("touchstart", { bubbles: true, touches: [] })));
  await expect.poll(ringAlpha).toBe(0);
  await page.keyboard.press("Shift");
  expect(await page.evaluate(() => document.documentElement.dataset.nfInput)).toBe("keyboard");
});

// Large text on a narrow screen: the capsule scrolls, and the selected tab is
// scrolled into view; at normal size the capsule keeps its place across tabs.
test("the selected tab stays in view and the capsule keeps its place across tabs", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await installNativeBoundary(page, {
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "layout-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0" },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  const list = page.locator(".article-list-inline-tabs .tabs__list");
  await expect(list).toBeVisible();
  await page.evaluate(() => { document.documentElement.style.fontSize = "32px"; });
  const tabs = page.locator(".article-list-inline-tabs .tabs__tab");
  for (const index of [2, 0, 2]) {
    await tabs.nth(index).evaluate((el) => el.click());
    await expect(tabs.nth(index)).toHaveAttribute("aria-selected", "true");
    await expect.poll(() => page.evaluate((index) => {
      const list = document.querySelector(".article-list-inline-tabs .tabs__list");
      const tab = list.querySelectorAll(".tabs__tab")[index];
      const a = list.getBoundingClientRect();
      const b = tab.getBoundingClientRect();
      return b.left >= a.left - 1 && b.right <= a.right + 1;
    }, index)).toBe(true);
  }

  await page.evaluate(() => { document.documentElement.style.fontSize = ""; });
  await page.setViewportSize({ width: 400, height: 870 });
  const place = () => list.evaluate((el) => { const rect = el.getBoundingClientRect(); return [Math.round(rect.left * 10) / 10, Math.round(rect.width * 10) / 10]; });
  await tabs.nth(1).evaluate((el) => el.click());
  await page.waitForTimeout(300);
  const before = await place();
  for (const index of [2, 0, 1]) {
    await tabs.nth(index).evaluate((el) => el.click());
    await page.waitForTimeout(300);
    expect(await place()).toEqual(before);
  }
});

// Settings › Appearance › Sidebar icons switches the 未读 and 今日看板 tiles
// between A (default), B, C and the classic grey tile; the board tile shows
// a dot when a board is new and moves while one is written (NextFlux 1.18.0).
test("sidebar icon style switches the tiles and the board shows new and writing", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.setViewportSize({ width: 390, height: 800 });
  await installNativeBoundary(page, {
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "layout-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0", aiApiKey: undefined, showUnreadByDefault: true },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  await expect(page.locator(".article-list-inline-tabs .tabs__list")).toBeVisible();
  await page.locator('.article-list-header > [data-sidebar="trigger"]').evaluate((el) => el.click());
  const boardTile = page.locator('a[href="#/board"] .sidebar-row-icon');
  await expect(boardTile).toBeVisible();
  const tiles = () => page.evaluate(() => [...document.querySelectorAll('[data-sidebar="menu-button"] .sidebar-row-icon')]
    .slice(0, 2).map((el) => {
      const style = getComputedStyle(el);
      const box = el.getBoundingClientRect();
      const svg = el.querySelector(":scope > svg");
      return { cls: el.className, kind: el.dataset.k || null, image: style.backgroundImage, color: style.color, glyph: svg ? getComputedStyle(svg).color : null, radius: style.borderRadius, w: box.width, h: box.height };
    }));
  const setStyle = (value) => page.evaluate(async (v) => {
    const { updateSettings } = await import("/src/stores/settingsStore.js");
    await updateSettings({ sidebarIconStyle: v });
  }, value);

  const sizes = {};
  const rows = {};
  for (const style of ["a", "b", "c", "classic"]) {
    if (style !== "a") await setStyle(style);
    await expect.poll(async () => (await tiles())[1].cls).toContain(style === "classic" ? "ds-icon-badge" : `nf-sb-${style}`);
    const [first, second] = await tiles();
    sizes[style] = [first.w, first.h, second.w, second.h];
    rows[style] = await page.evaluate(() => [...document.querySelectorAll('[data-sidebar="menu-button"]')].slice(0, 2).map((el) => el.getBoundingClientRect().height));
    if (style === "classic") {
      expect(second.cls).not.toContain("nf-sb");
      await expect(boardTile.locator(".nf-board-side-icon")).toHaveCount(1);
    } else {
      expect(first.kind).not.toBeNull();
      expect(second.kind).toBe("board");
      // A and B are solid gradient tiles with white glyphs; C is tinted glass.
      expect(first.image).toContain("gradient");
      expect(second.image).toContain("gradient");
      if (style !== "c") expect(second.color).toBe("rgb(255, 255, 255)");
      else expect(second.color).not.toBe("rgb(255, 255, 255)");
      // The symbol takes the tile's colour (set C used to draw them white).
      expect(first.glyph).toBe(first.color);
      expect(second.glyph).toBe(second.color);
      if (style === "b") expect(second.radius).toBe("50%");
    }
  }
  // The three sets use the draft's 36px tile; 经典 keeps its 30px one. The
  // rows stay the same height either way.
  // Sizes come back fractional (29.999998 under load), so compare to 0.01px.
  for (const style of ["a", "b", "c"]) for (const size of sizes[style]) expect(size).toBeCloseTo(36, 2);
  for (const size of sizes.classic) expect(size).toBeCloseTo(30, 2);
  expect(new Set(Object.values(rows).map(String)).size).toBe(1);

  await setStyle("c");
  await page.evaluate(async () => {
    const { board } = await import("/src/toolbox/board/boardStore.js");
    board.set({ ...board.get(), unseen: true, updating: false });
  });
  await expect(boardTile).toHaveAttribute("data-state", "new");
  expect(await boardTile.evaluate((el) => getComputedStyle(el, "::after").content)).not.toBe("none");
  await page.evaluate(async () => {
    const { board } = await import("/src/toolbox/board/boardStore.js");
    board.set({ ...board.get(), updating: true });
  });
  await expect(boardTile).toHaveAttribute("data-state", "working");
  expect(await boardTile.evaluate((el) => getComputedStyle(el.querySelector(".nf-sb-wave")).animationName)).toContain("nf-sb-c-fill");
  // The moving parts animate whole elements, so no set lays the page out on
  // every frame (Chrome does for animated shapes inside an <svg>).
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Performance.enable");
  const layouts = async () => (await cdp.send("Performance.getMetrics")).metrics.find((m) => m.name === "LayoutCount").value;
  for (const style of ["a", "b", "c"]) {
    await setStyle(style);
    await expect(boardTile).toHaveAttribute("data-state", "working");
    await page.waitForTimeout(300);
    const before = await layouts();
    // Headless Chrome draws no frames while the test only waits, so an
    // animation inside the <svg> would lay nothing out; drive a second of frames.
    await page.evaluate(() => new Promise((done) => {
      const end = performance.now() + 1000;
      const frame = () => (performance.now() < end ? requestAnimationFrame(frame) : done());
      requestAnimationFrame(frame);
    }));
    expect(await layouts() - before, `set ${style}`).toBeLessThan(6);
  }
  // Holds still while the phone sidebar is closed, and moves again once it opens.
  const wave = () => boardTile.evaluate((el) => getComputedStyle(el.querySelector(".nf-sb-wave"), "::before").animationPlayState);
  expect(await wave()).toBe("running");
  const panel = page.locator(".sidebar-mobile-panel");
  await panel.evaluate((el) => el.setAttribute("data-open", "false"));
  expect(await wave()).toBe("paused");
  await panel.evaluate((el) => el.setAttribute("data-open", "true"));
  expect(await wave()).toBe("running");
  // Another element marked collapsed (an accordion, a menu) leaves the tiles moving.
  await boardTile.evaluate((el) => {
    const host = document.createElement("div");
    host.setAttribute("data-state", "collapsed");
    host.className = "nf-test-collapsed";
    host.append(el.cloneNode(true));
    document.body.append(host);
  });
  expect(await page.locator(".nf-test-collapsed .nf-sb-wave").evaluate((el) => getComputedStyle(el, "::before").animationPlayState)).toBe("running");
  await page.evaluate(() => document.querySelector(".nf-test-collapsed").remove());
  // The real sidebar on a wider screen: folding it with Ctrl+B stops the tiles.
  await page.setViewportSize({ width: 1100, height: 800 });
  const desktop = page.locator(".sidebar-desktop");
  const desktopWave = () => desktop.locator('a[href="#/board"] .nf-sb-wave').evaluate((el) => getComputedStyle(el, "::before").animationPlayState);
  await expect(desktop).toHaveAttribute("data-state", "expanded");
  expect(await desktopWave()).toBe("running");
  await page.keyboard.press("Control+b");
  await expect(desktop).toHaveAttribute("data-state", "collapsed");
  expect(await desktopWave()).toBe("paused");
  await page.keyboard.press("Control+b");
  await expect(desktop).toHaveAttribute("data-state", "expanded");
  expect(await desktopWave()).toBe("running");
  expect(pageErrors).toEqual([]);
});

// A four- or five-digit unread count sits at the row's end; the row's label
// stops before it, even at 320px with text twice as large.
for (const [width, scale] of [[320, 2], [320, 1], [390, 1.3]]) test(`sidebar unread count stays clear of its label at ${width}px, text x${scale}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 800 });
  await installNativeBoundary(page, {
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "layout-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0" },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  await expect(page.locator(".article-list-inline-tabs .tabs__list")).toBeVisible();
  await page.locator('.article-list-header > [data-sidebar="trigger"]').evaluate((el) => el.click());
  for (const count of [1234, 12345]) {
    await page.evaluate(async ([scale, count]) => {
      document.documentElement.style.fontSize = `${16 * scale}px`;
      const { unreadCounts } = await import("/src/stores/feedsStore.js");
      unreadCounts.set({ 1: count });
    }, [scale, count]);
    const item = page.locator(".sidebar-mobile-panel .nf-count-item");
    await expect(item.locator('[data-sidebar="menu-badge"]')).toHaveText(String(count));
    const gap = await item.evaluate((el) => {
      const label = el.querySelector(".sidebar-row > span:last-child");
      const range = document.createRange();
      range.selectNodeContents(label);
      const text = range.getBoundingClientRect();
      const box = label.getBoundingClientRect();
      const badge = el.querySelector('[data-sidebar="menu-badge"]');
      const digits = document.createRange();
      digits.selectNodeContents(badge);
      return { label: Math.min(text.right, box.right), badge: digits.getBoundingClientRect().left };
    });
    expect(gap.label, `${count}`).toBeLessThanOrEqual(gap.badge - 4);
  }
});

// Settings › General: 查重方式 sits under 隐藏重复条目 and is offered only
// while that is on (NextFlux 1.19.0).
test("查重方式 picks title, URL or both, and hides with 隐藏重复条目 off", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.setViewportSize({ width: 393, height: 850 });
  await installNativeBoundary(page, {
    auth: {
      serverUrl: "https://miniflux.xiaochen.win", username: "layout-test-user", password: "", userId: 7,
      token: "fixed-synthetic-test-token", authType: "token",
    },
    settings: { ...STORED_SETTINGS, syncInterval: "0" },
    cacheRoot: {
      version: 3, revision: 1, account: { serverUrl: "https://miniflux.xiaochen.win", userId: "7" },
      transaction: null, lastSyncTime: null,
      tables: { articles: null, feeds: null, categories: null, feedIcons: null },
    },
  });
  await page.goto(PRODUCTION_URL);
  await expect(page.locator(".article-list-inline-tabs .tabs__list")).toBeVisible();
  await page.evaluate(async () => (await import("/src/stores/modalStore.js")).settingsModalOpen.set(true));
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: /常规|General/ }).first().click();
  const row = dialog.getByRole("button", { name: /查重方式/ });
  await expect(row).toBeVisible();
  await expect(row).toContainText("同时");
  await row.click();
  await page.getByRole("menuitemradio", { name: "标题" }).or(page.getByRole("menuitem", { name: "标题" })).or(page.getByRole("option", { name: "标题" })).first().click();
  await expect(row).toContainText("标题");
  expect(await page.evaluate(async () => (await import("/src/stores/settingsStore.js")).settingsState.get().duplicateMatch)).toBe("title");
  await expect(dialog.getByText("标题完全相同的文章只显示第一条")).toBeVisible();
  await page.evaluate(async () => (await import("/src/stores/settingsStore.js")).updateSettings({ hideDuplicateArticles: false }));
  await expect(row).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});
