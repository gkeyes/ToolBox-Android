import assert from "node:assert/strict";
import test from "node:test";
import { createReportManager } from "../src/toolbox/board/report-manager.mjs";

const copy = (value) => structuredClone(value);
const HOURS = 60 * 60 * 1000;
const ids = (count, first = 1) => Array.from({ length: count }, (_, index) => first + index);
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const tick = () => new Promise((resolve) => setImmediate(resolve));
async function until(predicate) {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    if (predicate()) return;
    await tick();
  }
  assert.ok(predicate(), "expected asynchronous state was not reached");
}
const resultFor = (articles) => ({
  articles: articles.map((article, index) => ({ ...article, number: index + 1, head: article.title, excerpt: article.text })),
  topics: articles.length ? [{ title: "本期主题", gist: "重点", summary: "摘要", articles: [1] }] : [],
  cut: false, total: articles.length, skipped: 0,
});

function fixture({ generate, writeDigest, initialState = null, initialReports = [], recoverIds } = {}) {
  let clock = Date.parse("2026-11-17T00:00:00Z");
  const config = { enabled: true, aiReady: true, fingerprint: "model-a|visible|dedup" };
  const catalog = new Map(ids(3000).map((id) => [id, { id, title: `文章 ${id}`, feedTitle: "源", text: `原文 ${id}`, status: "unread" }]));
  const db = { state: copy(initialState), reports: new Map(initialReports.map((report) => [report.id, copy(report)])), saves: [], reportWrites: [], calls: [] };
  const hooks = {};
  const repository = {
    async loadState() { db.calls.push("loadState"); await hooks.loadState?.(); return copy(db.state); },
    async saveState(state) {
      db.calls.push("saveState"); await hooks.saveState?.(state);
      db.state = copy(state); db.saves.push(copy(state));
    },
    async prune() {
      db.calls.push("prune");
      for (const [id, report] of db.reports) if (report.completedAt <= clock - 48 * HOURS) db.reports.delete(id);
      await hooks.prune?.();
    },
    async listReports() { db.calls.push("listReports"); await hooks.listReports?.(); return copy([...db.reports.values()]); },
    async readReport(id) { db.calls.push("readReport"); await hooks.readReport?.(id); return copy(db.reports.get(id) || null); },
    async saveReport(report) {
      db.calls.push("saveReport"); await hooks.saveReport?.(report);
      db.reports.set(report.id, copy(report)); db.reportWrites.push(copy(report)); return copy(report);
    },
    async removeReport(id) { db.calls.push("removeReport"); db.reports.delete(id); },
    async flush() { db.calls.push("flush"); },
    async clear() { db.calls.push("clear"); db.state = null; db.reports.clear(); },
  };
  const changes = [];
  const generationCalls = [];
  const digestCalls = [];
  const options = {
    repository, now: () => clock, configuration: () => ({ ...config }),
    selectArticles: async (selected) => selected.map((id) => catalog.get(id)).filter((article) => article && article.status === "unread" && !article.hidden).map(copy),
    recoverIds: recoverIds || (async () => []),
    generate: async (args) => {
      generationCalls.push({ articles: copy(args.articles), checkpoint: copy(args.checkpoint), signal: args.signal });
      return generate ? generate(args, generationCalls.length) : resultFor(args.articles);
    },
    writeDigest: async (...args) => {
      digestCalls.push(copy(args[0]));
      return writeDigest ? writeDigest(...args) : "## 本期主题\n这是已生成的报告。[1]";
    },
    onChange: (value) => changes.push(value),
  };
  const manager = createReportManager(options);
  const event = (newIds, extra = {}) => {
    clock += 1000;
    return { outcome: "committed", newEntryIds: newIds, newEntryCount: newIds.length,
      syncedAt: new Date(clock - 20).toISOString(), committedAt: new Date(clock).toISOString(),
      initialSync: false, ...extra };
  };
  return { manager, repository, options, config, catalog, db, hooks, changes, generationCalls, digestCalls, event,
    now: () => clock,
    advance: (duration) => { clock += duration; },
    initialize: (values = {}) => manager.initialize({ baselineAt: clock, ...values }),
    async sync(newIds, extra) { await manager.onSync(event(newIds, extra)); await manager.start(); await tick(); },
  };
}

