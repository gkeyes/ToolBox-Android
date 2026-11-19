// Compare two built NextFlux dist directories with the existing synthetic native boundary.
// Install examples/nextflux/test/browser dependencies and its Playwright Chromium first.
// node scripts/bench/nextflux-search.mjs OLD_DIST NEW_DIST [--rounds=50] [--chromium=/path/to/chrome]
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { extname, resolve, sep } from "node:path";
import { parseArgs } from "node:util";
import { createArticleCache } from "../../examples/nextflux/src/toolbox/cache.js";
import { installNativeBoundary, STORED_SETTINGS } from "../../examples/nextflux/test/browser/fixtures/startup-native.js";

const require = createRequire(new URL("../../examples/nextflux/test/browser/package.json", import.meta.url));
const { chromium } = require("playwright");
const { positionals, values } = parseArgs({
  allowPositionals: true,
  options: { rounds: { type: "string", default: "50" }, chromium: { type: "string" } },
});
const rounds = Number(values.rounds);
if (positionals.length !== 2 || !Number.isSafeInteger(rounds) || rounds < 1) {
  throw new Error("Provide old and new dist directories, with a positive integer number of rounds.");
}

const serverUrl = "https://miniflux.xiaochen.win";
async function createFixture() {
  const data = new Map();
  const storage = {
    getMany: async keys => keys.map(key => data.get(key) ?? null),
    keys: async () => [...data.keys()],
    async apply({ set = [], remove = [] }) {
      for (const { key, value } of set) data.set(key, value);
      for (const key of remove) data.delete(key);
    },
  };
  const cache = createArticleCache(storage, { autoGc: false });
  const syncedAt = "2026-11-18T03:04:00Z";
  const token = await cache.prepareSync({ account: { serverUrl, userId: "7" }, syncedAt });
  const articles = Array.from({ length: 200 }, (_, i) => ({
    id: i + 1, title: (i % 2 ? "甲类" : "乙类") + "测量文章" + i,
    feedId: 1, url: "https://example.test/" + i, status: "unread", starred: 0,
    published_at: syncedAt, created_at: syncedAt, changed_at: syncedAt, content: "<p>正文</p>",
  }));
  await cache.applySyncBatch(token, articles);
  await cache.commitSync(token, { feeds: [{ id: 1, title: "专题", categoryId: 1 }], categories: [{ id: 1, title: "目录" }] });
  return [...data].map(([key, value]) => ({ key, value }));
}

async function serveDist(directory) {
  const root = resolve(directory);
  const types = { ".js": "text/javascript", ".html": "text/html", ".css": "text/css", ".svg": "image/svg+xml" };
  const server = createServer(async (request, response) => {
    try {
      const pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
      const file = resolve(root, "." + (pathname === "/" ? "/index.html" : pathname));
      if (!file.startsWith(root + sep)) throw new Error("Path outside dist");
      const data = await readFile(file);
      response.writeHead(200, { "Content-Type": types[extname(file)] || "application/octet-stream" }).end(data);
    } catch { response.writeHead(404).end(); }
  });
  await new Promise((done, reject) => server.once("error", reject).listen(0, "127.0.0.1", done));
  return server;
}

async function openSearch(browser, server, fixture) {
  const url = "http://127.0.0.1:" + server.address().port + "/";
  const page = await browser.newPage({ viewport: { width: 400, height: 900 }, hasTouch: true });
  await installNativeBoundary(page, {
    url, auth: { serverUrl, username: "test", userId: 7, authType: "token", token: "fixture" },
    settings: { ...STORED_SETTINGS, syncInterval: "0" }, persistStorage: true,
  });
  await page.goto(url);
  await page.locator(".nextflux-article-list-page").waitFor();
  await page.evaluate(set => window.ToolBox.storage.apply({ set }), fixture);
  await page.reload();
  await page.locator(".nextflux-article-list-page").waitFor();
  await page.locator("#splash-screen").waitFor({ state: "detached" });
  await page.keyboard.press("f");
  await page.getByRole("textbox").waitFor();
  return page;
}

async function measure(page, term) {
  return page.getByRole("textbox").evaluate((input, term) => {
    const start = performance.now();
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, term);
    input.dispatchEvent(new Event("input", { bubbles: true }));
    return new Promise((done, reject) => {
      function sample() {
        const row = document.querySelector(".search-result-row");
        if (row?.textContent.includes(term) && row.getBoundingClientRect().height > 0) {
          requestAnimationFrame(() => done(performance.now() - start));
        } else if (performance.now() - start > 7000) {
          reject(new Error("A matching result did not appear"));
        } else {
          requestAnimationFrame(sample);
        }
      }
      requestAnimationFrame(sample);
    });
  }, term);
}

const fixture = await createFixture();
const browser = await chromium.launch({ executablePath: values.chromium });
const servers = [];
try {
  const pages = [];
  for (const dir of positionals) {
    const server = await serveDist(dir);
    servers.push(server);
    pages.push(await openSearch(browser, server, fixture));
  }
  const samples = [[], []];
  for (let round = 0; round <= rounds; round++) {
    for (const version of (round % 2 ? [1, 0] : [0, 1])) {
      const ms = await measure(pages[version], round % 2 ? "甲类" : "乙类");
      if (round > 0) samples[version].push(ms);
    }
  }
  const result = samples.map((measurements, index) => {
    const sorted = [...measurements].sort((a, b) => a - b);
    const mid = Math.floor(rounds / 2);
    return { dist: positionals[index], medianMs: rounds % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2,
      p95Ms: sorted[Math.ceil(rounds * 0.95) - 1], measurements };
  });
  console.log(JSON.stringify({ chromium: browser.version(), rounds, result }, null, 2));
} finally {
  await browser.close();
  for (const server of servers) server.close();
}
