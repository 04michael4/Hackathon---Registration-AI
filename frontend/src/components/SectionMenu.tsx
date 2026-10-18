"use client";
import { useEffect, useLayoutEffect, useRef, type KeyboardEvent } from "react";
import type { ScheduleSection } from "@/lib/api/types";
import type { SectionDifficulty } from "@/lib/difficulty";
import type { SwapOption } from "@/lib/sectionPackages";
import { DifficultyBadge } from "./ApiScheduleResults";

const when = (meetings: { days: readonly string[]; start: string; end: string }[]) =>
  meetings.length ? meetings.map(m => `${m.days.map(d => d.charAt(0) + d.slice(1, 3).toLowerCase()).join("/")} ${m.start}–${m.end}`).join(", ") : "TBA";

/** Right-click menu of a placed section: swap to another section of the same course in one click, open its details, or remove it. */
export function SectionMenu({ section, x, y, options, loading, difficultyOf, onSwap, onPreview, onDetails, onRemove, onClose }: Readonly<{
  section: ScheduleSection; x: number; y: number; options: SwapOption[]; loading: boolean; difficultyOf: (crn: string) => SectionDifficulty | null;
  onSwap: (option: SwapOption) => void; onPreview?: (option: SwapOption | null) => void; onDetails: () => void; onRemove: () => void; onClose: () => void;
}>) {
  const menu = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const node = menu.current;
    if (!node) return;
    const box = node.getBoundingClientRect();
    node.style.setProperty("--menu-left", `${Math.max(8, Math.min(x, window.innerWidth - box.width - 8))}px`);
    node.style.setProperty("--menu-top", `${Math.max(8, Math.min(y, window.innerHeight - box.height - 8))}px`);
  }, [x, y, options.length, loading]);
  useEffect(() => {
    menu.current?.querySelector<HTMLButtonElement>("[role=menuitem]:not([disabled])")?.focus();
    const outside = (event: PointerEvent) => { if (!(event.target instanceof Node && menu.current?.contains(event.target))) onClose(); };
    const away = () => onClose();
    document.addEventListener("pointerdown", outside); window.addEventListener("resize", away); window.addEventListener("scroll", away, true);
    return () => { document.removeEventListener("pointerdown", outside); window.removeEventListener("resize", away); window.removeEventListener("scroll", away, true); };
  }, [onClose, loading]);
  const keys = (event: KeyboardEvent<HTMLDivElement>) => {
    const items = [...(menu.current?.querySelectorAll<HTMLButtonElement>("[role=menuitem]:not([disabled])") ?? [])];
    const at = items.indexOf(document.activeElement as HTMLButtonElement);
    if (event.key === "Escape" || event.key === "Tab") { event.preventDefault(); onClose(); }
    else if (event.key === "ArrowDown" || event.key === "ArrowUp") { event.preventDefault(); items[(at + (event.key === "ArrowDown" ? 1 : items.length - 1)) % items.length]?.focus(); }
    else if (event.key === "Home" || event.key === "End") { event.preventDefault(); items[event.key === "Home" ? 0 : items.length - 1]?.focus(); }
  };
  return <div className="section-menu" role="menu" aria-label={`Swap ${section.courseId} section ${section.sectionCode}`} ref={menu} onKeyDown={keys}>
    <p className="section-menu-title">Swap {section.courseId} · {section.sectionCode}</p>
    <p className="section-menu-note">Point at an option to see it outlined on the calendar.</p>
    {loading && <p className="section-menu-note" role="status">Loading sections…</p>}
    {!loading && !options.length && <p className="section-menu-note">No other section of this course is linked to choose from.</p>}
    {options.map(option => <button type="button" role="menuitem" key={option.key} className="section-menu-item" onClick={() => onSwap(option)}
      onPointerEnter={() => onPreview?.(option)} onPointerLeave={() => onPreview?.(null)} onFocus={() => onPreview?.(option)} onBlur={() => onPreview?.(null)}>
      <strong>{option.label}</strong>
      {option.sections.map(s => <span key={s.crn}>{s.componentType.charAt(0) + s.componentType.slice(1).toLowerCase()} {s.sectionCode} · {when(s.meetings)}{s.instructors.length ? ` · ${s.instructors.join(", ")}` : ""}{s.status === "CLOSED" ? " · Closed" : ""}</span>)}
      <span className="section-menu-meta"><DifficultyBadge difficulty={difficultyOf(option.sections[0].crn)} />{option.overlaps && <em>Overlaps another course</em>}</span>
    </button>)}
    <hr />
    <button type="button" role="menuitem" className="section-menu-item is-plain" onClick={onDetails}>Course details</button>
    <button type="button" role="menuitem" className="section-menu-item is-plain is-danger" onClick={onRemove}>Remove {section.courseId} from the calendar</button>
  </div>;
}
