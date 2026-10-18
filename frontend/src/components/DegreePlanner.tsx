"use client";

import { useMemo, useState } from "react";
import { useSyllo } from "@/context/SylloContext";
import { catalog, completed, electiveSlots, historicalCredits, nextTermId, terms } from "@/lib/mock-data";
import { credits, degreeAudit, effectiveSlots, getTerm, sequenceIssues } from "@/lib/planner";
import type { AppState, ElectiveSlot } from "@/lib/types";

function cloneState(state: AppState): AppState {
  return JSON.parse(JSON.stringify(state)) as AppState;
}

function relatives(id: string, backwards: boolean): Set<string> {
  const found = new Set<string>();
  const walk = (current: string) => {
    const next = backwards ? catalog[current].prereqs : Object.keys(catalog).filter((candidate) => catalog[candidate].prereqs.includes(current));
    next.filter((candidate) => catalog[candidate] && !found.has(candidate) && candidate !== id).forEach((candidate) => { found.add(candidate); walk(candidate); });
  };
  walk(id); return found;
}

export function DegreePlanner() {
  const { state, navigate, update, setSelectedCourse, showToast } = useSyllo();
  const [query, setQuery] = useState("");
  const [targetTerm, setTargetTerm] = useState(nextTermId);
  const [workspaceTab, setWorkspaceTab] = useState<"plan" | "ai">("plan");
  const [electiveSlot, setElectiveSlot] = useState<ElectiveSlot["id"] | null>(null);
  const [electiveQuery, setElectiveQuery] = useState("");
  const [focusCourse, setFocusCourse] = useState<string | null>(null);
  const [prompt, setPrompt] = useState("");
  const audit = degreeAudit(state);
  const slots = effectiveSlots(state);
  const prerequisiteIds = focusCourse ? relatives(focusCourse, true) : new Set<string>();
  const followOnIds = focusCourse ? relatives(focusCourse, false) : new Set<string>();

  const searchResults = useMemo(() => {
    const tokens = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
    return Object.entries(catalog).filter(([, course]) => tokens.every((token) => [course.code, course.name, course.description, course.requirement, course.interest].join(" ").toLowerCase().includes(token)));
  }, [query]);

  const plannedTerm = (id: string) => terms.find((term) => state.degree[term.id]?.includes(id)) ?? terms.find((term) => slots.some((slot) => slot.term === term.id && state.electives[slot.id] === id));
  const addReasons = (id: string, termId: string): string[] => {
    const course = catalog[id], term = getTerm(termId), termIndex = terms.findIndex((candidate) => candidate.id === termId), reasons: string[] = [];
    if (!course || !term || term.past) return ["Choose a future semester."];
    if (completed.has(id)) return ["Already completed."];
    const existing = plannedTerm(id); if (existing) return [`Already planned · ${existing.label}.`];
    if (!course.terms.includes(term.season)) reasons.push(`Offered in ${course.terms.join(" and ")} only.`);
    course.prereqs.forEach((prereq) => { if (!completed.has(prereq) && !terms.slice(0, termIndex).some((candidate) => state.degree[candidate.id]?.includes(prereq))) reasons.push(`Take ${catalog[prereq].code} before ${term.label}.`); });
    return reasons;
  };

  const addCourse = (id: string) => {
    const reasons = addReasons(id, targetTerm); if (reasons.length) { showToast("Course cannot be added", reasons.join(" ")); return; }
    const course = catalog[id];
    const emptySlot = !course.required ? slots.find((slot) => slot.credits === course.credits && !state.electives[slot.id]) : undefined;
    update(`Add ${course.code} to degree plan`, (draft) => {
      if (emptySlot) { draft.electives[emptySlot.id] = id; draft.preferences.degreeElectiveTerms[emptySlot.id] = targetTerm; }
      else draft.degree[targetTerm].push(id);
      draft.review.status = "draft";
    });
    showToast(`${course.code} added`, `${getTerm(targetTerm)?.label}${emptySlot ? " · Elective slot filled" : ""}`);
  };

  const moveCourse = (id: string, from: string, to: string) => {
    if (from === to) return;
    const source = getTerm(from), target = getTerm(to); if (!source || !target || source.past || target.past) return;
    const slot = slots.find((candidate) => candidate.term === from && state.electives[candidate.id] === id);
    const candidate = cloneState(state);
    if (slot) candidate.preferences.degreeElectiveTerms[slot.id] = to;
    else { candidate.degree[from] = candidate.degree[from].filter((courseId) => courseId !== id); candidate.degree[to].push(id); }
    const previousIssues = new Set(degreeAudit(state).issues);
    const newIssues = degreeAudit(candidate).issues.filter((issue) => !previousIssues.has(issue));
    if (newIssues.length || sequenceIssues(candidate.degree).length) { showToast("Course stays in place", (newIssues[0] ?? sequenceIssues(candidate.degree)[0]) || "This move breaks the demo sequence."); return; }
    update(`Move ${catalog[id].code}`, (draft) => {
      if (slot) draft.preferences.degreeElectiveTerms[slot.id] = to;
      else { draft.degree[from] = draft.degree[from].filter((courseId) => courseId !== id); draft.degree[to].push(id); }
      draft.review.status = "draft";
    });
    showToast(`${catalog[id].code} moved`, `${target.label} · Prerequisites and offering checked.`);
  };

  const electiveReasons = (id: string, slot: ElectiveSlot): string[] => {
    const course = catalog[id], term = getTerm(slot.term), targetIndex = terms.findIndex((candidate) => candidate.id === slot.term), reasons: string[] = [];
    if (!course || !term) return ["Course or semester not found."];
    if (course.required) reasons.push("Reserved for a required course slot.");
    if (course.credits !== slot.credits) reasons.push(`This slot needs ${slot.credits} credits.`);
    if (!course.terms.includes(term.season)) reasons.push(`Not offered in ${term.label}.`);
    course.prereqs.forEach((prereq) => { if (!completed.has(prereq) && !terms.slice(0, targetIndex).some((candidate) => state.degree[candidate.id]?.includes(prereq))) reasons.push(`Take ${catalog[prereq].code} before ${term.label}.`); });
    if (plannedTerm(id) && state.electives[slot.id] !== id) reasons.push("Already included in your degree plan.");
    return reasons;
  };

  const answer = (question: string): string => {
    const lower = question.toLowerCase();
    const matched = Object.entries(catalog).find(([, course]) => lower.includes(course.code.toLowerCase()) || lower.includes(course.name.toLowerCase()));
    if (matched) { const [id, course] = matched, term = plannedTerm(id); return `${course.code} · ${course.name}\n${course.credits} credits · ${course.requirement}\nPrerequisites: ${course.prereqs.map((prereq) => catalog[prereq].code).join(", ") || "None in the demo catalog"}.\n${completed.has(id) ? "Completed in your record." : term ? `Planned for ${term.label}.` : "Not currently in your plan."}`; }
    if (/elective|interest/.test(lower)) return `Your plan has ${electiveSlots.length} free-elective slots. Choose an empty slot in the degree calendar to compare matching demo courses.`;
    if (/prereq|sequence|order/.test(lower)) return "Courses must appear after their prerequisites. Completed semesters stay locked, and invalid moves are blocked.";
    return `You have ${historicalCredits} completed credits and ${audit.plannedCredits} planned credits, covering ${audit.totalCredits} of the demo 120-credit requirement.\n${audit.valid ? `Your current plan reaches graduation in ${audit.graduation}.` : audit.issues.slice(0, 4).join("\n")}`;
  };

  const ask = () => {
    const question = prompt.trim(); if (!question) return;
    update("Ask local degree assistant", (draft) => { draft.degreeChat.push({ role: "user", text: question }, { role: "assistant", text: answer(question) }); });
    setPrompt("");
  };

  const selectCourse = (id: string) => { setFocusCourse((current) => current === id ? null : id); setSelectedCourse(id); };
  const termSlots = (termId: string) => slots.filter((slot) => slot.term === termId);
  const futureCredits = (termId: string) => credits(state.degree[termId] ?? []) + termSlots(termId).reduce((total, slot) => total + (state.electives[slot.id] ? catalog[state.electives[slot.id]!].credits : 0), 0);

  return <section className="screen progress-screen active react-screen"><div className="degree-shell">
    <div className="screen-topline"><button className="text-button" onClick={() => navigate("features")}>← All features</button><span className="demo-badge">DEMO DEGREE DATA</span></div>
    <div className="degree-page-heading"><div><span className="features-kicker">DEGREE PLANNER</span><h1>Make room for your future.</h1><p>Computer Science, B.S. · Data Science minor · 2024–2028</p></div><span className="degree-demo-label">Demo curriculum</span></div>
    <div className="degree-credit-summary"><strong>{historicalCredits}/120 credits completed</strong><span>{audit.plannedCredits} planned</span><span>{Math.max(0, 120 - audit.totalCredits)} to plan</span>{audit.valid && <span>Graduation · {audit.graduation}</span>}</div>
    <section className="degree-course-search"><div className="degree-search-heading"><h2>Add a course</h2><span>Demo catalog</span></div><div className="degree-search-controls"><label className="degree-search-query"><span>Search courses</span><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Course name, code, department, or keyword" /></label><label className="degree-search-term"><span>Add to semester</span><select value={targetTerm} onChange={(event) => setTargetTerm(event.target.value)}>{terms.filter((term) => !term.past).map((term) => <option value={term.id} key={term.id}>{term.label}</option>)}</select></label></div>{query && <div className="degree-course-results"><div className="degree-search-result-heading"><span>{searchResults.length} courses</span><button onClick={() => setQuery("")}>×</button></div>{searchResults.map(([id, course]) => { const reasons = addReasons(id, targetTerm); return <article key={id}><div><strong>{course.code} · {course.name}</strong><p>{course.credits} credits · {course.requirement}</p>{reasons.length > 0 && <span className="degree-search-reason">{reasons.join(" ")}</span>}</div><div className="degree-search-result-actions"><button className="button quiet" onClick={() => setSelectedCourse(id)}>Details</button><button className="button primary" disabled={reasons.length > 0} onClick={() => addCourse(id)}>Add course</button></div></article>; })}</div>}</section>
    <div className="degree-workspace-tabs" role="tablist"><button role="tab" aria-selected={workspaceTab === "plan"} onClick={() => setWorkspaceTab("plan")}>Degree calendar</button><button role="tab" aria-selected={workspaceTab === "ai"} onClick={() => setWorkspaceTab("ai")}>Ask Syllo AI</button></div>
    {workspaceTab === "ai" ? <section className="degree-ai-card"><div className="degree-ai-heading"><span>✦</span><div><h2>Syllo AI</h2><p>Your degree, one question at a time.</p></div></div><div className="degree-ai-messages" role="log">{state.degreeChat.length ? state.degreeChat.map((message, index) => <article className={`degree-ai-message ${message.role === "user" ? "from-you" : "from-syllo"}`} key={`${message.role}-${index}`}><strong>{message.role === "user" ? "You" : "Syllo"}</strong><p>{message.text}</p></article>) : <p className="syllo-empty">Ask what is left to graduate, how prerequisites work, or which electives fit.</p>}</div><div className="degree-ai-starters">{["What’s left to graduate?", "Explain my prerequisites", "Suggest electives"].map((question) => <button key={question} onClick={() => setPrompt(question)}>{question}</button>)}</div><form onSubmit={(event) => { event.preventDefault(); ask(); }}><label>What would you like to know?</label><div className="degree-ai-composer"><textarea rows={2} maxLength={4000} value={prompt} onChange={(event) => setPrompt(event.target.value)} /><button className="button primary" type="submit">Send ↑</button></div></form><small className="degree-ai-note">Demo assistant · Answers use your local saved plan.</small></section> : <section className="degree-four-year-plan"><div className="degree-section-heading"><div><h2>Your degree calendar</h2><p>Click a course for details and related classes. Drag future courses to move them.</p></div></div>{focusCourse && <div className="degree-course-focus-note"><span><strong>{catalog[focusCourse].code}</strong> Selected</span><span>Prerequisites ({prerequisiteIds.size})</span><span>Follow-on courses ({followOnIds.size})</span><button onClick={() => setFocusCourse(null)}>Clear selection ×</button></div>}<div className="degree-calendar-scroll"><div className="degree-year-grid degree-semester-calendar">{[0, 1, 2, 3].map((year) => <section className="degree-year" key={year}><div className="degree-year-heading"><span>Year {year + 1} classes</span><strong>{terms[year * 2].year}–{terms[year * 2 + 1].year}</strong></div>{terms.slice(year * 2, year * 2 + 2).map((term) => <section className={`degree-term ${term.past ? "is-completed" : "is-future"} ${term.id === nextTermId ? "is-next-semester" : ""}`} key={term.id} onDragOver={(event) => { if (!term.past) event.preventDefault(); }} onDrop={(event) => { if (term.past) return; event.preventDefault(); const raw = event.dataTransfer.getData("application/syllo-degree"); if (raw) { const data = JSON.parse(raw) as { id: string; from: string }; moveCourse(data.id, data.from, term.id); } }}><div className="degree-term-heading"><h3>{term.past ? "▣ " : ""}{term.label}</h3><span>{term.past ? "Completed" : `${futureCredits(term.id)} credits${term.id === nextTermId ? " · Next semester" : ""}`}</span></div><div className="degree-term-courses">{state.degree[term.id]?.map((id) => { const selected = focusCourse === id, before = prerequisiteIds.has(id), after = followOnIds.has(id); return <article className={`degree-course ${term.past ? "is-completed" : ""} ${selected ? "is-course-selected" : before ? "is-course-prerequisite" : after ? "is-course-unlocked" : focusCourse ? "is-course-unrelated" : ""}`} draggable={!term.past} onDragStart={(event) => event.dataTransfer.setData("application/syllo-degree", JSON.stringify({ id, from: term.id }))} key={id}><button className="degree-course-info" onClick={() => selectCourse(id)}><span>{catalog[id].code} <small>{catalog[id].credits} cr</small></span><strong>{catalog[id].name}</strong><em>{catalog[id].requirement}</em></button></article>; })}{termSlots(term.id).map((slot) => { const id = state.electives[slot.id]; return id ? <article className="degree-course elective-filled" draggable onDragStart={(event) => event.dataTransfer.setData("application/syllo-degree", JSON.stringify({ id, from: term.id }))} key={slot.id}><button className="degree-course-info" onClick={() => selectCourse(id)}><span>{catalog[id].code} <small>{catalog[id].credits} cr</small></span><strong>{catalog[id].name}</strong><em>Free elective</em></button><div className="degree-slot-actions"><button onClick={() => setElectiveSlot(slot.id)}>Change</button><button onClick={() => update("Remove elective", (draft) => { draft.electives[slot.id] = null; draft.review.status = "draft"; })}>Remove</button></div></article> : <button className="degree-elective-slot" onClick={() => { setElectiveSlot(slot.id); setElectiveQuery(""); }} key={slot.id}><span>＋</span><strong>Choose an elective</strong><small>{slot.credits} credits · Free elective</small></button>; })}{!state.degree[term.id]?.length && !termSlots(term.id).length && <p className="degree-term-empty">{term.past ? "Recorded in transcript." : "Drag a course here."}</p>}</div>{term.id === nextTermId && <button className="degree-calendar-link" onClick={() => { update("Use degree plan in semester calendar", (draft) => { draft.planner = draft.degree[nextTermId].filter((id) => Boolean(catalog[id].time)); }); navigate("planner"); }}>Open weekly calendar →</button>}</section>)}</section>)}</div></div></section>}
    <section className={`degree-review-callout ${audit.valid ? "ready" : ""}`}><div><strong>{audit.valid ? "Your plan is ready for review." : "Finish your graduation plan."}</strong><p>{audit.valid ? "All required courses are placed and the demo plan reaches 120 credits." : `${audit.issues.length} planning items still need attention.`}</p><span className="degree-review-status">{state.review.status}</span></div><button className="button primary" disabled={!audit.valid || state.review.status === "submitted" || state.review.status === "approved"} onClick={() => { update("Submit plan for advisor review", (draft) => { draft.review.status = "submitted"; draft.review.submittedAt = new Date().toISOString(); }); navigate("advisor"); }}>Send to advisor →</button></section>
    {!audit.valid && <details className="degree-audit-details"><summary>What still needs attention</summary><ul>{audit.issues.map((issue) => <li key={issue}>{issue}</li>)}</ul></details>}
    <p className="degree-demo-note">Simulated curriculum and transcript. This is not an official Lehigh degree audit.</p>
    {electiveSlot && (() => { const slot = slots.find((candidate) => candidate.id === electiveSlot); if (!slot) return null; const matches = Object.entries(catalog).filter(([, course]) => !course.required && [course.code, course.name, course.interest, course.description].join(" ").toLowerCase().includes(electiveQuery.toLowerCase())); return <div className="syllo-dialog-backdrop"><div className="syllo-dialog-card" role="dialog" aria-modal="true"><div className="degree-dialog-heading"><div><h2>Find your {slot.credits}-credit elective</h2><p>{getTerm(slot.term)?.label} · Demo catalog</p></div><button onClick={() => setElectiveSlot(null)}>×</button></div><label>Search<input autoFocus type="search" value={electiveQuery} onChange={(event) => setElectiveQuery(event.target.value)} /></label><div className="syllo-course-grid">{matches.map(([id, course]) => { const reasons = electiveReasons(id, slot); return <article key={id}><div><strong>{course.code} · {course.name}</strong><small>{course.credits} credits · {course.interest}</small>{reasons.length > 0 && <span className="degree-search-reason">{reasons.join(" ")}</span>}</div><button className="button primary" disabled={reasons.length > 0} onClick={() => { update("Choose elective", (draft) => { draft.electives[slot.id] = id; draft.review.status = "draft"; }); setElectiveSlot(null); showToast("Elective added", `${course.code} is in ${getTerm(slot.term)?.label}.`); }}>Choose</button></article>; })}</div></div></div>; })()}
  </div></section>;
}
