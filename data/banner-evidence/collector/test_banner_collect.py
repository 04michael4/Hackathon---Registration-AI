"""Tests for the Banner collector (stdlib only, no database, no browser).

    python3 -I data/banner-evidence/collector/test_banner_collect.py

They use the SYNTHETIC fixtures in fixtures/ (fake CRNs 99001-99004, a made-up course), never real Banner data.
"""
import copy
import hashlib
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import banner_collect as bc  # noqa: E402

FIX = HERE / "fixtures"


def load(name):
    return json.loads((FIX / name).read_text(encoding="utf-8"))


def captures():
    return [load("synthetic-grid.json"), load("synthetic-popup-99001.json"), load("synthetic-popup-99002.json"), load("synthetic-popup-99003.json")]


def plan_of(items):
    return bc.build_plan([(c, "0" * 64) for c in items])


class ValidCaptures(unittest.TestCase):
    def test_plan_shape(self):
        plan = plan_of(captures())
        self.assertEqual(plan["course_id"], "CSE-099")
        kinds = sorted(s["page_kind"] for s in plan["sources"])
        self.assertEqual(kinds, ["LINKED_SECTIONS"] * 3 + ["SEARCH_RESULTS"] + ["SECTION_DETAILS"] * 3)
        pairs = sorted((c["lecture"], c["lab"]) for c in plan["claims"]["links"])
        # lecture-side (2) and lab-side (2) views of the same two links
        self.assertEqual(pairs, [("99001", "99002"), ("99001", "99002"), ("99001", "99003"), ("99001", "99003")])

    def test_labels_are_kept_apart(self):
        plan = plan_of(captures())
        fields = [(f["view"], f["label"], f["crn"], f["value"]) for s in plan["sources"] for f in s["fields"]]
        self.assertIn(("SEARCH_GRID", "Hours", "99002", "0"), fields)
        self.assertIn(("CLASS_DETAILS", "Credit Hours", "99002", "3"), fields)

    def test_no_explicit_count_is_recorded_as_such_never_inferred(self):
        plan = plan_of(captures())
        kinds = {n["kind"] for s in plan["sources"] for n in s["notes"]}
        self.assertIn("NO_COUNT_STATEMENT", kinds)
        self.assertNotIn("LINK_RULE_TEXT", kinds)
        # the required count stays an explicit UNKNOWN placeholder, and no SQL ever gives REQUIRED_LAB_COUNT a value
        self.assertIn(("REQUIRED_LAB_COUNT", "99001"), plan["claims"]["placeholders"])
        sql = "\n".join(bc.generate_sql(plan))
        self.assertRegex(sql, r"'REQUIRED_LAB_COUNT', 'CSE-099', '99001', NULL, NULL\)")
        self.assertNotRegex(sql, r"'REQUIRED_LAB_COUNT'[^;]*, '[0-9]+'\)")

    def test_explicit_count_language_is_kept_verbatim_for_review(self):
        items = captures()
        items[1]["linked"]["panel_text"] = "Linked Sections You must register for one of the following labs 99002 99003"
        plan = plan_of(items)
        notes = [n for s in plan["sources"] for n in s["notes"] if n["kind"] == "LINK_RULE_TEXT"]
        self.assertEqual(len(notes), 1)
        self.assertIn("must register", notes[0]["text"])

    def test_identical_content_hashes_identically_whatever_the_timestamp(self):
        a, b = captures(), captures()
        for c in b:
            c["captured_at"] = "2027-01-01T00:00:00.000Z"
        self.assertEqual([s["sha"] for s in plan_of(a)["sources"]], [s["sha"] for s in plan_of(b)["sources"]])

    def test_changed_content_is_a_different_source(self):
        a, b = captures(), captures()
        b[0]["rows"][1]["hours"] = "1"
        self.assertNotEqual(plan_of(a)["sources"][0]["sha"], plan_of(b)["sources"][0]["sha"])

    def test_lecture_with_no_links_is_none_shown_and_standalone_stays_unknown(self):
        grid = load("synthetic-grid.json")
        grid["rows"] = grid["rows"][:1]
        grid["header"].update(result_count="1", records="1")
        popup = load("synthetic-popup-99001.json")
        popup["linked"] = {"options": [], "panel_text": "No linked course information available."}
        plan = plan_of([grid, popup])
        linked = [s for s in plan["sources"] if s["page_kind"] == "LINKED_SECTIONS"][0]
        self.assertEqual(linked["sections"][0]["outcome"], "NONE_SHOWN")
        self.assertEqual(linked["links"], [])
        self.assertIn(("STANDALONE_STATUS", "99001"), plan["claims"]["placeholders"])
        sql = "\n".join(bc.generate_sql(plan))
        self.assertNotRegex(sql, r"'STANDALONE_STATUS'[^;]*'(STANDALONE|HAS_LABS)'")

    def test_meeting_parser(self):
        m = bc.parse_meeting("Wednesday,Friday S M T W T F S 12:10 PM - 01:25 PM Type: Lab On-Campus Only Building: None Room: None Start Date: 01/25/2027 End Date: 05/07/2027", "t")
        self.assertEqual((m["days_iso"], m["start"], m["end"], m["building"], m["room"], m["start_date"], m["end_date"]),
                         ([3, 5], "12:10", "13:25", "None", "None", "2027-01-25", "2027-05-07"))
        self.assertEqual(bc.parse_meeting("Friday S M T W T F S 01:35 PM - 04:15 PM Type: Lab Building: 019 Packard Lab Room: 112 Start Date: 01/25/2027 End Date: 05/07/2027", "t")["end"], "16:15")


