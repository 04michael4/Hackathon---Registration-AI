"""Unit tests for csb_dataset.py and catalog_courses.py (no database, no network, SYNTHETIC data only).

    cd data/csb-research && python3 -I -m unittest -v test_csb_dataset      # python3 -I ignores the working directory: run it as below instead
    python3 -I data/csb-research/test_csb_dataset.py
"""
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import catalog_courses  # noqa: E402
import csb_dataset as ds  # noqa: E402
import synthetic_fixture as fx  # noqa: E402

PROGRAM = json.loads((HERE / "csb_program_2026_27.json").read_text(encoding="utf-8"))


class MeetingTests(unittest.TestCase):
    def test_weekly_meeting_gives_one_row_per_day(self):
        m = ds.parse_meeting(fx.WEEKLY.format(days="Monday,Wednesday", start="03:00 PM", end="04:15 PM", type="Class", building="Synthetic Hall", room="101"))
        self.assertEqual((m["kind"], m["days"], m["start"], m["end"], m["location"], m["type"]), ("WEEKLY", [1, 3], "15:00", "16:15", "Synthetic Hall 101", "Class"))
        self.assertEqual((m["startDate"], m["endDate"]), ("2027-01-25", "2027-05-07"))

    def test_no_building_and_no_room_is_tba(self):
        m = ds.parse_meeting(fx.WEEKLY.format(days="Friday", start="08:00 AM", end="08:50 AM", type="Lab", building="None", room="None"))
        self.assertEqual(m["location"], "TBA")

    def test_common_hour_exam_is_a_dated_one_time_meeting_never_weekly(self):
        m = ds.parse_meeting(fx.EXAM)
        self.assertEqual((m["kind"], m["days"], m["startDate"], m["endDate"]), ("DATED_ONE_TIME", [3], "2027-03-03", "2027-03-03"))

    def test_arranged_meeting_has_no_clock_time(self):
        m = ds.parse_meeting(fx.ARRANGED)
        self.assertEqual(m["kind"], "ARRANGED")
        self.assertNotIn("start", m)

    def test_unrecognized_meeting_text_is_refused(self):
        with self.assertRaises(ds.DatasetError):
            ds.parse_meeting("Monday 9am to 10am")

    def test_pack_meeting_tuple_round_trips_to_the_grid_string(self):
        for raw in (fx.WEEKLY.format(days="Tuesday,Thursday", start="10:45 AM", end="12:00 PM", type="Class", building="Lab Hall", room="1"), fx.EXAM, fx.ARRANGED):
            import re
            x = re.match(r"^(.*?) S M T W T F S (?:(\d\d:\d\d [AP]M) - (\d\d:\d\d [AP]M)|-) Type: (.+?) Building: (.*?) Room: (.*?) Start Date: (\d\d/\d\d/\d{4}) End Date: (\d\d/\d\d/\d{4})$", raw)
            self.assertEqual(ds.rebuild_meeting(x.groups()), raw)

    def test_credit_hours(self):
        self.assertEqual(ds.parse_hours("1.5"), (1.5, 1.5))
        self.assertEqual(ds.parse_hours("0"), (0.0, 0.0))
        self.assertEqual(ds.parse_hours("1 TO 3"), (1.0, 3.0))
        with self.assertRaises(ds.DatasetError):
            ds.parse_hours("three")


