"""Unit tests for noncse_details.py (stdlib only; no database). Course names and CRNs are synthetic or public catalog wording.

    cd data/catalog-research && python3 -m unittest -v test_noncse_details
"""
import unittest

import noncse_details as nd

GR = "Not all restrictions are applicable. Must be enrolled in one of the following Levels: Graduate (GR) Must be enrolled in one of the following Colleges: P.C.Rossin Engrg & Applied Sci (EN) Must be enrolled in one of the following Classes: Graduate (GR)"
FR = "Not all restrictions are applicable. Cannot be enrolled in one of the following Classes: First-Year Student (FR)"
JRSR = "Not all restrictions are applicable. Must be enrolled in one of the following Classes: Junior (JR) Senior(SR) Special Approvals: Department Permission Required"
PRE = "Catalog Prerequisites And/Or Test Score Subject Course Number Level Grade "


class RestrictionTests(unittest.TestCase):
    def test_silence_is_none_listed(self):
        for t in (nd.NONE_RESTRICTIONS, "", None):
            self.assertEqual(nd.parse_restrictions(t), {"status": "NONE_LISTED", "text": None, "rules": []})

    def test_level_college_and_class_segments(self):
        r = nd.parse_restrictions(GR)
        self.assertEqual(r["status"], "RULE_DEFINED")
        self.assertEqual([(x["kind"], x["polarity"], x["values"]) for x in r["rules"]],
                         [("LEVEL", "MUST", ["GR"]), ("COLLEGE", "MUST", ["EN"]), ("CLASS_STANDING", "MUST", ["GR"])])

    def test_cannot_and_approvals(self):
        self.assertEqual(nd.parse_restrictions(FR)["rules"], [{"kind": "CLASS_STANDING", "polarity": "CANNOT", "values": ["FR"]}])
        r = nd.parse_restrictions(JRSR)["rules"]
        self.assertEqual(r[0]["values"], ["JR", "SR"])
        self.assertEqual(r[1], {"kind": "DEPARTMENT_APPROVAL", "polarity": "NEEDS", "values": ["Department Permission Required"]})

    def test_anything_unrecognized_stays_text_only_and_invents_no_rule(self):
        for t in ("Must be enrolled in one of the following Degrees: Bachelor of Arts (BA)",
                  "Must be enrolled in one of the following Classes: Postdoc (PD)",
                  "Must be enrolled in one of the following Classes: Junior (JR) and some extra words nobody explains",
                  "Students must see the advisor.",
                  "Must be enrolled in one of the following Levels: Doctoral (DR)"):
            r = nd.parse_restrictions(t)
            self.assertEqual((r["status"], r["rules"]), ("TEXT_ONLY", []), t)
            self.assertEqual(r["text"], nd.clean(t))


