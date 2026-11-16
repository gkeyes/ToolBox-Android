import { atom } from "nanostores";

// Account, feed/database and background-service boundaries; both footer controls
// and General keep their real settings store and production persistence engine.
export const lastSync = atom(null);
export const isSyncing = atom(false);
export const isOnline = atom(true);
export const syncProgress = atom("");
export const error = atom(null);
export const isModalOpen = atom(false);
export const continuousSync = atom(false);
export const backgroundSyncHealth = atom({ state: "idle" });
export const feedLoads = [];
export const getLastSyncTime = () => null;
export const loadFeeds = () => feedLoads.push(window.controlsSidebarFixture.settings().showHiddenFeeds);
export const startContinuousSync = async () => continuousSync.set(true);
export const stopContinuousSync = async () => continuousSync.set(false);
export const syncCalls = [];
let finishPendingSync;
export function sync(mode) {
  const fixture = window.controlsSidebarFixture;
  const limit = fixture.settings().recentCacheLimit;
  syncCalls.push({ mode, limit, persistedLimit: fixture.persistedCacheLimit() });
  isSyncing.set(true);
  syncProgress.set(`正在缓存最近文章 · ${limit}`);
  return new Promise((resolve) => {
    finishPendingSync = () => {
      isSyncing.set(false);
      syncProgress.set("");
      resolve({ outcome: "committed" });
      finishPendingSync = null;
    };
  });
}
export function finishSync() { finishPendingSync?.(); }
export const forceSync = () => sync("foreground");
