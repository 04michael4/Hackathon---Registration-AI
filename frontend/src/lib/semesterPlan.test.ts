import { describe, expect, it } from "vitest";
import { buildSemesterPlan } from "./semesterPlan";
import type { CourseSummary, ScheduleSection } from "./api/types";

const course: CourseSummary = { courseId: "TEST-101", subject: "TEST", number: "101", title: "Synthetic lab course", credits: 4, sectionCount: 2, predictedRating: null };
const lecture: ScheduleSection = { crn: "90001", courseId: course.courseId, sectionCode: "010", title: course.title, credits: 3, meetings: [{ days: ["MONDAY"], start: "09:00", end: "10:00", location: null }] };
const lab: ScheduleSection = { ...lecture, crn: "90002", sectionCode: "060", credits: 1, meetings: [] };
const build = (selected: Record<string, "required" | "optional">, sections: ScheduleSection[] = [], catalog: Record<string, CourseSummary> = { [course.courseId]: course }) => buildSemesterPlan("2027-SPRING", "Spring 2027", selected, catalog, sections);

describe("semester courses shared with the degree calendar", () => {
  it("prefills every actual course including solver-selected electives and TBA courses", () => {
    const plan = build({}, [lecture, lab, { ...lab, courseId: "TEST-102", crn: "90003", title: "Synthetic arranged course" }]);
    expect(plan.courses).toEqual([
      { courseId: "TEST-101", title: course.title, credits: 4, crns: ["90001", "90002"], hasMeetings: true },
      { courseId: "TEST-102", title: "Synthetic arranged course", credits: 1, crns: ["90003"], hasMeetings: false },
    ]);
  });
  it("does not double count lecture/lab course cards or repeated section identifiers", () => {
    const plan = build({ "TEST-101": "required" }, [lecture, lab, lecture]);
    expect(plan.courses).toHaveLength(1);
    expect(plan.courses[0].credits).toBe(4);
  });
  it("includes newly added required courses as planned without inventing sections", () => {
    const plan = build({ "TEST-101": "required", "TEST-102": "optional" });
    expect(plan.courses).toEqual([{ courseId: "TEST-101", title: course.title, credits: 4, crns: [], hasMeetings: false }]);
  });
  it("removes stale section facts when a calendar course becomes unplaced", () => {
    const selected = { "TEST-101": "required" as const };
    expect(build(selected, [lecture]).courses[0].crns).toEqual(["90001"]);
    expect(build(selected).courses[0].crns).toEqual([]);
    expect(build({}).courses).toEqual([]);
  });
  it("keeps unknown credits unavailable and never copies catalog instructor metadata", () => {
    const plan = build({ "TEST-999": "required", "TEST-101": "required" }, [], { "TEST-101": { ...course, instructors: ["Synthetic instructor"] } as CourseSummary });
    expect(plan.courses[0]).toMatchObject({ courseId: "TEST-999", credits: null, crns: [] });
    expect(JSON.stringify(plan)).not.toContain("instructor");
    expect(plan.term).toBe("2027-SPRING");
  });
});
