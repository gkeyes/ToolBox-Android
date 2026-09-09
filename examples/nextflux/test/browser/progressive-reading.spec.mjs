import { test, expect } from "@playwright/test";
import { installFixture, openFixture, settleFrames } from "./fixture.mjs";

const completeBody = (page, id = 96) => page.locator(`.article-body[data-font-article-id="${id}"][data-reading-complete="true"]`);

async function checkBoundary(page, observations) {
  expect(observations.blocked).toEqual([]);
  expect(observations.pageErrors).toEqual([]);
  expect(await page.evaluate(() => window.__nextfluxTest.unexpectedNetwork)).toEqual([]);
}

test("the first batch is readable before the full nested article, table and plaintext finish; selection and status updates retain nodes", async ({ page }) => {
  const paragraphs = Array.from({ length: 1800 }, (_, index) => `<p>Reading paragraph ${index}; complete text.</p>`).join("");
  const rows = Array.from({ length: 500 }, (_, index) => `<tr><td>Cell ${index}</td><td>Retained ${index}</td></tr>`).join("");
  const plain = "纯文本完整保留。".repeat(30_000);
  const html = `<div><p>FIRST_READING_BATCH</p>${paragraphs}<table><tbody>${rows}</tbody></table><div>${plain}</div><p>FINAL_READING_BATCH</p></div>`;
  const observations = await installFixture(page, { articleBodies: { 96: html } });
  await page.addInitScript(() => {
    window.addEventListener("nextflux:reading-content", ({ detail }) => {
      const root = detail?.root;
      if (window.__nextfluxTest.firstReadingBatch || !root?.textContent.includes("FIRST_READING_BATCH")) return;
      const first = root.querySelector("p");
      const selection = getSelection(), range = document.createRange();
      range.selectNodeContents(first);
      selection.removeAllRanges(); selection.addRange(range);
      window.__nextfluxTest.firstReadingNode = first;
      window.__nextfluxTest.firstReadingBatch = { complete: root.dataset.readingComplete, hasEnd: root.textContent.includes("FINAL_READING_BATCH") };
    });
  });
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  await expect(completeBody(page)).toBeAttached({ timeout: 30_000 });
  const result = await page.evaluate(() => ({
    first: window.__nextfluxTest.firstReadingBatch,
    sameNode: window.__nextfluxTest.firstReadingNode === document.querySelector(".article-body p"),
    selected: getSelection().toString(),
    text: document.querySelector(".article-body").textContent,
    starts: window.__nextfluxTest.readingRequests.length,
    templatePasses: Object.values(window.__nextfluxTest.sanitizations).reduce((total, value) => total + value, 0),
  }));
  expect(result.first).toEqual({ complete: "false", hasEnd: false });
  expect(result.sameNode).toBe(true);
  expect(result.selected).toBe("FIRST_READING_BATCH");
  expect(result.text).toContain(plain);
  expect(result.text).toContain("FINAL_READING_BATCH");
  expect(result.templatePasses).toBe(0);
  await expect(page.locator(".article-body table")).toHaveCount(1);
  await expect(page.locator(".article-body tr")).toHaveCount(500);
  await expect(page.locator(".article-body td")).toHaveCount(1000);
  await page.locator(".article-scroll-area").evaluate((element) => { element.scrollTop = 240; });
  const patches = await page.evaluate(() => window.__nextfluxTest.results.filter((item) => item.method === "patchState").length);
  await page.locator(".action-buttons button").filter({ has: page.locator("svg.lucide-star") }).click();
  await expect.poll(() => page.evaluate(() => window.__nextfluxTest.results.filter((item) => item.method === "patchState").length)).toBeGreaterThan(patches);
  await settleFrames(page);
  expect(await page.evaluate(() => window.__nextfluxTest.readingRequests.length)).toBe(result.starts);
  expect(await page.evaluate(() => window.__nextfluxTest.firstReadingNode === document.querySelector(".article-body p"))).toBe(true);
  expect(await page.locator(".article-scroll-area").evaluate((element) => element.scrollTop)).toBeGreaterThanOrEqual(200);
  await checkBoundary(page, observations);
});

test("offscreen code stays complete plaintext without computing Shiki; near viewport highlighting and copy retain exact code", async ({ page }) => {
  const code = "\n  const value = '<script>';\n\n\n  console.log(value);\n";
  const html = `<p>CODE_ARTICLE_HEAD</p>${"<p>A long paragraph preceding the offscreen code example.</p>".repeat(150)}<pre><code class="language-javascript">${code.replaceAll("<", "&lt;")}</code></pre>`;
  const observations = await installFixture(page, { articleBodies: { 96: html } });
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  await expect(completeBody(page)).toBeAttached();
  const block = page.locator(".article-body .code-block");
  await expect(block).toHaveAttribute("data-highlighted", "false");
  expect(await block.locator("pre:not([hidden]) code").textContent()).toBe(code);
  await settleFrames(page);
  expect(await page.evaluate(() => window.__nextfluxTest.highlightRequests.length)).toBe(0);
  await block.scrollIntoViewIfNeeded();
  await expect(block).toHaveAttribute("data-highlighted", "true");
  expect(await block.locator("pre:not([hidden]) code").textContent()).toBe(code);
  await block.getByRole("button", { name: "Copy", exact: true }).click();
  expect(await page.evaluate(() => window.__nextfluxTest.clipboardCalls.at(-1))).toBe(code);
  expect(await page.evaluate(() => window.__nextfluxTest.highlightRequests.length)).toBe(1);
  await expect(block.locator("script")).toHaveCount(0);
  await checkBoundary(page, observations);
});

