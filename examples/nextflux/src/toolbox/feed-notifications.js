import { atom } from "nanostores";
import { feeds } from "../stores/feedsStore.js";
import { runtimeForeground } from "./foreground.js";
import { getLatestFeedEntryId, getNewUnreadFeedEntries } from "../api/miniflux.js";
import { createFeedNotifier } from "./feed-notify.mjs";

// Feed ids with update notifications on, for the sidebar's bell marks and menu labels.
export const watchedFeeds = atom([]);
let notifier = null;

function toolbox() { return globalThis.window?.ToolBox; }

function current() {
  if (!notifier) {
    notifier = createFeedNotifier({
      api: { latestEntryId: getLatestFeedEntryId, newUnreadEntries: getNewUnreadFeedEntries },
      notifications: toolbox().notifications,
      storage: toolbox().storage,
      feedTitle: (feedId) => feeds.get().find((feed) => Number(feed.id) === Number(feedId))?.title || "",
      isForeground: () => runtimeForeground.get(),
    });
  }
  return notifier;
}

export async function restoreFeedNotifications() {
  if (!toolbox()?.notifications || !toolbox()?.storage) return;
  watchedFeeds.set(await current().watchedFeedIds());
}

export async function setFeedNotification(feedId, enabled) {
  await current().setWatched(feedId, enabled);
  watchedFeeds.set(await current().watchedFeedIds());
}

/** After a committed background sync. Reads the watch list from storage, so it also works before the sidebar restored it. */
export function checkWatchedFeeds() {
  if (!toolbox()?.notifications || !toolbox()?.storage) return Promise.resolve([]);
  return current().check();
}

/** Opens the entry or feed behind a tapped notification. Hosts without onOpen just open NextFlux. */
export function installNotificationOpen(navigate) {
  const notifications = toolbox()?.notifications;
  if (typeof notifications?.onOpen !== "function") return;
  notifications.onOpen((event) => {
    void current().takeOpen(event).then((route) => { if (route) navigate(route); }).catch(() => {});
  });
}
