import { describe, expect, it } from "vitest";
import { firstFittingPackage, meetingsOverlap, overlapLanes, replaceCourse, sectionMoves, sectionPackages, swapOptions, type DetailSection } from "./sectionPackages";
import type { CourseDetail, ScheduleSection } from "./api/types";

const section = (crn: string, componentType: string, sectionCode: string, lectureLink: string | null, days: string[], start: string, end: string, status: DetailSection["status"] = "OPEN"): DetailSection =>
  ({ crn, sectionCode, componentType, lectureLink, credits: lectureLink ? 0 : 4, status, instructors: [], instructorRatings: [], meetings: [{ days, start, end, location: null }] });
const detail = (sections: DetailSection[]): CourseDetail => ({ courseId: "TEST-101", subject: "TEST", number: "101", title: "Synthetic lab course", credits: 4, predictedRating: null, description: null, conditions: [], ratingHistory: [], sections });
const placed = (courseId: string, crn: string, days: ScheduleSection["meetings"][number]["days"], start: string, end: string): ScheduleSection =>
  ({ crn, courseId, sectionCode: "010", title: courseId, credits: 3, meetings: [{ days, start, end, location: null }] });

const lectureA = section("90001", "LECTURE", "010", null, ["MONDAY"], "09:00", "10:00");
const lectureB = section("90002", "LECTURE", "011", null, ["TUESDAY"], "09:00", "10:00");
const labA1 = section("90011", "LAB", "060", "90001", ["WEDNESDAY"], "10:00", "12:00");
const labA2 = section("90012", "LAB", "061", "90001", ["FRIDAY"], "10:00", "12:00");
const labB1 = section("90021", "LAB", "070", "90002", ["THURSDAY"], "13:00", "15:00");
const course = detail([lectureA, lectureB, labA1, labA2, labB1]);

describe("section packages", () => {
  it("pairs each lecture with every lab linked to it, in catalog order", () => {
    expect(sectionPackages(course).map(p => [p.crns, p.label])).toEqual([
      [["90001", "90011"], "Lecture 010 + Lab 060"],
      [["90001", "90012"], "Lecture 010 + Lab 061"],
      [["90002", "90021"], "Lecture 011 + Lab 070"],
    ]);
  });
  it("keeps a lecture without links as a package of its own and combines one of each linked component type", () => {
    const recitation = section("90031", "RECITATION", "R01", "90001", ["THURSDAY"], "08:00", "09:00");
    const lone = section("90040", "LECTURE", "020", null, ["FRIDAY"], "15:00", "16:00");
    expect(sectionPackages(detail([lectureA, labA1, labA2, recitation, lone])).map(p => p.crns)).toEqual([
      ["90001", "90011", "90031"], ["90001", "90012", "90031"], ["90040"],
    ]);
  });
  it("never invents a link: an unlinked lab is not packaged", () => {
    expect(sectionPackages(detail([section("90099", "LAB", "099", "77777", ["MONDAY"], "08:00", "09:00")]))).toEqual([]);
  });
});

describe("placement helpers", () => {
  it("detects overlaps only on a shared day with intersecting times", () => {
    expect(meetingsOverlap(labA1.meetings, [{ days: ["WEDNESDAY"], start: "11:59", end: "13:00" }])).toBe(true);
    expect(meetingsOverlap(labA1.meetings, [{ days: ["WEDNESDAY"], start: "12:00", end: "13:00" }])).toBe(false);
    expect(meetingsOverlap(labA1.meetings, [{ days: ["MONDAY"], start: "10:00", end: "12:00" }])).toBe(false);
  });
  it("suggests the first open package that fits the other courses, and falls back to the first package", () => {
    const packages = sectionPackages(course);
    expect(firstFittingPackage(packages, [placed("OTHER-1", "1", ["WEDNESDAY"], "10:30", "11:00")], false)?.crns).toEqual(["90001", "90012"]);
    const closedFirst = sectionPackages(detail([{ ...lectureA, status: "CLOSED" }, lectureB, labA1, labB1]));
    expect(firstFittingPackage(closedFirst, [], false)?.crns).toEqual(["90002", "90021"]);
    expect(firstFittingPackage(closedFirst, [], true)?.crns).toEqual(["90001", "90011"]);
    expect(firstFittingPackage(packages, [placed("OTHER-1", "1", ["MONDAY", "TUESDAY"], "08:00", "18:00")], false)?.crns).toEqual(["90001", "90011"]);
  });
  it("replaces only the dragged course's sections", () => {
    const board = [placed("OTHER-1", "1", ["MONDAY"], "08:00", "09:00"), placed("TEST-101", "90001", ["MONDAY"], "09:00", "10:00"), placed("TEST-101", "90011", ["WEDNESDAY"], "10:00", "12:00")];
    expect(replaceCourse(board, "TEST-101", ["90002", "90021"])).toEqual(["1", "90002", "90021"]);
  });
});

describe("section moves while dragging", () => {
  const board = [placed("TEST-101", "90001", ["MONDAY"], "09:00", "10:00"), placed("TEST-101", "90011", ["WEDNESDAY"], "10:00", "12:00")];
  it("moves a lab only to labs linked to the same lecture", () => {
    expect(sectionMoves(course, board, "90011", false).map(m => [m.section.crn, m.crns])).toEqual([["90012", ["90001", "90012"]]]);
  });
  it("moves a lecture to another lecture together with a lab linked to it", () => {
    expect(sectionMoves(course, board, "90001", false).map(m => [m.section.crn, m.crns])).toEqual([["90002", ["90002", "90021"]]]);
  });
  it("offers nothing for a section that is not in the course detail", () => {
    expect(sectionMoves(course, board, "12345", false)).toEqual([]);
  });
});

describe("right-click swap options", () => {
  const board = [placed("TEST-101", "90001", ["MONDAY"], "09:00", "10:00"), placed("TEST-101", "90011", ["WEDNESDAY"], "10:00", "12:00"), placed("OTHER-1", "1", ["FRIDAY"], "10:30", "11:00")];
  it("offers a lab every other lab of the same lecture, flagging overlaps with other courses", () => {
    expect(swapOptions(course, board, "90011").map(o => [o.label, o.crns, o.overlaps])).toEqual([["Lab 061", ["90001", "90012"], true]]);
  });
  it("offers a lecture every other exact lecture + lab pair", () => {
    expect(swapOptions(course, board, "90001").map(o => [o.label, o.crns])).toEqual([
      ["Lecture 010 + Lab 061", ["90001", "90012"]],
      ["Lecture 011 + Lab 070", ["90002", "90021"]],
    ]);
  });
  it("offers nothing for an unknown section", () => {
    expect(swapOptions(course, board, "12345")).toEqual([]);
  });
});

describe("overlap lanes", () => {
  it("puts overlapping blocks on the same day side by side and leaves others full width", () => {
    const items = [{ id: "a", day: "MONDAY", s: 540, e: 600 }, { id: "b", day: "MONDAY", s: 570, e: 630 }, { id: "c", day: "MONDAY", s: 700, e: 760 }, { id: "d", day: "TUESDAY", s: 540, e: 600 }];
    const lanes = overlapLanes(items, i => i.day, i => i.s, i => i.e).map(l => `${l.item.id}:${l.lane}/${l.lanes}`).sort();
    expect(lanes).toEqual(["a:0/2", "b:1/2", "c:0/1", "d:0/1"]);
  });
});
