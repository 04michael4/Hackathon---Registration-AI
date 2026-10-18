import type { CourseDetail, ScheduleSection } from "./api/types";

export type DetailSection = CourseDetail["sections"][number];
type Meeting = { days: readonly string[]; start: string; end: string };

/** One registrable combination of a course's sections: a lecture with one of each linked component (lab, recitation, ...), or a standalone section. */
export interface SectionPackage { key: string; crns: string[]; label: string; sections: DetailSection[] }
/** A slot shown while a placed section is dragged: dropping on it switches the course to `crns`. */
export interface SectionMove { section: DetailSection; crns: string[] }

const MAX_PACKAGES = 60;
const title = (value: string) => value.charAt(0) + value.slice(1).toLowerCase();
const minutes = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));

/** Every combination the catalog links together, in catalog order. Nothing is inferred beyond the published lecture links. */
export function sectionPackages(detail: CourseDetail): SectionPackage[] {
  const parts = detail.sections.filter(section => section.lectureLink);
  const packages: SectionPackage[] = [];
  for (const lecture of detail.sections.filter(section => !section.lectureLink)) {
    const linked = parts.filter(part => part.lectureLink === lecture.crn);
    const byType = [...new Set(linked.map(part => part.componentType))].map(type => linked.filter(part => part.componentType === type));
    let combos: DetailSection[][] = [[lecture]];
    for (const options of byType) combos = combos.flatMap(combo => options.map(option => [...combo, option]));
    for (const sections of combos) {
      if (packages.length >= MAX_PACKAGES) return packages;
      packages.push({ key: sections.map(s => s.crn).join("+"), crns: sections.map(s => s.crn), label: sections.map(s => `${title(s.componentType)} ${s.sectionCode}`).join(" + "), sections });
    }
  }
  return packages;
}

export function meetingsOverlap(a: readonly Meeting[], b: readonly Meeting[]): boolean {
  return a.some(x => b.some(y => x.days.some(day => y.days.includes(day)) && minutes(x.start) < minutes(y.end) && minutes(y.start) < minutes(x.end)));
}

/** A starting suggestion only: the first package that is open (unless closed sections are allowed) and does not overlap the other courses. The backend check decides validity. */
export function firstFittingPackage(packages: SectionPackage[], others: ScheduleSection[], includeClosed: boolean): SectionPackage | undefined {
  const busy = others.flatMap(section => section.meetings);
  const fits = (p: SectionPackage) => (includeClosed || p.sections.every(s => s.status !== "CLOSED")) && !p.sections.some(s => meetingsOverlap(s.meetings, busy));
  return packages.find(fits) ?? packages[0];
}

/** The board's CRNs with every section of `courseId` replaced by `crns`, keeping the order of the other courses. */
export function replaceCourse(board: ScheduleSection[], courseId: string, crns: string[]): string[] {
  return [...board.filter(section => section.courseId !== courseId).map(section => section.crn), ...crns];
}

/**
 * Where a placed section can move. A linked lab/recitation moves to another of its kind linked to the same lecture; a lecture (or standalone
 * section) moves to another lecture, taking the first package of that lecture that fits the rest of the board.
 */
export function sectionMoves(detail: CourseDetail, board: ScheduleSection[], draggedCrn: string, includeClosed: boolean): SectionMove[] {
  const dragged = detail.sections.find(section => section.crn === draggedCrn);
  if (!dragged) return [];
  const current = board.filter(section => section.courseId === detail.courseId).map(section => section.crn);
  if (dragged.lectureLink) {
    return detail.sections
      .filter(section => section.crn !== dragged.crn && section.lectureLink === dragged.lectureLink && section.componentType === dragged.componentType)
      .map(section => ({ section, crns: current.map(crn => crn === dragged.crn ? section.crn : crn) }));
  }
  const packages = sectionPackages(detail);
  const others = board.filter(section => section.courseId !== detail.courseId);
  return detail.sections
    .filter(section => !section.lectureLink && section.crn !== dragged.crn)
    .flatMap(lecture => {
      const chosen = firstFittingPackage(packages.filter(p => p.crns[0] === lecture.crn), others, includeClosed);
      return chosen ? [{ section: lecture, crns: chosen.crns }] : [];
    });
}

/** A choice in the right-click menu of a placed section: the resulting CRNs for its course, and whether it overlaps the other courses. */
export interface SwapOption { key: string; label: string; crns: string[]; sections: DetailSection[]; overlaps: boolean }

/**
 * Every other way to take the course of a placed section. For a linked lab/recitation: the other ones of the same kind linked to the same
 * lecture. For a lecture: every package of the course except the one on the board, so the exact lecture + lab pair can be chosen.
 */
export function swapOptions(detail: CourseDetail, board: ScheduleSection[], crn: string): SwapOption[] {
  const clicked = detail.sections.find(section => section.crn === crn);
  if (!clicked) return [];
  const current = board.filter(section => section.courseId === detail.courseId).map(section => section.crn);
  const busy = board.filter(section => section.courseId !== detail.courseId).flatMap(section => section.meetings);
  const overlaps = (sections: DetailSection[]) => sections.some(s => meetingsOverlap(s.meetings, busy));
  if (clicked.lectureLink) {
    return detail.sections
      .filter(s => s.crn !== clicked.crn && s.lectureLink === clicked.lectureLink && s.componentType === clicked.componentType)
      .map(s => ({ key: s.crn, label: `${title(s.componentType)} ${s.sectionCode}`, crns: current.map(c => c === clicked.crn ? s.crn : c), sections: [s], overlaps: overlaps([s]) }));
  }
  const now = [...current].sort().join("+");
  return sectionPackages(detail)
    .filter(p => [...p.crns].sort().join("+") !== now)
    .map(p => ({ key: p.key, label: p.label, crns: p.crns, sections: p.sections, overlaps: overlaps(p.sections) }));
}

/** Side-by-side lanes for blocks on the same day whose times overlap, so each one stays visible and droppable. */
export function overlapLanes<T>(items: T[], day: (item: T) => string, start: (item: T) => number, end: (item: T) => number): { item: T; lane: number; lanes: number }[] {
  const out: { item: T; lane: number; lanes: number }[] = [];
  const byDay = new Map<string, T[]>();
  for (const item of items) byDay.set(day(item), [...(byDay.get(day(item)) ?? []), item]);
  for (const group of byDay.values()) {
    const sorted = [...group].sort((a, b) => start(a) - start(b) || end(a) - end(b));
    let cluster: { item: T; lane: number }[] = [], clusterEnd = -1;
    const flush = () => { const lanes = Math.max(1, ...cluster.map(c => c.lane + 1)); cluster.forEach(c => out.push({ ...c, lanes })); cluster = []; };
    for (const item of sorted) {
      if (cluster.length && start(item) >= clusterEnd) flush();
      const used = new Set(cluster.filter(c => end(c.item) > start(item)).map(c => c.lane));
      let lane = 0; while (used.has(lane)) lane++;
      cluster.push({ item, lane });
      clusterEnd = Math.max(clusterEnd, end(item));
    }
    if (cluster.length) flush();
  }
  return out;
}
