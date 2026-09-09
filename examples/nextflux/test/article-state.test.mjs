import test from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { createAutoReadQueue } from "../src/toolbox/auto-read.js";

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const tick = () => new Promise((resolve) => setImmediate(resolve));

function queueFixture(overrides = {}) {
  const timers = new Map();
  const requests = [];
  const commits = [];
  let timerId = 0;
  const queue = createAutoReadQueue({
    run: (task, options) => {
      assert.equal(options.priority, "auto");
      return Promise.resolve().then(() => task(() => {}));
    },
    selectIds: async (ids) => ids,
    send: async (ids, check) => {
      assert.notEqual(check(), false);
      check.beforeRequest();
      requests.push([...ids]);
    },
    save: async (patches) => commits.push(patches),
    schedule: (task, delay) => {
      assert.equal(delay, 75);
      const id = ++timerId;
      timers.set(id, task);
      return id;
    },
    unschedule: (id) => timers.delete(id),
    ...overrides,
  });
  const expire = () => {
    const tasks = [...timers.values()];
    timers.clear();
    for (const task of tasks) task();
  };
  return { queue, timers, requests, commits, expire };
}

test("automatic reads deduplicate until committed and flush at 75 ms or 256 distinct IDs", async () => {
  const fixture = queueFixture();
  const first = fixture.queue.enqueue(1);
  assert.equal(fixture.queue.enqueue("1"), first);
  assert.equal(fixture.timers.size, 1);
  assert.deepEqual(fixture.requests, []);
  fixture.expire();
  await first;
  assert.deepEqual(fixture.requests, [[1]]);
  assert.deepEqual(fixture.commits, [[{ id: 1, status: "read" }]]);

  const pending = Array.from({ length: 257 }, (_, index) => fixture.queue.enqueue(index + 2));
  await Promise.all(pending.slice(0, 256));
  assert.equal(fixture.requests[1].length, 256);
  assert.equal(fixture.timers.size, 1, "the remainder keeps its own bounded window");
  fixture.expire();
  await pending[256];
  assert.deepEqual(fixture.requests[2], [258]);
});

test("manual intent removes a queued automatic ID even while its metadata selection is in flight", async () => {
  const selecting = deferred();
  const fixture = queueFixture({ selectIds: () => selecting.promise });
  const first = fixture.queue.enqueue(1);
  const second = fixture.queue.enqueue(2);
  fixture.expire();
  await tick();
  fixture.queue.cancelUnsent(1);
  selecting.resolve([1, 2]);
  await Promise.all([first, second]);
  assert.deepEqual(fixture.requests, [[2]]);
  assert.deepEqual(fixture.commits, [[{ id: 2, status: "read" }]]);
});

test("native admission throttling makes cancelled IDs removable before a retry payload is rebuilt", async () => {
  const limited = deferred();
  const retry = deferred();
  const sent = [];
  const fixture = queueFixture({
    send: async (ids, check) => {
      check.beforeRequest();
      check.onRateLimited();
      limited.resolve();
      await retry.promise;
      assert.equal(check(), true);
      check.beforeRequest();
      sent.push([...ids]);
    },
  });
  const first = fixture.queue.enqueue(1);
  const second = fixture.queue.enqueue(2);
  fixture.expire();
  await limited.promise;
  fixture.queue.cancelUnsent(1);
  retry.resolve();
  await Promise.all([first, second]);
  assert.deepEqual(sent, [[2]]);
  assert.deepEqual(fixture.commits, [[{ id: 2, status: "read" }]]);
});

test("manual intent arriving before native admission rejection cancels the rejected automatic retry", async () => {
  const dispatched = deferred(); const rejectedAdmission = deferred();
  const fixture = queueFixture({ send: async (_ids, check) => {
    check.beforeRequest(); dispatched.resolve(); await rejectedAdmission.promise;
    check.onRateLimited();
    assert.equal(check(), false, "the earlier manual intent is rechecked when admission rejects");
    throw Object.assign(new Error("intent superseded"), { code: "CANCELLED" });
  } });
  const reading = fixture.queue.enqueue(1); fixture.expire();
  await dispatched.promise;
  fixture.queue.cancelUnsent(1);
  rejectedAdmission.resolve(); await reading; await tick();
  assert.deepEqual(fixture.commits, []);
});

