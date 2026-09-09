import { useStore } from "@nanostores/react";
import { Button, Spinner } from "@heroui/react";
import { forceSync, isOnline, isSyncing } from "@/stores/syncStore.js";
import { RefreshCw } from "lucide-react";

const SyncButton = () => {
  const $isOnline = useStore(isOnline);
  const $isSyncing = useStore(isSyncing);

  const handleForceSync = async () => {
    try {
      await forceSync();
    } catch (err) {
      console.error("强制同步失败:", err);
    }
  };

  return (
    <Button
      className="nextflux-compact-touch-target"
      aria-label="立即同步"
      onPress={handleForceSync}
      isDisabled={$isSyncing || !$isOnline}
      isPending={$isSyncing}
      variant="ghost"
      isIconOnly
      size="sm"
    >
      {$isSyncing ? (
        <Spinner color="current" size="sm" />
      ) : (
        <RefreshCw className="size-4 text-muted" />
      )}
    </Button>
  );
};

export default SyncButton;
