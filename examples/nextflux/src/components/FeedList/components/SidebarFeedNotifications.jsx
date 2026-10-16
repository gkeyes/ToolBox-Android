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

  const titles = watched
    .map((id) => $feeds.find((feed) => Number(feed.id) === id)?.title)
    .filter(Boolean);
  const caption = watched.length
    ? t("feedNotify.watching", { count: watched.length })
    : t("feedNotify.none");

  return (
    <div className="sidebar-pref-wrap">
      <button
        type="button"
        className="sidebar-pref ds-row w-full text-left"
        aria-expanded={open}
        disabled={!watched.length}
        onClick={() => setOpen((value) => !value)}
      >
        <span className={`ds-icon-badge ${watched.length ? "ds-tone-info" : "ds-tone-neutral"}`}>
          <Bell />
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate ds-body font-medium">{t("feedNotify.title")}</div>
          <div className="truncate ds-caption ds-tnum">{caption}</div>
        </div>
        {watched.length > 0 && (
          <ChevronDown className={`size-4 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} />
        )}
      </button>
      {open && watched.length > 0 && (
        <div className="px-3 pb-2 flex flex-col gap-1">
          {titles.map((title) => (
            <div key={title} className="truncate ds-caption">{title}</div>
          ))}
          <div className="ds-caption opacity-70">{t("feedNotify.listHint")}</div>
        </div>
      )}
    </div>
  );
}
