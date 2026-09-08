import {
  Button,
  FieldError,
  FieldGroup,
  Fieldset,
  Form,
  Input,
  Label,
  Spinner,
  TextField,
} from "@heroui/react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { renameModalOpen, currentCategoryId } from "@/stores/modalStore.js";
import { useStore } from "@nanostores/react";
import { useParams } from "react-router-dom";
import { categories } from "@/stores/feedsStore";
import { useTranslation } from "react-i18next";
import CustomModal from "@/components/ui/CustomModal.jsx";
import { renameCachedCategory } from "@/stores/syncStore.js";

export default function RenameModal() {
  const { t } = useTranslation();
  const $categories = useStore(categories);
  const { categoryId: urlCategoryId } = useParams();
  const $currentCategoryId = useStore(currentCategoryId);
  // 优先使用 currentCategoryId（来自右键菜单），否则使用 URL 中的 categoryId
  const categoryId = $currentCategoryId || urlCategoryId;
  const [newTitle, setNewTitle] = useState("");
  const [loading, setLoading] = useState(false);
  const $renameModalOpen = useStore(renameModalOpen);

  useEffect(() => {
    if (categoryId) {
      setNewTitle(
        $categories.find((c) => c.id === parseInt(categoryId))?.title || "",
      );
    }
  }, [$categories, categoryId]);

  const onClose = () => {
    renameModalOpen.set(false);
    currentCategoryId.set(null);
    if (categoryId) {
      setNewTitle(
        $categories.find((c) => c.id === parseInt(categoryId))?.title || "",
      );
    }
  };

  const handleRename = async (e) => {
    e.preventDefault();
    if (!categoryId) return;
    try {
      setLoading(true);
      await renameCachedCategory(categoryId, newTitle);
      onClose();
    } catch (error) {
      if (error.code !== "ACCOUNT_CHANGED") toast.error(error.message || "重命名失败，请刷新后重试。");
    } finally {
      setLoading(false);
      setNewTitle(""); // 重置输入框
    }
  };

  return (
    <CustomModal
      open={$renameModalOpen}
      onOpenChange={onClose}
      title={t("articleList.renameCategory.title")}
      footer={
        <>
          <Button variant="tertiary" onPress={onClose} fullWidth>
            {t("common.cancel")}
          </Button>
          <Button
            type="submit"
            form="rename-form"
            isPending={loading}
            fullWidth
          >
            {loading && <Spinner color="current" size="sm" />}
            {t("common.save")}
          </Button>
        </>
      }
    >
      <Form
        id="rename-form"
        className="w-full px-4 pb-4"
        onSubmit={handleRename}
      >
        <Fieldset>
          <FieldGroup>
            <TextField isRequired name="title" variant="secondary">
              <Label>{t("articleList.renameCategory.categoryName")}</Label>
              <Input
                placeholder={t(
                  "articleList.renameCategory.categoryNamePlaceholder",
                )}
                value={newTitle}
                onChange={(event) => setNewTitle(event.target.value)}
              />
              <FieldError>
                {t("articleList.renameCategory.categoryNameRequired")}
              </FieldError>
            </TextField>
          </FieldGroup>
        </Fieldset>
      </Form>
    </CustomModal>
  );
}
