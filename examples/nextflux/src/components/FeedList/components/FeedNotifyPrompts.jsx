import { useStore } from "@nanostores/react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";
import CustomAlertDialog from "@/components/ui/CustomAlertDialog.jsx";
import { feedNotifyPrompt, setFeedNotification } from "@/toolbox/feed-notifications.js";
import { startContinuousSync } from "@/toolbox/background.js";

// The two dialogs of turning on a feed's update notifications: background sync is off, or
// NextFlux may not post notifications.
export default function FeedNotifyPrompts() {
  const { t } = useTranslation();
  const prompt = useStore(feedNotifyPrompt);
  const close = () => feedNotifyPrompt.set(null);

  const enableWithSync = async () => {
    const feed = prompt?.feed;
    if (!feed) return;
    await startContinuousSync();
    let result;
    try {
      result = await setFeedNotification(feed.id, true);
    } catch (error) {
      if (error?.code === "PERMISSION_DENIED" || error?.code === "SYSTEM_PERMISSION_DENIED") {
        // Background sync stays on; the permission dialog replaces this one.
        queueMicrotask(() => feedNotifyPrompt.set({ kind: "permission" }));
        return;
      }
      throw error;
    }
    if (result === "blocked") queueMicrotask(() => feedNotifyPrompt.set({ kind: "permission" }));
    else toast.success(t("feedNotify.on", { title: feed.title }));
  };

  return (
    <>
      <CustomAlertDialog
        title={t("feedNotify.needsSyncTitle")}
        content={t("feedNotify.needsSync")}
        isOpen={prompt?.kind === "needsSync"}
        onConfirm={enableWithSync}
        onClose={close}
        status="accent"
        confirmVariant="primary"
        cancelText={t("common.cancel")}
        confirmText={t("feedNotify.enableSync")}
      />
      <CustomAlertDialog
        title={t("feedNotify.permissionTitle")}
        content={t("feedNotify.permission")}
        isOpen={prompt?.kind === "permission"}
        onClose={close}
        status="warning"
        confirmVariant="primary"
        confirmText={t("feedNotify.gotIt")}
      />
    </>
  );
}
