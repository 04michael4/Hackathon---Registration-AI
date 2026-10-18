import { afterEach, describe, expect, it, vi } from "vitest";
import { buildGenerateRequest, defaultForm, explainInfeasible, extractPreferences, formFromPreferences, validPreferences } from "./api/scheduleClient";
import type { GenerateRequest, SchedulePreferences } from "./api/types";
import { autoFillSelection, emptyResultReason, extractionIsEmpty, keepPlaced, freshSession, mergeSuggestions, planName, readSavedPlans, savePlan, savedKey, summarizeExtraction, writeSavedPlans, type PlanStorage } from "./planSession";

const WEEKDAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"] as const;
/** What the AI returns for: "I want CSE 140 and MATH 021. I absolutely cannot take classes before 10 AM. I'd like at least an hour between classes." */
const extracted: SchedulePreferences = {
  hardConstraints: { requiredCourseIds: ["CSE-140", "MATH-021"], minCredits: 12, maxCredits: 18, blockedTimes: [{ days: [...WEEKDAYS], start: "00:00", end: "10:00" }] },
  softPreferences: { minBreakMinutes: { value: 60, weight: 3 }, minimizeGaps: { weight: 0 } },
};
const memory = (initial: Record<string, string> = {}): PlanStorage & { data: Record<string, string> } => {
  const data = { ...initial };
  return { data, getItem: key => data[key] ?? null, setItem: (key, value) => { data[key] = value; } };
};
const request = (courses: string[]): GenerateRequest => buildGenerateRequest("2027-SPRING", courses, [], defaultForm);
const plan = (courses: string[], crns: string[]) => ({ name: "n", request: request(courses), crns, courseIds: courses });
afterEach(() => vi.unstubAllGlobals());

describe("AI preferences are applied without a JSON review", () => {
  it("test 1: names the courses and the hard rule in plain words, with no raw structure", () => {
    const summary = summarizeExtraction(extracted, [], ["Credit range not mentioned; used defaults 12–18"]);
    expect(summary.required).toEqual(["CSE 140", "MATH 021"]);
    expect(summary.requirements).toContain("No classes before 10:00 AM");
    expect(summary.preferences).toEqual(["Prefer at least 60 minutes between classes"]);
    expect(summary.requirements).toContain("12–18 credits (default)");
    expect(summary.notes, "a default is not a note").toEqual([]);
    expect(summary.attention).toEqual([]);
    expect(JSON.stringify(summary)).not.toContain("hardConstraints");
    expect(extractionIsEmpty(summary)).toBe(false);
  });
  it("test A: named courses become Available suggestions and never planned ones, and nothing is duplicated or dropped", () => {
    const planned = { "ECO-001": "required" } as const;
    const first = mergeSuggestions({}, extracted, [], { ...planned });
    expect(Object.keys(first)).toEqual(["CSE-140", "MATH-021"]);
    expect(first["MATH-021"].reason).toBe("You asked for this course.");
    // Asking again, and a course that is already planned: still one entry each, planned ones are not suggested.
    const again = mergeSuggestions(first, extracted, [], { ...planned, "CSE-140": "required" });
    expect(Object.keys(again)).toEqual(["MATH-021", "CSE-140"]);
    expect(Object.keys(mergeSuggestions({ "OLD-001": { reason: "earlier" } }, extracted, [], {}))).toEqual(["CSE-140", "MATH-021", "OLD-001"]);
  });
  it("test C/D: catalog matches from an interest are listed with their reason, once, ahead of older suggestions", () => {
    const interest: SchedulePreferences = { hardConstraints: { requiredCourseIds: [], minCredits: 12, maxCredits: 18, blockedTimes: [] }, softPreferences: null };
    const found = [{ courseId: "STAT-243", title: "Statistical Methods", credits: 3, reason: "Matches your interest: its title relates to statistics" }, { courseId: "STAT-243", title: "Statistical Methods", credits: 3, reason: "duplicate" }];
    const merged = mergeSuggestions({ "BIS-324": { reason: "earlier" } }, interest, found, { "BUAN-348": "required" });
    expect(Object.keys(merged)).toEqual(["STAT-243", "BIS-324"]);
    expect(merged["STAT-243"].reason).toContain("statistics");
    expect(extractionIsEmpty(summarizeExtraction(interest), found)).toBe(false);
    expect(extractionIsEmpty(summarizeExtraction(interest), [])).toBe(true);
  });
  it("test 1: the extracted hard rule and wish fill the editable form and survive into the generate request", () => {
    const form = formFromPreferences(extracted, defaultForm);
    expect(form.blockedTimes).toEqual([{ days: [...WEEKDAYS], start: "00:00", end: "10:00" }]);
    const body = buildGenerateRequest("2027-SPRING", ["CSE-140", "MATH-021"], [], form);
    expect(body.preferences.hardConstraints.blockedTimes[0]).toEqual({ days: [...WEEKDAYS], start: "00:00", end: "10:00" });
    expect(body.preferences.softPreferences?.minBreakMinutes).toEqual({ value: 60, weight: 3 });
  });
  it("reports text with no scheduling content so the student can rephrase instead of applying nothing", () => {
    const nothing: SchedulePreferences = { hardConstraints: { requiredCourseIds: [], minCredits: 12, maxCredits: 18, blockedTimes: [] }, softPreferences: null };
    expect(extractionIsEmpty(summarizeExtraction(nothing, ["Underwater Basket Weaving"], [])), "unmatched names are not applied").toBe(true);
    expect(summarizeExtraction(nothing, ["Underwater Basket Weaving"], []).attention).toEqual(["I could not match “Underwater Basket Weaving” to a course in this semester."]);
  });
  it("describes an evening limit and a soft morning wish differently", () => {
    const evening: SchedulePreferences = { hardConstraints: { requiredCourseIds: ["CSE-140"], minCredits: 12, maxCredits: 18, blockedTimes: [{ days: [...WEEKDAYS], start: "16:00", end: "23:59" }] }, softPreferences: { earliestStart: { value: "10:00", weight: 4 } } };
    const summary = summarizeExtraction(evening);
    expect(summary.requirements).toContain("Every class must end by 4:00 PM");
    expect(summary.preferences).toEqual(["Prefer no classes before 10:00 AM"]);
  });
});