test("an already-sent automatic read stays owned through its durable commit", { timeout: 10000 }, async (t) => {
  const acknowledged = deferred();
  const persisted = deferred();
  const saving = deferred();
  t.after(() => { acknowledged.resolve(); persisted.resolve(); });
  const fixture = queueFixture({
    send: (_ids, check) => { check.beforeRequest(); return acknowledged.promise; },
    save: async () => { saving.resolve(); await persisted.promise; },
  });
  const reading = fixture.queue.enqueue(1);
  fixture.expire();
  await tick();
  fixture.queue.cancelUnsent(1);
  assert.equal(fixture.queue.enqueue(1), reading);
  acknowledged.resolve();
  await saving.promise;
  let completed = false;
  reading.then(() => { completed = true; });
  await tick();
  assert.equal(completed, false);
  persisted.resolve();
  await reading;
});

test("account invalidation cancels a pending window and stale queued work cannot consume a new account's ID", async () => {
  const admitted = [];
  const fixture = queueFixture({
    run: (task) => {
      const completion = deferred();
      admitted.push(async () => {
        try { completion.resolve(await task(() => {})); }
        catch (failure) { completion.reject(failure); }
      });
      return completion.promise;
    },
  });
  const queued = fixture.queue.enqueue(1);
  const rejected = assert.rejects(queued, { code: "ACCOUNT_CHANGED" });
  fixture.expire();
  const waiting = fixture.queue.enqueue(2);
  const waitingRejected = assert.rejects(waiting, { code: "ACCOUNT_CHANGED" });
  fixture.queue.cancelAll(Object.assign(new Error("account changed"), { code: "ACCOUNT_CHANGED" }));
  await Promise.all([rejected, waitingRejected]);
  assert.equal(fixture.timers.size, 0);
  const replacement = fixture.queue.enqueue(1);
  fixture.expire();
  await admitted[0]();
  assert.deepEqual(fixture.requests, []);
  await admitted[1]();
  await replacement;
  assert.deepEqual(fixture.requests, [[1]]);
});

