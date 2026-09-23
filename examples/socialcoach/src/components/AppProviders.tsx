"use client";
import { MotionConfig } from "framer-motion";
import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useApp, useLang } from "@/store/useApp";
import { isReady, useByok } from "@/lib/byok";
import { ModelSheet } from "./ModelSheet";
import { FeedbackWidget } from "./Feedback";
import { Toaster } from "./ui";
import { trackOpen } from "@/lib/analytics/track";

export function AppProviders({ children }: { children: React.ReactNode }) {
  const hydrated = useApp((s) => s.hydrated);
  const profile = useApp((s) => s.profile);
  const settings = useApp((s) => s.settings);
  const router = useRouter();
  const path = usePathname();
  const byok = useByok();
  const lang = useLang();
  const needsModel = false;
  const forced = needsModel && !isReady(byok);

  useEffect(() => {
    if (!hydrated) return;
    if (!profile && path !== "/onboarding") router.replace("/onboarding");
    if (profile && path === "/onboarding") router.replace("/");
  }, [hydrated, profile, path, router]);

  useEffect(() => {
    // Follow the resolved language, not just an explicit choice: before
    // onboarding there is no profile, and the document would keep claiming
    // zh-CN to screen readers and translation prompts on an English browser.
    document.documentElement.lang = lang === "zh" ? "zh-CN" : "en";
  }, [lang]);

  // An explicit choice pins the palette; "system" removes the attribute so the
  // prefers-color-scheme block takes over again.
  useEffect(() => {
    const t = settings.theme ?? "system";
    if (t === "system") document.documentElement.removeAttribute("data-theme");
    else document.documentElement.setAttribute("data-theme", t);
  }, [settings.theme]);

  // Once per day, profile or not; the flag separates visitors from learners.
  useEffect(() => {
    if (hydrated) trackOpen(!!profile);
  }, [hydrated, profile]);


  return (
    <MotionConfig reducedMotion="user">
    <div className="sheet">
      {hydrated ? children : <div className="min-h-dvh" />}
      {/* One instance for the whole app. `forced` has nothing to fall back on,
          so it cannot be dismissed until it works. */}
      <ModelSheet open={hydrated && (forced || byok.sheetOpen)} onClose={byok.closeSheet} forced={forced} />
      {hydrated && <FeedbackWidget />}
      <Toaster />
    </div>
    </MotionConfig>
  );
}
