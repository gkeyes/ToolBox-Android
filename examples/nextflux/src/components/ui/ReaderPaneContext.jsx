import { createContext, useContext, useLayoutEffect, useState } from "react";

export const ReaderPaneContext = createContext(null);

// Portaled dialogs keep a window-wide dismiss target. Measure only mounted
// reader dialogs so selecting entries and scrolling do no extra layout work.
export function useReaderOverlayRef() {
  const reader = useContext(ReaderPaneContext);
  const [container, setContainer] = useState(null);

  useLayoutEffect(() => {
    const pane = reader?.current;
    if (!pane || !container) return;
    const align = () => {
      const bounds = pane.getBoundingClientRect();
      container.style.setProperty("--nf-pane-left", Math.max(0, bounds.left) + "px");
      container.style.setProperty("--nf-pane-right", Math.max(0, document.documentElement.clientWidth - bounds.right) + "px");
    };
    align();
    container.dataset.readerOverlay = "";
    const observer = new ResizeObserver(align);
    observer.observe(pane);
    window.addEventListener("resize", align);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", align);
      delete container.dataset.readerOverlay;
    };
  }, [reader, container]);

  return reader ? setContainer : undefined;
}