describe("the break-between-classes wish", () => {
  it("test 2: is recognized, validated and sent only when the student sets it", () => {
    expect(validPreferences(extracted)).toBe(true);
    expect(validPreferences({ ...extracted, softPreferences: { minBreakMinutes: { value: 300, weight: 3 } } })).toBe(false);
    expect(validPreferences({ ...extracted, softPreferences: { minBreakMinutes: { value: 60, weight: 9 } } })).toBe(false);
    expect(buildGenerateRequest("2027-SPRING", ["CSE-140"], [], defaultForm).preferences.softPreferences).not.toHaveProperty("minBreakMinutes");
    expect(buildGenerateRequest("2027-SPRING", ["CSE-140"], [], { ...defaultForm, minBreak: "90", minBreakWeight: "4" }).preferences.softPreferences?.minBreakMinutes).toEqual({ value: 90, weight: 4 });
    expect(() => buildGenerateRequest("2027-SPRING", ["CSE-140"], [], { ...defaultForm, minBreak: "2" })).toThrow();
  });
});

describe("saved schedules", () => {
  it("test 3: a saved schedule appears in the list and is still there after a refresh (a new read of the same storage)", () => {
    const storage = memory();
    const saved = savePlan(plan(["CSE-140", "MATH-021"], ["11", "21"]), storage, "id-1");
    expect(saved.ok).toBe(true);
    const afterRefresh = readSavedPlans(storage);
    expect(afterRefresh.map(p => p.id)).toEqual(["id-1"]);
    expect(afterRefresh[0]).toMatchObject({ crns: ["11", "21"], courseIds: ["CSE-140", "MATH-021"] });
    expect(savePlan(plan(["ECO-001"], ["31"]), storage, "id-2").ok).toBe(true);
    expect(readSavedPlans(storage).map(p => p.id)).toEqual(["id-1", "id-2"]);
  });
  it("test 3: success is reported only when the plan was actually stored", () => {
    const full: PlanStorage = { getItem: () => null, setItem: () => { throw new DOMException("quota", "QuotaExceededError"); } };
    const outcome = savePlan(plan(["CSE-140"], ["11"]), full, "x");
    expect(outcome.ok).toBe(false);
    expect(savePlan(plan(["CSE-140"], ["11"]), undefined, "x").ok).toBe(false);
    const forgetful: PlanStorage = { getItem: () => null, setItem: () => undefined };
    expect(savePlan(plan(["CSE-140"], ["11"]), forgetful, "x").ok, "a store that does not keep the data is not a success").toBe(false);
  });
  it("names plans readably and numbers duplicates", () => {
    const storage = memory();
    const first = savePlan({ ...plan(["CSE-140", "MATH-021"], ["11"]), name: planName("Spring 2027", ["CSE-140", "MATH-021"], []) }, storage, "a");
    expect(first.ok && first.plan.name).toBe("Spring 2027 · CSE 140, MATH 021");
    expect(planName("Spring 2027", ["CSE-140", "MATH-021"], readSavedPlans(storage))).toBe("Spring 2027 · CSE 140, MATH 021 (2)");
  });
  it("test 4: Clear starts an empty session and never touches stored schedules", () => {
    const storage = memory();
    savePlan(plan(["CSE-140"], ["11"]), storage, "keep-me");
    const before = storage.data[savedKey];
    const fresh = freshSession();
    expect(fresh.selected).toEqual({});
    expect(fresh.form).toEqual(defaultForm);
    expect(fresh.form.blockedTimes).not.toBe(defaultForm.blockedTimes);
    expect(storage.data[savedKey]).toBe(before);
    expect(readSavedPlans(storage).map(p => p.id)).toEqual(["keep-me"]);
    // Courses can be added again: merging into the empty selection works.
    expect(Object.keys(mergeSuggestions({}, extracted, [], fresh.selected))).toEqual(["CSE-140", "MATH-021"]);
  });
  it("ignores corrupt stored data instead of failing", () => {
    expect(readSavedPlans(memory({ [savedKey]: "{not json" }))).toEqual([]);
    expect(readSavedPlans(memory({ [savedKey]: JSON.stringify([{ id: 1 }]) }))).toEqual([]);
    expect(writeSavedPlans([], memory()).ok).toBe(true);
  });
});

