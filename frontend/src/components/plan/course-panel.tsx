"use client";

import { useMemo, useState } from "react";

import { Search, X } from "lucide-react";

import { CourseCard } from "@/components/course-card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import type { CourseDetail, Day } from "@/lib/api/types";
import { useCatalog } from "@/lib/catalog";
import { NEXT_TERM, TERMS, termLabel } from "@/lib/demo/program";
import { courseCode, isOffered, isSlot, kindOf, missingPrereqs, recommended, requirementFor, termIndex, whyLine } from "@/lib/rules";
import { student, useStore } from "@/lib/store";
import { DAY_LETTER, minutes, WEEKDAYS } from "@/lib/ui";

type TimeOfDay = "morning" | "afternoon" | "evening";

interface CoursePanelProps {
  placedCourses: string[];
  onAdd: (courseId: string) => void;
  onOpen: (courseId: string) => void;
}

function matchesQuery(course: CourseDetail, query: string, requirementLabel: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  const compact = q.replace(/[\s-]/g, "");
  const code = course.courseId.toLowerCase().replace("-", "");
  const unpadded = code.replace(/([a-z]+)0+(\d)/, "$1$2");
  const haystack = [course.title, course.courseId, requirementLabel, ...course.sections.flatMap((s) => s.instructors)].join(" ").toLowerCase();
  return code.includes(compact) || unpadded.includes(compact) || q.split(/\s+/).every((w) => haystack.includes(w));
}

function matchesFilters(course: CourseDetail, days: Day[], times: TimeOfDay[], credits: string[]): boolean {
  if (credits.length && !credits.includes(String(course.credits))) return false;
  const lectures = course.sections.filter((s) => !s.lectureLink);
  return lectures.some((s) =>
    s.meetings.every((m) => {
      const dayOk = days.length === 0 || m.days.every((d) => days.includes(d));
      const start = minutes(m.start);
      const slot: TimeOfDay = start < 12 * 60 ? "morning" : start < 17 * 60 ? "afternoon" : "evening";
      return dayOk && (times.length === 0 || times.includes(slot));
    }),
  );
}

/** "Take CSE 109 in Spring 2027 → eligible Fall 2027." (the spec's wording) */
function whenEligible(missing: string[]): string | null {
  if (missing.length !== 1) return null;
  const next = TERMS[termIndex(NEXT_TERM) + 1];
  if (!next || !isOffered(missing[0], NEXT_TERM)) return null;
  return `Take ${courseCode(missing[0])} in ${termLabel(NEXT_TERM)} → eligible ${next.label}.`;
}

