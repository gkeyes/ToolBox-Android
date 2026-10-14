import { useEffect, useLayoutEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { cn } from "@/lib/utils";

// Attributes app code uses to find rows or press targets. The copy keeps
// presentation attributes (data-read, data-active, ...) so it looks the same.
const LOOKUP_ATTRIBUTES = ["data-article-id", "data-react-aria-pressable", "data-nextflux-press", "data-shared-article-title", "data-reading-local-anchor"];

// Copies the pressed row for the lifted preview without ids or lookup hooks,
// so app code that looks rows up by attribute never finds the copy.
function liftedCopy(anchor) {
  const copy = anchor.cloneNode(true);
  for (const node of [copy, ...copy.querySelectorAll("*")]) {
    for (const { name } of [...node.attributes]) {
      if (name === "id" || name.startsWith("aria-") || LOOKUP_ATTRIBUTES.includes(name)) node.removeAttribute(name);
    }
  }
  return copy;
}

// position.anchor (the pressed row) gives the iOS presentation: the page
// blurs, the row lifts, and the menu sits under it. Without one the menu opens
// at the pointer.
const ContextMenu = ({ isOpen, onClose, position, children }) => {
  const menuRef = useRef(null);
  const liftRef = useRef(null);
  const anchor = position?.anchor?.isConnected ? position.anchor : null;

  useLayoutEffect(() => {
    const lift = liftRef.current;
    if (!isOpen || !anchor || !lift) return;
    const rect = anchor.getBoundingClientRect();
    Object.assign(lift.style, { left: `${rect.left}px`, top: `${rect.top}px`, width: `${rect.width}px`, height: `${rect.height}px` });
    lift.replaceChildren(liftedCopy(anchor));
    return () => lift.replaceChildren();
  }, [isOpen, anchor]);

  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        onClose();
      }
    };

    const handleEscape = (event) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    const handleScroll = () => {
      onClose();
    };

    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("keydown", handleEscape);
    document.addEventListener("scroll", handleScroll, true);

    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("keydown", handleEscape);
      document.removeEventListener("scroll", handleScroll, true);
    };
  }, [isOpen, onClose]);

  useEffect(() => {
    if (isOpen && menuRef.current && position) {
      const menu = menuRef.current;
      // Layout size: the entrance animation scales the box, which
      // getBoundingClientRect() would report as smaller than the menu.
      const width = menu.offsetWidth;
      const height = menu.offsetHeight;
      const viewportWidth = window.innerWidth;
      const viewportHeight = window.innerHeight;

      let { x, y } = position;
      if (anchor) {
        // Under the row; above it when there is no room below.
        const rect = anchor.getBoundingClientRect();
        x = rect.left + 8;
        y = rect.bottom + 8;
        if (y + height > viewportHeight - 10 && rect.top - height - 8 >= 10) y = rect.top - height - 8;
      }

      // Keep a 10px margin from the right and bottom edges
      if (x + width > viewportWidth - 10) {
        x = viewportWidth - width - 10;
      }
      if (y + height > viewportHeight - 10) {
        y = viewportHeight - height - 10;
      }

      // Ensure menu doesn't go off the left or top edge
      x = Math.max(10, x);
      y = Math.max(10, y);

      menu.style.left = `${x}px`;
      menu.style.top = `${y}px`;
    }
  }, [isOpen, position, anchor]);

  if (!isOpen) return null;

  return createPortal(
    <>
    {anchor && <div className="nextflux-context-scrim" aria-hidden="true" onClick={onClose} />}
    {anchor && <div ref={liftRef} className="nextflux-context-lift" aria-hidden="true" inert />}
    <div
      ref={menuRef}
      className={cn(
        "nextflux-context-menu fixed z-[9999] min-w-[220px]",
        "p-1.5",
      )}
      style={{
        left: position?.x || 0,
        top: position?.y || 0,
      }}
    >
      {children}
    </div>
    </>,
    document.body,
  );
};

const ContextMenuItem = ({ onClick, children, className, startContent }) => {
  const handleClick = (e) => {
    e.preventDefault();
    e.stopPropagation();
    onClick?.();
  };

  return (
    <div
      onClick={handleClick}
      onTouchEnd={handleClick}
      className={cn(
        "ds-state ds-press-row min-h-12 px-3 cursor-pointer rounded-[var(--ds-radius-small)] text-[length:var(--ds-text-body)]",
        "select-none",
        "flex gap-3 items-center",
        className,
      )}
    >
      <span className="min-w-0 flex-1">{children}</span>
      {startContent}
    </div>
  );
};

export { ContextMenu, ContextMenuItem };