describe("no schedule satisfies the hard rules", () => {
  it("test 5: the explanation request names no sections, so the backend explains the engine's own findings", async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json({ explanation: "No schedule fits.", highlights: ["Dropping the 10 AM rule would work"] }));
    vi.stubGlobal("fetch", fetcher);
    const answer = await explainInfeasible("2027-SPRING", extracted);
    expect(answer.highlights).toHaveLength(1);
    expect(fetcher.mock.calls[0][0]).toBe("/api/backend/schedules/explain");
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ term: "2027-SPRING", crns: [], preferences: extracted });
  });
});

describe("when Generate finds nothing", () => {
  it("test B: a plan below the minimum credits is explained in numbers and offers the exact fix without applying it", () => {
    const why = emptyResultReason({ requiredIds: ["MATH-021"], credits: { "MATH-021": 4 }, minCredits: 12, maxCredits: 18 });
    expect(why.text).toContain("4 credits");
    expect(why.text).toContain("minimum is 12");
    expect(why.lowerMinimumTo).toBe(4);
  });
  it("explains a plan above the maximum without offering to change a hard rule", () => {
    const why = emptyResultReason({ requiredIds: ["A-1", "B-1"], credits: { "A-1": 12, "B-1": 9 }, minCredits: 12, maxCredits: 18 });
    expect(why.text).toContain("21 credits");
    expect(why.lowerMinimumTo).toBeNull();
  });
  it("falls back to the general causes when the credits are fine or unknown", () => {
    for (const credits of [{ "A-1": 15 }, {}]) {
      const why = emptyResultReason({ requiredIds: ["A-1"], credits, minCredits: 12, maxCredits: 18 });
      expect(why.text).toContain("blocked time");
      expect(why.text).toContain("not changed");
      expect(why.lowerMinimumTo).toBeNull();
    }
  });
});

