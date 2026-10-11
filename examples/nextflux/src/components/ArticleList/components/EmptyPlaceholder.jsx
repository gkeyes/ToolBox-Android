import { BookOpen } from "lucide-react";
import { useTranslation } from "react-i18next";
import { cn } from "@heroui/react";
import { useStore } from "@nanostores/react";
import { settingsState } from "@/stores/settingsStore.js";

export default function EmptyPlaceholder() {
  const { t } = useTranslation();
  const { floatingSidebar } = useStore(settingsState);
  return (
    <div
      className={cn(
        "h-full w-full",
        floatingSidebar ? "" : "rounded-[var(--ds-radius-card)] bg-[var(--ds-card)]",
      )}
    >
      <div className="ds-empty h-full">
        <span className="ds-empty-icon is-accent"><BookOpen /></span>
        <p className="ds-empty-title">{t("articleList.emptyPlaceholder")}</p>
        <p className="ds-empty-text">{t("articleList.selectHint")}</p>
      </div>
    </div>
  );
}