class PrerequisiteTests(unittest.TestCase):
    def test_silence_is_none_listed(self):
        self.assertEqual(nd.parse_prerequisite("Catalog Prerequisites No prerequisite information available.")["status"], "NONE_LISTED")
        self.assertEqual(nd.parse_corequisite("Corequisites No corequisite course information available.")["status"], "NONE_LISTED")

    def test_a_flat_or_list(self):
        r = nd.parse_prerequisite(PRE + "Mathematics 021 Undergraduate TR Or Mathematics 031 Undergraduate TR Or Mathematics 051 Undergraduate TR")
        self.assertEqual(r["status"], "RULE_DEFINED")
        self.assertEqual(r["expression"], {"op": "OR", "args": [{"course": "MATH-021"}, {"course": "MATH-031"}, {"course": "MATH-051"}]})

    def test_a_single_course_is_not_wrapped(self):
        r = nd.parse_prerequisite(PRE + "Physics 011 Undergraduate TR")
        self.assertEqual(r["expression"], {"course": "PHY-011"})

    def test_parentheses_group_uniformly(self):
        r = nd.parse_prerequisite(PRE + "( Mathematics 021 Undergraduate TR Or Mathematics 031 Undergraduate TR ) And Computer Science & Engineering 017 Undergraduate TR")
        self.assertEqual(r["expression"], {"op": "AND", "args": [{"op": "OR", "args": [{"course": "MATH-021"}, {"course": "MATH-031"}]}, {"course": "CSE-017"}]})

    def test_mixed_and_or_without_parentheses_is_ambiguous_and_stays_text(self):
        r = nd.parse_prerequisite(PRE + "Electrical & Computer Engr 201 Undergraduate TR Or ( Computer Science & Engineering 201 Undergraduate TR Or Computer Science & Engineering 202 Undergraduate TR ) And Computer Science & Engineering 109 Undergraduate TR")
        self.assertEqual((r["status"], r["expression"]), ("TEXT_ONLY", None))
        self.assertIn("Electrical & Computer Engr 201", r["text"])

    def test_unknown_subject_names_are_reported_not_guessed(self):
        r = nd.parse_prerequisite(PRE + "Basket Weaving 101 Undergraduate TR".replace("101", "101"))
        self.assertEqual(r["status"], "TEXT_ONLY")
        r = nd.parse_prerequisite(PRE + "Underwater Basketry 201 Undergraduate TR Or Mathematics 021 Undergraduate TR")
        self.assertEqual((r["status"], r["unmapped"]), ("TEXT_ONLY", ["Underwater Basketry"]))
        r2 = nd.parse_prerequisite(PRE + "Underwater Basketry 201 Undergraduate TR", subjects={"Underwater Basketry": "UWB"})
        self.assertEqual(r2["expression"], {"course": "UWB-201"})

    def test_test_scores_and_unbalanced_parentheses_stay_text(self):
        for t in (PRE + "Mathematics 021 Undergraduate TR Or AP Calculus AB Score 4", PRE + "( Mathematics 021 Undergraduate TR Or Mathematics 031 Undergraduate TR",
                  PRE + "Mathematics 021 Undergraduate TR Or", PRE + "And Mathematics 021 Undergraduate TR"):
            self.assertEqual(nd.parse_prerequisite(t)["status"], "TEXT_ONLY", t)


def popup(crn, kind, linked=(), term="202710", **extra):
    return {"view": "CLASS_DETAILS_POPUP", "term_code": term, "crn": crn, "details": {"schedule_type": kind, "credit_hours": "4"},
            "restrictions_text": nd.NONE_RESTRICTIONS, "prerequisites_text": "Catalog Prerequisites No prerequisite information available.",
            "corequisites_text": "Corequisites No corequisite course information available.",
            "linked": {"options": [{"label": "Option 1", "sections": [{"crn": c, "schedule_type": k} for c, k in linked]}]}, **extra}


class LinkTests(unittest.TestCase):
    def test_a_lecture_lists_its_labs_and_a_lab_lists_its_lecture(self):
        self.assertEqual(nd.links_from_popup(popup("10001", "Class", [("10002", "Recitation"), ("10003", "Recitation")])), [("10002", "10001"), ("10003", "10001")])
        self.assertEqual(nd.links_from_popup(popup("10002", "Recitation", [("10001", "Class")])), [("10002", "10001")])

    def test_two_lectures_or_itself_are_never_links(self):
        self.assertEqual(nd.links_from_popup(popup("10001", "Class", [("10001", "Class"), ("10005", "Class")])), [])

    def test_no_linked_tab_means_no_links(self):
        self.assertEqual(nd.links_from_popup({"crn": "10001", "details": {"schedule_type": "Class"}}), [])


class CoursePayloadTests(unittest.TestCase):
    def test_payload_shape_and_link_dedup(self):
        lec = popup("10001", "Class", [("10002", "Recitation")], restrictions_text=FR)
        rec = popup("10002", "Recitation", [("10001", "Class")], restrictions_text=FR)
        p = nd.build_course_payload("MATH-099", [rec, lec])
        self.assertEqual((p["courseId"], p["inspectedCrns"], p["links"]), ("MATH-099", ["10001", "10002"], [{"lab": "10002", "lecture": "10001", "bothSides": True}]))
        self.assertEqual(p["restrictions"]["rules"][0]["values"], ["FR"])
        self.assertEqual(p["disagreements"], [])

    def test_a_link_seen_from_one_side_only_is_not_both_sides(self):
        p = nd.build_course_payload("MATH-099", [popup("10001", "Class", [("10002", "Recitation")])])
        self.assertEqual(p["links"], [{"lab": "10002", "lecture": "10001", "bothSides": False}])

    def test_a_labs_page_may_say_less_than_its_lecture_without_being_a_disagreement(self):
        lec = popup("10005", "Class", [("10002", "Recitation")], restrictions_text=FR)
        rec = popup("10002", "Recitation", [("10005", "Class")])   # the recitation page lists no restriction
        p = nd.build_course_payload("MATH-099", [rec, lec])      # the recitation has the lower CRN
        self.assertEqual((p["requirementsFromCrn"], p["restrictions"]["status"], p["disagreements"]), ("10005", "RULE_DEFINED", []))

    def test_a_section_that_differs_is_reported_not_merged(self):
        a = popup("10001", "Class")
        b = popup("10009", "Class", restrictions_text=FR)
        p = nd.build_course_payload("MATH-099", [a, b])
        self.assertEqual(p["disagreements"], [{"crn": "10009", "what": "restrictions"}])
        self.assertEqual(p["restrictions"]["status"], "NONE_LISTED")   # the first section's, unchanged