test("150 articles stay pending and the 151st new eligible article starts exactly one report", async () => {
  const f = fixture();
  await f.initialize();
  await f.sync(ids(150));
  assert.equal(f.generationCalls.length, 0);
  assert.equal(f.manager.getState().pendingIds.length, 150);
  await f.sync([151]);
  assert.equal(f.generationCalls.length, 1);
  assert.deepEqual(f.generationCalls[0].articles.map((article) => article.id), ids(151));
  assert.equal(f.manager.getReports().length, 1);
  assert.equal(f.manager.getState().job, null);
  assert.deepEqual(f.manager.getState().pendingIds, []);
  assert.equal(f.digestCalls.length, 1);
});

test("analysis and digest share one ephemeral AI configuration without storing its transport", async () => {
  const firstStream = () => "first";
  const nextStream = () => "next";
  const firstPolicy = { unit: "utf8", budget: 798976 };
  const f = fixture({
    generate: async ({ articles, stream, inputPolicy }) => {
      assert.equal(stream, firstStream);
      assert.deepEqual(inputPolicy, firstPolicy);
      f.config.stream = nextStream;
      f.config.inputPolicy = { unit: "utf8", budget: 24576 };
      return resultFor(articles);
    },
    writeDigest: async (_result, _signal, _publish, stream) => {
      assert.equal(stream, firstStream, "the report body uses the same service as its analysis");
      return "## 已保存\n本期报告。";
    },
  });
  f.config.stream = firstStream;
  f.config.inputPolicy = firstPolicy;
  await f.initialize();
  await f.sync(ids(151));
  assert.equal(f.manager.getReports().length, 1);
  assert.ok(f.db.saves.every((saved) => !Object.hasOwn(saved.job || {}, "stream")), "cloning saved state succeeds because no function or credential is persisted");
});

test("small successful refreshes accumulate, while failures, status-only updates and duplicate IDs do not count", async () => {
  const f = fixture();
  await f.initialize();
  await f.sync(ids(80));
  await f.sync(ids(80), { updatedCount: 900, newEntryCount: 900 });
  await f.sync(ids(100, 81), { outcome: "failed" });
  await f.sync([], { updatedCount: 1000, newEntryCount: 1000 });
  assert.equal(f.manager.getState().pendingIds.length, 80);
  assert.equal(f.generationCalls.length, 0);
  await f.sync(ids(90, 81));
  assert.equal(f.generationCalls.length, 1);
  assert.equal(f.generationCalls[0].articles.length, 170);
  await f.sync(ids(170));
  assert.equal(f.generationCalls.length, 1, "analyzed but unselected articles are not fresh arrivals again");
  assert.equal(f.manager.getState().pendingIds.length, 0);
});

test("known cache and an initial full sync establish a baseline instead of becoming new report input", async () => {
  const f = fixture();
  await f.initialize({ knownIds: ids(100) });
  await f.sync(ids(100));
  await f.sync(ids(400), { initialSync: true });
  await f.sync(ids(400));
  assert.equal(f.generationCalls.length, 0);
  assert.equal(f.manager.getState().pendingIds.length, 0);
  await f.sync(ids(151, 401));
  assert.equal(f.generationCalls.length, 1);
  assert.deepEqual(f.generationCalls[0].articles.map((article) => article.id), ids(151, 401));
});

