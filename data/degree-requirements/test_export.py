import json, tempfile, unittest
from pathlib import Path
import build_staging_db as B, export_import_ready as E

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures" / "banner_synthetic"


class ExportTests(unittest.TestCase):
    """Default build: public-catalog programs only, no Banner rows, no instructors."""
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        p = Path(cls.tmp.name)
        B.build(p / "t.db").close()
        cls.doc = E.export(p / "t.db", p / "out.json")
        with open(p / "out.json", encoding="utf-8") as f:
            cls.reloaded = json.load(f)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_export_roundtrips(self):
        self.assertEqual(len(self.reloaded["programs"]), 5)
        self.assertEqual(self.reloaded["programs"], self.doc["programs"])

    def test_export_withholds_banner_and_instructor_data(self):
        self.assertEqual(self.reloaded["sections"], [])
        self.assertEqual(self.reloaded["banner_data"]["status"], "NOT_INCLUDED_REDISTRIBUTION_NOT_VERIFIED")
        self.assertEqual(self.reloaded["instructor_data"]["status"], "NOT_INCLUDED_REDISTRIBUTION_NOT_VERIFIED")

    def test_requirement_tree_keeps_any_group(self):
        cs = next(p for p in self.reloaded["programs"] if p["id"] == "MINOR_CS")
        self.assertTrue(any(c["logic"] == "ANY" for c in cs["requirements"][0]["children"]))

    def test_conflicts_and_asserted_equivalences_carry_their_labels(self):
        rossin = next(p for p in self.reloaded["programs"] if p["id"] == "ROSSIN_BS_CS")
        self.assertTrue(rossin["conflicts"])
        subjects = {u["subject"] for u in self.reloaded["unresolved"]}
        self.assertIn("ROSSIN_BS_CS advanced HSS", subjects)
        self.assertIn("ECO 325 / MKT 325", subjects)
        self.assertIn("BANNER_SECTIONS", subjects)

    def test_committed_export_matches_a_fresh_default_build(self):
        committed = json.loads((HERE / "export" / "non_cse_staging_202710.json").read_text(encoding="utf-8"))
        self.assertEqual(committed, json.loads(json.dumps(self.doc, sort_keys=True)))
        self.assertEqual(committed["sections"], [])
        self.assertEqual(committed["banner_data"]["status"], "NOT_INCLUDED_REDISTRIBUTION_NOT_VERIFIED")


class ExportWithFixtureTests(unittest.TestCase):
    """Sections/instructors in the export, exercised on SYNTHETIC fixtures only."""
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        p = Path(cls.tmp.name)
        B.build(p / "t.db", instructors_csv=FIX / "instructors.csv", banner_dir=FIX, banner_origin="synthetic_fixture").close()
        cls.doc = E.export(p / "t.db", p / "out.json")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_sections_carry_meetings_and_unknowns(self):
        s = next(x for x in self.doc["sections"] if x["crn"] == "99010")
        self.assertEqual(len(s["meetings"]), 3)
        self.assertEqual(s["restrictions"]["status"], "NOT_COLLECTED")
        self.assertEqual(self.doc["banner_data"]["status"], "SYNTHETIC_FIXTURE_ONLY")
        self.assertEqual(len(self.doc["sections"]), len({s["crn"] for s in self.doc["sections"]}))

    def test_export_orders_primary_first(self):
        lab = next(x for x in self.doc["sections"] if x["crn"] == "99021")
        self.assertEqual([i["name"] for i in lab["instructors"]], ["Staff, Teaching", "Fixture, Extra"])
        self.assertEqual([i["is_placeholder"] for i in lab["instructors"]], [1, 0])

    def test_no_seat_fields(self):
        text = json.dumps(self.doc).lower()
        for bad in ("seats remain", "waitlist seats"):
            self.assertNotIn(bad, text)


if __name__ == "__main__":
    unittest.main()
