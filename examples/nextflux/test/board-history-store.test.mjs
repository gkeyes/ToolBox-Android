import test from "node:test";
import assert from "node:assert/strict";
import { register } from "node:module";
import { atom } from "nanostores";
import { boardInputPolicy } from "../src/toolbox/ai-context.mjs";

// Exercise the real history binding, manager, document storage and generator.
// Only the external account/RSS/runtime inputs and AI/native transports are
// controlled. Each fixture imports a separate binding instance and atoms.
const sourceRoot = new URL("../src/", import.meta.url).href;
const stubs = {
  "@/stores/authStore.js": "export const authState = f.authState;",
  "@/stores/settingsStore.js": "export const settingsState = f.settingsState;",
  "@/stores/syncStore.js": "export const syncCommitted = f.syncCommitted;",
  "@/db/storage.js": `export const getArticleIds = (...args) => f.db.getArticleIds(...args);
    export const getCachedArticleMetadata = (...args) => f.db.getCachedArticleMetadata(...args);
    export const getFeeds = (...args) => f.db.getFeeds(...args);
    export const getLastSyncTime = (...args) => f.db.getLastSyncTime(...args);`,
  "@/api/openai.js": "export const streamChat = (...args) => f.stream(...args);",
  "../foreground.js": "export const runtimeClosing = f.runtimeClosing; export const runtimeForeground = f.runtimeForeground;",
};
register("data:text/javascript," + encodeURIComponent(`
const root = ${JSON.stringify(sourceRoot)};
const stubs = ${JSON.stringify(stubs)};
export async function resolve(specifier, context, next) {
  const parent = context.parentURL?.startsWith(root) ? new URL(context.parentURL) : null;
  const id = parent?.searchParams.get("historyBinding");
  if (id && specifier in stubs) {
    const source = "const f = globalThis.__historyBindings.get(" + JSON.stringify(id) + ");" + stubs[specifier];
    return { url: "data:text/javascript," + encodeURIComponent(source), shortCircuit: true };
  }
  if (id && specifier.startsWith(".")) {
    const resolved = new URL(specifier, context.parentURL);
    if (resolved.href.startsWith(root)) resolved.searchParams.set("historyBinding", id);
    return next(resolved.href, context);
  }
  return next(specifier, context);
}`));

globalThis.__historyBindings = new Map();
let sequence = 0;
const clone = (value) => structuredClone(value);
const tick = () => new Promise((resolve) => setImmediate(resolve));
const ids = (count, first = 1) => Array.from({ length: count }, (_, index) => first + index);
const deferred = () => {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
};

