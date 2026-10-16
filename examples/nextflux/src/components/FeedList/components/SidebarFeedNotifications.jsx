import { useState } from "react";
import { useStore } from "@nanostores/react";
import { Bell, ChevronDown } from "lucide-react";
import { useTranslation } from "react-i18next";
import { feeds } from "@/stores/feedsStore.js";
import { watchedFeeds, feedNotificationsAvailable } from "@/toolbox/feed-notifications.js";

// The sidebar's 「更新通知」 row: how many feeds notify, and which, when tapped.
export default function SidebarFeedNotifications() {
  const { t } = useTranslation();
  const watched = useStore(watchedFeeds);
  const $feeds = useStore(feeds);
  const [open, setOpen] = useState(false);
  if (!feedNotificationsAvailable()) return null;

  const watchedList = watched
    .map((id) => $feeds.find((feed) => Number(feed.id) === id))
    .filter(Boolean);
  const titles = watchedList.map((feed) => feed.title);
  // Before the catalog loads, count every watch; afterwards only feeds that still exist.
  const count = $feeds.length ? titles.length : watched.length;
  const caption = count
    ? t("feedNotify.watching", { count })
    : t("feedNotify.none");

  return (
    <div className="sidebar-pref-wrap">
      <button
        type="button"
        className="sidebar-pref ds-row w-full text-left"
        aria-expanded={open}
        disabled={!count}
        onClick={() => setOpen((value) => !value)}
      >
        <span className={`ds-icon-badge ${count ? "ds-tone-info" : "ds-tone-neutral"}`}>
          <Bell />
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate ds-body font-medium">{t("feedNotify.title")}</div>
          <div className="truncate ds-caption ds-tnum">{caption}</div>
        </div>
        {count > 0 && (
          <ChevronDown className={`size-4 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} />
        )}
      </button>
      {open && count > 0 && (
        <div className="px-3 pb-2 flex flex-col gap-1">
          {watchedList.map((feed) => (
            <div key={feed.id} className="truncate ds-caption">{feed.title}</div>
          ))}
          <div className="ds-caption opacity-70">{t("feedNotify.listHint")}</div>
        </div>
      )}
    </div>
  );
}
