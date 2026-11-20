import { Button, Dropdown, Label } from "@heroui/react";
import {
  FilePen,
  FolderPen,
  Trash2,
} from "lucide-react";
import {
  MoreVerticalIcon as EllipsisVertical,
  SearchIcon,
  SyncIcon as RefreshCw,
} from "@/components/ui/icons.jsx";
import { useParams } from "react-router";
import { memo } from "react";
import {
  editFeedModalOpen,
  renameModalOpen,
  searchDialogOpen,
  unsubscribeModalOpen,
  currentFeedId,
} from "@/stores/modalStore.js";
import { useTranslation } from "react-i18next";
import { handleRefresh } from "@/handlers/feedHandlers";

// Memoised: the header re-renders on every filter switch, and this menu's
// React Aria trigger is costly to redraw for nothing.
export default memo(MenuButton);
function MenuButton() {
  const { feedId, categoryId } = useParams();
  const { t } = useTranslation();

  const hasFeedMenu = !!feedId;
  const hasCategoryMenu = !!categoryId && !feedId;
  if (!hasFeedMenu && !hasCategoryMenu) {
    return <Button className="nextflux-toolbar-button" aria-label={t("common.search")}
      size="sm" variant="ghost" isIconOnly onPress={() => searchDialogOpen.set(true)}>
      <SearchIcon className="size-4 text-muted" />
    </Button>;
  }

  return (
    <Dropdown>
      <Button className="nextflux-toolbar-button" aria-label={t("common.more", { defaultValue: "More" })} size="sm" variant="ghost" isIconOnly>
        <EllipsisVertical className="size-4 text-muted" />
      </Button>

      <Dropdown.Popover>
        <Dropdown.Menu
          aria-label={hasFeedMenu ? "Feed Actions" : "Category Actions"}
          onAction={(key) => {
            if (key === "search") searchDialogOpen.set(true);
            if (hasFeedMenu) {
              if (key === "refresh") handleRefresh(feedId);
              if (key === "edit") {
                currentFeedId.set(feedId);
                editFeedModalOpen.set(true);
              }
              if (key === "unsubscribe") {
                currentFeedId.set(feedId);
                unsubscribeModalOpen.set(true);
              }
            }

            if (hasCategoryMenu) {
              if (key === "rename") renameModalOpen.set(true);
            }
          }}
        >
          <Dropdown.Item id="search" textValue={t("common.search")}>
            <SearchIcon className="size-4 text-muted" />
            <Label>{t("common.search")}</Label>
          </Dropdown.Item>
          {hasFeedMenu && (
            <>
              <Dropdown.Item
                id="refresh"
                textValue="refresh"
                className="cursor-pointer"
              >
                <RefreshCw className="size-4 text-muted" />
                <Label>{t("articleList.refreshFeed")}</Label>
              </Dropdown.Item>
              <Dropdown.Item id="edit" textValue="edit">
                <FilePen className="size-4 text-muted" />
                <Label>{t("articleList.editFeed")}</Label>
              </Dropdown.Item>

              <Dropdown.Item id="unsubscribe" textValue="unsubscribe">
                <Trash2 className="size-4 text-danger" />
                <Label className="text-danger">
                  {t("articleList.unsubscribe")}
                </Label>
              </Dropdown.Item>
            </>
          )}

          {hasCategoryMenu && (
            <Dropdown.Item id="rename" textValue="rename">
              <FolderPen className="size-4 text-muted" />
              <Label>{t("articleList.renameCategory.title")}</Label>
            </Dropdown.Item>
          )}
        </Dropdown.Menu>
      </Dropdown.Popover>
    </Dropdown>
  );
}
