import { ApiError, isRecord, requestJson } from "./httpClient";
import { DAYS, type CheckRequest, type CheckResponse, type ExplainResponse, type ExtractResponse, type GenerateRequest, type GenerateResponse, type Schedule, type SchedulePreferences, type SuggestResponse, type TermsResponse, type TimeBlock } from "./types";

const strings = (v: unknown): v is string[] => Array.isArray(v) && v.every(x => typeof x === "string");
const number = (v: unknown) => typeof v === "number" && Number.isFinite(v);
const integer = (v: unknown) => number(v) && Number.isInteger(v) && Number(v) >= 0;
const time = (v: unknown): v is string => typeof v === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(v);
const weight = (v: unknown) => integer(v) && Number(v) <= 5;
export function validPreferences(v: unknown): boolean {
  if (!isRecord(v) || !isRecord(v.hardConstraints)) return false;
  const h = v.hardConstraints, s = v.softPreferences;
  if (h.latestStart !== undefined && h.latestStart !== null && !time(h.latestStart)) return false;
  if (!strings(h.requiredCourseIds) || !integer(h.minCredits) || Number(h.minCredits) < 1 || !integer(h.maxCredits) || Number(h.maxCredits) > 21 || Number(h.minCredits) > Number(h.maxCredits) || !Array.isArray(h.blockedTimes) || !h.blockedTimes.every(b => isRecord(b) && strings(b.days) && b.days.length > 0 && b.days.every(d => DAYS.includes(d as typeof DAYS[number])) && time(b.start) && time(b.end) && b.start < b.end)) return false;
  if (s === undefined || s === null) return true;
  if (!isRecord(s)) return false;
  for (const key of ["optionalCourseIds", "earliestStart", "latestEnd", "daysOff", "minimizeGaps", "minBreakMinutes"]) {
    const preference = s[key];
    if (preference === undefined || preference === null) continue;
    if (!isRecord(preference) || !weight(preference.weight)) return false;
    if (key === "optionalCourseIds" && (!strings(preference.value) || preference.value.length > 10)) return false;
    if (["earliestStart", "latestEnd"].includes(key) && !time(preference.value)) return false;
    if (key === "minBreakMinutes" && (!integer(preference.value) || Number(preference.value) < 5 || Number(preference.value) > 240)) return false;
    if (key === "daysOff" && (!strings(preference.value) || !preference.value.every(d => DAYS.includes(d as typeof DAYS[number])))) return false;
  }
  return true;
}
export function validSchedule(value: unknown): boolean {
  return isRecord(value) && number(value.score) && Number(value.score) >= 0 && Number(value.score) <= 100 && integer(value.totalCredits) && typeof value.overload === "boolean" &&
    isRecord(value.scoreBreakdown) && Object.values(value.scoreBreakdown).every(number) && Array.isArray(value.sections) && value.sections.every(s => isRecord(s) &&
      [s.crn, s.courseId, s.sectionCode, s.title].every(v => typeof v === "string") && integer(s.credits) && Array.isArray(s.meetings) && s.meetings.every(m => isRecord(m) && strings(m.days) && m.days.every(d => DAYS.includes(d as typeof DAYS[number])) && time(m.start) && time(m.end) && m.start < m.end && (m.location === null || typeof m.location === "string")));
}
export function validGeneration(v: unknown): boolean {
  return isRecord(v) && typeof v.term === "string" && Array.isArray(v.schedules) && v.schedules.every(s => validSchedule(s) && isRecord(s) && integer(s.rank) && Number(s.rank) >= 1) && strings(v.diagnostics) && typeof v.searchLimitReached === "boolean" && Array.isArray(v.electiveAdvice) && v.electiveAdvice.every(a => isRecord(a) && typeof a.courseId === "string" && ["IN_TOP_SCHEDULE", "IN_OTHER_SUGGESTION", "FITS_WITH_CHANGES", "CANNOT_FIT"].includes(String(a.status)) && typeof a.summary === "string" && strings(a.fixes) && strings(a.alternativeCrns));
}
export function validCheck(v: unknown): boolean {
  return validSchedule(v) && isRecord(v) && typeof v.term === "string" && typeof v.complete === "boolean" && Array.isArray(v.issues) && v.issues.every(i => isRecord(i) && typeof i.type === "string" && ["CONFLICT", "INCOMPLETE"].includes(String(i.severity)) && strings(i.crns) && typeof i.message === "string") && Array.isArray(v.candidates) && v.candidates.every(c => isRecord(c) && typeof c.crn === "string" && typeof c.courseId === "string" && typeof c.fits === "boolean" && typeof c.completesLecture === "boolean" && strings(c.reasons));
}
export interface PreferenceForm {
  minCredits: string; maxCredits: string; maxResults: string; includeClosedSections: boolean;
  optionalWeight: string; earliest: string; earliestWeight: string; latest: string; latestWeight: string;
  daysOff: typeof DAYS[number][]; daysWeight: string; gapsWeight: string; minBreak: string; minBreakWeight: string; latestStart: string; blockedTimes: TimeBlock[];
}
export const defaultForm: PreferenceForm = { minCredits: "12", maxCredits: "18", maxResults: "5", includeClosedSections: false, optionalWeight: "3", earliest: "", earliestWeight: "3", latest: "", latestWeight: "3", daysOff: [], daysWeight: "3", gapsWeight: "0", minBreak: "", minBreakWeight: "3", latestStart: "", blockedTimes: [] };
export function buildGenerateRequest(term: string, required: string[], optional: string[], form: PreferenceForm): GenerateRequest {
  const errors: { field: string; message: string }[] = [];
  const numeric = (field: keyof PreferenceForm, min: number, max: number) => {
    const raw = form[field]; const n = typeof raw === "string" && /^\d+$/.test(raw) ? Number(raw) : NaN;
    if (!Number.isInteger(n) || n < min || n > max) errors.push({ field, message: `Must be a whole number between ${min} and ${max}.` });
    return n;
  };
  const minCredits = numeric("minCredits", 1, 21), maxCredits = numeric("maxCredits", 1, 21), maxResults = numeric("maxResults", 1, 10);
  if (minCredits > maxCredits) errors.push({ field: "minCredits", message: "Minimum credits cannot exceed maximum credits." });
  if (!term) errors.push({ field: "term", message: "Select a catalog term." });
  if (!required.length) errors.push({ field: "requiredCourseIds", message: "Select at least one required course." });
  if (optional.length > 10) errors.push({ field: "optionalCourseIds", message: "Choose no more than 10 optional courses." });
  const minBreakWeight = numeric("minBreakWeight", 0, 5), optionalWeight = numeric("optionalWeight", 0, 5), earliestWeight = numeric("earliestWeight", 0, 5), latestWeight = numeric("latestWeight", 0, 5), daysWeight = numeric("daysWeight", 0, 5), gapsWeight = numeric("gapsWeight", 0, 5);
  for (const field of ["earliest", "latest"] as const) if (form[field] && !time(form[field])) errors.push({ field, message: "Use a valid 24-hour time (HH:mm)." });
  const minBreak = form.minBreak.trim() === "" ? 0 : numeric("minBreak", 5, 240);
  if (form.latestStart && !time(form.latestStart)) errors.push({ field: "latestStart", message: "Use a valid 24-hour time (HH:mm)." });
  form.blockedTimes.forEach((b, i) => { if (!b.days.length || b.days.some(d => !DAYS.includes(d)) || !time(b.start) || !time(b.end) || b.start >= b.end) errors.push({ field: `blockedTimes[${i}]`, message: "Choose days and an end time after the start time." }); });
  if (errors.length) throw new ApiError("Correct these preferences before generating a schedule.", 400, errors);
  return { term, maxResults, includeClosedSections: form.includeClosedSections, preferences: {
    hardConstraints: { requiredCourseIds: [...new Set(required)], minCredits, maxCredits, blockedTimes: form.blockedTimes, ...(form.latestStart ? { latestStart: form.latestStart } : {}) },
    softPreferences: { optionalCourseIds: { value: [...new Set(optional)], weight: optionalWeight },
      ...(form.earliest ? { earliestStart: { value: form.earliest, weight: earliestWeight } } : {}),
      ...(form.latest ? { latestEnd: { value: form.latest, weight: latestWeight } } : {}),
      ...(form.daysOff.length ? { daysOff: { value: form.daysOff, weight: daysWeight } } : {}), minimizeGaps: { weight: gapsWeight },
      ...(minBreak > 0 ? { minBreakMinutes: { value: minBreak, weight: minBreakWeight } } : {}) },
  } };
}
export function formFromPreferences(preferences: SchedulePreferences, current: PreferenceForm): PreferenceForm {
  const h = preferences.hardConstraints, s = preferences.softPreferences;
  return { ...current, minCredits: String(h.minCredits), maxCredits: String(h.maxCredits), blockedTimes: h.blockedTimes ?? [], latestStart: h.latestStart ?? "", optionalWeight: String(s?.optionalCourseIds?.weight ?? 3), earliest: s?.earliestStart?.value ?? "", earliestWeight: String(s?.earliestStart?.weight ?? 3), latest: s?.latestEnd?.value ?? "", latestWeight: String(s?.latestEnd?.weight ?? 3), daysOff: s?.daysOff?.value ?? [], daysWeight: String(s?.daysOff?.weight ?? 3), gapsWeight: String(s?.minimizeGaps?.weight ?? 0), minBreak: s?.minBreakMinutes ? String(s.minBreakMinutes.value) : "", minBreakWeight: String(s?.minBreakMinutes?.weight ?? 3) };
}
const base = "/api/backend/";
/** Claude can take longer than a catalog read; the proxy allows the same time. */
const AI_TIMEOUT_MS = 65_000;
export const loadTerms = (signal?: AbortSignal) => requestJson<TermsResponse>(base + "terms", { signal }, v => isRecord(v) && Array.isArray(v.items) && v.items.every(t => isRecord(t) && typeof t.term === "string" && typeof t.name === "string" && (t.catalogSnapshotDate === null || typeof t.catalogSnapshotDate === "string")));
export const generateSchedules = (body: GenerateRequest, signal?: AbortSignal) => requestJson<GenerateResponse>(base + "schedules/generate", { body, signal }, validGeneration);
export const checkSchedule = (body: CheckRequest, signal?: AbortSignal) => requestJson<CheckResponse>(base + "schedules/check", { body, signal }, validCheck);
export const suggestElectives = (body: CheckRequest & { q?: string; subject?: string; limit?: number }, signal?: AbortSignal) => requestJson<SuggestResponse>(base + "schedules/suggest-electives", { body, signal }, v => isRecord(v) && typeof v.term === "string" && Array.isArray(v.suggestions) && v.suggestions.every(s => validSchedule(s) && isRecord(s) && typeof s.courseId === "string" && typeof s.title === "string"));
const validSuggestion = (v: unknown) => isRecord(v) && typeof v.courseId === "string" && /^[A-Z]+-\d+(?:-\d+)?$/.test(v.courseId) && typeof v.title === "string" && number(v.credits) && typeof v.reason === "string" && (v.qualifying === undefined || v.qualifying === null || typeof v.qualifying === "string");
/** `suggestions` is missing from older backends and is then treated as empty; malformed entries make the whole reply invalid. */
export const extractPreferences = async (term: string, text: string, signal?: AbortSignal): Promise<ExtractResponse> => {
  const data = await requestJson<Omit<ExtractResponse, "suggestions"> & { suggestions?: unknown }>(base + "preferences/extract", { body: { term, text }, signal, timeoutMs: AI_TIMEOUT_MS },
    v => isRecord(v) && validPreferences(v.preferences) && strings(v.unresolved) && strings(v.warnings) && (v.suggestions === undefined || (Array.isArray(v.suggestions) && v.suggestions.every(validSuggestion))));
  return { ...data, suggestions: (data.suggestions ?? []) as ExtractResponse["suggestions"] };
};
export const explainSchedule = (term: string, schedule: Schedule, preferences: SchedulePreferences, signal?: AbortSignal) => requestJson<ExplainResponse>(base + "schedules/explain", { body: { term, crns: schedule.sections.map(s => s.crn), preferences }, signal, timeoutMs: AI_TIMEOUT_MS }, v => isRecord(v) && typeof v.explanation === "string" && strings(v.highlights));
/** No schedule satisfies the rules: Claude explains, from the engine's own diagnostics, which rules block it and what could be relaxed. */
export const explainInfeasible = (term: string, preferences: SchedulePreferences, signal?: AbortSignal) => requestJson<ExplainResponse>(base + "schedules/explain", { body: { term, crns: [], preferences }, signal, timeoutMs: AI_TIMEOUT_MS }, v => isRecord(v) && typeof v.explanation === "string" && strings(v.highlights));
