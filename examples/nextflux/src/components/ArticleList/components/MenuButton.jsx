import { Button, Dropdown, Label } from "@heroui/react";
import {
  EllipsisVertical,
  FilePen,
  FolderPen,
  Trash2,
  RefreshCw,
} from "lucide-react";
import { useParams } from "react-router-dom";
import {
  editFeedModalOpen,
  renameModalOpen,
  unsubscribeModalOpen,
  currentFeedId,
} from "@/stores/modalStore.js";
import { useTranslation } from "react-i18next";
import { handleRefresh } from "@/handlers/feedHandlers";

export default function MenuButton() {
  const { feedId, categoryId } = useParams();
  const { t } = useTranslation();

  const hasFeedMenu = !!feedId;
  const hasCategoryMenu = !!categoryId && !feedId;
  const isDisabled = !feedId && !categoryId;

  return (
    <Dropdown>
      <Button size="sm" variant="ghost" isIconOnly isDisabled={isDisabled}>
        <EllipsisVertical className="size-4 text-muted" />
      </Button>

      {(hasFeedMenu || hasCategoryMenu) && (
        <Dropdown.Popover>
          <Dropdown.Menu
            aria-label={hasFeedMenu ? "Feed Actions" : "Category Actions"}
            onAction={(key) => {
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
      )}
    </Dropdown>
  );
}