class Rejections(unittest.TestCase):
    def assert_rejected(self, items, fragment=None):
        with self.assertRaises(bc.CollectError) as ctx:
            plan_of(items)
        if fragment:
            self.assertIn(fragment, str(ctx.exception))

    def test_unknown_keys_are_refused_so_a_layout_change_cannot_add_fields(self):
        items = captures()
        items[0]["rows"][0]["instructor"] = "Someone"
        self.assert_rejected(items, "unexpected keys")
        items = captures()
        items[1]["details"]["enrollment"] = "5"
        self.assert_rejected(items, "unexpected keys")
        items = captures()
        items[0]["status"] = "x"
        self.assert_rejected(items, "unexpected keys")

    def test_seat_instructor_email_and_long_number_tripwires(self):
        for bad in ("70 of 70 seats remain.", "Smith, Jo (Primary)", "someone@lehigh.edu", "id 123456789", "<html>", "Cookie: x=1", "token=abc", "authorization: bearer abcdefghijkl"):
            items = captures()
            items[0]["rows"][0]["title"] = bad
            self.assert_rejected(items, "tripwire")

    def test_tripwire_in_free_text_tabs(self):
        items = captures()
        items[1]["restrictions_text"] = "Call 215550123456"
        self.assert_rejected(items, "tripwire")

    def test_wrong_term_or_zero_results(self):
        items = captures()
        items[0]["header"]["term_label"] = "2026 Fall Semester"
        self.assert_rejected(items, "expected")
        items = captures()
        items[0]["rows"], items[0]["header"]["result_count"], items[0]["header"]["records"] = [], "0", "0"
        self.assert_rejected(items, "0 classes")
        items = captures()
        items[1]["term_code"] = "202620"
        self.assert_rejected(items, "term code")

    def test_partial_page_or_pagination(self):
        items = captures()
        items[0]["header"]["records"] = "12"
        self.assert_rejected(items, "disagree")

    def test_malformed_fields(self):
        for path, value in ((("rows", 0, "crn"), "1234"), (("rows", 0, "hours"), "three"), (("rows", 0, "section"), "toolong1"), (("rows", 0, "link_label"), "Lab - Maybe")):
            items = captures()
            node = items[0]
            for key in path[:-1]:
                node = node[key]
            node[path[-1]] = value
            self.assert_rejected(items)

    def test_duplicate_or_foreign_rows(self):
        items = captures()
        items[0]["rows"][2]["crn"] = items[0]["rows"][1]["crn"]
        self.assert_rejected(items, "duplicate")
        items = captures()
        items[0]["rows"][1]["course_number"] = "100"
        self.assert_rejected(items, "another course")

    def test_unparseable_meeting(self):
        items = captures()
        items[0]["rows"][0]["meetings"][0] = "TBA"
        self.assert_rejected(items, "unrecognized")

    def test_popup_must_agree_with_the_grid(self):
        items = captures()
        items[1]["details"]["title"] = "A Different Title"
        self.assert_rejected(items, "disagrees")
        items = captures()
        items[1]["crn"] = "99999"
        self.assert_rejected(items, "does not list")

    def test_link_to_a_section_the_grid_does_not_show(self):
        items = captures()
        items[1]["linked"]["options"][0]["sections"][0]["crn"] = "99777"
        self.assert_rejected(items, "does not show")

    def test_link_that_cannot_be_oriented_fails_closed(self):
        # Lab 99002's Linked Sections lists another LAB (99003): consistent with the grid in every other way, so only
        # the orientation rule (exactly one side must be the Lecture) can refuse it.
        items = captures()
        items[2]["linked"]["options"] = [{"label": "Option 1", "sections": [{"subject_description": "Computer Science & Engineering",
                                                                          "schedule_type": "Lab", "section": "061", "crn": "99003"}]}]
        items[2]["linked"]["panel_text"] = "Linked Sections Option 1 Title Schedule Type Section CRN Computer Science & Engineering Lab 061 99003"
        self.assert_rejected(items, "cannot orient")

    def test_one_grid_per_import_and_one_popup_per_crn(self):
        self.assert_rejected(captures()[1:], "exactly one search grid")
        items = captures()
        items.append(copy.deepcopy(items[1]))
        self.assert_rejected(items, "two popup captures")

    def test_sha_mismatch_means_a_transcription_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "c.json"
            path.write_text(json.dumps(captures()[0]), encoding="utf-8")
            right = hashlib.sha256(path.read_bytes()).hexdigest()
            bc.load_capture(path, right)
            with self.assertRaises(bc.CollectError):
                bc.load_capture(path, "0" * 64)

    def test_extractor_error_reports_are_not_importable(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "c.json"
            path.write_text('{"error":"SANITIZATION_TRIPWIRE","view":"SEARCH_GRID"}', encoding="utf-8")
            with self.assertRaises(bc.CollectError):
                bc.load_capture(path, None)


class GeneratedSql(unittest.TestCase):
    def setUp(self):
        self.sql = "\n".join(bc.generate_sql(plan_of(captures())))

    def test_only_additive_idempotent_statements(self):
        statements = [s for s in self.sql.split(";\n") if s.strip()]
        self.assertTrue(all(s.lstrip().startswith("INSERT INTO banner_evidence.") for s in statements))
        self.assertTrue(all(s.rstrip().endswith("ON CONFLICT DO NOTHING") or s.rstrip().endswith("ON CONFLICT DO NOTHING;") for s in statements))
        for forbidden in ("UPDATE ", "DELETE ", "TRUNCATE", "DROP ", "ALTER ", "claim_review", "cse007_snapshot", "catalog_api"):
            self.assertNotIn(forbidden, self.sql)

    def test_literals_are_quoted(self):
        self.assertEqual(bc.lit("O'Brien"), "'O''Brien'")
        self.assertEqual(bc.lit(None), "NULL")
        with self.assertRaises(bc.CollectError):
            bc.lit("a\x00b")

    def test_claims_are_never_given_a_value_beyond_linked(self):
        for m in re.finditer(r"INSERT INTO banner_evidence.claim \(.*?\) VALUES (\(.*?\)) ON CONFLICT", self.sql):
            values = m.group(1)
            if "LECTURE_LAB_LINK" not in values:
                self.assertTrue(values.rstrip(")").endswith("NULL, NULL"), values)  # other_crn and asserted_value both NULL

    def test_contradictions_are_only_written_from_a_lecture_view(self):
        self.assertEqual(self.sql.count("'CONTRADICTS'"), 1)  # one lecture popup in the fixture; lab views never contradict

    def test_importer_scans_its_own_generated_text(self):
        """Regression: a raw hash prefix with 8+ digits in a provenance note reached the database and was refused there."""
        original = bc.grouped
        bc.grouped = lambda sha: "1234567890123456"
        try:
            with self.assertRaises(bc.CollectError) as ctx:
                plan_of(captures())
            self.assertIn("generated", str(ctx.exception))
        finally:
            bc.grouped = original

    def test_generated_text_is_tripwire_checked_and_hash_is_grouped(self):
        self.assertRegex(bc.grouped("0" * 64), r"^0000 0000 0000 0000$")
        self.assertFalse(re.search(r"[0-9]{8,}", bc.grouped("1234567812345678" + "0" * 48)))
        self.assertIn("page capture hash", plan_of(captures())["sources"][0]["provenance_note"])


class ExtractorsStayOnTheAllowlist(unittest.TestCase):
    """Static checks on the browser scripts: they may only read these cells and click these tabs."""

    GRID = (HERE / "extract_grid.js").read_text(encoding="utf-8")
    POPUP = (HERE / "extract_popup.js").read_text(encoding="utf-8")
    CODE_ONLY = staticmethod(lambda text: "\n".join(line for line in text.splitlines() if not line.strip().startswith("//")))

    def test_grid_reads_only_whitelisted_cells(self):
        code = self.CODE_ONLY(self.GRID)
        cells = set(re.findall(r"(?:cell|text)\(r(?:ow)?, '(\w+)'\)", code))
        self.assertEqual(cells, {"courseReferenceNumber", "subjectDescription", "courseNumber", "sequenceNumber", "courseTitle", "creditHours", "scheduleType", "attribute", "meetingTime"})
        # (the word "seats" is not checked here: the tripwire regex itself contains it; the cell whitelist above is the proof)
        for never in ("instructor", "'status'", "faculty", "enrollment"):
            self.assertNotIn(never, code)

    def test_popup_clicks_only_allowlisted_tabs_and_close(self):
        code = self.CODE_ONLY(self.POPUP)
        self.assertIn("const TABS = ['classDetails', 'restrictions', 'preReqs', 'coReqs', 'linked']", code)
        for never in ("facultyMeetingTimes", "enrollmentInfo", "fees", "bookstore"):
            self.assertNotIn(never, code)
        self.assertEqual(sorted(set(re.findall(r"\.click\(\)", code))), [".click()"])
        self.assertEqual(code.count(".click()"), 3)  # title link, tab, Close button

    def test_neither_script_makes_requests_or_reads_credentials(self):
        for code in (self.CODE_ONLY(self.GRID), self.CODE_ONLY(self.POPUP)):
            for never in ("fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "document.cookie", "localStorage", "sessionStorage", "navigator.credentials", ".submit(", "location.href", "window.open"):
                self.assertNotIn(never, code)
            self.assertIn("seats?", code)  # the tripwire is present


if __name__ == "__main__":
    unittest.main(verbosity=2)
