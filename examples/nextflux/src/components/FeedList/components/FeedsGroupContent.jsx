import { useStore } from "@nanostores/react";
import {
  getFeedCount,
  getCategoryCount,
  categoryExpandedState,
  updateCategoryExpandState,
} from "@/stores/feedsStore.js";
import { cn } from "@/lib/utils";
import { ChevronRight, FolderPen } from "lucide-react";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible.jsx";
import { Link } from "react-router";
import {
  SidebarMenuAction,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
} from "@/components/ui/sidebar";
import { useSidebar } from "@/components/ui/sidebar.jsx";
import { settingsState } from "@/stores/settingsStore";
import { memo, useEffect, useState } from "react";
import FeedItem from "./FeedItem";
import { ContextMenu, ContextMenuItem } from "@/components/ui/ContextMenu";
import { renameModalOpen, currentCategoryId } from "@/stores/modalStore.js";
import { useTranslation } from "react-i18next";

const FeedsGroupContent = ({ category, active, activeFeedId }) => {
  const { t } = useTranslation();
  const $getCategoryCount = useStore(getCategoryCount);
  const $getFeedCount = useStore(getFeedCount);
  const { isMobile, setOpenMobile } = useSidebar();
  const { defaultExpandCategory } = useStore(settingsState);
  const $categoryExpandedState = useStore(categoryExpandedState);
  const [contextMenu, setContextMenu] = useState({
    isOpen: false,
    position: { x: 0, y: 0 },
  });

  useEffect(() => {
    // activeFeedId is set only for the category that contains the active feed.
    if (activeFeedId != null) {
      updateCategoryExpandState(category.id, true);
      // 滚动到活动的 feed
      const feedItem = document.querySelector(".active-feed");
      feedItem?.scrollIntoView({ behavior: "instant", block: "nearest" });
    }
  }, [activeFeedId, category.id]);

  const handleContextMenu = (e) => {
    e.preventDefault();
    setContextMenu({
      isOpen: true,
      position: {
        x: e.clientX,
        y: e.clientY,
        // The item carries the chevron and count; only its row (the link's
        // box) lifts, not the feeds nested under it.
        anchor: e.currentTarget.closest('[data-sidebar="menu-item"]') || e.currentTarget,
        row: e.currentTarget,
      },
    });
  };

  const closeContextMenu = () => {
    setContextMenu({ isOpen: false, position: { x: 0, y: 0 } });
  };

  return (
    <Collapsible
      key={category.id}
      open={$categoryExpandedState[category.id] ?? defaultExpandCategory}
      onOpenChange={(open) => updateCategoryExpandState(category.id, open)}
    >
      <SidebarMenuItem key={`menu-${category.id}`}>
        <SidebarMenuButton
          isActive={active}
          asChild
        >
          <Link
            to={`/category/${category.id}`}
            onClick={() => isMobile && setOpenMobile(false)}
            onContextMenu={handleContextMenu}
          >
            <span className="pl-[32px] min-w-0 truncate font-medium">{category.title}</span>
          </Link>
        </SidebarMenuButton>

        <CollapsibleTrigger asChild>
          <SidebarMenuAction className="sidebar-disclosure left-0 right-auto text-muted" aria-label={category.title}>
            <ChevronRight />
          </SidebarMenuAction>
        </CollapsibleTrigger>
        <SidebarMenuBadge>
          {$getCategoryCount(category.id) !== 0 &&
            $getCategoryCount(category.id)}
        </SidebarMenuBadge>
        <CollapsibleContent>
          <SidebarMenuSub className="m-0 px-0 border-none">
            {category.feeds.map((feed) => (
              <FeedItem
                key={feed.id}
                feed={feed}
                active={feed.id === activeFeedId}
                count={$getFeedCount(feed.id)}
              />
            ))}
          </SidebarMenuSub>
        </CollapsibleContent>

        <ContextMenu
          isOpen={contextMenu.isOpen}
          onClose={closeContextMenu}
          position={contextMenu.position}
        >
          <div className="px-2 py-1.5 text-xs font-medium text-muted opacity-60 line-clamp-1">
            {category.title}
          </div>
          <ContextMenuItem
            onClick={() => {
              currentCategoryId.set(category.id.toString());
              renameModalOpen.set(true);
              closeContextMenu();
            }}
            startContent={<FolderPen className="size-4 text-muted" />}
          >
            {t("articleList.renameCategory.title")}
          </ContextMenuItem>
        </ContextMenu>
      </SidebarMenuItem>
    </Collapsible>
  );
};

export default memo(FeedsGroupContent);
