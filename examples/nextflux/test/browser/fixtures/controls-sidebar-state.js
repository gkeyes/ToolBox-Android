import { atom } from "nanostores";

// Account, feed/database and background-service boundaries; both footer controls
// and General keep their real settings store and production persistence engine.
export const lastSync = atom(null);
export const isSyncing = atom(false);
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
