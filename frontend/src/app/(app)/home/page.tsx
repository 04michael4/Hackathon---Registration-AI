"use client";

import Link from "next/link";

import { CalendarDays, FolderOpen, GraduationCap, MessagesSquare } from "lucide-react";

import { isFeasible } from "@/lib/rules";
import { student, useStore } from "@/lib/store";

const FEATURES = [
  { href: "/plan", title: "Semester Planner", body: "Build your schedule manually, then use intelligent suggestions when you want help.", cta: "Open Semester Planner", icon: CalendarDays },
  { href: "/degree", title: "Degree Planner", body: "See your progress, the prerequisite map, and which courses you should take next.", cta: "Open Degree Planner", icon: GraduationCap },
  { href: "/advisor", title: "Academic Advisor Portal", body: "Share your plan, receive feedback, and make changes with your advisor.", cta: "Open Advisor Portal", icon: MessagesSquare },
  { href: "/saved", title: "Saved Schedules", body: "Reopen, compare and pick the schedule you'll use on registration day.", cta: "Open Saved Schedules", icon: FolderOpen },
];

/** David's features hub: "What would you like to do?" */
export default function HomePage() {
  const { doc, session } = useStore();
  const advisorLocked = session?.role === "student" && !isFeasible(student, doc.degree);
  return (
    <div className="mx-auto flex h-full max-w-6xl flex-col justify-center">
      <div className="mb-8 border-b border-grid pb-6">
        <p className="text-sm text-ink-muted">Welcome back, {session?.name.split(" ")[0]}.</p>
        <h1 className="text-3xl font-bold">What would you like to do?</h1>
      </div>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {FEATURES.map(({ href, title, body, cta, icon: Icon }) => {
          const locked = href === "/advisor" && advisorLocked;
          return (
            <article key={href} className="flex min-h-56 flex-col rounded-xl border border-grid bg-white p-6">
              <span className="mb-5 grid size-9 place-items-center rounded-lg bg-brand-soft text-brand" aria-hidden>
                <Icon className="size-5" />
              </span>
              <h2 className="text-lg font-bold">{title}</h2>
              <p className="mt-2 flex-1 text-sm text-ink-muted">{body}</p>
              {locked && <p className="mt-2 text-xs text-ink-muted">Complete your degree plan to unlock advisor review.</p>}
              <Link href={href} className="mt-4 text-sm font-bold text-brand hover:underline">
                {cta} <span aria-hidden>→</span>
              </Link>
            </article>
          );
        })}
      </div>
    </div>
  );
}
