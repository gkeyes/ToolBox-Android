import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { chromium, expect } from "@playwright/test";
import { installFixture, openFixture, settleFrames } from "../browser/fixture.mjs";
import { installPerformanceInstrumentation } from "./instrument.mjs";
import { articles, articleBodies, continuousReadIds, fixtureTimestamp, librarySize } from "./scenarios.mjs";

if (process.env.GITHUB_ACTIONS !== "true") throw new Error("Performance comparisons are authorized only in GitHub Actions.");
const candidateDir = path.resolve(process.env.NEXTFLUX_CANDIDATE_DIR);
const baselineDir = path.resolve(process.env.NEXTFLUX_BASELINE_DIR);
const outputDir = path.resolve(process.env.NEXTFLUX_PERFORMANCE_OUTPUT);
const variants = {
  baseline: { directory: baselineDir, baseURL: "http://127.0.0.1:4173" },
  candidate: { directory: candidateDir, baseURL: "http://127.0.0.1:4174" },
};
const contextOptions = {
  viewport: { width: 390, height: 844 }, deviceScaleFactor: 1,
  isMobile: true, hasTouch: true, locale: "en-US", timezoneId: "UTC",
  colorScheme: "light", reducedMotion: "reduce", serviceWorkers: "block",
};
const observationWindowMs = 750; // Covers the existing 600 ms font debounce, not a performance gate.
const median = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
};
const report = {
  schemaVersion: 1, status: "RUNNING", startedAt: new Date().toISOString(),
  methodology: {
    repetitions: 3, alternatingOrder: [["baseline", "candidate"], ["candidate", "baseline"], ["baseline", "candidate"]],
    cold: "Fresh browser context; app readiness and real cache migration complete; first article visit and no prior code highlighting.",
    warm: "Same context and article; close and reopen through UI after the first pass. Both visits start unread and opening marks read.",
    firstReadable: "Trusted primary pointerdown on the card to first body paragraph intersecting the reader viewport after two requestAnimationFrame callbacks: a paint opportunity proxy, not FCP.",
    bodyComplete: "Trusted primary pointerdown on the card to all original paragraphs and raw code blocks committed, including the end marker, after two animation frames. Offscreen syntax highlighting is not required.",
    longTasks: "PerformanceObserver longtask entries overlapping the recorded phase; each raw entry retains its full duration, so adjacent phases can share a boundary entry.",
    stateResponse: "Trusted primary pointerdown on the UI control to successful production cache Worker patchState acknowledgement after fake native storage.apply commits. Toolbar UI paint opportunity is recorded separately.",
    fontScans: "Actual TreeWalker.nextNode visits under article title/content with SHOW_TEXT while system-ui is selected; instrumentation does not itself use TreeWalker.",
    continuous: "24 individually acknowledged Mark as Read actions in the real browser Worker with 96 cached articles. The separate 18,518-article storage unit fixture is not browser-scale evidence.",
    isolation: "Same candidate harness, synthetic HTML, fixed fixture timestamp, native boundary, viewport and browser binary for both production dist builds. No browser clock mocking, CPU throttling, OS cache purge or external network.",
    screenshotValidation: "REMOVED by policy; screenshot, video and trace are OFF.",
    boundary: "GitHub Linux Chromium plus synthetic native bridge. Not Android, physical-device, HyperOS, real Miniflux latency or native Room throughput evidence.",
    performanceGate: "NONE. Functional integrity, zero errors and zero outside requests remain required. Timeouts are liveness limits, not speed targets.",
  },
  environment: {
    node: process.version, platform: process.platform, arch: process.arch,
    osRelease: os.release(), cpus: os.cpus().map(({ model, speed }) => ({ model, speedMHz: speed })),
    totalMemoryBytes: os.totalmem(), contextOptions, observationWindowMs,
    githubRunId: process.env.GITHUB_RUN_ID, githubRunAttempt: process.env.GITHUB_RUN_ATTEMPT,
    runnerImage: process.env.ImageOS, runnerImageVersion: process.env.ImageVersion,
    screenshot: "off", video: "off", trace: "off", fixtureTimestamp,
    librarySize, continuousReadIds, articles: articles.map(({ metadata }) => metadata),
  },
  builds: {}, samples: [], summaries: [], failures: [],
};

