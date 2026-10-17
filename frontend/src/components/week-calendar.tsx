"use client";

import { useState, type DragEvent, type ReactNode } from "react";

import type { Day } from "@/lib/api/types";
import { clock, DAY_END, DAY_SHORT, DAY_START, minutes, WEEKDAYS } from "@/lib/ui";
import { cn } from "@/lib/utils";

export type BlockVariant = "placed" | "conflict" | "ghost" | "ghost-highlight" | "ghost-blocked";

export interface CalendarBlock {
  id: string;
  crn: string;
  label: string; // e.g. "CSE 017"
  sub: string; // e.g. "Lab · Demo Hall 108"
  days: Day[];
  start: string;
  end: string;
  color: string;
  variant: BlockVariant;
  /** Hover text, e.g. why a ghost can't be chosen. */
  note?: string;
}

interface WeekCalendarProps {
  blocks: CalendarBlock[];
  hourHeight?: number;
  onBlockClick?: (block: CalendarBlock) => void;
  wrapBlock?: (block: CalendarBlock, node: ReactNode) => ReactNode;
  onDropCourse?: (courseId: string) => void;
  emptyState?: ReactNode;
}

interface Placed {
  block: CalendarBlock;
  day: Day;
  lane: number;
  lanes: number;
}

/** Side-by-side lanes for blocks that overlap in time on the same day. */
function layoutDay(day: Day, blocks: CalendarBlock[]): Placed[] {
  const items = blocks
    .filter((b) => b.days.includes(day))
    .sort((a, b) => minutes(a.start) - minutes(b.start) || a.crn.localeCompare(b.crn));
  const out: Placed[] = [];
  let cluster: Placed[] = [];
  let clusterEnd = -1;
  const flush = () => {
    const lanes = Math.max(1, ...cluster.map((p) => p.lane + 1));
    cluster.forEach((p) => out.push({ ...p, lanes }));
    cluster = [];
  };
  for (const block of items) {
    const start = minutes(block.start);
    if (start >= clusterEnd) {
      flush();
      clusterEnd = -1;
    }
    const used = new Set(cluster.filter((p) => minutes(p.block.end) > start).map((p) => p.lane));
    let lane = 0;
    while (used.has(lane)) lane++;
    cluster.push({ block, day, lane, lanes: 1 });
    clusterEnd = Math.max(clusterEnd, minutes(block.end));
  }
  flush();
  return out;
}

const VARIANT_CLASS: Record<BlockVariant, string> = {
  placed: "border-l-[3px] text-ink shadow-[0_1px_0_var(--grid)]",
  conflict: "border-2 border-signal bg-[#FBE4E1] text-ink",
  ghost: "border border-dashed bg-white/80 text-ink hover:bg-white",
  "ghost-highlight": "border-2 border-dashed border-ink bg-marker/70 text-ink hover:bg-marker",
  "ghost-blocked": "border border-dashed border-grid bg-paper/80 text-muted-foreground cursor-not-allowed",
};

