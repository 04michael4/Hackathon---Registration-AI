"""Unit tests for predict_ratings.py (stdlib only; no database, no network). All data is synthetic.

    cd data/professor-ratings && python3 -m unittest -v test_predict_ratings
"""
import unittest

import predict_ratings as pr

SPRING_2027 = pr.semester_index(2027, "SPRING")


def r(i, year, season, q, d=3.0, c=None):
    return {"i": i, "x": pr.semester_index(year, season), "q": float(q), "d": float(d), "c": c, "t": f"{year} {season.title()}", "s": "RMP"}


class MathTests(unittest.TestCase):
    def test_semester_index_makes_consecutive_semesters_one_apart(self):
        self.assertEqual(pr.semester_index(2026, "FALL") - pr.semester_index(2026, "SPRING"), 1)
        self.assertEqual(pr.semester_index(2027, "SPRING") - pr.semester_index(2026, "FALL"), 1)
        self.assertEqual(pr.semester_index(2026, "SUMMER") - pr.semester_index(2026, "SPRING"), 0.5)

    def test_weight_halves_every_half_life(self):
        self.assertEqual(pr.weight(2, 2), 0.5)
        self.assertEqual(pr.weight(4, 2), 0.25)
        self.assertEqual(pr.weight(9, None), 1.0)

    def test_no_earlier_rating_means_no_prediction(self):
        self.assertIsNone(pr.predict_level([r(1, 2027, "SPRING", 5)], SPRING_2027, {"q": 3, "d": 3}, 2, 2))
        self.assertIsNone(pr.predict_level([], SPRING_2027, {"q": 3, "d": 3}, 2, 2))

    def test_ratings_at_or_after_the_target_are_never_used(self):
        rows = [r(1, 2026, "FALL", 2), r(1, 2027, "SPRING", 5), r(1, 2027, "FALL", 5)]
        level = pr.predict_level(rows, SPRING_2027, {"q": 3, "d": 3}, None, 0)
        self.assertEqual((level["q"], level["n"], level["last"]), (2.0, 1, "2026 Fall"))

    def test_few_ratings_are_pulled_toward_the_overall_mean(self):
        level = pr.predict_level([r(1, 2026, "FALL", 5)], SPRING_2027, {"q": 3.0, "d": 3.0}, None, 2)
        self.assertAlmostEqual(level["q"], (5 + 2 * 3) / 3)

    def test_recent_semesters_count_more(self):
        rows = [r(1, 2025, "SPRING", 1), r(1, 2026, "FALL", 5)]   # 4 and 1 semesters before Spring 2027
        recent = pr.predict_level(rows, SPRING_2027, {"q": 3, "d": 3}, 1, 0)
        flat = pr.predict_level(rows, SPRING_2027, {"q": 3, "d": 3}, None, 0)
        self.assertAlmostEqual(recent["q"], (0.0625 * 1 + 0.5 * 5) / 0.5625)
        self.assertAlmostEqual(flat["q"], 3.0)

    def test_course_ratings_pull_the_course_value_toward_them(self):
        rows = [r(1, 2026, "FALL", 5, c="CSE-017"), r(1, 2026, "FALL", 1, c="CSE-109"), r(1, 2026, "FALL", 3)]
        level = pr.predict_level(rows, SPRING_2027, {"q": 3, "d": 3}, None, 0)
        c17 = pr.predict_course(rows, "CSE-017", SPRING_2027, level, None, 1)
        self.assertAlmostEqual(c17["q"], (5 + 1 * 3) / 2)
        self.assertEqual(c17["n"], 1)
        none = pr.predict_course(rows, "CSE-216", SPRING_2027, level, None, 1)
        self.assertEqual((none["q"], none["n"]), (level["q"], 0))
        ignored = pr.predict_course(rows, "CSE-017", SPRING_2027, level, None, None)
        self.assertEqual(ignored["q"], level["q"])

    def test_confidence_thresholds(self):
        self.assertEqual([pr.confidence(v) for v in (0.5, 2.99, 3, 9.99, 10, 40)], ["LOW", "LOW", "MEDIUM", "MEDIUM", "HIGH", "HIGH"])


