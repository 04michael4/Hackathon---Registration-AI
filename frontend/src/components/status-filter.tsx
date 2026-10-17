"use client";

import { COURSE_STATUSES, type CourseStatus } from "@/lib/rules";
import { cn } from "@/lib/utils";

interface StatusFilterProps {
  /** Selected statuses; empty means All. */
  value: CourseStatus[];
  onChange: (value: CourseStatus[]) => void;
  counts: Record<CourseStatus, number>;
}

/** Multi-select course-status filter with an "All" option. */
export function StatusFilter({ value, onChange, counts }: StatusFilterProps) {
  const toggle = (status: CourseStatus) => {
    const next = value.includes(status) ? value.filter((s) => s !== status) : [...value, status];
    // Picking every status is the same as All.
    onChange(next.length === COURSE_STATUSES.length ? [] : COURSE_STATUSES.filter((s) => next.includes(s)));
  };
  const chip = (active: boolean) =>
    cn(
      "inline-flex h-7 items-center gap-1.5 rounded-full border px-3 text-xs",
      active ? "border-brand bg-brand text-white" : "border-grid bg-white text-ink hover:border-brand-mid",
    );
  return (
    <div role="group" aria-label="Filter by course status" className="flex flex-wrap items-center gap-1.5">
      <span className="mr-1 text-xs font-bold text-ink-muted">Show</span>
      <button type="button" aria-pressed={value.length === 0} onClick={() => onChange([])} className={chip(value.length === 0)}>
        All
      </button>
      {COURSE_STATUSES.map((status) => (
        <button key={status} type="button" aria-pressed={value.includes(status)} onClick={() => toggle(status)} className={chip(value.includes(status))}>
          {status}
          <span className={cn("tabular", value.includes(status) ? "text-white/80" : "text-ink-muted")}>{counts[status]}</span>
        </button>
      ))}
    </div>
  );
}

export function statusCounts(items: { status: CourseStatus }[]): Record<CourseStatus, number> {
  const counts = Object.fromEntries(COURSE_STATUSES.map((s) => [s, 0])) as Record<CourseStatus, number>;
  for (const i of items) counts[i.status]++;
  return counts;
}
