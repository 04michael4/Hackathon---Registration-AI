"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { Lightbulb, Search, SlidersHorizontal, X } from "lucide-react";

import { CourseCard } from "@/components/course-card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import type { CourseDetail, Day } from "@/lib/api/types";
import { useCatalog } from "@/lib/catalog";
import { NEXT_TERM, TERMS, termLabel } from "@/lib/demo/program";
import { searchByInterest } from "@/lib/interest";
import { courseCode, isOffered, kindOf, missingPrereqs, requirementFor, termIndex, whyLine } from "@/lib/rules";
import { student, useStore } from "@/lib/store";
import { DAY_LETTER, minutes, WEEKDAYS } from "@/lib/ui";

type TimeOfDay = "morning" | "afternoon" | "evening";

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
  return course.sections
    .filter((s) => !s.lectureLink)
    .some((s) =>
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

interface SearchBarProps {
  placedCourses: string[];
  onAdd: (courseId: string) => void;
  onOpen: (courseId: string) => void;
}

export function SearchBar({ placedCourses, onAdd, onOpen }: SearchBarProps) {
  const { courses } = useCatalog();
  const { doc } = useStore();
  const [query, setQuery] = useState("");
  const [interest, setInterest] = useState("");
  const [days, setDays] = useState<Day[]>([]);
  const [times, setTimes] = useState<TimeOfDay[]>([]);
  const [credits, setCredits] = useState<string[]>([]);
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const filterCount = days.length + times.length + credits.length;
  const mode: "interest" | "courses" | null = interest.trim() ? "interest" : query.trim() || filterCount ? "courses" : null;

  useEffect(() => {
    const close = (event: MouseEvent) => {
      if (box.current && !box.current.contains(event.target as Node) && !(event.target as HTMLElement).closest("[data-radix-popper-content-wrapper]")) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  const results = useMemo(() => {
    if (mode === "interest") {
      const matches = searchByInterest(
        Object.values(courses).map((c) => ({ courseId: c.courseId, title: c.title, description: c.description, kind: kindOf(student, doc.degree, c.courseId) })),
        interest,
      );
      return matches.map((m) => ({ courseId: m.courseId, note: `Matches ${m.matched.map((w) => `“${w}”`).join(", ")}` }));
    }
    if (mode === "courses") {
      return Object.values(courses)
        .filter((c) => matchesQuery(c, query, requirementFor(student, doc.degree, c.courseId)?.label ?? "") && matchesFilters(c, days, times, credits))
        .sort((a, b) => a.courseId.localeCompare(b.courseId))
        .map((c) => ({ courseId: c.courseId, note: null as string | null }));
    }
    return [];
  }, [mode, courses, interest, query, days, times, credits, doc.degree]);

  const clear = () => {
    setQuery("");
    setInterest("");
    setDays([]);
    setTimes([]);
    setCredits([]);
    setOpen(false);
  };

  return (
    <div ref={box} className="relative" onKeyDown={(e) => e.key === "Escape" && setOpen(false)}>
      <div className="flex flex-wrap items-center gap-2">
        <label className="relative min-w-60 flex-1">
          <span className="sr-only">Search courses</span>
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-ink-muted" aria-hidden />
          <Input
            value={query}
            onFocus={() => setOpen(true)}
            onChange={(e) => { setQuery(e.target.value); setInterest(""); setOpen(true); }}
            placeholder="Search courses: code, title, professor or requirement"
            className="bg-white pl-8"
          />
        </label>
        <label className="relative min-w-60 flex-1">
          <span className="sr-only">Search by interest</span>
          <Lightbulb className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-ink-muted" aria-hidden />
          <Input
            value={interest}
            onFocus={() => setOpen(true)}
            onChange={(e) => { setInterest(e.target.value); setQuery(""); setOpen(true); }}
            placeholder="Search by interest: machine learning, security, finance"
            className="bg-white pl-8"
          />
        </label>
        <Popover>
          <PopoverTrigger asChild>
            <Button variant="outline">
              <SlidersHorizontal aria-hidden /> Filters{filterCount ? ` (${filterCount})` : ""}
            </Button>
          </PopoverTrigger>
          <PopoverContent align="end" className="w-72 space-y-3 text-sm">
            <div>
              <p className="mb-1 font-bold">Days</p>
              <ToggleGroup type="multiple" size="sm" variant="outline" value={days} onValueChange={(v) => { setDays(v as Day[]); setOpen(true); }} aria-label="Days">
                {WEEKDAYS.map((d) => <ToggleGroupItem key={d} value={d} className="w-8 px-0" aria-label={d.toLowerCase()}>{DAY_LETTER[d]}</ToggleGroupItem>)}
              </ToggleGroup>
            </div>
            <div>
              <p className="mb-1 font-bold">Time of day</p>
              <ToggleGroup type="multiple" size="sm" variant="outline" value={times} onValueChange={(v) => { setTimes(v as TimeOfDay[]); setOpen(true); }} aria-label="Time of day">
                <ToggleGroupItem value="morning">Morning</ToggleGroupItem>
                <ToggleGroupItem value="afternoon">Afternoon</ToggleGroupItem>
                <ToggleGroupItem value="evening">Evening</ToggleGroupItem>
              </ToggleGroup>
            </div>
            <div>
              <p className="mb-1 font-bold">Credits</p>
              <ToggleGroup type="multiple" size="sm" variant="outline" value={credits} onValueChange={(v) => { setCredits(v); setOpen(true); }} aria-label="Credits">
                <ToggleGroupItem value="3">3 credits</ToggleGroupItem>
                <ToggleGroupItem value="4">4 credits</ToggleGroupItem>
              </ToggleGroup>
            </div>
          </PopoverContent>
        </Popover>
        {mode && (
          <Button variant="ghost" onClick={clear}>
            <X aria-hidden /> Clear
          </Button>
        )}
      </div>

      {open && mode && (
        <div className="absolute inset-x-0 top-full z-30 mt-1 max-h-[min(60vh,32rem)] overflow-y-auto rounded-lg border border-grid bg-white shadow-lg" role="region" aria-label="Search results">
          <p className="border-b border-grid px-3 py-2 text-xs text-ink-muted">
            {results.length} {results.length === 1 ? "course" : "courses"} in {termLabel(NEXT_TERM)}
            {mode === "interest" ? " related to your interest, electives first" : ""}
          </p>
          {results.length === 0 ? (
            <p className="px-3 py-5 text-sm text-ink-muted">
              {mode === "interest" ? "No course mentions that yet. Try a broader word like “data” or “security”." : "Nothing matches. Try a course code like “CSE 140” or fewer filters."}
            </p>
          ) : (
            results.map(({ courseId, note }) => {
              const course = courses[courseId];
              if (!course) return null;
              const missing = missingPrereqs(student, doc.degree, courseId, NEXT_TERM);
              const placed = placedCourses.includes(courseId);
              const hint = missing.length ? whenEligible(missing) : null;
              return (
                <CourseCard
                  key={courseId}
                  courseId={courseId}
                  title={course.title}
                  credits={course.credits}
                  kind={kindOf(student, doc.degree, courseId)}
                  why={placed ? "On your calendar." : whyLine(student, doc.degree, courseId)}
                  lockedReason={missing.length ? `Take ${missing.map(courseCode).join(" and ")} first.` : undefined}
                  badges={missing.length ? ["Prereq missing"] : []}
                  onAdd={placed ? undefined : () => { onAdd(courseId); setOpen(false); }}
                  onOpen={() => onOpen(courseId)}
                  draggable={!placed}
                >
                  {note && <p className="mt-0.5 text-xs text-brand">{note}</p>}
                  {hint && <p className="mt-0.5 text-xs text-ink">{hint}</p>}
                </CourseCard>
              );
            })
          )}
        </div>
      )}
    </div>
  );
}
