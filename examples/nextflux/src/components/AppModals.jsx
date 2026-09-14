import { useEffect, useState } from "react";
import { useStore } from "@nanostores/react";
import { toast } from "sonner";
import {
  settingsModalOpen,
  addFeedModalOpen,
  addCategoryModalOpen,
  logoutModalOpen,
  renameModalOpen,
  unsubscribeModalOpen,
  editFeedModalOpen,
  searchDialogOpen,
} from "@/stores/modalStore.js";

const modals = [
  [settingsModalOpen, () => import("./Settings/Settings.jsx")],
  [addFeedModalOpen, () => import("./FeedList/components/AddFeedModal.jsx")],
  [addCategoryModalOpen, () => import("./FeedList/components/AddCategoryModal.jsx")],
  [logoutModalOpen, () => import("./FeedList/components/LogoutModal.jsx")],
  [renameModalOpen, () => import("./ArticleList/components/RenameModal.jsx")],
  [unsubscribeModalOpen, () => import("./ArticleList/components/UnsubscribeModal.jsx")],
  [editFeedModalOpen, () => import("./ArticleList/components/EditFeedModal.jsx")],
  [searchDialogOpen, () => import("./Search/SearchModal.jsx")],
];

function DeferredModal({ open, load }) {
  const isOpen = useStore(open);
  const [Component, setComponent] = useState(null);

  useEffect(() => {
    if (!isOpen || Component) return;
    let active = true;
    load().then(
      ({ default: Modal }) => {
        if (active) setComponent(() => Modal);
      },
      () => {
        if (!active) return;
        open.set(false);
        toast.error("界面加载失败，请重新打开 NextFlux 后重试。");
      },
    );
    return () => { active = false; };
  }, [isOpen, Component, open, load]);

  // Preserve drafts and closing animations after the first successful load.
  return Component ? <Component /> : null;
}

export default function AppModals() {
  return modals.map(([open, load], index) => (
    <DeferredModal key={index} open={open} load={load} />
  ));
}
