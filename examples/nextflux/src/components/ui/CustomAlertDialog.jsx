import { Button, AlertDialog, Spinner } from "@heroui/react";
import { useEffect, useRef, useState } from "react";

export default function CustomAlertDialog({
  title,
  content,
  isOpen,
  onConfirm,
  onClose,
  cancelText = "取消",
  confirmText = "确定",
}) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const pending = useRef(false);

  useEffect(() => {
    if (isOpen) setError("");
  }, [isOpen]);

  const handleClose = () => {
    if (!pending.current) onClose();
  };

  const handleConfirm = async () => {
    if (pending.current) return;
    pending.current = true;
    setError("");
    try {
      setLoading(true);
      await onConfirm();
      onClose();
    } catch (failure) {
      setError(failure?.code === "ACCOUNT_CHANGED"
        ? "当前账号已变化，请重新打开页面后重试。"
        : failure?.message || "操作未完成，请重试。");
    } finally {
      pending.current = false;
      setLoading(false);
    }
  };

  return (
    <AlertDialog>
      <Button className="hidden" />
      <AlertDialog.Backdrop
        className="nextflux-modal-backdrop"
        isOpen={isOpen}
        onOpenChange={(open) => !open && handleClose()}
      >
        <AlertDialog.Container>
          <AlertDialog.Dialog className="nextflux-modal-surface sm:max-w-[400px] p-0" aria-busy={loading}>
            <AlertDialog.CloseTrigger className="nextflux-close-button" isDisabled={loading} />
            <AlertDialog.Header className="nextflux-close-header px-4 pt-4">
              <AlertDialog.Icon status="danger" />
              <AlertDialog.Heading>{title}</AlertDialog.Heading>
            </AlertDialog.Header>
            <AlertDialog.Body className="nextflux-modal-body px-4">
              <p>{content}</p>
              {error && <p className="nextflux-dialog-error" role="alert">{error}</p>}
            </AlertDialog.Body>
            <AlertDialog.Footer className="nextflux-modal-footer p-4">
              <Button variant="tertiary" onPress={handleClose} isDisabled={loading}>
                {cancelText}
              </Button>
              <Button
                variant="danger"
                onPress={handleConfirm}
                isPending={loading}
              >
                {loading && <Spinner color="current" size="sm" />}
                {confirmText}
              </Button>
            </AlertDialog.Footer>
          </AlertDialog.Dialog>
        </AlertDialog.Container>
      </AlertDialog.Backdrop>
    </AlertDialog>
  );
}
