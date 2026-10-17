"use client";

import { useState } from "react";
import { useSyllo } from "@/context/SylloContext";
import { catalog, completed, terms } from "@/lib/mock-data";
import { degreeAudit, effectiveSlots } from "@/lib/planner";
import type { CourseStatus } from "@/lib/types";

const statusOptions: CourseStatus[] = ["Done", "Review", "Withdraw", "Planned"];

export function AdvisorPortal() {
  const { state, navigate, update, setSelectedCourse, showToast } = useSyllo();
  const [filters, setFilters] = useState<Set<CourseStatus>>(new Set());
  const [comment, setComment] = useState("");
  const [commentCourse, setCommentCourse] = useState("");
  const [statusCourse, setStatusCourse] = useState("");
  const [status, setStatus] = useState<CourseStatus>("Planned");
  const audit = degreeAudit(state);
  const isAdvisor = state.role === "advisor";
  const slots = effectiveSlots(state);
  const allCourseIds = terms.flatMap((term) => state.degree[term.id] ?? []).concat(Object.values(state.electives).filter((id): id is string => Boolean(id)));
  const courseStatus = (id: string): CourseStatus => completed.has(id) ? "Done" : state.preferences.advisorCourseStatuses[id] ?? (state.review.comments.some((item) => item.courseId === id) || state.review.status === "submitted" ? "Review" : "Planned");
  const visible = (id: string) => filters.size === 0 || filters.has(courseStatus(id));
  const notifyStudent = (text: string, draft: typeof state) => { draft.review.notifications.push({ id: crypto.randomUUID(), text, read: false, createdAt: new Date().toISOString() }); };

  const postFeedback = () => {
    const text = comment.trim(); if (!text || !isAdvisor) return;
    update("Advisor feedback", (draft) => {
      draft.review.comments.push({ id: crypto.randomUUID(), text, courseId: commentCourse || null, createdAt: new Date().toISOString(), type: "comment" });
      notifyStudent(`Your advisor commented on ${commentCourse ? catalog[commentCourse].code : "your four-year plan"}.`, draft);
    });
    setComment(""); showToast("Feedback posted", "The student demo has a new notification.");
  };

  const decide = (next: "approved" | "changes-requested") => {
    if (!isAdvisor) return;
    if (next === "approved" && (!audit.valid || !["submitted", "changes-requested"].includes(state.review.status))) { showToast("Plan cannot be approved yet", "The student must submit a complete plan."); return; }
    const text = next === "approved" ? "Your four-year plan is approved in this demo." : "Changes requested. Review the advisor’s comments and update your plan.";
    update("Advisor decision", (draft) => {
      draft.review.status = next;
      draft.review.comments.push({ id: crypto.randomUUID(), text, courseId: null, createdAt: new Date().toISOString(), type: "decision" });
      notifyStudent(next === "approved" ? "Your advisor approved your degree plan." : "Your advisor requested changes to your degree plan.", draft);
    });
    showToast(next === "approved" ? "Demo plan approved" : "Changes requested");
  };

  if (!isAdvisor && !audit.valid) return <section className="screen advisor-screen active react-screen"><div className="advisor-shell"><div className="degree-page-heading"><div><span className="features-kicker">ACADEMIC ADVISOR PORTAL</span><h1>Finish your plan first.</h1><p>Your advisor can review a complete path to graduation.</p></div></div><section className="advisor-gate"><span className="advisor-gate-symbol">↗</span><h2>A few pieces are still missing.</h2><p>{audit.issues.length} demo planning items remain.</p><ul>{audit.issues.map((issue) => <li key={issue}>{issue}</li>)}</ul><button className="button primary" onClick={() => navigate("requirements")}>Continue your degree plan →</button><small>Advisor demo accounts can access the review at any time.</small></section></div></section>;

  return <section className="screen advisor-screen active react-screen"><div className="advisor-shell">
    <button className="text-button advisor-back" onClick={() => navigate("features")}>← All features</button>
    <div className="degree-page-heading"><div><span className="features-kicker">ACADEMIC ADVISOR PORTAL</span><h1>David Hwang’s degree plan</h1><p>{isAdvisor ? "Review the full plan and leave feedback for David." : "Your four-year plan and your advisor’s feedback, together."}</p></div><span className="degree-demo-label">{isAdvisor ? "Advisor demo" : "Student demo"}</span></div>
    <section className="advisor-plan-summary"><div><strong>{state.review.status.replaceAll("-", " ")}</strong><p>{audit.totalCredits} / 120 credits · {audit.valid ? `Planned graduation ${audit.graduation}` : "Graduation requirements incomplete"}</p></div>{!isAdvisor && state.review.notifications.some((notice) => !notice.read) && <button className="button quiet" onClick={() => update("Read advisor feedback", (draft) => { draft.review.notifications.forEach((notice) => { notice.read = true; }); })}>Mark updates read</button>}</section>
    <div className="advisor-connected-layout"><section className="advisor-full-plan"><div className="degree-section-heading"><h2>The complete four-year plan</h2><p>Each course shows the requirement it fulfills.</p></div><div className="syllo-status-filters" role="group" aria-label="Filter course statuses"><button aria-pressed={filters.size === 0} onClick={() => setFilters(new Set())}>All</button>{statusOptions.map((item) => <button key={item} aria-pressed={filters.has(item)} onClick={() => setFilters((current) => { const next = new Set(current); if (next.has(item)) next.delete(item); else next.add(item); return next; })}>{item}</button>)}</div>
      {isAdvisor && <details className="advisor-status-editor"><summary>Update a planning status</summary><p>Demo review labels only; this does not alter registration.</p><label>Course<select value={statusCourse} onChange={(event) => setStatusCourse(event.target.value)}><option value="">Choose a course</option>{allCourseIds.filter((id) => !completed.has(id)).map((id) => <option value={id} key={id}>{catalog[id].code} · {catalog[id].name}</option>)}</select></label><label>Status<select value={status} onChange={(event) => setStatus(event.target.value as CourseStatus)}>{statusOptions.filter((item) => item !== "Done").map((item) => <option key={item}>{item}</option>)}</select></label><button onClick={() => { if (!statusCourse) return; update("Update advisor planning status", (draft) => { draft.preferences.advisorCourseStatuses[statusCourse] = status; notifyStudent(`Your advisor marked ${catalog[statusCourse].code} ${status.toLowerCase()} in the demo plan.`, draft); }); showToast("Planning label saved"); }}>Save label</button></details>}
      <div className="advisor-term-grid">{terms.map((term) => { const ids = (state.degree[term.id] ?? []).filter(visible); const termSlots = slots.filter((slot) => slot.term === term.id && state.electives[slot.id]).filter((slot) => visible(state.electives[slot.id]!)); if (!ids.length && !termSlots.length && filters.size) return null; return <article className="advisor-term" key={term.id}><div><h3>{term.label}</h3><span>{term.past ? "Completed" : "Planned"}</span></div>{ids.map((id) => <button key={id} onClick={() => setSelectedCourse(id)}><strong>{catalog[id].code} <small>{catalog[id].credits} cr</small></strong><span>{catalog[id].name}</span><em>{catalog[id].requirement} · {courseStatus(id)}</em></button>)}{termSlots.map((slot) => { const id = state.electives[slot.id]!; return <button key={slot.id} onClick={() => setSelectedCourse(id)}><strong>{catalog[id].code} <small>{catalog[id].credits} cr</small></strong><span>{catalog[id].name}</span><em>Free elective · {courseStatus(id)}</em></button>; })}</article>; })}</div>
    </section><aside className="advisor-feedback"><h2>Advisor feedback</h2><div className="advisor-feedback-list">{state.review.comments.length ? state.review.comments.map((item) => <article className={`advisor-feedback-item ${item.type === "decision" ? "decision" : ""}`} key={item.id}><div><strong>Demo advisor</strong><time>{new Date(item.createdAt).toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}</time></div>{item.courseId ? <button onClick={() => setSelectedCourse(item.courseId)}>{catalog[item.courseId]?.code}</button> : <span>Entire four-year plan</span>}<p>{item.text}</p></article>) : <p className="advisor-no-feedback">No feedback yet.</p>}</div>
      {isAdvisor ? <><form onSubmit={(event) => { event.preventDefault(); postFeedback(); }}><label>Comment on<select value={commentCourse} onChange={(event) => setCommentCourse(event.target.value)}><option value="">The entire four-year plan</option>{allCourseIds.map((id) => <option value={id} key={id}>{catalog[id].code} · {catalog[id].name}</option>)}</select></label><label>Suggestion or comment<textarea rows={4} value={comment} onChange={(event) => setComment(event.target.value)} required /></label><button className="button primary" type="submit">Post feedback</button></form><div className="advisor-decision-actions"><button className="button quiet" onClick={() => decide("changes-requested")}>Request changes</button><button className="button primary" disabled={!audit.valid || !["submitted", "changes-requested"].includes(state.review.status)} onClick={() => decide("approved")}>Approve plan</button></div></> : <p className="advisor-action-note">Feedback and decisions appear here when your advisor reviews your plan.</p>}
    </aside></div>
    <p className="degree-demo-note">Demo collaboration is saved in this browser. No message is sent to a real advisor.</p>
  </div></section>;
}
