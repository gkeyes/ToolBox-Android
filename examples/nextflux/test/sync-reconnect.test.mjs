import test, { mock } from "node:test";
import assert from "node:assert/strict";

// Auto-sync with the real sync store. While offline the next attempt is a full
// interval away; coming back online must sync as soon as it is due.
globalThis.window = Object.assign(new EventTarget(), {
  ToolBox: { network: { request: () => new Promise(() => {}) } },
});
mock.timers.enable({ apis: ["setTimeout", "Date"], now: Date.parse("2026-10-14T00:00:00Z") });
mock.method(console, "error", () => {});
const { startAutoSync, stopAutoSync, isOnline, isSyncing } = await import("../src/stores/syncStore.js");
const { settingsState } = await import("../src/stores/settingsStore.js");
const { authState } = await import("../src/stores/authStore.js");
const { runtimeForeground } = await import("../src/toolbox/foreground.js");

test("auto-sync runs right after the connection comes back", () => {
  settingsState.set({ ...settingsState.get(), syncInterval: "15" });
  authState.set({ ...authState.get(), userId: 1 });
  runtimeForeground.set(true);
  isOnline.set(false);
  startAutoSync();
  mock.timers.tick(60 * 1000);
  assert.equal(isSyncing.get(), false, "offline: no sync yet");
  window.dispatchEvent(new Event("online"));
  mock.timers.tick(1000);
  assert.equal(isOnline.get(), true);
  assert.equal(isSyncing.get(), true, "sync started within a second of reconnecting");
  stopAutoSync();
});