test("an empty-cache first sync uses initialEntryIds as its baseline even when newEntryIds is empty", async () => {
  const f = fixture();
  await f.initialize({ knownIds: [] });
  await f.sync([], { initialSync: true, initialEntryIds: ids(500), newEntryCount: 0 });
  assert.equal(f.manager.getState().seen.length, 500);
  assert.equal(f.manager.getState().pendingIds.length, 0);
  assert.equal(f.generationCalls.length, 0);
  await f.sync([...ids(500), ...ids(100, 501)]);
  assert.deepEqual(f.manager.getState().pendingIds, ids(100, 501));
  assert.equal(f.generationCalls.length, 0, "the next sync's overlap cannot count initial historical data as new");
  await f.sync(ids(51, 601));
  assert.equal(f.generationCalls.length, 1);
  assert.deepEqual(f.generationCalls[0].articles.map((article) => article.id), ids(151, 501));
});

test("read or hidden candidates are removed before threshold counting, and force permits a smaller report", async () => {
  const f = fixture();
  await f.initialize();
  await f.sync(ids(150));
  f.catalog.get(1).status = "read";
  f.catalog.get(2).hidden = true;
  await f.sync([151]);
  assert.equal(f.generationCalls.length, 0);
  assert.equal(f.manager.getState().pendingIds.length, 149);
  await f.manager.start({ force: true });
  assert.equal(f.generationCalls.length, 1);
  assert.equal(f.generationCalls[0].articles.length, 149);
  assert.ok(!f.generationCalls[0].articles.some((article) => article.id <= 2));
});

test("one running task freezes its input while new arrivals build one later period", async () => {
  const gate = deferred();
  let concurrent = 0;
  let maximum = 0;
  const f = fixture({ generate: async (args, index) => {
    concurrent += 1; maximum = Math.max(maximum, concurrent);
    try { if (index === 1) await gate.promise; return resultFor(args.articles); }
    finally { concurrent -= 1; }
  } });
  await f.initialize();
  await f.manager.onSync(f.event(ids(151)));
  await until(() => f.generationCalls.length === 1);
  const firstRun = f.manager.start();
  const frozen = f.manager.getState().job;
  f.catalog.get(1).text = "同步后改写的正文";
  await f.manager.onSync(f.event(ids(70, 152)));
  await f.manager.onSync(f.event(ids(80, 222)));
  assert.equal(f.manager.getState().pendingIds.length, 150);
  await f.manager.onSync(f.event([302]));
  assert.equal(f.manager.getState().pendingIds.length, 151);
  assert.equal(f.generationCalls.length, 1);
  assert.deepEqual(f.manager.getState().job.articles, frozen.articles);
  gate.resolve();
  await firstRun;
  await until(() => f.manager.getReports().length === 2);
  assert.equal(maximum, 1);
  assert.deepEqual(f.generationCalls[1].articles.map((article) => article.id), ids(151, 152));
  const reports = f.manager.getReports();
  assert.equal(reports[0].articles[0].text, "原文 1");
  assert.equal(reports[1].periodStart, reports[0].periodEnd);
  assert.ok(reports[1].periodEnd > reports[0].periodEnd);
});

test("analysis failure retains frozen input and checkpoint until explicit retry without swallowing new pending arrivals", async () => {
  const f = fixture({ generate: async (args, index) => {
    if (index === 1) { await args.onCheckpoint({ version: 1, completed: 1 }); throw new Error("AI 连接失败"); }
    assert.deepEqual(args.checkpoint, { version: 1, completed: 1 });
    return resultFor(args.articles);
  } });
  await f.initialize();
  await f.sync(ids(151));
  const failed = f.manager.getState().job;
  assert.equal(failed.status, "error");
  assert.equal(failed.phase, "analysis");
  assert.match(failed.error, /AI 连接失败/);
  await f.sync(ids(50, 152));
  assert.equal(f.generationCalls.length, 1, "a sync does not repeatedly spend requests on a failed job");
  await f.manager.start({ retry: true });
  assert.equal(f.generationCalls.length, 2);
  assert.deepEqual(f.generationCalls[1].articles, failed.articles);
  assert.equal(f.manager.getReports().length, 1);
  assert.deepEqual(f.manager.getState().pendingIds, ids(50, 152));
});

