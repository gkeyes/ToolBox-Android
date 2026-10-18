import { useState } from "react";
import { useStore } from "@nanostores/react";
import { Bell, ChevronRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import { feeds } from "@/stores/feedsStore.js";
import { continuousSync } from "@/toolbox/background.js";
import { watchedFeeds, feedNotificationsAvailable } from "@/toolbox/feed-notifications.js";

// Notification settings share the background sync timer, not a separate service.
export default function SidebarFeedNotifications() {
  const { t } = useTranslation();
  const watched = useStore(watchedFeeds);
  const $feeds = useStore(feeds);
  const syncing = useStore(continuousSync);
  const [open, setOpen] = useState(false);
  if (!feedNotificationsAvailable()) return null;

  const watchedList = watched
    .map((id) => $feeds.find((feed) => Number(feed.id) === id))
    .filter(Boolean);
  // Only count feeds still in the catalog, matching the existing watch list behavior.
  const count = watchedList.length;
  const caption = count
    ? t("feedNotify.watchingShort", { count, defaultValue: t("feedNotify.watching", { count }) })
    : t("feedNotify.none");

  return (
    <div className="sidebar-pref-wrap">
      <button
        type="button"
        className="sidebar-pref ds-row w-full text-left"
        aria-expanded={count > 0 && open}
        disabled={!count}
        onClick={() => setOpen((value) => !value)}
      >
        <Bell className="sidebar-pref-icon" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <div className="truncate ds-body font-medium">{t("feedNotify.title")}</div>
          <div className="sidebar-pref-summary ds-caption ds-tnum">
            <span>{caption}</span>
            {count > 0 && !syncing && (
              <span className="sidebar-notify-paused">
                · {t("feedNotify.paused", { defaultValue: t("sidebar.backgroundSync.off") })}
              </span>
            )}
          </div>
        </div>
        {count > 0 && (
          <ChevronRight
            aria-hidden="true"
            className={"sidebar-pref-chevron shrink-0 transition-transform" + (open ? " rotate-90" : "")}
          />
        )}
      </button>
      {count > 0 && open && (
        <div className="sidebar-notify-details" role="region" aria-label={t("feedNotify.title")}>
          <p className="ds-caption font-medium">
            {t("feedNotify.listTitle", { defaultValue: t("common.feed") })}
          </p>
          <ul className="sidebar-notify-list">
            {watchedList.map((feed) => (
              <li key={feed.id}>{feed.title}</li>
            ))}
          </ul>
          <p className="ds-caption">{t("feedNotify.listHint")}</p>
        </div>
      )}
    </div>
  );
}
