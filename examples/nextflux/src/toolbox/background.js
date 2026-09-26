import { atom } from "nanostores";
import { settingsState } from "../stores/settingsStore.js";
import { backgroundSync } from "../stores/syncStore.js";
import { toast } from "sonner";

export const continuousSync = atom(false);
const TIMER_KEY = "nextflux.sync";
const STATE_KEY = "nextflux.background-sync.v1";
let sessionId = null;
let initialized = false;

function toolbox() { return globalThis.window?.ToolBox; }
function api() { return toolbox()?.background; }
function storage() { return toolbox()?.storage; }
function intervalMs() {
  const value = Number(settingsState.get().syncInterval);
  return Number.isFinite(value) && value >= 0 ? value * 60000 : 15 * 60000;
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
  if (!sessionId) return;
  const interval = intervalMs();
  if (interval === 0) await api().cancelTimer(TIMER_KEY).catch((error) => {
    if (error?.code !== "NOT_FOUND") throw error;
  });
  else await api().setTimer(TIMER_KEY, interval);
}

async function restoreContinuousSync() {
  const enabled = await readEnabledIntent();
  if (!enabled) {
    sessionId = null;
    continuousSync.set(false);
    return;
  }
  await ensureSession();
  await refreshTimer();
  continuousSync.set(true);
}

export async function initializeBackground() {
  if (!api()) return;
  if (!initialized) {
    initialized = true;
    api().onTimer((event) => {
      if (event.key === TIMER_KEY && continuousSync.get()) backgroundSync().catch(() => {});
    });
    api().onRestore(async () => {
      try {
        await restoreContinuousSync();
      } catch {
        sessionId = null;
        continuousSync.set(false);
      }
    });
    settingsState.listen(() => {
      if (!continuousSync.get()) return;
      refreshTimer().catch(() => toast.error("后台同步间隔未更新，请重新开启后台同步。"));
    });
  }
  try {
    await restoreContinuousSync();
  } catch {
    sessionId = null;
    continuousSync.set(false);
  }
}

export async function startContinuousSync() {
  if (!api()) throw new Error("当前 ToolBox 不支持后台同步。");
  try {
    await ensureSession();
    await refreshTimer();
    await writeEnabledIntent(true);
    continuousSync.set(true);
  } catch {
    try {
      await api().cancelTimer(TIMER_KEY);
    } catch { /* Ignore cleanup failures after a failed start. */ }
    if (sessionId) {
      try { await api().stop(sessionId); } catch { /* Native service retains its stop action. */ }
      sessionId = null;
    }
    continuousSync.set(false);
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
}