// Exercise the production stores, account scheduler, and cache transactions.
// Only network delivery, toast, and the Worker transport are replaced.
const root = fileURLToPath(new URL("../", import.meta.url));
const data = new Map();
const api = {};
const events = [];
const patchResults = [];
let failRoot = false;
let loseRootReply = false;
let failRootReads = false;
let patchResponseGate = null;
const ROOT_KEY = "nextflux.cache.v3.root";
const storage = {
  get: async (key) => {
    if (failRootReads && key === ROOT_KEY) throw new Error("root readback unavailable");
    return structuredClone(data.get(key) ?? null);
  },
  getMany: async (keys) => {
    if (failRootReads && keys.includes(ROOT_KEY)) throw new Error("root readback unavailable");
    return keys.map((key) => structuredClone(data.get(key) ?? null));
  },
  async apply({ set = [], remove = [] } = {}) {
    assert.ok(set.length + remove.length <= 256);
    if (failRoot && set.some(({ key }) => key === ROOT_KEY)) throw new Error("cache write failed");
    for (const { key, value } of set) data.set(key, structuredClone(value));
    for (const key of remove) data.delete(key);
    if (loseRootReply && set.some(({ key }) => key === ROOT_KEY)) throw new Error("root reply lost after commit");
  },
  set: async (key, value) => data.set(key, structuredClone(value)),
  remove: async (key) => data.delete(key),
  keys: async () => [...data.keys()],
  secure: { get: async () => null, set: async () => {}, remove: async () => {} },
};
Object.defineProperty(globalThis, "navigator", { configurable: true, value: { onLine: true } });
globalThis.window = { addEventListener() {}, removeEventListener() {}, ToolBox: { storage } };
globalThis.ToolBox = window.ToolBox;
globalThis.__nextfluxArticleStateApi = api;
globalThis.__nextfluxArticleStateCacheHook = async (phase, method, result) => {
  if (method !== "patchState") return;
  events.push(`local:${phase}`);
  if (phase === "committed") {
    patchResults.push(result);
    await patchResponseGate?.promise;
  }
};
const bundle = await build({
  absWorkingDir: root,
  stdin: { contents: `
    export * as sync from './src/stores/syncStore.js';
    export * as articles from './src/stores/articlesStore.js';
    export * as auth from './src/stores/authStore.js';
    export * as db from './src/db/storage.js';
    export * as feeds from './src/stores/feedsStore.js';
  `, resolveDir: root },
  bundle: true, write: false, format: "esm", platform: "node",
  alias: { "@": `${root}src` },
  plugins: [{ name: "article-state-boundaries", setup(plugin) {
    plugin.onResolve({ filter: /(?:^|\/)toolbox\/cache-client\.js$/ }, () => ({ path: "cache-client", namespace: "test-cache" }));
    plugin.onLoad({ filter: /.*/, namespace: "test-cache" }, () => ({ resolveDir: root, contents: `
      import { createArticleCache } from './src/toolbox/cache.js';
      export function createWorkerArticleCache(storage) {
        const cache = createArticleCache(storage);
        return new Proxy(cache, { get(target, property) {
          const method = Reflect.get(target, property);
          if (typeof method !== 'function') return method;
          return async (...args) => {
            await globalThis.__nextfluxArticleStateCacheHook('start', property);
            const result = await method.apply(target, args);
            await globalThis.__nextfluxArticleStateCacheHook('committed', property, result);
            return result;
          };
        } });
      }
    ` }));
    plugin.onResolve({ filter: /(?:^|\/)api\/miniflux(?:\.js)?$/ }, () => ({ path: "api", namespace: "test-api" }));
    plugin.onLoad({ filter: /.*/, namespace: "test-api" }, () => ({ contents: "export default globalThis.__nextfluxArticleStateApi;" }));
    plugin.onResolve({ filter: /^sonner$/ }, () => ({ path: "toast", namespace: "test-toast" }));
    plugin.onLoad({ filter: /.*/, namespace: "test-toast" }, () => ({ contents: "export const toast = { error() {} };" }));
  } }],
});
const { sync, articles, auth, db, feeds } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString("base64")}`);
const article = (id, status = "unread") => ({
  id, feedId: 1, title: `Article ${id}`, content: "Body", status, starred: 0,
  published_at: "2026-09-09T00:00:00.000Z",
});
async function resetStores(count = 1) {
  failRoot = false;
  loseRootReply = false;
  failRootReads = false;
  patchResponseGate = null;
  await sync.cancelAccountOperations();
  await db.clearArticleCache();
  auth.authState.set({ serverUrl: "https://example.org", userId: 1, token: "fixture", authType: "token" });
  sync.isOnline.set(true);
  navigator.onLine = true;
  articles.resetArticleState();
  await db.addFeeds([{ id: 1, title: "Feed" }]);
  await db.addArticles(Array.from({ length: count }, (_, index) => article(index + 1)));
  const original = await db.getArticleMetadata(1);
  articles.filteredArticles.set([original]);
  articles.activeArticle.set(original);
  feeds.unreadCounts.set({ 1: count });
  feeds.starredCounts.set({ 1: 0 });
  for (const key of Object.keys(api)) delete api[key];
  api.updateEntriesStatus = async (ids, status, check) => {
    check?.beforeRequest?.();
    events.push(`server:${status}:${ids.join(",")}`);
  };
  api.updateEntryStarred = async () => events.push("server:starred");
  events.length = 0;
  patchResults.length = 0;
  return original;
}

test("acknowledgement and the local patch response precede visible article and count updates", { timeout: 10000 }, async (t) => {
  const original = await resetStores();
  const acknowledgement = deferred();
  api.updateEntriesStatus = async (_ids, _status, check) => {
    check?.beforeRequest?.();
    await acknowledgement.promise;
    events.push("server:confirmed");
  };
  patchResponseGate = deferred();
  const responseGate = patchResponseGate;
  t.after(() => { acknowledgement.resolve(); responseGate.resolve(); });
  const changing = articles.updateArticleStatus(original, "read");
  await tick();
  assert.deepEqual(events, []);
  assert.equal((await db.getArticleMetadata(1)).status, "unread");
  acknowledgement.resolve();
  while (!events.includes("local:committed")) await tick();
  assert.deepEqual(events, ["server:confirmed", "local:start", "local:committed"]);
  assert.equal(articles.activeArticle.get().status, "unread");
  assert.equal(feeds.unreadCounts.get()[1], 1);
  patchResponseGate.resolve();
  await changing;
  assert.equal(articles.activeArticle.get().status, "read");
  assert.equal(feeds.unreadCounts.get()[1], 0);
});

test("a server-confirmed local failure blocks following mutations until recovery without replaying the server request", async () => {
  const original = await resetStores();
  failRoot = true;
  await assert.rejects(articles.updateArticleStatus(original, "read"), { code: "LOCAL_STATE_PENDING" });
  assert.equal(articles.activeArticle.get().status, "unread");
  await assert.rejects(articles.updateArticleStarred(original), /cache write failed|本地|缓存/);
  assert.equal(events.filter((event) => event.startsWith("server:")).length, 1);
  failRoot = false;
  await articles.updateArticleStarred(original);
  assert.equal(events.filter((event) => event === "server:read:1").length, 1);
  const starredRequest = events.indexOf("server:starred");
  assert.ok(events.lastIndexOf("local:committed", starredRequest) >= 0, "recovery commits before the next server mutation");
  assert.equal(articles.activeArticle.get().status, "read");
  assert.equal(articles.activeArticle.get().starred, 1);
  assert.equal(feeds.unreadCounts.get()[1], 0);
});

test("a failed local bookmark save recovers the explicit target before a later toggle reaches the server", async () => {
  const original = await resetStores();
  failRoot = true;
  await assert.rejects(articles.updateArticleStarred(original), { code: "LOCAL_STATE_PENDING" });
  failRoot = false;
  await articles.updateArticleStarred(original);
  assert.equal(events.filter((event) => event === "server:starred").length, 2, "only the two user toggles reach the server");
  assert.equal((await db.getArticleMetadata(1)).starred, 0);
  assert.equal(articles.activeArticle.get().starred, 0);
});

test("recovery publishes acknowledged rows when a lost root reply is later confirmed and patch replay is a no-op", async () => {
  const original = await resetStores();
  loseRootReply = true;
  failRootReads = true;
  await assert.rejects(articles.updateArticleStatus(original, "read"), { code: "LOCAL_STATE_PENDING" });
  assert.equal(articles.activeArticle.get().status, "unread");
  assert.equal(feeds.unreadCounts.get()[1], 1);
  loseRootReply = false;
  failRootReads = false;
  await sync.runAccountOperation(async () => {});
  assert.deepEqual(patchResults.at(-1).articles, [], "the worker settled the original commit before replaying its fields");
  assert.equal(articles.activeArticle.get().status, "read");
  assert.equal(articles.filteredArticles.get()[0].status, "read");
  assert.equal(feeds.unreadCounts.get()[1], 0);
  assert.equal(events.filter((event) => event === "server:read:1").length, 1);
});

test("manual actions pass an unsent automatic batch in the account queue", { timeout: 10000 }, async (t) => {
  await resetStores(257);
  const blocker = deferred();
  const entered = deferred();
  t.after(() => blocker.resolve());
  const busy = sync.runAccountOperation(async () => { entered.resolve(); await blocker.promise; });
  await entered.promise;
  const automatic = Array.from({ length: 256 }, (_, index) => articles.queueArticleRead({ id: index + 1 }));
  const manual = articles.updateArticleStatus({ id: 257 }, "read");
  blocker.resolve();
  await Promise.all([busy, manual, ...automatic]);
  const serverCalls = events.filter((event) => event.startsWith("server:"));
  assert.equal(serverCalls[0], "server:read:257");
  assert.equal(serverCalls.length, 2);
  assert.equal(serverCalls[1].split(",").length, 256);
});

test("manual unread cancels a pending auto window and waits behind a sent auto acknowledgement and commit", { timeout: 10000 }, async (t) => {
  let original = await resetStores();
  const pending = articles.queueArticleRead(original);
  await articles.updateArticleStatus(original, "unread");
  await pending;
  assert.deepEqual(events, [], "no server call is needed when manual unread cancels the unsent read");

  original = await resetStores(256);
  const acknowledgement = deferred();
  const started = deferred();
  t.after(() => acknowledgement.resolve());
  api.updateEntriesStatus = async (ids, status, check) => {
    check?.beforeRequest?.();
    events.push(`server:${status}`);
    if (status === "read") { started.resolve(); await acknowledgement.promise; }
  };
  const automatic = Array.from({ length: 256 }, (_, index) => articles.queueArticleRead({ id: index + 1 }));
  await started.promise;
  const manual = articles.updateArticleStatus(original, "unread");
  await tick();
  assert.deepEqual(events, ["server:read"]);
  acknowledgement.resolve();
  await Promise.all([...automatic, manual]);
  assert.deepEqual(events, ["server:read", "local:start", "local:committed", "server:unread", "local:start", "local:committed"]);
  assert.equal((await db.getArticleMetadata(1)).status, "unread");
  assert.equal(articles.activeArticle.get().status, "unread");
  assert.equal(feeds.unreadCounts.get()[1], 1);
});

test("account invalidation discards an old pending local recovery before the same ID is loaded again", async () => {
  const original = await resetStores();
  failRoot = true;
  await assert.rejects(articles.updateArticleStatus(original, "read"), { code: "LOCAL_STATE_PENDING" });
  await resetStores();
  await articles.updateArticleStarred({ id: 1 });
  assert.equal((await db.getArticleMetadata(1)).status, "unread");
  assert.deepEqual(events, ["server:starred", "local:start", "local:committed"]);
});
