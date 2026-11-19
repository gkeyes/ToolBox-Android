import test from "node:test";
import assert from "node:assert/strict";
import { createReportStorage, REPORT_RETENTION_MS } from "../src/toolbox/board/report-storage.mjs";
import { createReportManager } from "../src/toolbox/board/report-manager.mjs";

const START = Date.UTC(2026, 10, 17, 8);
const HOUR = 60 * 60 * 1000;
const ACCOUNT = "https://miniflux.example|user-7";
const clone = (value) => JSON.parse(JSON.stringify(value));
const size = (value) => Buffer.byteLength(JSON.stringify(value), "utf8");
const isRoot = (key) => key.startsWith("nextflux.board.history.v1.") && key.endsWith(".root");
const isRootWrite = (change) => (change.set || []).some(({ key }) => isRoot(key));
const ioFailure = () => Object.assign(new Error("simulated native storage failure"), { code: "NETWORK_UNAVAILABLE" });

function memoryStorage(initial = []) {
  const values = new Map([...initial].map(([key, value]) => [key, clone(value)]));
  const calls = { writes: [], reads: [], scans: 0 };
  const hooks = { beforeApply: null, afterApply: null, beforeRead: null };
  return {
    values, calls, hooks,
    async getMany(keys) {
      hooks.beforeRead?.(keys);
      assert.ok(keys.length <= 256, "bounded getMany key count");
      const result = keys.map((key) => values.has(key) ? clone(values.get(key)) : null);
      assert.ok(size(result) <= 1024 * 1024, "bounded getMany response bytes");
      calls.reads.push({ keys: [...keys], bytes: size(result) });
      return result;
    },
    async apply(change) {
      assert.ok(size(change) <= 1024 * 1024, "bounded apply payload bytes");
      assert.ok((change.set?.length || 0) + (change.remove?.length || 0) <= 256, "bounded apply key count");
      calls.writes.push(clone(change));
      await hooks.beforeApply?.(change);
      // A native batch is atomic; failures may happen before it or after its
      // durable commit, but do not expose half of that batch.
      for (const { key, value } of change.set || []) values.set(key, clone(value));
      for (const key of change.remove || []) values.delete(key);
      await hooks.afterApply?.(change);
    },
    async keys() { calls.scans += 1; return [...values.keys()]; },
  };
}

function report(overrides = {}) {
  return {
    id: "report-1", kind: "interval", periodStart: START - HOUR, periodEnd: START,
    completedAt: START, title: "本时段新闻", articles: [
      { id: 11, title: "第一条", number: 1, url: "https://example.test/11", text: "预览一" },
      { id: 12, title: "未纳入主题", number: 2, url: "https://example.test/12", text: "预览二" },
      { id: 13, title: "第三条", number: 3, url: "https://example.test/13", text: "预览三" },
    ],
    topics: [{ title: "一个主题", articles: [1, 3], summary: "主题摘要" }],
    digest: "报告正文 [1] [3]", ...overrides,
  };
}

test("large reports and unexpired task snapshots use bounded documents and survive restart", async () => {
  const storage = memoryStorage();
  const repository = createReportStorage(storage, ACCOUNT, { now: () => START });
  // Include non-ASCII UTF-8, quotes, escapes and pairs near chunk boundaries.
  const text = ("资讯🙂\\\"\n".repeat(10000) + "x".repeat(49145) + "🌏").repeat(8);
  assert.ok(size(text) > 1024 * 1024);
  const state = { pending: [101, 102], activeJob: { phase: "digest", text }, observedThrough: START };
  await repository.saveState(state);
  const saved = await repository.saveReport(report({ articles: [{ id: 11, number: 1, text }], digest: text }));
  assert.equal(saved.expiresAt, START + REPORT_RETENTION_MS);
  await repository.flush();
  const restarted = createReportStorage(memoryStorage(storage.values), ACCOUNT, { now: () => START });
  assert.deepEqual(await restarted.loadState(), state);
  assert.deepEqual(await restarted.readReport("report-1"), saved);
  const listing = await restarted.listReports();
  assert.equal(listing.length, 1);
  assert.equal(listing[0].includedCount, 1);
  assert.equal(listing[0].unseen, true);
  assert.ok(size(listing) < 1024);
  assert.equal("articles" in listing[0], false);
  assert.equal("ref" in listing[0], false);
  assert.ok(storage.calls.writes.filter((change) => change.set?.some(({ key }) => /\.p\d+$/.test(key))).length > 4);
  assert.ok([...storage.values.keys()].every((key) => key.length < 180));
});

