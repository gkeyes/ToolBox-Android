import test, { after } from "node:test";
import assert from "node:assert/strict";
import { SERVER_URL, assertServerUrl, request, responseText, basicAuth, toolboxAxiosAdapter } from "../src/toolbox/network.js";
import { createArticleCache, ROOT_KEY } from "../src/toolbox/cache.js";

const ordinaryValues = new Map([[ROOT_KEY, {
  version: 3, revision: 1, transaction: "previous-account", lastSyncTime: null,
  account: { serverUrl: SERVER_URL, userId: "7" },
  tables: { articles: null, feeds: null, categories: null, feedIcons: null },
}]]);
const ordinaryWrites = [];
let beforeOrdinaryRead;
const authStorage = {
  get: async (key) => structuredClone(ordinaryValues.get(key) ?? null),
  getMany: async (keys) => {
    const values = keys.map((key) => structuredClone(ordinaryValues.get(key) ?? null));
    await beforeOrdinaryRead?.(keys);
    return values;
  },
  async apply({ set = [], remove = [] } = {}) {
    const next = new Map(ordinaryValues);
    for (const { key, value } of set) next.set(key, structuredClone(value));
    for (const key of remove) next.delete(key);
    ordinaryValues.clear();
    for (const [key, value] of next) ordinaryValues.set(key, value);
    ordinaryWrites.push({ set, remove });
  },
  keys: async () => [...ordinaryValues.keys()],
  secure: {},
};

// Only the Worker transport is replaced. Account binding is enforced by the
// production v3 engine; the app has no Node or main-thread fallback.
const cacheRequests = [];
class LoginCacheWorker {
  constructor() {
    this.listeners = new Map();
    this.cache = createArticleCache(authStorage, { autoGc: false });
    this.terminated = false;
  }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(listener);
  }
  removeEventListener(type, listener) { this.listeners.get(type)?.delete(listener); }
  terminate() { this.terminated = true; }
  emit(message) {
    if (this.terminated) return;
    for (const listener of this.listeners.get("message") || []) listener({ data: structuredClone(message) });
  }
  postMessage(value) {
    const message = structuredClone(value);
    cacheRequests.push(message);
    void Promise.resolve().then(() => this.cache[message.method](...message.args)).then(
      (result) => this.emit({ type: "cache:result", id: message.id, ok: true, value: result }),
      (error) => this.emit({ type: "cache:result", id: message.id, ok: false,
        error: { name: error.name, message: error.message, code: error.code } }),
    );
  }
}
const originalWorker = Object.getOwnPropertyDescriptor(globalThis, "Worker");
Object.defineProperty(globalThis, "Worker", { configurable: true, writable: true, value: LoginCacheWorker });
after(() => {
  if (originalWorker) Object.defineProperty(globalThis, "Worker", originalWorker);
  else delete globalThis.Worker;
});

function host(handler) { globalThis.window = { ToolBox: { network: { request: handler }, storage: authStorage } }; }
function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

test("exact server origin rejects alternate hosts, ports and embedded credentials", () => {
  assert.equal(assertServerUrl("/v1/me").href, `${SERVER_URL}/v1/me`);
  for (const url of ["https://miniflux.xiaochen.win.evil.test/v1/me", "http://miniflux.xiaochen.win/v1/me", "https://miniflux.xiaochen.win:8443/v1/me", "https://secret@miniflux.xiaochen.win/v1/me", "//evil.test/v1/me", "https://127.0.0.1/v1/me"]) {
    assert.throws(() => assertServerUrl(url));
  }
});

test("forbidden origins never dispatch the native request, even with auth headers", async () => {
  let calls = 0;
  host(async () => { calls += 1; });
  await assert.rejects(request("https://evil.test/v1/me", { headers: { Authorization: "secret" } }));
  assert.equal(calls, 0);
});

test("missing bridge fails closed without a browser fetch fallback", async () => {
  globalThis.window = {};
  await assert.rejects(request("/v1/me"), /ToolBox/);
});

