import { useStore } from "@nanostores/react";
import { Button, Spinner } from "@heroui/react";
import { forceSync, isOnline, isSyncing } from "@/stores/syncStore.js";
import {
  SyncIcon as RefreshCw,
} from "@/components/ui/icons.jsx";

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
      className="nextflux-toolbar-button"
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
        <RefreshCw className="size-5 text-muted" />
      )}
    </Button>
  );
};

export default SyncButton;
