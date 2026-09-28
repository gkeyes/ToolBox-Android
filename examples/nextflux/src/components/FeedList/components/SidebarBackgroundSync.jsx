import { useState } from "react";
import { useStore } from "@nanostores/react";
import { Switch } from "@heroui/react";
import { RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";
import { settingsState } from "@/stores/settingsStore.js";
import {
  backgroundSyncHealth,
  continuousSync,
  startContinuousSync,
  stopContinuousSync,
} from "@/toolbox/background.js";
import { backgroundIntervalMs } from "@/toolbox/background-policy.mjs";

export default function SidebarBackgroundSync() {
  const { t } = useTranslation();
  const active = useStore(continuousSync);
  const health = useStore(backgroundSyncHealth);
  const { syncInterval } = useStore(settingsState);
  const [changing, setChanging] = useState(false);
  const minutes = Math.round(backgroundIntervalMs(syncInterval) / 60000);

  const status = changing
    ? t(active ? "sidebar.backgroundSync.stopping" : "sidebar.backgroundSync.starting")
    : !active
      ? t("sidebar.backgroundSync.off")
      : health.state === "syncing"
        ? t("sidebar.backgroundSync.syncing")
        : health.state === "error"
          ? t("sidebar.backgroundSync.failed")
          : health.state === "deferred"
            ? t("sidebar.backgroundSync.deferred")
            : t("sidebar.backgroundSync.running", { minutes });

  const change = async (selected) => {
    if (changing || selected === active) return;
    setChanging(true);
    try {
      if (selected) await startContinuousSync();
      else await stopContinuousSync();
    } catch (error) {
      toast.error(error?.message || t("sidebar.backgroundSync.failed"));
    } finally {
      setChanging(false);
    }
  };

  return (
    <div className="px-1">
      <div className="flex min-h-12 items-center gap-2 rounded-xl bg-default/45 px-3 py-2">
        <RefreshCw className={`size-4 shrink-0 text-muted ${health.state === "syncing" ? "animate-spin" : ""}`} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium">{t("sidebar.backgroundSync.title")}</div>
          <div className="truncate text-xs text-muted opacity-70">{status}</div>
        </div>
        <Switch
          aria-label={t("sidebar.backgroundSync.title")}
          size="sm"
          isSelected={active}
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
