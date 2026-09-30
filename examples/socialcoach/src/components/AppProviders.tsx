"use client";
import { MotionConfig } from "framer-motion";
import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useApp, useLang } from "@/store/useApp";
import { useByok } from "@/lib/byok";
import { ModelSheet } from "./ModelSheet";
import { Toaster } from "./ui";

export function AppProviders({ children }: { children: React.ReactNode }) {
  const hydrated = useApp((s) => s.hydrated);
  const profile = useApp((s) => s.profile);
  const settings = useApp((s) => s.settings);
  const router = useRouter();
  const path = usePathname();
  const byok = useByok();
  const lang = useLang();

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

  return (
    <MotionConfig reducedMotion="user">
    <div className="sheet">
      {hydrated ? children : <div className="min-h-dvh" />}
      <ModelSheet open={hydrated && byok.sheetOpen} onClose={byok.closeSheet} />
      <Toaster />
    </div>
    </MotionConfig>
  );
}