test("digest failure retries only the digest, preserving the successful analysis", async () => {
  let attempts = 0;
  const f = fixture({ writeDigest: async () => { if (++attempts === 1) throw new Error("报告正文断网"); return "恢复的报告正文"; } });
  await f.initialize();
  await f.sync(ids(151));
  assert.equal(f.manager.getState().job.phase, "digest");
  assert.equal(f.manager.getState().job.status, "error");
  await f.manager.start({ retry: true });
  assert.equal(f.generationCalls.length, 1);
  assert.equal(f.digestCalls.length, 2);
  assert.equal(f.manager.getReports()[0].digest, "恢复的报告正文");
});

test("restart after publishing but before clearing the job acknowledges the existing report without more AI", async () => {
  const previous = fixture();
  await previous.initialize();
  await previous.sync(ids(151));
  const beforeClear = previous.db.saves.find((value) => value.job?.phase === "complete");
  assert.ok(beforeClear);
  const published = previous.manager.getReports();
  const restored = fixture({ initialState: beforeClear, initialReports: published,
    generate: () => assert.fail("published report repeated analysis"),
    writeDigest: () => assert.fail("published report repeated digest"),
  });
  await restored.initialize();
  const report = await restored.manager.start();
  assert.equal(report.id, published[0].id);
  assert.equal(restored.manager.getState().job, null);
  assert.equal(restored.manager.getReports().length, 1);
  assert.equal(restored.db.reportWrites.length, 0);
});

test("pause saves completed work, resumes the same period, and collects arrivals without starting a parallel task", async () => {
  const f = fixture({ generate: async (args, index) => {
    if (index > 1) { assert.deepEqual(args.checkpoint, { completed: 1 }); return resultFor(args.articles); }
    await args.onCheckpoint({ completed: 1 });
    await new Promise((resolve, reject) => {
      const abort = () => reject(Object.assign(new Error("cancelled"), { name: "AbortError" }));
      if (args.signal.aborted) abort();
      else args.signal.addEventListener("abort", abort, { once: true });
    });
  } });
  await f.initialize();
  await f.manager.onSync(f.event(ids(151)));
  await until(() => f.manager.getState().job?.checkpoint?.completed === 1);
  const frozen = f.manager.getState().job;
  await f.manager.pause();
  assert.equal(f.manager.getState().job.status, "paused");
  await f.sync(ids(20, 152));
  assert.equal(f.generationCalls.length, 1);
  await f.manager.resume();
  assert.equal(f.generationCalls.length, 2);
  const report = f.manager.getReports()[0];
  assert.equal(report.periodStart, frozen.periodStart);
  assert.equal(report.periodEnd, frozen.periodEnd);
  assert.deepEqual(f.manager.getState().pendingIds, ids(20, 152));
});

test("missing AI configuration and disabled automation stay idle without a restart loop", async () => {
  const f = fixture();
  await f.initialize();
  f.config.aiReady = false;
  await f.sync(ids(151));
  const writes = f.db.saves.length;
  await tick(); await tick();
  assert.equal(f.db.saves.length, writes);
  assert.equal(f.generationCalls.length, 0);
  assert.equal(f.manager.getState().pendingIds.length, 151);
  f.config.aiReady = true;
  await f.manager.resume();
  assert.equal(f.generationCalls.length, 1);
  f.config.enabled = false;
  await f.sync(ids(151, 152));
  await tick();
  assert.equal(f.generationCalls.length, 1);
  assert.equal(f.manager.getState().pendingIds.length, 151, "disabling automatic requests must not discard new arrivals");
  const idleWrites = f.db.saves.length;
  await tick(); await tick();
  assert.equal(f.db.saves.length, idleWrites);
  await f.manager.start({ force: true });
  assert.equal(f.generationCalls.length, 2, "manual generation remains available while automation is disabled");
  assert.equal(f.manager.getState().pendingIds.length, 0);
  f.config.enabled = true;
  await f.sync(ids(151, 152));
  assert.equal(f.generationCalls.length, 2, "replayed IDs cannot re-enter after the manual report");
});

