import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { cn } from "@/lib/utils";

const ContextMenu = ({ isOpen, onClose, position, children }) => {
  const menuRef = useRef(null);

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
  }, [isOpen, position]);

  if (!isOpen) return null;

  return createPortal(
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
    </div>,
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
      {startContent}
      {children}
    </div>
  );
};

export { ContextMenu, ContextMenuItem };
