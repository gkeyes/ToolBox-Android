import test from "node:test";
import assert from "node:assert/strict";
import {
  SYNC_PAGE_SIZE,
  SYNC_MIN_PAGE_SIZE,
  adaptivePageRequest,
  isRetryablePageError,
  nextEntryCursor,
} from "../src/toolbox/sync-pagination.mjs";

const httpError = (status) => Object.assign(new Error(String(status)), { code: "HTTP_ERROR", response: { status } });

test("sync pages start bounded and shrink on gateway timeout until success", async () => {
  const sizes = [];
  const result = await adaptivePageRequest({
    pageSize: SYNC_PAGE_SIZE,
    minPageSize: SYNC_MIN_PAGE_SIZE,
    sleep: async () => {},
    request: async (size) => {
      sizes.push(size);
      if (size > 50) throw httpError(504);
      return { entries: [{ id: 9 }] };
    },
  });
  assert.deepEqual(sizes, [200, 100, 50]);
  assert.equal(result.pageSize, 50);
});

test("502/503/504 and transport timeouts are retryable, auth errors are not", () => {
  assert.equal(isRetryablePageError(httpError(502)), true);
  assert.equal(isRetryablePageError(httpError(503)), true);
  assert.equal(isRetryablePageError(httpError(504)), true);
  assert.equal(isRetryablePageError({ code: "NETWORK_TIMEOUT" }), true);
  assert.equal(isRetryablePageError(httpError(401)), false);
});

test("descending ID cursor advances without OFFSET and rejects unstable order", () => {
  assert.equal(nextEntryCursor([{ id: 30 }, { id: 20 }, { id: 10 }], 0, "desc"), 10);
  assert.equal(nextEntryCursor([{ id: 9 }, { id: 8 }], 10, "desc"), 8);
  assert.throws(() => nextEntryCursor([{ id: 11 }], 10, "desc"), /游标/);
  assert.throws(() => nextEntryCursor([{ id: 9 }, { id: 9 }], 10, "desc"), /顺序/);
});

test("minimum page receives bounded retries before surfacing a persistent 504", async () => {
  let attempts = 0;
  await assert.rejects(adaptivePageRequest({
    pageSize: 25,
    minPageSize: 25,
    retryDelays: [0, 0],
    sleep: async () => {},
    request: async () => { attempts += 1; throw httpError(504); },
  }), /504/);
  assert.equal(attempts, 3);
});
