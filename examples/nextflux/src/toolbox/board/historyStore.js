import { atom } from "nanostores";
import { authState } from "@/stores/authStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import { syncCommitted } from "@/stores/syncStore.js";
import { getArticleIds, getCachedArticleMetadata, getFeeds, getLastSyncTime } from "@/db/storage.js";
import { streamChat } from "@/api/openai.js";
import { runtimeClosing, runtimeForeground } from "../foreground.js";
import { dedupeArticles } from "../dedupe.mjs";
import { arrivedAt, boardArticleIds, digestMessages } from "./board.mjs";
import { generateBoard } from "./generate-board.js";
import { createReportManager } from "./report-manager.mjs";
import { createReportStorage } from "./report-storage.mjs";

const EMPTY = { status: "ready", reports: [], pendingCount: 0, job: null, error: "" };
export const reportHistory = atom({ ...EMPTY });
const accountKey = (auth = authState.get()) => auth.userId ? `${auth.serverUrl}|${auth.userId}` : "";
let context = null;
let retired = Promise.resolve();
const syncEvents = new Set();

function config() {
  const s = settingsState.get();
  return { enabled: s.autoBoardEnabled !== false, aiReady: Boolean(s.aiApiKey),
    fingerprint: JSON.stringify(["board-v2", s.aiBaseUrl, s.aiModel, Boolean(s.showHiddenFeeds),
      s.hideDuplicateArticles !== false, s.duplicateMatch || "either"]) };
}
function reportError(error, key = accountKey()) {
  if (error?.name === "AbortError" || !key || accountKey() !== key) return;
  reportHistory.set({ ...reportHistory.get(), error: error?.message || "看板历史暂时无法读取，请重试。" });
}
async function selectArticles(ids) {
  if (!ids.length) return [];
  const s = settingsState.get();
  const feeds = new Map((await getFeeds()).filter((feed) => s.showHiddenFeeds || !feed.hide_globally)
    .map((feed) => [feed.id, feed]));
  const rows = (await getCachedArticleMetadata({ ids, filter: "unread" }))
    .filter((row) => row.status === "unread" && feeds.has(row.feedId))
    .sort((a, b) => arrivedAt(b) - arrivedAt(a) || b.id - a.id);
  return dedupeArticles(rows, { enabled: s.hideDuplicateArticles !== false, match: s.duplicateMatch || "either" })
    .map((row) => ({ id: row.id, title: row.titleText || row.title || "", feedTitle: feeds.get(row.feedId)?.title || "",
      url: row.url || "", text: row.previewText || "", published_at: row.published_at, created_at: row.created_at }));
}
export async function initializeBoardHistory() {
  const key = accountKey();
  if (!key) return null;
  if (context?.key === key && context.failed) {
    const previous = context;
    context = null;
    retired = retired.catch(() => {}).then(() => previous.manager?.dispose());
  }
  if (context?.key === key) {
    const current = context;
    await current.ready;
    if (context !== current || accountKey() !== key) return null;
    await current.manager.refresh();
    return context === current && accountKey() === key ? current.manager : null;
  }
  const current = { key, manager: null, ready: null };
  context = current;
  reportHistory.set({ ...EMPTY, status: "loading" });
  current.ready = (async () => {
    await retired.catch(() => {});
    const storage = globalThis.window?.ToolBox?.storage ?? globalThis.ToolBox?.storage;
    if (!storage) throw new Error("请在 ToolBox 中打开看板历史。");
    const repository = await createReportStorage(storage, key);
    current.manager = createReportManager({ repository, selectArticles, configuration: config,
      recoverIds: async (through) => (await getCachedArticleMetadata({ filter: "unread" }))
        .filter((row) => Date.parse(row.created_at || "") > through).map((row) => row.id),
      generate: generateBoard,
      writeDigest: async (result, signal, publish) => {
        let text = "";
        await streamChat(digestMessages(result.topics, result.articles), {
          signal, onChunk: (chunk) => { text += chunk; publish(text); },
        });
        return text;
      },
      onChange: (state) => { if (context === current && accountKey() === key) reportHistory.set(state); },
    });
    await current.manager.initialize({ baselineAt: getLastSyncTime()?.getTime() ?? Date.now(), knownIds: await getArticleIds() });
    if (context !== current || accountKey() !== key) return;
    if (runtimeClosing.get()) await current.manager.pause();
    else void current.manager.resume().catch((error) => reportError(error, key));
  })();
  try { await current.ready; } catch (failure) {
    current.failed = true;
    if (context === current) reportHistory.set({ ...EMPTY, status: "error", error: failure.message });
    throw failure;
  }
  return context === current ? current.manager : null;
}