test("reports expire at exactly 48 hours after first completion while task state has no TTL", async () => {
  let time = START;
  const storage = memoryStorage();
  const repository = createReportStorage(storage, ACCOUNT, { now: () => time });
  const state = { pendingIds: [1, 2], job: { phase: "analysis", checkpoint: { batch: 3 } } };
  await repository.saveState(state);
  await repository.saveReport(report());
  time += HOUR;
  await repository.saveReport(report({ id: "report-2", kind: "today", completedAt: time }));
  time += HOUR;
  const updated = await repository.saveReport(report({ completedAt: time, expiresAt: time + 999999999, unseen: false, digest: "更新后正文" }));
  assert.equal(updated.completedAt, START);
  assert.equal(updated.expiresAt, START + REPORT_RETENTION_MS);
  assert.deepEqual((await repository.listReports()).map(({ id, unseen }) => ({ id, unseen })), [
    { id: "report-2", unseen: true }, { id: "report-1", unseen: false },
  ]);
  time = START + REPORT_RETENTION_MS - 1;
  assert.ok(await repository.readReport("report-1"));
  time += 1;
  assert.equal(await repository.readReport("report-1"), null);
  assert.deepEqual((await repository.listReports()).map(({ id }) => id), ["report-2"]);
  time += HOUR;
  assert.equal(await repository.prune(), 1);
  assert.deepEqual(await repository.listReports(), []);
  time += 500 * HOUR;
  await repository.prune();
  assert.deepEqual(await repository.loadState(), state);
});

test("an already expired completed job is acknowledged without republishing or changing its completion time", async () => {
  let time = START;
  const storage = memoryStorage();
  const repository = createReportStorage(storage, ACCOUNT, { now: () => time });
  await repository.saveReport(report());
  time += REPORT_RETENTION_MS;
  const acknowledged = await repository.saveReport(report({ completedAt: time, unseen: false }));
  assert.equal(acknowledged.completedAt, START);
  assert.equal(acknowledged.expiresAt, time);
  assert.deepEqual(await repository.listReports(), []);
  const recovered = await repository.saveReport(report({ id: "old-complete", completedAt: START }));
  assert.equal(recovered.completedAt, START);
  assert.equal(recovered.expiresAt, time);
  assert.equal(await repository.readReport("old-complete"), null);
});

test("same IDs are upserted once and account clear never changes another account or article cache", async () => {
  const storage = memoryStorage([["nextflux.cache.v3.root", { untouched: true }]]);
  const first = createReportStorage(storage, ACCOUNT, { now: () => START });
  const second = createReportStorage(storage, "https://another.example|user-7", { now: () => START });
  await first.saveState({ account: "first" });
  await first.saveReport(report());
  await first.saveReport(report({ digest: "替换后的正文", unseen: false }));
  assert.equal((await first.listReports()).length, 1);
  await second.saveState({ account: "second" });
  const secondReport = await second.saveReport(report({ digest: "另一个账号的正文" }));
  await first.clear();
  assert.equal(await first.loadState(), null);
  assert.deepEqual(await first.listReports(), []);
  assert.deepEqual(await second.loadState(), { account: "second" });
  assert.deepEqual(await second.readReport("report-1"), secondReport);
  assert.deepEqual(storage.values.get("nextflux.cache.v3.root"), { untouched: true });
  assert.equal([...storage.values.keys()].filter(isRoot).length, 1);
});

