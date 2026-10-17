"use client";

import type { DragEvent, ReactNode } from "react";

import { Lock } from "lucide-react";

import { Button } from "@/components/ui/button";
import type { RequirementKind } from "@/lib/demo/program";
import { courseCode } from "@/lib/rules";
import { KIND_COLOR, KIND_LABEL } from "@/lib/ui";
import { cn } from "@/lib/utils";

export type CourseBadge = "Prereq missing" | "Time conflict" | "Not offered this term";

interface CourseCardProps {
  courseId: string;
  title: string;
  credits: number;
  kind: RequirementKind;
  why?: string;
  badges?: CourseBadge[];
  /** Shown instead of the Add button when the course can't be added, e.g. "Take CSE 109 first." */
  lockedReason?: string;
  onAdd?: () => void;
  addLabel?: string;
  onOpen?: () => void;
  draggable?: boolean;
  dragPayload?: string;
  children?: ReactNode;
  className?: string;
}

/** The one course card used on every tab: an index-tab row colored by requirement type. */
export function CourseCard({
  courseId,
  title,
  credits,
  kind,
  why,
  badges = [],
  lockedReason,
  onAdd,
  addLabel = "Add",
  onOpen,
  draggable,
  dragPayload,
  children,
  className,
}: CourseCardProps) {
  const canDrag = draggable && !lockedReason;
  const onDragStart = (event: DragEvent) => {
    event.dataTransfer.setData("text/plain", dragPayload ?? courseId);
    event.dataTransfer.effectAllowed = "move";
  };
  return (
    <div
      draggable={canDrag}
      onDragStart={canDrag ? onDragStart : undefined}
      className={cn(
        "group relative flex gap-3 border-b border-grid bg-white py-2.5 pr-2.5 pl-4 last:border-b-0",
        canDrag && "cursor-grab active:cursor-grabbing",
        lockedReason && "bg-paper",
        className,
      )}
    >
      <span
        aria-hidden
        className="absolute inset-y-1.5 left-0 w-1.5 rounded-r-sm"
        style={{ background: lockedReason ? "var(--grid)" : KIND_COLOR[kind] }}
      />
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <button
            type="button"
            onClick={onOpen}
            disabled={!onOpen}
            className="shrink-0 whitespace-nowrap font-heading text-[0.95rem] font-semibold tracking-tight text-ink [font-stretch:85%] hover:underline disabled:no-underline"
          >
            {courseCode(courseId)}
          </button>
          <span className="min-w-0 truncate text-sm text-ink">{title}</span>
          <span className="tabular ml-auto shrink-0 text-xs text-muted-foreground">{credits} cr</span>
        </div>
        <p className="mt-0.5 text-xs text-muted-foreground">
          <span className="sr-only">{KIND_LABEL[kind]}. </span>
          {lockedReason ? (
            <span className="inline-flex items-center gap-1 text-ink">
              <Lock className="size-3" aria-hidden /> {lockedReason}
            </span>
          ) : (
            why
          )}
        </p>
        {badges.length > 0 && (
          <div className="mt-1 flex flex-wrap gap-1">
            {badges.map((b) => (
              <span
                key={b}
                className={cn(
                  "rounded-sm px-1.5 py-px text-[0.7rem] font-bold",
                  b === "Time conflict" ? "bg-signal text-white" : "bg-accent text-ink",
                )}
              >
                {b}
              </span>
            ))}
          </div>
        )}
        {children}
      </div>
      {onAdd && !lockedReason && (
        <Button size="sm" variant="outline" className="self-center" onClick={onAdd} aria-label={`${addLabel} ${courseCode(courseId)}`}>
          {addLabel}
        </Button>
      )}
    </div>
  );
}
