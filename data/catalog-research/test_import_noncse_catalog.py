"""Unit tests for import_noncse_catalog.py (no database; synthetic fixtures with fake CRNs 99xxx only).

    python3 -I data/catalog-research/test_import_noncse_catalog.py   (from the repository root)
"""
import copy
import importlib.util
import json
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEGREE = HERE.parent / "degree-requirements"
FIXTURES = DEGREE / "fixtures" / "banner_synthetic"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


imp = load("import_noncse_catalog", HERE / "import_noncse_catalog.py")
stage = load("build_staging_db", DEGREE / "build_staging_db.py")
export_mod = load("export_import_ready", DEGREE / "export_import_ready.py")


class Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="noncse-unit-")
        db = Path(cls.tmp) / "staging.db"
        stage.build(db, banner_dir=FIXTURES, banner_origin="synthetic_fixture").close()
        cls.export = export_mod.export(str(db), str(Path(cls.tmp) / "export.json"))
        con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
        cls.equivalences = [(a, b) for a, b in con.execute("SELECT course_code, equivalent_code FROM course_equivalence ORDER BY 1, 2")]
        con.close()
        cls.grid = imp.read_csv(FIXTURES / "sections_grid.csv", imp.GRID_COLUMNS)
        cls.extra = imp.read_csv(FIXTURES / "meetings_extra.csv", imp.EXTRA_COLUMNS)
        cls.nosec = imp.read_csv(FIXTURES / "no_sections.csv", imp.NOSEC_COLUMNS)

    def run_normalize(self, export=None, grid=None, extra=None, nosec=None, term="202710"):
        return imp.normalize(copy.deepcopy(export or self.export), term, copy.deepcopy(grid or self.grid), copy.deepcopy(extra or self.extra),
                             copy.deepcopy(nosec or self.nosec), self.equivalences)


class Normalize(Base):
    def test_counts_and_identities(self):
        grid, programs = self.run_normalize()
        self.assertEqual(len(grid["offerings"]), 7)
        self.assertEqual(sum(len(o["sections"]) for o in grid["offerings"]), 11)
        self.assertEqual(len(grid["gridRows"]), 11)
        self.assertEqual(len(programs["programs"]), 5)
        self.assertTrue(all(o["dataSource"] == "BANNER_GRID_STAGING" for o in grid["offerings"]))

    def test_days_are_expanded_one_row_per_day_and_exams_are_dated(self):
        grid, _ = self.run_normalize()
        sections = {s["crn"]: s for o in grid["offerings"] for s in o["sections"]}
        self.assertEqual([m["day"] for m in sections["99020"]["meetings"]], [1, 3, 5])          # MWF
        self.assertEqual([m["day"] for m in sections["99001"]["meetings"]], [2, 4])             # TR (R = Thursday)
        exams = [m for m in sections["99010"]["meetings"] if m["kind"] == "DATED_ONE_TIME"]
        self.assertEqual([(m["startDate"], m["day"]) for m in exams], [("2027-02-25", 4), ("2027-04-08", 4)])
        self.assertTrue(all(m["startDate"] == m["endDate"] for m in exams))
        self.assertTrue(all(m["kind"] == "WEEKLY" for m in sections["99001"]["meetings"]))

    def test_locations(self):
        grid, _ = self.run_normalize()
        sections = {s["crn"]: s for o in grid["offerings"] for s in o["sections"]}
        self.assertEqual(sections["99001"]["meetings"][0]["location"], "TBA")
        self.assertEqual(sections["99021"]["meetings"][0]["location"], "Synthetic Lab 180")
        self.assertEqual(imp.location("None", "None"), "TBA")
        self.assertEqual(imp.location("Hall", "None"), "Hall")

    def test_components_and_links_are_never_inferred(self):
        grid, _ = self.run_normalize()
        sections = {s["crn"]: s for o in grid["offerings"] for s in o["sections"]}
        self.assertEqual(sections["99002"]["component"], "RECITATION")
        self.assertEqual((sections["99002"]["linkStatus"], sections["99002"]["lectureCrn"]), ("NOT_COLLECTED", None))
        self.assertEqual((sections["99021"]["linkStatus"], sections["99021"]["lectureCrn"]), ("LISTED_BY_LECTURE_ONLY", "99020"))
        self.assertEqual((sections["99030"]["component"], sections["99030"]["linkStatus"]), ("LAB", "NOT_COLLECTED"))
        self.assertEqual(sections["99020"]["linkStatus"], "NOT_APPLICABLE")

    def test_credits_are_the_grid_numbers_and_varying_credits_have_no_single_value(self):
        grid, _ = self.run_normalize()
        offerings = {o["offeringId"]: o for o in grid["offerings"]}
        self.assertEqual((offerings["MATH-312"]["credits"], offerings["MATH-312"]["creditsStatus"]), (None, "VARIES_BY_SECTION"))
        self.assertEqual((offerings["MATH-021"]["credits"], offerings["MATH-021"]["creditsStatus"]), (4, "SINGLE_VALUE"))
        self.assertEqual(offerings["PHY-012"]["credits"], 1)
        self.assertEqual(offerings["PHY-012"]["administrativeType"], "Lab")

    def test_the_cross_list_assertion_is_an_unverified_alias(self):
        grid, programs = self.run_normalize()
        offerings = {o["offeringId"]: o for o in grid["offerings"]}
        self.assertEqual(offerings["ECO-325"]["aliases"], [{"subject": "MKT", "number": "325", "status": "CROSS_LIST_USER_ASSERTED_UNVERIFIED"}])
        self.assertEqual(offerings["ECO-325"]["crossListingStatus"], "CROSS_LISTED_USER_ASSERTED_UNVERIFIED")
        self.assertEqual(offerings["MATH-021"]["crossListingStatus"], "UNVERIFIED")
        self.assertEqual(len(programs["equivalences"]), 2)

    def test_no_instructor_data_reaches_the_payload(self):
        grid, programs = self.run_normalize()

        def keys(node):
            if isinstance(node, dict):
                for k, v in node.items():
                    yield k
                    yield from keys(v)
            elif isinstance(node, list):
                for v in node:
                    yield from keys(v)
        names = {k.lower() for k in keys([grid, programs])}
        self.assertFalse({k for k in names if "instructor" in k})

    def test_read_csv_never_copies_the_instructor_column(self):
        import csv
        with open(FIXTURES / "sections_grid.csv", encoding="utf-8", newline="") as f:
            self.assertIn("instructor", csv.DictReader(f).fieldnames)      # the file has it ...
        self.assertNotIn("instructor", self.grid[0])                         # ... the importer never reads it

    def test_source_and_programs(self):
        grid, programs = self.run_normalize()
        self.assertEqual(grid["source"]["origin"], "synthetic_fixture")
        self.assertEqual(grid["source"]["redistribution"], "SYNTHETIC")
        self.assertEqual(grid["source"]["verification"], "GRID_ONLY_DETAILS_NOT_INSPECTED")
        self.assertEqual({c["prerequisiteStatus"] for c in programs["courses"]}, {"NOT_COLLECTED"})
        self.assertTrue(any(u["subject"] == "INSTRUCTORS" for u in programs["unresolved"]))
        self.assertEqual(imp.course_id("MATH 021"), "MATH-021")