test("an AI or filter change requires an explicit restart and invalidates the old partial generation", async () => {
  const f = fixture({ generate: async (args, index) => {
    if (index === 1) { await args.onCheckpoint({ oldConfiguration: true }); throw new Error("offline"); }
    assert.equal(args.checkpoint, null);
    return resultFor(args.articles);
  } });
  await f.initialize();
  await f.sync(ids(151));
  f.config.fingerprint = "model-b|visible|dedup";
  await f.manager.start();
  assert.equal(f.generationCalls.length, 1);
  await f.manager.start({ retry: true });
  assert.equal(f.generationCalls.length, 2);
  assert.equal(f.manager.getReports()[0].fingerprint, f.config.fingerprint);
});

test("unreported low-volume input survives more than 48 hours while completed reports expire separately", async () => {
  const f = fixture();
  await f.initialize();
  await f.sync(ids(100));
  const periodStart = f.manager.getState().periodStart;
  f.advance(49 * HOURS);
  await f.manager.refresh();
  assert.equal(f.manager.getState().pendingIds.length, 100);
  await f.sync(ids(51, 101));
  assert.equal(f.generationCalls.length, 1);
  assert.equal(f.generationCalls[0].articles.length, 151);
  assert.equal(f.manager.getReports()[0].periodStart, periodStart);
  await f.sync(ids(20, 152));
  f.advance(48 * HOURS + 1);
  await f.manager.refresh();
  assert.equal(f.manager.getReports().length, 0);
  assert.deepEqual(f.manager.getState().pendingIds, ids(20, 152));
});

test("generating, opening and removing a report preserve all source read states and exact final membership", async () => {
  const f = fixture();
  await f.initialize();
  await f.sync(ids(151));
  const published = f.manager.getReports()[0];
  const report = await f.manager.readReport(published.id);
  assert.equal(report.unseen, false);
  assert.deepEqual(report.topics.flatMap((topic) => topic.articles.map((number) => report.articles[number - 1].id)), [1]);
  assert.equal(report.articles.length, 151);
  assert.equal([...f.catalog.values()].filter((article) => article.status === "read").length, 0);
  await f.manager.removeReport(report.id);
  assert.equal(f.manager.getReports().length, 0);
  assert.equal([...f.catalog.values()].filter((article) => article.status === "read").length, 0);
});

test("startup recovery adds only unseen committed IDs and retains existing pending input", async () => {
  const previous = fixture();
  await previous.initialize({ knownIds: ids(20) });
  await previous.sync(ids(60, 21));
  const saved = previous.manager.getState();
  let recoveredFrom;
  const restored = fixture({ initialState: saved, recoverIds: async (at) => { recoveredFrom = at; return ids(170); } });
  await restored.initialize();
  assert.equal(recoveredFrom, saved.observedThrough);
  assert.deepEqual(restored.manager.getState().pendingIds, ids(150, 21));
  await restored.manager.start();
  assert.equal(restored.generationCalls.length, 0, "recovery still respects the strict >150 boundary");
  await restored.sync([171]);
  assert.equal(restored.generationCalls[0].articles.length, 151);
});

test("dispose during a state write prevents every late publication from the old account", async () => {
  const f = fixture();
  await f.initialize();
  const gate = deferred();
  const entered = deferred();
  f.hooks.saveState = async (value) => { if (value.pendingIds.length) { entered.resolve(); await gate.promise; } };
  const syncing = f.manager.onSync(f.event([1]));
  await entered.promise;
  const publications = f.changes.length;
  const disposal = f.manager.dispose();
  gate.resolve();
  await Promise.allSettled([syncing, disposal]);
  await tick();
  assert.equal(f.changes.length, publications, "old write acknowledgement published after account disposal");
  assert.equal(f.generationCalls.length, 0);
});

