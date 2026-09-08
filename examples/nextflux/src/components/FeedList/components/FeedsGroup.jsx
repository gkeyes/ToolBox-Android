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
const FeedsGroup = () => {
  const { t } = useTranslation();
  const $feedsByCategory = useStore(feedsByCategory);
  return (
    <SidebarGroup>
      <SidebarGroupLabel>{t("common.feed")}</SidebarGroupLabel>
      <SidebarGroupContent>
        <SidebarMenu>
          {$feedsByCategory.map((category) => (
            <FeedsGroupContent key={category.id} category={category} />
          ))}
        </SidebarMenu>
      </SidebarGroupContent>
    </SidebarGroup>
  );
};

export default FeedsGroup;