test("adapter forwards methods, credentials, encoded params, JSON and status", async () => {
  let sent;
  host(async (payload) => { sent = payload; return { status: 201, headers: { "content-type": "application/json" }, body: '{"id":1}', bodyEncoding: "text" }; });
  const result = await toolboxAxiosAdapter({ url: "/v1/feeds", method: "post", params: { title: "甲 & 乙", ignored: undefined }, headers: { "X-Auth-Token": "test-token", "Content-Type": "application/json" }, data: '{"title":"feed"}' });
  assert.equal(new URL(sent.url).searchParams.get("title"), "甲 & 乙");
  assert.equal(new URL(sent.url).searchParams.has("ignored"), false);
  assert.equal(sent.method, "POST");
  assert.equal(sent.headers["X-Auth-Token"], "test-token");
  assert.equal(sent.body, '{"title":"feed"}');
  assert.equal(result.status, 201);
  assert.equal(result.data, '{"id":1}');
  assert.equal(sent.maxResponseBytes, 2 * 1024 * 1024);
});

test("HTTP errors expose status but never response content or request credentials", async () => {
  host(async () => ({ status: 401, body: "sensitive-response", headers: {}, bodyEncoding: "text" }));
  await assert.rejects(toolboxAxiosAdapter({ url: "/v1/me", headers: { "X-Auth-Token": "sensitive-token" } }), (error) => {
    assert.equal(error.response.status, 401);
    assert.equal(error.config, undefined);
    assert.doesNotMatch(JSON.stringify(error), /sensitive/);
    return true;
  });
});

test("native errors retain known codes and discard potentially sensitive messages", async () => {
  host(async () => { throw { code: "QUOTA_EXCEEDED", message: "secret body" }; });
  await assert.rejects(request("/v1/entries"), (error) => {
    assert.equal(error.code, "QUOTA_EXCEEDED");
    assert.doesNotMatch(error.message, /secret/);
    return true;
  });
});

test("base64 responses and Basic credentials preserve Unicode UTF-8", () => {
  const value = "中文 / café";
  const base64 = Buffer.from(value).toString("base64");
  assert.equal(responseText({ bodyEncoding: "base64", body: base64 }), value);
  assert.equal(basicAuth("用户", "密码"), `Basic ${Buffer.from("用户:密码").toString("base64")}`);
});

test("cancelled requests are not dispatched", async () => {
  let calls = 0;
  host(async () => { calls += 1; });
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(request("/v1/me", { signal: controller.signal }), { code: "CANCELLED" });
  assert.equal(calls, 0);
});


test("login verifies the cache account before persisting credentials and restores them before UI", { timeout: 10000 }, async (t) => {
  const { login, restoreAuth, authState } = await import("../src/stores/authStore.js");
  let saved;
  host(async () => ({ status: 200, headers: {}, body: '{"id":7,"username":"reader"}', bodyEncoding: "text" }));
  window.ToolBox.storage.secure = {
    set: async (key, value) => { saved = { key, value }; },
    get: async () => saved?.value || null,
  };
  const readingCache = deferred();
  const releaseCache = deferred();
  t.after(() => { beforeOrdinaryRead = undefined; releaseCache.resolve(); });
  beforeOrdinaryRead = async (keys) => {
    if (keys.includes(ROOT_KEY)) { readingCache.resolve(); await releaseCache.promise; }
  };
  const loggingIn = login(SERVER_URL, "", "", "test-only-token");
  await readingCache.promise;
  assert.equal(saved, undefined, "credentials cannot be saved while account verification is pending");
  assert.equal(authState.get().userId, "");
  assert.deepEqual(cacheRequests.find(({ method }) => method === "initialize").args,
    [{ serverUrl: SERVER_URL, userId: "7" }]);
  releaseCache.resolve();
  await loggingIn;
  assert.equal(saved.key, "nextflux.auth");
  assert.equal(saved.value.token, "test-only-token");
  assert.equal(saved.value.password, "");
  authState.set({});
  await restoreAuth();
  assert.equal(authState.get().userId, 7);
  assert.equal(authState.get().token, "test-only-token");
  saved = null;
  await restoreAuth();
  assert.equal(authState.get().token, "");
  assert.equal(authState.get().userId, "");
});

