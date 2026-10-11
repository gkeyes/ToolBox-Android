import { ArrowUp } from "lucide-react";
import { visibleRange } from "@/stores/articlesStore.js";
import { useStore } from "@nanostores/react";
import { AnimatePresence, motion } from "framer-motion";
import { settingsState } from "@/stores/settingsStore.js";
import { exitTween, spring, useReducedMotionPreference } from "@/design/motion.js";

export default function Indicator({ virtuosoRef }) {
  const $visibleRange = useStore(visibleRange);
  const { reduceMotion } = useStore(settingsState);
  const reduced = useReducedMotionPreference(reduceMotion);

  const handleClick = () => {
    virtuosoRef.current?.scrollToIndex({
      index: 0,
      align: "start",
      behavior: reduced ? "auto" : "smooth",
    });
  };

  return (
    <AnimatePresence initial={false} mode="wait">
      {$visibleRange.startIndex !== 0 && (
        <motion.div
          initial={reduced ? false : { opacity: 0, scale: 0.8 }}
          animate={{ opacity: 1, scale: 1, transition: reduced ? { duration: 0 } : spring.spatial }}
          exit={{ opacity: 0, scale: 0.8, transition: reduced ? { duration: 0 } : exitTween }}
          whileTap={reduced ? undefined : { scale: 0.965, transition: spring.pressIn }}
          onClick={handleClick}
          className="indicator article-list-indicator absolute cursor-pointer select-none right-4 mt-16 standalone:mt-safe-offset-16 rounded-full z-10 min-h-8 py-1.5 px-3 flex gap-1 items-center ds-tnum text-xs font-semibold"
        >
          {$visibleRange.startIndex}
          <ArrowUp strokeWidth={3} className="size-3 opacity-70" />
        </motion.div>
      )}
    </AnimatePresence>
  );
}
