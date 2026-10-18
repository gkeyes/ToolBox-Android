import { memo, useEffect } from "react";
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
import SidebarFeedNotifications from "@/components/FeedList/components/SidebarFeedNotifications.jsx";
import FeedNotifyPrompts from "@/components/FeedList/components/FeedNotifyPrompts.jsx";
import SidebarHiddenFeeds from "@/components/FeedList/components/SidebarHiddenFeeds.jsx";
import logo from "@/assets/app-icon.svg";
import { getLastSyncTime } from "@/db/storage.js";
import AddFeedButton from "@/components/FeedList/components/AddFeedButton.jsx";
import { useTranslation } from "react-i18next";
import { useSwipeGesture } from "@/hooks/useSwipeGesture";
import { useParams } from "react-router";
import { useIsMobile } from "@/hooks/use-mobile";
import { isModalOpen } from "@/stores/modalStore";

// Sync progress changes once per fetched page; only this line subscribes to it.
const SyncStatusLine = () => {
  const { t } = useTranslation();
  const $lastSync = useStore(lastSync);
  const $isSyncing = useStore(isSyncing);
  const $syncProgress = useStore(syncProgress);
  const $syncError = useStore(error);
  return (
    <span role="status" className="ds-caption ds-tnum line-clamp-2">
      {$isSyncing ? $syncProgress || t("common.syncing") : $syncError?.message || formatLastSync($lastSync)}
    </span>
  );
};

// Route-dependent swipe-to-open lives in a leaf so route changes do not
// re-render the sidebar tree.
const SidebarSwipeOpener = () => {
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
  return null;
};

const FeedListSidebar = () => {
  const $lastSync = useStore(lastSync);
  const { showHiddenFeeds, floatingSidebar, feedFontScale } = useStore(settingsState);

  // The mobile sidebar renders in its own panel, so the scale lives on the root.
  useEffect(() => {
    const scale = Number.isFinite(feedFontScale) ? Math.min(1.5, Math.max(0.5, feedFontScale)) : 1;
    document.documentElement.style.setProperty("--nf-feed-scale", String(scale));
  }, [feedFontScale]);

  useEffect(() => {
    lastSync.set(getLastSyncTime());
  }, []);

  useEffect(() => {
    loadFeeds();
  }, [$lastSync, showHiddenFeeds]);

  return (
    <>
    <SidebarSwipeOpener />
    <Sidebar
      variant={floatingSidebar ? "floating" : "sidebar"}
      className="sidebar"
    >
      <SidebarHeader className="sidebar-header standalone:pt-safe-or-2">
        <SidebarMenu>
          <SidebarMenuItem>
            <div className="flex flex-wrap items-center gap-2 pl-1">
              <img src={logo} alt="" className="nf-app-icon size-9" />
              <div className="grid min-w-[5em] flex-1 text-left leading-tight">
                <span className="ds-title truncate">Nextflux</span>
                <SyncStatusLine />
              </div>
              <div className="article-list-actions sidebar-header-actions ml-auto flex shrink-0">
                <SyncButton />
                <AddFeedButton />
              </div>
            </div>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        <ScrollShadow className="h-full overscroll-contain" size={10}>
          <ArticlesGroup />
          <FeedsGroup />
          <div className="sidebar-feed-options">
            <SidebarHiddenFeeds />
          </div>
        </ScrollShadow>
      </SidebarContent>
      <SidebarFooter>
        <div className="sidebar-sync-card">
          <SidebarBackgroundSync />
          <SidebarFeedNotifications />
        </div>
        <ProfileButton />
      </SidebarFooter>
    </Sidebar>
    <FeedNotifyPrompts />
    </>
  );
};

export default memo(FeedListSidebar);