export function CoursePanel({ placedCourses, onAdd, onOpen }: CoursePanelProps) {
  const { courses, available } = useCatalog();
  const { doc } = useStore();
  const [query, setQuery] = useState("");
  const [days, setDays] = useState<Day[]>([]);
  const [times, setTimes] = useState<TimeOfDay[]>([]);
  const [credits, setCredits] = useState<string[]>([]);
  const searching = query.trim() !== "" || days.length + times.length + credits.length > 0;

  const recs = useMemo(() => recommended(student, doc.degree, NEXT_TERM, available), [doc.degree, available]);
  const fromPlan = (doc.degree[NEXT_TERM] ?? []).filter((id) => !isSlot(id) && available.has(id));
  const groups = useMemo(() => {
    const byLabel = new Map<string, string[]>();
    for (const r of recs) {
      if (fromPlan.includes(r.courseId)) continue;
      byLabel.set(r.requirement.label, [...(byLabel.get(r.requirement.label) ?? []), r.courseId]);
    }
    return [...byLabel.entries()];
  }, [recs, fromPlan]);

  const results = useMemo(
    () =>
      Object.values(courses)
        .filter((c) => matchesQuery(c, query, requirementFor(student, doc.degree, c.courseId)?.label ?? "") && matchesFilters(c, days, times, credits))
        .sort((a, b) => a.courseId.localeCompare(b.courseId)),
    [courses, query, days, times, credits, doc.degree],
  );

  const card = (courseId: string, allowLocked: boolean) => {
    const course = courses[courseId];
    if (!course) return null;
    const missing = missingPrereqs(student, doc.degree, courseId, NEXT_TERM);
    const locked = missing.length > 0;
    const placed = placedCourses.includes(courseId);
    if (locked && !allowLocked) return null;
    const hint = locked ? whenEligible(missing) : null;
    return (
      <CourseCard
        key={courseId}
        courseId={courseId}
        title={course.title}
        credits={course.credits}
        kind={kindOf(student, doc.degree, courseId)}
        why={placed ? "On your calendar." : whyLine(student, doc.degree, courseId)}
        lockedReason={locked ? `Take ${missing.map(courseCode).join(" and ")} first.` : undefined}
        badges={locked ? ["Prereq missing"] : []}
        onAdd={placed ? undefined : () => onAdd(courseId)}
        onOpen={() => onOpen(courseId)}
        draggable={!placed}
      >
        {hint && <p className="mt-1 text-xs text-ink">{hint}</p>}
      </CourseCard>
    );
  };

  return (
    <div className="flex h-full flex-col">
      <div className="space-y-2 border-b border-grid p-3">
        <label className="relative block">
          <span className="sr-only">Search courses</span>
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Code, title, professor or requirement"
            className="bg-white pl-8"
          />
        </label>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs">
          <ToggleGroup type="multiple" size="sm" variant="outline" value={days} onValueChange={(v) => setDays(v as Day[])} aria-label="Days">
            {WEEKDAYS.map((d) => (
              <ToggleGroupItem key={d} value={d} className="w-7 px-0" aria-label={d.toLowerCase()}>
                {DAY_LETTER[d]}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <ToggleGroup type="multiple" size="sm" variant="outline" value={times} onValueChange={(v) => setTimes(v as TimeOfDay[])} aria-label="Time of day">
            <ToggleGroupItem value="morning">AM</ToggleGroupItem>
            <ToggleGroupItem value="afternoon">PM</ToggleGroupItem>
            <ToggleGroupItem value="evening">Eve</ToggleGroupItem>
          </ToggleGroup>
          <ToggleGroup type="multiple" size="sm" variant="outline" value={credits} onValueChange={setCredits} aria-label="Credits">
            <ToggleGroupItem value="3">3 cr</ToggleGroupItem>
            <ToggleGroupItem value="4">4 cr</ToggleGroupItem>
          </ToggleGroup>
          {searching && (
            <Button variant="ghost" size="xs" onClick={() => { setQuery(""); setDays([]); setTimes([]); setCredits([]); }}>
              <X aria-hidden /> Clear
            </Button>
          )}
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {searching ? (
          <section aria-label="Search results">
            <p className="px-3 pt-3 pb-1 text-xs text-muted-foreground">
              {results.length} {results.length === 1 ? "course" : "courses"} in {termLabel(NEXT_TERM)}
            </p>
            {results.length === 0 ? (
              <p className="px-3 py-6 text-sm text-muted-foreground">Nothing matches. Try a course code like “CSE 140” or fewer filters.</p>
            ) : (
              results.map((c) => card(c.courseId, true))
            )}
          </section>
        ) : (
          <>
            {fromPlan.length > 0 && (
              <section>
                <h2 className="px-3 pt-3 pb-1 text-sm font-bold">From your degree plan</h2>
                <div className="border-y border-grid">{fromPlan.map((id) => card(id, true))}</div>
              </section>
            )}
            {groups.length === 0 && fromPlan.length === 0 ? (
              <p className="px-3 py-6 text-sm text-muted-foreground">No recommendations right now. Search for any course above.</p>
            ) : (
              groups.map(([label, ids]) => (
                <section key={label}>
                  <h2 className="px-3 pt-3 pb-1 text-sm font-bold">{label}</h2>
                  <div className="border-y border-grid">{ids.map((id) => card(id, false))}</div>
                </section>
              ))
            )}
          </>
        )}
      </div>
    </div>
  );
}
