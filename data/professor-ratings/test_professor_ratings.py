"""Unit tests for the Python side of professor_ratings (stdlib only; no database, no network). Names here are synthetic.

    cd data/professor-ratings && python3 -m unittest -v test_professor_ratings
"""
import csv
import json
import tempfile
import unittest
from pathlib import Path

import collect_instructors
import import_professor_ratings as ipr
import rmp_client
from professor_names import fold, parse_banner_name, rmp_name_keys, split_listed


class NameTests(unittest.TestCase):
    def test_fold_drops_accents_apostrophes_and_turns_hyphens_into_spaces(self):
        self.assertEqual(fold("José"), "jose")
        self.assertEqual(fold("O'Quillon"), "oquillon")
        self.assertEqual(fold("Test-Wick"), "test wick")
        self.assertEqual(fold("  J.  Robert "), "j robert")

    def test_parse_banner_name(self):
        p = parse_banner_name("Testwick, Alder J. (P)")
        self.assertEqual((p["bannerName"], p["lastKey"], p["firstKey"]), ("Testwick, Alder J.", "testwick", "alder j"))
        self.assertEqual(parse_banner_name("Van Dorn, Mira")["lastKey"], "van dorn")

    def test_placeholders_and_unusable_strings_are_not_people(self):
        for text in ("Staff", "Staff, Pat", "TBA", "Instructor TBA", "", None, "Nocomma", "Onlylast,", ", Onlyfirst"):
            self.assertIsNone(parse_banner_name(text), text)

    def test_split_listed_separates_people(self):
        self.assertEqual(split_listed("A, B; C, D;"), ["A, B", "C, D"])
        self.assertEqual(split_listed(None), [])

    def test_rmp_name_keys(self):
        self.assertEqual(rmp_name_keys("Alder", "Testwick"), ("alder", "testwick"))
        self.assertIsNone(rmp_name_keys("Alder", "..."))


class CollectTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def write_csv(self, rows):
        path = self.tmp / "instructors.csv"
        with open(path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=["term", "crn", "course", "instructor", "role", "collected", "origin"])
            w.writeheader()
            for r in rows:
                w.writerow({"course": "X 1", "collected": "", "origin": "t", **r})
        return path

    def test_noncse_csv_skips_staff_and_maps_roles(self):
        path = self.write_csv([{"term": "202710", "crn": "10001", "instructor": "Testwick, Alder (P)", "role": "Primary"},
                               {"term": "202710", "crn": "10001", "instructor": "Quillon, Bree", "role": "Additional"},
                               {"term": "202710", "crn": "10002", "instructor": "Staff", "role": "Primary"}])
        payload, skipped = collect_instructors.collect(noncse_csv=path)
        self.assertEqual([i["bannerName"] for i in payload["instructors"]], ["Quillon, Bree", "Testwick, Alder"])
        self.assertEqual({(a["crn"], a["role"], a["source"]) for a in payload["assignments"]}, {("10001", "PRIMARY", "BANNER_GRID"), ("10001", "ADDITIONAL", "BANNER_GRID")})
        self.assertEqual(skipped, {"placeholder_or_unparseable": 1})

    def test_fixture_sections_use_the_term_table_and_mark_multi_name_sections_listed(self):
        fixture = {"term": [{"term_id": "t1", "term_code": "202710"}],
                   "section": [{"term_id": "t1", "crn": "10003", "instructor": "Testwick, Alder", "instructor_is_primary": True},
                               {"term_id": "t1", "crn": "10004", "instructor": "Testwick, Alder; Quillon, Bree", "instructor_is_primary": True}]}
        path = self.tmp / "fixture.json"
        path.write_text(json.dumps(fixture), encoding="utf-8")
        payload, _ = collect_instructors.collect(snapshot=path)
        roles = {a["crn"] + a["bannerName"]: a["role"] for a in payload["assignments"]}
        self.assertEqual(roles["10003Testwick, Alder"], "PRIMARY")
        self.assertEqual(roles["10004Quillon, Bree"], "LISTED")
        self.assertTrue(all(a["term"] == "202710" and a["source"] == "SNAPSHOT" for a in payload["assignments"]))

    def test_the_same_person_in_two_sources_is_one_instructor(self):
        csv_path = self.write_csv([{"term": "202710", "crn": "10001", "instructor": "Testwick, Alder", "role": "Primary"}])
        fx = self.tmp / "f.json"
        fx.write_text(json.dumps({"term": [], "section": [{"term_id": "x202710", "crn": "10009", "instructor": "Testwick, Alder"}]}), encoding="utf-8")
        payload, _ = collect_instructors.collect(noncse_csv=csv_path, cse_fixture=fx)
        self.assertEqual(len(payload["instructors"]), 1)
        self.assertEqual(len(payload["assignments"]), 2)


def page(nodes, has_next, cursor="c", total=3):
    return {"resultCount": total, "edges": [{"node": n} for n in nodes], "pageInfo": {"hasNextPage": has_next, "endCursor": cursor}}


def node(i, school=509, **extra):
    return {"id": f"T{i}", "legacyId": i, "firstName": "Alder", "lastName": f"Name{i}", "department": "Art", "avgRating": 4.0, "avgDifficulty": 3.0,
            "wouldTakeAgainPercent": -1, "numRatings": 2, "school": {"legacyId": school}, **extra}