describe("extract response", () => {
  it("accepts the older backend reply without suggestions and a reply with real suggestions; rejects malformed ones", async () => {
    const base = { preferences: extracted, unresolved: [], warnings: [] };
    const reply = (body: unknown) => { vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(body))); return extractPreferences("2027-SPRING", "x"); };
    expect((await reply(base)).suggestions).toEqual([]);
    const suggestion = { courseId: "STAT-243", title: "Statistical Methods", credits: 3, reason: "Matches your interest" };
    expect((await reply({ ...base, suggestions: [suggestion] })).suggestions).toEqual([suggestion]);
    await expect(reply({ ...base, suggestions: [{ ...suggestion, courseId: "not a course" }] })).rejects.toMatchObject({ kind: "invalid" });
  });
});

describe("hard time rules and notes in plain words", () => {
  const rules = (blockedTimes: SchedulePreferences["hardConstraints"]["blockedTimes"]): SchedulePreferences => ({ hardConstraints: { requiredCourseIds: [], minCredits: 12, maxCredits: 18, blockedTimes }, softPreferences: null });
  it("test B: states each kind of hard rule as the student would read it", () => {
    const text = (blocks: SchedulePreferences["hardConstraints"]["blockedTimes"]) => summarizeExtraction(rules(blocks)).requirements;
    expect(text([{ days: [...WEEKDAYS], start: "00:00", end: "10:00" }])).toContain("No classes before 10:00 AM");
    expect(text([{ days: [...WEEKDAYS], start: "15:00", end: "23:59" }])).toContain("Every class must end by 3:00 PM");
    expect(text([{ days: [...WEEKDAYS], start: "10:00", end: "23:59" }])).toContain("Every class must end by 10:00 AM");
    expect(text([{ days: ["FRIDAY"], start: "00:00", end: "23:59" }])).toContain("No classes on Fridays");
    expect(text([{ days: ["TUESDAY", "THURSDAY", "FRIDAY"], start: "00:00", end: "23:59" }])).toContain("No classes on Tuesdays, Thursdays, Fridays");
    expect(text([{ days: [...WEEKDAYS], start: "12:00", end: "23:59" }])).toContain("Every class must end by 12:00 PM");
  });
  it("keeps the three 10 AM requests apart in words, in the form and in the request", () => {
    const start: SchedulePreferences = { hardConstraints: { requiredCourseIds: ["MATH-021"], minCredits: 4, maxCredits: 18, blockedTimes: [], latestStart: "10:00" }, softPreferences: null };
    const end: SchedulePreferences = { hardConstraints: { ...start.hardConstraints, latestStart: null, blockedTimes: [{ days: [...WEEKDAYS], start: "10:00", end: "23:59" }] }, softPreferences: null };
    const none: SchedulePreferences = { hardConstraints: { ...start.hardConstraints, latestStart: null, blockedTimes: [{ days: [...WEEKDAYS], start: "00:00", end: "10:00" }] }, softPreferences: null };
    expect(summarizeExtraction(start).requirements).toContain("Every class must start before 10:00 AM (it may end later)");
    expect(summarizeExtraction(end).requirements).toContain("Every class must end by 10:00 AM");
    expect(summarizeExtraction(none).requirements).toContain("No classes before 10:00 AM");
    // the start rule is a field of its own, not a blocked time
    const form = formFromPreferences(start, defaultForm);
    expect(form.latestStart).toBe("10:00");
    expect(form.blockedTimes).toEqual([]);
    const body = buildGenerateRequest("2027-SPRING", ["MATH-021"], [], form);
    expect(body.preferences.hardConstraints.latestStart).toBe("10:00");
    expect(body.preferences.hardConstraints.blockedTimes).toEqual([]);
    expect(buildGenerateRequest("2027-SPRING", ["MATH-021"], [], defaultForm).preferences.hardConstraints).not.toHaveProperty("latestStart");
    expect(validPreferences(start)).toBe(true);
    expect(validPreferences({ ...start, hardConstraints: { ...start.hardConstraints, latestStart: "ten" } })).toBe(false);
    expect(() => buildGenerateRequest("2027-SPRING", ["MATH-021"], [], { ...defaultForm, latestStart: "25:00" })).toThrow();
  });
  it("test E: defaults are shown as defaults, interpretations as notes, and only real problems ask for attention", () => {
    const summary = summarizeExtraction(rules([]), [], [
      "Credit range not mentioned; used defaults 12–18",
      "Read 'only classes before 10 AM' as: every class must end by 10:00 AM",
      "No course fits both your interest and your time rules: 3 related course(s) have no complete set of sections that fits.",
    ]);
    expect(summary.requirements).toContain("12–18 credits (default)");
    expect(summary.notes).toEqual(["Read 'only classes before 10 AM' as: every class must end by 10:00 AM"]);
    expect(summary.attention).toEqual(["No course fits both your interest and your time rules: 3 related course(s) have no complete set of sections that fits."]);
    expect(summarizeExtraction(rules([]), [], []).requirements).toContain("12–18 credits");
  });
  it("carries the verified section summary to the course list, marked as fitting the rules only when rules exist", () => {
    const found = [{ courseId: "STAT-243", title: "Statistical Methods", credits: 3, reason: "Matches", qualifying: "Lecture 010 Mon/Wed/Fri 08:00–08:50" }];
    const withRules = mergeSuggestions({}, rules([{ days: [...WEEKDAYS], start: "00:00", end: "08:00" }]), found, {});
    expect(withRules["STAT-243"]).toMatchObject({ qualifying: "Lecture 010 Mon/Wed/Fri 08:00–08:50", fitsRules: true });
    expect(mergeSuggestions({}, rules([]), found, {})["STAT-243"].fitsRules).toBe(false);
  });
  it("accepts a suggestion with or without a verified section summary", async () => {
    const base = { preferences: rules([]), unresolved: [], warnings: [] };
    const suggestion = { courseId: "CSE-160", title: "Introduction to Data Science", credits: 3, reason: "Matches" };
    const reply = (suggestions: unknown) => { vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ ...base, suggestions }))); return extractPreferences("2027-SPRING", "x"); };
    expect((await reply([{ ...suggestion, qualifying: "Lecture 010 Mon/Wed 10:45–12:00" }])).suggestions[0].qualifying).toBe("Lecture 010 Mon/Wed 10:45–12:00");
    expect((await reply([{ ...suggestion, qualifying: null }])).suggestions).toHaveLength(1);
    await expect(reply([{ ...suggestion, qualifying: 5 }])).rejects.toMatchObject({ kind: "invalid" });
  });
});

