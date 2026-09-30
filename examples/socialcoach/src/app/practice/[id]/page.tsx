"use client";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useApp } from "@/store/useApp";
import { Briefing } from "@/components/practice/Briefing";
import { Chat, endingRevealUntil } from "@/components/practice/Chat";
import { Debrief } from "@/components/practice/Debrief";

export default function PracticePage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const session = useApp((s) => s.sessions.find((x) => x.id === id));
  const hydrated = useApp((s) => s.hydrated);
  const [revealTick, finishReveal] = useState(0);

  useEffect(() => {
    if (hydrated && !session) router.replace("/");
  }, [hydrated, session, router]);
  useEffect(() => {
    if (session?.status !== "ended") return;
    const until = endingRevealUntil(id);
    if (!until) return;
    const timer = window.setTimeout(() => finishReveal((tick) => tick + 1), Math.max(1, until - Date.now() + 1));
    return () => window.clearTimeout(timer);
  }, [id, session?.status, revealTick]);

  if (!session) return null;
  if (session.status === "briefing") return <Briefing key={id} session={session} />;
  if (session.status === "active" || session.status === "ended" && endingRevealUntil(id)) return <Chat key={id} session={session} />;
  return <Debrief key={id} session={session} />;
}
