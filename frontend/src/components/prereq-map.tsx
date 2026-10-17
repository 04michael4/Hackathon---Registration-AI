"use client";

import { useEffect, useMemo, useRef, useState, type MouseEvent } from "react";

import { Check } from "lucide-react";

import { COURSES, termLabel, type CourseMeta, type StudentAccount } from "@/lib/demo/program";
import { courseCode, historyTermOf, kindOf, termOf, type DegreePlan } from "@/lib/rules";
import { KIND_COLOR } from "@/lib/ui";
import { cn } from "@/lib/utils";

export interface PrereqGraph {
  /** Columns left to right: a course sits one column after its latest prerequisite. */
  columns: string[][];
  prereqs: Record<string, string[]>;
  dependents: Record<string, string[]>;
}

/** Builds the map from the course data: only courses that need, or are needed by, another course. */
export function buildPrereqGraph(courses: Record<string, CourseMeta>): PrereqGraph {
  const ids = Object.keys(courses).sort();
  const prereqs: Record<string, string[]> = {};
  const dependents: Record<string, string[]> = {};
  for (const id of ids) {
    prereqs[id] = courses[id].prereqs.filter((p) => p in courses).sort();
    for (const p of prereqs[id]) (dependents[p] ??= []).push(id);
  }
  const inMap = ids.filter((id) => prereqs[id].length > 0 || (dependents[id]?.length ?? 0) > 0);
  for (const id of inMap) dependents[id] = (dependents[id] ?? []).sort();

  const depth: Record<string, number> = {};
  const depthOf = (id: string, seen: Set<string>): number => {
    if (depth[id] !== undefined) return depth[id];
    if (seen.has(id)) return 0; // a prerequisite cycle in the data; don't loop forever
    seen.add(id);
    depth[id] = prereqs[id].length ? 1 + Math.max(...prereqs[id].map((p) => depthOf(p, seen))) : 0;
    return depth[id];
  };
  inMap.forEach((id) => depthOf(id, new Set()));

  const columns: string[][] = [];
  for (const id of inMap) (columns[depth[id]] ??= []).push(id);
  // Order each column by where its prerequisites sit, so arrows cross less. Ties by course id.
  const row: Record<string, number> = {};
  columns.forEach((column, c) => {
    const key = (id: string) => (c === 0 ? 0 : prereqs[id].reduce((s, p) => s + (row[p] ?? 0), 0) / prereqs[id].length);
    column.sort((a, b) => key(a) - key(b) || a.localeCompare(b));
    column.forEach((id, r) => (row[id] = r));
  });
  return { columns, prereqs, dependents };
}

function ancestorsOf(id: string, prereqs: Record<string, string[]>): Set<string> {
  const out = new Set<string>();
  const stack = [...prereqs[id]];
  while (stack.length) {
    const next = stack.pop()!;
    if (out.has(next)) continue;
    out.add(next);
    stack.push(...(prereqs[next] ?? []));
  }
  return out;
}

type Rect = { left: number; right: number; mid: number };

interface PrereqMapProps {
  student: StudentAccount;
  plan: DegreePlan;
}

/**
 * Prerequisite map, modeled on a curriculum flowchart: earlier courses on the left.
 * No arrows until a course is selected; then its prerequisite chain and the courses it unlocks light up.
 */
