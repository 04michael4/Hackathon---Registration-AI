"use client";

import { useMemo, useState } from "react";
import { useSyllo } from "@/context/SylloContext";
import { catalog, completed, nextTermId } from "@/lib/mock-data";
import { conflicts, courseDays, credits, eligibility, formatTime, preferenceIssues, timeRange } from "@/lib/planner";
import type { PlanPreview, Preferences } from "@/lib/types";

const weekdays = ["Mon", "Tue", "Wed", "Thu", "Fri"];
type CalendarStyle = React.CSSProperties & Record<"--day" | "--top" | "--height" | "--sem-lane" | "--sem-lanes", string | number>;

function CourseListItem({ id, type, onAdd, onRemove }: Readonly<{ id: string; type: "recommended" | "planned"; onAdd: (id: string) => void; onRemove: (id: string) => void }>) {
  const { state, setSelectedCourse } = useSyllo();
  const course = catalog[id];
  const added = state.planner.includes(id);
  return <article className={`planner-sidebar-course ${type === "planned" ? "planned" : ""}`} draggable={type !== "planned" && !added} onDragStart={(event) => { event.dataTransfer.setData("application/x-syllo-course", id); event.dataTransfer.effectAllowed = "copy"; }}>
    <span className="sidebar-course-color" /><div><button className="sem-course-link" type="button" onClick={() => setSelectedCourse(id)}><strong>{course.code}</strong><span>{course.name}</span></button></div>
    <div className="sem-card-actions">{type === "planned" ? <button onClick={() => onRemove(id)} aria-label={`Remove ${course.code}`}>×</button> : <button className="course-add-button" onClick={() => onAdd(id)} disabled={added}>{added ? "Added" : "Add"}</button>}</div>
  </article>;
}