function summarize() {
  const groups = new Map();
  for (const sample of report.samples) {
    const key = `${sample.scenario}/${sample.cacheState}/${sample.variant}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(sample);
  }
  return [...groups.entries()].map(([group, samples]) => ({
    group, repetitions: samples.length,
    medians: Object.fromEntries(Object.keys(samples[0].metrics).map((key) => [key, median(samples.map((sample) => sample.metrics[key]))])),
  }));
}

function markdown() {
  const lines = [
    "# NextFlux production-build comparison", "",
    `Status: **${report.status}**. Baseline: \`${report.builds.baseline?.commit || "unavailable"}\`; candidate: \`${report.builds.candidate?.commit || "unavailable"}\`.`, "",
    "GitHub Linux Chromium only. Native storage and network are synthetic fixture boundaries. These measurements do not establish Android or HyperOS smoothness.", "",
    "Each scenario has 3 alternating repetitions per build. Cold is the first article visit after app/cache readiness; warm reopens the same article in the same context. Timing begins at trusted primary pointerdown. First-readable and body-complete times are two-frame paint opportunity proxies, not FCP. Complete prose and code text are asserted; offscreen highlighting may remain deferred.", "",
    "The continuous scenario has 96 cached articles and 24 separate Mark as Read operations. Its state response is the per-run median acknowledgement time; state storage counts are totals across all 24 operations. The separate 18,518-article storage unit test is not included in these browser numbers.", "",
    "Performance values have no hard pass threshold. Functional integrity, no errors and no external requests are required. Screenshot validation was removed; screenshot, video and trace are off.", "",
    "All table cells show baseline → candidate medians; times are milliseconds.", "",
    "| Scenario / cache | First readable | Body complete | State response | State apply / getMany |",
    "| --- | ---: | ---: | ---: | ---: |",
  ];
  const keys = [...new Set(report.summaries.map(({ group }) => group.replace(/\/(baseline|candidate)$/, "")))];
  const pairs = [];
  const format = (value) => Number.isFinite(value) ? value.toFixed(2) : "N/A";
  for (const key of keys) {
    const before = report.summaries.find(({ group }) => group === `${key}/baseline`);
    const after = report.summaries.find(({ group }) => group === `${key}/candidate`);
    const pair = (metric) => `${format(before?.medians[metric])} → ${format(after?.medians[metric])}`;
    pairs.push({ key, pair });
    lines.push(`| ${key} | ${pair("firstReadablePaintProxyMs")} | ${pair("bodyCompletePaintProxyMs")} | ${pair("stateResponseMs")} | ${pair("stateStorageApplyCalls")} / ${pair("stateStorageGetManyCalls")} |`);
  }
  lines.push("", "| Scenario / cache / phase | Long tasks: count | Total ms | Longest ms | Font nodes | Highlight Worker requests |", "| --- | ---: | ---: | ---: | ---: | ---: |");
  for (const { key, pair } of pairs) {
    const phases = key.startsWith("continuous-") ? ["sequence"] : ["open", "scroll", "state"];
    for (const phase of phases) lines.push(`| ${key} / ${phase} | ${pair(`${phase}LongTaskCount`)} | ${pair(`${phase}LongTaskTotalMs`)} | ${pair(`${phase}LongTaskMaxMs`)} | ${pair(`${phase}FontScanNodes`)} | ${pair(`${phase}HighlightWorkerRequests`)} |`);
  }
  lines.push("", "Raw per-run measurements, long tasks, storage calls, fixture hashes, browser version and machine details are in comparison.json.");
  if (report.failures.length) lines.push("", ...report.failures.map((failure) => `Failure: ${failure.message}`));
  return `${lines.join("\n")}\n`;
}

async function persist() {
  report.summaries = summarize();
  await writeFile(path.join(outputDir, "comparison.json"), `${JSON.stringify(report, null, 2)}\n`);
  await writeFile(path.join(outputDir, "comparison.md"), markdown());
}