class RequirementTests(unittest.TestCase):
    def test_banner_prerequisite_keeps_and_or_groups(self):
        p = ds.parse_banner_requirement(fx.PRE_CSB310, "PREREQUISITE")
        self.assertEqual(p["status"], "RULE_DEFINED")
        self.assertEqual(p["expression"], {"op": "AND", "args": [{"op": "OR", "args": [{"course": "ACCT-108"}, {"course": "ACCT-152"}]}, {"op": "OR", "args": [{"course": "CSE-241"}, {"course": "CSE-341"}]}]})
        self.assertEqual(p["minGradeShown"], ["TR"])

    def test_silent_banner_is_none_listed(self):
        self.assertEqual(ds.parse_banner_requirement(fx.PRE_NONE, "PREREQUISITE")["status"], "NONE_LISTED")
        self.assertEqual(ds.parse_restrictions(fx.RESTR_NONE)["status"], "NONE_LISTED")

    def test_unmapped_subject_stays_words_never_a_guess(self):
        p = ds.parse_banner_requirement(fx.PRE_UNMAPPED, "PREREQUISITE")
        self.assertEqual((p["status"], p["expression"]), ("TEXT_ONLY", None))

    def test_restrictions_become_rules_including_degrees(self):
        r = ds.parse_restrictions("Not all restrictions are applicable. Must be enrolled in one of the following Degrees: BS in Synthetic (BSSY) BS in Other(BSOT) "
                                  "Cannot be enrolled in one of the following Classes: First-Year Student (FR)")
        self.assertEqual(r["status"], "RULE_DEFINED")
        self.assertEqual([(x["kind"], x["polarity"], x["values"]) for x in r["rules"]], [("DEGREE", "MUST", ["BSOT", "BSSY"]), ("CLASS_STANDING", "CANNOT", ["FR"])])

    def test_restriction_parser_does_not_leak_the_extra_kinds(self):
        import noncse_details as nd
        ds.parse_restrictions(fx.RESTR_SENIORS)
        self.assertNotIn("Degrees", nd.RESTRICTION_KINDS)

    def test_catalog_prerequisite_parser_and_canonical_comparison(self):
        cat = ds.parse_catalog_prerequisite("(PHY 010 or PHY 011) and (MATH 022 or MATH 032, )")
        self.assertEqual(cat["status"], "RULE_DEFINED")
        ban = ds.parse_banner_requirement(fx.PRE_PHY021, "PREREQUISITE")
        self.assertEqual(ds.canonical(cat["expression"]), ds.canonical(ban["expression"]))

    def test_mixed_and_or_without_parentheses_is_text_only(self):
        self.assertEqual(ds.parse_catalog_prerequisite("CSE 017 and CSE 140 or MATH 021")["status"], "TEXT_ONLY")

    def test_comparison_statuses(self):
        same = ds.compare_prerequisites("(ACCT 108 or ACCT 152) and (CSE 241 or CSE 341)", {fx.PRE_CSB310: ["1"]})[0]
        self.assertEqual(same["status"], "MATCH")
        conflict = ds.compare_prerequisites("(PHY 010 or PHY 011) and (MATH 022 or MATH 032) and CHM 030", {fx.PRE_PHY021: ["1"]})[0]
        self.assertEqual(conflict["status"], "CONFLICT")
        self.assertIn("CHM 030", conflict["catalogText"])                       # both statements are kept
        self.assertIn("Physics 010", conflict["bannerText"])
        logic = ds.compare_prerequisites("(PHY 010 and PHY 011) and (MATH 022 or MATH 032)", {fx.PRE_PHY021: ["1"]})[0]
        self.assertEqual(logic["status"], "SAME_COURSES_DIFFERENT_LOGIC")
        self.assertEqual(ds.compare_prerequisites(None, {fx.PRE_NONE: ["1"]})[0]["status"], "MATCH_BOTH_SILENT")
        self.assertEqual(ds.compare_prerequisites(None, {fx.PRE_CSB310: ["1"]})[0]["status"], "BANNER_ONLY")
        self.assertEqual(ds.compare_prerequisites("CSE 017", {fx.PRE_NONE: ["1"]})[0]["status"], "CATALOG_ONLY")


class LinkTests(unittest.TestCase):
    def test_link_status_both_sides_one_side_and_none(self):
        panels = {"A": [["B"], ["C"]], "B": [["A"]], "C": [["A"]], "D": [], "E": [["A"]]}
        self.assertEqual(ds.link_status("A", panels), "LISTED_BY_ONE_SIDE_ONLY")        # E lists A, A does not list E... A lists B, C; E is a one-way listing
        panels = {"A": [["B"], ["C"]], "B": [["A"]], "C": [["A"]], "D": []}
        self.assertEqual(ds.link_status("A", panels), "CONFIRMED_BOTH_SIDES")
        self.assertEqual(ds.link_status("B", panels), "CONFIRMED_BOTH_SIDES")
        self.assertEqual(ds.link_status("D", panels), "NO_LINKS_LISTED")
        self.assertEqual(ds.link_status("A", {"A": [["B"]], "B": []}), "LISTED_BY_ONE_SIDE_ONLY")
        self.assertEqual(ds.link_status("B", {"A": [["B"]], "B": []}), "LISTED_BY_ONE_SIDE_ONLY")