async function fixture(t, { automatic = false } = {}) {
  const id = String(++sequence);
  const serverUrl = "https://history.example";
  const catalogs = new Map();
  const releases = [];
  const values = new Map();
  const f = {
    authState: atom({ serverUrl, userId: 7 }),
    settingsState: atom({ autoBoardEnabled: automatic, aiApiKey: "fixture-key", aiModel: "fixture-model",
      aiBaseUrl: "https://ai.example/v1", hideDuplicateArticles: false, showHiddenFeeds: false }),
    syncCommitted: atom(null), runtimeClosing: atom(false), runtimeForeground: atom(false),
    hooks: {}, aiCalls: [], publications: [],
    gate() { const gate = deferred(); releases.push(gate.resolve); return gate; },
    account(userId = f.authState.get().userId) { return `${serverUrl}|${userId}`; },
    catalog(userId = f.authState.get().userId) {
      const key = String(userId);
      if (!catalogs.has(key)) catalogs.set(key, { rows: [], syncedAt: new Date(Date.now() - 60000) });
      return catalogs.get(key);
    },
    addArticles(articleIds, userId = f.authState.get().userId) {
      const catalog = f.catalog(userId);
      catalog.rows.push(...articleIds.map((articleId) => ({ id: articleId, feedId: 1, status: "unread",
        title: `文章 ${articleId}`, titleText: `文章 ${articleId}`, previewText: `原文预览 ${articleId}`,
        url: `https://example.test/${userId}/${articleId}`, created_at: new Date(Date.now() - 1000).toISOString(),
        published_at: new Date(Date.now() - 2000).toISOString() })));
    },
    event(articleIds, extra = {}) {
      const at = Date.now() - 10000;
      return { outcome: "committed", initialSync: false, newEntryIds: articleIds, newEntryCount: articleIds.length,
        syncedAt: new Date(at - 20).toISOString(), committedAt: new Date(at).toISOString(), ...extra };
    },
    emit(event) { f.catalog().syncedAt = new Date(event.syncedAt); f.syncCommitted.set(event); },
  };
  f.db = {
    async getArticleIds() {
      const userId = String(f.authState.get().userId);
      const articleIds = f.catalog(userId).rows.map((row) => row.id);
      await f.hooks.articleIds?.(userId);
      return articleIds;
    },
    async getCachedArticleMetadata({ ids: selected } = {}) {
      const rows = f.catalog().rows.filter((row) => !selected || selected.includes(row.id));
      return clone(rows);
    },
    async getFeeds() { return [{ id: 1, title: "测试订阅" }]; },
    getLastSyncTime() { return f.catalog().syncedAt; },
  };
  f.stream = async (messages, { onChunk, signal }) => {
    if (signal?.aborted) throw Object.assign(new Error("aborted"), { name: "AbortError" });
    const digest = messages[1].content.startsWith("主题：");
    f.aiCalls.push({ digest, account: f.account() });
    onChunk(digest ? "## 本期主题\n已完成的报告正文。[1]" : JSON.stringify({
      topics: [{ title: "本期主题", gist: "重点", summary: "主题摘要", articles: [1] }],
    }));
    return { finishReason: "stop" };
  };
  const readDocument = (ref) => {
    if (!ref) return null;
    const header = values.get(ref);
    return JSON.parse(Array.from({ length: header.parts }, (_, part) => values.get(`${ref}.p${part}`)).join(""));
  };
  f.storage = {
    async getMany(keys) { return keys.map((key) => clone(values.get(key) ?? null)); },
    async keys() { return [...values.keys()]; },
    async apply(change) {
      for (const { key, value } of change.set || []) if (key.endsWith(".root")) {
        const state = readDocument(value.stateRef);
        await f.hooks.publish?.(value.accountKey, state);
        f.publications.push({ account: value.accountKey, state: clone(state) });
      }
      for (const { key, value } of change.set || []) values.set(key, clone(value));
      for (const key of change.remove || []) values.delete(key);
    },
  };
  globalThis.__historyBindings.set(id, f);
  globalThis.window = { ToolBox: { storage: f.storage } };
  f.history = await import(`../src/toolbox/board/historyStore.js?historyBinding=${id}`);
  const { createReportStorage } = await import(`../src/toolbox/board/report-storage.mjs?historyBinding=${id}`);
  f.repository = (userId = f.authState.get().userId) => createReportStorage(f.storage, f.account(userId));
  t.after(async () => {
    releases.forEach((release) => release());
    f.hooks = {};
    f.authState.set({ serverUrl, userId: "" });
    await f.history.clearBoardHistory();
    await f.history.flushBoardHistory();
    globalThis.__historyBindings.delete(id);
  });
  return f;
}

test("flush begun without a context waits for a final sync's initialization and journal commit", { timeout: 5000 }, async (t) => {
  const f = await fixture(t);
  f.addArticles([1, 2, 3]);
  const initializing = deferred();
  const initializationGate = f.gate();
  f.hooks.articleIds = async () => { initializing.resolve(); await initializationGate.promise; };
  const event = f.event([], { initialSync: true, initialEntryIds: [1, 2, 3] });
  const journaling = deferred();
  const journalGate = f.gate();
  let held = false;
  f.hooks.publish = async (_account, state) => {
    if (held || state?.periodStart !== Date.parse(event.committedAt)) return;
    held = true; journaling.resolve(); await journalGate.promise;
  };
  let finished = false;
  const closing = f.history.flushBoardHistory();
  void closing.then(() => { finished = true; }, () => { finished = true; });
  f.emit(event); // The context is created after flush captured its first pause.
  await initializing.promise;
  await tick();
  assert.equal(finished, false, "flush must await the newly created manager");
  initializationGate.resolve();
  await journaling.promise;
  await tick();
  assert.equal(finished, false, "initialization alone does not acknowledge the event journal");
  journalGate.resolve();
  await closing;
  const state = await f.repository().loadState();
  assert.deepEqual(state.seen.map(([articleId]) => articleId).sort((a, b) => a - b), [1, 2, 3]);
  assert.equal(state.periodStart, Date.parse(event.committedAt));
  assert.equal(state.observedThrough, Date.parse(event.syncedAt));
  assert.deepEqual(f.aiCalls, []);
});