export function PrereqMap({ student, plan }: PrereqMapProps) {
  const graph = useMemo(() => buildPrereqGraph(COURSES), []);
  const [selected, setSelected] = useState<string | null>(null);
  const [rects, setRects] = useState<Record<string, Rect>>({});
  const content = useRef<HTMLDivElement>(null);
  const nodes = useRef(new Map<string, HTMLElement>());

  // Measure every node once laid out and on resize; arrows are derived from these positions.
  useEffect(() => {
    const box = content.current;
    if (!box) return;
    const measure = () => {
      const origin = box.getBoundingClientRect();
      const next: Record<string, Rect> = {};
      nodes.current.forEach((el, id) => {
        const r = el.getBoundingClientRect();
        next[id] = { left: r.left - origin.left, right: r.right - origin.left, mid: r.top - origin.top + r.height / 2 };
      });
      setRects(next);
    };
    const observer = new ResizeObserver(measure);
    observer.observe(box);
    return () => observer.disconnect();
  }, []);

  const ancestors = useMemo(() => (selected ? ancestorsOf(selected, graph.prereqs) : new Set<string>()), [selected, graph]);
  const unlocked = useMemo(() => new Set(selected ? graph.dependents[selected] : []), [selected, graph]);

  const edges: [string, string][] = [];
  if (selected) {
    for (const to of [selected, ...ancestors]) for (const from of graph.prereqs[to]) if (ancestors.has(from)) edges.push([from, to]);
    for (const to of unlocked) edges.push([selected, to]);
  }

  const toggle = (id: string) => setSelected((current) => (current === id ? null : id));
  const clearOnEmpty = (event: MouseEvent) => {
    if (!(event.target as HTMLElement).closest("[data-course]")) setSelected(null);
  };

  const status = (id: string) => {
    const taken = historyTermOf(student, id);
    if (taken) return { done: true, text: `Done, ${termLabel(taken)}` };
    const planned = termOf(student, plan, id);
    return { done: false, text: planned ? `Planned, ${termLabel(planned)}` : "Not in your plan" };
  };

  return (
    <div className="flex h-full min-h-0 flex-col gap-2" onKeyDown={(e) => e.key === "Escape" && setSelected(null)}>
      <p className="shrink-0 text-sm text-ink-muted" aria-live="polite">
        {selected ? (
          <>
            <b className="text-ink">{courseCode(selected)}</b>
            {ancestors.size ? ` needs ${[...ancestors].sort().map(courseCode).join(", ")} first` : " has no prerequisites"}
            {unlocked.size ? ` and unlocks ${[...unlocked].map(courseCode).join(", ")}.` : " and doesn't unlock another course here."}
            {" "}Click it again or empty space to clear.
          </>
        ) : (
          "Click a course to see what it needs and what it unlocks. Earlier courses are on the left."
        )}
      </p>
      <div className="min-h-0 flex-1 overflow-auto rounded-lg border border-grid bg-white" onClick={clearOnEmpty}>
        <div ref={content} className="relative flex min-h-full min-w-max gap-12 p-4">
          <svg className="pointer-events-none absolute inset-0 size-full overflow-visible" aria-hidden>
            <defs>
              <marker id="prereq-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
                <path d="M0 0 L8 4 L0 8 z" fill="var(--brand)" />
              </marker>
            </defs>
            {edges.map(([from, to]) => {
              const a = rects[from];
              const b = rects[to];
              if (!a || !b) return null;
              const x1 = a.right;
              const x2 = b.left - 2;
              const bend = Math.max(24, (x2 - x1) / 2);
              return (
                <path
                  key={`${from}>${to}`}
                  data-edge={`${from}>${to}`}
                  d={`M ${x1} ${a.mid} C ${x1 + bend} ${a.mid}, ${x2 - bend} ${b.mid}, ${x2} ${b.mid}`}
                  fill="none"
                  stroke="var(--brand)"
                  strokeWidth={1.75}
                  markerEnd="url(#prereq-arrow)"
                />
              );
            })}
          </svg>

          {graph.columns.map((column, c) => (
            <section key={c} className="flex w-44 flex-col" aria-label={c === 0 ? "No prerequisites" : `Step ${c + 1}`}>
              <h3 className="mb-2 text-xs font-bold tracking-wide text-ink-muted uppercase">{c === 0 ? "Start here" : `Step ${c + 1}`}</h3>
              <div className="flex flex-1 flex-col justify-center gap-2.5">
                {column.map((id) => {
                  const s = status(id);
                  const role = id === selected ? "selected" : ancestors.has(id) ? "prereq" : unlocked.has(id) ? "unlocks" : selected ? "dim" : "idle";
                  return (
                    <button
                      key={id}
                      type="button"
                      data-course={id}
                      data-role={role}
                      ref={(el) => {
                        if (el) nodes.current.set(id, el);
                        else nodes.current.delete(id);
                      }}
                      onClick={() => toggle(id)}
                      aria-pressed={id === selected}
                      className={cn(
                        "relative z-10 flex w-full items-stretch overflow-hidden rounded-md border bg-white text-left transition-opacity",
                        role === "selected" && "border-brand bg-brand-soft ring-2 ring-brand",
                        (role === "prereq" || role === "unlocks") && "border-brand",
                        role === "dim" && "opacity-30",
                        (role === "idle" || role === "dim") && "border-grid hover:border-brand-mid",
                      )}
                    >
                      <span aria-hidden className="w-1 shrink-0" style={{ background: KIND_COLOR[kindOf(student, plan, id)] }} />
                      <span className="min-w-0 flex-1 px-2 py-1.5">
                        <span className="flex items-center gap-1">
                          <span className="font-heading text-sm font-bold">{courseCode(id)}</span>
                          {s.done && <Check className="size-3.5 text-brand" aria-label="Done" />}
                          {role === "prereq" && <span className="ml-auto text-[0.65rem] font-bold text-brand uppercase">Needed</span>}
                          {role === "unlocks" && <span className="ml-auto text-[0.65rem] font-bold text-brand uppercase">Unlocks</span>}
                        </span>
                        <span className="block truncate text-xs text-ink-muted">{COURSES[id].title}</span>
                        <span className="block truncate text-[0.7rem] text-ink-muted">{s.text}</span>
                      </span>
                    </button>
                  );
                })}
              </div>
            </section>
          ))}
        </div>
      </div>
      <p className="shrink-0 text-xs text-ink-muted">
        Built from the demo program&apos;s prerequisite data. Courses that neither need nor unlock another course aren&apos;t shown.
      </p>
    </div>
  );
}
