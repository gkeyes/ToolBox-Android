import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { useStore } from "@nanostores/react";
import { atom } from "nanostores";
import { useTranslation } from "react-i18next";
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
  currentFeedId,
  currentCategoryId,
} from "@/stores/modalStore.js";

const modals = [
  [settingsModalOpen, () => import("./Settings/Settings.jsx")],
  [addFeedModalOpen, () => import("./FeedList/components/AddFeedModal.jsx")],
  [addCategoryModalOpen, () => import("./FeedList/components/AddCategoryModal.jsx")],
  [logoutModalOpen, () => import("./FeedList/components/LogoutModal.jsx")],
  [renameModalOpen, () => import("./ArticleList/components/RenameModal.jsx"), currentCategoryId],
  [unsubscribeModalOpen, () => import("./ArticleList/components/UnsubscribeModal.jsx"), currentFeedId],
  [editFeedModalOpen, () => import("./ArticleList/components/EditFeedModal.jsx"), currentFeedId],
  [searchDialogOpen, () => import("./Search/SearchModal.jsx")],
];

const noTarget = atom(null);

function LoadingModal({ onCancel }) {
  const ref = useRef(null);
  const titleId = useId();
  const { t } = useTranslation();
  useLayoutEffect(() => {
    const dialog = ref.current;
    dialog.showModal();
    return () => { dialog.close(); };
  }, []);
  return (
    <dialog
      ref={ref}
      className="nextflux-modal-surface nextflux-modal-loading"
      aria-labelledby={titleId}
      onCancel={(event) => { event.preventDefault(); onCancel(); }}
    >
      <p id={titleId} role="status">{t("common.loading")}</p>
      <button type="button" onClick={onCancel}>{t("common.cancel")}</button>
    </dialog>
  );
}

function DeferredModal({ open, load, target = noTarget }) {
  const isOpen = useStore(open);
  const targetId = useStore(target);
  const [Component, setComponent] = useState(null);

  const cancel = () => {
    if (target.get() !== targetId) return;
    open.set(false);
    target.set(null);
  };

  useEffect(() => {
    if (!isOpen || Component) return;
    let active = true;
    let loaded = false;
    load().then(
      ({ default: Modal }) => {
        if (!active || !open.get() || target.get() !== targetId) return;
        loaded = true;
        setComponent(() => Modal);
      },
      () => {
        if (!active) return;
        cancel();
        toast.error("界面加载失败，请重新打开 NextFlux 后重试。");
      },
    );
    return () => {
      active = false;
      if (!loaded) cancel();
    };
  }, [isOpen, Component, open, load, target, targetId]);

  // Preserve drafts and closing animations after the first successful load.
  if (Component) return <Component />;
  return isOpen ? <LoadingModal onCancel={cancel} /> : null;
}

export default function AppModals() {
  return modals.map(([open, load, target], index) => (
    <DeferredModal key={index} open={open} load={load} target={target} />
  ));
}
