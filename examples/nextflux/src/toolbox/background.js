import { atom } from "nanostores";
import { settingsState } from "../stores/settingsStore.js";
import { backgroundSync, forceSync } from "../stores/syncStore.js";
import { toast } from "sonner";
import { backgroundHealth, backgroundIntervalMs } from "./background-policy.mjs";

export const continuousSync = atom(false);
export const backgroundSyncHealth = atom(backgroundHealth());
const TIMER_KEY = "nextflux.sync";
const STATE_KEY = "nextflux.background-sync.v1";
const HEALTH_KEY = "nextflux.background-sync.health.v1";
let sessionId = null;
let initialized = false;
let backgroundRun = null;
let observedInterval = null;

function toolbox() { return globalThis.window?.ToolBox; }
function api() { return toolbox()?.background; }
function storage() { return toolbox()?.storage; }
function intervalMs() { return backgroundIntervalMs(settingsState.get().syncInterval); }

function clockLabel(value = Date.now()) {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(value));
}

function shortMessage(error) {
  const value = String(error?.message || error || "后台同步失败").replace(/\s+/g, " ").trim();
  return value.length > 48 ? `${value.slice(0, 47)}…` : value;
}

async function updateActivity(text, detail = null, updatedAt = Date.now()) {
  if (!sessionId || typeof api()?.updateActivity !== "function") return;
  try {
    await api().updateActivity({
      sessionId,
      text,
      ...(detail ? { detail } : {}),
      updatedAt,
    });
  } catch {
    // Activity summaries are informative only; sync must remain independent.
  }
}

async function successfulActivity(result) {
  const syncedAt = Date.parse(result?.syncedAt || "") || Date.now();
  let unread = null;
  try {
    const { totalUnreadCount } = await import("../stores/feedsStore.js");
    unread = totalUnreadCount.get();
  } catch { /* Keep the sync result even if the optional count is unavailable. */ }
  const newEntries = result?.newEntries;
  const primary = newEntries == null
    ? `${clockLabel(syncedAt)} 已完成首次同步`
    : newEntries > 0
      ? `${clockLabel(syncedAt)} 已同步 · 新增 ${newEntries} 条`
      : `${clockLabel(syncedAt)} 已同步 · 暂无新增`;
  const details = [];
  if (Number.isFinite(result?.updatedEntries) && result.updatedEntries > 0) details.push(`本次处理 ${result.updatedEntries} 条变更`);
  if (Number.isFinite(unread)) details.push(`未读 ${unread} 条`);
  await updateActivity(primary, details.join(" · ") || "Miniflux 同步正常", syncedAt);
}

async function readEnabledIntent() {
  if (!storage()?.get) return false;
  const saved = await storage().get(STATE_KEY);
  return saved?.enabled === true;
}

async function writeEnabledIntent(enabled) {
  if (!storage()?.set) throw new Error("当前 ToolBox 不支持保存后台同步状态。");
  await storage().set(STATE_KEY, { enabled: Boolean(enabled) });
}

async function restoreHealth() {
  if (!storage()?.get) return;
  const saved = await storage().get(HEALTH_KEY).catch(() => null);
  if (saved && typeof saved === "object" && !Array.isArray(saved)) {
    backgroundSyncHealth.set(backgroundHealth(saved));
  }
}

function setHealth(event, now = Date.now()) {
  const next = backgroundHealth(backgroundSyncHealth.get(), event, now, intervalMs());
  backgroundSyncHealth.set(next);
  storage()?.set?.(HEALTH_KEY, next).catch(() => {});
  return next;
}

async function findSession() {
  const sessions = await api().listSessions();
  const current = sessionId
    ? sessions.find((session) => session.sessionId === sessionId)
    : null;
  sessionId = current?.sessionId || sessions[0]?.sessionId || null;
  return sessionId;
}

async function ensureSession() {
  if (await findSession()) return sessionId;
  const session = await api().start({
    restoreAfterProcessDeath: true,
    restoreAfterReboot: true,
  });
  sessionId = session.sessionId;
  return sessionId;
}

async function refreshTimer() {
  if (!sessionId) return null;
  const interval = intervalMs();
  await api().setTimer(TIMER_KEY, interval);
  if (continuousSync.get()) setHealth({ type: "enabled" });
  return interval;
}

