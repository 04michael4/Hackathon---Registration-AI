import { isRecord } from "./api/httpClient";
import { defaultForm, validPreferences, type PreferenceForm } from "./api/scheduleClient";
import type { CourseSuggestion, GenerateRequest, SchedulePreferences } from "./api/types";

/** A schedule the student saved in this browser. Only CRNs are stored; opening one re-checks them against the live catalog. */
export interface SavedPlan { id: string; name: string; request: GenerateRequest; crns: string[]; courseIds: string[]; savedAt?: string; credits?: number }
export const savedKey = "smartcourse-api-plans-v1";
export type PlanStorage = Pick<Storage, "getItem" | "setItem">;
export type Selection = Record<string, "required" | "optional">;

/** The browser's localStorage, or undefined when it is blocked or unavailable (private windows, server rendering). */
export function browserStorage(): PlanStorage | undefined {
  try { return typeof window === "undefined" ? undefined : window.localStorage; } catch { return undefined; }
}

const validPlan = (item: unknown): item is SavedPlan => isRecord(item) && typeof item.id === "string" && typeof item.name === "string" && Array.isArray(item.crns) && item.crns.length <= 60 && item.crns.every((crn: unknown) => typeof crn === "string") && Array.isArray(item.courseIds) && item.courseIds.length <= 30 && item.courseIds.every((id: unknown) => typeof id === "string") && isRecord(item.request) && typeof item.request.term === "string" && typeof item.request.includeClosedSections === "boolean" && Number.isInteger(item.request.maxResults) && Number(item.request.maxResults) >= 1 && Number(item.request.maxResults) <= 10 && validPreferences(item.request.preferences);

export function readSavedPlans(storage: PlanStorage | undefined = browserStorage()): SavedPlan[] {
  if (!storage) return [];
  try {
    const value: unknown = JSON.parse(storage.getItem(savedKey) ?? "[]");
    return Array.isArray(value) ? value.filter(validPlan) : [];
  } catch { return []; }
}

/** Writes the list and reads it back, so a full or blocked store (or a plan the reader would reject) is reported instead of hidden. */
export function writeSavedPlans(plans: SavedPlan[], storage: PlanStorage | undefined = browserStorage()): { ok: true } | { ok: false; message: string } {
  if (!storage) return { ok: false, message: "Browser storage is unavailable, so the schedule could not be saved." };
  try {
    storage.setItem(savedKey, JSON.stringify(plans));
    const stored = readSavedPlans(storage);
    if (stored.length !== plans.length || plans.some((plan, i) => stored[i]?.id !== plan.id)) return { ok: false, message: "The schedule could not be stored in this browser." };
    return { ok: true };
  } catch { return { ok: false, message: "Browser storage is full or blocked, so the schedule could not be saved." }; }
}

/** "Spring 2027 · CSE 140, MATH 021", numbered when that name is already taken. */
export function planName(termName: string, courseIds: string[], existing: SavedPlan[]): string {
  const courses = courseIds.map(courseLabel).slice(0, 4).join(", ") + (courseIds.length > 4 ? ` +${courseIds.length - 4}` : "");
  const base = `${termName} · ${courses || "My plan"}`.slice(0, 110);
  const taken = new Set(existing.map(plan => plan.name));
  if (!taken.has(base)) return base;
  let n = 2;
  while (taken.has(`${base} (${n})`)) n++;
  return `${base} (${n})`;
}

/** Adds one plan to the stored list. Success means the plan was read back from storage; the caller shows "saved" only then. */
export function savePlan(plan: Omit<SavedPlan, "id" | "savedAt">, storage: PlanStorage | undefined = browserStorage(), id: string = crypto.randomUUID()): { ok: true; plans: SavedPlan[]; plan: SavedPlan } | { ok: false; message: string } {
  const existing = readSavedPlans(storage);
  const saved: SavedPlan = { ...plan, id, savedAt: new Date().toISOString() };
  const plans = [...existing, saved];
  const written = writeSavedPlans(plans, storage);
  return written.ok ? { ok: true, plans, plan: saved } : written;
}