test("dispose during initialization or a history read suppresses its late result and follow-up writes", async () => {
  const loading = fixture();
  const loadGate = deferred();
  const entered = deferred();
  loading.hooks.loadState = async () => { entered.resolve(); await loadGate.promise; };
  const initialization = loading.initialize();
  await entered.promise;
  const disposal = loading.manager.dispose();
  loadGate.resolve();
  await Promise.allSettled([initialization, disposal]);
  assert.equal(loading.changes.length, 0, "late initialization cannot publish an account after disposal");
  assert.equal(loading.db.saves.length, 0, "late initialization cannot create a fresh journal");

  const f = fixture();
  await f.initialize();
  await f.sync(ids(151));
  const report = f.manager.getReports()[0];
  const readGate = deferred();
  const readStarted = deferred();
  f.hooks.readReport = async () => { readStarted.resolve(); await readGate.promise; };
  const reading = f.manager.readReport(report.id);
  await readStarted.promise;
  const publications = f.changes.length;
  const reportWrites = f.db.reportWrites.length;
  const disposing = f.manager.dispose();
  readGate.resolve();
  await Promise.allSettled([reading, disposing]);
  assert.equal(f.changes.length, publications);
  assert.equal(f.db.reportWrites.length, reportWrites, "late read must not mark an old account's report seen");
});

test("an uncertain checkpoint acknowledgement cannot overwrite a durably completed batch with older memory", async () => {
  const f = fixture({ generate: async (args, index) => {
    if (index === 1) await args.onCheckpoint({ version: 1, completed: 1 });
    else assert.deepEqual(args.checkpoint, { version: 1, completed: 1 });
    return resultFor(args.articles);
  } });
  await f.initialize();
  const save = f.repository.saveState;
  let uncertain = true;
  f.repository.saveState = async (state) => {
    await save(state);
    if (uncertain && state.job?.checkpoint?.completed === 1) {
      uncertain = false;
      throw Object.assign(new Error("native commit reply and immediate readback were lost"), { code: "REPORT_STORAGE_COMMIT_UNCERTAIN" });
    }
  };
  await f.sync(ids(151));
  assert.deepEqual(f.db.state.job.checkpoint, { version: 1, completed: 1 }, "an error-status write erased a durably completed batch");
  assert.deepEqual(f.manager.getState().job.checkpoint, { version: 1, completed: 1 });
  assert.equal(f.manager.getState().job.status, "error");
  await f.manager.start({ retry: true });
  assert.equal(f.manager.getReports().length, 1);
  assert.equal(f.generationCalls.length, 2);
});

test("a lost report-save acknowledgement reuses the committed report without repeating analysis or digest", async () => {
  const f = fixture();
  await f.initialize();
  const save = f.repository.saveReport;
  let failedReply = true;
  f.repository.saveReport = async (report) => {
    const saved = await save(report);
    if (failedReply) {
      failedReply = false;
      throw Object.assign(new Error("lost report commit reply"), { code: "REPORT_STORAGE_COMMIT_UNCERTAIN" });
    }
    return saved;
  };
  await f.sync(ids(151));
  assert.equal(f.manager.getState().job.phase, "complete");
  assert.equal(f.manager.getState().job.status, "error");
  await f.manager.start({ retry: true });
  assert.equal(f.generationCalls.length, 1);
  assert.equal(f.digestCalls.length, 1);
  assert.equal(f.db.reportWrites.length, 1);
  assert.equal(f.manager.getReports().length, 1);
  assert.equal(f.manager.getState().job, null);
});