describe("Auto-fill", () => {
  it("keeps the plan, requires named courses, offers interest suggestions as optional (max 10), and always has a required course", () => {
    const suggested = { "CSE-160": { reason: "You asked for this course.", named: true }, "BIS-324": { reason: "You asked for this course.", named: true }, "STAT-243": { reason: "Matches your interest" } };
    expect(autoFillSelection({ "ECO-001": "required" }, suggested)).toEqual({ "ECO-001": "required", "CSE-160": "required", "BIS-324": "required", "STAT-243": "optional" });
    const many = Object.fromEntries(Array.from({ length: 14 }, (_, i) => [`DAT-${100 + i}`, { reason: "Matches" }]));
    const picked = autoFillSelection({}, many);
    // ten candidates in all: the best-ranked one is required, the other nine are optional
    expect(Object.values(picked).filter(r => r === "optional")).toHaveLength(9);
    expect(Object.values(picked).filter(r => r === "required"), "a schedule needs one required course").toHaveLength(1);
    expect(picked["DAT-100"]).toBe("required");
    expect(autoFillSelection({}, {})).toEqual({});
  });
  it("after the engine answers, only the courses it placed stay in the plan; the rest go back to Available", () => {
    const kept = keepPlaced({ "CSE-160": "required", "BIS-324": "optional", "STAT-243": "optional" }, new Set(["BIS-324"]));
    expect(kept).toEqual({ "CSE-160": "required", "BIS-324": "required" });
  });
});