class ClientTests(unittest.TestCase):
    def test_pages_are_followed_with_a_pause_between_requests(self):
        pages = [page([node(1), node(2)], True, "c1"), page([node(3)], False)]
        seen, sleeps = [], []
        def poster(variables):
            seen.append(variables["after"])
            return pages.pop(0)
        teachers, total = rmp_client.fetch_school_roster(509, sleep=sleeps.append, poster=poster)
        self.assertEqual((len(teachers), total, seen, sleeps), (3, 3, [None, "c1"], [2.0]))

    def test_the_request_cap_stops_a_runaway_roster(self):
        def poster(variables):
            return page([node(1)], True, total=999)
        with self.assertRaisesRegex(rmp_client.FetchError, "more than 2 requests"):
            rmp_client.fetch_school_roster(509, max_requests=2, sleep=lambda s: None, poster=poster)

    def test_a_professor_from_another_school_stops_the_fetch(self):
        with self.assertRaisesRegex(rmp_client.FetchError, "another school"):
            rmp_client.fetch_school_roster(509, sleep=lambda s: None, poster=lambda v: page([node(1, school=7)], False, total=1))

    def test_a_short_roster_is_refused(self):
        with self.assertRaisesRegex(rmp_client.FetchError, "reported 3"):
            rmp_client.fetch_school_roster(509, sleep=lambda s: None, poster=lambda v: page([node(1)], False, total=3))

    def test_the_school_node_id_is_the_documented_encoding(self):
        self.assertEqual(rmp_client.school_node_id(509), "U2Nob29sLTUwOQ==")

    def test_no_review_text_is_requested(self):
        for word in ("comment", "tags", "ratings(", "teacherNote"):
            self.assertNotIn(word, rmp_client.QUERY)
        for word in ("comment", "ratingTags", "grade", "attendance", "textbook", "teacherNote", "thumbs"):
            self.assertNotIn(word, rmp_client.RATINGS_QUERY)


def ratings_node(legacy_id, ratings, has_next, cursor="r", reported=None):
    return {"legacyId": legacy_id, "numRatings": len(ratings) if reported is None else reported,
            "ratings": {"edges": [{"node": r} for r in ratings], "pageInfo": {"hasNextPage": has_next, "endCursor": cursor}}}


def raw_rating(i, date="2024-12-20 10:00:00 +0000 UTC", clarity=4, helpful=5, difficulty=3, wta=1, cls="CSE17"):
    return {"legacyId": i, "date": date, "class": cls, "clarityRating": clarity, "helpfulRating": helpful, "difficultyRating": difficulty,
            "wouldTakeAgain": wta, "isForOnlineClass": False}


class RatingsFetchTests(unittest.TestCase):
    def test_each_professor_is_paged_and_one_cap_covers_them_all(self):
        pages = {("T1", None): ratings_node(1, [raw_rating(1)], True, "p2", reported=2), ("T1", "p2"): ratings_node(1, [raw_rating(2)], False, reported=2),
                 ("T2", None): ratings_node(2, [raw_rating(3)], False)}
        sleeps = []
        result = rmp_client.fetch_teacher_ratings([(1, "T1"), (2, "T2")], sleep=sleeps.append, poster=lambda v: pages[(v["id"], v["after"])])
        self.assertEqual({k: len(v["ratings"]) for k, v in result.items()}, {1: 2, 2: 1})
        self.assertEqual(len(sleeps), 2)
        with self.assertRaisesRegex(rmp_client.FetchError, "more than 2 requests"):
            rmp_client.fetch_teacher_ratings([(1, "T1"), (2, "T2")], max_requests=2, sleep=lambda s: None, poster=lambda v: pages[(v["id"], v["after"])])

    def test_a_different_professor_in_the_answer_stops_the_fetch(self):
        with self.assertRaisesRegex(rmp_client.FetchError, "different professor"):
            rmp_client.fetch_teacher_ratings([(1, "T1")], sleep=lambda s: None, poster=lambda v: ratings_node(99, [], False))

    def test_payload_quality_is_the_mean_of_clarity_and_helpfulness_and_scoreless_ratings_are_dropped(self):
        fetched = {7: {"reported": 4, "ratings": [raw_rating(1), raw_rating(2, clarity=3, helpful=None, wta=None, cls=None), raw_rating(3, clarity=None, helpful=None),
                                                  raw_rating(4, difficulty=None)]}}
        payload, dropped = ipr.rating_payload(fetched, "2026-10-17T00:00:00+00:00")
        rows = payload["teachers"][0]["ratings"]
        self.assertEqual(dropped, 2)
        self.assertEqual([(r["ratingId"], r["quality"], r["postedOn"], r["wouldTakeAgain"], r["classLabel"]) for r in rows],
                         [(1, 4.5, "2024-12-20", True, "CSE17"), (2, 3.0, "2024-12-20", None, "")])
        self.assertTrue(all(set(r) == {"ratingId", "postedOn", "quality", "difficulty", "wouldTakeAgain", "classLabel", "isOnline"} for r in rows))


class PayloadTests(unittest.TestCase):
    def test_only_professors_sharing_an_instructor_last_name_are_kept(self):
        teachers = [node(1, lastName="Testwick"), node(2, lastName="Unrelated"), node(3, lastName="Test-Wick", wouldTakeAgainPercent=None)]
        payload = ipr.build_rmp_payload(teachers, {"testwick", "test wick"}, "2026-10-17T00:00:00+00:00")
        self.assertEqual([t["legacyId"] for t in payload["teachers"]], [1, 3])
        self.assertEqual(payload["teachers"][1]["lastKey"], "test wick")
        self.assertEqual(payload["teachers"][1]["wouldTakeAgainPercent"], -1)

    def test_the_shared_database_is_refused(self):
        import os
        old = dict(os.environ)
        try:
            os.environ["PGDATABASE"] = "registration_unified_spring2027"
            args = type("A", (), {"allow_shared": False})()
            with self.assertRaises(ipr.ImportError_):
                ipr.guard(args)
            args.allow_shared = True
            ipr.guard(args)
        finally:
            os.environ.clear()
            os.environ.update(old)


if __name__ == "__main__":
    unittest.main()
