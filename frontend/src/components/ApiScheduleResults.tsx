"use client";
import { useRef, useState, type DragEvent, type ReactNode } from "react";
import { DAYS, type Day, type GenerateResponse, type Schedule, type ScheduleSection } from "@/lib/api/types";
import type { SectionDifficulty } from "@/lib/difficulty";
import { overlapLanes } from "@/lib/sectionPackages";

export function ScheduleFacts({ schedule }: Readonly<{ schedule: Omit<Schedule, "rank"> }>) {
  return <div className="api-schedule-facts"><strong>{schedule.totalCredits} credits</strong><span>Solver score: {schedule.score} / 100</span><span>{schedule.overload ? "Overload — approval may be required" : "Within the standard 18-credit limit"}</span>
    <details><summary>Score breakdown</summary><dl>{Object.entries(schedule.scoreBreakdown).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl></details>
    <small>First-semester students may need approval above 17 credits. A generated plan does not enroll you.</small></div>;
}
export function ScheduleAlternatives({ result, selected, onSelect }: Readonly<{ result: GenerateResponse; selected: number; onSelect: (index: number) => void }>) {
  return <section aria-label="Generated schedules" className="api-results">
    <h2>Generated alternatives</h2>
    {!result.schedules.length && <p role="status">No feasible schedules were returned. Adjust your required courses, credit range or blocked times and try again.</p>}
    <div className="api-alternatives">{result.schedules.map((schedule, index) => <button key={schedule.rank} type="button" aria-pressed={selected === index} onClick={() => onSelect(index)} data-schedule-rank={schedule.rank}>
      <strong>Alternative {schedule.rank}</strong><span>{schedule.totalCredits} credits · Score {schedule.score}</span><span>{schedule.sections.map(s => `${s.courseId} / ${s.crn}`).join(", ")}</span>{schedule.overload && <span>Overload</span>}
    </button>)}</div>
    {result.searchLimitReached && <p role="status">The search limit was reached. These results may not include every feasible option.</p>}
    {result.diagnostics.length > 0 && <ul>{result.diagnostics.map((message, i) => <li key={i}>{message}</li>)}</ul>}
    {result.electiveAdvice.map(advice => <details key={advice.courseId}><summary>{advice.courseId}: {advice.summary}</summary><p>{advice.status.replaceAll("_", " ")}</p><ul>{advice.fixes.map(fix => <li key={fix}>{fix}</li>)}</ul>{advice.alternativeCrns.length > 0 && <p>Alternative CRNs: {advice.alternativeCrns.join(", ")}</p>}</details>)}
  </section>;
}
/** Drag payload types: a course card dropped on the calendar, and a placed section being moved to another section. */
export const COURSE_DRAG_TYPE = "application/x-syllo-course";
const SECTION_DRAG_TYPE = "application/x-syllo-section";
/**
 * A dashed box on the calendar showing where a section would go. `droppable` boxes exist only while dragging and accept the drop;
 * the others are previews (hovering a course card, a placed class or a swap option) and ignore the pointer.
 */
export interface CalendarGhost { key: string; courseId: string; crn: string; sectionCode: string; componentType: string; meetings: { days: string[]; start: string; end: string }[]; crns: string[]; instructors: string[]; difficulty: SectionDifficulty | null; droppable: boolean; note?: string; overlaps?: string[] }

/** The predicted difficulty as a bare number on the 1-10 scale, colored by band; the full wording stays available to screen readers. */
export function DifficultyBadge({ difficulty }: Readonly<{ difficulty: SectionDifficulty | null }>) {
  return difficulty ? <span className={`difficulty-badge is-${difficulty.band}`} title={`Predicted difficulty ${difficulty.score.toFixed(1)} of 10 (${difficulty.label.toLowerCase()})`}>
    <span aria-hidden="true">{difficulty.score.toFixed(1)}</span><span className="syllo-sr-only">Predicted difficulty {difficulty.score.toFixed(1)} of 10, {difficulty.label.toLowerCase()}</span></span>
    : <span className="difficulty-badge is-none" title="No difficulty rating available"><span aria-hidden="true">N/A</span><span className="syllo-sr-only">No difficulty rating available</span></span>;
}

export function ApiWeekCalendar({ sections, onDrop, onCourseDrop, onRemove, onRemoveCourse, onOpenCourse, onSectionDragStart, onSectionDragEnd, onSectionMenu, onSectionHover, difficultyFor, ghosts = [], fadedCrns = [], onGhostDrop, onGhostEnter, toolbar, totalCredits = 0 }: Readonly<{ sections: ScheduleSection[]; onDrop?: (crn: string) => void; onCourseDrop?: (courseId: string) => void; onRemove?: (crn: string) => void; onRemoveCourse?: (section: ScheduleSection) => void; onOpenCourse?: (courseId: string) => void; onSectionDragStart?: (section: ScheduleSection) => void; onSectionDragEnd?: () => void; onSectionMenu?: (section: ScheduleSection, x: number, y: number) => void; onSectionHover?: (section: ScheduleSection | null) => void; difficultyFor?: (section: ScheduleSection) => SectionDifficulty | null; ghosts?: CalendarGhost[]; fadedCrns?: string[]; onGhostDrop?: (ghost: CalendarGhost) => void; onGhostEnter?: (ghost: CalendarGhost) => void; toolbar?: ReactNode; totalCredits?: number }>) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [dropping, setDropping] = useState(false);
  const minutes = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
  const meetings = sections.flatMap(section => section.meetings.flatMap((meeting, index) => meeting.days.map(day => ({ section, meeting, day, index }))));
  const ghostMeetings = ghosts.flatMap(ghost => ghost.meetings.flatMap((meeting, index) => meeting.days.filter((day): day is Day => (DAYS as readonly string[]).includes(day)).map(day => ({ ghost, meeting, day, index }))));
  const times = [...meetings.map(m => m.meeting), ...ghostMeetings.map(m => m.meeting)];
  const earliest = times.length ? Math.min(8 * 60, ...times.map(m => minutes(m.start))) : 8 * 60;
  const latest = times.length ? Math.max(18 * 60, ...times.map(m => minutes(m.end))) : 18 * 60;
  const start = Math.floor(earliest / 60) * 60, end = Math.min(1440, Math.ceil(latest / 60) * 60);
  const visibleDays = [...meetings, ...ghostMeetings].some(m => m.day === "SATURDAY" || m.day === "SUNDAY") ? DAYS : DAYS.slice(0, 5);
  const place = (day: Day, meeting: { start: string; end: string }, lane = 0, lanes = 1) => (node: HTMLElement | null) => {
    node?.style.setProperty("--event-day", String(DAYS.indexOf(day)));
    node?.style.setProperty("--event-top", String((minutes(meeting.start) - start) / 60));
    node?.style.setProperty("--event-height", String((minutes(meeting.end) - minutes(meeting.start)) / 60));
    node?.style.setProperty("--lane", String(lane));
    node?.style.setProperty("--lanes", String(lanes));
  };
  const ghostLanes = overlapLanes(ghostMeetings, g => g.day, g => minutes(g.meeting.start), g => minutes(g.meeting.end));
  const grid = (days: readonly Day[], interactive: boolean) => <div className="api-calendar-sheet" ref={node => node?.style.setProperty("--calendar-days", String(days.length))}><div className="api-calendar-days"><span></span>{days.map(day => <strong key={day}>{day}</strong>)}</div>
    <div className="api-calendar-grid" ref={node => { node?.style.setProperty("--calendar-hours", String((end - start) / 60)); }}>
      {Array.from({ length: (end - start) / 60 }, (_, i) => <div className="api-calendar-hour" key={i} ref={node => node?.style.setProperty("--hour", String(i))}><time>{(start / 60 + i) % 12 || 12}:00 {start / 60 + i < 12 ? "AM" : "PM"}</time></div>)}
      {meetings.map(({ section, meeting, day, index }) => {
        const difficulty = difficultyFor?.(section) ?? null;
        const name = `${section.courseId} section ${section.sectionCode}, ${day} ${meeting.start} to ${meeting.end}`;
        const short = minutes(meeting.end) - minutes(meeting.start) < 60;
        return <div className={`api-calendar-event${difficultyFor ? ` difficulty-${difficulty?.band ?? "none"}` : ""}${short ? " is-short" : ""}${interactive && fadedCrns.includes(section.crn) ? " is-replaced" : ""}`} key={`${section.crn}-${day}-${index}`} data-event-crn={section.crn} ref={place(day, meeting)}
          onPointerEnter={() => { if (interactive) onSectionHover?.(section); }} onPointerLeave={() => { if (interactive) onSectionHover?.(null); }}
          draggable={interactive && Boolean(onSectionDragStart)} title={interactive && onSectionDragStart ? "Drag to a highlighted slot, or right-click to swap sections" : undefined}
          onDragStart={e => { e.dataTransfer.setData(SECTION_DRAG_TYPE, section.crn); e.dataTransfer.effectAllowed = "move"; onSectionDragStart?.(section); }} onDragEnd={() => onSectionDragEnd?.()}
          onContextMenu={e => { if (!interactive || !onSectionMenu) return; e.preventDefault(); const box = e.currentTarget.getBoundingClientRect(); onSectionMenu(section, e.clientX || box.left + 12, e.clientY || box.top + 12); }}>
          <button type="button" className="api-event-open" aria-label={`${name}. Open course details`} onClick={() => onOpenCourse?.(section.courseId)}
            onFocus={() => { if (interactive) onSectionHover?.(section); }} onBlur={() => { if (interactive) onSectionHover?.(null); }}
            onKeyDown={e => { if (interactive && onSectionMenu && (e.key === "ContextMenu" || (e.shiftKey && e.key === "F10"))) { e.preventDefault(); const box = e.currentTarget.getBoundingClientRect(); onSectionMenu(section, box.left + 12, box.top + 12); } }}>
            <strong>{section.courseId} · {section.sectionCode}</strong> <span>{meeting.start}–{meeting.end}</span><small>CRN {section.crn}{meeting.location ? ` · ${meeting.location}` : ""}</small>
          </button>
          {interactive && onRemoveCourse && <button type="button" className="api-event-remove" aria-label={`Remove ${section.courseId} from the calendar`} title={`Remove ${section.courseId}`} onClick={() => onRemoveCourse(section)}>×</button>}
          {difficultyFor && <DifficultyBadge difficulty={difficulty} />}
        </div>;
      })}
      {interactive && ghostLanes.map(({ item: { ghost, meeting, day, index }, lane, lanes }) => <div className={`api-calendar-ghost ${ghost.droppable ? "is-drop" : "is-preview"} difficulty-${ghost.difficulty?.band ?? "none"}${ghost.overlaps?.length ? " has-conflict" : ""}${minutes(meeting.end) - minutes(meeting.start) < 60 ? " is-short" : ""}`} key={`${ghost.key}-${day}-${index}`} data-ghost-crns={ghost.crns.join(" ")} ref={place(day, meeting, lane, lanes)}
        onDragEnter={() => { if (ghost.droppable) onGhostEnter?.(ghost); }}
        onDragOver={e => { if (ghost.droppable && (e.dataTransfer.types.includes(SECTION_DRAG_TYPE) || e.dataTransfer.types.includes(COURSE_DRAG_TYPE))) { e.preventDefault(); e.dataTransfer.dropEffect = "move"; } }}
        onDrop={e => { if (!ghost.droppable) return; e.preventDefault(); e.stopPropagation(); setDropping(false); onGhostDrop?.(ghost); }}>
        <div className="api-ghost-text"><strong>{ghost.componentType.charAt(0)}{ghost.componentType.slice(1).toLowerCase()} {ghost.sectionCode}</strong> <span>{meeting.start}–{meeting.end}</span>
          {ghost.overlaps && ghost.overlaps.length > 0 && <small className="api-ghost-conflict">Overlaps {ghost.overlaps.join(", ")}</small>}{ghost.note && <small className="api-ghost-note">{ghost.note}</small>}
          {ghost.instructors.length > 0 && <small className="api-ghost-people">{ghost.instructors.join(", ")}</small>}</div><DifficultyBadge difficulty={ghost.difficulty} /></div>)}
    </div></div>;
  const accepts = (e: DragEvent) => (onCourseDrop && e.dataTransfer.types.includes(COURSE_DRAG_TYPE)) || (onDrop && e.dataTransfer.types.includes("text/plain") && !e.dataTransfer.types.includes(SECTION_DRAG_TYPE));
  return <section className={`api-week${dropping ? " is-drop-target" : ""}`} aria-label="Weekly timetable"
    onDragOver={e => { if (accepts(e)) { e.preventDefault(); e.dataTransfer.dropEffect = "copy"; setDropping(true); } }}
    onDragLeave={e => { if (!(e.relatedTarget instanceof Node && e.currentTarget.contains(e.relatedTarget))) setDropping(false); }}
    onDrop={e => {
      setDropping(false);
      if (!accepts(e)) return;
      e.preventDefault();
      const courseId = e.dataTransfer.getData(COURSE_DRAG_TYPE), crn = e.dataTransfer.getData("text/plain");
      if (courseId && courseId.length <= 30) onCourseDrop?.(courseId);
      else if (crn.trim() && crn.length <= 100) onDrop?.(crn);
    }}>
    <div className="manual-schedule-heading"><div className="schedule-title"><h2>My schedule</h2><p className="sem-credit-meter"><strong>{totalCredits}</strong> credits</p></div><div className="schedule-tools">{toolbar}<button type="button" className="button quiet sem-expand-calendar" aria-label="Expand calendar" onClick={() => dialog.current?.showModal()}>View bigger ↗</button></div></div>
    {difficultyFor && <p className="difficulty-legend"><span>Number in each class: predicted difficulty, 1–10, from professor ratings</span><span className="difficulty-badge is-easy">1–3</span><span className="difficulty-badge is-moderate">4–6</span><span className="difficulty-badge is-hard">7–10</span><span className="difficulty-badge is-none">N/A no rating</span><span>Point at a class or course to see where it can go; right-click a class to swap.</span></p>}
    <div className="api-calendar-scroll">{grid(visibleDays, true)}</div>
    {!sections.length && <p>Generate a schedule, drag a course from the course list onto the calendar, or add sections from course details.</p>}
    <ul className="api-section-list">{sections.map(section => <li key={section.crn}><span><strong>{section.courseId} · {section.sectionCode}</strong> — {section.title} · CRN {section.crn} · {section.credits} credits{!section.meetings.length && <em> · TBA / arranged / asynchronous — no scheduled meetings provided</em>}</span>{onRemove && <button type="button" onClick={() => onRemove(section.crn)} aria-label={`Remove CRN ${section.crn}`}>Remove</button>}</li>)}</ul>
    <dialog className="api-calendar-dialog" ref={dialog}><div className="api-dialog-bar"><button type="button" className="api-dialog-close" aria-label="Close expanded calendar" onClick={() => dialog.current?.close()}>×</button></div><div className="api-calendar-scroll">{grid(DAYS, false)}</div></dialog>
  </section>;
}
