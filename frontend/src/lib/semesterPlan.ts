import type { CourseSummary, ScheduleSection } from "./api/types";

export interface SemesterPlanCourse {
  courseId: string;
  title: string;
  credits: number | null;
  crns: string[];
  hasMeetings: boolean;
}
export interface SemesterPlan {
  term: string;
  name: string;
  courses: SemesterPlanCourse[];
}

/** Share course-level facts with the degree calendar, never instructor data or invented meetings. */
export function buildSemesterPlan(term: string, name: string, selected: Record<string, "required" | "optional">, catalog: Record<string, CourseSummary>, sections: ScheduleSection[] = []): SemesterPlan {
  const courses = new Map<string, SemesterPlanCourse>();
  const seen = new Set<string>();
  for (const section of sections) {
    if (seen.has(section.crn)) continue;
    seen.add(section.crn);
    const course = courses.get(section.courseId) ?? { courseId: section.courseId, title: section.title, credits: 0, crns: [], hasMeetings: false };
    course.credits = (course.credits ?? 0) + section.credits;
    course.crns.push(section.crn);
    course.hasMeetings ||= section.meetings.some(meeting => meeting.days.length > 0);
    courses.set(section.courseId, course);
  }
  for (const [id, role] of Object.entries(selected)) {
    if (role === "required" && !courses.has(id)) courses.set(id, { courseId: id, title: catalog[id]?.title ?? id, credits: catalog[id]?.credits ?? null, crns: [], hasMeetings: false });
  }
  return { term, name, courses: [...courses.values()] };
}