export const courseLabel = (courseId: string) => courseId.replace("-", " ");

export interface Suggested { [courseId: string]: { reason: string; qualifying?: string | null; fitsRules?: boolean; named?: boolean } }

/**
 * What the AI found goes to the Course List as Available; nothing here touches the plan. Courses the student named, and
 * courses the catalog search matched to an interest, are listed newest first, once each, and never ones already planned.
 */
export function mergeSuggestions(current: Suggested, preferences: SchedulePreferences, found: CourseSuggestion[], planned: Selection): Suggested {
  const fresh: Suggested = {}, hasRules = preferences.hardConstraints.blockedTimes.length > 0;
  const named = [...preferences.hardConstraints.requiredCourseIds, ...(preferences.softPreferences?.optionalCourseIds?.value ?? [])];
  for (const id of named) if (!planned[id]) fresh[id] = { reason: "You asked for this course.", named: true };
  for (const course of found) if (!planned[course.courseId] && !fresh[course.courseId]) fresh[course.courseId] = { reason: course.reason, qualifying: course.qualifying ?? null, fitsRules: hasRules };
  const older = Object.fromEntries(Object.entries(current).filter(([id]) => !fresh[id]));
  return { ...fresh, ...older };
}

/**
 * The choice Auto-fill hands to the scheduling engine: what is already planned stays; courses the student named become required;
 * interest suggestions become optional (the engine places as many as fit, up to 10). If nothing is required yet, the best-ranked
 * course is, because a schedule needs at least one. The engine, not this function, decides what fits.
 */
export function autoFillSelection(selected: Selection, suggested: Suggested): Selection {
  const next: Selection = { ...selected };
  let optional = Object.values(next).filter(role => role === "optional").length;
  for (const [id, course] of Object.entries(suggested)) {
    if (next[id]) continue;
    if (course.named) next[id] = "required";
    else if (optional < 10) { next[id] = "optional"; optional++; }
  }
  if (!Object.values(next).includes("required")) {
    const first = Object.keys(next)[0];
    if (first) next[first] = "required";
  }
  return next;
}

/** After Auto-fill: the courses the engine actually placed become part of the plan; the rest return to Available. */
export function keepPlaced(selection: Selection, placedCourseIds: Set<string>): Selection {
  return Object.fromEntries(Object.entries(selection).filter(([id, role]) => role === "required" || placedCourseIds.has(id)).map(([id]) => [id, "required" as const]));
}

/** Why Generate found nothing, in words, when the cause is visible from the credits alone; otherwise the usual hard-rule causes. */
export function emptyResultReason(input: { requiredIds: string[]; credits: Record<string, number | undefined>; minCredits: number; maxCredits: number }): { text: string; lowerMinimumTo: number | null } {
  const known = input.requiredIds.map(id => input.credits[id]);
  if (input.requiredIds.length && known.every((c): c is number => typeof c === "number")) {
    const total = known.reduce((sum, c) => sum + c, 0);
    if (total < input.minCredits) return { text: `Your plan has ${total} credits, but the minimum is ${input.minCredits}. Add more courses, or lower the minimum credits in Planning preferences. Nothing was changed.`, lowerMinimumTo: total };
    if (total > input.maxCredits) return { text: `Your required courses add up to ${total} credits, above the maximum of ${input.maxCredits}. Remove a course or raise the maximum credits in Planning preferences.`, lowerMinimumTo: null };
  }
  return { text: "No combination of sections satisfies every requirement. Possible causes: two required courses meet at the same time, a blocked time rules out every section, or the credit range cannot be met. Remove a course or change a blocked time, then generate again. Your plan was not changed.", lowerMinimumTo: null };
}

