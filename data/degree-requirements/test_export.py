import json, tempfile, unittest
from pathlib import Path
import build_staging_db as B, export_import_ready as E

class ExportTests(unittest.TestCase):
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
    def test_export_roundtrips_and_counts_match(self):
        self.assertEqual(len(self.reloaded["programs"]), 5)
        self.assertEqual(len(self.reloaded["sections"]), len({s["crn"] for s in self.reloaded["sections"]}))
    def test_sections_carry_meetings_and_unknowns(self):
        s = next(x for x in self.reloaded["sections"] if x["crn"] == "12766")
        self.assertEqual(len(s["meetings"]), 3)
        self.assertEqual(s["restrictions"]["status"], "NOT_COLLECTED")
        self.assertTrue(s["instructors"])
        self.assertEqual(s["instructors"][0]["role"], "Primary")
    def test_requirement_tree_keeps_any_group(self):
        cs = next(p for p in self.reloaded["programs"] if p["id"] == "MINOR_CS")
        core = cs["requirements"][0]
        self.assertTrue(any(c["logic"] == "ANY" for c in core["children"]))
    def test_no_seat_fields(self):
        text = json.dumps(self.reloaded).lower()
        for bad in ("seats remain", "waitlist seats"):
            self.assertNotIn(bad, text)
    def test_instructors_exported_for_every_section(self):
        for s in self.reloaded["sections"]:
            self.assertTrue(any(i["role"] == "Primary" for i in s["instructors"]), s["crn"])
        lab = next(x for x in self.reloaded["sections"] if x["crn"] == "12411")
        self.assertEqual([i["name"] for i in lab["instructors"]], ["Staff, Teaching", "Prendergast, Daniel"])
        self.assertEqual([i["is_placeholder"] for i in lab["instructors"]], [1, 0])
if __name__ == "__main__":
    unittest.main()
