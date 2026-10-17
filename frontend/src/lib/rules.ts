// One validation module for the whole frontend (prerequisite order, requirements, offered terms,
// credit limits). Time conflicts are NOT decided here: the backend engine owns those.

import {
  COURSES,
  CURRENT_TERM,
  NEXT_TERM,
  PROGRAM,
  type Requirement,
  type RequirementKind,
  type StudentAccount,
  TERMS,
  termLabel,
} from "./demo/program";

/** termId -> placed items. An item is a course id, or an elective slot "slot:<requirementId>:<n>". */
export type DegreePlan = Record<string, string[]>;

export const isSlot = (item: string) => item.startsWith("slot:");
export const slotRequirement = (item: string) => item.split(":")[1] ?? "";
export const termIndex = (termId: string) => TERMS.findIndex((t) => t.id === termId);

export function courseCode(id: string): string {
  return id.replace("-", " ");
}

/** Every course the student has taken or is taking, by term. */
export function historyTermOf(student: StudentAccount, courseId: string): string | undefined {
  return Object.entries(student.transcript).find(([, ids]) => ids.includes(courseId))?.[0];
}

/** Where a course sits: transcript first, then the plan. */
export function termOf(student: StudentAccount, plan: DegreePlan, courseId: string): string | undefined {
  return historyTermOf(student, courseId) ?? Object.entries(plan).find(([, ids]) => ids.includes(courseId))?.[0];
}

/** Prerequisites not completed or planned strictly before {@code termId}. */
export function missingPrereqs(student: StudentAccount, plan: DegreePlan, courseId: string, termId: string): string[] {
  const at = termIndex(termId);
  return (COURSES[courseId]?.prereqs ?? []).filter((p) => {
    const where = termOf(student, plan, p);
    return where === undefined || termIndex(where) >= at;
  });
}

export function isOffered(courseId: string, termId: string): boolean {
  const season = TERMS[termIndex(termId)]?.season;
  return !!season && (COURSES[courseId]?.offered ?? []).includes(season);
}

export interface RequirementStatus {
  requirement: Requirement;
  done: string[]; // from the transcript
  planned: string[]; // in the plan
  slots: number; // empty elective slots placed for it
  remaining: number;
}

/** Each course counts toward at most one requirement, the first one in program order it fits. */
export function requirementStatus(student: StudentAccount, plan: DegreePlan): RequirementStatus[] {
  const historyIds = Object.values(student.transcript).flat();
  const plannedIds = Object.values(plan).flat().filter((i) => !isSlot(i));
  const used = new Set<string>();
  return PROGRAM.requirements.map((requirement) => {
    const done: string[] = [];
    const planned: string[] = [];
    for (const option of requirement.options) {
      if (done.length + planned.length >= requirement.count || used.has(option)) continue;
      if (historyIds.includes(option)) {
        done.push(option);
        used.add(option);
      } else if (plannedIds.includes(option)) {
        planned.push(option);
        used.add(option);
      }
    }
    const slots = Object.values(plan).flat().filter((i) => isSlot(i) && slotRequirement(i) === requirement.id).length;
    const remaining = Math.max(0, requirement.count - done.length - planned.length - slots);
    return { requirement, done, planned, slots, remaining };
  });
}

/** The requirement a course counts toward for this student, if any still needs it. */
export function requirementFor(student: StudentAccount, plan: DegreePlan, courseId: string): Requirement | undefined {
  const statuses = requirementStatus(student, plan);
  const claimed = statuses.find((s) => s.done.includes(courseId) || s.planned.includes(courseId));
  if (claimed) return claimed.requirement;
  return statuses.find((s) => s.remaining + s.slots > 0 && s.requirement.options.includes(courseId))?.requirement;
}

export function kindOf(student: StudentAccount, plan: DegreePlan, courseId: string): RequirementKind {
  return requirementFor(student, plan, courseId)?.kind ?? "free";
}

/** Courses that list this one as a prerequisite. */
export function unlocks(courseId: string): string[] {
  return Object.values(COURSES).filter((m) => m.prereqs.includes(courseId)).map((m) => m.id);
}

