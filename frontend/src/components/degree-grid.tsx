"use client";

import { useState, type DragEvent, type ReactNode } from "react";

import { Check, Lock, MessageSquare, Plus, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { COURSES, CURRENT_TERM, NEXT_TERM, PROGRAM, type StudentAccount, TERMS } from "@/lib/demo/program";
import { courseCode, type CourseStatus, creditsOf, type DegreePlan, isSlot, kindOf, planItems, planProblems, slotRequirement, termIndex, whyLine } from "@/lib/rules";
import type { Comment } from "@/lib/store";
import { KIND_COLOR } from "@/lib/ui";
import { cn } from "@/lib/utils";

interface DegreeGridProps {
  student: StudentAccount;
  plan: DegreePlan;
  /** Omit for read-only review. */
  onMove?: (item: string, fromTerm: string, toTerm: string) => void;
  onRemove?: (item: string, term: string) => void;
  onFillSlot?: (item: string, term: string) => void;
  /** Advisor review: comments pinned to a course or a term. */
  comments?: Comment[];
  onComment?: (target: string, label: string) => void;
  highlight?: string | null;
  /** Advisor filter: show only these course statuses. Empty or omitted shows everything. */
  statusFilter?: CourseStatus[];
  /** The plan is waiting on the advisor, so planned courses read as "Review". */
  underReview?: boolean;
}

export function DegreeGrid({ student, plan, onMove, onRemove, onFillSlot, comments = [], onComment, highlight, statusFilter = [], underReview = false }: DegreeGridProps) {
  const [dragOver, setDragOver] = useState<string | null>(null);
  const { errors, warnings } = planProblems(student, plan);
  const editable = !!onMove;
  const all = planItems(student, plan, underReview);
  const shown = (status: CourseStatus) => statusFilter.length === 0 || statusFilter.includes(status);

  const commentCount = (target: string) => comments.filter((c) => c.target === target).length;

  const item = (id: string, termId: string, locked: boolean, status: CourseStatus): ReactNode => {
    const target = isSlot(id) ? `term:${termId}` : `course:${id}`;
    const n = isSlot(id) ? 0 : commentCount(target);
    if (isSlot(id)) {
      const requirement = PROGRAM.requirements.find((r) => r.id === slotRequirement(id));
      return (
        <li key={id} data-status={status}>
          <div
            draggable={editable}
            onDragStart={(e) => e.dataTransfer.setData("text/plain", JSON.stringify({ id, from: termId }))}
            className="flex items-center gap-2 rounded-md border border-dashed border-ink/40 bg-white px-2 py-1.5"
          >
            <span className="min-w-0 flex-1">
              <span className="block text-sm">{requirement?.label ?? "Elective"}</span>
              <span className="block text-xs text-muted-foreground">{status === "Review" ? "Choose a course, in review" : "Choose a course"}</span>
            </span>
            {onFillSlot && (
              <Button size="icon-xs" variant="outline" aria-label={`Choose a ${requirement?.label ?? "course"}`} onClick={() => onFillSlot(id, termId)}>
                <Plus aria-hidden />
              </Button>
            )}
          </div>
        </li>
      );
    }
    const kind = kindOf(student, plan, id);
    const withdrawn = status === "Withdraw";
    return (
      <li key={id} data-status={status}>
        <div
          draggable={editable && !locked}
          onDragStart={(e) => e.dataTransfer.setData("text/plain", JSON.stringify({ id, from: termId }))}
          className={cn(
            "group relative rounded-md border border-grid bg-white py-1.5 pr-1.5 pl-3",
            editable && !locked && "cursor-grab active:cursor-grabbing",
            locked && "bg-paper",
            withdrawn && "border-dashed text-ink-muted",
            highlight === target && "ring-2 ring-marker",
          )}
        >
          <span aria-hidden className="absolute inset-y-1 left-0 w-1 rounded-r-sm" style={{ background: withdrawn ? "var(--line)" : KIND_COLOR[kind] }} />
          <div className="flex items-baseline gap-1.5">
            <span className={cn("font-heading text-sm font-semibold whitespace-nowrap", withdrawn && "line-through")}>{courseCode(id)}</span>
            {withdrawn && <span className="rounded-sm border border-grid px-1 text-[0.65rem] font-bold">W</span>}
            <span className="tabular ml-auto text-xs text-muted-foreground">{withdrawn ? 0 : (COURSES[id]?.credits ?? 3)}</span>
            {withdrawn ? null : locked ? (
              <Check className="size-3.5 text-brand" aria-label={status === "Done" ? "Completed" : "In progress"} />
            ) : (
              onRemove && (
                <button
                  type="button"
                  className="text-muted-foreground opacity-0 group-hover:opacity-100 focus:opacity-100"
                  aria-label={`Remove ${courseCode(id)} from ${termId}`}
                  onClick={() => onRemove(id, termId)}
                >
                  <X className="size-3.5" />
                </button>
              )
            )}
          </div>
          <p className="truncate text-xs text-muted-foreground" title={withdrawn ? "Withdrawn: no credit" : whyLine(student, plan, id)}>
            {status === "Review" && <span className="mr-1 rounded-sm bg-gold px-1 text-[0.65rem] font-bold text-ink">Review</span>}
            {withdrawn ? "Withdrawn" : COURSES[id]?.title}
          </p>
          {(onComment || n > 0) && (
            <button
              type="button"
              disabled={!onComment}
              onClick={() => onComment?.(target, courseCode(id))}
              className={cn("mt-0.5 inline-flex items-center gap-1 text-xs", n ? "text-ink" : "text-muted-foreground opacity-0 group-hover:opacity-100 focus-visible:opacity-100")}
            >
              <MessageSquare className="size-3" aria-hidden />
              {n ? `${n} comment${n > 1 ? "s" : ""}` : "Comment"}
            </button>
          )}
        </div>
      </li>
    );
  };

  return (
    <div className="overflow-x-auto pb-2">
      <div className="grid min-w-[980px] grid-cols-8 gap-1.5">
        {TERMS.map((term) => {
          const locked = student.lockedTerms.includes(term.id);
          const termItems = all.filter((i) => i.termId === term.id);
          const items = termItems.filter((i) => shown(i.status));
          const credits = creditsOf(termItems.filter((i) => i.status !== "Withdraw").map((i) => i.item));
          const termErrors = errors.filter((e) => e.termId === term.id);
          const termWarnings = warnings.filter((w) => w.termId === term.id);
          const isNext = term.id === NEXT_TERM;
          const termTarget = `term:${term.id}`;
          const onDragOver = (e: DragEvent) => {
            if (!editable || locked) return;
            e.preventDefault();
            setDragOver(term.id);
          };
          const onDrop = (e: DragEvent) => {
            e.preventDefault();
            setDragOver(null);
            try {
              const { id, from } = JSON.parse(e.dataTransfer.getData("text/plain")) as { id: string; from: string };
              if (from !== term.id) onMove?.(id, from, term.id);
            } catch {
              // Not one of ours.
            }
          };
          return (
            <section
              key={term.id}
              aria-label={term.label}
              onDragOver={onDragOver}
              onDragLeave={() => setDragOver(null)}
              onDrop={onDrop}
              className={cn(
                "flex flex-col rounded-md border bg-white/60",
                isNext ? "border-ink" : "border-grid",
                dragOver === term.id && "bg-accent/50 outline-2 outline-dashed outline-ink",
                highlight === termTarget && "ring-2 ring-marker",
              )}
            >
              <header className={cn("border-b px-2 py-1.5", isNext ? "border-ink bg-ink text-white" : "border-grid")}>
                <h3 className="flex items-center gap-1 font-heading text-sm font-semibold">
                  {locked && <Lock className="size-3" aria-hidden />}
                  {term.label}
                </h3>
                <p className={cn("tabular text-xs", isNext ? "text-white/80" : "text-muted-foreground")}>
                  {credits} credits{locked ? (termIndex(term.id) < termIndex(CURRENT_TERM) ? ", completed" : ", in progress") : isNext ? ", next semester" : ""}
                </p>
              </header>
              <ul className="flex-1 space-y-1.5 p-1.5">
                {items.length === 0 && (
                  <li className="px-1 py-3 text-xs text-muted-foreground">{termItems.length ? "No courses match the filter." : editable ? "Drag a course here." : "No courses."}</li>
                )}
                {items.map((i) => item(i.item, term.id, locked, i.status))}
              </ul>
              {(termErrors.length > 0 || termWarnings.length > 0 || onComment || commentCount(termTarget) > 0) && (
                <footer className="space-y-1 border-t border-grid p-1.5 text-xs">
                  {termErrors.map((e) => <p key={e.message} className="font-bold text-signal">{e.message}</p>)}
                  {termWarnings.map((w) => <p key={w.message} className="text-caution">{w.message}</p>)}
                  {(onComment || commentCount(termTarget) > 0) && (
                    <button
                      type="button"
                      disabled={!onComment}
                      onClick={() => onComment?.(termTarget, term.label)}
                      className="inline-flex items-center gap-1 text-muted-foreground"
                    >
                      <MessageSquare className="size-3" aria-hidden />
                      {commentCount(termTarget) ? `${commentCount(termTarget)} on this term` : "Comment on term"}
                    </button>
                  )}
                </footer>
              )}
            </section>
          );
        })}
      </div>
    </div>
  );
}
