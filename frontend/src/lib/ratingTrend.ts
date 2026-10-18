import type { YearRating } from "./api/types";

export interface RatingTrend {
  recentYears: [number, number];
  earlierYears: [number, number];
  recentQuality: number;
  earlierQuality: number;
  change: number;
  direction: "improving" | "declining" | "steady";
  ratings: number;
}

const STEADY = 0.25;
const round2 = (value: number) => Math.round(value * 100) / 100;
const weighted = (years: YearRating[]) => years.reduce((sum, y) => sum + y.quality * y.ratings, 0) / years.reduce((sum, y) => sum + y.ratings, 0);

/**
 * Quality in the latest three rated years against all earlier years, each weighted by its number of ratings so a one-rating year counts
 * little. Null with fewer than four rated years: too little to call a direction. Changes under 0.25 points are "steady".
 */
export function qualityTrend(years: YearRating[]): RatingTrend | null {
  const rated = [...years].filter(y => y.ratings > 0).sort((a, b) => a.year - b.year);
  if (rated.length < 4) return null;
  const recent = rated.slice(-3), earlier = rated.slice(0, -3);
  const recentQuality = round2(weighted(recent)), earlierQuality = round2(weighted(earlier));
  const change = round2(recentQuality - earlierQuality);
  return {
    recentYears: [recent[0].year, recent[recent.length - 1].year],
    earlierYears: [earlier[0].year, earlier[earlier.length - 1].year],
    recentQuality, earlierQuality, change,
    direction: Math.abs(change) < STEADY ? "steady" : change > 0 ? "improving" : "declining",
    ratings: rated.reduce((sum, y) => sum + y.ratings, 0),
  };
}