async function waitForServer(baseURL) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch(baseURL, { signal: AbortSignal.timeout(1000) });
      if (response.ok) return;
    } catch { /* Python may still be binding its local socket. */ }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Static production server did not become available: ${baseURL}`);
}

async function quietCache(page) {
  await page.waitForFunction(() => {
    const fixture = window.__nextfluxTest;
    const completed = new Set(fixture.results.map(({ id }) => id));
    return fixture.calls.every(({ id }) => completed.has(id));
  });
  await settleFrames(page);
}

async function verifyBoundary(page, observations, outsideRequests, consoleErrors) {
  assert.deepEqual(observations.blocked, [], "No browser request may bypass the native fixture");
  assert.deepEqual(outsideRequests, [], "No main-page or Worker request may leave its exact local origin");
  assert.deepEqual(observations.pageErrors, [], "No uncaught page errors");
  assert.deepEqual(consoleErrors, [], "No console errors");
  const state = await page.evaluate(() => ({
    unexpectedNetwork: window.__nextfluxTest.unexpectedNetwork,
    failures: window.__nextfluxTest.results.filter((entry) => !entry.ok),
    workerUrls: window.__nextfluxTest.workerUrls, origin: location.origin,
    rootVersion: window.__nextfluxTest.storageRoot()?.version,
    longTaskSupported: window.__nextfluxPerf.longTaskSupported,
  }));
  assert.deepEqual(state.unexpectedNetwork, []);
  assert.deepEqual(state.failures, []);
  assert.equal(state.rootVersion, 3, "Real production cache migration must complete");
  assert.equal(state.longTaskSupported, true, "Missing instrumentation cannot be reported as zero long tasks");
  assert.ok(state.workerUrls.length > 0, "Production dist must create a real cache Worker");
  for (const url of state.workerUrls) {
    assert.equal(new URL(url).origin, state.origin);
    assert.match(new URL(url).pathname, /^\/assets\/.+\.js$/);
  }
  return state;
}

async function createSession(browser, variant) {
  const { baseURL } = variants[variant];
  const context = await browser.newContext({ ...contextOptions, baseURL });
  const outsideRequests = [];
  const consoleErrors = [];
  const allowed = (value) => /^(blob:|data:)/.test(value) || new URL(value).origin === baseURL;
  context.on("request", (request) => { if (!allowed(request.url())) outsideRequests.push(request.url()); });
  // Context routing also covers Worker requests; page routing retains the
  // fixture's exact-origin policy. ServiceWorkers are explicitly disabled.
  await context.route("**/*", (route) => allowed(route.request().url()) ? route.continue() : route.abort("blockedbyclient"));
  const page = await context.newPage();
  page.setDefaultTimeout(90_000);
  page.on("console", (message) => { if (message.type() === "error") consoleErrors.push(message.text()); });
  const observations = await installFixture(page, { baseURL, articleBodies, timestamp: fixtureTimestamp, reduceMotion: true, markAsReadOnScroll: false });
  await page.addInitScript(installPerformanceInstrumentation);
  await openFixture(page);
  await quietCache(page);
  await page.evaluate(() => window.__nextfluxPerf.observeFixture());
  const verify = () => verifyBoundary(page, observations, outsideRequests, consoleErrors);
  return { context, page, verify };
}

async function assertContent(page, article) {
  const actual = await page.locator(".article-content").evaluate((element) => ({
    paragraphs: [...element.querySelectorAll("p")].map((paragraph) => paragraph.textContent),
    codes: [...element.querySelectorAll(".code-block pre:not([hidden]) code")].map((code) => code.textContent.trim()),
  }));
  assert.deepEqual(actual.paragraphs, article.paragraphs, "Every original paragraph must remain intact and ordered");
  assert.deepEqual(actual.codes, article.codes, "Every code block and line must remain intact and ordered");
}

async function stateResult(page, articleId, expectedStatus) {
  await page.waitForFunction(() => {
    const perf = window.__nextfluxPerf;
    return perf.action && perf.cacheCompletions.slice(perf.action.resultIndex).some((entry) => entry.method === "patchState" && entry.ok);
  });
  await quietCache(page);
  const result = await page.evaluate(() => {
    const perf = window.__nextfluxPerf;
    const phase = perf.finish(perf.action);
    const acknowledgements = phase.cacheCompletions.filter((entry) => entry.method === "patchState" && entry.ok);
    return {
      ...phase, acknowledgementCount: acknowledgements.length,
      durableResponseMs: acknowledgements[0].at - perf.action.at,
      uiPaintResponseMs: perf.action.uiPaintAt === null ? null : perf.action.uiPaintAt - perf.action.at,
    };
  });
  assert.equal(result.acknowledgementCount, 1, "One state action must produce exactly one successful patch");
  assert.ok(result.storageApplyCalls > 0, "State response must include real production persistence through native storage.apply");
  assert.deepEqual(result.network.filter((entry) => entry.method === "PUT" && entry.path === "/v1/entries").map(({ ids, status }) => ({ ids, status })), [{ ids: [articleId], status: expectedStatus }]);
  return result;
}

function phaseMetrics(prefix, phase) {
  return {
    [`${prefix}LongTaskCount`]: phase.longTaskCount,
    [`${prefix}LongTaskTotalMs`]: phase.longTaskTotalMs,
    [`${prefix}LongTaskMaxMs`]: phase.longTaskMaxMs,
    [`${prefix}FontScanNodes`]: phase.fontScanNodes,
    [`${prefix}StorageApplyCalls`]: phase.storageApplyCalls,
    [`${prefix}StorageGetManyCalls`]: phase.storageGetManyCalls,
    [`${prefix}HighlightWorkerRequests`]: phase.highlightWorkerRequests,
  };
}

async function readPass(page, article, cacheState) {
  await quietCache(page);
  await page.evaluate((expected) => window.__nextfluxPerf.armReading(expected), { ...article.metadata, first: article.first, last: article.last });
  await page.locator(`[data-article-id="${article.id}"]`).click();
  await page.waitForFunction(() => Number.isFinite(window.__nextfluxPerf.reading?.firstReadableAt) && Number.isFinite(window.__nextfluxPerf.reading?.bodyCompleteAt));
  const reading = await page.evaluate(() => {
    const perf = window.__nextfluxPerf;
    return {
      ...perf.finish(perf.reading.start),
      firstReadablePaintProxyMs: perf.reading.firstReadableAt - perf.reading.start.at,
      bodyCompletePaintProxyMs: perf.reading.bodyCompleteAt - perf.reading.start.at,
    };
  });
  await expect(page.locator(".article-title")).toHaveText(`Article ${article.id}`);
  await assertContent(page, article);
  await quietCache(page);
  const scrollStart = await page.evaluate(() => window.__nextfluxPerf.snapshot("article-scroll-system-font"));
  const positions = [];
  for (const fraction of [0.35, 0.75, 1, 0]) {
    positions.push(await page.locator(".article-scroll-area").evaluate((element, value) => {
      element.scrollTop = (element.scrollHeight - element.clientHeight) * value;
      return { fraction: value, scrollTop: element.scrollTop, scrollHeight: element.scrollHeight, clientHeight: element.clientHeight };
    }, fraction));
    await page.waitForTimeout(observationWindowMs);
  }
  assert.ok(positions[2].scrollTop > 0, "The complete article must support real reader scrolling");
  const scroll = await page.evaluate((start) => window.__nextfluxPerf.finish(start), scrollStart);
  await assertContent(page, article);
  await quietCache(page);
  await expect(page.locator('.action-buttons button[aria-label="Unread"]')).toBeVisible();
  await page.evaluate(() => window.__nextfluxPerf.armAction('.action-buttons button[aria-label="Unread"]', null, '.action-buttons button[aria-label="Read"]'));
  await page.locator('.action-buttons button[aria-label="Unread"]').click();
  await page.waitForFunction(() => Number.isFinite(window.__nextfluxPerf.action?.uiPaintAt));
  const state = await stateResult(page, article.id, "unread");
  await expect(page.locator('.action-buttons button[aria-label="Read"]')).toBeVisible();
  await assertContent(page, article);
  return {
    scenario: article.name, cacheState,
    metrics: {
      firstReadablePaintProxyMs: reading.firstReadablePaintProxyMs,
      bodyCompletePaintProxyMs: reading.bodyCompletePaintProxyMs,
      ...phaseMetrics("open", reading), ...phaseMetrics("scroll", scroll), ...phaseMetrics("state", state),
      stateResponseMs: state.durableResponseMs, stateUiPaintProxyMs: state.uiPaintResponseMs,
    },
    measurements: { reading, scroll: { ...scroll, positions }, state },
  };
}

async function ensureCard(page, id) {
  const card = page.locator(`[data-article-id="${id}"]`);
  for (let attempt = 0; attempt < 60; attempt += 1) {
    if (await card.count()) { await card.scrollIntoViewIfNeeded(); return card; }
    await page.locator(".v-list").evaluate((element) => { element.scrollTop += element.clientHeight * 0.7; });
    await settleFrames(page);
  }
  throw new Error(`Article ${id} was not reachable by scrolling the real virtual list`);
}

async function continuousPass(page) {
  const operations = [];
  const start = await page.evaluate(() => window.__nextfluxPerf.snapshot("continuous-24-read-actions"));
  for (const id of continuousReadIds) {
    const card = await ensureCard(page, id);
    await card.click({ button: "right" });
    const menu = page.getByText("Mark as Read", { exact: true });
    await expect(menu).toBeVisible();
    await quietCache(page);
    await page.evaluate(() => window.__nextfluxPerf.armAction("div.cursor-pointer", "Mark as Read"));
    await menu.click();
    operations.push({ id, ...await stateResult(page, id, "read") });
    // Reopen the same menu to verify the rendered state, after timing the write.
    await card.click({ button: "right" });
    await expect(page.getByText("Mark as Unread", { exact: true })).toBeVisible();
    await page.keyboard.press("Escape");
  }
  const sequence = await page.evaluate((value) => window.__nextfluxPerf.finish(value), start);
  assert.equal(operations.length, 24);
  return {
    scenario: "continuous-read-96-library", cacheState: "migrated-cache",
    metrics: {
      ...phaseMetrics("sequence", sequence),
      stateResponseMs: median(operations.map(({ durableResponseMs }) => durableResponseMs)),
      stateStorageApplyCalls: operations.reduce((sum, operation) => sum + operation.storageApplyCalls, 0),
      stateStorageGetManyCalls: operations.reduce((sum, operation) => sum + operation.storageGetManyCalls, 0),
    },
    measurements: { sequence, operations },
  };
}

await mkdir(outputDir, { recursive: true });
let browser;
try {
  for (const [variant, { directory, baseURL }] of Object.entries(variants)) {
    const lock = JSON.parse(await readFile(path.join(directory, "examples/nextflux/package-lock.json"), "utf8"));
    assert.equal(lock.packages["node_modules/@playwright/test"].version, "1.63.0");
    const html = await readFile(path.join(directory, "examples/nextflux/dist/index.html"));
    report.builds[variant] = {
      commit: execFileSync("git", ["-C", directory, "rev-parse", "HEAD"], { encoding: "utf8" }).trim(),
      version: JSON.parse(await readFile(path.join(directory, "examples/nextflux/package.json"), "utf8")).version,
      playwright: lock.packages["node_modules/@playwright/test"].version,
      distIndexSha256: createHash("sha256").update(html).digest("hex"), baseURL,
    };
    await waitForServer(baseURL);
  }
  browser = await chromium.launch({ headless: true });
  report.environment.browserVersion = browser.version();
  for (const scenario of [...articles, null]) {
    for (let repetition = 1; repetition <= 3; repetition += 1) {
      const order = report.methodology.alternatingOrder[repetition - 1];
      for (const variant of order) {
        report.activeSample = { scenario: scenario?.name || "continuous-read-96-library", repetition, variant, cacheState: "setup" };
        const session = await createSession(browser, variant);
        try {
          if (scenario) {
            for (const cacheState of ["cold", "warm"]) {
              report.activeSample.cacheState = cacheState;
              const sample = await readPass(session.page, scenario, cacheState);
              sample.boundary = await session.verify();
              report.samples.push({ variant, repetition, order, ...sample });
              await persist();
              await session.page.getByRole("button", { name: "Close", exact: true }).click();
              await session.page.locator(".article-content").waitFor({ state: "detached" });
            }
          } else {
            report.activeSample.cacheState = "migrated-cache";
            const sample = await continuousPass(session.page);
            sample.boundary = await session.verify();
            report.samples.push({ variant, repetition, order, ...sample });
            await persist();
          }
        } catch (error) {
          // Text-only failure diagnostics stay small and contain no article body,
          // credentials, screenshots or traces; the dataset is wholly synthetic.
          report.failureContext = await session.page.evaluate(() => ({
            route: location.hash,
            paragraphCount: document.querySelectorAll(".article-content p").length,
            codeBlockCount: document.querySelectorAll(".article-content .code-block pre:not([hidden]) code").length,
            readingStarted: Boolean(window.__nextfluxPerf.reading?.start),
            firstReadableAt: window.__nextfluxPerf.reading?.firstReadableAt,
            bodyCompleteAt: window.__nextfluxPerf.reading?.bodyCompleteAt,
            actionStarted: Boolean(window.__nextfluxPerf.action),
            recentCacheCalls: window.__nextfluxTest.calls.slice(-8),
            recentCacheResults: window.__nextfluxTest.results.slice(-8),
            unexpectedNetwork: window.__nextfluxTest.unexpectedNetwork,
          })).catch((diagnosticError) => ({ unavailable: diagnosticError.message }));
          throw error;
        } finally { await session.context.close(); }
      }
    }
  }
  assert.equal(report.samples.length, 30, "All paired cold/warm reading and continuous samples must finish");
  for (const summary of summarize()) assert.equal(summary.repetitions, 3, "No incomplete set can be treated as a three-run comparison");
  report.status = "PASS";
  report.activeSample = null;
} catch (error) {
  report.status = "FAIL";
  report.failures.push({ message: error.message, stack: error.stack });
  process.exitCode = 1;
} finally {
  await browser?.close();
  report.finishedAt = new Date().toISOString();
  await persist();
}
