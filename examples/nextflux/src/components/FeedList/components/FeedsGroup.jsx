import { useStore } from "@nanostores/react";
import { feedsByCategory } from "@/stores/feedsStore.js";
import {
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarMenu,
} from "@/components/ui/sidebar";
import FeedsGroupContent from "@/components/FeedList/components/FeedsGroupContent.jsx";
import { useTranslation } from "react-i18next";
import { useParams } from "react-router";

// The only route subscriber of the feed catalog: rows receive plain active
// flags, so opening an article or switching feeds re-renders just the rows
// whose active state changed.
const FeedsGroup = () => {
  const { t } = useTranslation();
  const $feedsByCategory = useStore(feedsByCategory);
  const { categoryId, feedId } = useParams();
  const activeFeed = feedId == null ? null : parseInt(feedId);
  return (
    <SidebarGroup>
      <SidebarGroupLabel>{t("common.feed")}</SidebarGroupLabel>
      <SidebarGroupContent>
        <SidebarMenu>
          {$feedsByCategory.map((category) => (
            <FeedsGroupContent
              key={category.id}
              category={category}
              active={String(categoryId) === String(category.id)}
              activeFeedId={activeFeed != null && category.feeds.some((feed) => feed.id === activeFeed) ? activeFeed : null}
            />
          ))}
        </SidebarMenu>
      </SidebarGroupContent>
    </SidebarGroup>
  );
};

export default FeedsGroup;
