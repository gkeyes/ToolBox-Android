// Per-feed update notifications. After each background sync, every watched feed is asked for
// unread entries newer than the last one this module has seen; a feed with new entries gets one
// ToolBox notification (replaced, not stacked, by the next one). Tapping it opens NextFlux at the
// entry, or at the feed when several arrived.

export const FEED_NOTIFY_KEY = "nextflux.feed-notify.v1";
const MAX_LISTED_TITLES = 3;

export function feedNotificationId(feedId) {
  return `feed-${feedId}`;
}

function emptyState() {
  return { feeds: {}, opens: {}, blocked: null };
}

// Post failures that mean notifications are switched off, not a passing error.
const BLOCKED_CODES = new Set(["PERMISSION_DENIED", "SYSTEM_PERMISSION_DENIED"]);

function normalizeState(saved) {
  if (!saved || typeof saved !== "object" || Array.isArray(saved)) return emptyState();
  const feeds = saved.feeds && typeof saved.feeds === "object" ? saved.feeds : {};
  const opens = saved.opens && typeof saved.opens === "object" ? saved.opens : {};
  const blocked = BLOCKED_CODES.has(saved.blocked) ? saved.blocked : null;
  return { feeds: { ...feeds }, opens: { ...opens }, blocked };
}

function cleanTitle(value, fallback) {
  const text = String(value ?? "").replace(/\s+/g, " ").trim();
  return text || fallback;
}

// The notification wording; NextFlux passes its selected language's strings.
export const DEFAULT_NOTIFY_STRINGS = {
  untitledFeed: "订阅源更新",
  untitled: "无标题",
  one: "有 1 篇新文章",
  separator: "；",
  many: (count, titles) => `${count} 篇新文章：${titles}`,
};

/** Notification text for one feed: the entry title, or a count with the newest titles. */
export function feedNotificationText(feedTitle, entries, total = entries.length, strings = DEFAULT_NOTIFY_STRINGS) {
  const title = cleanTitle(feedTitle, strings.untitledFeed);
  const titles = entries.slice(0, MAX_LISTED_TITLES).map((entry) => cleanTitle(entry.title, strings.untitled));
  const count = Math.max(Number(total) || 0, entries.length);
  if (count <= 1) return { title, body: titles[0] || strings.one };
  const more = count > titles.length ? `…` : "";
  return { title, body: strings.many(count, `${titles.join(strings.separator)}${more}`) };
}

/** The NextFlux route a tapped notification opens; null when the tap is not one of ours. */
export function routeForOpen(record) {
  if (!record || !(Number(record.feedId) > 0)) return null;
  const ids = Array.isArray(record.entryIds) ? record.entryIds.filter((id) => Number(id) > 0) : [];
  const single = ids.length === 1 && !(Number(record.total) > 1);
  return single ? `/feed/${record.feedId}/article/${ids[0]}` : `/feed/${record.feedId}`;
}

/**
 * api.latestEntryId(feedId) -> number|null   newest entry of the feed, any status (baseline)
 * api.newUnreadEntries(feedId, afterEntryId) -> { total, entries } unread entries with id > afterEntryId, newest first
 * notifications: ToolBox.notifications
 * storage: ToolBox.storage
 * feedTitle(feedId) -> string
 * isForeground() -> boolean   the reader is on screen, so a system notification would be noise
 */