export function SemesterPlanner() {
  const { state, navigate, update, undo, canUndo, setSelectedCourse, showToast } = useSyllo();
  const [query, setQuery] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [electivesOnly, setElectivesOnly] = useState(false);
  const [pendingAdd, setPendingAdd] = useState<string | null>(null);
  const [preview, setPreview] = useState<PlanPreview | null>(null);
  const [prompt, setPrompt] = useState("");
  const [saveOpen, setSaveOpen] = useState(false);
  const [saveName, setSaveName] = useState("Spring 2027 · My plan");
  const [fullCalendar, setFullCalendar] = useState(false);
  const [renderedAt] = useState(() => Date.now());
  const registrationOpensAt = new Date("2026-11-02T07:00:00-05:00").getTime();
  const registrationLabel = renderedAt < registrationOpensAt ? `${Math.ceil((registrationOpensAt - renderedAt) / 86400000)} days` : "Open";

  const plannedConflicts = conflicts(state.planner);
  const recommended = Object.keys(catalog).filter((id) => catalog[id].required && !completed.has(id) && eligibility(id, nextTermId).ok);
  const results = useMemo(() => {
    const tokens = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
    return Object.entries(catalog).filter(([, course]) => {
      if (electivesOnly && course.required) return false;
      return tokens.every((token) => [course.code, course.name, course.description, course.requirement, course.section, course.interest].join(" ").toLowerCase().includes(token));
    }).sort(([a], [b]) => Number(eligibility(b, nextTermId).ok) - Number(eligibility(a, nextTermId).ok));
  }, [electivesOnly, query]);

  const remove = (id: string) => {
    update(`Remove ${catalog[id].code}`, (draft) => { draft.planner = draft.planner.filter((courseId) => courseId !== id); });
    setPreview(null);
    showToast(`${catalog[id].code} removed`, "Use Undo to restore it.");
  };

  const add = (id: string, strategy?: "replace" | "both") => {
    if (state.planner.includes(id)) return;
    const access = eligibility(id, nextTermId);
    if (!access.ok) { showToast("Course cannot be added", access.reasons.join(" ")); return; }
    const clashes = conflicts([...state.planner, id]).filter((pair) => pair.includes(id)).flatMap((pair) => pair.filter((courseId) => courseId !== id));
    if (clashes.length && !strategy) { setPendingAdd(id); return; }
    update(`Add ${catalog[id].code}`, (draft) => {
      if (strategy === "replace") draft.planner = draft.planner.filter((courseId) => !clashes.includes(courseId));
      draft.planner.push(id);
    });
    setPendingAdd(null); setSearchOpen(false); setPreview(null);
    showToast(`${catalog[id].code} added`, strategy === "both" ? "The overlap remains highlighted." : "Your calendar is updated.");
  };

  const setPreference = <K extends keyof Preferences>(key: K, value: Preferences[K]) => {
    update("Update planning preference", (draft) => { draft.preferences[key] = value; });
    setPreview(null);
  };

  const generatePreview = (preferences = state.preferences) => {
    if (preferences.start >= preferences.end || preferences.minCredits > preferences.maxCredits || preferences.minCredits < 1 || preferences.maxCredits > 24) {
      showToast("Check your preferences", "Use a valid time window and a credit range from 1 to 24."); return;
    }
    const chosen = [...state.planner];
    const candidates = recommended.filter((id) => !chosen.includes(id)).sort((a, b) => preferenceIssues(a, preferences).length - preferenceIssues(b, preferences).length || Number(catalog[b].rating) - Number(catalog[a].rating));
    for (const id of candidates) {
      if (credits(chosen) >= preferences.minCredits) break;
      if (credits([...chosen, id]) <= preferences.maxCredits && !conflicts([...chosen, id]).length) chosen.push(id);
    }
    if (credits(chosen) < preferences.minCredits) {
      for (const id of Object.keys(catalog).filter((courseId) => !catalog[courseId].required && eligibility(courseId, nextTermId).ok && !chosen.includes(courseId))) {
        if (credits(chosen) >= preferences.minCredits) break;
        if (credits([...chosen, id]) <= preferences.maxCredits && !conflicts([...chosen, id]).length) chosen.push(id);
      }
    }
    const exceptions = chosen.flatMap((id) => preferenceIssues(id, preferences));
    if (credits(chosen) < preferences.minCredits) exceptions.push(`Only ${credits(chosen)} credits fit the available demo sections.`);
    if (credits(chosen) > preferences.maxCredits) exceptions.push(`Your chosen courses exceed the ${preferences.maxCredits}-credit maximum.`);
    const next: PlanPreview = { ids: chosen, exceptions, conflicts: conflicts(chosen), source: [...state.planner], reasons: {} };
    chosen.forEach((id) => { next.reasons[id] = state.planner.includes(id) ? "Kept from your current schedule." : catalog[id].required ? "Advances a remaining requirement." : "Eligible elective that helps reach your credit goal."; });
    setPreview(next);
    update("Create local planning suggestion", (draft) => { draft.semesterChat.push({ role: "assistant", text: `I prepared ${chosen.length} courses (${credits(chosen)} credits). Review the preview before applying.` }); });
  };

  const sendPrompt = () => {
    const text = prompt.trim(); if (!text) return;
    const nextPreferences = { ...state.preferences };
    const lower = text.toLowerCase();
    const days: Record<string, string> = { monday: "Mon", tuesday: "Tue", wednesday: "Wed", thursday: "Thu", friday: "Fri" };
    Object.entries(days).forEach(([day, value]) => { if (lower.includes(day) && /free|off|avoid|no class/.test(lower)) nextPreferences.dayOff = value; });
    if (/early|morning/.test(lower) && /avoid|later|after|no/.test(lower)) nextPreferences.start = 600;
    const range = lower.match(/(\d{1,2})\s*(?:-|to|–)\s*(\d{1,2})\s*credits/);
    if (range) { nextPreferences.minCredits = Number(range[1]); nextPreferences.maxCredits = Number(range[2]); }
    update("Interpret local planning prompt", (draft) => { draft.preferences = nextPreferences; draft.semesterChat.push({ role: "user", text }); });
    setPrompt("");
    generatePreview(nextPreferences);
  };

  const applyPreview = () => {
    if (!preview || preview.conflicts.length || credits(preview.ids) > state.preferences.maxCredits) return;
    if (JSON.stringify(preview.source) !== JSON.stringify(state.planner)) { setPreview(null); showToast("Your schedule changed", "Generate a fresh preview."); return; }
    update("Apply Syllo plan", (draft) => { draft.planner = [...preview.ids]; });
    setPreview(null); showToast("Plan applied", `${preview.ids.length} courses · ${credits(preview.ids)} credits.`);
  };

  const saveSchedule = () => {
    const name = saveName.trim(); if (!name || !state.planner.length) return;
    update("Save schedule", (draft) => { draft.saved.unshift({ id: crypto.randomUUID(), name: name.slice(0, 80), term: nextTermId, courses: [...draft.planner], preferences: { ...draft.preferences }, savedAt: new Date().toISOString() }); });
    setSaveOpen(false); showToast("Schedule saved", "Find it in Saved schedules.");
  };

  const calendarEvents = (daysToShow: string[]) => state.planner.flatMap((id) => {
    const course = catalog[id], range = timeRange(course); if (!range) return [];
    const [start, end] = range;
    return courseDays(course).filter((day) => daysToShow.includes(day)).map((day) => {
      const overlaps = state.planner.filter((other) => other !== id && courseDays(catalog[other]).includes(day) && conflicts([id, other]).length);
      const group = [id, ...overlaps].sort();
      const style: CalendarStyle = { "--day": daysToShow.indexOf(day) + 1, "--top": `${Math.max(0, (start - 480) / 540 * 100)}%`, "--height": `${Math.min(100, (end - start) / 540 * 100)}%`, "--sem-lane": group.indexOf(id), "--sem-lanes": group.length };
      return <article key={`${id}-${day}`} className={`planner-calendar-event ${plannedConflicts.some((pair) => pair.includes(id)) ? "has-conflict" : ""}`} style={style} tabIndex={0} role="button" onClick={() => setSelectedCourse(id)}><button type="button" onClick={(event) => { event.stopPropagation(); remove(id); }} aria-label={`Remove ${course.code}`}>×</button><strong>{course.code}</strong><span>{course.name}</span><small>{formatTime(start)}–{formatTime(end)}</small>{plannedConflicts.some((pair) => pair.includes(id)) && <b className="sem-event-warning">Overlap</b>}</article>;
    });
  });

  return <section className="screen planner-screen active react-screen"><div className="planner-shell">
    <div className="screen-topline"><button className="text-button" onClick={() => navigate("features")}>← All features</button></div>
    <div className="planner-header"><div><span className="features-kicker">SPRING 2027 · NEXT SEMESTER</span><h1>Semester Planner</h1><p>Build your next semester, one course at a time.</p></div><div className="planner-total"><strong>{credits(state.planner)}</strong><span>planned credits</span></div></div>
    <div className="sem-toolbar"><div className="sem-registration"><strong>{registrationLabel}</strong><span><b>Until registration opens</b><small>Nov 2, 2026 · 7:00 AM ET · Demo date</small></span></div><div className="sem-toolbar-actions"><button className="button quiet" onClick={() => { update("Use semester calendar in degree plan", (draft) => { draft.degree[nextTermId] = [...draft.planner]; }); navigate("requirements"); }}>Use in degree plan</button><button className="button quiet" disabled={!canUndo} onClick={() => { undo(); setPreview(null); }}>↶ Undo</button><button className="button primary" onClick={() => state.planner.length ? setSaveOpen(true) : showToast("Add a course first")}>Save schedule</button></div></div>
    <div className={`planner-layout ${state.preferences.aiOpen ? "" : "ai-closed"}`}>
      <div className="manual-planner">
        <div className="course-search-area"><label className="course-search"><span>Search courses</span><div><input type="search" value={query} onFocus={() => setSearchOpen(true)} onChange={(event) => { setQuery(event.target.value); setSearchOpen(true); }} placeholder="Course name, code, department, or keyword" /></div></label><button className="sem-search-button" onClick={() => setSearchOpen(true)}>Search</button><div className="catalog-note">Demo catalog · sections, prerequisites, ratings, and offerings are simulated.</div><div className="sem-search-controls"><label><span>Explore</span><select value={electivesOnly ? "electives" : "all"} onChange={(event) => { setElectivesOnly(event.target.value === "electives"); setSearchOpen(true); }}><option value="all">All courses</option><option value="electives">Electives & prerequisites</option></select></label></div>
          {searchOpen && <div className="course-search-results is-open"><div className="sem-search-summary">{results.length} results · Spring 2027<button onClick={() => setSearchOpen(false)} aria-label="Close results">×</button></div>{results.map(([id, course]) => { const access = eligibility(id, nextTermId), added = state.planner.includes(id); return <article className={`search-result ${access.ok ? "" : "sem-blocked"}`} key={id}><div className="search-result-main"><div><span>{course.code}</span><strong>{course.name}</strong><small>{course.credits} credits · {course.days} · {course.time}</small></div><button className={`button ${added || !access.ok ? "quiet" : "primary"}`} onClick={() => add(id)} disabled={added || !access.ok}>{added ? "Added" : access.ok ? "Add to Schedule" : "Preview only"}</button></div>{!access.ok && <p className="course-warning sem-prerequisite">{access.reasons.join(" ")}</p>}<details><summary>Course details & prerequisites</summary><p>{course.description}</p><button className="text-button" onClick={() => setSelectedCourse(id)}>Open full details</button></details></article>; })}</div>}
        </div>
        {plannedConflicts.length > 0 && <div className="sem-conflict-notice" role="status">{plannedConflicts.length} time conflict{plannedConflicts.length === 1 ? "" : "s"} in this plan.</div>}
        <div className="schedule-workspace"><div className="schedule-calendar-column"><div className="manual-schedule-heading"><h2>My schedule</h2><button className="button quiet sem-expand-calendar" onClick={() => setFullCalendar(true)}>View bigger ↗</button></div><div className="planner-week-calendar" onDragOver={(event) => event.preventDefault()} onDrop={(event) => { event.preventDefault(); const id = event.dataTransfer.getData("application/x-syllo-course"); if (id) add(id); }}><div className="planner-calendar-days"><span />{["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"].map((day) => <strong key={day}>{day}</strong>)}</div><div className="planner-calendar-grid"><div className="planner-time-labels" aria-hidden="true">{Array.from({ length: 10 }, (_, index) => <span key={index}>{formatTime((index + 8) * 60)}</span>)}</div><div className="planner-calendar-lines" aria-hidden="true" /><div className="planner-calendar-events">{calendarEvents(weekdays)}{!state.planner.length && <p className="planner-calendar-empty">Add a course or drop one here</p>}</div></div></div></div>
          <aside className="planner-course-sidebar"><section><div className="planner-sidebar-heading"><div><span>DEGREE PATH</span><h3>Recommended classes</h3></div><strong>{recommended.length}</strong></div><p>Required courses with prerequisites met and a demo section next semester.</p><div className="planner-sidebar-list">{recommended.map((id) => <CourseListItem key={id} id={id} type="recommended" onAdd={add} onRemove={remove} />)}</div></section><section><div className="planner-sidebar-heading"><div><span>THIS SEMESTER</span><h3>Planned classes</h3></div><strong>{state.planner.length}</strong></div><div className="planner-sidebar-list">{state.planner.map((id) => <CourseListItem key={id} id={id} type="planned" onAdd={add} onRemove={remove} />)}</div></section></aside>
        </div>
      </div>
      <div className="ai-assistant-shell"><button className="syllo-ai-toggle" aria-expanded={state.preferences.aiOpen} onClick={() => setPreference("aiOpen", !state.preferences.aiOpen)}><span className="spark">✦</span><span>{state.preferences.aiOpen ? "Close AI" : "Syllo AI"}</span></button>{state.preferences.aiOpen && <aside className="intelligence-panel"><div className="intelligence-heading"><span className="spark">✦</span><div><strong>Syllo AI</strong><small>{state.planner.length} selected courses · {credits(state.planner)} credits</small></div></div><div className="sem-conversation"><div className="planner-chat" role="log">{state.semesterChat.length ? state.semesterChat.map((message, index) => <div className={`chat-message ${message.role === "user" ? "user" : "assistant"}`} key={`${message.role}-${index}`}><span>{message.role === "user" ? "You" : "Syllo"}</span><p>{message.text}</p></div>) : <div className="chat-message assistant"><span>Syllo</span><p>Tell me how you want your week to look. Demo suggestions use only local rules.</p></div>}</div><div className="chat-starters"><button onClick={() => setPrompt("Keep Fridays free and avoid early classes.")}>Keep Fridays free</button><button onClick={() => setPrompt("Suggest a course that helps my degree progress.")}>Help degree progress</button></div><form className="planner-chat-form" onSubmit={(event) => { event.preventDefault(); sendPrompt(); }}><textarea rows={3} value={prompt} onChange={(event) => setPrompt(event.target.value)} placeholder="Try “Fridays free, 12 to 16 credits”…" /><button type="submit">Send</button></form></div><details className="sem-ai-preferences"><summary>Customize your preferences</summary><div className="syllo-field-grid"><label>Start after<select value={state.preferences.start} onChange={(event) => setPreference("start", Number(event.target.value))}>{[480, 540, 600, 660, 720].map((value) => <option value={value} key={value}>{formatTime(value)}</option>)}</select></label><label>Finish by<select value={state.preferences.end} onChange={(event) => setPreference("end", Number(event.target.value))}>{[840, 900, 960, 1020, 1080].map((value) => <option value={value} key={value}>{formatTime(value)}</option>)}</select></label><label>Keep free<select value={state.preferences.dayOff} onChange={(event) => setPreference("dayOff", event.target.value)}><option value="">No preference</option>{weekdays.map((day) => <option value={day} key={day}>{day}</option>)}</select></label><label>Minimum credits<input type="number" min={1} max={24} value={state.preferences.minCredits} onChange={(event) => setPreference("minCredits", Number(event.target.value))} /></label><label>Maximum credits<input type="number" min={1} max={24} value={state.preferences.maxCredits} onChange={(event) => setPreference("maxCredits", Number(event.target.value))} /></label></div><button className="button primary" onClick={() => generatePreview()}>Preview a plan</button></details>{preview && <div className="sem-ai-preview"><div className="sem-preview-head"><strong>Plan preview</strong><span>{credits(preview.ids)} credits</span></div><div className="sem-preview-courses">{preview.ids.map((id) => <div key={id}><strong>{catalog[id].code}</strong><span>{preview.reasons[id]}</span><small>{catalog[id].days} · {catalog[id].time}</small></div>)}</div>{preview.exceptions.length ? <div className="syllo-warning">{preview.exceptions.map((issue) => <p key={issue}>{issue}</p>)}</div> : <p className="sem-preview-success">Fits your selected preferences.</p>}<div className="sem-preview-actions"><button className="button quiet" onClick={() => setPreview(null)}>Cancel</button><button className="button primary" disabled={preview.conflicts.length > 0 || credits(preview.ids) > state.preferences.maxCredits} onClick={applyPreview}>Apply plan</button></div></div>}<p className="chat-disclaimer">Local demo suggestions—review before applying. No AI service is called.</p></aside>}</div>
    </div>
    {pendingAdd && <div className="syllo-dialog-backdrop"><div className="syllo-dialog-card" role="dialog" aria-modal="true"><h2>Choose which courses to keep</h2><p>{catalog[pendingAdd].code} overlaps an existing course.</p><div className="syllo-modal-actions"><button className="button quiet" onClick={() => setPendingAdd(null)}>Keep current courses</button><button className="button quiet" onClick={() => add(pendingAdd, "both")}>Keep both and mark conflict</button><button className="button primary" onClick={() => add(pendingAdd, "replace")}>Use {catalog[pendingAdd].code}</button></div></div></div>}
    {saveOpen && <div className="syllo-dialog-backdrop"><form className="syllo-dialog-card" onSubmit={(event) => { event.preventDefault(); saveSchedule(); }}><h2>Save this schedule</h2><label>Schedule name<input autoFocus maxLength={80} value={saveName} onChange={(event) => setSaveName(event.target.value)} /></label><p>{state.planner.length} courses · {credits(state.planner)} credits. Saved in this browser.</p><div className="syllo-modal-actions"><button type="button" className="button quiet" onClick={() => setSaveOpen(false)}>Cancel</button><button type="submit" className="button primary">Save schedule</button></div></form></div>}
    {fullCalendar && <div className="syllo-dialog-backdrop"><div className="syllo-dialog-card" role="dialog" aria-modal="true"><div className="sem-full-header"><div><h2>Full weekly calendar</h2><p>All selected demo sections</p></div><button className="button quiet" onClick={() => setFullCalendar(false)}>Close ×</button></div><div className="syllo-course-grid">{state.planner.map((id) => <article key={id}><div><strong>{catalog[id].code} · {catalog[id].name}</strong><small>{catalog[id].days} · {catalog[id].time}</small></div><button className="button quiet" onClick={() => setSelectedCourse(id)}>Details</button></article>)}</div></div></div>}
  </div></section>;
}
