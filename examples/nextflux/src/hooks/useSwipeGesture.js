import { useEffect, useLayoutEffect, useRef } from "react";
import { imageGalleryActive } from "@/stores/articlesStore.js";
import { isModalOpen } from "@/stores/modalStore.js";
import { attachSwipeGesture } from "./swipeGesture.js";

export function useSwipeGesture({ onSwipeRight, threshold = 50, enabled = true, onStart, onMove, onEnd, onCancel }) {
  const gestureRef = useRef(null);
  const optionsRef = useRef({ onSwipeRight, threshold, enabled, onStart, onMove, onEnd, onCancel });

  useLayoutEffect(() => {
    optionsRef.current = { onSwipeRight, threshold, enabled, onStart, onMove, onEnd, onCancel };
  }, [onSwipeRight, threshold, enabled, onStart, onMove, onEnd, onCancel]);

  useEffect(() => attachSwipeGesture(document, {
    gestureRef,
    getOptions: () => optionsRef.current,
    isBlocked: () => isModalOpen.get() || imageGalleryActive.get(),
  }), []);
}
