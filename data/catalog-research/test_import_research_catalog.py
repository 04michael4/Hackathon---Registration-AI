"""Unit tests for import_research_catalog.py (no database): prerequisite parsing, normalization, and the refusals.

    python3 -I data/catalog-research/test_import_research_catalog.py   (from the repository root)
"""
import copy
import importlib.util
import json
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("importer", Path(__file__).resolve().parent / "import_research_catalog.py")
imp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(imp)


def lecture(crn="98001", days=("MONDAY", "WEDNESDAY")):
    return {"crn": crn, "sectionCode": "010", "component": "LECTURE", "credits": 3, "lectureCrn": None,
            "meetings": [{"days": list(days), "start": "10:00", "end": "10:50", "location": "TBA"}]}


def lab(crn="98002", parent="98001"):
    return {"crn": crn, "sectionCode": "060", "component": "LAB", "credits": 0, "lectureCrn": parent,
            "meetings": [{"days": ["THURSDAY"], "start": "13:00", "end": "14:15", "location": "Hall 1"}]}


def offering(**kw):
    base = {"offeringId": "CSE-098", "courseId": "CSE-098", "subject": "CSE", "number": "098", "topic": None, "title": "Synthetic", "credits": 3,
            "administrativeType": "Lecture", "dataSource": "BANNER_EVIDENCE", "tier": "CONDITIONAL", "sections": [lecture()], "rules": [],
            "prerequisiteNote": None, "components": "COMPONENTS_UNVERIFIED", "arrangedMeeting": None, "creditExclusions": [], "aliases": [],
            "crossListing": "UNVERIFIED", "notes": []}
    base.update(kw)
    return base


def fixture(*offerings):
    return {"term": "2027-SPRING", "source": "x", "caution": "y", "offerings": list(offerings)}


class ParsePrerequisite(unittest.TestCase):
    def test_single_course_with_catalog_annotation(self):
        self.assertEqual(imp.parse_prerequisite("CSE 017 (course-level catalog)"), {"course": "CSE-017"})

    def test_compact_codes_and_prefix(self):
        self.assertEqual(imp.parse_prerequisite("Official catalog: CSE017 prerequisite"), {"course": "CSE-017"})
        self.assertEqual(imp.parse_prerequisite("Catalog: CSE 326"), {"course": "CSE-326"})

    def test_or_and_flatten(self):
        self.assertEqual(imp.parse_prerequisite("CSE 004 OR CSE 007 OR CSE 012"),
                         {"op": "OR", "args": [{"course": "CSE-004"}, {"course": "CSE-007"}, {"course": "CSE-012"}]})
        self.assertEqual(imp.parse_prerequisite("(CSE 404 OR ECE 404) OR CSE 271"),
                         {"op": "OR", "args": [{"course": "CSE-404"}, {"course": "ECE-404"}, {"course": "CSE-271"}]})

    def test_nested_groups(self):
        self.assertEqual(imp.parse_prerequisite("(MATH 205 OR MATH 241) AND CSE 017"),
                         {"op": "AND", "args": [{"op": "OR", "args": [{"course": "MATH-205"}, {"course": "MATH-241"}]}, {"course": "CSE-017"}]})

    def test_unparenthesized_mix_is_refused(self):
        self.assertIsNone(imp.parse_prerequisite("CSE 017 AND CSE 160 OR CSE 326"))

    def test_bare_numbers_are_never_guessed(self):
        self.assertIsNone(imp.parse_prerequisite("(MATH021 OR 031 OR 051) AND CSE017"))

    def test_free_text_stays_text(self):
        for text in ("Banner lists prerequisites for this section; see the catalog.", "Banner: CSE 303, standing Junior/Senior/Graduate",
                     "Official catalog: CSE017 is prerequisite for CSE109, and may be taken CONCURRENTLY in the SAME semester", "", None):
            self.assertIsNone(imp.parse_prerequisite(text), text)

    def test_unbalanced_parentheses_are_refused(self):
        self.assertIsNone(imp.parse_prerequisite("(CSE 017 OR CSE 018"))
        self.assertIsNone(imp.parse_prerequisite("CSE 017 OR"))
        self.assertIsNone(imp.parse_prerequisite("AND CSE 017"))


