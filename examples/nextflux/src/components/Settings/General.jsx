import { continuousSync, startContinuousSync, stopContinuousSync } from "@/toolbox/background.js";
import { toast } from "sonner";
import { useState } from "react";
import { settingsState } from "@/stores/settingsStore";
import {
  CircleCheck,
  CircleDot,
  ClockArrowDown,
  ClockArrowUp,
  CopyMinus,
  FolderOpen,
  RefreshCw,
  CalendarDays,
  ScanSearch,
} from "lucide-react";
import { useStore } from "@nanostores/react";
import {
  ItemWrapper,
  SelItem,
  SwitchItem,
} from "@/components/ui/settingItem.jsx";
import { Button } from "@heroui/react";
import Language from "@/components/Settings/components/Language.jsx";
import { useTranslation } from "react-i18next";
import SettingIcon from "@/components/ui/SettingIcon";

export default function General() {
  const activeBackground = useStore(continuousSync);
  const [changingBackground, setChangingBackground] = useState(false);
  const {
    sortDirection,
    sortField,
    markAsReadOnScroll,
    syncInterval,
    defaultExpandCategory,
    showUnreadByDefault,
    hideDuplicateArticles,
    duplicateMatch,
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
      <div className="nf-set-group">
        <div className="ds-group-card">
          <Button
            variant="ghost"
            className="nf-set-row is-pressable nf-set-action"
            data-tone={activeBackground ? "danger" : "accent"}
            isPending={changingBackground}
            onPress={async () => {
              setChangingBackground(true);
              try { if (activeBackground) await stopContinuousSync(); else await startContinuousSync(); }
              catch (error) { toast.error(error.message || "后台同步操作失败，请重试。"); }
              finally { setChangingBackground(false); }
            }}
          >{activeBackground ? "停止后台同步" : "离开页面后继续同步"}</Button>
        </div>
        <p className="nf-set-footnote">后台同步使用持续通知，可随时停止。开启时会立即同步一次；同步间隔设为“手动”时，后台固定每 15 分钟同步。</p>
      </div>
      <ItemWrapper title={t("settings.general.feeds")}>
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
        <SwitchItem
          label={t("settings.general.hideDuplicateArticles")}
          description={t(`settings.general.hideDuplicateArticlesDescription${{ title: "Title", url: "Url" }[duplicateMatch] ?? ""}`)}
          icon={
            <SettingIcon variant="blue">
              <CopyMinus />
            </SettingIcon>
          }
          settingName="hideDuplicateArticles"
          settingValue={hideDuplicateArticles}
        />
        {hideDuplicateArticles !== false && (
          <SelItem
            label={t("settings.general.duplicateMatch")}
            icon={
              <SettingIcon variant="blue">
                <ScanSearch />
              </SettingIcon>
            }
            settingName="duplicateMatch"
            settingValue={duplicateMatch || "either"}
            options={[
              { value: "title", label: t("settings.general.duplicateMatchTitle") },
              { value: "url", label: t("settings.general.duplicateMatchUrl") },
              { value: "either", label: t("settings.general.duplicateMatchEither") },
            ]}
          />
        )}
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
