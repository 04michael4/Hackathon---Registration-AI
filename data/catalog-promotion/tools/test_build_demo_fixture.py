"""Tests for build_demo_fixture.py (stdlib only; synthetic workbook and evidence; no database, no network).

    python3 -I data/catalog-promotion/tools/test_build_demo_fixture.py
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_demo_fixture as bdf  # noqa: E402
import reconcile_tracker as rt  # noqa: E402
import test_reconcile_tracker as T  # noqa: E402


def sec(course, crn, kind="Lecture", grid="3", details="3", title=None, code="010"):
    return {"course_id": course, "crn": crn, "section_code": code, "title": title or f"Synthetic {course}", "schedule_type": kind,
            "grid_hours": grid, "details_credit_hours": details, "linked_outcome": "LISTED" if kind == "Lab" else "NONE_SHOWN"}


def mtg(crn, days, start, end, loc="Synthetic Hall 1"):
    return {"crn": crn, "days": days, "start": start, "end": end, "location": loc}


def data(**kw):
    base = {"sections": [], "meetings": [], "links": [], "requirements": [], "snapshot_sections": [], "snapshot_meetings": [], "snapshot_courses": []}
    base.update(kw)
    return base


def req(course, crn, kind, text, empty=False):
    return {"course_id": course, "crn": crn, "kind": kind, "text": text, "empty": empty}


def sheets(*rows, **extra):
    s = {"COURSE TRACKER": T.tracker_sheet(*rows)}
    s.update(extra)
    return s


FR_TEXT = "Not all restrictions are applicable. Cannot be enrolled in one of the following Classes: First-Year Student (FR)"


class Build(unittest.TestCase):
    def offering(self, fixture, oid):
        return next(o for o in fixture["offerings"] if o["offeringId"] == oid)

    def test_a_lecture_with_a_lab_keeps_the_lab_linked_and_free_of_credits(self):
        d = data(sections=[sec("CSE-017", "91001", grid="3", details="3"), sec("CSE-017", "91002", "Lab", "0", "3", code="060")],
                 meetings=[mtg("91001", [1, 3], "09:20", "10:10"), mtg("91002", [4], "10:45", "12:00")],
                 links=[{"course_id": "CSE-017", "lecture_crn": "91001", "linked_crn": "91002"}])
        f = bdf.build_fixture(sheets(T.trow("CSE 017", crns="91001; 91002", component="Exactly one lab (USER_CONFIRMED 2026-10-16)")), d)
        o = self.offering(f, "CSE-017")
        lecture, lab = o["sections"]
        self.assertEqual((lecture["component"], lecture["credits"], lecture["lectureCrn"]), ("LECTURE", 3, None))
        self.assertEqual((lab["component"], lab["credits"], lab["lectureCrn"]), ("LAB", 0, "91001"))
        self.assertEqual(lecture["meetings"][0]["days"], ["MONDAY", "WEDNESDAY"])
        self.assertEqual(lab["meetings"][0]["location"], "Synthetic Hall 1")
        self.assertEqual((o["credits"], o["components"], o["tier"]), (3, "LABS_USER_SUPPLIED", "CONDITIONAL"))

    def test_a_lab_that_shows_grid_credits_is_refused_not_guessed(self):
        d = data(sections=[sec("CSE-017", "91001"), sec("CSE-017", "91002", "Lab", "1", "3", code="060")],
                 meetings=[mtg("91001", [1], "09:00", "10:00"), mtg("91002", [4], "10:45", "12:00")],
                 links=[{"course_id": "CSE-017", "lecture_crn": "91001", "linked_crn": "91002"}])
        with self.assertRaisesRegex(rt.ReconcileError, "refusing to guess"):
            bdf.build_fixture(sheets(T.trow("CSE 017", crns="91001; 91002")), d)

    def test_a_lab_without_exactly_one_confirmed_lecture_is_refused(self):
        d = data(sections=[sec("CSE-017", "91001"), sec("CSE-017", "91002", "Lab", "0", "3", code="060")],
                 meetings=[mtg("91001", [1], "09:00", "10:00"), mtg("91002", [4], "10:45", "12:00")])
        with self.assertRaisesRegex(rt.ReconcileError, "no single confirmed lecture"):
            bdf.build_fixture(sheets(T.trow("CSE 017", crns="91001; 91002")), d)

    def test_lecture_credit_displays_that_differ_are_refused(self):
        d = data(sections=[sec("CSE-111", "91001", grid="3", details="4")], meetings=[mtg("91001", [1], "09:00", "10:00")])
        with self.assertRaisesRegex(rt.ReconcileError, "credit displays differ"):
            bdf.build_fixture(sheets(T.trow("CSE 111", crns="91001")), d)

    def test_a_tracker_crn_with_no_evidence_is_refused(self):
        with self.assertRaisesRegex(rt.ReconcileError, "no Banner evidence"):
            bdf.build_fixture(sheets(T.trow("CSE 111", crns="91001")), data())

    def test_two_topics_under_one_number_become_two_offerings_with_their_own_titles(self):
        d = data(sections=[sec("CSE-398", "91401", title="Topic Alpha", code="042"), sec("CSE-398", "91501", title="Topic Beta", code="052")],
                 meetings=[mtg("91401", [1, 3], "13:35", "14:50"), mtg("91501", [1, 3], "12:10", "13:25")])
        f = bdf.build_fixture(sheets(T.trow("CSE 398", "042 · Topic Alpha", "91401"), T.trow("CSE 398", "052 · Topic Beta", "91501")), d)
        ids = [o["offeringId"] for o in f["offerings"]]
        self.assertEqual(ids, ["CSE-398-042", "CSE-398-052"])
        a, b = f["offerings"]
        self.assertEqual((a["title"], b["title"]), ("Topic Alpha", "Topic Beta"))
        self.assertEqual(([s["crn"] for s in a["sections"]], [s["crn"] for s in b["sections"]]), (["91401"], ["91501"]))
        self.assertEqual((a["topic"], b["topic"]), ("Topic Alpha", "Topic Beta"))

    def test_the_verified_snapshot_course_comes_from_the_snapshot(self):
        d = data(snapshot_courses=[{"course_id": "CSE-007", "title": "Synthetic Intro", "credits": 4}],
                 snapshot_sections=[{"course_id": "CSE-007", "crn": "92001", "component_type": "LECTURE", "credits": 4, "section_code": "010", "lecture_crn": None},
                                    {"course_id": "CSE-007", "crn": "92002", "component_type": "LAB", "credits": 0, "section_code": "060", "lecture_crn": "92001"}],
                 snapshot_meetings=[mtg("92001", [1], "09:20", "10:35", "TBD"), mtg("92002", [3, 5], "10:45", "12:00")])
        f = bdf.build_fixture(sheets(T.trow("CSE 007", crns="92001; 92002")), d)
        o = self.offering(f, "CSE-007")
        self.assertEqual((o["tier"], o["dataSource"], o["credits"]), ("READY", "SNAPSHOT", 4))
        self.assertEqual([(s["crn"], s["component"], s["credits"], s["lectureCrn"]) for s in o["sections"]],
                         [("92001", "LECTURE", 4, None), ("92002", "LAB", 0, "92001")])

    def test_restrictions_and_independent_study_type_are_carried(self):
        d = data(sections=[sec("CSE-217", "91601", kind="Independent Study")], meetings=[mtg("91601", [1, 3], "15:00", "16:15")],
                 requirements=[req("CSE-217", "91601", "REGISTRATION_RESTRICTION", FR_TEXT), req("CSE-217", "91601", "PREREQUISITE_TEXT", "Catalog Prerequisites CSE 216")])
        f = bdf.build_fixture(sheets(T.trow("CSE 217", crns="91601", req="CSE216 prerequisite; First-Year (FR) excluded")), d)
        o = self.offering(f, "CSE-217")
        self.assertEqual(o["administrativeType"], "Independent Study")
        self.assertEqual(o["rules"], [{"kind": "CLASS_STANDING", "polarity": "CANNOT", "values": ["FR"], "source": "BANNER"}])
        self.assertEqual(o["prerequisiteNote"], "CSE216 prerequisite")
        self.assertTrue(any("TEAM_POLICY" in n for n in o["notes"]))

    def test_a_prerequisite_note_exists_only_when_banner_lists_prerequisites(self):
        d = data(sections=[sec("CSE-252", "91701")], meetings=[mtg("91701", [1], "12:10", "13:25")])
        f = bdf.build_fixture(sheets(T.trow("CSE 252", crns="91701", req="CSE 017 AND CSE 140 would be a prerequisite")), d)
        self.assertIsNone(self.offering(f, "CSE-252")["prerequisiteNote"])
        d2 = data(sections=[sec("CSE-252", "91701")], meetings=[mtg("91701", [1], "12:10", "13:25")],
                  requirements=[req("CSE-252", "91701", "PREREQUISITE_TEXT", "Catalog Prerequisites CSE 017")])
        f2 = bdf.build_fixture(sheets(T.trow("CSE 252", crns="91701", req="Verify exact rules")), d2)
        self.assertEqual(self.offering(f2, "CSE-252")["prerequisiteNote"], "Banner lists prerequisites for this section; see the catalog.")

    def test_workbook_annotations_are_cut_from_the_prerequisite_note(self):
        d = data(sections=[sec("CSE-313", "91801")], meetings=[mtg("91801", [1], "13:35", "14:50")],
                 requirements=[req("CSE-313", "91801", "PREREQUISITE_TEXT", "Catalog Prerequisites CSE 109")])
        f = bdf.build_fixture(sheets(T.trow("CSE 313", crns="91801", req="CSE 109 AND (MATH 043 OR MATH 205) CLAUDE READ-ONLY SCRATCH AUDIT x")), d)
        self.assertEqual(self.offering(f, "CSE-313")["prerequisiteNote"], "CSE 109 AND (MATH 043 OR MATH 205)")

    def test_a_credit_exclusion_is_included_only_when_the_workbook_itself_mentions_the_partner(self):
        d = data(sections=[sec("CSE-313", "91801")], meetings=[mtg("91801", [1], "13:35", "14:50")])
        with_note = bdf.build_fixture(sheets(T.trow("CSE 313", crns="91801", xl="Catalog: no credit for both CSE313 and CSE413")), d)
        self.assertEqual([e["with"] for e in self.offering(with_note, "CSE-313")["creditExclusions"]], ["CSE-413"])
        without = bdf.build_fixture(sheets(T.trow("CSE 313", crns="91801", xl="nothing relevant here")), d)
        self.assertEqual(self.offering(without, "CSE-313")["creditExclusions"], [])

    def test_an_arranged_team_meeting_and_a_catalog_alias_are_carried(self):
        d = data(sections=[sec("CSE-252", "91701")], meetings=[mtg("91701", [5], "10:45", "12:00")])
        cross = [T.CROSS_HEADERS, ["CSE 252 ↔ EMC 252", "CSE 252", "", "", "", "", "", "", ""]]
        f = bdf.build_fixture(sheets(T.trow("CSE 252", crns="91701", component="Fixed class plus a mandatory second weekly team meeting, time arranged by the team"),
                                     **{"POSSIBLE CROSS-LISTS": cross}), d)
        o = self.offering(f, "CSE-252")
        self.assertIn("Time to be arranged", o["arrangedMeeting"])
        self.assertEqual(o["aliases"], ["EMC 252"])

    def test_the_output_is_sorted_and_stable(self):
        d = data(sections=[sec("CSE-111", "91001"), sec("CSE-102", "91002")], meetings=[mtg("91001", [1], "09:00", "10:00"), mtg("91002", [2], "09:00", "10:00")])
        a = bdf.build_fixture(sheets(T.trow("CSE 111", crns="91001"), T.trow("CSE 102", crns="91002")), d)
        b = bdf.build_fixture(sheets(T.trow("CSE 102", crns="91002"), T.trow("CSE 111", crns="91001")), d)
        self.assertEqual(json.dumps(a, sort_keys=True), json.dumps(b, sort_keys=True))
        self.assertEqual([o["offeringId"] for o in a["offerings"]], ["CSE-102", "CSE-111"])

    def test_the_fixture_is_never_written_inside_the_repository_and_never_from_the_shared_database(self):
        with tempfile.TemporaryDirectory() as tmp:
            wb, ev = Path(tmp) / "w.xlsx", Path(tmp) / "e.json"
            T.write_xlsx(wb, sheets(T.trow("CSE 111", crns="91001")))
            ev.write_text(json.dumps(data(sections=[sec("CSE-111", "91001")], meetings=[mtg("91001", [1], "09:00", "10:00")])))
            with self.assertRaises(SystemExit):
                bdf.main(["--workbook", str(wb), "--evidence-json", str(ev), "--out", str(HERE / "must-not-exist.json")])
            self.assertFalse((HERE / "must-not-exist.json").exists())
            bdf.main(["--workbook", str(wb), "--evidence-json", str(ev), "--out", str(Path(tmp) / "out" / "fixture.json")])
            written = json.loads((Path(tmp) / "out" / "fixture.json").read_text(encoding="utf-8"))
            self.assertEqual(len(written["offerings"]), 1)
        with self.assertRaisesRegex(rt.ReconcileError, "shared database"):
            rt.psql_env({"PGDATABASE": "registration_cse007_local"})

    def test_the_evidence_query_is_one_read_only_select(self):
        import re
        sql = bdf.EVIDENCE_QUERY.strip()
        self.assertTrue(sql.upper().startswith("SELECT"))
        self.assertIsNone(re.search(r"\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|COPY|CALL)\b", sql, re.I))


if __name__ == "__main__":
    unittest.main(verbosity=1)
