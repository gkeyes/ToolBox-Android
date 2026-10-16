import { memo, useState } from "react";
import { Link } from "react-router";
import {
  Bell,
  BellOff,
  FilePen,
  Trash2,
} from "lucide-react";
import { useStore } from "@nanostores/react";
import { toast } from "sonner";
import {
  SyncIcon as RefreshCw,
  MarkAllReadIcon as CircleCheck,
  WarningIcon as TriangleAlert,
} from "@/components/ui/icons.jsx";
import { cn } from "@/lib/utils";
import {
  SidebarMenuSubButton,
  SidebarMenuSubItem,
} from "@/components/ui/sidebar";
import { useSidebar } from "@/components/ui/sidebar.jsx";
import FeedIcon from "@/components/ui/FeedIcon";
import { ContextMenu, ContextMenuItem } from "@/components/ui/ContextMenu";
import { useTranslation } from "react-i18next";
import { handleRefresh } from "@/handlers/feedHandlers";
import { handleMarkAllRead } from "@/handlers/articleHandlers";
import {
  editFeedModalOpen,
  unsubscribeModalOpen,
  currentFeedId,
} from "@/stores/modalStore.js";
import { watchedFeeds, setFeedNotification } from "@/toolbox/feed-notifications.js";
import { continuousSync, startContinuousSync } from "@/toolbox/background.js";

async function toggleFeedNotification(feed, enabled, t) {
  try {
    await setFeedNotification(feed.id, enabled);
  } catch (error) {
    toast.error(error?.code === "PERMISSION_DENIED" || error?.code === "SYSTEM_PERMISSION_DENIED"
      ? t("feedNotify.permission")
      : error?.message || t("feedNotify.failed"));
    return;
  }
  if (!enabled) { toast.success(t("feedNotify.off", { title: feed.title })); return; }
  if (continuousSync.get()) { toast.success(t("feedNotify.on", { title: feed.title })); return; }
  // Update notifications are fetched by background sync.
  toast(t("feedNotify.needsSync"), {
    action: {
      label: t("feedNotify.enableSync"),
      onClick: () => startContinuousSync()
        .then(() => toast.success(t("feedNotify.on", { title: feed.title })))
        .catch((error) => toast.error(error?.message || t("sidebar.backgroundSync.failed"))),
    },
  });
}

// Rows get their active state and count from FeedsGroupContent; they do not
// subscribe to the router or the count stores themselves.
const FeedItem = ({ feed, active, count }) => {
  const { t } = useTranslation();
  const { isMobile, setOpenMobile } = useSidebar();
  const notifying = useStore(watchedFeeds).includes(Number(feed.id));
  const [contextMenu, setContextMenu] = useState({
    isOpen: false,
    position: { x: 0, y: 0 },
  });

  const handleContextMenu = (e) => {
    e.preventDefault();
    setContextMenu({
      isOpen: true,
      position: {
        x: e.clientX,
        y: e.clientY,
        // The link is the whole feed row, count included. Its list item has no
        // menu-item marker, so closest() would lift the parent category.
        anchor: e.currentTarget,
      },
    });
  };

  const closeContextMenu = () => {
    setContextMenu({ isOpen: false, position: { x: 0, y: 0 } });
  };

  return (
    <SidebarMenuSubItem>
      <SidebarMenuSubButton
        asChild
        isActive={active}
        className={cn(
          "flex-wrap gap-x-[12px] gap-y-0 pl-[44px] pr-[12px]",
          active && "active-feed",
        )}
      >
        <Link
          to={`/feed/${feed.id}`}
          onClick={() => isMobile && setOpenMobile(false)}
          onContextMenu={handleContextMenu}
        >
          <FeedIcon feedId={feed.id} />
          {/* At large text on a narrow sidebar the name keeps room for a few
              characters and the count moves to the next line. */}
          <span className="flex-1 flex min-w-[min(4em,calc(100%_-_1.25rem_-_12px))] items-center gap-1">
            {feed.parsing_error_count > 0 && (
              <span className="text-warning">
                <TriangleAlert className="size-4" />
              </span>
            )}
            <span className="min-w-0 truncate">{feed.title}</span>
            {notifying && (
              <Bell className="size-3.5 shrink-0 text-accent" role="img" aria-label={t("feedNotify.marked")} />
            )}
          </span>
          <span className="sidebar-badge ml-auto shrink-0 ds-tnum text-[length:var(--ds-text-subhead)]">
            {count !== 0 && count}
          </span>
        </Link>
      </SidebarMenuSubButton>

      <ContextMenu
        isOpen={contextMenu.isOpen}
        onClose={closeContextMenu}
        position={contextMenu.position}
      >
        <div className="px-2 py-1.5 text-xs font-medium text-muted opacity-60 line-clamp-1">
          {feed.title}
        </div>
        <ContextMenuItem
          onClick={() => {
            handleRefresh(feed.id);
            closeContextMenu();
          }}
          startContent={<RefreshCw className="size-4 text-muted" />}
        >
          {t("common.refresh")}
        </ContextMenuItem>
        <ContextMenuItem
          onClick={() => {
            handleMarkAllRead("feed", feed.id);
            closeContextMenu();
          }}
          startContent={<CircleCheck className="size-4 text-muted" />}
        >
          {t("common.markAllRead")}
        </ContextMenuItem>
        <ContextMenuItem
          onClick={() => {
            currentFeedId.set(feed.id.toString());
            editFeedModalOpen.set(true);
            closeContextMenu();
          }}
          startContent={<FilePen className="size-4 text-muted" />}
        >
          {t("articleList.editFeed")}
        </ContextMenuItem>
        <ContextMenuItem
          onClick={() => {
            closeContextMenu();
            void toggleFeedNotification(feed, !notifying, t);
          }}
          startContent={notifying
            ? <BellOff className="size-4 text-muted" />
            : <Bell className="size-4 text-muted" />}
        >
          {t(notifying ? "feedNotify.turnOff" : "feedNotify.turnOn")}
        </ContextMenuItem>
        <div className="h-1" aria-hidden="true" />
        <ContextMenuItem
          onClick={() => {
            currentFeedId.set(feed.id.toString());
            unsubscribeModalOpen.set(true);
            closeContextMenu();
          }}
          className="text-danger! hover:bg-danger/20!"
          startContent={<Trash2 className="size-4" />}
        >
          {t("articleList.unsubscribe")}
        </ContextMenuItem>
      </ContextMenu>
    </SidebarMenuSubItem>
  );
};

export default memo(FeedItem);
