import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useShallow } from "zustand/react/shallow";
import { motion } from "framer-motion";
import { RefreshCw } from "lucide-react";
import { Button, ModelSetupButton, Spinner } from "@/components/ui";
import { useApp, useLang } from "@/store/useApp";
import { t } from "@/lib/i18n";
import { isModelNotConfigured, pattern as patternApi } from "@/lib/client-api";
import { isReady, useByok } from "@/lib/byok";
import { skillById } from "@/data/taxonomy";
import type { PatternSession } from "@/lib/tasks/types";
import type { Session } from "@/lib/types";

/** Three is the fewest that can show a habit rather than a coincidence. */
const MIN_SESSIONS = 3;

function toInput(s: Session): PatternSession {
  const trail = s.stanceTrail ?? [];
  const gaveGroundOn: number[] = [];
  trail.forEach((v, i) => {
    if (v < (i === 0 ? 20 : trail[i - 1])) gaveGroundOn.push(i + 1);
  });
  return {
    title: s.scenario.title.zh || s.scenario.title.en,
    at: s.endedAt ?? s.startedAt,
    outcome: s.outcome,
    verdict: s.report?.verdict,
    gaveGroundOn,
    turns: s.messages.filter((m) => m.role === "learner").length,
    weaknesses: (s.report?.weaknesses ?? []).map((w) => ({
      behavior: w.behavior,
      evidence: w.evidence,
      skill: w.skill,
      deficit: w.deficit,
    })),
  };
}

/**
 * The one thing you keep doing.
 *
 * The product proposal names this the engine that converts someone with a
 * conversation tomorrow into someone training a skill, on the grounds that an
 * acute problem becomes a chronic one the moment you see it is a habit. It was
 * the one thing in that document with no implementation: every report was
 * per-session, and Growth showed totals.
 *
 * It runs on the smart model over the whole history, so it never starts on its
 * own: it is cached against the session ids it read, and a new reading is an
 * explicit tap.
 */
export function PatternCard() {
  const lang = useLang();
  const { sessions, profile, patternInsight, setPatternInsight } = useApp(useShallow((s) => ({ sessions: s.sessions, profile: s.profile, patternInsight: s.patternInsight, setPatternInsight: s.setPatternInsight })));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<{ text: string; needsModel: boolean } | null>(null);
  const inflight = useRef(false);
  // Once a model is configured, drop the "needs a model" prompt so the run button comes back.
  const modelReady = useByok((s) => isReady(s));
  useEffect(() => {
    if (modelReady) setErr((current) => (current?.needsModel ? null : current));
  }, [modelReady]);

  const done = useMemo(() => sessions.filter((s) => s.status === "assessed" && s.report), [sessions]);
  const ids = done.map((s) => s.id);
  const cached = patternInsight;
  const stale = !cached || ids.some((id) => !cached.from.includes(id));
  const enough = done.length >= MIN_SESSIONS;

  const run = useCallback(async () => {
    if (inflight.current || !profile) return;
    inflight.current = true;
    setBusy(true);
    setErr(null);
    try {
      const res = await patternApi({ lang, goals: profile.goals, sessions: done.map(toInput) });
      setPatternInsight(res, done.map((s) => s.id));
    } catch (e) {
      setErr({ text: isModelNotConfigured(e) ? t(lang, "model_required") : e instanceof Error ? e.message : t(lang, "error_generic"), needsModel: isModelNotConfigured(e) });
    } finally {
      inflight.current = false;
      setBusy(false);
    }
  }, [lang, profile, done, setPatternInsight]);

  if (!enough) {
    return (
      <div className="card p-5 flex flex-col gap-2">
        <p className="eyebrow text-accent-deep">{t(lang, "pg_pattern_title")}</p>
        <p className="text-[14px] text-ink-3 leading-relaxed">
          {t(lang, "pg_pattern_locked", { n: MIN_SESSIONS - done.length })}
        </p>
      </div>
    );
  }

  const r = cached?.result;

  return (
    <div className="bg-slab text-slab-ink rounded-[var(--radius)] p-5 flex flex-col gap-3">
      <div className="flex items-start justify-between gap-3">
        <p className="eyebrow text-slab-ink/60">{t(lang, "pg_pattern_title")}</p>
        {cached && !busy && (
          <button
            type="button"
            onClick={() => void run()}
            disabled={busy}
            aria-label={t(lang, "pg_pattern_again")}
            className="press shrink-0 text-slab-ink/60 hover:text-slab-ink disabled:opacity-40"
          >
            <RefreshCw size={15} className={busy ? "animate-spin" : undefined} />
          </button>
        )}
      </div>

      {err ? (
        <div className="flex flex-col gap-3 items-start">
          <p className="text-[14px] leading-relaxed">{err.text}</p>
          {err.needsModel ? <ModelSetupButton /> : <Button variant="secondary" size="sm" onClick={() => { setErr(null); void run(); }}>{t(lang, "retry")}</Button>}
        </div>
      ) : busy ? (
        <p className="inline-flex items-center gap-2 text-[14px] text-slab-ink/70"><Spinner />{t(lang, "pg_pattern_reading")}</p>
      ) : !cached ? (
        <div className="flex flex-col gap-3 items-start">
          <p className="text-[14px] leading-relaxed text-slab-ink/80">{t(lang, "pg_pattern_offer", { n: done.length })}</p>
          <Button variant="secondary" size="sm" onClick={() => void run()}>{t(lang, "pg_pattern_run")}</Button>
        </div>
      ) : !r?.found ? (
        <p className="text-[14px] leading-relaxed text-slab-ink/80">{t(lang, "pg_pattern_none")}</p>
      ) : (
        <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }} className="flex flex-col gap-4">
          {stale && <p className="text-[12px] text-slab-ink/60">{t(lang, "pg_pattern_stale")}</p>}
          <p className="display text-[21px] leading-snug lg:text-[24px]">{r.pattern}</p>
          {r.why && <p className="text-[14px] leading-relaxed text-slab-ink/80 lg:max-w-[var(--measure)]">{r.why}</p>}

          {/* The quotes are the whole claim to legitimacy: this reads harder than
              any single debrief, so it shows its two receipts rather than asserting. */}
          <ul className="flex flex-col gap-2.5 border-l border-slab-ink/25 pl-3.5">
            {r.evidence.map((e, i) => (
              <li key={i} className="flex flex-col gap-0.5">
                <span className="text-[11px] text-slab-ink/55">{e.title}</span>
                <span className="text-[13.5px] leading-relaxed text-slab-ink/90">“{e.quote}”</span>
              </li>
            ))}
          </ul>

          <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 pt-1">
            {r.skill && <span className="text-[12px] text-slab-ink/60">{skillById(r.skill).name[lang]}</span>}
            {r.nextStep && <span className="text-[13.5px] font-semibold">{r.nextStep}</span>}
          </div>
        </motion.div>
      )}
    </div>
  );
}
