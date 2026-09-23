"use client";
import type { ReactNode } from "react";
import { ArrowLeft, Check } from "lucide-react";
import { clsx } from "clsx";
import { IconButton } from "@/components/ui";
import { useLang } from "@/store/useApp";
import { t } from "@/lib/i18n";

/** A stable orientation point across preparation, conversation and review. */
export function PracticeJourney({ phase, onBack, backLabel, actions }: {
  phase: 0 | 1 | 2;
  onBack: () => void;
  backLabel?: string;
  actions?: ReactNode;
}) {
  const lang = useLang();
  const steps = ["pr_step_prepare", "pr_step_talk", "pr_step_review"] as const;
  return (
    <div className="practice-journey flex items-center gap-2 py-2 border-b border-line">
      <IconButton label={backLabel ?? t(lang, "pr_back_workspace")} onClick={onBack}><ArrowLeft size={19} /></IconButton>
      <span className="hidden lg:block display text-[15px] pr-6">SocialCoach</span>
      <div className="practice-journey-compact sm:hidden flex flex-1 min-w-0 items-center justify-center gap-2 text-[12px]" aria-label={t(lang, "pr_journey")}>
        <span aria-hidden className="h-6 w-6 shrink-0 rounded-full inline-flex items-center justify-center bg-ink text-paper text-[10px] num">{phase + 1}</span>
        <span className="min-w-0 truncate font-semibold">{t(lang, steps[phase])}</span>
        <span className="num shrink-0 text-ink-3">{phase + 1}/3</span>
      </div>
      <ol aria-label={t(lang, "pr_journey")} className="hidden sm:flex flex-1 items-center justify-center gap-4 text-[12px]">
        {steps.map((key, i) => (
          <li key={key} aria-current={phase === i ? "step" : undefined} className={clsx("flex items-center gap-1.5 whitespace-nowrap", phase === i ? "text-ink font-semibold" : "text-ink-3")}>
            {i > 0 && <span className="w-6 h-px bg-line mr-1" aria-hidden />}
            <span aria-hidden className={clsx("h-5 w-5 rounded-full inline-flex items-center justify-center text-[10px] num", phase === i ? "bg-ink text-paper" : "bg-inset text-ink-3")}>
              {i < phase ? <Check size={12} /> : i + 1}
            </span>
            {t(lang, key)}
          </li>
        ))}
      </ol>
      <div className="flex items-center justify-end min-w-11">{actions}</div>
    </div>
  );
}
