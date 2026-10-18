import { catalog, completed, electiveSlots, historicalCredits, terms } from "./mock-data";
import type { AppState, Course, DegreeAudit, Preferences } from "./types";

export function getTerm(termId: string) {
  return terms.find((term) => term.id === termId);
}

export function courseDays(course: Course): string[] {
  return course.days.split("/").map((day) => day.trim().slice(0, 3)).filter(Boolean);
}

function timeValue(value: string, period?: string): number | null {
  const match = value.trim().match(/(\d+):(\d+)\s*(AM|PM)?/i);
  if (!match) return null;
  const suffix = (match[3] || period || "AM").toUpperCase();
  return (Number(match[1]) % 12 + (suffix === "PM" ? 12 : 0)) * 60 + Number(match[2]);
}

export function timeRange(course: Course): [number, number] | null {
  if (!course.time) return null;
  const parts = course.time.split("–");
  if (parts.length !== 2) return null;
  const period = parts[1].match(/AM|PM/i)?.[0];
  const start = timeValue(parts[0], period);
  const end = timeValue(parts[1], period);
  return start === null || end === null ? null : [start, end];
}

export function formatTime(minutes: number): string {
  return `${Math.floor(minutes / 60) % 12 || 12}:${String(minutes % 60).padStart(2, "0")} ${minutes >= 720 ? "PM" : "AM"}`;
}

export function conflicts(ids: string[]): [string, string][] {
  const result: [string, string][] = [];
  ids.forEach((id, index) => ids.slice(index + 1).forEach((other) => {
    const a = catalog[id] ? timeRange(catalog[id]) : null;
    const b = catalog[other] ? timeRange(catalog[other]) : null;
    if (a && b && a[0] < b[1] && b[0] < a[1] && courseDays(catalog[id]).some((day) => courseDays(catalog[other]).includes(day))) {
      result.push([id, other]);
    }
  }));
  return result;
}

export function eligibility(id: string, termId: string): { ok: boolean; reasons: string[] } {
  const course = catalog[id];
  const term = getTerm(termId);
  const reasons: string[] = [];
  if (!course || !term) return { ok: false, reasons: ["Course or semester not found."] };
  if (completed.has(id)) reasons.push("Already completed in the demo record.");
  if (!course.terms.includes(term.season)) reasons.push(`Offered in ${course.terms.join(" and ")} only in the demo catalog.`);
  course.prereqs.filter((prereq) => !completed.has(prereq)).forEach((prereq) => reasons.push(`Complete ${catalog[prereq]?.code ?? prereq} first.`));
  if (termId === "spring-2027" && !timeRange(course)) reasons.push("A next-semester section time is not available.");
  return { ok: reasons.length === 0, reasons };
}

export function credits(ids: string[]): number {
  return [...new Set(ids)].reduce((total, id) => total + (catalog[id]?.credits ?? 0), 0);
}

export function effectiveSlots(state: AppState) {
  return electiveSlots.map((slot) => ({ ...slot, term: state.preferences.degreeElectiveTerms[slot.id] ?? slot.term }));
}

export function sequenceIssues(degree: AppState["degree"]): string[] {
  const issues: string[] = [];
  const seen = new Set<string>();
  terms.forEach((term, index) => (degree[term.id] ?? []).forEach((id) => {
    const course = catalog[id];
    if (!course) return;
    if (seen.has(id)) issues.push(`${course.code} appears more than once.`);
    seen.add(id);
    if (term.past) return;
    if (!course.terms.includes(term.season)) issues.push(`${course.code} is offered in ${course.terms.join(" and ")} only.`);
    course.prereqs.forEach((prereq) => {
      const earlier = completed.has(prereq) || terms.slice(0, index).some((candidate) => (degree[candidate.id] ?? []).includes(prereq));
      if (!earlier) issues.push(`${course.code} must be in a later semester than ${catalog[prereq]?.code ?? prereq}.`);
    });
  }));
  return issues;
}

export function degreeAudit(state: AppState): DegreeAudit {
  const slots = effectiveSlots(state);
  const futureIds = terms.filter((term) => !term.past).flatMap((term) => state.degree[term.id] ?? []);
  const required = Object.keys(catalog).filter((id) => catalog[id].required && !completed.has(id));
  const missing = required.filter((id) => !futureIds.includes(id));
  const issues = sequenceIssues(state.degree);
  missing.forEach((id) => issues.push(`${catalog[id].code} is missing from the degree plan.`));
  const selected: string[] = [];
  slots.forEach((slot) => {
    const id = state.electives[slot.id];
    const course = id ? catalog[id] : undefined;
    if (!id || !course) {
      issues.push(`Choose a course for the ${slot.credits}-credit elective slot.`);
      return;
    }
    if (course.required || course.credits !== slot.credits) issues.push(`${course.code} does not meet this elective slot.`);
    if (selected.includes(id) || futureIds.includes(id) || completed.has(id)) issues.push(`${course.code} cannot count toward two requirements.`);
    selected.push(id);
    const termIndex = terms.findIndex((term) => term.id === slot.term);
    const term = terms[termIndex];
    if (!term || !course.terms.includes(term.season)) issues.push(`${course.code} is not offered in this elective semester.`);
    course.prereqs.forEach((prereq) => {
      const earlier = completed.has(prereq) || terms.slice(0, termIndex).some((candidate) => (state.degree[candidate.id] ?? []).includes(prereq));
      if (!earlier) issues.push(`${course.code} needs ${catalog[prereq]?.code ?? prereq} first.`);
    });
  });
  const plannedCredits = credits([...futureIds, ...selected]);
  const totalCredits = historicalCredits + plannedCredits;
  if (totalCredits < 120) issues.push(`${120 - totalCredits} more credits are needed to reach the demo 120-credit requirement.`);
  const lastIndex = Math.max(...terms.map((term, index) => (state.degree[term.id]?.length || slots.some((slot) => slot.term === term.id && state.electives[slot.id])) ? index : 0));
  const finalTerm = terms[lastIndex];
  const uniqueIssues = [...new Set(issues)];
  return {
    valid: uniqueIssues.length === 0,
    issues: uniqueIssues,
    plannedCredits,
    totalCredits,
    graduation: uniqueIssues.length ? "Plan incomplete" : `${finalTerm.season === "spring" ? "May" : "December"} ${finalTerm.year}`,
    missing,
  };
}

export function preferenceIssues(id: string, preferences: Preferences): string[] {
  const course = catalog[id];
  const range = timeRange(course);
  const issues: string[] = [];
  if (range && range[0] < preferences.start) issues.push(`${course.code} starts at ${formatTime(range[0])}, before your preference.`);
  if (range && range[1] > preferences.end) issues.push(`${course.code} ends at ${formatTime(range[1])}, after your preference.`);
  if (preferences.dayOff && courseDays(course).includes(preferences.dayOff)) issues.push(`${course.code} meets on your preferred day off.`);
  if (preferences.professor && !course.section.toLowerCase().includes(preferences.professor.toLowerCase())) issues.push(`${course.code} does not use your preferred professor.`);
  return issues;
}
