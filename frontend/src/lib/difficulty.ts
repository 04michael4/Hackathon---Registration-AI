import type { CourseDetail } from "./api/types";

export type DifficultyBand = "easy" | "moderate" | "hard";
export interface SectionDifficulty {
  /** Predicted difficulty on the 1-10 scale shown to students (1 easiest, 10 hardest). */
  score: number;
  band: DifficultyBand;
  label: string;
  /** Predicted quality, still on the source 1-5 scale, when known. */
  quality: number | null;
  source: "section" | "lecture" | "course";
}

const BAND_LABEL: Record<DifficultyBand, string> = { easy: "Easy", moderate: "Moderate", hard: "Hard" };
const mean = (values: number[]) => values.reduce((sum, v) => sum + v, 0) / values.length;

/** Rating difficulty is 1.00-5.00; map it linearly onto 1-10 so 1 stays 1 and 5 becomes 10. */
export function toTenPoint(difficulty: number): number {
  return Math.round((1 + (difficulty - 1) * 9 / 4) * 10) / 10;
}

/** 1-3 easy (green), 4-6 moderate (yellow), 7-10 hard (red), judged on the rounded score. */
export function difficultyBand(score: number): DifficultyBand {
  const whole = Math.round(score);
  return whole <= 3 ? "easy" : whole <= 6 ? "moderate" : "hard";
}

function fromRatings(ratings: { predictedQuality: number | null; predictedDifficulty: number | null }[]) {
  const difficulties = ratings.flatMap(r => r.predictedDifficulty === null ? [] : [r.predictedDifficulty]);
  if (!difficulties.length) return null;
  const qualities = ratings.flatMap(r => r.predictedQuality === null ? [] : [r.predictedQuality]);
  return { difficulty: mean(difficulties), quality: qualities.length ? mean(qualities) : null };
}

/**
 * The predicted difficulty for a placed section: its own instructors' prediction; for a lab or recitation without one, its lecture's;
 * otherwise the course-level prediction. Null when there is no prediction at all (never a made-up number).
 */
export function sectionDifficulty(detail: CourseDetail | undefined, crn: string): SectionDifficulty | null {
  if (!detail) return null;
  const section = detail.sections.find(s => s.crn === crn);
  if (!section) return null;
  const lecture = section.lectureLink ? detail.sections.find(s => s.crn === section.lectureLink) : undefined;
  const own = fromRatings(section.instructorRatings ?? []);
  const linked = lecture ? fromRatings(lecture.instructorRatings ?? []) : null;
  const course = detail.predictedRating ? { difficulty: detail.predictedRating.difficulty, quality: detail.predictedRating.quality } : null;
  const [picked, source] = own ? [own, "section" as const] : linked ? [linked, "lecture" as const] : course ? [course, "course" as const] : [null, null];
  if (!picked || !source) return null;
  const score = toTenPoint(picked.difficulty);
  const band = difficultyBand(score);
  return { score, band, label: BAND_LABEL[band], quality: picked.quality, source };
}
