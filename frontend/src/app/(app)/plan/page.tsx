"use client";

import { useEffect, useMemo, useState } from "react";

import { AlertTriangle, Loader2, Sparkles } from "lucide-react";
import { toast } from "sonner";

import { AiAssistant } from "@/components/plan/ai-assistant";
import { ClassSidebar } from "@/components/plan/class-sidebar";
import { CourseDrawer } from "@/components/plan/course-drawer";
import { SearchBar } from "@/components/plan/search-bar";
import { type CalendarBlock, WeekCalendar } from "@/components/week-calendar";
import { Button } from "@/components/ui/button";
import { ContextMenu, ContextMenuContent, ContextMenuItem, ContextMenuSeparator, ContextMenuTrigger } from "@/components/ui/context-menu";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api/client";
import type { CheckResponse } from "@/lib/api/types";
import { useCatalog } from "@/lib/catalog";
import { API_TERM, REGISTRATION_OPENS, termLabel } from "@/lib/demo/program";
import { blocksFor, componentLabel, coursesOf, creditsOfCrns, crnsOfCourse, whenText, withLinked } from "@/lib/plan";
import { courseCode, kindOf } from "@/lib/rules";
import { NEXT, student, uid, useStore } from "@/lib/store";

function countdown(now: number): string {
  const ms = REGISTRATION_OPENS.getTime() - now;
  if (ms <= 0) return "Registration is open";
  const days = Math.floor(ms / 86_400_000);
  const hours = Math.floor((ms % 86_400_000) / 3_600_000);
  return `Registration opens in ${days}d ${String(hours).padStart(2, "0")}h`;
}

