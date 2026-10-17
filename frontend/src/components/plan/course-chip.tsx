"use client";

import type { DragEvent } from "react";

import { Lock, Plus } from "lucide-react";

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { RequirementKind } from "@/lib/demo/program";
import { courseCode } from "@/lib/rules";
import { KIND_COLOR, KIND_LABEL } from "@/lib/ui";
import { cn } from "@/lib/utils";

interface CourseChipProps {
  courseId: string;
  title: string;
  credits: number;
  kind: RequirementKind;
  /** Hover details: what it counts toward and unlocks. */
  why: string;
  lockedReason?: string;
  placed?: boolean;
  onOpen: () => void;
  onAdd: () => void;
}

/** Compact "Recommended classes" chip: code and short title; details on hover, full details in the drawer. */
export function CourseChip({ courseId, title, credits, kind, why, lockedReason, placed, onOpen, onAdd }: CourseChipProps) {
  const canDrag = !lockedReason && !placed;
  const onDragStart = (event: DragEvent) => {
    event.dataTransfer.setData("text/plain", courseId);
    event.dataTransfer.effectAllowed = "move";
  };
  return (
    <div
      draggable={canDrag}
      onDragStart={canDrag ? onDragStart : undefined}
      className={cn(
        "group flex h-9 items-center gap-1.5 overflow-hidden rounded-md border bg-white pr-1 pl-0",
        placed ? "border-brand bg-brand-soft" : "border-grid",
        canDrag && "cursor-grab active:cursor-grabbing",
        lockedReason && "bg-paper",
      )}
    >
      <span aria-hidden className="h-full w-1 shrink-0" style={{ background: lockedReason ? "var(--line)" : KIND_COLOR[kind] }} />
      <Tooltip>
        <TooltipTrigger asChild>
          <button type="button" onClick={onOpen} className="flex min-w-0 flex-1 items-baseline gap-1.5 text-left">
            <span className="font-heading text-[0.82rem] font-bold whitespace-nowrap">{courseCode(courseId)}</span>
            <span className="truncate text-xs text-ink-muted">{title}</span>
          </button>
        </TooltipTrigger>
        <TooltipContent side="left" className="max-w-64">
          <p className="font-bold">{courseCode(courseId)} {title}</p>
          <p>{credits} credits, {KIND_LABEL[kind].toLowerCase()}</p>
          <p>{lockedReason ?? (placed ? "On your calendar." : why)}</p>
        </TooltipContent>
      </Tooltip>
      {lockedReason ? (
        <Lock className="mr-1 size-3.5 shrink-0 text-ink-muted" aria-label={lockedReason} />
      ) : placed ? null : (
        <button
          type="button"
          onClick={onAdd}
          aria-label={`Add ${courseCode(courseId)}`}
          className="grid size-6 shrink-0 place-items-center rounded text-brand hover:bg-brand-soft"
        >
          <Plus className="size-4" />
        </button>
      )}
    </div>
  );
}