async function manualReport(retry) {
  const key = accountKey();
  const manager = await initializeBoardHistory();
  if (!manager || runtimeClosing.get() || accountKey() !== key) return null;
  if (!config().aiReady) { reportError(new Error("请先在设置中配置 AI 服务。")); return null; }
  await manager.resume({ autoStart: false });
  if (runtimeClosing.get() || accountKey() !== key) return null;
  return manager.start({ force: true, retry });
}
export const generatePendingReport = () => manualReport(false);
export const retryPendingReport = () => manualReport(true);
export async function readBoardReport(id) { return (await initializeBoardHistory())?.readReport(id) ?? null; }
export async function deleteBoardReport(id) { await (await initializeBoardHistory())?.removeReport(id); }
export async function saveBoardSnapshot(state, text = "") {
  const manager = await initializeBoardHistory();
  if (!manager) return null;
  const report = {
    id: state.reportId || `today-${state.at}`, kind: state.kind || "today", unseen: false,
    periodStart: state.periodStart ?? state.day, periodEnd: state.periodEnd ?? state.at,
    completedAt: state.at, total: state.total, skipped: state.skipped || 0,
    includedCount: boardArticleIds(state.topics, state.articles).length,
    articles: state.articles, topics: state.topics, cut: Boolean(state.cut), digest: text,
    filter: state.filter,
    fingerprint: config().fingerprint,
  };
  return manager.saveReport(report);
}
export async function saveBoardDigest(id, text) {
  const manager = await initializeBoardHistory();
  const report = await manager?.readReport(id);
  if (report) await manager.saveReport({ ...report, digest: text });
}
export async function flushBoardHistory() {
  const failures = [];
  const pause = async () => {
    const current = context;
    try { await current?.ready; await current?.manager?.pause(); }
    catch (error) { failures.push(error); }
  };
  await pause();
  // A cache commit publishes its event synchronously but journaling it is
  // asynchronous. Drain those observers before acknowledging runtime close.
  while (syncEvents.size) {
    const results = await Promise.allSettled([...syncEvents]);
    failures.push(...results.filter((result) => result.status === "rejected").map((result) => result.reason));
  }
  await pause();
  try { await retired; } catch (error) { failures.push(error); }
  if (failures.length) throw failures[0];
}
// logout changes auth before awaiting its cleanup. The listener has already
// retired the old manager, so the credential/cache cleanup can await this too.
export async function clearBoardHistory() { await retired; }

let account = accountKey();
authState.listen(() => {
  const next = accountKey();
  if (next === account) return;
  account = next;
  const previous = context;
  context = null;
  reportHistory.set({ ...EMPTY });
  if (previous) retired = retired.catch(() => {}).then(async () => {
    await previous.ready.catch(() => {});
    await previous.manager?.dispose({ clear: true });
  });
  if (next) void initializeBoardHistory().catch((error) => reportError(error, next));
});
syncCommitted.listen((event) => {
  if (!event || !accountKey()) return;
  const key = accountKey();
  const observing = initializeBoardHistory().then((manager) => {
    if (accountKey() === key) return manager?.onSync(event);
  });
  syncEvents.add(observing);
  void observing.catch((error) => reportError(error, key)).finally(() => syncEvents.delete(observing));
});
let lastConfig = JSON.stringify(config());
settingsState.listen(() => {
  const value = JSON.stringify(config());
  if (value === lastConfig) return;
  lastConfig = value;
  const current = context;
  if (!current) return;
  void current.ready.then(async () => {
    if (context !== current) return;
    await current.manager.pause();
    if (!runtimeClosing.get() && config().enabled) await current.manager.resume();
  }).catch((error) => reportError(error, current.key));
});
runtimeClosing.listen((closing) => {
  const key = accountKey();
  if (closing) void flushBoardHistory().catch((error) => reportError(error, key));
});
runtimeForeground.listen((foreground) => {
  const key = accountKey();
  if (!foreground || !key || runtimeClosing.get()) return;
  void initializeBoardHistory().then((manager) => {
    if (accountKey() === key && !runtimeClosing.get()) return manager?.resume();
  }).catch((error) => reportError(error, key));
});
