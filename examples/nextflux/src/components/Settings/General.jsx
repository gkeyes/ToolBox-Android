import { continuousSync, startContinuousSync, stopContinuousSync } from "@/toolbox/background.js";
import { toast } from "sonner";
import { useState } from "react";
import { settingsState } from "@/stores/settingsStore";
import {
  CircleCheck,
  CircleDot,
  ClockArrowDown,
  ClockArrowUp,
  Eye,
  FolderOpen,
  RefreshCw,
  CalendarDays,
} from "lucide-react";
import { useStore } from "@nanostores/react";
import {
  ItemWrapper,
  SelItem,
  SwitchItem,
} from "@/components/ui/settingItem.jsx";
import { Button, Separator } from "@heroui/react";
import Language from "@/components/Settings/components/Language.jsx";
import { useTranslation } from "react-i18next";
import SettingIcon from "@/components/ui/SettingIcon";

export default function General() {
  const activeBackground = useStore(continuousSync);
  const [changingBackground, setChangingBackground] = useState(false);
  const {
    sortDirection,
    sortField,
    showHiddenFeeds,
    markAsReadOnScroll,
    syncInterval,
    defaultExpandCategory,
    showUnreadByDefault,
  } = useStore(settingsState);
  const { t } = useTranslation();
  return (
    <>
      <Language />
      <ItemWrapper title={t("settings.general.sync")}>
        <SelItem
          label={t("settings.general.syncInterval")}
          icon={
            <SettingIcon variant="default">
              <RefreshCw />
            </SettingIcon>
          }
          settingName="syncInterval"
          settingValue={syncInterval}
          options={[
            { value: "0", label: t("settings.general.syncOff") },
            { value: "5", label: t("settings.general.sync5min") },
            { value: "15", label: t("settings.general.sync15min") },
            { value: "30", label: t("settings.general.sync30min") },
            { value: "60", label: t("settings.general.sync1hour") },
          ]}
        />
      </ItemWrapper>
      <div className="px-3 flex flex-col gap-2">
        <Button variant="secondary" isPending={changingBackground} onPress={async () => {
          setChangingBackground(true);
          try { if (activeBackground) await stopContinuousSync(); else await startContinuousSync(); }
          catch (error) { toast.error(error.message || "后台同步操作失败，请重试。"); }
          finally { setChangingBackground(false); }
        }}>{activeBackground ? "停止后台同步" : "离开页面后继续同步"}</Button>
        <p className="text-xs text-muted">后台同步使用持续通知，可随时停止。同步间隔关闭时，后台按 15 分钟同步。</p>
      </div>
      <ItemWrapper title={t("settings.general.feeds")}>
        <SwitchItem
          label={t("settings.general.showHiddenFeeds")}
          icon={
            <SettingIcon variant="purple">
              <Eye />
            </SettingIcon>
          }
          settingName="showHiddenFeeds"
          settingValue={showHiddenFeeds}
        />
        <Separator />
        <SwitchItem
          label={t("settings.general.defaultExpandCategory")}
          icon={
            <SettingIcon variant="blue">
              <FolderOpen />
            </SettingIcon>
          }
          settingName="defaultExpandCategory"
          settingValue={defaultExpandCategory}
        />
      </ItemWrapper>
      <ItemWrapper title={t("settings.general.articleList")}>
        <SelItem
          label={t("settings.general.sortItems")}
          icon={
            <SettingIcon variant="blue">
              {sortDirection === "desc" ? <ClockArrowDown /> : <ClockArrowUp />}
            </SettingIcon>
          }
          settingName="sortDirection"
          settingValue={sortDirection}
          options={[
            { value: "desc", label: t("settings.general.sortDesc") },
            { value: "asc", label: t("settings.general.sortAsc") },
          ]}
        />
        <Separator />
        <SelItem
          label={t("settings.general.sortField")}
          icon={
            <SettingIcon variant="blue">
              <CalendarDays />
            </SettingIcon>
          }
          settingName="sortField"
          settingValue={sortField}
          options={[
            {
              value: "published_at",
              label: t("settings.general.sortByPublishDate"),
            },
            {
              value: "created_at",
              label: t("settings.general.sortByCreateDate"),
            },
          ]}
        />
        <Separator />
        <SwitchItem
          label={t("settings.general.showUnreadByDefault")}
          description={t("settings.general.showUnreadByDefaultDescription")}
          icon={
            <SettingIcon variant="amber">
              <CircleDot className="p-1 fill-current" />
            </SettingIcon>
          }
          settingName="showUnreadByDefault"
          settingValue={showUnreadByDefault}
        />
        <Separator />
        <SwitchItem
          label={t("settings.general.markAsReadOnScroll")}
          icon={
            <SettingIcon variant="red">
              <CircleCheck />
            </SettingIcon>
          }
          settingName="markAsReadOnScroll"
          settingValue={markAsReadOnScroll}
        />
      </ItemWrapper>
    </>
  );
}