class BuildTests(unittest.TestCase):
    def setUp(self):
        self.rows, self.caps, self.searches = fx.make_inputs()
        self.sections, self.offerings = ds.build_sections(self.rows, self.caps, "BANNER_202710")
        self.by = {s["crn"]: s for s in self.sections}

    def test_sections_offerings_and_components(self):
        self.assertEqual(len(self.sections), 8)
        self.assertEqual({o["offeringId"]: (o["creditsText"], o["creditsStatus"], o["componentsStatus"]) for o in self.offerings}, {
            "ACCT-999": ("3", "SINGLE_VALUE", "SINGLE_COMPONENT"), "CSB-310": ("3", "SINGLE_VALUE", "SINGLE_COMPONENT"), "CSB-392": ("1 TO 3", "VARIABLE_RANGE", "SINGLE_COMPONENT"),
            "EES-022": ("1", "SINGLE_VALUE", "COMPONENT_ONLY_COURSE"), "PHY-021": ("4", "SINGLE_VALUE", "LINKED_COMPONENTS_LISTED")})
        self.assertEqual(self.by["99011"]["componentType"], "RECITATION")
        self.assertEqual(self.by["99030"]["componentType"], "INDEPENDENT_STUDY")

    def test_credits_are_the_grid_numbers_and_a_bundled_display_is_flagged_not_trusted(self):
        s = self.by["99011"]
        self.assertEqual((s["creditsText"], s["detailsCreditHoursText"], s["creditsDisplayStatus"]), ("0", "4", "DETAILS_DIFFER_FROM_GRID"))
        self.assertEqual(self.by["99010"]["creditsDisplayStatus"], "GRID_AND_DETAILS_AGREE")
        self.assertEqual([d["field"] for d in s["discrepancies"]], ["CREDIT_HOURS"])

    def test_link_status_is_derived_from_both_panels(self):
        self.assertEqual({c: self.by[c]["linkStatus"] for c in ("99010", "99011", "99012", "99001", "99020")},
                         {"99010": "CONFIRMED_BOTH_SIDES", "99011": "CONFIRMED_BOTH_SIDES", "99012": "CONFIRMED_BOTH_SIDES", "99001": "NO_LINKS_LISTED", "99020": "NO_LINKS_LISTED"})
        self.assertEqual(sorted((k["optionNo"], k["memberCrn"]) for k in self.by["99010"]["links"]), [(1, "99011"), (2, "99012")])

    def test_a_listing_only_one_side_makes_the_pair_unconfirmed(self):
        caps = fx.mutated(self.caps, "99010", linked={"options": [{"label": "Option 1", "sections": [{"crn": "99011", "schedule_type": "Recitation", "section": "111"}]}], "panel_text": None})
        sections, _ = ds.build_sections(self.rows, caps, "BANNER_202710")
        by = {s["crn"]: s for s in sections}
        self.assertEqual(by["99011"]["linkStatus"], "CONFIRMED_BOTH_SIDES")
        self.assertEqual(by["99012"]["linkStatus"], "LISTED_BY_ONE_SIDE_ONLY")        # 99012 lists the lecture, the lecture no longer lists 99012
        self.assertEqual(by["99010"]["linkStatus"], "LISTED_BY_ONE_SIDE_ONLY")

    def test_meetings_exam_arranged_and_instructors(self):
        two = self.by["99002"]
        self.assertEqual(sorted((m["kind"], m["day"]) for m in two["meetings"]), [("DATED_ONE_TIME", 3), ("WEEKLY", 1), ("WEEKLY", 3)])
        self.assertEqual([(i["position"], i["name"], i["role"], i["placeholder"]) for i in two["instructors"]], [(1, "Roe, Richard", "Primary", False), (2, "Poe, Edgar", "Additional", False)])
        self.assertEqual(len(self.by["99030"]["arranged"]), 1)
        self.assertEqual(self.by["99030"]["meetings"], [])
        self.assertEqual([(i["name"], i["placeholder"]) for i in self.by["99010"]["instructors"]], [("Staff, Teaching", True)])      # a placeholder, never a guessed professor

    def test_rules_and_inspected_panels(self):
        kinds = sorted(r["kind"] for r in self.by["99001"]["rules"])
        self.assertEqual(kinds, ["CLASS_STANDING", "MAJOR", "PREREQUISITE"])
        d = self.by["99001"]["detail"]
        self.assertIn("Linked Sections", d["panelsInspected"])
        self.assertIn("Enrollment/Waitlist (seat counts are never stored)", d["panelsNotInspected"])
        self.assertEqual(self.by["99040"]["detail"]["prerequisiteStatus"], "TEXT_ONLY")
        self.assertEqual([r["kind"] for r in self.by["99040"]["rules"]], [])        # text-only creates no rule row

    def test_cross_list_rows_without_seat_numbers(self):
        self.assertEqual(self.by["99012"]["crossList"], [{"crn": "99999", "subject": "Physics", "courseNumber": "019", "title": "Synthetic Completion", "section": "113"}])

    def test_refusals(self):
        with self.assertRaises(ds.DatasetError):                    # a section with no Class Details capture
            ds.build_sections(self.rows, {k: v for k, v in self.caps.items() if k != "99001"}, "S")
        with self.assertRaises(ds.DatasetError):                    # a capture with no grid row
            rows = dict(self.rows); rows.pop("99001")
            ds.build_sections(rows, self.caps, "S")
        bad = dict(self.rows); bad["99001"] = dict(self.rows["99001"], subject="Underwater Basketweaving")
        with self.assertRaises(ds.DatasetError):
            ds.build_sections(bad, self.caps, "S")
        caps = fx.mutated(self.caps, "99010", linked={"options": [{"label": "Option 1", "sections": [{"crn": "88888", "schedule_type": "Recitation", "section": "1"}]}], "panel_text": None})
        with self.assertRaises(ds.DatasetError):                    # a link to a CRN that is not stored
            ds.build_sections(self.rows, caps, "S")

    def test_comparisons(self):
        cmp = {c["courseId"]: c["status"] for c in ds.build_comparisons(fx.make_catalog()["courses"], self.sections)}
        self.assertEqual(cmp["CSB-310"], "MATCH")
        self.assertEqual(cmp["PHY-021"], "CONFLICT")
        self.assertEqual(cmp["EES-022"], "MATCH_BOTH_SILENT")
        self.assertEqual(cmp["ACCT-999"], "NOT_COMPARABLE_TEXT_ONLY")

    def test_searches_keep_not_offered_results(self):
        s = ds.build_searches(self.searches, "BANNER_202710")
        zero = [x for x in s if x["result"] == "NOT_OFFERED"]
        self.assertEqual([(x["subjectLabel"], x["courseNumber"], x["courseId"]) for x in zero], [("Computer Science and Business", "010", "CSB-010")])
        self.assertEqual(s[0]["kind"], "SUBJECT_WIDE")


