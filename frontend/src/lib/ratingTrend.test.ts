import { describe, expect, it } from "vitest";
import { qualityTrend } from "./ratingTrend";
import type { YearRating } from "./api/types";

const y = (year: number, quality: number, ratings = 4): YearRating => ({ year, quality, difficulty: 3, ratings, lowSample: ratings < 3 });

describe("quality trend", () => {
  it("compares the latest three rated years with the earlier ones, weighted by ratings", () => {
    const trend = qualityTrend([y(2020, 3), y(2021, 3.4), y(2023, 4), y(2024, 4.2), y(2025, 4.4)]);
    expect(trend).toMatchObject({ recentYears: [2023, 2025], earlierYears: [2020, 2021], recentQuality: 4.2, earlierQuality: 3.2, change: 1, direction: "improving", ratings: 20 });
  });
  it("lets a one-rating year count little and calls small changes steady", () => {
    expect(qualityTrend([y(2019, 4, 20), y(2020, 1, 1), y(2024, 4.1, 10), y(2025, 4.1, 10), y(2026, 4.1, 10)])?.direction).toBe("steady");
    expect(qualityTrend([y(2019, 4.5), y(2020, 4.5), y(2024, 3.5), y(2025, 3.6), y(2026, 3.4)])?.direction).toBe("declining");
  });
  it("needs at least four rated years", () => {
    expect(qualityTrend([y(2024, 3), y(2025, 4), y(2026, 5)])).toBeNull();
    expect(qualityTrend([y(2023, 3, 0), y(2024, 3), y(2025, 4), y(2026, 5)])).toBeNull();
  });
});
