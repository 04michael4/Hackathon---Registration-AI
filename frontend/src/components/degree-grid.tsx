"use client";

import { useState, type DragEvent, type ReactNode } from "react";

import { Check, Lock, MessageSquare, Plus, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { COURSES, NEXT_TERM, PROGRAM, type StudentAccount, TERMS } from "@/lib/demo/program";
import { courseCode, creditsOf, type DegreePlan, isSlot, kindOf, planProblems, slotRequirement, termIndex, whyLine } from "@/lib/rules";
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
}

export function DegreeGrid({ student, plan, onMove, onRemove, onFillSlot, comments = [], onComment, highlight }: DegreeGridProps) {
  const [dragOver, setDragOver] = useState<string | null>(null);
  const { errors, warnings } = planProblems(student, plan);
  const editable = !!onMove;

  const commentCount = (target: string) => comments.filter((c) => c.target === target).length;

  const item = (id: string, termId: string, locked: boolean): ReactNode => {
    const target = isSlot(id) ? `term:${termId}` : `course:${id}`;
    const n = isSlot(id) ? 0 : commentCount(target);
    if (isSlot(id)) {
      const requirement = PROGRAM.requirements.find((r) => r.id === slotRequirement(id));
      return (
        <li key={id}>
          <div
            draggable={editable}
            onDragStart={(e) => e.dataTransfer.setData("text/plain", JSON.stringify({ id, from: termId }))}
            className="flex items-center gap-2 rounded-[3px] border border-dashed border-ink/40 bg-white px-2 py-1.5"
          >
            <span className="min-w-0 flex-1">
              <span className="block text-sm">{requirement?.label ?? "Elective"}</span>
              <span className="block text-xs text-muted-foreground">Choose a course</span>
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
    return (
      <li key={id}>
        <div
          draggable={editable && !locked}
          onDragStart={(e) => e.dataTransfer.setData("text/plain", JSON.stringify({ id, from: termId }))}
          className={cn(
            "group relative rounded-[3px] border border-grid bg-white py-1.5 pr-1.5 pl-3",
            editable && !locked && "cursor-grab active:cursor-grabbing",
            locked && "bg-paper",
            highlight === target && "ring-2 ring-marker",
          )}
        >
          <span aria-hidden className="absolute inset-y-1 left-0 w-1 rounded-r-sm" style={{ background: KIND_COLOR[kind] }} />
          <div className="flex items-baseline gap-1.5">
            <span className="font-heading text-sm font-semibold whitespace-nowrap [font-stretch:85%]">{courseCode(id)}</span>
            <span className="tabular ml-auto text-xs text-muted-foreground">{COURSES[id]?.credits ?? 3}</span>
            {locked ? (
              <Check className="size-3.5 text-req-elective" aria-label="Completed or in progress" />
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
          <p className="truncate text-xs text-muted-foreground" title={whyLine(student, plan, id)}>{COURSES[id]?.title}</p>
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
          const history = student.transcript[term.id] ?? [];
          const items = locked ? history : (plan[term.id] ?? []);
          const credits = creditsOf(items);
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
                "flex flex-col rounded-sm border bg-white/60",
                isNext ? "border-ink" : "border-grid",
                dragOver === term.id && "bg-accent/50 outline-2 outline-dashed outline-ink",
                highlight === termTarget && "ring-2 ring-marker",
              )}
            >
              <header className={cn("border-b px-2 py-1.5", isNext ? "border-ink bg-ink text-white" : "border-grid")}>
                <h3 className="flex items-center gap-1 font-heading text-sm font-semibold [font-stretch:85%]">
                  {locked && <Lock className="size-3" aria-hidden />}
                  {term.label}
                </h3>
                <p className={cn("tabular text-xs", isNext ? "text-white/80" : "text-muted-foreground")}>
                  {credits} credits{locked ? (termIndex(term.id) < termIndex("F2026") ? ", completed" : ", in progress") : isNext ? ", next semester" : ""}
                </p>
              </header>
              <ul className="flex-1 space-y-1.5 p-1.5">
                {items.length === 0 && <li className="px-1 py-3 text-xs text-muted-foreground">Drag a course here.</li>}
                {items.map((id) => item(id, term.id, locked))}
              </ul>
              {(termErrors.length > 0 || termWarnings.length > 0 || onComment || commentCount(termTarget) > 0) && (
                <footer className="space-y-1 border-t border-grid p-1.5 text-xs">
                  {termErrors.map((e) => <p key={e.message} className="font-bold text-signal">{e.message}</p>)}
                  {termWarnings.map((w) => <p key={w.message} className="text-[#8a5a0e]">{w.message}</p>)}
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