const clock = (value: string) => {
  const [h, m] = value.split(":").map(Number);
  return `${h % 12 === 0 ? 12 : h % 12}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`;
};
const dayName = (day: string) => day.charAt(0) + day.slice(1).toLowerCase();
const shortDays = (days: string[]) => days.length === 5 && ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"].every(d => days.includes(d)) ? "Every weekday" : days.map(d => dayName(d).slice(0, 3)).join("/");
const WEEKDAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"];

/** `attention` is what the student must act on (unmatched names, nothing found); `notes` is how the AI read the request. Defaults are neither. */
export interface ExtractionSummary { required: string[]; optional: string[]; requirements: string[]; preferences: string[]; notes: string[]; attention: string[] }

const DEFAULT_CREDITS = /^Credit range not mentioned/;
const NEEDS_ACTION = /^(No course|Ignored|Requested|Minimum|More than|No required)/;

/** A plain-language account of what the AI understood. Hard rules (blocked times, credits) are kept apart from ranking wishes. */
export function summarizeExtraction(preferences: SchedulePreferences, unresolved: string[] = [], warnings: string[] = []): ExtractionSummary {
  const hard = preferences.hardConstraints, soft = preferences.softPreferences ?? {};
  const requirements: string[] = [], wishes: string[] = [];
  for (const block of hard.blockedTimes ?? []) {
    const everyWeekday = WEEKDAYS.every(day => (block.days as string[]).includes(day));
    const wholeDay = block.start === "00:00" && block.end === "23:59";
    requirements.push(wholeDay ? `No classes on ${block.days.map(d => dayName(d) + "s").join(", ")}`
      : everyWeekday && block.start === "00:00" ? `No classes before ${clock(block.end)}`
      : everyWeekday && block.end === "23:59" ? `Every class must end by ${clock(block.start)}`
      : `No classes ${shortDays(block.days)} ${clock(block.start)}–${clock(block.end)}`);
  }
  if (hard.latestStart) requirements.push(`Every class must start before ${clock(hard.latestStart)} (it may end later)`);
  requirements.push(`${hard.minCredits}–${hard.maxCredits} credits${warnings.some(w => DEFAULT_CREDITS.test(w)) ? " (default)" : ""}`);
  if (soft.earliestStart) wishes.push(`Prefer no classes before ${clock(soft.earliestStart.value)}`);
  if (soft.latestEnd) wishes.push(`Prefer classes to end by ${clock(soft.latestEnd.value)}`);
  if (soft.daysOff?.value.length) wishes.push(`Prefer ${soft.daysOff.value.map(d => dayName(d) + "s").join(", ")} off`);
  if (soft.minBreakMinutes) wishes.push(`Prefer at least ${soft.minBreakMinutes.value} minutes between classes`);
  if (soft.minimizeGaps && soft.minimizeGaps.weight > 0) wishes.push("Prefer back-to-back classes (few gaps)");
  return { required: hard.requiredCourseIds.map(courseLabel), optional: (soft.optionalCourseIds?.value ?? []).map(courseLabel), requirements, preferences: wishes,
    attention: [...unresolved.map(name => `I could not match “${name}” to a course in this semester.`), ...warnings.filter(w => NEEDS_ACTION.test(w))],
    notes: warnings.filter(w => !DEFAULT_CREDITS.test(w) && !NEEDS_ACTION.test(w)) };
}

/** True when the AI found nothing it can apply (no courses and no timing wishes), so the student should rephrase. */
export const extractionIsEmpty = (summary: ExtractionSummary, suggestions: CourseSuggestion[] = []) => !suggestions.length && !summary.required.length && !summary.optional.length && !summary.preferences.length && summary.requirements.length <= 1;

/** The working session after Clear: nothing selected and default preferences. Saved schedules live in storage and are not part of it. */
export function freshSession(): { selected: Selection; form: PreferenceForm } {
  return { selected: {}, form: { ...defaultForm, blockedTimes: [], daysOff: [] } };
}
