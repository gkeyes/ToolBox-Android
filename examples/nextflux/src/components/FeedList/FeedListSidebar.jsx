import { useEffect } from "react";
import { useStore } from "@nanostores/react";
import { loadFeeds } from "@/stores/feedsStore.js";
import { isSyncing, lastSync, syncProgress, error } from "@/stores/syncStore.js";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar";
import { ScrollShadow } from "@heroui/react";
import { formatLastSync } from "@/lib/format";
import { settingsState } from "@/stores/settingsStore.js";
import ArticlesGroup from "@/components/FeedList/components/ArticlesGroup.jsx";
import FeedsGroup from "@/components/FeedList/components/FeedsGroup.jsx";
import SyncButton from "@/components/FeedList/components/SyncButton.jsx";
import ProfileButton from "@/components/FeedList/components/ProfileButton.jsx";
import SidebarBackgroundSync from "@/components/FeedList/components/SidebarBackgroundSync.jsx";
import SidebarHiddenFeeds from "@/components/FeedList/components/SidebarHiddenFeeds.jsx";
import logo from "@/assets/logo.png";
import { getLastSyncTime } from "@/db/storage.js";
import AddFeedButton from "@/components/FeedList/components/AddFeedButton.jsx";
import { useTranslation } from "react-i18next";
import { useSwipeGesture } from "@/hooks/useSwipeGesture";
import { useParams } from "react-router-dom";
import { useIsMobile } from "@/hooks/use-mobile";
import { isModalOpen } from "@/stores/modalStore";

const FeedListSidebar = () => {
  const { t } = useTranslation();
  const $lastSync = useStore(lastSync);
  const $isSyncing = useStore(isSyncing);
  const $syncProgress = useStore(syncProgress);
  const $syncError = useStore(error);
  const { showHiddenFeeds, floatingSidebar } = useStore(settingsState);
  const { setOpenMobile } = useSidebar();
  const { articleId } = useParams();
  const { isMobile } = useIsMobile();
  useSwipeGesture({
    enabled: !articleId && isMobile,
    threshold: 50,
    onSwipeRight: () => {
      if (!articleId && isMobile && !isModalOpen.get()) {
        setOpenMobile(true);
      }
    },
  });

  useEffect(() => {
    lastSync.set(getLastSyncTime());
  }, []);

  useEffect(() => {
    loadFeeds();
  }, [$lastSync, showHiddenFeeds]);

  return (
    <Sidebar
      variant={floatingSidebar ? "floating" : "sidebar"}
      className="sidebar"
    >
      <SidebarHeader className="sidebar-header standalone:pt-safe-or-2">
        <SidebarMenu>
          <SidebarMenuItem>
            <div className="flex items-center gap-2 pl-1">
              <img src={logo} alt="" className="size-9 rounded-[var(--ds-radius-small)]" />
              <div className="grid min-w-0 flex-1 text-left leading-tight">
                <span className="ds-title truncate">Nextflux</span>
                <span role="status" className="ds-caption ds-tnum line-clamp-2">
                  {$isSyncing ? $syncProgress || t("common.syncing") : $syncError?.message || formatLastSync($lastSync)}
                </span>
              </div>
              <SyncButton />
              <AddFeedButton />
            </div>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        <ScrollShadow className="h-full overscroll-contain" size={10}>
          <ArticlesGroup />
          <FeedsGroup />
        </ScrollShadow>
      </SidebarContent>
      <SidebarFooter>
        <SidebarBackgroundSync />
        <SidebarHiddenFeeds />
        <ProfileButton />
      </SidebarFooter>
    </Sidebar>
  );
};

export default FeedListSidebar;
