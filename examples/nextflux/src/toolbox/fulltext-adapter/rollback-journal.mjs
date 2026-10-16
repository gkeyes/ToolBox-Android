// Rollbacks still owed to the Miniflux server after an AI full-text test,
// kept in ToolBox storage so they survive an exit mid-test. One entry per
// account and feed; writes are queued so they never overtake each other.

const KEY = "nextflux.scraper-rollbacks.v1";

export function createRollbackJournal({ storage, account }) {
  let queue = Promise.resolve();
  const prefix = `${account}:`;
  const read = async () => {
    const stored = await storage.get(KEY);
    return stored && typeof stored === "object" && !Array.isArray(stored) ? stored : {};
  };
  const change = (edit) => {
    const run = queue.then(async () => {
      const all = await read();
      edit(all);
      await storage.set(KEY, all);
    });
    queue = run.catch(() => {});
    return run;
  };
  return {
    save: (entry) => change((all) => { all[prefix + entry.feedId] = entry; }),
    clear: (feedId) => change((all) => { delete all[prefix + feedId]; }),
    async list() {
      await queue;
      return Object.entries(await read())
        .filter(([key, entry]) => key.startsWith(prefix) && entry && typeof entry.candidate === "string")
        .map(([, entry]) => ({
          feedId: Number(entry.feedId), candidate: entry.candidate, baselineRule: String(entry.baselineRule || ""),
          ...(entry.failure === "server" || entry.failure === "network" ? { failure: entry.failure } : {}),
        }));
    },
  };
}