class Refusals(Base):
    def refuse(self, **kw):
        with self.assertRaises(imp.InputError):
            self.run_normalize(**kw)

    def test_wrong_term_or_format(self):
        self.refuse(term="202720")
        bad = copy.deepcopy(self.export)
        bad["format"] = "something/2"
        self.refuse(export=bad)

    def test_export_and_grid_must_list_the_same_crns(self):
        self.refuse(grid=self.grid[:-1])
        extra_row = dict(self.grid[0], crn="99999")
        self.refuse(grid=self.grid + [extra_row])

    def test_a_row_from_another_term(self):
        grid = copy.deepcopy(self.grid)
        grid[0]["term"] = "202720"
        self.refuse(grid=grid)

    def test_duplicate_crn_in_the_export(self):
        bad = copy.deepcopy(self.export)
        bad["sections"].append(copy.deepcopy(bad["sections"][0]))
        self.refuse(export=bad)

    def test_unknown_schedule_type_and_day_letter(self):
        bad = copy.deepcopy(self.export)
        bad["sections"][0]["schedule_type"] = "Seminar"
        self.refuse(export=bad)
        bad = copy.deepcopy(self.export)
        bad["sections"][0]["meetings"][0]["days"] = "MX"
        self.refuse(export=bad)

    def test_an_exam_must_be_one_dated_day(self):
        bad = copy.deepcopy(self.export)
        exam = next(m for s in bad["sections"] for m in s["meetings"] if m["meeting_type"] == "Common Hour Exam")
        exam["end_date"] = "2027-02-26"
        self.refuse(export=bad)

    def test_a_lab_listed_by_two_lectures_is_not_guessed(self):
        bad = copy.deepcopy(self.export)
        lecture = next(s for s in bad["sections"] if s["crn"] == "99020")
        other = next(s for s in bad["sections"] if s["crn"] == "99001")
        other["linked_crns"].append({"linked_crn": "99021", "relation": "LAB_OPTION", "status": "OBSERVED_PARTIAL_LIST"})
        self.assertTrue(lecture["linked_crns"])
        self.refuse(export=bad)

    def test_a_missing_grid_evidence_record(self):
        bad = copy.deepcopy(self.export)
        bad["evidence"] = [e for e in bad["evidence"] if e["origin"] == "public_catalog"]
        self.refuse(export=bad)

    def test_sections_of_one_course_must_agree_on_the_title(self):
        bad = copy.deepcopy(self.export)
        next(s for s in bad["sections"] if s["crn"] == "99002")["title"] = "A Different Title"
        self.refuse(export=bad)

    def test_a_bad_course_code(self):
        with self.assertRaises(imp.InputError):
            imp.course_id("math21")


class Safety(unittest.TestCase):
    def test_the_sql_is_one_transaction_and_a_dry_run_rolls_back(self):
        sql = imp.build_sql({"x": 1}, {"y": 2}, "label", "a" * 64, True)
        self.assertTrue(sql.startswith("BEGIN;") and sql.rstrip().endswith("ROLLBACK;"))
        self.assertTrue(imp.build_sql({"x": 1}, {"y": 2}, "label", "a" * 64, False).rstrip().endswith("COMMIT;"))
        self.assertEqual(sql.count("import_noncse_term"), 1)
        self.assertEqual(sql.count("import_degree_programs"), 1)

    def test_dollar_quote_tag_never_occurs_in_the_text(self):
        literal = imp.sql_literal("a $q0$ b")
        tag = literal[: literal.index("$", 1) + 1]
        self.assertNotIn(tag, "a $q0$ b")

    def test_the_shared_database_is_refused(self):
        old = dict(os.environ)
        try:
            os.environ["PGDATABASE"], os.environ["PGPORT"] = "registration_cse007_local", "55437"
            with self.assertRaises(SystemExit):
                imp.main(["--export", "x", "--staging-db", "y", "--banner-dir", "z"])
            os.environ["PGDATABASE"], os.environ["PGPORT"] = "registration_unified", "55433"
            with self.assertRaises(SystemExit):
                imp.main(["--export", "x", "--staging-db", "y", "--banner-dir", "z"])
        finally:
            os.environ.clear()
            os.environ.update(old)


if __name__ == "__main__":
    unittest.main()