def improving_professors():
    """Three synthetic professors whose ratings rise steadily: recency should beat the all-time mean."""
    rows = []
    for i, start in ((1, 1.0), (2, 1.5), (3, 2.0)):
        q = start
        for year in range(2019, 2027):
            for season in ("SPRING", "FALL"):
                rows.append(r(i, year, season, round(min(5.0, q), 1), d=round(max(1.0, 5 - q), 1)))
                q += 0.2
    return rows


class BacktestTests(unittest.TestCase):
    def test_the_first_semester_of_a_professor_is_not_scored(self):
        rows = [r(1, 2024, "SPRING", 2), r(1, 2024, "FALL", 4)]
        self.assertEqual(pr.backtest(rows, pr.semester_index(2019, "SPRING"), None, 0)["pairs"], 1)

    def test_backtest_errors_are_measured_against_the_semester_mean(self):
        rows = [r(1, 2024, "SPRING", 2), r(1, 2024, "FALL", 4), r(1, 2024, "FALL", 5)]
        res = pr.backtest(rows, pr.semester_index(2019, "SPRING"), None, 0)
        self.assertEqual(res["maeQuality"], 2.5)   # predicted 2 from spring, fall mean 4.5

    def test_recency_wins_on_a_steady_trend_and_the_choice_is_deterministic(self):
        rows = improving_professors()
        since = pr.semester_index(2019, "SPRING")
        params, report = pr.choose_parameters(rows, since)
        self.assertIsNotNone(params["halfLife"])
        self.assertLess(report["chosen"]["maeQuality"], report["baselines"]["allTimeMean"]["maeQuality"])
        self.assertEqual(pr.choose_parameters(rows, since), (params, report))

    def test_a_tie_keeps_the_first_setting_of_the_grid(self):
        rows = [r(1, year, season, 3, 3, c="ZZZ-101") for year in (2023, 2024, 2025) for season in ("SPRING", "FALL")]   # every setting is exact
        params, _ = pr.choose_parameters(rows, pr.semester_index(2019, "SPRING"))
        self.assertEqual(params, {"halfLife": pr.GRID_HALF_LIFE[0], "priorStrength": pr.GRID_PRIOR[0], "courseStrength": pr.GRID_COURSE[0]})

    def test_baselines_are_reported_without_course_scores(self):
        _, report = pr.choose_parameters(improving_professors(), pr.semester_index(2019, "SPRING"))
        self.assertEqual(set(report["baselines"]), {"allTimeMean", "lastSemesterOnly", "overallMean"})
        self.assertNotIn("coursePairs", report["baselines"]["lastSemesterOnly"])


class BuildTests(unittest.TestCase):
    def test_only_instructors_with_history_are_predicted_and_values_stay_in_range(self):
        rows = [r(1, 2026, "FALL", 5, d=5, c="CSE-017"), r(2, 2027, "FALL", 1)]
        params = {"halfLife": 2, "priorStrength": 2, "courseStrength": 3}
        out, prior = pr.build_predictions(rows, {1: ["CSE-017", "CSE-017", "CSE-109"], 2: ["CSE-216"], 3: ["CSE-007"]}, SPRING_2027, params)
        self.assertEqual([p["instructorId"] for p in out], [1])
        p = out[0]
        self.assertEqual([c["courseId"] for c in p["courses"]], ["CSE-017", "CSE-109"])
        self.assertTrue(all(1 <= v <= 5 for v in (p["quality"], p["difficulty"], *[c["quality"] for c in p["courses"]])))
        self.assertEqual((p["basisRatings"], p["confidence"], p["lastRatedTerm"]), (1, "LOW", "2026 Fall"))
        self.assertEqual(prior, {"q": 5.0, "d": 5.0})   # the overall mean ignores ratings at or after the target too


if __name__ == "__main__":
    unittest.main()
