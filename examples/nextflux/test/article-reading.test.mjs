import test from "node:test";
import assert from "node:assert/strict";
import {
  createArticleRequestGate,
  sameReadingSource,
  createArticleScrollReset,
  mergeArticleForReading,
  startArticleRead,
} from "../src/lib/articleReadingState.js";

const article = (id = 1, changes = {}) => ({
  id, title: "An article", titleText: "An article", url: "https://example.org/post",
  content: "<p>The complete article.</p>", bodyDigest: "body-v1",
  status: "unread", starred: 0, enclosures: [], feed: { id: 2, title: "Feed" },
  ...changes,
});
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

test("near-bottom prefetch and endReached share one synchronous claim; an old query cannot unlock its replacement", async () => {
  const gate = createArticleRequestGate();
  const pending = deferred();
  let generation = 1;
  let requests = 0;
  const fetchPage = async () => {
    const request = gate.acquire(generation);
    if (!request) return;
    requests += 1;
    await pending.promise;
    if (gate.isCurrent(request, generation)) gate.release(request);
  };
  const prefetch = fetchPage();
  const endReached = fetchPage();
  assert.equal(requests, 1);
  generation = 2;
  const replacement = gate.acquire(generation);
  assert.ok(replacement);
  pending.resolve();
  await Promise.all([prefetch, endReached]);
  assert.equal(gate.isCurrent(replacement, generation), true);
  assert.equal(gate.acquire(generation), null);
  gate.release(replacement);
  assert.ok(gate.acquire(generation));
  gate.invalidate();
  assert.equal(gate.isCurrent(replacement, generation), false);
});

test("an unchanged cache snapshot retains the reader object and full-text mode", () => {
  const loaded = article();
  const current = mergeArticleForReading(null, loaded, null);
  assert.equal(current.originalContent, loaded.content);
  assert.equal(mergeArticleForReading(current, article(), current), current);
  const fullText = { ...current, content: "<p>Fetched full text.</p>", shownOriginal: true };
  assert.equal(mergeArticleForReading(fullText, article(), fullText), fullText);
  const refreshed = mergeArticleForReading(fullText, article(1, { bodyDigest: "body-v2", content: "<p>Updated feed content.</p>" }), fullText);
  assert.equal(refreshed.content, fullText.content);
  assert.equal(refreshed.shownOriginal, true);
  assert.equal(refreshed.originalContent, "<p>Updated feed content.</p>");
});

test("a delayed content read preserves newly acknowledged read/starred state and updates title, URL and enclosures", () => {
  const atStart = mergeArticleForReading(null, article(), null);
  const acknowledged = { ...atStart, status: "read", starred: 1 };
  const loaded = article(1, { title: "Updated title", url: "https://example.org/new", enclosures: [{ url: "/proxy/a/b", mime_type: "audio/mpeg" }] });
  const result = mergeArticleForReading(acknowledged, loaded, atStart);
  assert.equal(result.status, "read");
  assert.equal(result.starred, 1);
  assert.equal(result.title, "Updated title");
  assert.equal(result.url, loaded.url);
  assert.deepEqual(result.enclosures, loaded.enclosures);
  const nextArticle = mergeArticleForReading(result, article(2), result);
  assert.equal(nextArticle.id, 2);
  assert.equal(nextArticle.shownOriginal, false);
});

test("quick A to B navigation ignores a late A result and a cancelled read error", async () => {
  const first = deferred();
  const second = deferred();
  const failures = [];
  const published = [];
  let current = null;
  const handlers = {
    getCurrent: () => current,
    publish: (next) => { current = next; published.push(next.id); },
    notFound: () => failures.push("missing"),
    onError: (error) => failures.push(error.message),
  };
  const a = startArticleRead(1, { ...handlers, load: () => first.promise });
  a.cancel();
  const b = startArticleRead(2, { ...handlers, load: () => second.promise });
  second.resolve(article(2));
  await b.done;
  first.resolve(article(1));
  await a.done;
  assert.deepEqual(published, [2]);
  assert.equal(current.id, 2);
  const rejected = deferred();
  const closing = startArticleRead(3, { ...handlers, load: () => rejected.promise });
  closing.cancel();
  rejected.reject(new Error("late failure"));
  await closing.done;
  assert.deepEqual(failures, []);
});

test("reading preparation is reused through metadata/settings changes, and invalidated by article, source, URL or original mode", () => {
  const source = { articleId: 1, html: article().content, baseUrl: article().url, shownOriginal: false };
  for (let index = 0; index < 50; index += 1) {
    assert.equal(sameReadingSource(source, { ...source, status: index % 2 ? "read" : "unread", starred: index % 2, fontSize: 16 + index, bodyDigest: `digest-${index}` }), true);
  }
  for (const changed of [{ shownOriginal: true }, { html: "<p>Original full text.</p>" }, { baseUrl: "https://example.org/new-base" }, { articleId: 2 }]) assert.equal(sameReadingSource(source, { ...source, ...changed }), false);
});

test("scroll resets synchronously once per displayed article and never overwrites immediate user scrolling", () => {
  const reset = createArticleScrollReset();
  const calls = [];
  const viewport = { scrollTop: 500, scrollTo(options) { this.scrollTop = options.top; calls.push(options); } };
  reset(null, 1);
  assert.equal(calls.length, 0);
  reset(viewport, 1);
  assert.equal(viewport.scrollTop, 0);
  viewport.scrollTop = 240;
  reset(viewport, 1);
  assert.equal(viewport.scrollTop, 240);
  assert.equal(calls.length, 1);
  reset(viewport, 2);
  assert.equal(viewport.scrollTop, 0);
  assert.equal(calls.length, 2);
  reset(null, null);
  reset(viewport, 2);
  assert.equal(calls.length, 3);
  assert.deepEqual(calls[0], { top: 0, behavior: "instant" });
});
