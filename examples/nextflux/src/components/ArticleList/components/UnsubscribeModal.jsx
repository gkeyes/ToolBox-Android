import { removeCachedFeed } from "@/stores/syncStore.js";
import { unsubscribeModalOpen, currentFeedId } from "@/stores/modalStore.js";
import { useStore } from "@nanostores/react";
import { useNavigate, useParams } from "react-router-dom";
import { feeds } from "@/stores/feedsStore";
import CustomAlertDialog from "@/components/ui/CustomAlertDialog.jsx";
import { useTranslation } from "react-i18next";


export default function UnsubscribeModal() {
  const { t } = useTranslation();
  const $feeds = useStore(feeds);
  const { feedId: routeFeedId } = useParams();
  const $unsubscribeModalOpen = useStore(unsubscribeModalOpen);
  const $currentFeedId = useStore(currentFeedId);
  // 优先使用 store 中的 feedId，如果没有则使用路由参数中的 feedId
  const feedId = $currentFeedId || routeFeedId;
  const navigate = useNavigate();

  const feedTitle = feedId
    ? $feeds.find((f) => f.id === parseInt(feedId))?.title
    : "";

  const onClose = () => {
    unsubscribeModalOpen.set(false);
    currentFeedId.set(null); // 清除 store 中的 feedId
  };

  const handleUnsubscribe = async () => {
    if (!feedId) throw new Error("未找到订阅源，请重新选择后重试。");
    await removeCachedFeed(feedId);
    navigate("/"); // 取消订阅后返回首页；确认弹窗仅在操作成功后关闭。
  };

  return (
    <CustomAlertDialog
      title={t("articleList.unsubscribe")}
      content={`${t("articleList.unsubscribeDescription")}「${feedTitle}」`}
      isOpen={$unsubscribeModalOpen}
      onConfirm={handleUnsubscribe}
      onClose={onClose}
      confirmText={t("common.confirm")}
      cancelText={t("common.cancel")}
    />
  );
}