test("late generator callbacks and completion after account disposal cannot publish or start the digest", async () => {
  const gate = deferred();
  let callbacks;
  const f = fixture({ generate: async (args) => {
    callbacks = args;
    await gate.promise;
    return resultFor(args.articles);
  } });
  await f.initialize();
  await f.manager.onSync(f.event(ids(151)));
  await until(() => Boolean(callbacks));
  const running = f.manager.start();
  const changes = f.changes.length;
  const saves = f.db.saves.length;
  const disposal = f.manager.dispose();
  assert.equal(callbacks.signal.aborted, true);
  callbacks.onProgress({ text: "late output from the previous account" });
  await assert.rejects(callbacks.onCheckpoint({ completed: 1 }), { name: "AbortError" });
  gate.resolve();
  await Promise.allSettled([running, disposal]);
  assert.equal(f.changes.length, changes);
  assert.equal(f.db.saves.length, saves);
  assert.equal(f.digestCalls.length, 0);
  assert.equal(f.db.reportWrites.length, 0);
});

test("an uncertain state stays protected while readback is unavailable and later recovers without an automatic retry", async () => {
  let readbackUnavailable = false;
  const f = fixture({ generate: async (args, index) => {
    if (index === 1) await args.onCheckpoint({ completed: 1 });
    else assert.deepEqual(args.checkpoint, { completed: 1 });
    return resultFor(args.articles);
  } });
  await f.initialize();
  const save = f.repository.saveState;
  let uncertain = true;
  f.repository.saveState = async (state) => {
    await save(state);
    if (uncertain && state.job?.checkpoint?.completed === 1) {
      uncertain = false;
      readbackUnavailable = true;
      throw Object.assign(new Error("commit acknowledgement unknown"), { code: "REPORT_STORAGE_COMMIT_UNCERTAIN" });
    }
  };
  f.hooks.loadState = () => { if (readbackUnavailable) throw new Error("readback unavailable"); };
  await f.sync(ids(151));
  assert.deepEqual(f.db.state.job.checkpoint, { completed: 1 });
  assert.equal(f.manager.getState().job.status, "error", "failed status persistence must not leave an endless running indicator");
  const storedWrites = f.db.saves.length;
  await assert.rejects(f.manager.start({ retry: true }), /readback unavailable/);
  assert.equal(f.db.saves.length, storedWrites, "no stale mutation can cross the unresolved readback barrier");
  readbackUnavailable = false;
  await f.sync([152]);
  assert.equal(f.generationCalls.length, 1, "recovering storage cannot silently restart a failed provider request");
  assert.deepEqual(f.manager.getState().job.checkpoint, { completed: 1 });
  assert.equal(f.manager.getState().job.status, "error");
  await f.manager.start({ retry: true });
  assert.equal(f.generationCalls.length, 2);
  assert.equal(f.manager.getReports().length, 1);
  assert.deepEqual(f.manager.getState().pendingIds, [152]);
});

for (const phase of ["digest", "complete"]) {
  test(`a lost ${phase} phase acknowledgement preserves completed stages on retry`, async () => {
    const f = fixture();
    await f.initialize();
    const save = f.repository.saveState;
    let uncertain = true;
    f.repository.saveState = async (state) => {
      await save(state);
      if (uncertain && state.job?.phase === phase) {
        uncertain = false;
        throw Object.assign(new Error("phase commit reply lost"), { code: "REPORT_STORAGE_COMMIT_UNCERTAIN" });
      }
    };
    await f.sync(ids(151));
    assert.equal(f.manager.getState().job.phase, phase);
    assert.equal(f.manager.getState().job.status, "error");
    await f.manager.start({ retry: true });
    assert.equal(f.generationCalls.length, 1);
    assert.equal(f.digestCalls.length, 1);
    assert.equal(f.manager.getReports().length, 1);
  });
}

