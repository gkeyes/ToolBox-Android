import { useEffect, useLayoutEffect, useRef } from "react";
import { imageGalleryActive } from "@/stores/articlesStore.js";
import { isModalOpen } from "@/stores/modalStore.js";
import { attachArticleSwipeBack } from "./articleSwipeBack.js";

export function useArticleSwipeBack({ pageRef, enabled = true, onBack, onTakeoverEntrance, reduceMotion = false, onActiveChange }) {
  const optionsRef = useRef({ enabled, onBack, onTakeoverEntrance, reduceMotion, onActiveChange });
  useLayoutEffect(() => {
    optionsRef.current = { enabled, onBack, onTakeoverEntrance, reduceMotion, onActiveChange };
  }, [enabled, onBack, onTakeoverEntrance, reduceMotion, onActiveChange]);

  useEffect(() => {
    const page = pageRef.current;
    if (!page || !enabled) return;
    return attachArticleSwipeBack(page, {
      getOptions: () => optionsRef.current,
      isBlocked: () => isModalOpen.get() || imageGalleryActive.get(),
    });
  }, [pageRef, enabled]);
}
