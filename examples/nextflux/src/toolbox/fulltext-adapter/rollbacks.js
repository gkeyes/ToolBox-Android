import { createRollbackJournal } from "./rollback-journal.mjs";
import { replayScraperRollbacks } from "./fulltext-server.mjs";

// The signed-in account's rollback journal, or none outside ToolBox. Callers
// pass their own auth state and API, so test fixtures can substitute them.
export function rollbackJournal(auth) {
  const storage = globalThis.ToolBox?.storage;
  const { serverUrl, userId } = auth || {};
  return storage && userId ? createRollbackJournal({ storage, account: `${serverUrl}#${userId}` }) : undefined;
}

let replaying = null;
// Rolls back rules left on the server by a test cut short (once per start,
// and again before a new test so it never builds on a leftover rule).
// Resolves to the rollbacks still owed.
export function replayRollbacks({ auth, api }) {
  const journal = rollbackJournal(auth);
  if (!journal) return Promise.resolve([]);
  if (!replaying) replaying = replayScraperRollbacks({ api, journal }).finally(() => { replaying = null; });
  return replaying;
}
