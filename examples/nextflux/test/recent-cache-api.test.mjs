import test from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULT_RECENT_CACHE_LIMIT,
  RECENT_CACHE_LIMITS,
  normalizeRecentCacheLimit,
} from "../src/toolbox/recent-cache.mjs";

// Exercise the production Axios/native adapter, including its URL encoding.
// No network request leaves this in-memory ToolBox boundary.
let respond;
globalThis.window ??= {};
globalThis.window.ToolBox = {
  network: { request: async (payload) => {
    assert.ok(respond, "each test installs its own fake server");
    return respond(payload);
  } },
};
const { default: minifluxAPI, getRecentEntries, getEntriesInBatches } = await import("../src/api/miniflux.js");
const { authState } = await import("../src/stores/authStore.js");

const response = (entries, total = entries.length) => ({
  status: 200,
  headers: {},
  body: JSON.stringify({ entries, total }),
});
const ids = (entries) => entries.map((entry) => entry.id);

function articleRows(count) {
  return Array.from({ length: count }, (_, index) => ({
    // Deliberate gaps prove that pagination follows real IDs, not row offsets.
    id: 10_000 - index * 3,
    title: `缓存文章 ${index}`,
    content: `<p>第 ${index} 篇的已保存正文</p>`,
    status: index % 4 === 0 ? "unread" : "read",
    starred: false,
    published_at: "2020-01-01T00:00:00Z",
    changed_at: "2020-01-01T00:00:00Z",
  }));
}

function server(rows, intercept) {
  const calls = [];
  respond = async (payload) => {
    const url = new URL(payload.url);
    assert.equal(payload.method, "GET");
    assert.equal(url.pathname, "/v1/entries");
    assert.equal(url.searchParams.has("offset"), false, "recent pages never use OFFSET");
    assert.equal(url.searchParams.get("order"), "id");
    assert.equal(url.searchParams.get("direction"), "desc");
    const request = {
      url,
      limit: Number(url.searchParams.get("limit")),
      before: Number(url.searchParams.get("before_entry_id")) || 0,
      statuses: url.searchParams.getAll("status"),
    };
    assert.ok(Number.isSafeInteger(request.limit) && request.limit > 0 && request.limit <= 200);
    calls.push(request);
    const overridden = await intercept?.(request, calls);
    if (overridden !== undefined) return overridden;
    const filtered = rows.filter((entry) =>
      request.statuses.includes(entry.status) && (!request.before || entry.id < request.before))
      .sort((left, right) => right.id - left.id);
    return response(filtered.slice(0, request.limit), filtered.length);
  };
  return calls;
}

test("recent cache settings accept only supported tiers and preserve the explicit off value", () => {
  assert.equal(DEFAULT_RECENT_CACHE_LIMIT, 200);
  assert.deepEqual(RECENT_CACHE_LIMITS, [50, 100, 200, 500, 1000]);
  for (const value of [0, "0", ...RECENT_CACHE_LIMITS, ...RECENT_CACHE_LIMITS.map(String)]) {
    assert.equal(normalizeRecentCacheLimit(value), Number(value));
  }
  for (const value of [undefined, null, "", "  ", "abc", true, false, {}, [], -1, 75, NaN, Infinity]) {
    assert.equal(normalizeRecentCacheLimit(value), 200, `invalid value ${String(value)} falls back to 200`);
  }
});

for (const limit of [50, 100, 200, 500, 1000]) {
  test(`recent ${limit} caches exactly the newest available entries and stops without an extra page`, async () => {
    const rows = articleRows(1300);
    // Removed rows are newer than every live article. They must neither enter
    // the window nor consume its quota, and old read entries must be included.
    const calls = server([
      { ...rows[0], id: 12_000, status: "removed" },
      { ...rows[0], id: 11_999, status: "removed" },
      ...rows,
    ]);
    assert.equal(minifluxAPI.getRecentEntries, getRecentEntries);
    const result = await getRecentEntries(limit);
    assert.deepEqual(ids(result), ids(rows.slice(0, limit)));
    assert.ok(result.some((entry) => entry.status === "read" && entry.changed_at.startsWith("2020")),
      "older already-read articles are fetched without a changed_after restriction");
    assert.equal(calls.length, Math.ceil(limit / 200));
    assert.equal(calls.reduce((sum, call) => sum + call.limit, 0), limit);
    let delivered = 0;
    for (const call of calls) {
      assert.deepEqual(call.statuses, ["read", "unread"], "statuses are repeated query parameters");
      assert.equal(call.url.searchParams.has("changed_after"), false);
      assert.equal(call.before, delivered ? rows[delivered - 1].id : 0);
      delivered += call.limit;
    }
  });
}

test("streaming recent pages delivers each article once and returns no duplicate collection", async () => {
  const rows = articleRows(700);
  const calls = server(rows);
  const pages = [];
  const result = await getRecentEntries("500", undefined, async (batch) => pages.push(batch));
  assert.equal(result, undefined);
  assert.deepEqual(pages.map((batch) => batch.length), [200, 200, 100]);
  assert.deepEqual(ids(pages.flat()), ids(rows.slice(0, 500)));
  assert.equal(new Set(ids(pages.flat())).size, 500);
  assert.equal(calls.length, 3);
});