export function WeekCalendar({ blocks, hourHeight = 44, onBlockClick, wrapBlock, onDropCourse, emptyState }: WeekCalendarProps) {
  const [dropping, setDropping] = useState(false);
  const hours = Array.from({ length: (DAY_END - DAY_START) / 60 }, (_, i) => DAY_START / 60 + i);
  const height = ((DAY_END - DAY_START) / 60) * hourHeight;

  const onDragOver = (event: DragEvent) => {
    if (!onDropCourse) return;
    event.preventDefault();
    setDropping(true);
  };
  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDropping(false);
    const id = event.dataTransfer.getData("text/plain");
    if (id && onDropCourse) onDropCourse(id);
  };

  return (
    <div
      className={cn("relative rounded-sm border border-grid bg-white", dropping && "outline-2 outline-dashed outline-ink")}
      onDragOver={onDragOver}
      onDragLeave={() => setDropping(false)}
      onDrop={onDrop}
    >
      <div className="sticky top-0 z-20 grid grid-cols-[3.25rem_repeat(5,1fr)] border-b border-grid bg-white">
        <span />
        {WEEKDAYS.map((d) => (
          <span key={d} className="border-l border-grid py-1.5 text-center font-heading text-sm font-semibold [font-stretch:85%]">
            {DAY_SHORT[d]}
          </span>
        ))}
      </div>
      <div className="grid grid-cols-[3.25rem_repeat(5,1fr)]" style={{ height }}>
        <div className="relative">
          {hours.map((h) => (
            <span
              key={h}
              className="tabular absolute right-1.5 -translate-y-1/2 text-[0.7rem] text-muted-foreground"
              style={{ top: (h - DAY_START / 60) * hourHeight }}
            >
              {h > DAY_START / 60 ? clock(`${h}:00`) : ""}
            </span>
          ))}
        </div>
        {WEEKDAYS.map((day) => (
          <div key={day} className="graph-paper relative border-l border-grid" style={{ ["--hour" as string]: `${hourHeight}px` }}>
            {layoutDay(day, blocks).map(({ block, lane, lanes }) => {
              const top = ((minutes(block.start) - DAY_START) / 60) * hourHeight;
              const blockHeight = Math.max(18, ((minutes(block.end) - minutes(block.start)) / 60) * hourHeight - 2);
              const ghost = block.variant.startsWith("ghost");
              const node = (
                <button
                  type="button"
                  key={`${block.id}-${day}`}
                  title={block.note}
                  aria-disabled={block.variant === "ghost-blocked"}
                  aria-label={`${block.label}, ${DAY_SHORT[day]} ${clock(block.start)} to ${clock(block.end)}${block.note ? `. ${block.note}` : ""}`}
                  onClick={() => block.variant !== "ghost-blocked" && onBlockClick?.(block)}
                  className={cn(
                    "absolute overflow-hidden rounded-[3px] px-1.5 py-1 text-left leading-tight transition-colors",
                    VARIANT_CLASS[block.variant],
                    ghost ? "z-10" : "z-0",
                  )}
                  style={{
                    top,
                    height: blockHeight,
                    left: `calc(${(lane / lanes) * 100}% + 2px)`,
                    width: `calc(${100 / lanes}% - 4px)`,
                    borderColor: block.variant === "placed" || block.variant === "ghost" ? block.color : undefined,
                    background: block.variant === "placed" ? `color-mix(in srgb, ${block.color} 13%, white)` : undefined,
                  }}
                >
                  <span className="block font-heading text-[0.8rem] font-semibold [font-stretch:85%]">{block.label}</span>
                  {blockHeight > 40 && <span className="block truncate text-[0.7rem]">{block.sub}</span>}
                </button>
              );
              return wrapBlock ? <div key={`${block.id}-${day}-w`}>{wrapBlock(block, node)}</div> : node;
            })}
          </div>
        ))}
      </div>
      {blocks.length === 0 && emptyState && (
        <div className="pointer-events-none absolute inset-0 top-9 flex items-center justify-center p-6 text-center">
          <div className="pointer-events-auto max-w-xs rounded-sm border border-grid bg-white/95 px-5 py-4 text-sm">{emptyState}</div>
        </div>
      )}
    </div>
  );
}

/** Thumbnail calendar for saved schedules and AI options: colored bars only, no text. */
export function MiniCalendar({ blocks, height = 120 }: { blocks: Pick<CalendarBlock, "days" | "start" | "end" | "color" | "id">[]; height?: number }) {
  const span = DAY_END - DAY_START;
  return (
    <div className="grid grid-cols-5 gap-px rounded-sm border border-grid bg-grid" style={{ height }} aria-hidden>
      {WEEKDAYS.map((day) => (
        <div key={day} className="relative bg-white">
          {blocks
            .filter((b) => b.days.includes(day))
            .map((b) => (
              <span
                key={`${b.id}-${day}`}
                className="absolute inset-x-0.5 rounded-[2px]"
                style={{
                  top: `${((minutes(b.start) - DAY_START) / span) * 100}%`,
                  height: `${((minutes(b.end) - minutes(b.start)) / span) * 100}%`,
                  background: b.color,
                }}
              />
            ))}
        </div>
      ))}
    </div>
  );
}