class Normalize(unittest.TestCase):
    def test_days_times_and_schedule_types(self):
        out = imp.normalize(fixture(offering(sections=[lecture(), lab()], components="LABS_USER_SUPPLIED")), "202710")
        o = out["offerings"][0]
        self.assertEqual(o["requiredLabCount"], 1)
        self.assertEqual([m["day"] for m in o["sections"][0]["meetings"]], [1, 3])
        self.assertEqual([s["scheduleType"] for s in o["sections"]], ["Lecture", "Lab"])
        self.assertEqual(o["sections"][1]["lectureCrn"], "98001")

    def test_topic_gets_its_own_undecided_identity(self):
        out = imp.normalize(fixture(offering(offeringId="CSE-398-042", courseId="CSE-398-042", number="398", topic="Advanced OS")), "202710")
        self.assertEqual(out["offerings"][0]["identityStatus"], "TOPIC_IDENTITY_UNDECIDED")
        self.assertEqual(imp.normalize(fixture(offering()), "202710")["offerings"][0]["identityStatus"], "SINGLE")

    def test_arranged_meeting_is_attached_to_the_one_lecture(self):
        out = imp.normalize(fixture(offering(arrangedMeeting="A team meeting has no published time.")), "202710")
        self.assertEqual(out["offerings"][0]["sections"][0]["arrangedMeeting"], "A team meeting has no published time.")

    def test_exclusion_partner_ids_and_labels(self):
        out = imp.normalize(fixture(offering(creditExclusions=[{"with": "CSE-007", "kind": "NO_DOUBLE_CREDIT", "text": "t"},
                                                               {"with": "MATH 261", "kind": "NO_DOUBLE_CREDIT", "text": "t"}])), "202710")
        a, b = out["offerings"][0]["creditExclusions"]
        self.assertEqual((a["withCourseId"], a["withText"]), ("CSE-007", "CSE 007"))
        self.assertEqual((b["withCourseId"], b["withText"]), (None, "MATH 261"))

    def test_aliases(self):
        out = imp.normalize(fixture(offering(aliases=["BIOE 320"])), "202710")
        self.assertEqual(out["offerings"][0]["aliases"], [{"subject": "BIOE", "number": "320"}])

    def refused(self, fx, term="202710"):
        with self.assertRaises(imp.InputError):
            imp.normalize(fx, term)

    def test_refusals(self):
        self.refused({"term": "2026-FALL", "offerings": [offering()]})
        self.refused(fixture(offering()), term="202720")
        self.refused(fixture(offering(), offering()))                                                 # duplicate offering id
        self.refused(fixture(offering(), offering(offeringId="CSE-099", sections=[lecture()])))       # duplicate CRN
        self.refused(fixture(offering(sections=[lecture(), lab()], components="COMPONENTS_UNVERIFIED")))  # labs without the user-supplied status
        self.refused(fixture(offering(components="LABS_USER_SUPPLIED")))                              # the status without labs
        self.refused(fixture(offering(sections=[lecture(), lecture("98003"), lab()], components="LABS_USER_SUPPLIED")))  # labs, two lectures
        self.refused(fixture(offering(sections=[lab()])))                                             # no lecture
        self.refused(fixture(offering(components="SOMETHING_ELSE")))
        self.refused(fixture(offering(aliases=["biology 320"])))
        self.refused(fixture(offering(sections=[lecture(days=("FUNDAY",))])))
        self.refused(fixture())                                                                       # nothing to import
        self.refused(fixture(offering(arrangedMeeting="x", sections=[lecture(), lecture("98003")])))

    def test_normalizing_does_not_mutate_the_input(self):
        fx = fixture(offering(sections=[lecture(), lab()], components="LABS_USER_SUPPLIED"))
        before = copy.deepcopy(fx)
        imp.normalize(fx, "202710")
        self.assertEqual(fx, before)


class Safety(unittest.TestCase):
    def test_dollar_quote_tag_never_occurs_in_the_text(self):
        literal = imp.sql_literal("a $q0$ b $q1$ c")
        tag = literal[: literal.index("$", 1) + 1]
        self.assertNotIn(tag, "a $q0$ b $q1$ c")
        self.assertTrue(literal.startswith(tag) and literal.endswith(tag))

    def test_shared_database_is_refused(self):
        import os
        old = dict(os.environ)
        try:
            os.environ["PGDATABASE"], os.environ["PGPORT"] = "registration_cse007_local", "55437"
            with self.assertRaises(imp.InputError):
                imp.refuse_shared_database()
            os.environ["PGDATABASE"], os.environ["PGPORT"] = "registration_spring2027", "55433"
            with self.assertRaises(imp.InputError):
                imp.refuse_shared_database()
            os.environ["PGDATABASE"], os.environ["PGPORT"] = "registration_spring2027", "55437"
            imp.refuse_shared_database()
        finally:
            os.environ.clear()
            os.environ.update(old)

    def test_sql_is_a_single_transaction_and_dry_run_rolls_back(self):
        sql = imp.build_sql({"termCode": "202710", "offerings": []}, "label", "a" * 64, [], True)
        self.assertTrue(sql.startswith("BEGIN;") and sql.rstrip().endswith("ROLLBACK;"))
        self.assertTrue(imp.build_sql({"termCode": "202710", "offerings": []}, "label", "a" * 64, [], False).rstrip().endswith("COMMIT;"))

    def test_the_decisions_file_names_no_person_and_certifies_nothing(self):
        text = (Path(__file__).resolve().parent / "policy_decisions.json").read_text(encoding="utf-8")
        data = json.loads(text)
        for d in data["decisions"]:
            self.assertEqual(d["decisionType"], "ALLOW_SECTION_TYPE_AS_CONDITIONAL")
            self.assertIn("Not Registrar certification", d["limitations"])
            self.assertNotIn("@", text)


if __name__ == "__main__":
    unittest.main()
