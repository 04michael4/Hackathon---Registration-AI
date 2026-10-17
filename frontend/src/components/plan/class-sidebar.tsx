"use client";

import { useMemo } from "react";

import { X } from "lucide-react";

import { CourseChip } from "@/components/plan/course-chip";
import { useCatalog } from "@/lib/catalog";
import { NEXT_TERM } from "@/lib/demo/program";
import { componentLabel, type SectionIndex } from "@/lib/plan";
import { courseCode, isSlot, kindOf, recommended, whyLine } from "@/lib/rules";
import { student, useStore } from "@/lib/store";
import { KIND_COLOR } from "@/lib/ui";

interface ClassSidebarProps {
  placed: string[];
  placedCourses: string[];
  index: SectionIndex;
  onAdd: (courseId: string) => void;
  onOpen: (courseId: string) => void;
  onRemove: (crn: string) => void;
}

/** David's right sidebar: Recommended classes (compact chips) and Planned classes. */
export function ClassSidebar({ placed, placedCourses, index, onAdd, onOpen, onRemove }: ClassSidebarProps) {
  const { courses, available } = useCatalog();
  const { doc } = useStore();
  const recs = useMemo(() => recommended(student, doc.degree, NEXT_TERM, available), [doc.degree, available]);
  const fromPlan = (doc.degree[NEXT_TERM] ?? []).filter((id) => !isSlot(id) && available.has(id));
  const ids = [...new Set([...fromPlan, ...recs.map((r) => r.courseId)])];
  const lectures = placed.filter((crn) => !index.get(crn)?.section.lectureLink);

  return (
    <aside className="flex h-full min-h-0 flex-col gap-3" aria-label="Course planning lists">
      <section className="flex min-h-0 flex-1 flex-col rounded-lg border border-grid bg-white">
        <div className="flex items-baseline justify-between border-b border-grid px-3 py-2">
          <h2 className="text-sm font-bold">Recommended classes</h2>
          <span className="tabular text-xs text-ink-muted">{ids.length}</span>
        </div>
        <p className="px-3 pt-2 text-xs text-ink-muted">Prerequisites met, needed to graduate, offered this term. Drag onto the calendar or press +.</p>
        <div className="min-h-0 flex-1 space-y-1.5 overflow-y-auto p-3">
          {ids.length === 0 && <p className="text-sm text-ink-muted">Nothing left to recommend. Search for any course above.</p>}
          {ids.map((id) => {
            const course = courses[id];
            if (!course) return null;
            return (
              <CourseChip
                key={id}
                courseId={id}
                title={course.title}
                credits={course.credits}
                kind={kindOf(student, doc.degree, id)}
                why={fromPlan.includes(id) ? `In your degree plan for this term. ${whyLine(student, doc.degree, id)}.` : `${whyLine(student, doc.degree, id)}.`}
                placed={placedCourses.includes(id)}
                onOpen={() => onOpen(id)}
                onAdd={() => onAdd(id)}
              />
            );
          })}
        </div>
      </section>

      <section className="flex max-h-[42%] min-h-0 flex-col rounded-lg border border-grid bg-white">
        <div className="flex items-baseline justify-between border-b border-grid px-3 py-2">
          <h2 className="text-sm font-bold">Planned classes</h2>
          <span className="tabular text-xs text-ink-muted">{lectures.length}</span>
        </div>
        <ul className="min-h-0 flex-1 overflow-y-auto">
          {placed.length === 0 && <li className="px-3 py-3 text-sm text-ink-muted">Nothing planned yet.</li>}
          {placed.map((crn) => {
            const hit = index.get(crn);
            if (!hit) return null;
            return (
              <li key={crn} className="flex items-center gap-2 border-b border-grid px-3 py-1.5 text-sm last:border-b-0">
                <span className="h-4 w-1 rounded-sm" style={{ background: KIND_COLOR[kindOf(student, doc.degree, hit.course.courseId)] }} aria-hidden />
                <button type="button" className="min-w-0 flex-1 truncate text-left" onClick={() => onOpen(hit.course.courseId)}>
                  <b className="font-heading">{courseCode(hit.course.courseId)}</b>{" "}
                  <span className="text-ink-muted">{hit.section.lectureLink ? componentLabel(hit.section) : "Section"} {hit.section.sectionCode}</span>
                </button>
                <button type="button" onClick={() => onRemove(crn)} aria-label={`Remove ${courseCode(hit.course.courseId)} ${hit.section.sectionCode}`} className="text-ink-muted hover:text-ink">
                  <X className="size-3.5" />
                </button>
              </li>
            );
          })}
        </ul>
      </section>
    </aside>
  );
}