export function createFeedNotifier({ api, notifications, storage, feedTitle = () => "", isForeground = () => false, isCached = async () => true, strings = () => DEFAULT_NOTIFY_STRINGS }) {
  let queue = Promise.resolve();
  const serial = (work) => {
    const run = queue.catch(() => {}).then(work);
    queue = run;
    return run;
  };
  // A failed read aborts the run: saving an empty state over it would erase every watch.
  const load = async () => normalizeState(await storage.get(FEED_NOTIFY_KEY));
  const save = (state) => storage.set(FEED_NOTIFY_KEY, state);

  async function watchedFeedIds() {
    const state = await load();
    return Object.keys(state.feeds).map(Number).filter((id) => id > 0);
  }

  /**
   * Turning a feed on records its newest entry, so only entries that arrive afterwards notify.
   * Resolves true, false when turned off, or "blocked" when it is on but the last post was refused
   * because notifications are switched off (Android or the tool's grant); the caller says so.
   */
  function setWatched(feedId, enabled) {
    const id = Number(feedId);
    if (!(id > 0)) return Promise.reject(new Error("订阅编号无效。"));
    return serial(async () => {
      const state = await load();
      if (!enabled) {
        delete state.feeds[id];
        delete state.opens[feedNotificationId(id)];
        await save(state);
        await notifications?.cancel?.(feedNotificationId(id)).catch(() => {});
        return false;
      }
      if (!state.feeds[id]) {
        // Fails with PERMISSION_DENIED while the tool's notification grant is off. It can't see
        // Android's own switch; only a refused post shows that, recorded as state.blocked.
        await notifications.cancel(feedNotificationId(id));
        // The probe proves the tool's grant is back; it can't see Android's own switch.
        if (state.blocked === "PERMISSION_DENIED") state.blocked = null;
        // An empty feed starts at 0, so its first entry notifies.
        state.feeds[id] = { lastEntryId: Number(await api.latestEntryId(id)) || 0 };
        await save(state);
      }
      return state.blocked ? "blocked" : true;
    });
  }

  /**
   * Runs after a committed sync. One failing feed does not stop the others. `foreground` marks a
   * sync the reader ran on screen: it only moves the cursors, even if NextFlux leaves meanwhile.
   */
  function check({ foreground = false, knownFeedIds = null } = {}) {
    return serial(async () => {
      const state = await load();
      const results = [];
      // Feeds gone from the committed catalog (deleted on the server) stop being watched.
      // null means the catalog could not be read; an empty set means no feeds are left.
      if (knownFeedIds) {
        for (const key of Object.keys(state.feeds)) {
          if (knownFeedIds.has(Number(key))) continue;
          delete state.feeds[key];
          delete state.opens[feedNotificationId(Number(key))];
          await notifications?.cancel?.(feedNotificationId(Number(key))).catch(() => {});
          results.push({ feedId: Number(key), outcome: "removed" });
        }
      }
      for (const key of Object.keys(state.feeds)) {
        const feedId = Number(key);
        const watch = state.feeds[key];
        try {
          if (!Number.isSafeInteger(watch?.lastEntryId) || watch.lastEntryId < 0) {
            state.feeds[key] = { lastEntryId: Number(await api.latestEntryId(feedId)) || 0 };
            results.push({ feedId, outcome: "baseline" });
            continue;
          }
          const { entries = [], total: reported = 0 } = await api.newUnreadEntries(feedId, watch.lastEntryId) || {};
          const newer = entries.filter((entry) => Number.isSafeInteger(entry?.id) && entry.id > watch.lastEntryId);
          // Only entries the committed sync wrote announce, so a tap never opens a missing one.
          // The rest stay above the cursor and announce after the sync that caches them.
          const cached = await Promise.all(newer.map((entry) => isCached(entry.id).catch(() => false)));
          const fresh = newer.filter((_, index) => cached[index]).sort((a, b) => b.id - a.id);
          const total = Math.max(fresh.length, (Number(reported) || 0) - (newer.length - fresh.length));
          if (!fresh.length) { results.push({ feedId, outcome: "none" }); continue; }
          if (foreground || isForeground()) {
            state.feeds[key] = { lastEntryId: fresh[0].id };
            results.push({ feedId, outcome: "foreground", count: fresh.length });
            continue;
          }
          const id = feedNotificationId(feedId);
          const text = feedNotificationText(feedTitle(feedId) || fresh[0].feed?.title, fresh, total, strings());
          try {
            await notifications.post(id, text.title, text.body);
          } catch (error) {
            // The cursor stays put, so these entries notify once posting works again.
            if (BLOCKED_CODES.has(error?.code)) state.blocked = error.code;
            throw error;
          }
          state.blocked = null;
          state.feeds[key] = { lastEntryId: fresh[0].id };
          state.opens[id] = { feedId, entryIds: fresh.map((entry) => entry.id), total };
          results.push({ feedId, outcome: "notified", count: Math.max(Number(total) || 0, fresh.length) });
        } catch (error) {
          results.push({ feedId, outcome: "failed", code: error?.code || "ERROR" });
        }
      }
      await save(state);
      return results;
    });
  }

  /** A notifications.onOpen event: the route to open, once. */
  function takeOpen(event) {
    return serial(async () => {
      const state = await load();
      const record = state.opens[event?.id];
      if (!record) return null;
      delete state.opens[event.id];
      await save(state);
      return routeForOpen(record);
    });
  }

  /** Logout: forgets every watch, cursor and pending open, and withdraws posted notices. */
  function clear() {
    return serial(async () => {
      // Removal runs even when the state can't be read; only the notice cleanup needs it.
      const state = await load().catch(() => emptyState());
      await storage.remove(FEED_NOTIFY_KEY);
      await Promise.all(Object.keys(state.feeds).map((key) =>
        notifications?.cancel?.(feedNotificationId(Number(key))).catch(() => {})));
    });
  }

  return { watchedFeedIds, setWatched, check, takeOpen, clear };
}
