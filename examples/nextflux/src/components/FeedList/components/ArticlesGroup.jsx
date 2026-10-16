import { useStore } from "@nanostores/react";
import { filter } from "@/stores/articlesStore.js";
import { totalStarredCount, totalUnreadCount } from "@/stores/feedsStore.js";
import {
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar.jsx";
import { Link, useLocation, useParams } from "react-router";
import SidebarBadge from "./SidebarBadge.jsx";
import { useTranslation } from "react-i18next";

const ArticlesGroup = () => {
  const { t } = useTranslation();
  const $filter = useStore(filter);
  const $totalUnreadCount = useStore(totalUnreadCount);
  const $totalStarredCount = useStore(totalStarredCount);
  const { isMobile, setOpenMobile } = useSidebar();
  const { feedId, categoryId } = useParams();
  const onBoard = useLocation().pathname.startsWith("/board");
  // 根据筛选条件获取显示文本和计数
  const getDisplayInfo = () => {
    switch ($filter) {
      case "unread":
        return {
          icon: <SidebarBadge kind="unread" />,
          text: t("articleList.unread"),
          count: $totalUnreadCount,
        };
      case "starred":
        return {
          icon: <SidebarBadge kind="starred" />,
          text: t("articleList.starred"),
          count: $totalStarredCount,
        };
      default:
        return {
          icon: <SidebarBadge kind="all" />,
          text: t("articleList.all"),
          count: $totalUnreadCount,
        };
    }
  };

  const { icon, text, count } = getDisplayInfo();
  return (
    <SidebarGroup className="group-data-[collapsible=icon]:hidden">
      <SidebarGroupLabel>{t("common.article")}</SidebarGroupLabel>
      <SidebarMenu>
        <SidebarMenuItem>
          <SidebarMenuButton asChild size="lg" isActive={!feedId && !categoryId && !onBoard}>
            <Link to="/" onClick={() => isMobile && setOpenMobile(false)}>
              {icon}
              <span className="font-semibold">{text}</span>
            </Link>
          </SidebarMenuButton>
          <SidebarMenuBadge className="h-12">
            {count !== 0 && count}
          </SidebarMenuBadge>
        </SidebarMenuItem>
        <SidebarMenuItem>
          <SidebarMenuButton asChild size="lg" isActive={onBoard}>
            <Link to="/board" onClick={() => isMobile && setOpenMobile(false)}>
              <SidebarBadge kind="board" />
              <span className="font-semibold">今日看板</span>
            </Link>
          </SidebarMenuButton>
        </SidebarMenuItem>
      </SidebarMenu>
    </SidebarGroup>
  );
};

export default ArticlesGroup;
