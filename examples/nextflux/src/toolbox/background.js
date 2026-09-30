import { atom } from "nanostores";
import { settingsState } from "../stores/settingsStore.js";
import { claimAutoSync } from "../stores/syncStore.js";
import { toast } from "sonner";
import { backgroundHealth, backgroundIntervalMs } from "./background-policy.mjs";
import { continuousSync, nativeTimerEffective } from "./background-state.js";
import { runtimeForeground, runtimeClosing } from "./foreground.js";

export { continuousSync };
export const backgroundSyncHealth = atom(backgroundHealth());
const TIMER_KEY = "nextflux.sync";
const STATE_KEY = "nextflux.background-sync.v1";
const HEALTH_KEY = "nextflux.background-sync.health.v1";
let sessionId = null;
let initialized = false;
let backgroundRun = null;
let observedInterval = null;
let timerWrite = Promise.resolve();
let timerRevision = 0;
let healthWrite = Promise.resolve();
const eventWork = new Set();

function trackWork(work) {
  eventWork.add(work);
  void work.finally(() => eventWork.delete(work)).catch(() => {});
  return work;
}

function toolbox() { return globalThis.window?.ToolBox; }
function api() { return toolbox()?.background; }
function storage() { return toolbox()?.storage; }
function intervalMs() { return backgroundIntervalMs(settingsState.get().syncInterval); }

function formatClock(value) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "--:--";
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(date);
}

async function publishTaskStatus(primaryText, secondaryText, updatedAt = Date.now()) {
  if (!sessionId || typeof api()?.updateActivity !== "function") return false;
  try {
    await api().updateActivity({ sessionId, text: primaryText, detail: secondaryText, updatedAt });
    return true;
  } catch {
    return false;
  }
}

async function publishBackgroundStatus(result) {
  const updatedAt = Date.parse(result?.syncedAt || "") || Date.now();
  const newEntryCount = Math.max(0, Number(result?.newEntryCount) || 0);
  const unreadCount = Math.max(0, Number(result?.unreadCount) || 0);
  const primaryText = result?.initialSync
    ? `${formatClock(updatedAt)} 已同步 · 首次完成`
    : newEntryCount > 0
      ? `${formatClock(updatedAt)} 已同步 · 新增 ${newEntryCount} 条`
      : `${formatClock(updatedAt)} 已同步 · 暂无更新`;
  return publishTaskStatus(primaryText, `未读 ${unreadCount} 条`, updatedAt);
}

async function publishBackgroundFailure(error) {
  const updatedAt = Date.now();
  const detail = String(error?.message || "后台同步失败").replace(/\s+/g, " ").trim();
  return publishTaskStatus(`${formatClock(updatedAt)} 同步失败 · 将自动重试`, detail.slice(0, 72), updatedAt);
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
  healthWrite = healthWrite.catch(() => {}).then(() => storage()?.set?.(HEALTH_KEY, next));
  void healthWrite.catch(() => {});
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
  const revision = ++timerRevision;
  nativeTimerEffective.set(false);
  const write = timerWrite.catch(() => {}).then(async () => {
    if (revision !== timerRevision) return null;
    await api().setTimer(TIMER_KEY, interval);
    if (revision === timerRevision) nativeTimerEffective.set(true);
    return interval;
  });
  timerWrite = write;
  await write;
  return interval;
}

async function verifyNativeSession() {
  if (!continuousSync.get() || !sessionId) { nativeTimerEffective.set(false); return false; }
  try {
    const current = await api().status(sessionId);
    if (!current) { sessionId = null; nativeTimerEffective.set(false); return false; }
    return true;
  } catch {
    nativeTimerEffective.set(false);
    return false;
  }
}