test("failure halfway through a large staged write preserves the committed report and state", async () => {
  const storage = memoryStorage();
  const repository = createReportStorage(storage, ACCOUNT, { now: () => START });
  await repository.saveState({ checkpoint: "safe" });
  const original = await repository.saveReport(report());
  const rootKey = [...storage.values.keys()].find(isRoot);
  const originalRoot = clone(storage.values.get(rootKey));
  let batches = 0;
  storage.hooks.beforeApply = (change) => {
    if (change.set?.some(({ key }) => /\.p\d+$/.test(key)) && ++batches === 2) throw ioFailure();
  };
  await assert.rejects(repository.saveReport(report({ digest: "大数据🙂".repeat(300000) })), { code: "NETWORK_UNAVAILABLE" });
  assert.deepEqual(storage.values.get(rootKey), originalRoot);
  storage.hooks.beforeApply = null;
  const restarted = createReportStorage(memoryStorage(storage.values), ACCOUNT, { now: () => START });
  assert.deepEqual(await restarted.readReport("report-1"), original);
  assert.deepEqual(await restarted.loadState(), { checkpoint: "safe" });
  const beforePrune = storage.values.size;
  await repository.prune();
  assert.ok(storage.values.size < beforePrune, "orphan staged chunks are collected");
  assert.deepEqual(await repository.readReport("report-1"), original);
});

test("failed root publication retains the old checkpoint and a later write recovers", async () => {
  const storage = memoryStorage();
  const repository = createReportStorage(storage, ACCOUNT, { now: () => START });
  await repository.saveState({ checkpoint: 1 });
  storage.hooks.beforeApply = (change) => { if (isRootWrite(change)) throw ioFailure(); };
  await assert.rejects(repository.saveState({ checkpoint: 2, text: "x".repeat(1200000) }), { code: "NETWORK_UNAVAILABLE" });
  storage.hooks.beforeApply = null;
  assert.deepEqual(await repository.loadState(), { checkpoint: 1 });
  await repository.saveState({ checkpoint: 3 });
  const restarted = createReportStorage(memoryStorage(storage.values), ACCOUNT, { now: () => START });
  assert.deepEqual(await restarted.loadState(), { checkpoint: 3 });
  assert.equal([...storage.values.values()].filter((value) => value?.kind === "state").length, 1);
});

test("lost native commit replies are read back, and an uncertain commit is recovered on restart", async () => {
  const storage = memoryStorage();
  const repository = createReportStorage(storage, ACCOUNT, { now: () => START });
  await repository.saveState({ checkpoint: 1 });
  storage.hooks.afterApply = (change) => {
    if (isRootWrite(change)) { storage.hooks.afterApply = null; throw ioFailure(); }
  };
  await repository.saveState({ checkpoint: 2 });
  assert.deepEqual(await repository.loadState(), { checkpoint: 2 });
  storage.hooks.afterApply = (change) => {
    if (isRootWrite(change)) {
      storage.hooks.afterApply = null;
      storage.hooks.beforeRead = () => { throw ioFailure(); };
      throw ioFailure();
    }
  };
  await assert.rejects(repository.saveState({ checkpoint: 3 }), { code: "REPORT_STORAGE_COMMIT_UNCERTAIN" });
  storage.hooks.beforeRead = null;
  const restarted = createReportStorage(memoryStorage(storage.values), ACCOUNT, { now: () => START });
  assert.deepEqual(await restarted.loadState(), { checkpoint: 3 });
  await restarted.saveState({ checkpoint: 4 });
  assert.deepEqual(await restarted.loadState(), { checkpoint: 4 });
});

test("corrupted chunks, missing documents and mismatched accounts report errors without resetting data", async () => {
  const original = memoryStorage();
  const repository = createReportStorage(original, ACCOUNT, { now: () => START });
  await repository.saveState({ checkpoint: "alpha" });
  await repository.saveReport(report());
  const rootKey = [...original.values.keys()].find(isRoot);
  const root = original.values.get(rootKey);
  const cases = [
    ["checksum", (storage) => {
      const key = `${root.stateRef}.p0`;
      storage.values.set(key, storage.values.get(key).replace("alpha", "bravo"));
    }, "REPORT_STORAGE_INVALID"],
    ["missing chunk", (storage) => storage.values.delete(`${root.stateRef}.p0`), "REPORT_STORAGE_INVALID"],
    ["header account", (storage) => {
      storage.values.get(root.stateRef).accountKey = "another-account";
    }, "REPORT_STORAGE_ACCOUNT_MISMATCH"],
    ["root account", (storage) => {
      storage.values.get(rootKey).accountKey = "another-account";
    }, "REPORT_STORAGE_ACCOUNT_MISMATCH"],
  ];
  for (const [label, damage, code] of cases) {
    const storage = memoryStorage(original.values);
    damage(storage);
    const before = clone([...storage.values]);
    const restarted = createReportStorage(storage, ACCOUNT, { now: () => START });
    await assert.rejects(restarted.loadState(), { code }, label);
    assert.deepEqual([...storage.values], before, `${label}: no silent reset`);
  }
});