export default function PlanPage() {
  const catalog = useCatalog();
  const { sectionByCrn } = catalog;
  const store = useStore();
  const { doc, commit, undo } = store;
  const placed = doc.crns;
  const [pending, setPending] = useState<string | null>(null);
  const [check, setCheck] = useState<CheckResponse | null>(null);
  const [checking, setChecking] = useState(false);
  const [drawer, setDrawer] = useState<string | null>(null);
  const [aiOpen, setAiOpen] = useState(false);
  const [saveOpen, setSaveOpen] = useState(false);
  const [saveName, setSaveName] = useState("");
  const [now, setNow] = useState(() => Date.now());
  const [min, max] = student.creditRange;

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  const placedCourses = useMemo(() => coursesOf(placed, sectionByCrn), [placed, sectionByCrn]);
  const credits = creditsOfCrns(placed, sectionByCrn);
  const kind = (courseId: string) => kindOf(student, doc.degree, courseId);

  // Ask the engine about the current board whenever it changes.
  useEffect(() => {
    if (catalog.state.status !== "ready" || (placed.length === 0 && !pending)) return;
    let cancelled = false;
    const required = placedCourses.length ? placedCourses : pending ? [pending] : [];
    const timer = window.setTimeout(() => {
      setChecking(true);
      api
        .check({
          term: API_TERM,
          preferences: { hardConstraints: { requiredCourseIds: required, minCredits: min, maxCredits: max } },
          courseIds: pending ? [pending] : [],
          crns: placed,
        })
        .then((result) => !cancelled && setCheck(result))
        .catch((error: unknown) => !cancelled && toast.error(error instanceof Error ? error.message : "Couldn't check the schedule."))
        .finally(() => !cancelled && setChecking(false));
    }, 120);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [placed, pending, placedCourses, catalog.state.status, min, max]);

  const current = placed.length === 0 && !pending ? null : check;
  const issues = current?.issues ?? [];
  const conflictCrns = new Set(issues.filter((i) => i.severity === "CONFLICT").flatMap((i) => i.crns));
  const candidate = (crn: string) => current?.candidates.find((c) => c.crn === crn);

  const blocks: CalendarBlock[] = useMemo(() => {
    const placedBlocks = blocksFor(placed, sectionByCrn, kind, (crn) => (conflictCrns.has(crn) ? "conflict" : "placed"));
    // Ghosts: sections of the course being added, and the labs/recitations a placed lecture still needs.
    const ghostCrns = new Set<string>();
    if (pending) {
      for (const s of catalog.courses[pending]?.sections ?? []) if (!s.lectureLink && !placed.includes(s.crn)) ghostCrns.add(s.crn);
    }
    for (const c of current?.candidates ?? []) if (c.completesLecture) ghostCrns.add(c.crn);
    const ghostBlocks = blocksFor(
      [...ghostCrns],
      sectionByCrn,
      kind,
      (crn) => {
        const cand = candidate(crn);
        if (cand?.completesLecture) return "ghost-highlight";
        return cand && !cand.fits ? "ghost-blocked" : "ghost";
      },
      (crn) => {
        const cand = candidate(crn);
        const s = sectionByCrn.get(crn)?.section;
        if (cand && !cand.fits) return cand.reasons.join(" ");
        return s ? `Choose ${componentLabel(s).toLowerCase()} ${s.sectionCode} (${whenText(s)})` : undefined;
      },
    );
    return [...placedBlocks, ...ghostBlocks];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placed, pending, current, sectionByCrn, catalog.courses, doc.degree]);

  const place = (crn: string) => {
    const hit = sectionByCrn.get(crn);
    if (!hit) return;
    const code = courseCode(hit.course.courseId);
    commit(`Add ${code} ${hit.section.sectionCode}`, (d) => {
      const crns = [...d.crns, crn];
      return { ...d, crns, courses: coursesOf(crns, sectionByCrn) };
    });
    if (!hit.section.lectureLink) setPending(null);
  };

  const remove = (crn: string) => {
    const hit = sectionByCrn.get(crn);
    if (!hit) return;
    const gone = withLinked(crn, placed, sectionByCrn);
    const code = courseCode(hit.course.courseId);
    const label = hit.section.lectureLink ? `${code} ${componentLabel(hit.section).toLowerCase()}` : code;
    commit(`Remove ${label}`, (d) => {
      const crns = d.crns.filter((c) => !gone.includes(c));
      return { ...d, crns, courses: coursesOf(crns, sectionByCrn) };
    });
    toast(`Removed ${label}`, { action: { label: "Undo", onClick: () => undo() } });
  };

  const removeCourse = (courseId: string) => {
    const gone = crnsOfCourse(courseId, placed, sectionByCrn);
    commit(`Remove ${courseCode(courseId)}`, (d) => {
      const crns = d.crns.filter((c) => !gone.includes(c));
      return { ...d, crns, courses: coursesOf(crns, sectionByCrn) };
    });
    toast(`Removed ${courseCode(courseId)}`, { action: { label: "Undo", onClick: () => undo() } });
  };

  const swap = (crn: string) => {
    const courseId = sectionByCrn.get(crn)?.course.courseId;
    if (!courseId) return;
    const gone = crnsOfCourse(courseId, placed, sectionByCrn);
    commit(`Swap ${courseCode(courseId)} section`, (d) => {
      const crns = d.crns.filter((c) => !gone.includes(c));
      return { ...d, crns, courses: coursesOf(crns, sectionByCrn) };
    });
    setPending(courseId);
  };

  const replace = (oldCourse: string, newCrn: string) => {
    const gone = crnsOfCourse(oldCourse, placed, sectionByCrn);
    const section = sectionByCrn.get(newCrn)?.section;
    commit(`Move ${courseCode(oldCourse)} to section ${section?.sectionCode ?? newCrn}`, (d) => {
      const crns = [...d.crns.filter((c) => !gone.includes(c)), newCrn];
      return { ...d, crns, courses: coursesOf(crns, sectionByCrn) };
    });
  };

  const startAdding = (courseId: string) => {
    if (placedCourses.includes(courseId)) return;
    setPending(courseId);
    setDrawer(null);
  };

  const acceptAi = (crns: string[], label: string) => {
    commit(label, (d) => {
      const all = [...d.crns, ...crns.filter((c) => !d.crns.includes(c))];
      return { ...d, crns: all, courses: coursesOf(all, sectionByCrn) };
    });
    toast(`${label} added to your calendar`, { action: { label: "Undo", onClick: () => undo() } });
    setAiOpen(false);
  };

  const save = () => {
    const name = saveName.trim() || `Plan ${String.fromCharCode(65 + doc.saved.length)}`;
    commit(`Save “${name}”`, (d) => ({
      ...d,
      saved: [
        { id: uid(), name, term: API_TERM, crns: d.crns, courseIds: d.courses, credits, updatedAt: new Date().toISOString() },
        ...d.saved,
      ],
    }));
    setSaveOpen(false);
    setSaveName("");
    toast(`Saved “${name}”`, { description: "Find it in Saved schedules." });
  };

  /** For a time conflict: another section of either course that fits as things stand. */
  const alternativeFor = (courseId: string) =>
    current?.candidates.find((c) => c.courseId === courseId && c.fits && !sectionByCrn.get(c.crn)?.section.lectureLink);

  /** Student-facing wording for the engine's "needs a LAB" style prompts. */
  const promptText = (type: string, message: string, crn: string | undefined) => {
    const hit = crn ? sectionByCrn.get(crn) : undefined;
    if (!hit) return message;
    const code = courseCode(hit.course.courseId);
    if (type === "MISSING_LINKED_SECTION") {
      const component = /needs a (\w+)\.$/.exec(message)?.[1]?.toLowerCase() ?? "linked section";
      return `${code} needs a ${component}. Pick one of the highlighted blocks.`;
    }
    return `This ${componentLabel(hit.section).toLowerCase()} belongs to a ${code} lecture you haven't placed. Add the lecture, or remove it.`;
  };

  const conflictIssues = issues.filter((i) => i.type === "TIME_CONFLICT");
  const prompts = issues.filter((i) => ["MISSING_LINKED_SECTION", "LINKED_WITHOUT_LECTURE"].includes(i.type));
  const otherConflicts = issues.filter((i) => i.severity === "CONFLICT" && i.type !== "TIME_CONFLICT");
  const creditTone = credits > max ? "text-signal" : credits >= min ? "text-brand" : "text-ink";

  if (catalog.state.status === "error") {
    return (
      <div className="mx-auto max-w-lg rounded-sm border border-grid bg-white p-6">
        <h1 className="text-2xl font-bold">The course catalog isn&apos;t available</h1>
        <p className="mt-2 text-sm">{catalog.state.message}</p>
        <p className="mt-2 text-sm text-muted-foreground">
          To run the demo, start the backend with <code className="rounded-sm bg-muted px-1">CATALOG_SOURCE=sample ./mvnw spring-boot:run</code>.
        </p>
        <Button className="mt-4" onClick={catalog.reload}>Try again</Button>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-2.5">
      <div className="flex shrink-0 flex-wrap items-center gap-x-6 gap-y-1">
        <h1 className="font-heading text-2xl font-bold">{termLabel(NEXT)}</h1>
        <p className="tabular text-sm text-ink-muted">{countdown(now)}</p>
        <p className="tabular text-sm" aria-live="polite">
          <span className={`font-heading text-xl font-bold ${creditTone}`}>{credits}</span>
          <span className="text-ink-muted"> / {min}–{max} credits</span>
        </p>
        <div className="ml-auto flex items-center gap-2">
          {checking && <Loader2 className="size-4 animate-spin text-ink-muted" aria-label="Checking schedule" />}
          <Button variant="outline" onClick={() => setSaveOpen(true)} disabled={placed.length === 0}>Save version</Button>
          <Button variant={aiOpen ? "outline" : "default"} onClick={() => setAiOpen((open) => !open)} aria-pressed={aiOpen}>
            <Sparkles aria-hidden /> {aiOpen ? "Hide Syllo AI" : "Build with AI"}
          </Button>
        </div>
      </div>

      <div className="shrink-0">
        {catalog.state.status === "loading" ? (
          <p className="flex h-9 items-center gap-2 text-sm text-ink-muted"><Loader2 className="size-4 animate-spin" aria-hidden /> Loading courses…</p>
        ) : (
          <SearchBar placedCourses={placedCourses} onAdd={startAdding} onOpen={setDrawer} />
        )}
      </div>

      {(conflictIssues.length > 0 || otherConflicts.length > 0 || prompts.length > 0 || pending) && (
        <div className="max-h-28 shrink-0 space-y-1.5 overflow-y-auto">
          {conflictIssues.map((issue) => {
            const [a, b] = issue.crns.map((crn) => sectionByCrn.get(crn)?.course.courseId ?? crn);
            const alt = alternativeFor(b) ?? alternativeFor(a);
            const altCourse = alt?.courseId;
            const altSection = alt ? sectionByCrn.get(alt.crn)?.section : undefined;
            return (
              <div key={issue.crns.join()} role="alert" className="flex flex-wrap items-center gap-2 rounded-md border border-signal bg-signal-soft px-3 py-1.5 text-sm">
                <AlertTriangle className="size-4 text-signal" aria-hidden />
                <span className="mr-auto">
                  {courseCode(a)} and {courseCode(b)} meet at the same time. Which one do you want to keep?
                </span>
                <Button size="sm" variant="outline" className="bg-white" onClick={() => removeCourse(b)}>Keep {courseCode(a)}</Button>
                <Button size="sm" variant="outline" className="bg-white" onClick={() => removeCourse(a)}>Keep {courseCode(b)}</Button>
                {alt && altCourse && altSection && (
                  <Button size="sm" onClick={() => replace(altCourse, alt.crn)}>
                    Move {courseCode(altCourse)} to {whenText(altSection)}
                  </Button>
                )}
              </div>
            );
          })}
          {otherConflicts.map((issue) => (
            <p key={issue.message} role="alert" className="rounded-md border border-signal bg-signal-soft px-3 py-1.5 text-sm">{issue.message}</p>
          ))}
          {prompts.map((issue) => (
            <p key={issue.message} className="rounded-md bg-accent px-3 py-1.5 text-sm">
              {promptText(issue.type, issue.message, issue.crns[0])}
            </p>
          ))}
          {pending && (
            <div className="flex items-center gap-2 rounded-md border border-dashed border-brand bg-white px-3 py-1 text-sm">
              <span className="mr-auto">
                Choose a section of <b>{courseCode(pending)}</b> on the calendar. Grey ones don&apos;t fit; hover to see why.
              </span>
              <Button size="sm" variant="ghost" onClick={() => setPending(null)}>Cancel</Button>
            </div>
          )}
        </div>
      )}

      <div className="grid min-h-0 flex-1 gap-3 lg:grid-cols-[minmax(0,1fr)_19rem] xl:grid-cols-[minmax(0,1fr)_21rem]">
        <div className="flex min-h-0 min-w-0 flex-col gap-1">
          <div className="min-h-0 flex-1">
            <WeekCalendar
              blocks={blocks}
              onDropCourse={startAdding}
              onBlockClick={(block) => {
                if (block.variant.startsWith("ghost")) place(block.crn);
                else setDrawer(sectionByCrn.get(block.crn)?.course.courseId ?? null);
              }}
              wrapBlock={(block, node) =>
                block.variant.startsWith("ghost") ? (
                  node
                ) : (
                  <ContextMenu>
                    <ContextMenuTrigger asChild>{node}</ContextMenuTrigger>
                    <ContextMenuContent>
                      <ContextMenuItem onSelect={() => setDrawer(sectionByCrn.get(block.crn)?.course.courseId ?? null)}>Details</ContextMenuItem>
                      <ContextMenuItem onSelect={() => setDrawer(sectionByCrn.get(block.crn)?.course.courseId ?? null)}>Professor rating</ContextMenuItem>
                      <ContextMenuItem onSelect={() => swap(block.crn)}>Swap section</ContextMenuItem>
                      <ContextMenuSeparator />
                      <ContextMenuItem variant="destructive" onSelect={() => remove(block.crn)}>Remove</ContextMenuItem>
                    </ContextMenuContent>
                  </ContextMenu>
                )
              }
              emptyState={
                <>
                  <p className="font-bold">Your week is empty.</p>
                  <p className="mt-1 text-ink-muted">Drag a recommended class here, press + on one, or use Build with AI.</p>
                </>
              }
            />
          </div>
          <p className="shrink-0 text-xs text-ink-muted">Click a course for details. Right-click it to swap the section or remove it. Ctrl/Cmd+Z undoes.</p>
        </div>

        <div className="min-h-0">
          {aiOpen ? (
            <div className="h-full overflow-hidden rounded-lg border border-grid">
              <AiAssistant placed={placed} onAccept={acceptAi} onClose={() => setAiOpen(false)} />
            </div>
          ) : (
            <ClassSidebar placed={placed} placedCourses={placedCourses} index={sectionByCrn} onAdd={startAdding} onOpen={setDrawer} onRemove={remove} />
          )}
        </div>
      </div>

      <CourseDrawer courseId={drawer} onClose={() => setDrawer(null)} onShowSections={startAdding} />

      <Dialog open={saveOpen} onOpenChange={setSaveOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Save this version</DialogTitle>
            <DialogDescription>Saved versions appear in Saved schedules. Your draft is also saved automatically.</DialogDescription>
          </DialogHeader>
          <form onSubmit={(e) => { e.preventDefault(); save(); }} className="space-y-1.5">
            <Label htmlFor="save-name">Name</Label>
            <Input id="save-name" autoFocus value={saveName} onChange={(e) => setSaveName(e.target.value)} placeholder="Plan A – no Fridays" />
          </form>
          <DialogFooter>
            <Button variant="outline" onClick={() => setSaveOpen(false)}>Cancel</Button>
            <Button onClick={save}>Save version</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
