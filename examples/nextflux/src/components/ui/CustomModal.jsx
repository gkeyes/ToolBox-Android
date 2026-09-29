import { useIsMobile } from "@/hooks/use-mobile.jsx";
import { Modal, cn, Button } from "@heroui/react";
import { useTranslation } from "react-i18next";
import MobileSheet from "@/components/ui/MobileSheet.jsx";

export default function CustomModal({
  open,
  onOpenChange,
  title,
  fixedHeight = false,
  children,
  footer,
}) {
  const { isMedium } = useIsMobile();
  const { t } = useTranslation();

  if (isMedium) {
    return (
      <MobileSheet
        open={open}
        onOpenChange={onOpenChange}
        title={title}
        closeLabel={t("common.close")}
        fixedHeight={fixedHeight}
        footer={footer}
      >
        {children}
      </MobileSheet>
    );
  }

  return (
    <Modal>
      <Button className="hidden" />
      <Modal.Backdrop className="nextflux-modal-backdrop" isOpen={open} onOpenChange={onOpenChange}>
        <Modal.Container>
          <Modal.Dialog className={cn("nextflux-modal-surface p-0", fixedHeight && "h-2/3")}>
            <Modal.Header className="nextflux-close-header">
              <Modal.Heading className="p-4">{title}</Modal.Heading>
            </Modal.Header>
            <Modal.Body className="nextflux-modal-body m-0 p-0">{children}</Modal.Body>
            {footer && (
              <Modal.Footer className="nextflux-modal-footer p-4 m-0">
                {footer}
              </Modal.Footer>
            )}
            <Modal.CloseTrigger className="nextflux-close-button" />
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </Modal>
  );
}
