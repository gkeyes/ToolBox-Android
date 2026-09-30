import { test as base, expect } from "@playwright/test";

const PRIMARY = "https://media.example.invalid/images/primary";
const FALLBACK = "https://media.example.invalid/images/fallback.png?signature=fixture";
const SECONDARY = "https://media.example.invalid/images/secondary.png";
const SERVER_ARCHIVE = "https://miniflux.xiaochen.win/media/v1/store/fixture-origin/sig";
const ORIGIN = "https://origin.example.invalid/full.png";
const TRANSPARENT_GIF = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";

const test = base.extend({
  page: async ({ page }, use, testInfo) => {
    const pageErrors = [];
    const externalRequests = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await page.route("**/*", async (route) => {
      const url = new URL(route.request().url());
      if (url.origin === "http://127.0.0.1:4175") await route.continue();
      else { externalRequests.push(url.href); await route.abort("blockedbyclient"); }
    });
    await use(page);
    let nativeState = null;
    if (!page.isClosed() && await page.evaluate(() => Boolean(window.mediaCompatFixture))) {
      nativeState = await page.evaluate(() => window.mediaCompatFixture.snapshot());
      await page.evaluate(() => window.mediaCompatFixture.dispose());
      await expect.poll(() => page.evaluate(() => {
        const state = window.mediaCompatFixture.snapshot();
        return [state.activeStreams, state.waitingReads, state.activeObjectUrls.length];
      }), { message: "native streams and Blob URLs are released after the article is disposed" }).toEqual([0, 0, 0]);
    }
    await testInfo.attach("browser-diagnostics", {
      body: JSON.stringify({ pageErrors, externalRequests, nativeState }), contentType: "application/json",
    });
    expect(pageErrors, "uncaught errors from production parser, renderer or image components").toEqual([]);
    expect(externalRequests, "the fixture must never contact an external server").toEqual([]);
  },
});

async function openFixture(page) {
  await page.goto("/media-compat.html");
  await page.waitForFunction(() => Boolean(window.mediaCompatFixture));
}

async function openArticle(page, html) {
  await openFixture(page);
  await page.evaluate((value) => window.mediaCompatFixture.renderArticle(value), html);
}

async function pending(page, source) {
  await expect.poll(() => page.evaluate((value) => window.mediaCompatFixture.pendingRequests(value), source), {
    message: `the production image Hook opens the next candidate: ${source}`,
  }).toBe(1);
}

async function respond(page, source, options = {}) {
  await pending(page, source);
  await page.evaluate(({ source, options }) => window.mediaCompatFixture.respond(source, options), { source, options });
}

async function decodedImage(page, count = 1) {
  const image = page.getByTestId("article-body").locator("img");
  await expect(image).toHaveCount(count);
  for (let index = 0; index < count; index += 1) await expect(image.nth(index)).toBeVisible();
  await expect.poll(() => image.evaluateAll((elements) => elements.map((element) => [element.complete, element.naturalWidth, element.naturalHeight]))).toEqual(Array.from({ length: count }, () => [true, 8, 4]));
  await expect(page.getByRole("button", { name: "重试图片" })).toHaveCount(0);
  return image;
}

async function snapshot(page) {
  return page.evaluate(() => window.mediaCompatFixture.snapshot());
}

test("a 200 HTML src falls back to a real srcset image automatically", async ({ page }) => {
  await openArticle(page, `<p>Before image</p><img src="${PRIMARY}" srcset="${FALLBACK} 2x" alt="HTML fallback"><p>After image</p>`);
  await respond(page, PRIMARY, { type: "text/html; charset=utf-8", body: "html" });
  await respond(page, FALLBACK);
  await decodedImage(page);
  await expect(page.getByText("After image", { exact: true })).toBeVisible();
  const state = await snapshot(page);
  expect(state.requests.map(({ source }) => source)).toEqual([PRIMARY, FALLBACK]);
  expect(state.requests[0].state).toBe("cancelled");
  expect(state.requests[0].cancels).toBeGreaterThanOrEqual(1);
  expect(state.activeStreams).toBe(0);
});