class LoaderTests(unittest.TestCase):
    def setUp(self):
        import json, tempfile
        from pathlib import Path
        self.json, self.dir = json, Path(tempfile.mkdtemp())

    def write(self, name, obj):
        (self.dir / name).write_text(self.json.dumps(obj), encoding="utf-8")

    def test_one_popup_per_file_and_an_array_file_both_load(self):
        import import_noncse_details as imp
        self.write("a.json", popup("10001", "Class"))
        self.write("b.json", [popup("10002", "Recitation"), popup("10003", "Recitation")])
        popups, sha = imp.load_popups(self.dir)
        self.assertEqual(sorted(popups), ["10001", "10002", "10003"])
        self.assertRegex(sha, "^[0-9a-f]{64}$")

    def test_bad_captures_are_refused(self):
        import import_noncse_details as imp
        cases = {"error.json": {"error": "ROW_NOT_FOUND", "view": "CLASS_DETAILS_POPUP", "crn": "10001"},
                 "view.json": {**popup("10001", "Class"), "view": "SEARCH_RESULTS"},
                 "term.json": popup("10001", "Class", term="202790"),
                 "crn.json": popup("1000", "Class"),
                 "email.json": {**popup("10001", "Class"), "restrictions_text": "see someone@example.edu"},
                 "digits.json": {**popup("10001", "Class"), "restrictions_text": "id 123456789"}}
        for name, obj in cases.items():
            for f in self.dir.glob("*.json"):
                f.unlink()
            self.write(name, obj)
            with self.assertRaises(imp.ImportRefused, msg=name):
                imp.load_popups(self.dir)

    def test_the_same_crn_twice_is_refused(self):
        import import_noncse_details as imp
        self.write("a.json", popup("10001", "Class"))
        self.write("b.json", popup("10001", "Class"))
        with self.assertRaises(imp.ImportRefused):
            imp.load_popups(self.dir)

    def test_an_empty_directory_is_refused(self):
        import import_noncse_details as imp
        with self.assertRaises(imp.ImportRefused):
            imp.load_popups(self.dir)

    def test_captured_crns_must_be_staged_non_cse_sections(self):
        import import_noncse_details as imp
        with self.assertRaises(imp.ImportRefused):
            imp.build({"10001": popup("10001", "Class")}, {"20001": ("MATH-021", "LECTURE")})

    def test_a_lab_listed_by_two_lectures_is_dropped_and_reported_not_stored(self):
        import import_noncse_details as imp
        lec1 = popup("10001", "Class", [("10002", "Recitation"), ("10003", "Recitation")])
        lec2 = popup("10009", "Class", [("10002", "Recitation")])
        staged = {"10001": ("MATH-021", "LECTURE"), "10009": ("MATH-021", "LECTURE"), "10002": ("MATH-021", "RECITATION"), "10003": ("MATH-021", "RECITATION")}
        courses, shared = imp.build({"10001": lec1, "10009": lec2}, staged)
        self.assertEqual(shared, {"MATH-021": ["10002"]})
        self.assertEqual(courses[0]["links"], [{"lab": "10003", "lecture": "10001", "bothSides": False}])

    def test_a_link_leaving_the_course_is_refused(self):
        import import_noncse_details as imp
        staged = {"10001": ("MATH-021", "LECTURE"), "20002": ("ECO-001", "RECITATION")}
        with self.assertRaises(imp.ImportRefused):
            imp.build({"10001": popup("10001", "Class", [("20002", "Recitation")])}, staged)


if __name__ == "__main__":
    unittest.main()
