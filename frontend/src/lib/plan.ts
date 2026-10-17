import type { CourseDetail, Section, TimeBlock } from "./api/types";
import type { RequirementKind } from "./demo/program";
import type { CalendarBlock, BlockVariant } from "@/components/week-calendar";
import { courseCode } from "./rules";
import { clock, dayPattern, KIND_COLOR } from "./ui";

export type SectionIndex = Map<string, { section: Section; course: CourseDetail }>;

export function componentLabel(section: Section): string {
  const type = section.componentType.toLowerCase();
  return type.charAt(0).toUpperCase() + type.slice(1);
}

/** "MWF 10am–10:50am" */
export function whenText(section: Section): string {
  return section.meetings.map((m) => `${dayPattern(m.days)} ${clock(m.start)}–${clock(m.end)}`).join(", ") || "Time to be announced";
}

/** Calendar blocks for sections, one per meeting pattern. */
export function blocksFor(
  crns: string[],
  index: SectionIndex,
  kindOf: (courseId: string) => RequirementKind,
  variantOf: (crn: string) => BlockVariant,
  noteOf?: (crn: string) => string | undefined,
): CalendarBlock[] {
  return crns.flatMap((crn) => {
    const hit = index.get(crn);
    if (!hit) return [];
    const { section, course } = hit;
    return section.meetings.map((m, i) => ({
      id: `${crn}-${i}`,
      crn,
      label: courseCode(course.courseId),
      sub: `${section.lectureLink ? componentLabel(section) + " " : ""}${section.sectionCode}${m.location ? `, ${m.location}` : ""}`,
      days: m.days,
      start: m.start,
      end: m.end,
      color: KIND_COLOR[kindOf(course.courseId)],
      variant: variantOf(crn),
      note: noteOf?.(crn),
    }));
  });
}

/** The CRNs to remove with a section: a lecture takes its linked labs and recitations along. */
export function withLinked(crn: string, placed: string[], index: SectionIndex): string[] {
  const hit = index.get(crn);
  if (!hit) return [crn];
  if (hit.section.lectureLink) return [crn];
  return [crn, ...placed.filter((other) => index.get(other)?.section.lectureLink === crn)];
}

/** All placed CRNs of one course. */
export function crnsOfCourse(courseId: string, placed: string[], index: SectionIndex): string[] {
  return placed.filter((crn) => index.get(crn)?.course.courseId === courseId);
}

export function coursesOf(placed: string[], index: SectionIndex): string[] {
  return [...new Set(placed.map((crn) => index.get(crn)?.course.courseId).filter((id): id is string => !!id))].sort();
}

export function creditsOfCrns(placed: string[], index: SectionIndex): number {
  return placed.reduce((sum, crn) => sum + (index.get(crn)?.section.credits ?? 0), 0);
}

/** Placed sections' meetings as blocked times, so a generated plan keeps them in place. */
export function meetingsAsBlocks(placed: string[], index: SectionIndex): TimeBlock[] {
  return placed.flatMap((crn) => index.get(crn)?.section.meetings.map((m) => ({ days: m.days, start: m.start, end: m.end })) ?? []);
}

// Engine diagnostics are documented sentences (docs/api-contract.md, endpoint 6); these turn them into fixes.

export function blockedCourse(diagnostic: string): string | null {
  return /^([A-Z]+-\d+): every section overlaps your blocked times\.$/.exec(diagnostic)?.[1] ?? null;
}

export function alwaysConflict(diagnostic: string): [string, string] | null {
  const m = /^([A-Z]+-\d+) and ([A-Z]+-\d+) conflict in every combination\.$/.exec(diagnostic);
  return m ? [m[1], m[2]] : null;
}

export function isCreditProblem(diagnostic: string): boolean {
  return /credits/.test(diagnostic) && !diagnostic.startsWith("Suggestion:");
}
