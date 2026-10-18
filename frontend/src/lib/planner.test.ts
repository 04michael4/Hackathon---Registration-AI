import { describe, expect, it } from "vitest";
import { catalog, defaultState } from "./mock-data";
import { conflicts, degreeAudit, eligibility, timeRange } from "./planner";

describe("planner rules", () => {
  it("parses demo section times", () => {
    expect(timeRange(catalog.cse241)).toEqual([550, 625]);
    expect(timeRange(catalog.cse398)).toBeNull();
  });

  it("detects overlapping demo sections deterministically", () => {
    expect(conflicts(["ds210", "eco001"])).toEqual([["ds210", "eco001"]]);
    expect(conflicts(["cse241", "math231"])).toEqual([]);
  });

  it("keeps ineligible courses out of the next-semester planner", () => {
    expect(eligibility("cse398", "spring-2027").ok).toBe(false);
    expect(eligibility("cse241", "spring-2027").ok).toBe(true);
  });

  it("requires both electives before the demo degree plan is complete", () => {
    const initial = degreeAudit(defaultState);
    expect(initial.valid).toBe(false);
    expect(initial.issues).toContain("Choose a course for the 4-credit elective slot.");

    const complete = structuredClone(defaultState);
    complete.electives["elective-a"] = "eco001";
    complete.electives["elective-b"] = "art003";
    expect(degreeAudit(complete)).toMatchObject({ valid: true, totalCredits: 120, graduation: "May 2028" });
  });
});
