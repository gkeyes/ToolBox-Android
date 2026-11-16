import { continuousSync, startContinuousSync, stopContinuousSync } from "@/toolbox/background.js";
import { toast } from "sonner";
import { useRef, useState } from "react";
import { settingsState, updateSettings } from "@/stores/settingsStore";
import { forceSync, isOnline, isSyncing, syncProgress } from "@/stores/syncStore.js";
import { flushPreferences } from "@/toolbox/preferences.js";
import { RECENT_CACHE_LIMITS, normalizeRecentCacheLimit } from "@/toolbox/recent-cache.mjs";
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
  HardDriveDownload,
} from "lucide-react";
import { useStore } from "@nanostores/react";
import {
  ItemWrapper,
  MenuRow,
  SelItem,
  SwitchItem,
} from "@/components/ui/settingItem.jsx";
import { Button, Dropdown, Label } from "@heroui/react";
import Language from "@/components/Settings/components/Language.jsx";
import { useTranslation } from "react-i18next";
import SettingIcon from "@/components/ui/SettingIcon";

function OfflineCacheSettings() {
  const { recentCacheLimit } = useStore(settingsState);
  const online = useStore(isOnline);
  const syncing = useStore(isSyncing);
  const progress = useStore(syncProgress);
  const operation = useRef(null);
  const [pending, setPending] = useState(null);
  const limit = normalizeRecentCacheLimit(recentCacheLimit);
  const options = [
    ...RECENT_CACHE_LIMITS.map((value) => ({ value: String(value), label: `${value} 条` })),
    { value: "0", label: "关闭额外缓存" },
  ];

  async function chooseLimit(values) {
    const value = String(values.currentKey ?? [...values][0]);
    if (operation.current || isSyncing.get() || value === String(limit) || !options.some((option) => option.value === value)) return;
    const previous = String(limit);
    operation.current = "saving";
    setPending("saving");
    try {
      await updateSettings({ recentCacheLimit: value });
    } catch {
      // Keep the visible selection tied to the last confirmed preference.
      await updateSettings({ recentCacheLimit: previous }).catch(() => {});
      toast.error("缓存设置保存失败，请重试。");
    } finally {
      operation.current = null;
      setPending(null);
    }
  }

  async function cacheNow() {
    if (operation.current || isSyncing.get() || !isOnline.get() || limit === 0) return;
    operation.current = "caching";
    setPending("caching");
    try {
      try { await flushPreferences(); }
      catch { toast.error("缓存设置尚未保存，请重新选择数量后重试。"); return; }
      const result = await forceSync();
      if (result?.outcome === "committed") toast.success("最近文章缓存已更新");
    } catch {
      // The sync action reports its own error; avoid a second toast for one failure.
    } finally {
      operation.current = null;
      setPending(null);
    }
  }

  return (
    <ItemWrapper
      title="离线缓存"
      footer="按服务器收录顺序保留最近文章的正文；部分订阅只提供摘要。未读和收藏额外保留，总条数可能超过所选数量。旧的已读非收藏缓存会滚动清理，服务器文章不受影响。图片和音视频仍需联网。调整数量后下次同步生效，也可立即缓存；关闭后沿用原有同步方式。"
    >
      <MenuRow
        label="最近文章"
        icon={<SettingIcon variant="blue"><HardDriveDownload /></SettingIcon>}
        description={syncing ? "同步完成后可调整数量" : "每次同步自动更新"}
        value={options.find((option) => option.value === String(limit)).label}
        ariaLabel="最近文章缓存数量"
        selectedKeys={new Set([String(limit)])}
        disallowEmptySelection
        onSelectionChange={chooseLimit}
      >
        {options.map((option) => (
          <Dropdown.Item id={option.value} key={option.value} textValue={option.label} isDisabled={Boolean(pending) || syncing}>
            <Dropdown.ItemIndicator />
            <Label>{option.label}</Label>
          </Dropdown.Item>
        ))}
      </MenuRow>
      <Button
        variant="ghost"
        className="nf-set-row is-pressable nf-set-action"
        data-tone="accent"
        isDisabled={Boolean(pending) || syncing || !online || limit === 0}
        isPending={pending === "caching"}
        onPress={cacheNow}
      >立即缓存</Button>
      {(pending === "saving" || syncing || !online) && (
        <p className="nf-set-footnote" role="status" aria-live="polite">
          {pending === "saving" ? "正在保存缓存设置…" : syncing ? progress || "正在缓存文章…" : "当前离线，联网后可更新；已缓存的正文仍可阅读。"}
        </p>
      )}
    </ItemWrapper>
  );
}

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
      <OfflineCacheSettings />
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