async function runBackgroundSync(reason) {
  if (!continuousSync.get()) return false;
  if (backgroundRun) return backgroundRun;
  const attemptAt = Date.now();
  setHealth({ type: "attempt", reason }, attemptAt);
  backgroundRun = (async () => {
    try {
      const result = await backgroundSync();
      if (result?.outcome !== "committed") {
        throw new Error("后台同步未完成写入。");
      }
      setHealth({ type: "success" });
      await successfulActivity(result);
      return true;
    } catch (error) {
      if (error?.code === "SYNC_PREEMPTED") {
        setHealth({ type: "deferred", message: "后台同步已让位给前台操作。" });
        if (globalThis.document?.visibilityState === "visible") {
          try {
            const result = await forceSync();
            if (result?.outcome === "committed") {
              setHealth({ type: "success" });
              await successfulActivity(result);
              return true;
            }
          } catch (foregroundError) {
            setHealth({ type: "failure", message: foregroundError?.message || "前台补同步失败" });
            await updateActivity(`${clockLabel()} 同步异常`, `${shortMessage(foregroundError)} · 将自动重试`);
            return false;
          }
        }
        return false;
      }
      setHealth({ type: "failure", message: error?.message || "后台同步失败" });
      await updateActivity(`${clockLabel()} 同步异常`, `${shortMessage(error)} · 将自动重试`);
      return false;
    }
  })().finally(() => { backgroundRun = null; });
  return backgroundRun;
}

async function restoreContinuousSync() {
  await restoreHealth();
  const enabled = await readEnabledIntent();
  if (!enabled) {
    sessionId = null;
    continuousSync.set(false);
    setHealth({ type: "stopped" });
    return false;
  }
  await ensureSession();
  continuousSync.set(true);
  await updateActivity("等待下次同步", "后台同步已恢复");
  await refreshTimer();
  setHealth({ type: "enabled" });
  return true;
}

export async function initializeBackground() {
  if (!api()) return;
  if (!initialized) {
    initialized = true;
    api().onTimer((event) => {
      if (event.key === TIMER_KEY && continuousSync.get()) void runBackgroundSync("timer");
    });
    api().onRestore(async () => {
      try {
        if (await restoreContinuousSync()) await runBackgroundSync("restore");
      } catch {
        sessionId = null;
        continuousSync.set(false);
        setHealth({ type: "stopped" });
      }
    });
    settingsState.listen((value) => {
      const nextInterval = String(value?.syncInterval ?? "");
      if (nextInterval === observedInterval) return;
      observedInterval = nextInterval;
      if (!continuousSync.get()) return;
      refreshTimer().catch(() => toast.error("后台同步间隔未更新，请重新开启后台同步。"));
    });
  }
  try {
    await restoreContinuousSync();
  } catch {
    sessionId = null;
    continuousSync.set(false);
    setHealth({ type: "stopped" });
  }
}

export async function startContinuousSync() {
  if (!api()) throw new Error("当前 ToolBox 不支持后台同步。");
  try {
    await ensureSession();
    continuousSync.set(true);
    await updateActivity("等待首次同步", "后台同步已开启");
    await refreshTimer();
    await writeEnabledIntent(true);
    setHealth({ type: "enabled" });
    await runBackgroundSync("start");
  } catch {
    try {
      await api().cancelTimer(TIMER_KEY);
    } catch { /* Ignore cleanup failures after a failed start. */ }
    if (sessionId) {
      try { await api().stop(sessionId); } catch { /* Native service retains its stop action. */ }
      sessionId = null;
    }
    continuousSync.set(false);
    setHealth({ type: "stopped" });
    throw new Error("未能开启后台同步，请开启小工具的后台运行权限和宿主后台保障。");
  }
}

export async function stopContinuousSync() {
  if (!api()) return;
  await writeEnabledIntent(false);
  try {
    await api().cancelTimer(TIMER_KEY);
  } catch (error) {
    if (error?.code !== "NOT_FOUND") throw error;
  }
  const sessions = sessionId ? [{ sessionId }] : await api().listSessions().catch(() => []);
  for (const session of sessions) {
    try {
      await api().stop(session.sessionId);
    } catch (error) {
      if (error?.code !== "NOT_FOUND") throw error;
    }
  }
  sessionId = null;
  continuousSync.set(false);
  setHealth({ type: "stopped" });
}

export { runBackgroundSync };