async function runBackgroundSync(reason) {
  if (!continuousSync.get() || runtimeClosing.get()) return false;
  if (backgroundRun) return backgroundRun;
  const scheduled = claimAutoSync();
  if (!scheduled) return false;
  const attemptAt = Date.now();
  setHealth({ type: "attempt", reason }, attemptAt);
  backgroundRun = (async () => {
    try {
      const result = await scheduled;
      if (runtimeClosing.get()) return false;
      if (result?.outcome !== "committed") {
        throw new Error("后台同步未完成写入。");
      }
      setHealth({ type: "success" });
      await publishBackgroundStatus(result);
      return true;
    } catch (error) {
      if (runtimeClosing.get()) return false;
      if (error?.code === "SYNC_PREEMPTED") {
        setHealth({ type: "deferred", message: "后台同步已让位给明确的前台操作。" });
        return false;
      }
      setHealth({ type: "failure", message: error?.message || "后台同步失败" });
      await publishBackgroundFailure(error);
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
    nativeTimerEffective.set(false);
    setHealth({ type: "stopped" });
    return false;
  }
  continuousSync.set(true);
  await ensureSession();
  await refreshTimer();
  setHealth({ type: "enabled" });
  return true;
}

async function initializeBackgroundWork() {
  if (!api()) return;
  if (!initialized) {
    initialized = true;
    api().onTimer((event) => {
      if (event.key !== TIMER_KEY || !continuousSync.get() || runtimeClosing.get()) return;
      const work = verifyNativeSession().then((valid) => {
        if (valid && !runtimeClosing.get()) { nativeTimerEffective.set(true); return runBackgroundSync("timer"); }
        return false;
      });
      trackWork(work);
    });
    api().onRestore(() => {
      if (runtimeClosing.get()) return;
      const work = (async () => {
        try {
          if (await restoreContinuousSync() && !runtimeClosing.get()) await runBackgroundSync("restore");
        } catch {
          sessionId = null;
          nativeTimerEffective.set(false);
          if (!runtimeClosing.get()) setHealth({ type: "failure", message: "后台定时器恢复失败，前台同步会继续。" });
        }
      })();
      trackWork(work);
    });
    settingsState.listen((value) => {
      const nextInterval = String(value?.syncInterval ?? "");
      if (nextInterval === observedInterval) return;
      observedInterval = nextInterval;
      if (!continuousSync.get() || runtimeClosing.get()) return;
      const work = refreshTimer().catch(() => {
        nativeTimerEffective.set(false);
        toast.error("后台同步间隔未更新，前台同步会继续。请重新开启后台同步。");
      });
      trackWork(work);
    });
    runtimeForeground.listen((visible) => {
      if (visible && continuousSync.get() && !runtimeClosing.get()) trackWork(verifyNativeSession());
    });
  }
  try {
    await restoreContinuousSync();
  } catch {
    sessionId = null;
    nativeTimerEffective.set(false);
    if (!runtimeClosing.get()) setHealth({ type: "failure", message: "后台定时器未恢复，前台同步会继续。" });
  }
}

export function initializeBackground() {
  return trackWork(initializeBackgroundWork());
}

export function startContinuousSync() {
  return trackWork(startContinuousSyncWork());
}

async function startContinuousSyncWork() {
  if (!api()) throw new Error("当前 ToolBox 不支持后台同步。");
  try {
    await ensureSession();
    await refreshTimer();
    await writeEnabledIntent(true);
    continuousSync.set(true);
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
    nativeTimerEffective.set(false);
    setHealth({ type: "stopped" });
    throw new Error("未能开启后台同步，请开启小工具的后台运行权限和宿主后台保障。");
  }
}

export function stopContinuousSync() {
  return trackWork(stopContinuousSyncWork());
}

async function stopContinuousSyncWork() {
  if (!api()) return;
  await writeEnabledIntent(false);
  continuousSync.set(false);
  nativeTimerEffective.set(false);
  timerRevision += 1;
  await timerWrite.catch(() => {});
  let cleanupError = null;
  try {
    await api().cancelTimer(TIMER_KEY);
  } catch (error) {
    if (error?.code !== "NOT_FOUND") cleanupError = error;
  }
  const sessions = sessionId ? [{ sessionId }] : await api().listSessions().catch(() => []);
  for (const session of sessions) {
    try {
      await api().stop(session.sessionId);
    } catch (error) {
      if (error?.code !== "NOT_FOUND" && !cleanupError) cleanupError = error;
    }
  }
  sessionId = null;
  setHealth({ type: "stopped" });
  if (cleanupError) throw cleanupError;
}

export async function flushBackgroundWork() {
  await Promise.allSettled([timerWrite, backgroundRun, ...eventWork]);
  await healthWrite;
}

export { runBackgroundSync };
