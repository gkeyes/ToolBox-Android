import { useSyncExternalStore } from 'react';
import { host } from './bridge';
import { abortAndSettleRequests } from './network';
import { flushStorage } from './storage';

let foreground = false;
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); };
const snapshot = () => foreground;

export function useRuntimeForeground(): boolean {
  return useSyncExternalStore(subscribe, snapshot, () => false);
}

export function initializeRuntime(): void {
  // Leaving the page commits the debounced store writes; failures surface in the storage banner.
  if (typeof document !== 'undefined') {
    const flush = () => { void flushStorage().catch(() => {}); };
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') flush(); });
    window.addEventListener('pagehide', flush);
  }
  const api = host();
  if (!api) { foreground = true; return; } // Standalone browser preview.
  const runtime = api.runtime;
  runtime.onStateChanged((state) => {
    const next = state.foreground && !state.closing;
    if (next === foreground) return;
    foreground = next;
    for (const listener of listeners) listener();
  });
  runtime.registerFlushHandler(async () => {
    await abortAndSettleRequests();
    await flushStorage();
  });
}