/** One-line "why": what it counts toward and what it unlocks. */
export function whyLine(student: StudentAccount, plan: DegreePlan, courseId: string): string {
  const req = requirementFor(student, plan, courseId);
  const opens = unlocks(courseId).map(courseCode);
  const parts = [req ? `Counts toward ${req.label}` : "Counts as a free elective"];
  if (opens.length) parts.push(`Unlocks ${opens.slice(0, 3).join(", ")}`);
  return parts.join(". ");
}

export interface Recommendation {
  courseId: string;
  requirement: Requirement;
}

/**
 * The spec's single definition: a course is Recommended for a term when its prerequisites are met
 * (completed or planned earlier), it counts toward an unmet requirement, and it is offered that term.
 * {@code available} narrows to courses the catalog actually has sections for.
 */
export function recommended(student: StudentAccount, plan: DegreePlan, termId: string, available?: Set<string>): Recommendation[] {
  const taken = new Set(Object.values(student.transcript).flat());
  const statuses = requirementStatus(student, plan);
  const out: Recommendation[] = [];
  const seen = new Set<string>();
  for (const status of statuses) {
    const open = status.remaining + status.slots + status.planned.length > 0;
    if (!open) continue;
    for (const id of status.requirement.options) {
      if (seen.has(id) || taken.has(id)) continue;
      if (available && !available.has(id)) continue;
      if (!isOffered(id, termId)) continue;
      if (missingPrereqs(student, plan, id, termId).length) continue;
      const plannedElsewhere = termOf(student, plan, id);
      if (plannedElsewhere && plannedElsewhere !== termId) continue;
      if (status.remaining === 0 && status.slots === 0 && !status.planned.includes(id)) continue;
      out.push({ courseId: id, requirement: status.requirement });
      seen.add(id);
    }
  }
  return out;
}

export function creditsOf(items: string[]): number {
  return items.filter((i) => !isSlot(i)).reduce((sum, id) => sum + (COURSES[id]?.credits ?? 3), 0)
    + items.filter(isSlot).length * 3;
}

export type MoveCheck = { ok: true; warnings: string[] } | { ok: false; message: string };

/** Whether {@code courseId} may go into {@code toTerm}: prerequisites before it, dependents after it. */
export function checkMove(student: StudentAccount, plan: DegreePlan, courseId: string, toTerm: string): MoveCheck {
  if (isSlot(courseId)) return { ok: true, warnings: [] };
  const without: DegreePlan = Object.fromEntries(Object.entries(plan).map(([t, ids]) => [t, ids.filter((i) => i !== courseId)]));
  const missing = missingPrereqs(student, without, courseId, toTerm);
  if (missing.length) {
    return { ok: false, message: `${courseCode(courseId)} needs ${missing.map(courseCode).join(" and ")} first.` };
  }
  const at = termIndex(toTerm);
  const broken = unlocks(courseId).filter((dependent) => {
    const where = termOf(student, without, dependent);
    return where !== undefined && termIndex(where) <= at;
  });
  if (broken.length) {
    return {
      ok: false,
      message: `${broken.map(courseCode).join(" and ")} ${broken.length > 1 ? "need" : "needs"} ${courseCode(courseId)} first. Move ${broken.length > 1 ? "them" : "it"} later before moving ${courseCode(courseId)}.`,
    };
  }
  const warnings: string[] = [];
  if (!isOffered(courseId, toTerm)) warnings.push(`${courseCode(courseId)} isn't usually offered in ${termLabel(toTerm)}.`);
  const credits = creditsOf([...(without[toTerm] ?? []), courseId]);
  if (credits > PROGRAM.maxCreditsPerTerm) warnings.push(`${termLabel(toTerm)} would have ${credits} credits, over ${PROGRAM.maxCreditsPerTerm}.`);
  return { ok: true, warnings };
}

export interface TermWarning {
  termId: string;
  message: string;
}