for (const [failure, options] of [
  ["HTTP failure", { status: 404 }],
  ["browser decode failure", { body: "decode" }],
]) {
  test(`${failure} advances to the alternate source without a retry click`, async ({ page }) => {
    await openArticle(page, `<img src="${PRIMARY}" srcset="${FALLBACK} 2x" alt="Candidate fallback">`);
    await respond(page, PRIMARY, options);
    await respond(page, FALLBACK);
    await decodedImage(page);
    const state = await snapshot(page);
    expect(state.requests.map(({ source }) => source)).toEqual([PRIMARY, FALLBACK]);
    if (failure === "browser decode failure") {
      expect(state.createdUrls).toHaveLength(2);
      expect(state.revokedUrls).toEqual([state.createdUrls[0]]);
      expect(state.activeObjectUrls).toEqual([state.createdUrls[1]]);
    }
  });
}

test("a transparent data GIF placeholder displays the real srcset candidate", async ({ page }) => {
  await openArticle(page, `<img src="${TRANSPARENT_GIF}" srcset="${FALLBACK} 2x" alt="Lazy source placeholder">`);
  await respond(page, FALLBACK);
  const image = await decodedImage(page);
  expect(await image.getAttribute("src")).toMatch(/^blob:/);
  expect((await snapshot(page)).requests.map(({ source }) => source)).toEqual([FALLBACK]);
});

test("picture source candidates render when img has no src", async ({ page }) => {
  await openArticle(page, `<picture><source media="(min-width: 1px)" type="image/png" srcset="${SECONDARY} 1x, ${FALLBACK} 2x"><img alt="Picture source only"></picture>`);
  await respond(page, FALLBACK);
  await decodedImage(page);
  expect((await snapshot(page)).requests.map(({ source }) => source)).toEqual([FALLBACK]);
});

test("data-srcset candidates render through the production parser and image Hook", async ({ page }) => {
  await openArticle(page, `<img data-srcset="${SECONDARY} 1x, ${FALLBACK} 2x" alt="Lazy srcset only">`);
  await respond(page, FALLBACK);
  await decodedImage(page);
  expect((await snapshot(page)).requests.map(({ source }) => source)).toEqual([FALLBACK]);
});

test("real PNG bytes decode when Content-Type is application/octet-stream", async ({ page }) => {
  await openArticle(page, `<img src="${PRIMARY}" alt="Generic MIME PNG">`);
  await respond(page, PRIMARY, { type: "application/octet-stream" });
  await decodedImage(page);
  expect((await snapshot(page)).requests[0].state).toBe("completed");
});

for (const type of ["text/html", "application/octet-stream"]) {
  test(`${type} HTML stays failed and the real retry button recovers after the source returns a PNG`, async ({ page }) => {
    await openArticle(page, `<img src="${PRIMARY}" alt="Retry source">`);
    await respond(page, PRIMARY, { type, body: "html" });
    const retry = page.getByRole("button", { name: "重试图片" });
    await expect(retry).toBeVisible();
    await expect(page.getByTestId("article-body").locator("img")).toHaveCount(0);
    await retry.click();
    await respond(page, PRIMARY, { type: "application/octet-stream" });
    await decodedImage(page);
    expect(await page.evaluate((source) => window.mediaCompatFixture.requestCount(source), PRIMARY)).toBe(2);
  });
}

test("unmount during a decoded failure fallback cancels native reads and releases the invalid Blob", async ({ page }) => {
  await openArticle(page, `<img src="${PRIMARY}" srcset="${FALLBACK} 2x" alt="Cancelled fallback">`);
  await respond(page, PRIMARY, { body: "decode" });
  await respond(page, FALLBACK, { hold: true });
  await expect.poll(async () => (await snapshot(page)).waitingReads).toBe(1);
  await page.evaluate(() => window.mediaCompatFixture.unmountArticle());
  await expect(page.getByTestId("media-compat-surface")).toHaveAttribute("data-mounted", "false");
  await expect.poll(async () => {
    const state = await snapshot(page);
    return [state.activeStreams, state.waitingReads, state.activeObjectUrls.length];
  }).toEqual([0, 0, 0]);
  const state = await snapshot(page);
  expect(state.requests[1]).toMatchObject({ source: FALLBACK, state: "cancelled", aborted: true });
  expect(state.requests[1].cancels).toBeGreaterThanOrEqual(1);
  expect(state.revokedUrls).toEqual(state.createdUrls);
});