test("resuming for a manual request clears suspension without starting an automatic period", async () => {
  const f = fixture();
  await f.initialize();
  await f.manager.pause();
  await f.sync(ids(151));
  assert.equal(f.generationCalls.length, 0);
  await f.manager.resume({ autoStart: false });
  assert.equal(f.generationCalls.length, 0);
  assert.equal(f.manager.getState().job, null);
  f.config.enabled = false;
  await f.manager.start({ force: true });
  assert.equal(f.generationCalls.length, 1);
  assert.equal(f.manager.getReports().length, 1);
});

test("a manual resume joins an already active period instead of waiting then forcing the next pool", async () => {
  const gate = deferred();
  const f = fixture({ generate: async (args) => { await gate.promise; return resultFor(args.articles); } });
  await f.initialize();
  await f.manager.onSync(f.event(ids(151)));
  await until(() => f.generationCalls.length === 1);
  await f.manager.onSync(f.event([152]));
  let resumed = false;
  const resuming = f.manager.resume({ autoStart: false }).then(() => { resumed = true; });
  await tick();
  const joinedWhileRunning = resumed;
  const manual = f.manager.start({ force: true });
  gate.resolve();
  await Promise.all([manual, resuming]);
  assert.equal(joinedWhileRunning, true, "manual resume waited for a live report before allowing start to join it");
  assert.equal(f.generationCalls.length, 1);
  assert.deepEqual(f.manager.getState().pendingIds, [152]);
});

test("a history result is suppressed when disposal happens during marking it seen", async () => {
  const f = fixture();
  await f.initialize();
  await f.sync(ids(151));
  const report = f.manager.getReports()[0];
  const gate = deferred();
  const entered = deferred();
  f.hooks.saveReport = async () => { entered.resolve(); await gate.promise; };
  const reading = f.manager.readReport(report.id);
  await entered.promise;
  const changes = f.changes.length;
  const disposal = f.manager.dispose();
  gate.resolve();
  assert.equal(await reading, null);
  await disposal;
  assert.equal(f.changes.length, changes);
});

for (const failFirstPause of [false, true]) {
  test(`pause waits for aborted-run cleanup before flushing${failFirstPause ? " even when its first state write fails" : ""}`, async () => {
    const cleanup = deferred();
    let abortObserved = false;
    const f = fixture({ generate: async (args) => {
      await args.onCheckpoint({ completed: 1 });
      await new Promise((resolve) => {
        const aborted = () => { abortObserved = true; resolve(); };
        if (args.signal.aborted) aborted();
        else args.signal.addEventListener("abort", aborted, { once: true });
      });
      await cleanup.promise;
      throw Object.assign(new Error("cancelled"), { name: "AbortError" });
    } });
    await f.initialize();
    await f.manager.onSync(f.event(ids(151)));
    await until(() => f.manager.getState().job?.checkpoint?.completed === 1);
    let failed = false;
    f.hooks.saveState = (state) => {
      if (failFirstPause && !failed && state.job?.status === "paused") {
        failed = true;
        throw new Error("pause state write failed");
      }
    };
    let settled = false;
    const pausing = f.manager.pause();
    void pausing.then(() => { settled = true; }, () => { settled = true; });
    await until(() => abortObserved);
    await tick();
    const waitedForCleanup = !settled;
    const flushedBeforeCleanup = f.db.calls.includes("flush");
    cleanup.resolve();
    if (failFirstPause) await assert.rejects(pausing, /pause state write failed/);
    else await pausing;
    assert.equal(waitedForCleanup, true, "pause acknowledged shutdown while its cancelled task could still write");
    assert.equal(flushedBeforeCleanup, false);
    assert.equal(f.db.state.job.status, "paused");
    assert.deepEqual(f.db.state.job.checkpoint, { completed: 1 });
    assert.equal(f.db.calls.at(-1), "flush", "the final native flush must follow every aborted-task write");
    const writes = f.db.saves.length;
    await tick();
    assert.equal(f.db.saves.length, writes);
    assert.equal(f.digestCalls.length, 0);
  });
}