/** Problems in a plan: prerequisite order (errors) and offered term / credit load (warnings). */
export function planProblems(student: StudentAccount, plan: DegreePlan): { errors: TermWarning[]; warnings: TermWarning[] } {
  const errors: TermWarning[] = [];
  const warnings: TermWarning[] = [];
  for (const [termId, items] of Object.entries(plan)) {
    for (const id of items.filter((i) => !isSlot(i))) {
      const missing = missingPrereqs(student, plan, id, termId);
      if (missing.length) errors.push({ termId, message: `${courseCode(id)} needs ${missing.map(courseCode).join(" and ")} first.` });
      if (!isOffered(id, termId)) warnings.push({ termId, message: `${courseCode(id)} isn't usually offered in ${termLabel(termId)}.` });
    }
    const credits = creditsOf(items);
    if (credits > PROGRAM.maxCreditsPerTerm) warnings.push({ termId, message: `${termLabel(termId)} has ${credits} credits.` });
  }
  return { errors, warnings };
}

/** Feasible = every requirement covered (by courses or slots) and no prerequisite errors. */
export function isFeasible(student: StudentAccount, plan: DegreePlan): boolean {
  return requirementStatus(student, plan).every((s) => s.remaining === 0) && planProblems(student, plan).errors.length === 0;
}

/**
 * Places every remaining required course in the earliest open term where its prerequisites are
 * already satisfied and it's offered, under the credit cap; remaining elective needs become slots.
 */
export function autoFill(student: StudentAccount, current: DegreePlan, perTermCap = 16): DegreePlan {
  const plan: DegreePlan = Object.fromEntries(TERMS.map((t) => [t.id, [...(current[t.id] ?? [])]]));
  const openTerms = TERMS.filter((t) => termIndex(t.id) >= termIndex(NEXT_TERM) && !student.lockedTerms.includes(t.id));
  const statuses = requirementStatus(student, plan);
  const todo: string[] = [];
  for (const s of statuses) {
    if (s.requirement.kind !== "core" || s.remaining === 0) continue;
    const have = new Set([...s.done, ...s.planned]);
    todo.push(...s.requirement.options.filter((o) => !have.has(o)).slice(0, s.remaining));
  }
  // Place in prerequisite order: repeat until nothing more can go in.
  let progress = true;
  while (todo.length && progress) {
    progress = false;
    for (const id of [...todo]) {
      const term = openTerms.find(
        (t) => isOffered(id, t.id) && missingPrereqs(student, plan, id, t.id).length === 0
          && creditsOf([...plan[t.id], id]) <= perTermCap,
      );
      if (term) {
        plan[term.id].push(id);
        todo.splice(todo.indexOf(id), 1);
        progress = true;
      }
    }
  }
  // Elective and gen-ed needs become slots, spread over the lightest terms.
  for (const s of requirementStatus(student, plan)) {
    for (let n = 0; n < s.remaining; n++) {
      const lightest = [...openTerms].sort((a, b) => creditsOf(plan[a.id]) - creditsOf(plan[b.id]))[0];
      if (lightest) plan[lightest.id].push(`slot:${s.requirement.id}:${n + s.slots + 1}`);
    }
  }
  return plan;
}

/** A course's status on a plan, as the advisor filters it. */
export type CourseStatus = "Done" | "In progress" | "Planned" | "Review" | "Withdraw";
export const COURSE_STATUSES: CourseStatus[] = ["Done", "In progress", "Planned", "Review", "Withdraw"];

/**
 * Transcript terms give Done, In progress or Withdraw. A planned course (or elective slot) is "Review"
 * while the plan waits on the advisor, otherwise "Planned".
 */
export function courseStatus(student: StudentAccount, termId: string, item: string, underReview: boolean): CourseStatus {
  if (student.withdrawn?.[termId]?.includes(item)) return "Withdraw";
  if (student.transcript[termId]?.includes(item)) return termIndex(termId) >= termIndex(CURRENT_TERM) ? "In progress" : "Done";
  return underReview ? "Review" : "Planned";
}

/** Every item the degree grid shows, term by term, with its status. Withdrawn courses come after the term's others. */
export function planItems(student: StudentAccount, plan: DegreePlan, underReview: boolean): { termId: string; item: string; status: CourseStatus }[] {
  return TERMS.flatMap((t) => {
    const items = student.lockedTerms.includes(t.id) ? [...(student.transcript[t.id] ?? []), ...(student.withdrawn?.[t.id] ?? [])] : (plan[t.id] ?? []);
    return items.map((item) => ({ termId: t.id, item, status: courseStatus(student, t.id, item, underReview) }));
  });
}
