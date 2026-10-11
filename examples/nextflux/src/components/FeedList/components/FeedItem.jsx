import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  RefreshCw,
  CircleCheck,
  TriangleAlert,
  FilePen,
  Trash2,
} from "lucide-react";
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
import { useStore } from "@nanostores/react";
import { getFeedCount } from "@/stores/feedsStore.js";
import {
  editFeedModalOpen,
  unsubscribeModalOpen,
  currentFeedId,
} from "@/stores/modalStore.js";

const FeedItem = ({ feed }) => {
  const { t } = useTranslation();
  const { isMobile, setOpenMobile } = useSidebar();
  const { feedId } = useParams();
  const $getFeedCount = useStore(getFeedCount);
  const [contextMenu, setContextMenu] = useState({
    isOpen: false,
    position: { x: 0, y: 0 },
  });

  const handleContextMenu = (e) => {
    e.preventDefault();
    setContextMenu({
      isOpen: true,
      position: { x: e.clientX, y: e.clientY },
    });
  };

  const closeContextMenu = () => {
    setContextMenu({ isOpen: false, position: { x: 0, y: 0 } });
  };

  return (
    <SidebarMenuSubItem>
      <SidebarMenuSubButton
        asChild
        isActive={parseInt(feedId) === feed.id}
        className={cn(
          "pl-11 pr-3",
          parseInt(feedId) === feed.id && "active-feed",
        )}
      >
        <Link
          to={`/feed/${feed.id}`}
          onClick={() => isMobile && setOpenMobile(false)}
          onContextMenu={handleContextMenu}
        >
          <FeedIcon feedId={feed.id} />
          <span className="flex-1 flex items-center gap-1">
            {feed.parsing_error_count > 0 && (
              <span className="text-warning">
                <TriangleAlert className="size-4" />
              </span>
            )}
            <span className="line-clamp-1">{feed.title}</span>
          </span>
          <span className="sidebar-badge ds-tnum text-[length:var(--ds-text-subhead)]">
            {$getFeedCount(feed.id) !== 0 && $getFeedCount(feed.id)}
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

export default FeedItem;
