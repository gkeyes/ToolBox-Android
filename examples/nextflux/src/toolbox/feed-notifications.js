import { atom } from "nanostores";
import { feeds } from "../stores/feedsStore.js";
import { runtimeForeground } from "./foreground.js";
import { getLatestFeedEntryId, getNewUnreadFeedEntries } from "../api/miniflux.js";
import { createFeedNotifier } from "./feed-notify.mjs";
import { getArticleMetadata } from "../db/storage.js";
import i18n from "../i18n/index.js";

// Feed ids with update notifications on, for the sidebar's bell marks and menu labels.
export const watchedFeeds = atom([]);
// The dialog the feed menu asks for: null, { kind: "needsSync", feed } or { kind: "permission" }.
export const feedNotifyPrompt = atom(null);

export function feedNotificationsAvailable() {
  return Boolean(toolbox()?.notifications && toolbox()?.storage);
}
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
      isCached: async (entryId) => Boolean(await getArticleMetadata(entryId)),
      strings: () => ({
        untitledFeed: i18n.t("feedNotify.untitledFeed"),
        untitled: i18n.t("feedNotify.untitled"),
        one: i18n.t("feedNotify.one"),
        separator: i18n.t("feedNotify.separator"),
        many: (count, titles) => i18n.t("feedNotify.many", { count, titles }),
      }),
    });
  }
  return notifier;
}

export async function restoreFeedNotifications() {
  if (!toolbox()?.notifications || !toolbox()?.storage) return;
  watchedFeeds.set(await current().watchedFeedIds());
}

/** Resolves true, false, or "blocked" (on, but notifications are switched off). */
export async function setFeedNotification(feedId, enabled) {
  const result = await current().setWatched(feedId, enabled);
  watchedFeeds.set(await current().watchedFeedIds());
  return result;
}

/** Logout clears the account's watches; an unsubscribed feed drops its own. */
export async function clearFeedNotifications() {
  if (!toolbox()?.notifications || !toolbox()?.storage) return;
  await current().clear();
  watchedFeeds.set([]);
}

export async function forgetFeedNotification(feedId) {
  if (!toolbox()?.notifications || !toolbox()?.storage) return;
  await setFeedNotification(feedId, false);
}

/** After a committed background sync. Reads the watch list from storage, so it also works before the sidebar restored it. */
export function checkWatchedFeeds(options) {
  if (!toolbox()?.notifications || !toolbox()?.storage) return Promise.resolve([]);
  return current().check(options);
}

/** Opens the entry or feed behind a tapped notification. Hosts without onOpen just open NextFlux. */
export function installNotificationOpen(navigate) {
  const notifications = toolbox()?.notifications;
  if (typeof notifications?.onOpen !== "function") return;
  notifications.onOpen((event) => {
    void current().takeOpen(event).then((route) => { if (route) navigate(route); }).catch(() => {});
  });
}
