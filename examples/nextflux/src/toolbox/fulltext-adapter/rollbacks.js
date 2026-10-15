import { authState } from "@/stores/authStore.js";
import minifluxAPI from "@/api/miniflux.js";
import { createRollbackJournal } from "./rollback-journal.mjs";
import { replayScraperRollbacks } from "./fulltext-server.mjs";

// The signed-in account's rollback journal, or none outside ToolBox.
export function rollbackJournal() {
  const storage = globalThis.ToolBox?.storage;
  const { serverUrl, userId } = authState.get();
  return storage && userId ? createRollbackJournal({ storage, account: `${serverUrl}#${userId}` }) : undefined;
}

let replaying = null;
// Rolls back rules left on the server by a test cut short (once per start,
// and again before a new test so it never builds on a leftover rule).
export function replayRollbacks() {
  const journal = rollbackJournal();
  if (!journal) return Promise.resolve();
  if (!replaying) replaying = replayScraperRollbacks({ api: minifluxAPI, journal }).finally(() => { replaying = null; });
  return replaying;
}