class ProgramTests(unittest.TestCase):
    def setUp(self):
        self.groups, self.lists, self.seqs, self.labs, self.notes = ds.build_program(PROGRAM)

    def test_the_degree_credits_sum_to_136_and_nothing_else_is_assumed(self):
        top = [g for g in self.groups if g["parentKey"] is None]
        self.assertEqual(sum(g["credits"] for g in top), PROGRAM["program"]["total_credits"])
        self.assertEqual(PROGRAM["program"]["total_credits"], 136)
        self.assertEqual({g["key"]: g["credits"] for g in top}, {"CS_REQUIRED": 35, "BUSINESS_REQUIRED": 32, "MATH_REQUIRED": 14, "NATURAL_WORLD": 12, "CSB_REQUIRED": 10,
                                                                 "CSB_PROFESSIONAL_ELECTIVES": 9, "HUMANITIES_SOCIAL": 18, "FREE_ELECTIVES": 6})

    def test_every_category_credit_equals_the_sum_of_its_items(self):
        for cat in PROGRAM["requirements"]:
            self.assertEqual(sum(i["credits"] for i in cat["items"]), cat["credits"], cat["key"])

    def test_alternatives_and_categories_are_preserved_as_such(self):
        g = {x["key"]: x for x in self.groups}
        self.assertEqual(g["DATABASE"]["logic"], "ANY")
        self.assertEqual([(o["optionSet"], o["optionOrder"], o["courseId"]) for o in g["CALCULUS_1"]["options"]], [(1, 1, "MATH-021"), (2, 1, "MATH-075"), (2, 2, "MATH-076")])
        self.assertEqual([(o["optionSet"], o["optionOrder"], o["courseId"]) for o in g["INTRO_PROGRAMMING"]["options"]], [(1, 1, "CSE-003"), (1, 2, "CSE-004"), (2, 1, "CSE-007")])
        for key in ("HE_AL_ELECTIVES", "SW_ELECTIVES", "FREE_ELECTIVES_6", "NW_12_CREDITS"):
            self.assertEqual((g[key]["logic"], g[key]["options"]), ("CREDITS", []))              # categories carry no invented course
        self.assertEqual((g["CSE_ELECTIVE"]["logic"], g["CSE_ELECTIVE"]["n"], g["CSE_ELECTIVE"]["credits"]), ("CHOOSE_N", 1, 3))
        self.assertEqual((g["PROFESSIONAL_ELECTIVES_9"]["logic"], g["PROFESSIONAL_ELECTIVES_9"]["credits"]), ("CREDITS", 9))   # a separate 9-credit requirement

    def test_cse_elective_list_is_catalog_listed_and_professional_candidates_are_unverified(self):
        lists = {x["key"]: x for x in self.lists}
        self.assertTrue(all(m["memberStatus"] == "CATALOG_LISTED" for m in lists["CS_ELECTIVE_LIST"]["members"]))
        self.assertEqual(len(lists["CS_ELECTIVE_LIST"]["members"]), 36)
        pro = lists["CSB_PROFESSIONAL_ELECTIVES"]
        self.assertEqual(pro["status"], "APPROVAL_NOT_VERIFIED")
        self.assertTrue(all(m["memberStatus"] == "APPROVAL_NOT_VERIFIED" for m in pro["members"]))
        self.assertIn("CSB-392", [m["courseId"] for m in pro["members"] if m["memberGroup"] == "CSB_SUBJECT_CATALOG_COURSES"])    # a CSB-prefixed course is not automatically approved

    def test_natural_science_sequences(self):
        by = {s["key"]: s for s in self.seqs}
        self.assertEqual(len(self.seqs), 17)
        self.assertEqual(by["EES_002_022_102"]["courses"], ["EES-002", "EES-022", "EES-102"])
        self.assertIn("EES 002 and EES 022", by["EES_002_022_102"]["specialRule"])
        self.assertEqual(by["CHEM_CHM040_CHM110"]["footnoteMarks"], ["1"])
        self.assertEqual(by["PHYS_PHY011_PHY021"]["footnoteMarks"], ["2"])
        self.assertEqual(by["ASTR_PHY005_ASTR105"]["footnoteMarks"], [])
        self.assertEqual({l["courseId"] for l in self.labs}, {"CHM-111", "PHY-012"})

    def test_program_ids_cover_everything_the_program_names(self):
        ids = ds.program_ids(PROGRAM)
        for must in ("CSB-010", "CSB-313", "BUS-001", "MGT-301", "MATH-075", "ECO-045", "CSE-341", "CHM-111", "PHY-012", "EES-102", "ACCT-315", "ENTP-304", "CSE-392"):
            self.assertIn(must, ids)


