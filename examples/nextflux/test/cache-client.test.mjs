import assert from "node:assert/strict";
import test from "node:test";
import { createWorkerArticleCache } from "../src/toolbox/cache-client.js";

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

function workerHarness(t) {
  const original = Object.getOwnPropertyDescriptor(globalThis, "Worker");
  const workers = [];
  const clients = [];
  class FakeWorker {
    constructor(url, options) {
      this.url = url;
      this.options = options;
      this.listeners = new Map();
      this.messages = [];
      this.waiters = [];
      this.terminated = false;
      workers.push(this);
    }
    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, new Set());
      this.listeners.get(type).add(listener);
    }
    removeEventListener(type, listener) { this.listeners.get(type)?.delete(listener); }
    postMessage(value) {
      const message = structuredClone(value);
      this.messages.push(message);
      for (const waiter of [...this.waiters]) {
        if (!waiter.predicate(message)) continue;
        this.waiters.splice(this.waiters.indexOf(waiter), 1);
        waiter.resolve(message);
      }
    }
    terminate() { this.terminated = true; }
    emit(type, event = {}) {
      for (const listener of [...(this.listeners.get(type) || [])]) listener(event);
    }
    receive(message) { this.emit("message", { data: structuredClone(message) }); }
    request(method) {
      return this.messages.find((message) => message.type === "cache:request" && message.method === method);
    }
    reply(request, value) {
      this.receive({ type: "cache:result", id: request.id, ok: true, value });
    }
    waitForMessage(predicate) {
      const message = this.messages.find(predicate);
      return message ? Promise.resolve(message) : new Promise((resolve) => this.waiters.push({ predicate, resolve }));
    }
  }
  Object.defineProperty(globalThis, "Worker", { configurable: true, writable: true, value: FakeWorker });
  t.after(() => {
    for (const client of clients) client.dispose();
    if (original) Object.defineProperty(globalThis, "Worker", original);
    else delete globalThis.Worker;
  });
  return {
    workers,
    create(storage = {}) {
      const cache = createWorkerArticleCache(storage);
      clients.push(cache);
      return { cache, worker: workers.at(-1) };
    },
  };
}

test("clear cancels old reads while mutations and an in-flight storage reply finish", async (t) => {
  const nativeWrite = deferred();
  const change = { set: [{ key: "nextflux.cache.v3.root", value: { revision: 4 } }] };
  const calls = [];
  const { cache, worker } = workerHarness(t).create({
    apply(value) { calls.push(value); return nativeWrite.promise; },
  });
  assert.equal(worker.url.href, new URL("../src/toolbox/cache-worker.js", import.meta.url).href);
  assert.deepEqual(worker.options, { type: "module" });
  const reading = cache.readArticle(7);
  const catalogReading = cache.getCatalogItem("feedIcons", 2);
  const readingCancelled = assert.rejects(reading, { code: "ACCOUNT_CHANGED" });
  const catalogCancelled = assert.rejects(catalogReading, { code: "ACCOUNT_CHANGED" });
  const mutation = cache.patchState([{ id: 7, status: "read" }]);
  worker.receive({ type: "storage:request", id: "storage:1", method: "apply", args: [change] });
  const clearing = cache.clear();
  await Promise.all([readingCancelled, catalogCancelled]);
  worker.reply(worker.request("readArticle"), { id: 7, content: "old account body" });
  worker.reply(worker.request("getCatalogItem"), { feedId: 2, data: "old icon" });
  nativeWrite.resolve();
  const storageReply = await worker.waitForMessage((message) => message.type === "storage:result");
  assert.equal(storageReply.ok, true);
  assert.equal(storageReply.id, "storage:1");
  assert.deepEqual(calls, [change]);
  worker.reply(worker.request("patchState"), { revision: 4, articles: [{ id: 7, status: "read" }] });
  assert.equal((await mutation).revision, 4);
  worker.reply(worker.request("clear"));
  await clearing;
  assert.equal(worker.terminated, false);
});

test("storage failures retain error code, message and cause through the worker round trip", async (t) => {
  const nativeError = Object.assign(new Error("native write failed"), {
    code: "DISK_FULL",
    cause: Object.assign(new Error("device has no free space"), { code: "ENOSPC" }),
    unrelatedPayload: { doNotCopy: true },
  });
  const { cache, worker } = workerHarness(t).create({ getMany: async () => { throw nativeError; } });
  const reading = cache.readMetadata(9);
  const rejected = assert.rejects(reading, (error) => {
    assert.equal(error.name, "Error");
    assert.equal(error.code, "DISK_FULL");
    assert.equal(error.message, "native write failed");
    assert.ok(error.cause instanceof Error);
    assert.equal(error.cause.code, "ENOSPC");
    assert.equal(error.cause.message, "device has no free space");
    return true;
  });
  worker.receive({ type: "storage:request", id: "storage:2", method: "getMany", args: [["article:9"]] });
  const reply = await worker.waitForMessage((message) => message.type === "storage:result");
  assert.equal(reply.ok, false);
  assert.equal("unrelatedPayload" in reply.error, false);
  worker.receive({ type: "cache:result", id: worker.request("readMetadata").id, ok: false, error: reply.error });
  await rejected;
});

test("worker errors and undecodable messages reject every pending call without restarting", async (t) => {
  const harness = workerHarness(t);
  for (const [eventName, code] of [["error", "CACHE_WORKER_ERROR"], ["messageerror", "CACHE_WORKER_MESSAGE_ERROR"]]) {
    const { cache, worker } = harness.create();
    const reading = assert.rejects(cache.meta(), { code });
    const writing = assert.rejects(cache.updateCatalog({ feeds: [] }), { code });
    worker.emit(eventName, { message: "worker stopped", preventDefault() {} });
    await Promise.all([reading, writing]);
    assert.equal(worker.terminated, true);
    const sent = worker.messages.length;
    await assert.rejects(cache.initialize(), { code });
    assert.equal(worker.messages.length, sent);
  }
  assert.equal(harness.workers.length, 2);
});

test("dispose rejects pending work and ignores late results after terminating the worker", async (t) => {
  const { cache, worker } = workerHarness(t).create();
  const expected = { code: "CACHE_WORKER_TERMINATED" };
  const initializing = assert.rejects(cache.initialize(), expected);
  const reading = assert.rejects(cache.readQueryPage("query:1"), expected);
  const writing = assert.rejects(cache.commitSync("sync:1", { feeds: [], categories: [] }), expected);
  const lateRead = worker.request("readQueryPage");
  cache.dispose();
  await Promise.all([initializing, reading, writing]);
  worker.reply(lateRead, { items: [{ id: 1 }], total: 1 });
  assert.equal(worker.terminated, true);
  assert.ok([...worker.listeners.values()].every((listeners) => listeners.size === 0));
  await assert.rejects(cache.readArticle(1), expected);
  cache.dispose();
});