test("a successful fallback releases its idle Blob after unmount and cache expiry", async ({ page }) => {
  await page.clock.install();
  await openArticle(page, `<img src="${PRIMARY}" srcset="${FALLBACK} 2x" alt="Released fallback">`);
  await respond(page, PRIMARY, { type: "text/html", body: "html" });
  await respond(page, FALLBACK);
  await decodedImage(page);
  const loaded = await snapshot(page);
  expect(loaded.activeObjectUrls).toHaveLength(1);
  await page.evaluate(() => window.mediaCompatFixture.unmountArticle());
  await expect(page.getByTestId("media-compat-surface")).toHaveAttribute("data-mounted", "false");
  await page.clock.fastForward(31_000);
  await expect.poll(async () => (await snapshot(page)).activeObjectUrls).toEqual([]);
  const released = await snapshot(page);
  expect(released.activeStreams).toBe(0);
  expect(released.revokedUrls).toEqual(released.createdUrls);
});

test("a server archive PNG is used before srcset origin media and opens no direct origin request", async ({ page }) => {
  await openArticle(page, `<img src="/media/v1/store/fixture-origin/sig" srcset="${ORIGIN} 2x" alt="Cached archive">`);
  await respond(page, SERVER_ARCHIVE);
  await decodedImage(page);
  expect(await page.evaluate((source) => window.mediaCompatFixture.requestCount(source), SERVER_ARCHIVE)).toBe(1);
  expect(await page.evaluate((source) => window.mediaCompatFixture.requestCount(source), ORIGIN)).toBe(0);
  expect((await snapshot(page)).requests.map(({ source }) => source)).toEqual([SERVER_ARCHIVE]);
});

test("the origin candidate is opened after the server archive fails", async ({ page }) => {
  await openArticle(page, `<img src="/media/v1/store/fixture-origin/sig" srcset="${ORIGIN} 2x" alt="Archive fallback">`);
  await pending(page, SERVER_ARCHIVE);
  expect(await page.evaluate((source) => window.mediaCompatFixture.requestCount(source), ORIGIN)).toBe(0);
  await respond(page, SERVER_ARCHIVE, { status: 404 });
  await respond(page, ORIGIN);
  await decodedImage(page);
  expect((await snapshot(page)).requests.map(({ source }) => source)).toEqual([SERVER_ARCHIVE, ORIGIN]);
});

test("two mounted images share each pending candidate and the successfully decoded Blob", async ({ page }) => {
  await openArticle(page, `<img src="${PRIMARY}" srcset="${FALLBACK} 2x" alt="Shared first"><img src="${PRIMARY}" srcset="${FALLBACK} 2x" alt="Shared second">`);
  await respond(page, PRIMARY, { type: "text/html", body: "html" });
  await respond(page, FALLBACK);
  const images = await decodedImage(page, 2);
  const state = await snapshot(page);
  expect(state.requests.map(({ source }) => source)).toEqual([PRIMARY, FALLBACK]);
  expect(state.createdUrls).toHaveLength(1);
  expect(state.activeObjectUrls).toEqual(state.createdUrls);
  expect(await images.evaluateAll((elements) => elements.map((element) => element.src))).toEqual([state.createdUrls[0], state.createdUrls[0]]);
});

test("account media cleanup cancels a pending stream and does not start the fallback candidate", async ({ page }) => {
  await openArticle(page, `<img src="${PRIMARY}" srcset="${FALLBACK} 2x" alt="Account cleanup">`);
  await respond(page, PRIMARY, { hold: true });
  await expect.poll(async () => (await snapshot(page)).waitingReads).toBe(1);
  await page.evaluate(() => window.mediaCompatFixture.clearSessionMedia());
  await expect(page.getByTestId("article-body").getByRole("status")).toContainText("取消");
  await expect.poll(async () => {
    const state = await snapshot(page);
    return [state.activeStreams, state.waitingReads, state.activeObjectUrls.length];
  }).toEqual([0, 0, 0]);
  const state = await snapshot(page);
  expect(state.requests).toHaveLength(1);
  expect(state.requests[0]).toMatchObject({ source: PRIMARY, state: "cancelled", aborted: true });
  expect(state.requests[0].cancels).toBeGreaterThanOrEqual(1);
});

function sourcePage(media, head = "", closing = "") {
  const text = "正文讨论图像传输、资源寻址与浏览器解码过程，并保留读者需要的完整上下文。".repeat(8);
  return `<!doctype html><html lang="zh"><head><title>Image compatibility article</title>${head}</head><body><article><h1>Image compatibility article</h1><p>${text}</p>${media}<h2>Article context</h2><p>${text}${closing}</p></article></body></html>`;
}

