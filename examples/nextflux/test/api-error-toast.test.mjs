import test, { mock } from "node:test";
import assert from "node:assert/strict";
import { toast } from "sonner";

// The real API client over a fake ToolBox network bridge. Callers report
// failures themselves, so the client must never add its own error toast.
const responses = [];
globalThis.window ??= {};
globalThis.window.ToolBox = { network: { request: async () => responses.shift() } };
const { default: minifluxAPI, getEntriesInBatches } = await import("../src/api/miniflux.js");
const toasts = mock.method(toast, "error", () => {});
mock.method(console, "error", () => {});

test("a failed action shows no toast from the API client", async () => {
  toasts.mock.resetCalls();
  responses.push({ status: 500, headers: {}, body: "" });
  await assert.rejects(minifluxAPI.updateEntryStarred({ id: 1 }), /HTTP 500/);
  assert.equal(toasts.mock.callCount(), 0);
});

test("a 502/503/504 page that a retry recovers shows no toast", async () => {
  toasts.mock.resetCalls();
  const page = { entries: [{ id: 3 }], total: 1 };
  responses.push(
    { status: 502, headers: {}, body: "" },
    { status: 503, headers: {}, body: "" },
    { status: 504, headers: {}, body: "" },
    { status: 200, headers: {}, body: JSON.stringify(page) },
  );
  const entries = await getEntriesInBatches("/v1/entries", {});
  assert.deepEqual(entries.map((entry) => entry.id), [3]);
  assert.equal(responses.length, 0);
  assert.equal(toasts.mock.callCount(), 0);
});