test("turning automatic reports off keeps arrivals journaled and the manual action resumes a paused manager", { timeout: 5000 }, async (t) => {
  const f = await fixture(t, { automatic: true });
  await f.history.initializeBoardHistory();
  f.settingsState.set({ ...f.settingsState.get(), autoBoardEnabled: false });
  f.addArticles(ids(151));
  f.emit(f.event(ids(151)));
  await f.history.flushBoardHistory();
  const pending = await f.repository().loadState();
  assert.equal(pending.pendingIds.length, 151);
  assert.equal(f.history.reportHistory.get().pendingCount, 151);
  assert.deepEqual(f.aiCalls, [], "disabled automatic reports do not call the provider");
  const report = await f.history.generatePendingReport();
  assert.ok(report);
  assert.equal(report.total, 151);
  assert.equal(report.articles.length, 151);
  assert.match(report.digest, /已完成的报告正文/);
  assert.equal(f.settingsState.get().autoBoardEnabled, false);
  assert.equal(f.aiCalls.filter((call) => call.digest).length, 1);
  const completed = await f.repository().loadState();
  assert.deepEqual(completed.pendingIds, []);
  assert.equal(completed.job, null);
});

test("changing capacity leaves the active report intact and applies only to the next period without persisting its transport or key", { timeout: 5000 }, async (t) => {
  const f = await fixture(t, { automatic: true });
  f.settingsState.set({ ...f.settingsState.get(), aiContextWindow: "1000000" });
  const originalPolicy = boardInputPolicy(f.settingsState.get());
  await f.history.initializeBoardHistory();
  const firstEntered = deferred();
  const firstGate = f.gate();
  const secondEntered = deferred();
  const secondGate = f.gate();
  const calls = [];
  let analyses = 0;
  let firstSignal;
  const transport = f.stream;
  f.stream = async (messages, options) => {
    const digest = messages[1].content.startsWith("主题：");
    calls.push({ digest, context: options.configuration.aiContextWindow });
    if (!digest && ++analyses === 1) {
      firstSignal = options.signal;
      firstEntered.resolve();
      await firstGate.promise;
    } else if (!digest && analyses === 2) {
      secondEntered.resolve();
      await secondGate.promise;
    }
    return transport(messages, options);
  };
  const completed = deferred();
  const stopListening = f.history.reportHistory.listen((state) => {
    if (state.reports.length === 2 && !state.job) completed.resolve();
  });
  t.after(stopListening);
  f.addArticles(ids(151));
  f.emit(f.event(ids(151)));
  await firstEntered.promise;
  const firstState = await f.repository().loadState();
  assert.deepEqual(firstState.job.checkpoint.inputPolicy, originalPolicy);
  f.settingsState.set({ ...f.settingsState.get(), aiContextWindow: "32000" });
  const nextPolicy = boardInputPolicy(f.settingsState.get());
  await tick();
  await tick();
  assert.equal(firstSignal.aborted, false, "capacity changes must not cancel a paid request");
  assert.equal(calls.length, 1, "capacity changes must not repeat an unfinished analysis");
  const unchanged = await f.repository().loadState();
  assert.equal(unchanged.job.id, firstState.job.id);
  assert.deepEqual(unchanged.job.checkpoint.inputPolicy, originalPolicy);

  // A second period accumulates while the first provider call is still held.
  const accumulated = deferred();
  f.hooks.publish = async (_account, state) => {
    if (state?.pendingIds.length === 151 && state.job?.id === firstState.job.id) accumulated.resolve();
  };
  f.addArticles(ids(151, 152));
  f.emit(f.event(ids(151, 152)));
  await accumulated.promise;
  firstGate.resolve();
  await secondEntered.promise;
  assert.deepEqual(calls, [
    { digest: false, context: "1000000" },
    { digest: true, context: "1000000" },
    { digest: false, context: "32000" },
  ]);
  const secondState = await f.repository().loadState();
  assert.notEqual(secondState.job.id, firstState.job.id);
  assert.deepEqual(secondState.job.checkpoint.inputPolicy, nextPolicy);
  assert.equal(firstSignal.aborted, false);
  secondGate.resolve();
  await completed.promise;
  assert.equal(calls.length, 4, "each period needs one analysis and one digest");
  assert.equal(analyses, 2);
  const stored = JSON.stringify(await f.storage.getMany(await f.storage.keys()));
  assert.doesNotMatch(stored, /fixture-key|aiApiKey|\bstream\b|\bconfiguration\b/);
});