test("redirect and relative base href survive full text selection and resolve both image and link", async ({ page }) => {
  await openFixture(page);
  const url = "https://news.example.invalid/old/story";
  const finalUrl = "https://news.example.invalid/new/article/";
  const resourceBase = "https://news.example.invalid/new/assets/";
  const source = `${resourceBase}images/pixel.png`;
  const html = sourcePage('<img src="images/pixel.png" alt="Redirected image">', '<base href="../assets/">', '<a href="../related/story?from=media#section">Related reading</a>');
  const result = await page.evaluate(async (options) => {
    const document = await window.mediaCompatFixture.fetchWebDocument(options);
    const fullText = await window.mediaCompatFixture.loadBestFullText({
      article: { id: 901, url: options.url },
      api: { async fetchEntryContent() { throw new Error("Fixture Miniflux full text unavailable"); } },
      fetchSource: async () => document,
    });
    window.mediaCompatFixture.renderArticle(fullText.content, fullText.baseUrl);
    return { documentUrl: document.url, documentBase: document.baseUrl, source: fullText.source, baseUrl: fullText.baseUrl };
  }, { html, url, finalUrl });
  expect(result.documentUrl).toBe(finalUrl);
  expect(result.documentBase).toBe(resourceBase);
  expect(result.source).toMatch(/^defuddle/);
  expect(result.baseUrl).toBe(resourceBase);
  await respond(page, source);
  await decodedImage(page);
  await expect(page.getByRole("link", { name: "Related reading" })).toHaveAttribute("href", "https://news.example.invalid/new/related/story?from=media#section");
  expect((await snapshot(page)).requests.map(({ source }) => source)).toEqual([source]);
});

test("real Defuddle extraction retains lazy srcset and picture alternate candidates", async ({ page }) => {
  await openFixture(page);
  const url = "https://media.example.invalid/articles/fulltext";
  const html = sourcePage(`<figure><picture><source srcset="${SECONDARY} 1x"><img data-lazy-srcset="${FALLBACK} 2x" alt="Lazy picture full text"></picture><figcaption>Lazy picture caption</figcaption></figure>`);
  const result = await page.evaluate((input) => {
    const extracted = window.mediaCompatFixture.extractWithDefuddle(input);
    window.mediaCompatFixture.renderArticle(extracted.content, extracted.metadata.baseUrl);
    return { baseUrl: extracted.metadata.baseUrl, content: extracted.content };
  }, { html, url, baseUrl: url });
  expect(result.baseUrl).toBe(url);
  await respond(page, FALLBACK, { type: "text/html", body: "html" });
  await respond(page, SECONDARY);
  const image = await decodedImage(page);
  await expect(image).toHaveAttribute("alt", "Lazy picture full text");
  expect((await snapshot(page)).requests.map(({ source }) => source)).toEqual([FALLBACK, SECONDARY]);
});

test("DW lazy image templates survive real extraction and keep image alt and caption", async ({ page }) => {
  await openFixture(page);
  const url = "https://www.dw.com/zh/fixture-article";
  const source = "https://static.dw.com/image/79414331_906.jpg";
  const html = sourcePage('<figure><img data-format="MASTER_LANDSCAPE" data-id="79414331" data-url="https://static.dw.com/image/79414331_${formatId}.jpg" alt="DW supercomputer image" style="padding-bottom:56.25%;height:0;max-height:0"><figcaption>DW supercomputer caption</figcaption></figure>');
  const extracted = await page.evaluate((input) => {
    const result = window.mediaCompatFixture.extractWithDefuddle(input);
    window.mediaCompatFixture.renderArticle(result.content, result.metadata.baseUrl);
    return { content: result.content, metadata: result.metadata };
  }, { html, url, baseUrl: url });
  expect(extracted.content).toContain("79414331_906.jpg");
  await respond(page, source);
  const image = await decodedImage(page);
  await expect(image).toHaveAttribute("alt", "DW supercomputer image");
  await expect(page.getByText("DW supercomputer caption", { exact: true })).toBeVisible();
  expect((await snapshot(page)).requests.map(({ source }) => source)).toEqual([source]);
});
