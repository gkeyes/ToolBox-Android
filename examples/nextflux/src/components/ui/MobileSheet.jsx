import { useRef } from "react";
import { Button, Drawer, cn } from "@heroui/react";

const SHEET_EASING = "cubic-bezier(0.32, 0.72, 0, 1)";

function animateTranslate(element, from, to, duration, onDone) {
  if (!element?.animate || window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches) {
    if (element) element.style.translate = "";
    onDone?.();
    return;
  }
  const animation = element.animate(
    [{ translate: `0 ${from}px` }, { translate: `0 ${to}px` }],
    { duration, easing: SHEET_EASING, fill: "both" },
  );
  animation.finished.then(() => {
    animation.cancel();
    element.style.translate = "";
    onDone?.();
  }).catch(() => {});
}

export default function MobileSheet({
  open,
  onOpenChange,
  title,
  closeLabel = "Close",
  fixedHeight = false,
  dialogClassName,
  bodyClassName,
  headerStart,
  children,
  footer,
}) {
  const dialogRef = useRef(null);
  const dragRef = useRef(null);

  const onPointerDown = (event) => {
    if (event.button !== 0) return;
    const dialog = dialogRef.current;
    if (!dialog) return;
    dragRef.current = {
      pointerId: event.pointerId,
      startY: event.clientY,
      lastY: event.clientY,
      lastTime: event.timeStamp,
      velocity: 0,
      distance: 0,
    };
    event.currentTarget.setPointerCapture?.(event.pointerId);
    dialog.style.willChange = "translate";
  };

  const onPointerMove = (event) => {
    const drag = dragRef.current;
    const dialog = dialogRef.current;
    if (!drag || drag.pointerId !== event.pointerId || !dialog) return;
    const distance = Math.max(0, event.clientY - drag.startY);
    const dt = Math.max(1, event.timeStamp - drag.lastTime);
    drag.velocity = ((event.clientY - drag.lastY) / dt) * 1000;
    drag.lastY = event.clientY;
    drag.lastTime = event.timeStamp;
    drag.distance = distance;
    dialog.style.translate = `0 ${distance}px`;
    event.preventDefault();
  };

  const finishDrag = (event, cancelled = false) => {
    const drag = dragRef.current;
    const dialog = dialogRef.current;
    if (!drag || drag.pointerId !== event.pointerId || !dialog) return;
    dragRef.current = null;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    const threshold = Math.min(140, dialog.getBoundingClientRect().height * 0.22);
    const dismiss = !cancelled && (drag.distance >= threshold || drag.velocity >= 720);
    const from = drag.distance;
    if (dismiss) {
      animateTranslate(dialog, from, dialog.getBoundingClientRect().height + 32, 180, () => {
        dialog.style.willChange = "";
        onOpenChange(false);
      });
    } else {
      animateTranslate(dialog, from, 0, 220, () => { dialog.style.willChange = ""; });
    }
  };

  return (
    <Drawer>
      <Button className="hidden" />
      <Drawer.Backdrop className="nextflux-modal-backdrop" isOpen={open} onOpenChange={onOpenChange}>
        <Drawer.Content>
          <Drawer.Dialog
            ref={dialogRef}
            className={cn(
              "nextflux-modal-surface nextflux-mobile-sheet p-0",
              fixedHeight && "h-4/5",
              dialogClassName,
            )}
          >
            <div
              className="nextflux-sheet-handle-zone"
              onPointerDown={onPointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={(event) => finishDrag(event)}
              onPointerCancel={(event) => finishDrag(event, true)}
            >
              <Drawer.Handle className="p-1" />
            </div>
            <Drawer.CloseTrigger className="nextflux-close-button" aria-label={closeLabel} />
            <Drawer.Header className="nextflux-close-header px-4 pt-1 pb-4 flex flex-row items-center gap-2">
              {headerStart}
              <Drawer.Heading>{title}</Drawer.Heading>
            </Drawer.Header>
            <Drawer.Body className={cn("nextflux-modal-body m-0 p-0", bodyClassName)}>{children}</Drawer.Body>
            {footer && <Drawer.Footer className="nextflux-modal-footer p-4 m-0">{footer}</Drawer.Footer>}
          </Drawer.Dialog>
        </Drawer.Content>
      </Drawer.Backdrop>
    </Drawer>
  );
}