class CatalogParserTests(unittest.TestCase):
    PAGE = """<div id="courseinventorycontainer"><div class="courses">
<div class="courseblock">
<p class="courseblocktitle"><strong><span class='cdspacing'>SYN&#160;030  </span><span class='cdspacing'>Synthetic Principles</span> 0,4 <span class='cdspacing'>Credits</span></strong></p>
<p class="courseblockdesc">
A synthetic description.<br />
<b>Prerequisites:</b> <a href="/x" title="SYN&#160;010">SYN&#160;010</a> or SYN 020<br />
<b>Can be taken Concurrently:</b> SYN 020<br/>
<b>Attribute/Distribution:</b> LS, NS, NW, Q<br />
</p>
</div><div class="courseblock">
<p class="courseblocktitle"><strong><span class='cdspacing'>SYN&#160;252   (EMC 252) </span><span class='cdspacing'>Synthetic Ethics</span> 3 <span class='cdspacing'>Credits</span></strong></p>
<p class="courseblockdesc">Another synthetic description.</p>
</div></div></div>"""

    def test_blocks_codes_aliases_credits_and_labels(self):
        a, b = catalog_courses.parse_page(self.PAGE)
        self.assertEqual((a["course_id"], a["title"], a["credits_text"], a["attributes"]), ("SYN-030", "Synthetic Principles", "0,4", ["LS", "NS", "NW", "Q"]))
        self.assertEqual((a["prerequisites_text"], a["concurrent_text"]), ("SYN 010 or SYN 020", "SYN 020"))
        self.assertEqual((b["course_id"], b["catalog_aliases"], b["prerequisites_text"], b["attributes"]), ("SYN-252", ["EMC-252"], None, []))


if __name__ == "__main__":
    unittest.main(verbosity=2)
