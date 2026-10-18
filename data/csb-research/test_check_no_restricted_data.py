"""Tests of check_no_restricted_data.py with a SYNTHETIC payload (invented CRNs 99xxx and names): python3 -I data/csb-research/test_check_no_restricted_data.py"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_no_restricted_data as chk  # noqa: E402

PROSE = "Students who enrol must already hold a passing grade in the first course of the invented sequence before registering here"
PAYLOAD = {
    "sections": [{"crn": "99123", "instructors": [{"name": "Zzyzx, Quentin"}], "detail": {"restrictionsText": PROSE, "panelsNotInspected": ["our own note about panels that were never opened at all"]},
                  "links": [{"memberCrn": "99124"}]}],
    "catalogCourses": [{"courseId": "ZZ-001", "description": "A synthetic course description that is long enough to count as prose for the eight word test"}],
    "comparisons": [{"courseId": "ZZ-001", "crns": ["99125"], "bannerText": "", "catalogText": ""}],
}


class Scan(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.payload = self.dir / "payload.json"
        self.payload.write_text(json.dumps(PAYLOAD), encoding="utf-8")
        self.restricted = chk.load_restricted(self.payload, [])

    def tearDown(self):
        self.tmp.cleanup()

    def kinds(self, text):
        f = self.dir / "candidate.txt"
        f.write_text(text, encoding="utf-8")
        return {kind for _, _, kind in chk.scan([f], *self.restricted)}

    def test_collects_every_crn_including_linked_and_compared(self):
        self.assertEqual({"99123", "99124", "99125"}, self.restricted[0])

    def test_a_real_crn_is_found_but_an_unrelated_number_is_not(self):
        self.assertEqual({"CRN"}, self.kinds("section 99124 is a lab"))
        self.assertEqual(set(), self.kinds("port 55442 and 12345 and 2027 and 99.123"))

    def test_an_instructor_is_found_by_surname_and_by_full_name(self):
        self.assertEqual({"INSTRUCTOR"}, self.kinds("taught by Zzyzx"))
        self.assertEqual({"INSTRUCTOR"}, self.kinds("Zzyzx, Quentin"))
        self.assertEqual(set(), self.kinds("Staff, Teaching"))

    def test_eight_copied_words_are_found_and_seven_are_not(self):
        self.assertEqual({"COPIED_TEXT"}, self.kinds("quote: must already hold a passing grade in the first course"))
        self.assertEqual(set(), self.kinds("must already hold a passing grade in"))

    def test_the_projects_own_panel_notes_are_not_restricted_text(self):
        self.assertEqual(set(), self.kinds("our own note about panels that were never opened at all"))

    def test_credentials_and_home_paths_are_found(self):
        # built from pieces so that this file itself holds nothing shaped like a credential or a home-directory path
        self.assertEqual({"CREDENTIAL"}, self.kinds("pass" + "word = '" + "x" * 12 + "'"))
        self.assertEqual({"CREDENTIAL"}, self.kinds("Authorization: Bear" + "er " + "a" * 32))
        self.assertEqual({"LOCAL_PATH"}, self.kinds("see /Us" + "ers/someone/data/file.json"))
        self.assertEqual(set(), self.kinds("sha256 " + "a" * 64))

    def test_the_report_never_contains_the_hit(self):
        f = self.dir / "candidate.txt"
        f.write_text("99123 Zzyzx", encoding="utf-8")
        report = "\n".join(f"{p}:{n} {k}" for p, n, k in chk.scan([f], *self.restricted))
        self.assertNotIn("99123", report)
        self.assertNotIn("Zzyzx", report)


if __name__ == "__main__":
    unittest.main()