test("shared account writers serialize, freeze inputs and flush all already queued mutations", async () => {
  const storage = memoryStorage();
  const first = createReportStorage(storage, ACCOUNT, { now: () => START });
  const second = createReportStorage(storage, ACCOUNT, { now: () => START });
  const state = { pending: [1], checkpoint: "captured" };
  const savedState = first.saveState(state);
  state.pending.push(2); state.checkpoint = "changed after save";
  const savedReport = second.saveReport(report());
  await second.flush();
  await Promise.all([savedState, savedReport]);
  assert.deepEqual(await first.loadState(), { pending: [1], checkpoint: "captured" });
  assert.equal((await first.listReports()).length, 1);
  const scans = storage.calls.scans;
  await Promise.all([first.saveState({ sequence: 1 }), second.saveState({ sequence: 2 })]);
  assert.deepEqual(await second.loadState(), { sequence: 2 });
  assert.equal(storage.calls.scans, scans, "ordinary checkpoints retire known documents without scanning the article cache");
});

test("garbage collection failure preserves the published value and can be retried", async () => {
  const storage = memoryStorage();
  const repository = createReportStorage(storage, ACCOUNT, { now: () => START });
  await repository.saveState({ checkpoint: 1 });
  storage.hooks.beforeApply = (change) => { if (change.remove?.length) throw ioFailure(); };
  await repository.saveState({ checkpoint: 2 });
  assert.deepEqual(await repository.loadState(), { checkpoint: 2 });
  assert.equal([...storage.values.values()].filter((value) => value?.kind === "state").length, 2);
  await assert.rejects(repository.prune(), { code: "NETWORK_UNAVAILABLE" });
  storage.hooks.beforeApply = null;
  await repository.prune();
  assert.deepEqual(await repository.loadState(), { checkpoint: 2 });
  assert.equal([...storage.values.values()].filter((value) => value?.kind === "state").length, 1);
});

test("invalid snapshots and report metadata are rejected before altering committed data", async () => {
  const storage = memoryStorage();
  const repository = createReportStorage(storage, ACCOUNT, { now: () => START });
  await repository.saveState({ checkpoint: 1 });
  const cycle = {}; cycle.self = cycle;
  await assert.rejects(repository.saveState(cycle), { code: "INVALID_REQUEST" });
  await assert.rejects(repository.saveState({ value: Infinity }), { code: "INVALID_REQUEST" });
  await assert.rejects(repository.saveReport(report({ completedAt: undefined })), { code: "INVALID_REQUEST" });
  await assert.rejects(repository.saveReport(report({ periodEnd: START - 2 * HOUR })), { code: "INVALID_REQUEST" });
  assert.deepEqual(await repository.loadState(), { checkpoint: 1 });
  assert.deepEqual(await repository.listReports(), []);
});

test("eight half-hour idle syncs keep every report watermark within the idle storage budget", async () => {
  let time = START;
  const storage = memoryStorage();
  const repository = createReportStorage(storage, ACCOUNT, { now: () => time });
  const manager = createReportManager({ repository, now: () => time, selectArticles: async () => [],
    configuration: () => ({ enabled: true, aiReady: true }) });
  await manager.initialize({ baselineAt: time });
  await manager.resume();
  storage.calls.reads.length = 0;
  storage.calls.writes.length = 0;
  storage.calls.scans = 0;
  for (let round = 0; round < 8; round++) {
    time += HOUR / 2;
    await manager.onSync({ outcome: "committed", syncedAt: time, committedAt: time, newEntryIds: [] });
    await manager.start();
    assert.equal(manager.getState().observedThrough, time);
  }
  assert.equal(storage.calls.scans, 0);
  // The 1.32.4 path made 144 host calls, including 64 writes.
  assert.ok(storage.calls.reads.length + storage.calls.writes.length <= 72);
  assert.ok(storage.calls.writes.length <= 32);
  assert.equal((await repository.loadState()).observedThrough, time);
});
