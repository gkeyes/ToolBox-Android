import CustomAlertDialog from "@/components/ui/CustomAlertDialog.jsx";
import { useTranslation } from "react-i18next";
import { logoutModalOpen } from "@/stores/modalStore.js";
import { useStore } from "@nanostores/react";
import { logout } from "@/stores/authStore.js";
import { toast } from "sonner";

export default function LogoutModal() {
  const { t } = useTranslation();
  const $logoutModalOpen = useStore(logoutModalOpen);
  const handleLogout = async () => {
    try {
      await logout();
    } catch (error) {
      // Logging out can unmount this dialog before cleanup finishes.
      toast.error("退出登录未能完成，请检查存储权限并重试；仍在运行的后台同步可在 ToolBox 中停止。");
      throw error;
    } finally {
      // Auth changes unmount this dialog; do not reopen it on the next login.
      logoutModalOpen.set(false);
    }
  };
  return (
    <CustomAlertDialog
      title={t("sidebar.profile.logout")}
      content={t("sidebar.profile.logoutConfirmDescription")}
      isOpen={$logoutModalOpen}
      onConfirm={handleLogout}
      onClose={() => logoutModalOpen.set(false)}
      confirmText={t("sidebar.profile.logout")}
      cancelText={t("common.cancel")}
    />
  );
}