test("login rejects alternate origin and fails closed when secure persistence fails", async () => {
  const { login, authState } = await import("../src/stores/authStore.js");
  let calls = 0;
  host(async () => { calls += 1; return { status: 200, headers: {}, body: '{"id":7,"username":"reader"}', bodyEncoding: "text" }; });
  window.ToolBox.storage.secure = { set: async () => { throw new Error("secret-native-error"); } };
  await assert.rejects(login("https://evil.test", "", "", "secret"));
  assert.equal(calls, 0);
  await assert.rejects(login(SERVER_URL, "", "", "secret"), /安全存储/);
  assert.equal(authState.get().userId, "");
});

test("login rejects a cache bound to another account without saving credentials or clearing it", async () => {
  const { login, authState } = await import("../src/stores/authStore.js");
  const previousState = structuredClone(authState.get());
  const previousCache = structuredClone([...ordinaryValues]);
  const previousWrites = ordinaryWrites.length;
  let credentialWrites = 0;
  host(async () => ({ status: 200, headers: {}, body: '{"id":8,"username":"another-reader"}', bodyEncoding: "text" }));
  window.ToolBox.storage.secure = { set: async () => { credentialWrites += 1; } };
  await assert.rejects(login(SERVER_URL, "", "", "new-account-token"), { code: "ACCOUNT_CHANGED" });
  assert.equal(credentialWrites, 0);
  assert.deepEqual(authState.get(), previousState);
  assert.equal(authState.get().userId, "");
  assert.deepEqual([...ordinaryValues], previousCache);
  assert.equal(ordinaryWrites.length, previousWrites, "account mismatch cannot delete or rebind the retained cache");
});


test("synchronization paginates bounded requests and returns the complete entry set", async () => {
  const { getEntriesInBatches } = await import("../src/api/miniflux.js");
  const offsets = [];
  host(async ({ url, maxResponseBytes }) => {
    assert.equal(maxResponseBytes, 4 * 1024 * 1024);
    const params = new URL(url).searchParams;
    const offset = Number(params.get("offset"));
    const limit = Number(params.get("limit"));
    offsets.push(offset);
    assert.equal(limit, 1000);
    assert.equal(params.get("order"), "id");
    assert.equal(params.get("direction"), "asc");
    const entries = Array.from({ length: Math.min(limit, 18518 - offset) }, (_, i) => ({ id: offset + i + 1 }));
    return { status: 200, headers: {}, body: JSON.stringify({ total: 18518, entries }), bodyEncoding: "text" };
  });
  const entries = await getEntriesInBatches("/v1/entries", { starred: true });
  assert.equal(entries.length, 18518);
  assert.deepEqual(offsets, Array.from({ length: 19 }, (_, page) => page * 1000), "all 18,518 articles use the upstream 1,000-entry pages");
  assert.equal(entries.at(-1).id, 18518);
});

test("oversized article batches retry at a smaller page size without skipping entries", async () => {
  const { getEntriesInBatches } = await import("../src/api/miniflux.js");
  const sizes = [];
  host(async ({ url, maxResponseBytes }) => {
    assert.equal(maxResponseBytes, 4 * 1024 * 1024);
    const params = new URL(url).searchParams;
    const offset = Number(params.get("offset"));
    const limit = Number(params.get("limit"));
    sizes.push(limit);
    if (limit > 250) throw { code: "QUOTA_EXCEEDED" };
    const entries = Array.from({ length: Math.min(limit, 630 - offset) }, (_, i) => ({ id: offset + i + 1 }));
    return { status: 200, headers: {}, body: JSON.stringify({ total: 630, entries }), bodyEncoding: "text" };
  });
  const entries = await getEntriesInBatches("/v1/entries");
  assert.deepEqual(sizes, [1000, 500, 250, 250, 250]);
  assert.equal(entries.length, 630);
  assert.equal(entries.at(-1).id, 630);
});