test("a shorter or empty archive finishes after its available recent page", async () => {
  const rows = articleRows(77);
  let calls = server(rows);
  assert.deepEqual(ids(await getRecentEntries(500)), ids(rows));
  assert.equal(calls.length, 1);
  calls = server([]);
  assert.deepEqual(await getRecentEntries(1000), []);
  assert.equal(calls.length, 1);
});

test("turning recent caching off sends no request or streaming callback", async () => {
  const calls = server(articleRows(300));
  assert.deepEqual(await getRecentEntries(0), []);
  let pages = 0;
  assert.equal(await getRecentEntries("0", undefined, () => { pages += 1; }), undefined);
  assert.equal(pages, 0);
  assert.equal(calls.length, 0);
});

test("unknown or missing recent cache settings use the 200-entry window", async () => {
  for (const setting of [undefined, "invalid", 75]) {
    const calls = server(articleRows(400));
    assert.equal((await getRecentEntries(setting)).length, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].limit, 200);
  }
});

test("adaptive shrinking 200 to 100 to 50 to 25 preserves the total limit and cursor", async () => {
  const rows = articleRows(800);
  const calls = server(rows, (call) => call.limit > 25
    ? { status: 504, headers: {}, body: "" }
    : undefined);
  const result = await getRecentEntries(500);
  assert.deepEqual(calls.slice(0, 4).map((call) => call.limit), [200, 100, 50, 25]);
  assert.deepEqual(calls.slice(0, 4).map((call) => call.before), [0, 0, 0, 0]);
  assert.equal(calls.length, 23, "three failed requests plus twenty complete pages");
  assert.deepEqual(ids(result), ids(rows.slice(0, 500)));
  assert.equal(calls.at(-1).before, rows[474].id);
});

test("the final bounded page can be smaller than the normal 25-entry retry minimum", async () => {
  const rows = articleRows(400);
  const calls = server(rows);
  const result = await getEntriesInBatches("/v1/entries", { status: ["read", "unread"] }, undefined, undefined, { maxEntries: 213 });
  assert.deepEqual(calls.map((call) => call.limit), [200, 13]);
  assert.deepEqual(ids(result), ids(rows.slice(0, 213)));
});

test("new entries arriving between pages do not shift the established ID cursor", async () => {
  const rows = articleRows(700);
  const expected = ids(rows.slice(0, 500));
  const calls = server(rows, (_call, allCalls) => {
    if (allCalls.length === 2) rows.unshift({ ...rows[0], id: 13_000 });
  });
  const result = await getRecentEntries(500);
  assert.deepEqual(ids(result), expected);
  assert.equal(calls.length, 3);
  assert.equal(result.some((entry) => entry.id === 13_000), false, "new arrivals belong to the next sync");
});

test("a failed later page propagates the error and never publishes it or requests further pages", async () => {
  const rows = articleRows(700);
  const calls = server(rows, (_call, allCalls) => allCalls.length === 2
    ? { status: 500, headers: {}, body: "" }
    : undefined);
  const pages = [];
  await assert.rejects(getRecentEntries(500, undefined, (batch) => pages.push(batch)), /HTTP 500/);
  assert.equal(calls.length, 2);
  assert.deepEqual(ids(pages.flat()), ids(rows.slice(0, 200)));
});

test("cancelling after a staged page stops before the next request", async () => {
  const calls = server(articleRows(700));
  let active = true;
  let pages = 0;
  await assert.rejects(getRecentEntries(500, () => active, () => {
    pages += 1;
    active = false;
  }), (error) => error.code === "CANCELLED");
  assert.equal(calls.length, 1);
  assert.equal(pages, 1);
});

test("an account change during a response prevents that page from being published", async () => {
  const previous = authState.get();
  const calls = server(articleRows(700), (_call, allCalls) => {
    if (allCalls.length === 1) authState.set({ ...previous, userId: "different-test-account" });
  });
  let pages = 0;
  try {
    await assert.rejects(getRecentEntries(500, undefined, () => { pages += 1; }),
      (error) => error.code === "ACCOUNT_CHANGED");
    assert.equal(calls.length, 1);
    assert.equal(pages, 0);
  } finally {
    authState.set(previous);
  }
});

test("an overlapping server page is rejected instead of counting duplicate articles toward the window", async () => {
  const rows = articleRows(700);
  const calls = server(rows, (_call, allCalls) => allCalls.length === 2
    ? response(rows.slice(199, 399), rows.length)
    : undefined);
  const pages = [];
  await assert.rejects(getRecentEntries(500, undefined, (batch) => pages.push(batch)), /重复/);
  assert.equal(calls.length, 2);
  assert.deepEqual(ids(pages.flat()), ids(rows.slice(0, 200)));
});

test("a server that exceeds its requested page size cannot overfill the recent cache", async () => {
  const rows = articleRows(80);
  const calls = server(rows, () => response(rows));
  let pages = 0;
  await assert.rejects(getRecentEntries(50, undefined, () => { pages += 1; }), /超过请求上限/);
  assert.equal(calls.length, 1);
  assert.equal(pages, 0);
});
