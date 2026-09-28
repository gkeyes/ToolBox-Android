import { useEffect, useLayoutEffect, useRef } from "react";
import { imageGalleryActive } from "@/stores/articlesStore.js";
import { isModalOpen } from "@/stores/modalStore.js";
import { attachArticleSwipeBack } from "./articleSwipeBack.js";

export function useArticleSwipeBack({ pageRef, enabled = true, onBack, reduceMotion = false, onActiveChange }) {
  const optionsRef = useRef({ enabled, onBack, reduceMotion, onActiveChange });
  useLayoutEffect(() => {
    optionsRef.current = { enabled, onBack, reduceMotion, onActiveChange };
  }, [enabled, onBack, reduceMotion, onActiveChange]);

  useEffect(() => {
    const page = pageRef.current;
    if (!page || !enabled) return;
    return attachArticleSwipeBack(page, {
      getOptions: () => optionsRef.current,
      isBlocked: () => isModalOpen.get() || imageGalleryActive.get(),
    });
  }, [pageRef, enabled]);
}