test("initial unread sync keeps upstream ordering and only shrinks transport-overflow pages", async () => {
  const { getUnreadEntriesByPage } = await import("../src/api/miniflux.js");
  const requests = [];
  host(async ({ url, maxResponseBytes }) => {
    assert.equal(maxResponseBytes, 4 * 1024 * 1024);
    const params = new URL(url).searchParams;
    const offset = Number(params.get("offset"));
    const limit = Number(params.get("limit"));
    requests.push([offset, limit]);
    assert.equal(params.has("order"), false);
    assert.equal(params.get("direction"), "desc");
    assert.equal(params.get("status"), "unread");
    if (limit > 250) throw { code: "QUOTA_EXCEEDED" };
    return { status: 200, headers: {}, body: JSON.stringify({ total: 1350,
      entries: Array.from({ length: limit }, (_, i) => ({ id: offset + i + 1 })),
    }), bodyEncoding: "text" };
  });
  const page = await getUnreadEntriesByPage(200);
  assert.deepEqual(requests, [[200, 1000], [200, 500], [200, 250]]);
  assert.equal(page.entries[0].id, 201);
  assert.equal(page.entries.at(-1).id, 450);
});

test("pagination stops after account cancellation and rejects incomplete results", async () => {
  const { getEntriesInBatches } = await import("../src/api/miniflux.js");
  let calls = 0;
  host(async () => {
    calls += 1;
    return { status: 200, headers: {}, body: JSON.stringify({ total: 400,
      entries: Array.from({ length: 200 }, (_, i) => ({ id: i + 1 })),
    }), bodyEncoding: "text" };
  });
  await assert.rejects(getEntriesInBatches("/v1/entries", {}, () => {
    if (calls) throw new Error("account cancelled");
  }), /account cancelled/);
  assert.equal(calls, 1);
  host(async () => ({ status: 200, headers: {}, body: '{"total":400,"entries":[]}', bodyEncoding: "text" }));
  await assert.rejects(getEntriesInBatches("/v1/entries"), /不完整/);
});


test("ToolBox rate limiting waits once without dropping or restarting a page", async (t) => {
  const { getEntriesInBatches } = await import("../src/api/miniflux.js");
  t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 0 });
  const offsets = [];
  host(async ({ url }) => {
    offsets.push(Number(new URL(url).searchParams.get("offset")));
    if (offsets.length === 1) throw { code: "RATE_LIMITED" };
    return { status: 200, headers: {}, body: '{"total":1,"entries":[{"id":1}]}', bodyEncoding: "text" };
  });
  const result = getEntriesInBatches("/v1/entries");
  await new Promise(setImmediate);
  assert.equal(offsets.length, 1);
  for (let second = 0; second < 60; second += 1) {
    t.mock.timers.tick(1000);
    await new Promise(setImmediate);
  }
  assert.deepEqual(await result, [{ id: 1 }]);
  assert.deepEqual(offsets, [0, 0]);

  let cancelled = false;
  let requests = 0;
  host(async () => { requests += 1; throw { code: "RATE_LIMITED" }; });
  const running = getEntriesInBatches("/v1/entries", {}, () => {
    if (cancelled) throw new Error("account cancelled");
  });
  const failure = assert.rejects(running, /account cancelled/);
  await new Promise(setImmediate);
  cancelled = true;
  t.mock.timers.tick(1000);
  await failure;
  assert.equal(requests, 1, "logout cancels backoff before another network request");
});


test("repeated server pages fail without imposing a page-count limit", async () => {
  const { getEntriesInBatches } = await import("../src/api/miniflux.js");
  let requests = 0;
  host(async () => {
    requests += 1;
    return { status: 200, headers: {}, body: '{"total":3,"entries":[{"id":1}]}', bodyEncoding: "text" };
  });
  await assert.rejects(getEntriesInBatches("/v1/entries"), /重复返回同一页/);
  assert.equal(requests, 2);
});