test("a saved today snapshot retains its article settings for reuse after a restart", async (t) => {
  const f = await fixture(t);
  const at = Date.now();
  const day = new Date(at); day.setHours(0, 0, 0, 0);
  const state = {
    reportId: `today-${at}`, kind: "today", at, day: day.getTime(),
    periodStart: day.getTime(), periodEnd: at, filter: "false|false|either", total: 1,
    articles: [{ id: 1, title: "保存的文章", number: 1 }],
    topics: [{ title: "保存的主题", articles: [1] }],
  };
  await f.history.saveBoardSnapshot(state, "已写好的完整报告 [1]");
  const saved = await f.repository().readReport(state.reportId);
  assert.equal(saved.filter, state.filter);
  assert.equal(saved.kind, "today");
  assert.equal(saved.periodStart, day.getTime());
  assert.equal(saved.digest, "已写好的完整报告 [1]");
  assert.deepEqual(saved.articles, state.articles);
  assert.equal(saved.unseen, true);
  await Promise.all([
    f.history.markBoardReportSeen(state.reportId),
    f.history.saveBoardDigest(state.reportId, "更新后的完整报告 [1]"),
  ]);
  const reopened = await f.repository().readReport(state.reportId);
  assert.equal(reopened.unseen, false);
  assert.equal(reopened.digest, "更新后的完整报告 [1]");
  assert.deepEqual(f.aiCalls, [], "saving and restoring do not generate content");
});

test("late initialization and an old sync event cannot publish into the replacement account", { timeout: 5000 }, async (t) => {
  const f = await fixture(t);
  f.addArticles([701], 7);
  f.addArticles([801], 8);
  const entered = deferred();
  const gate = f.gate();
  f.hooks.articleIds = async (userId) => {
    if (userId === "7") { entered.resolve(); await gate.promise; }
  };
  const oldInitialization = f.history.initializeBoardHistory();
  await entered.promise;
  f.emit(f.event([], { initialSync: true, initialEntryIds: [701, 799] }));
  f.authState.set({ ...f.authState.get(), userId: 8 });
  const replacement = f.history.initializeBoardHistory();
  const currentEvent = f.event([], { initialSync: true, initialEntryIds: [801] });
  f.emit(currentEvent);
  gate.resolve();
  assert.equal(await oldInitialization, null);
  assert.ok(await replacement);
  await f.history.flushBoardHistory();
  const state = await f.repository(8).loadState();
  assert.deepEqual(state.seen.map(([articleId]) => articleId), [801]);
  assert.equal(state.observedThrough, Date.parse(currentEvent.syncedAt));
  assert.equal(await f.repository(7).loadState(), null, "the retired account journal is cleared");
  const replacementWrites = f.publications.filter((publication) => publication.account === f.account(8) && publication.state);
  assert.ok(replacementWrites.length);
  assert.ok(replacementWrites.every(({ state: saved }) => saved.seen.every(([articleId]) => articleId === 801)));
  assert.equal(f.history.reportHistory.get().status, "ready");
  assert.equal(f.history.reportHistory.get().error, "");
  assert.deepEqual(f.aiCalls, []);
});
