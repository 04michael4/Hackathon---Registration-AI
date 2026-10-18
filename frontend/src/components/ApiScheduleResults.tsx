"use client";
import { useRef, type ReactNode } from "react";
import { DAYS, type GenerateResponse, type Schedule, type ScheduleSection } from "@/lib/api/types";

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
export function ApiWeekCalendar({ sections, onDrop, onRemove, onOpenCourse, toolbar, totalCredits = 0 }: Readonly<{ sections: ScheduleSection[]; onDrop?: (crn: string) => void; onRemove?: (crn: string) => void; onOpenCourse?: (courseId: string) => void; toolbar?: ReactNode; totalCredits?: number }>) {
  const dialog = useRef<HTMLDialogElement>(null);
  const minutes = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
  const meetings = sections.flatMap(section => section.meetings.flatMap((meeting, index) => meeting.days.map(day => ({ section, meeting, day, index }))));
  const earliest = meetings.length ? Math.min(8 * 60, ...meetings.map(m => minutes(m.meeting.start))) : 8 * 60;
  const latest = meetings.length ? Math.max(18 * 60, ...meetings.map(m => minutes(m.meeting.end))) : 18 * 60;
  const start = Math.floor(earliest / 60) * 60, end = Math.min(1440, Math.ceil(latest / 60) * 60);
  const visibleDays = meetings.some(m => m.day === "SATURDAY" || m.day === "SUNDAY") ? DAYS : DAYS.slice(0, 5);
  const grid = (days: readonly typeof DAYS[number][]) => <div className="api-calendar-sheet" ref={node => node?.style.setProperty("--calendar-days", String(days.length))}><div className="api-calendar-days"><span></span>{days.map(day => <strong key={day}>{day}</strong>)}</div>
    <div className="api-calendar-grid" ref={node => { node?.style.setProperty("--calendar-hours", String((end - start) / 60)); }}>
      {Array.from({ length: (end - start) / 60 }, (_, i) => <div className="api-calendar-hour" key={i} ref={node => node?.style.setProperty("--hour", String(i))}><time>{(start / 60 + i) % 12 || 12}:00 {start / 60 + i < 12 ? "AM" : "PM"}</time></div>)}
      {meetings.map(({ section, meeting, day, index }) => <button type="button" className="api-calendar-event" aria-label={`${section.courseId} section ${section.sectionCode}, ${day} ${meeting.start} to ${meeting.end}`} onClick={() => onOpenCourse?.(section.courseId)} key={`${section.crn}-${day}-${index}`} data-event-crn={section.crn} ref={node => {
        node?.style.setProperty("--event-day", String(DAYS.indexOf(day)));
        node?.style.setProperty("--event-top", String((minutes(meeting.start) - start) / 60));
        node?.style.setProperty("--event-height", String((minutes(meeting.end) - minutes(meeting.start)) / 60));
      }}><strong>{section.courseId} · {section.sectionCode}</strong><span>{meeting.start}–{meeting.end}</span><small>CRN {section.crn}{meeting.location ? ` · ${meeting.location}` : ""}</small></button>)}
    </div></div>;
  return <section className="api-week" aria-label="Weekly timetable" onDragOver={e => { if (onDrop) e.preventDefault(); }} onDrop={e => { e.preventDefault(); const crn = e.dataTransfer.getData("text/plain"); if (crn.trim() && crn.length <= 100) onDrop?.(crn); }}>
    <div className="manual-schedule-heading"><div className="schedule-title"><h2>My schedule</h2><p className="sem-credit-meter"><strong>{totalCredits}</strong> credits</p></div><div className="schedule-tools">{toolbar}<button type="button" className="button quiet sem-expand-calendar" aria-label="Expand calendar" onClick={() => dialog.current?.showModal()}>View bigger ↗</button></div></div>
    <div className="api-calendar-scroll">{grid(visibleDays)}</div>
    {!sections.length && <p>Generate a schedule or add sections from course details to see your week.</p>}
    <ul className="api-section-list">{sections.map(section => <li key={section.crn}><span><strong>{section.courseId} · {section.sectionCode}</strong> — {section.title} · CRN {section.crn} · {section.credits} credits{!section.meetings.length && <em> · TBA / arranged / asynchronous — no scheduled meetings provided</em>}</span>{onRemove && <button type="button" onClick={() => onRemove(section.crn)} aria-label={`Remove CRN ${section.crn}`}>Remove</button>}</li>)}</ul>
    <dialog className="api-calendar-dialog" ref={dialog}><button type="button" className="button" onClick={() => dialog.current?.close()}>Close expanded calendar</button><div className="api-calendar-scroll">{grid(DAYS)}</div></dialog>
  </section>;
}
