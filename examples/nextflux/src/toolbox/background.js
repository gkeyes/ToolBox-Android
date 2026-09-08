import { atom } from "nanostores";
import { settingsState } from "../stores/settingsStore.js";
import { forceSync } from "../stores/syncStore.js";
import { toast } from "sonner";

export const continuousSync = atom(false);
const TIMER_KEY = "nextflux.sync";
let sessionId = null;
let initialized = false;
function api() { return globalThis.window?.ToolBox?.background; }
function intervalMs() { return Math.max(5, Number(settingsState.get().syncInterval) || 15) * 60000; }
async function refreshTimer() {
  if (sessionId) await api().setTimer(TIMER_KEY, intervalMs());
}
export async function initializeBackground() {
  if (!api()) return;
  if (!initialized) {
    initialized = true;
    api().onTimer(event => {
      if (event.key === TIMER_KEY && continuousSync.get()) forceSync().catch(() => {});
    });
    api().onRestore(async () => {
      try {
        const sessions = await api().listSessions();
        sessionId = sessions[0]?.sessionId || null;
        continuousSync.set(Boolean(sessionId));
        await refreshTimer();
      } catch { continuousSync.set(false); }
    });
    settingsState.listen(() => refreshTimer().catch(() => toast.error("后台同步间隔未更新，请重新开启后台同步。")));
  }
  try {
    const sessions = await api().listSessions();
    sessionId = sessions[0]?.sessionId || null;
    continuousSync.set(Boolean(sessionId));
    await refreshTimer();
  } catch { continuousSync.set(false); }
}
export async function startContinuousSync() {
  if (!api()) throw new Error("当前 ToolBox 不支持后台同步。");
  try {
    const session = await api().start({ restoreAfterProcessDeath: true, restoreAfterReboot: false });
    sessionId = session.sessionId;
    await refreshTimer();
    continuousSync.set(true);
  } catch {
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
  const sessions = sessionId ? [{ sessionId }] : await api().listSessions().catch(() => []);
  for (const session of sessions) await api().stop(session.sessionId);
  sessionId = null;
  continuousSync.set(false);
}