test("streamed sync awaits every page consumer and does not return a collected archive", { timeout: 10000 }, async (t) => {
  const { getEntriesInBatches } = await import("../src/api/miniflux.js");
  const ready = [deferred(), deferred()];
  const release = [deferred(), deferred()];
  t.after(() => release.forEach((gate) => gate.resolve()));
  const offsets = [];
  const pages = [];
  const consumed = [];
  const total = 2005;
  host(async ({ url }) => {
    const offset = Number(new URL(url).searchParams.get("offset"));
    assert.equal(consumed.length, offsets.length, "the previous consumer finishes before the next request starts");
    offsets.push(offset);
    return { status: 200, headers: {}, body: JSON.stringify({ total,
      entries: Array.from({ length: Math.min(1000, total - offset) }, (_, index) => ({ id: offset + index + 1 })),
    }), bodyEncoding: "text" };
  });
  const streaming = getEntriesInBatches("/v1/entries", {}, () => {}, async (page) => {
    const index = pages.length;
    pages.push(page);
    if (index < ready.length) {
      ready[index].resolve();
      await release[index].promise;
    }
    consumed.push(index);
  });
  await ready[0].promise;
  assert.deepEqual(offsets, [0]);
  await new Promise(setImmediate);
  assert.deepEqual(offsets, [0], "first staged page applies backpressure");
  release[0].resolve();
  await ready[1].promise;
  assert.deepEqual(offsets, [0, 1000]);
  await new Promise(setImmediate);
  assert.deepEqual(offsets, [0, 1000], "later pages also apply backpressure");
  release[1].resolve();
  assert.equal(await streaming, undefined);
  assert.deepEqual(offsets, [0, 1000, 2000]);
  assert.deepEqual(pages.map((page) => page.length), [1000, 1000, 5]);
  assert.deepEqual(pages.flatMap((page) => page.map(({ id }) => id)), Array.from({ length: total }, (_, index) => index + 1));
});

test("a failed streamed page consumer stops pagination without returning partial success", async () => {
  const { getEntriesInBatches } = await import("../src/api/miniflux.js");
  const offsets = [];
  let batches = 0;
  const failure = new Error("staged cache write failed");
  host(async ({ url }) => {
    const offset = Number(new URL(url).searchParams.get("offset"));
    offsets.push(offset);
    return { status: 200, headers: {}, body: JSON.stringify({ total: 3000,
      entries: Array.from({ length: 1000 }, (_, index) => ({ id: offset + index + 1 })),
    }), bodyEncoding: "text" };
  });
  await assert.rejects(getEntriesInBatches("/v1/entries", {}, () => {}, async () => {
    batches += 1;
    if (batches === 2) throw failure;
  }), (error) => error === failure);
  assert.deepEqual(offsets, [0, 1000]);
  assert.equal(batches, 2);
});

test("cancellation while a streamed consumer is pending prevents the next page", { timeout: 10000 }, async (t) => {
  const { getEntriesInBatches } = await import("../src/api/miniflux.js");
  const ready = deferred();
  const release = deferred();
  t.after(() => release.resolve());
  let cancelled = false;
  let calls = 0;
  host(async () => {
    calls += 1;
    return { status: 200, headers: {}, body: '{"total":2,"entries":[{"id":1}]}', bodyEncoding: "text" };
  });
  const running = getEntriesInBatches("/v1/entries", {}, () => {
    if (cancelled) throw new Error("account cancelled");
  }, async () => {
    ready.resolve();
    await release.promise;
  });
  const rejected = assert.rejects(running, /account cancelled/);
  await ready.promise;
  cancelled = true;
  release.resolve();
  await rejected;
  assert.equal(calls, 1);
});

test("changed, new and starred stream wrappers forward the consumer and retain their filters", async () => {
  const { getChangedEntries, getNewEntries, getAllStarredEntries } = await import("../src/api/miniflux.js");
  const since = new Date("2026-09-09T00:00:00Z");
  const requests = [];
  host(async ({ url }) => {
    requests.push(new URL(url).searchParams);
    return { status: 200, headers: {}, body: '{"total":1,"entries":[{"id":1}]}', bodyEncoding: "text" };
  });
  const pages = [];
  const onPage = async (page) => { pages.push(page); };
  assert.equal(await getChangedEntries(since, () => {}, onPage), undefined);
  assert.equal(await getNewEntries(since, () => {}, onPage), undefined);
  assert.equal(await getAllStarredEntries(() => {}, onPage), undefined);
  assert.equal(requests[0].get("changed_after"), String(Math.floor(since.getTime() / 1000)));
  assert.equal(requests[1].get("after"), String(Math.floor(since.getTime() / 1000)));
  assert.equal(requests[2].get("starred"), "true");
  assert.equal(requests[2].get("status"), "read");
  assert.deepEqual(pages, [[{ id: 1 }], [{ id: 1 }], [{ id: 1 }]]);
});
