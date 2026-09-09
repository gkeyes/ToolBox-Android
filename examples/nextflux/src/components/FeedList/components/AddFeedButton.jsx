import { Button, Dropdown, Label } from "@heroui/react";
import { CirclePlus, FolderPlus, Rss, Upload } from "lucide-react";
import { addCategoryModalOpen, addFeedModalOpen } from "@/stores/modalStore";
import { useSidebar } from "@/components/ui/sidebar.jsx";
import minifluxAPI from "@/api/miniflux";
import { toast } from "sonner";
import { forceSync } from "@/stores/syncStore";
import { useTranslation } from "react-i18next";

export default function AddFeedButton() {
  const { t } = useTranslation();
  const { isMobile, setOpenMobile } = useSidebar();

  const importFile = async () => {
    try {
      const token = await window.ToolBox.files.open(["text/xml", "application/xml", "text/x-opml", "application/octet-stream"]);
      if (!token) return;
      if (token.size > 2 * 1024 * 1024) throw new Error("请选择不超过 2 MB 的 OPML 文件。");
      const bytes = await window.ToolBox.files.read(token.token);
      const xml = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      await minifluxAPI.importOPML({ text: async () => xml });
      await forceSync();
      toast.success(t("common.success"));
    } catch (error) {
      toast.error(error.code === "PERMISSION_DENIED" ? "请先在小工具权限中开启文件读取和网络。" : (error.message || "导入失败，请重试。"));
    }
  };

  return (
    <>
      <Dropdown>
        <Button className="nextflux-compact-touch-target" aria-label="添加订阅" size="sm" variant="ghost" isIconOnly>
          <CirclePlus className="size-4 text-muted" />
        </Button>
        <Dropdown.Popover>
          <Dropdown.Menu
            onAction={(key) => {
              if (key === "newFeed") {
                addFeedModalOpen.set(true);
                isMobile && setOpenMobile(false);
              }
              if (key === "importOPML") {
                importFile();
                isMobile && setOpenMobile(false);
              }
              if (key === "newCategory") {
                addCategoryModalOpen.set(true);
                isMobile && setOpenMobile(false);
              }
            }}
          >
            <Dropdown.Item id="newFeed" textValue={t("sidebar.addFeed")}>
              <Rss className="size-4 text-muted" />
              <Label>{t("sidebar.addFeed")}</Label>
            </Dropdown.Item>
            <Dropdown.Item id="importOPML" textValue={t("sidebar.importOPML")}>
              <Upload className="size-4 text-muted" />
              <Label>{t("sidebar.importOPML")}</Label>
            </Dropdown.Item>
            <Dropdown.Item
              id="newCategory"
              textValue={t("sidebar.addCategory")}
            >
              <FolderPlus className="size-4 text-muted" />
              <Label>{t("sidebar.addCategory")}</Label>
            </Dropdown.Item>
          </Dropdown.Menu>
        </Dropdown.Popover>
      </Dropdown>
    </>
  );
}
