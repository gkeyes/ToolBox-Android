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
    <div className="px-1">
      <div className="flex min-h-12 items-center gap-2 rounded-xl bg-default/45 px-3 py-2">
        <Eye className="size-4 shrink-0 text-muted" />
        <div className="min-w-0 flex-1 truncate text-sm font-medium">{title}</div>
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
    </div>
  );
}
