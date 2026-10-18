import { useState } from "react";
import { useStore } from "@nanostores/react";
import { Switch } from "@heroui/react";
import { Eye } from "lucide-react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";
import { settingsState, updateSettings } from "@/stores/settingsStore.js";

export default function SidebarHiddenFeeds() {
  const { t } = useTranslation();
  const { showHiddenFeeds } = useStore(settingsState);
  const [changing, setChanging] = useState(false);
  const title = t("settings.general.showHiddenFeeds");

  const change = async (selected) => {
    if (changing || selected === showHiddenFeeds) return;
    setChanging(true);
    try {
      await updateSettings({ showHiddenFeeds: selected });
    } catch (error) {
      toast.error(error?.message || "设置保存失败，请重试。");
    } finally {
      setChanging(false);
    }
  };

  return (
    <div className="sidebar-feed-visibility">
      <Eye className="sidebar-pref-icon" aria-hidden="true" />
      <span className="min-w-0 flex-1 ds-caption font-medium">{title}</span>
      <Switch
        aria-label={title}
        size="sm"
        isSelected={showHiddenFeeds}
        isDisabled={changing}
        onChange={change}
      >
        <Switch.Control className="rounded-full">
          <Switch.Thumb className="rounded-full" />
        </Switch.Control>
      </Switch>
    </div>
  );
}
