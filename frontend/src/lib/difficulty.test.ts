import { describe, expect, it } from "vitest";
import { difficultyBand, sectionDifficulty, toTenPoint } from "./difficulty";
import type { CourseDetail, InstructorRating } from "./api/types";

const rated = (difficulty: number | null, quality: number | null = 4): InstructorRating =>
  ({ name: "Synthetic, Teacher", predictedQuality: quality, predictedDifficulty: difficulty, confidence: difficulty === null ? null : "MEDIUM", ratingCount: difficulty === null ? null : 5, courseSpecific: true });
const section = (crn: string, lectureLink: string | null, instructorRatings: InstructorRating[]) =>
  ({ crn, sectionCode: crn.slice(-3), componentType: lectureLink ? "LAB" : "LECTURE", lectureLink, credits: 0, status: "OPEN" as const, instructors: [], instructorRatings, meetings: [] });
const detail = (sections: CourseDetail["sections"], courseDifficulty: number | null = null): CourseDetail => ({
  courseId: "TEST-101", subject: "TEST", number: "101", title: "Synthetic", credits: 4, description: null, conditions: [], ratingHistory: [], sections,
  predictedRating: courseDifficulty === null ? null : { quality: 3.5, difficulty: courseDifficulty, confidence: "LOW", basis: "PREDICTED", sectionsPredicted: 1, sections: 1 },
});

describe("difficulty scale", () => {
  it("maps 1-5 onto 1-10 with the ends fixed", () => {
    expect(toTenPoint(1)).toBe(1);
    expect(toTenPoint(5)).toBe(10);
    expect(toTenPoint(3)).toBe(5.5);
    expect(toTenPoint(2.95)).toBe(5.4);
  });
  it("bands 1-3 easy, 4-6 moderate, 7-10 hard on the rounded score", () => {
    expect([1, 3.4, 3.5, 6.4, 6.5, 10].map(difficultyBand)).toEqual(["easy", "easy", "moderate", "moderate", "hard", "hard"]);
  });
});

describe("section difficulty", () => {
  it("uses the section's own instructors first, averaging several", () => {
    const d = sectionDifficulty(detail([section("90001", null, [rated(2), rated(4, null)])], 5), "90001");
    expect(d).toMatchObject({ score: 5.5, band: "moderate", label: "Moderate", quality: 4, source: "section" });
  });
  it("falls back to the linked lecture, then to the course, and never invents a number", () => {
    const course = detail([section("90001", null, [rated(4.6)]), section("90011", "90001", [rated(null)])], 1.5);
    expect(sectionDifficulty(course, "90011")).toMatchObject({ score: 9.1, band: "hard", source: "lecture" });
    const courseOnly = detail([section("90001", null, []), section("90011", "90001", [])], 1.5);
    expect(sectionDifficulty(courseOnly, "90011")).toMatchObject({ score: 2.1, band: "easy", source: "course" });
    expect(sectionDifficulty(detail([section("90001", null, [])]), "90001")).toBeNull();
    expect(sectionDifficulty(undefined, "90001")).toBeNull();
    expect(sectionDifficulty(courseOnly, "12345")).toBeNull();
  });
});
