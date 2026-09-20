import { useEffect, useLayoutEffect, useRef } from "react";
import { imageGalleryActive } from "@/stores/articlesStore.js";
import { isModalOpen } from "@/stores/modalStore.js";
import { attachSwipeGesture } from "./swipeGesture.js";

export function useSwipeGesture({ onSwipeRight, threshold = 50 }) {
  const gestureRef = useRef(null);
  const optionsRef = useRef({ onSwipeRight, threshold });

  useLayoutEffect(() => {
    optionsRef.current = { onSwipeRight, threshold };
  }, [onSwipeRight, threshold]);

  useEffect(() => attachSwipeGesture(document, {
    gestureRef,
    getOptions: () => optionsRef.current,
    isBlocked: () => isModalOpen.get() || imageGalleryActive.get(),
  }), []);
}
