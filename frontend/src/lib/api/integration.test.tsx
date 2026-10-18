import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, requestJson } from "./httpClient";
import { proxyBackend } from "./backendProxy";
import { buildGenerateRequest, defaultForm, generateSchedules, validGeneration, validPreferences } from "./scheduleClient";
import { ApiWeekCalendar, ScheduleAlternatives, ScheduleFacts } from "../../components/ApiScheduleResults";
import { ApiPreferences } from "../../components/ApiPreferences";
import { ErrorNotice } from "../../components/ConnectedPlanner";
import type { GenerateResponse, Schedule } from "./types";

const schedule: Schedule = { rank: 1, score: 87.25, totalCredits: 7, overload: false, scoreBreakdown: { earliestStart: 75.5 }, sections: [
  { crn: "40100", courseId: "MATH-021", sectionCode: "010", title: "Synthetic math", credits: 4, meetings: [{ days: ["MONDAY"], start: "09:30", end: "10:45", location: null }] },
  { crn: "40200", courseId: "ENGL-001", sectionCode: "010", title: "Synthetic TBA", credits: 3, meetings: [] },
] };
const response: GenerateResponse = { term: "2027-SPRING", schedules: [schedule, { ...schedule, rank: 2, score: 70 }], diagnostics: [], searchLimitReached: false, electiveAdvice: [] };
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });
describe("schedule contract and validation", () => {
  it("sends exact API fields, numeric values and all weighted preferences", async () => {
    const body = buildGenerateRequest("2027-SPRING", ["MATH-021"], ["ENGL-001"], { ...defaultForm, minCredits: "4", maxCredits: "7", maxResults: "2", earliest: "09:00", latest: "17:00", daysOff: ["FRIDAY"], includeClosedSections: true, blockedTimes: [{ days: ["SUNDAY"], start: "12:00", end: "13:00" }] });
    expect(body).toEqual({ term: "2027-SPRING", maxResults: 2, includeClosedSections: true, preferences: { hardConstraints: { requiredCourseIds: ["MATH-021"], minCredits: 4, maxCredits: 7, blockedTimes: [{ days: ["SUNDAY"], start: "12:00", end: "13:00" }] }, softPreferences: { optionalCourseIds: { value: ["ENGL-001"], weight: 3 }, earliestStart: { value: "09:00", weight: 3 }, latestEnd: { value: "17:00", weight: 3 }, daysOff: { value: ["FRIDAY"], weight: 3 }, minimizeGaps: { weight: 0 } } } });
    const fetcher = vi.fn().mockResolvedValue(Response.json(response)); vi.stubGlobal("fetch", fetcher);
    expect(await generateSchedules(body)).toEqual(response);
    expect(fetcher.mock.calls[0][0]).toBe("/api/backend/schedules/generate");
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual(body);
    expect(fetcher.mock.calls[0][1].headers["Content-Type"]).toBe("application/json");
  });
  it.each([{ minCredits: "0" }, { maxCredits: "22" }, { minCredits: "19", maxCredits: "18" }, { maxResults: "0" }, { maxResults: "11" }, { optionalWeight: "6" }, { gapsWeight: "-1" }, { minCredits: "1.5" }, { maxCredits: "" }, { earliest: "25:00" }, { blockedTimes: [{ days: ["MONDAY"], start: "12:00", end: "11:00" }] }])("rejects invalid preferences %j before a request", patch => {
    expect(() => buildGenerateRequest("2027-SPRING", ["MATH-021"], [], { ...defaultForm, ...patch } as typeof defaultForm)).toThrow(ApiError);
  });
  it("requires courses and limits optional entries even when their weight is zero", () => {
    expect(() => buildGenerateRequest("2027-SPRING", [], [], defaultForm)).toThrow(ApiError);
    expect(() => buildGenerateRequest("2027-SPRING", ["MATH-021"], Array.from({ length: 11 }, (_, i) => `OPT-${i}`), { ...defaultForm, optionalWeight: "0" })).toThrow(ApiError);
  });
  it("rejects malformed scores and meetings, accepts empty real results", () => {
    expect(validGeneration(response)).toBe(true);
    expect(validGeneration({ ...response, schedules: [] })).toBe(true);
    expect(validGeneration({ ...response, schedules: [{ ...schedule, score: "87.25" }] })).toBe(false);
    expect(validGeneration({ ...response, schedules: [{ ...schedule, sections: [{ ...schedule.sections[0], meetings: [{ days: ["MONDAY"], start: "10:00", end: "09:00", location: null }] }] }] })).toBe(false);
  });
  it("checks extracted and saved preferences before exposing them to editable controls", () => {
    const preferences = buildGenerateRequest("2027-SPRING", ["MATH-021"], [], defaultForm).preferences;
    expect(validPreferences(preferences)).toBe(true);
    expect(validPreferences({ ...preferences, hardConstraints: { ...preferences.hardConstraints, requiredCourseIds: [] } })).toBe(true);
    expect(validPreferences({ ...preferences, softPreferences: { daysOff: { value: ["FUNDAY"], weight: 3 } } })).toBe(false);
    expect(validPreferences({ ...preferences, softPreferences: { earliestStart: { value: "09:00", weight: 99 } } })).toBe(false);
    expect(validPreferences({ ...preferences, hardConstraints: { ...preferences.hardConstraints, minCredits: "12" } })).toBe(false);
  });
});
describe("shared HTTP behavior", () => {
  it.each([400, 401, 403, 404, 409, 422, 500, 503])("handles HTTP %i and preserves validation/reference metadata", async status => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ type: "about:blank", title: "Failure", status, detail: "Safe backend message", requestId: "fixture-id", errors: [{ field: "maxResults", message: "between 1 and 10" }] }, { status })));
    await expect(requestJson("/api/backend/schedules/generate")).rejects.toMatchObject({ status, requestId: "fixture-id", errors: [{ field: "maxResults", message: "between 1 and 10" }] });
  });
  it("distinguishes unavailable networks, invalid JSON and empty bodies", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("fetch failed")));
    await expect(requestJson("/api/backend/terms")).rejects.toMatchObject({ kind: "network" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>")));
    await expect(requestJson("/api/backend/terms")).rejects.toMatchObject({ kind: "invalid" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    expect(await requestJson("/api/backend/terms")).toBeUndefined();
  });
  it("preserves caller cancellation and reports timeouts", async () => {
    vi.stubGlobal("fetch", (_path: string, options: RequestInit) => new Promise((_resolve, reject) => options.signal?.addEventListener("abort", () => reject(options.signal?.reason))));
    await expect(requestJson("/api/backend/terms", { timeoutMs: 5 })).rejects.toMatchObject({ kind: "timeout" });
    const controller = new AbortController(); const pending = requestJson("/api/backend/terms", { signal: controller.signal }); controller.abort();
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });
});
describe("backend proxy", () => {
  it("preserves POST validation status and JSON without forwarding cookies", async () => {
    const body = buildGenerateRequest("2027-SPRING", ["MATH-021"], [], defaultForm);
    const problem = { type: "about:blank", title: "Bad Request", status: 400, detail: "Invalid input", errors: [{ field: "maxResults", message: "between 1 and 10" }], requestId: "fixture" };
    const fetcher = vi.fn().mockResolvedValue(Response.json(problem, { status: 400, headers: { "X-Request-Id": "fixture" } })); vi.stubGlobal("fetch", fetcher);
    const result = await proxyBackend(new Request("http://localhost/api/backend/schedules/generate", { method: "POST", body: JSON.stringify(body), headers: { "Content-Type": "application/json", Cookie: "secret=fixture", Authorization: "Bearer fixture" } }), ["schedules", "generate"], "http://127.0.0.1:8080/");
    expect(String(fetcher.mock.calls[0][0])).toBe("http://127.0.0.1:8080/api/schedules/generate");
    expect(fetcher.mock.calls[0][1].headers).toEqual({ Accept: "application/json", "Content-Type": "application/json" });
    expect(result.status).toBe(400); expect(await result.json()).toEqual(problem); expect(result.headers.get("X-Request-Id")).toBe("fixture");
  });
  it("blocks unsupported paths, invalid configuration, malformed JSON and oversize bodies", async () => {
    const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
    expect((await proxyBackend(new Request("http://localhost/api/backend/admin"), ["admin"], "http://localhost:8080")).status).toBe(404);
    expect((await proxyBackend(new Request("http://localhost/api/backend/terms"), ["terms"], "http://user:pass@localhost:8080")).status).toBe(503);
    for (const [body, status] of [["{", 400], ["x".repeat(65537), 413]] as const) expect((await proxyBackend(new Request("http://localhost/api/backend/schedules/generate", { method: "POST", headers: { "Content-Type": "application/json" }, body }), ["schedules", "generate"], "http://localhost:8080")).status).toBe(status);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("returns a safe bad-gateway response for invalid upstream JSON", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>internal error</html>")));
    const response = await proxyBackend(new Request("http://localhost/api/backend/terms"), ["terms"], "http://localhost:8080");
    expect(response.status).toBe(502); expect(await response.text()).not.toContain("internal error");
  });
});
describe("actual schedule presentation", () => {
  it("renders alternatives, exact scores and server credit/overload metadata", () => {
    const html = renderToStaticMarkup(createElement(ScheduleAlternatives, { result: response, selected: 1, onSelect: () => {} }));
    expect(html).toContain("Alternative 1"); expect(html).toContain("Alternative 2"); expect(html).toContain("87.25"); expect(html).toContain('aria-pressed="true"');
    const facts = renderToStaticMarkup(createElement(ScheduleFacts, { schedule: { ...schedule, totalCredits: 19, overload: true } }));
    expect(facts).toContain("19 credits"); expect(facts).toContain("75.5"); expect(facts).toContain("Overload");
  });
  it("creates only actual calendar events and lists TBA sections separately", () => {
    const html = renderToStaticMarkup(createElement(ApiWeekCalendar, { sections: schedule.sections }));
    expect(html).toContain('data-event-crn="40100"'); expect(html).not.toContain('data-event-crn="40200"'); expect(html).toContain("TBA / arranged / asynchronous"); expect(html).toContain("09:30");
  });
  it("keeps the familiar weekday calendar and includes real weekend meetings when present", () => {
    const weekdays = renderToStaticMarkup(createElement(ApiWeekCalendar, { sections: schedule.sections }));
    expect(weekdays.match(/>SATURDAY</g)).toHaveLength(1); // Expanded calendar always includes the weekend.
    const weekend = renderToStaticMarkup(createElement(ApiWeekCalendar, { sections: [{ ...schedule.sections[0], meetings: [{ days: ["SATURDAY"], start: "06:30", end: "07:45", location: null }] }] }));
    expect(weekend.match(/>SATURDAY</g)).toHaveLength(2);
    expect(weekend).toContain("06:30"); expect(weekend).toContain("7:00 AM");
  });
  it("renders empty results, field errors and editable preference controls", () => {
    expect(renderToStaticMarkup(createElement(ScheduleAlternatives, { result: { ...response, schedules: [] }, selected: 0, onSelect: () => {} }))).toContain("No feasible schedules were returned");
    const error = new ApiError("Correct preferences", 400, [{ field: "maxCredits", message: "At most 21" }]);
    expect(renderToStaticMarkup(createElement(ErrorNotice, { error }))).toContain("At most 21");
    expect(renderToStaticMarkup(createElement(ApiPreferences, { value: defaultForm, onChange: () => {}, errors: error.errors }))).toContain('aria-invalid="true"');
  });
});