test("a giant code block retains its progressively mounted pre and lines, complete selection and copy without a second full render", async ({ page }) => {
  const code = "const retainedLine = '完整代码';\n".repeat(3000);
  const observations = await installFixture(page, { articleBodies: { 96: `<pre><code class="language-js">${code}</code></pre>` } });
  await page.addInitScript(() => window.addEventListener("nextflux:reading-content", ({ detail }) => {
    if (window.__nextfluxTest.firstCodeNode) return;
    const pre = detail?.root?.querySelector("pre");
    if (!pre?.textContent) return;
    window.__nextfluxTest.firstCodeNode = pre;
    window.__nextfluxTest.firstCodeLine = pre.querySelector(".line");
    window.__nextfluxTest.firstCodePartial = pre.textContent.length;
  }));
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  await expect(completeBody(page)).toBeAttached({ timeout: 30_000 });
  const block = page.locator(".article-body .code-block");
  await expect(block.locator("pre")).toHaveCount(1);
  await expect(block.locator("code .line")).toHaveCount(3001);
  expect(await block.locator("code").textContent()).toBe(code);
  expect(await page.evaluate(() => ({
    pre: window.__nextfluxTest.firstCodeNode === document.querySelector(".code-block pre"),
    line: window.__nextfluxTest.firstCodeLine === document.querySelector(".code-block .line"),
    partial: window.__nextfluxTest.firstCodePartial,
  }))).toEqual({ pre: true, line: true, partial: expect.any(Number) });
  expect(await page.evaluate(() => window.__nextfluxTest.firstCodePartial)).toBeLessThan(code.length);
  await expect(block).toHaveAttribute("data-highlighted", "false");
  await block.getByRole("button", { name: "Copy", exact: true }).click();
  expect(await page.evaluate(() => window.__nextfluxTest.clipboardCalls.at(-1))).toBe(code);
  expect(await page.evaluate(() => window.__nextfluxTest.highlightRequests.length)).toBe(0);
  await checkBoundary(page, observations);
});

test("safe malformed markup retains text and article-local anchors while active media remains placeholders", async ({ page }) => {
  const html = '<p>first<p>second<table><tbody><tr><td>A<td>B</table><script>window.PWNED=1</script><svg><script>window.PWNED=2</script></svg><a href="#end">Jump to final section</a>' + '<p>Long section filler text.</p>'.repeat(250) + '<h2 id="end">ANCHOR_TARGET</h2><video><source src="/proxy/fixture/video"></video><p>&lt;img src=x onerror=evil&gt;</p>';
  const observations = await installFixture(page, { articleBodies: { 96: html } });
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  await expect(completeBody(page)).toBeAttached();
  await expect(page.locator(".article-body td")).toHaveCount(2);
  await expect(page.locator(".article-body")).toContainText("<img src=x onerror=evil>");
  await expect(page.locator(".article-body script, .article-body svg, .article-body iframe, .article-body video, .article-body img")).toHaveCount(0);
  expect(await page.evaluate(() => window.PWNED)).toBeUndefined();
  await page.getByRole("link", { name: "Jump to final section" }).click();
  await expect(page.getByRole("heading", { name: "ANCHOR_TARGET" })).toBeInViewport();
  expect(await page.evaluate(() => window.__nextfluxTest.browserCalls.length)).toBe(0);
  await expect(page.getByRole("button", { name: "加载视频", exact: true })).toBeAttached();
  await checkBoundary(page, observations);
});

test("navigating away from an unfinished article prevents later batches or queued code from appearing in the next article", async ({ page }) => {
  const oldHtml = '<p>OLD_READING_HEAD</p>' + '<p>Old article retained paragraph.</p>'.repeat(6000) + '<pre><code class="language-js">const oldCode = true;</code></pre><p>OLD_READING_END</p>';
  const observations = await installFixture(page, { articleBodies: { 96: oldHtml, 95: "<p>NEW_READING_COMPLETE</p>" } });
  await openFixture(page);
  await page.locator('[data-article-id="96"]').click();
  await expect(page.locator(".article-body")).toContainText("OLD_READING_HEAD");
  await page.evaluate(() => { location.hash = "#/article/95"; });
  await expect(completeBody(page, 95)).toBeAttached();
  await expect(page.locator(".article-body")).toContainText("NEW_READING_COMPLETE");
  await settleFrames(page);
  await expect(page.locator(".article-body")).not.toContainText("OLD_READING");
  expect(await page.evaluate(() => window.__nextfluxTest.highlightRequests.length)).toBe(0);
  await checkBoundary(page, observations);
});
