"""Validation tests for the isolated staging DB. Run: python3 -I -m unittest test_staging_db -v  (from this folder)"""
import json, sqlite3, tempfile, unittest
from pathlib import Path
import build_staging_db as B

HERE = Path(__file__).resolve().parent

class StagingDbTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name) / "t.db"
        cls.db = B.build(cls.path)

    @classmethod
    def tearDownClass(cls):
        cls.db.close(); cls.tmp.cleanup()

    def q(self, sql, *a):
        return self.db.execute(sql, a).fetchall()

    def test_five_programs_kept_separate(self):
        ids = {r[0] for r in self.q("SELECT id FROM program")}
        self.assertEqual(ids, {"ROSSIN_BS_CS", "CAS_BS_CS", "CAS_BA_CS", "MINOR_CS", "MINOR_DS"})
        self.assertEqual(self.q("SELECT COUNT(DISTINCT college) FROM program WHERE degree_type IN ('BS','BA')")[0][0], 2)

    def test_college_specific_requirements_not_merged(self):
        def courses(pid):
            return {r[0] for r in self.q("""SELECT o.course_code FROM requirement_course_option o JOIN requirement_group g ON g.id=o.group_id
                                            WHERE g.program_id=? AND o.course_code IS NOT NULL""", pid)}
        rossin, bs, ba = courses("ROSSIN_BS_CS"), courses("CAS_BS_CS"), courses("CAS_BA_CS")
        self.assertIn("CHM 030", rossin); self.assertNotIn("CHM 030", bs); self.assertNotIn("CHM 030", ba)
        self.assertIn("MATH 241", rossin); self.assertIn("MATH 241", bs); self.assertNotIn("MATH 241", ba)
        self.assertIn("MATH 043", ba); self.assertNotIn("MATH 043", bs)
        self.assertNotIn("CSE 202", ba)

    def test_and_or_structure_preserved(self):
        # CSE 003 & 004 are ONE alternative (same option_set); CSE 007 is the other
        rows = self.q("""SELECT o.option_set, o.course_code FROM requirement_course_option o JOIN requirement_group g ON g.id=o.group_id
                         WHERE g.program_id='MINOR_CS' AND g.logic='ANY' ORDER BY o.option_set, o.course_code""")
        sets = {}
        for s, c in rows:
            sets.setdefault(s, []).append(c)
        self.assertEqual(sorted(sets.values()), [["CSE 003", "CSE 004"], ["CSE 007"]])
        # linear algebra OR group in Rossin BS: three single-course alternatives
        la = self.q("""SELECT COUNT(DISTINCT o.option_set) FROM requirement_course_option o JOIN requirement_group g ON g.id=o.group_id
                       WHERE g.program_id='ROSSIN_BS_CS' AND g.logic='ANY' AND o.course_code LIKE 'MATH 2%'""")[0][0]
        self.assertEqual(la, 3)

    def test_choose_n_and_credit_groups(self):
        r = self.q("SELECT n FROM requirement_group WHERE program_id='MINOR_DS' AND key='applied'")
        self.assertEqual(r, [(1,)])
        self.assertEqual(self.q("SELECT credits_text FROM requirement_group WHERE program_id='ROSSIN_BS_CS' AND key='tech_electives'"), [("15",)])

    def test_unresolved_requirements_are_flagged(self):
        flagged = self.q("SELECT COUNT(*) FROM requirement_course_option WHERE status IN ('UNRESOLVED_LIST','CONFLICT_PRESERVED')")[0][0]
        self.assertGreaterEqual(flagged, 2)
        conflicts = json.loads(self.q("SELECT conflicts_json FROM program WHERE id='MINOR_DS'")[0][0])
        self.assertTrue(conflicts)

    def test_required_noncse_courses_present_or_marked(self):
        for code in ("CHM 030", "ENGR 010", "ENGR 005", "MATH 021", "MATH 022", "MATH 231", "PHY 011", "PHY 012", "WRT 001", "WRT 002", "ECO 001", "MATH 312"):
            row = self.q("SELECT spring2027_status FROM course WHERE code=?", code)
            self.assertTrue(row, code)
            self.assertIn(row[0][0], ("COLLECTED_GRID", "NOT_OFFERED_IN_SPRING_2027", "NOT_COLLECTED"))
        self.assertEqual(self.q("SELECT spring2027_status FROM course WHERE code='ENGR 005'")[0][0], "NOT_OFFERED_IN_SPRING_2027")
        self.assertEqual(self.q("SELECT spring2027_status FROM course WHERE code='MATH 312'")[0][0], "COLLECTED_GRID")
        self.assertEqual(self.q("SELECT COUNT(*) FROM course WHERE spring2027_status='NOT_COLLECTED' AND code NOT LIKE 'CSE %'")[0][0], 0)

    def test_rossin_advanced_hss_resolved_to_12_credits(self):
        rows = self.q("SELECT credits, rule_text FROM requirement_course_option WHERE status LIKE 'RESOLVED_BY_USER%'")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][0], 12)
        self.assertIn("3 courses", rows[0][1])

    def test_eco_mkt_325_cross_listed_both_directions(self):
        pairs = {tuple(r) for r in self.q("SELECT course_code, equivalent_code FROM course_equivalence WHERE status='CROSS_LISTED_USER_CONFIRMED'")}
        self.assertEqual(pairs, {("ECO 325", "MKT 325"), ("MKT 325", "ECO 325")})

    def test_crns_unique_within_term(self):
        self.assertEqual(self.q("SELECT COUNT(*) - COUNT(DISTINCT crn) FROM section WHERE term_code='202710'")[0][0], 0)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO section VALUES ('12303','202710','CHM 030','999','x','Class',4,'LINKED','x',1)")
        self.db.rollback()

    def test_sections_have_crn_and_five_digits(self):
        bad = self.q("SELECT COUNT(*) FROM section WHERE crn NOT GLOB '[0-9][0-9][0-9][0-9][0-9]'")[0][0]
        self.assertEqual(bad, 0)

    def test_course_has_multiple_sections_and_section_multiple_meetings(self):
        self.assertGreater(self.q("SELECT COUNT(*) FROM section WHERE course_code='WRT 002'")[0][0], 30)
        m = self.q("SELECT COUNT(*) FROM meeting WHERE crn='12766'")[0][0]  # MATH 022 lecture: class + 2 common-hour exams
        self.assertEqual(m, 3)

    def test_meetings_attach_to_correct_crn(self):
        r = self.q("SELECT days, start_time, end_time FROM meeting WHERE crn='12303' AND meeting_type='Class'")
        self.assertEqual(r, [("MWF", "09:20", "10:10")])
        exam = self.q("SELECT start_date FROM meeting WHERE crn='12050' AND meeting_type='Common Hour Exam' ORDER BY start_date")
        self.assertEqual(exam, [("2027-02-22",), ("2027-04-05",)])
        orphan = self.q("SELECT COUNT(*) FROM meeting m LEFT JOIN section s ON s.term_code=m.term_code AND s.crn=m.crn WHERE s.crn IS NULL")[0][0]
        self.assertEqual(orphan, 0)

    def test_labs_have_zero_grid_credit_and_are_not_double_counted(self):
        # lecture + lab: only the lecture carries credits in the grid (CHM 030: 4 + 0)
        lab_hours = {r[0] for r in self.q("SELECT grid_hours FROM section WHERE course_code='CHM 030' AND schedule_type='Lab'")}
        self.assertEqual(lab_hours, {0})
        total = self.q("""SELECT SUM(l.grid_hours) FROM section l WHERE l.crn IN ('12305','12411')""")[0][0]
        self.assertEqual(total, 4)
        # PHY 012 is a separate 1-credit course (not part of a lecture CRN)
        self.assertEqual({r[0] for r in self.q("SELECT grid_hours FROM section WHERE course_code='PHY 012'")}, {1})

    def test_linked_labs_only_as_observed(self):
        rows = self.q("SELECT linked_crn, status FROM linked_section WHERE lecture_crn='12305'")
        self.assertEqual(len(rows), 4)
        self.assertTrue(all(s == "OBSERVED_PARTIAL_LIST" for _, s in rows))
        for (lab,) in self.q("SELECT linked_crn FROM linked_section"):
            self.assertEqual(self.q("SELECT schedule_type FROM section WHERE crn=?", lab)[0][0], "Lab")

    def test_unknown_restrictions_and_prereqs_stay_unknown(self):
        self.assertEqual({r[0] for r in self.q("SELECT status FROM section_restriction")}, {"NOT_COLLECTED"})
        self.assertEqual({r[0] for r in self.q("SELECT status FROM course_prerequisite")}, {"NOT_COLLECTED"})
        self.assertEqual({r[0] for r in self.q("SELECT expression_json IS NULL FROM course_prerequisite")}, {1})

    def test_every_section_has_a_primary_instructor(self):
        missing = self.q("SELECT crn FROM section WHERE crn NOT IN (SELECT crn FROM section_instructor WHERE role='Primary')")
        self.assertEqual(missing, [])
        self.assertEqual(self.q("SELECT COUNT(*) FROM section_instructor")[0][0], 225)

    def test_staff_placeholder_is_flagged_not_a_person(self):
        flagged = {r[0] for r in self.q("SELECT display_name FROM instructor WHERE is_placeholder=1")}
        self.assertEqual(flagged, {"Staff, Teaching"})
        self.assertEqual(self.q("SELECT COUNT(*) FROM instructor WHERE is_placeholder=0 AND display_name LIKE 'Staff%'")[0][0], 0)

    def test_instructor_roles_for_labs_and_recitations(self):
        rows = self.q("SELECT i.display_name, si.role FROM section_instructor si JOIN instructor i ON i.id=si.instructor_id WHERE si.crn='12411'")
        self.assertEqual(sorted(rows), [("Prendergast, Daniel", "Additional"), ("Staff, Teaching", "Primary")])

    def test_no_seat_or_waitlist_data_stored(self):
        cols = {r[1] for r in self.q("PRAGMA table_info(section)")}
        self.assertFalse({c for c in cols if "seat" in c or "enroll" in c or "waitlist" in c})

    def test_credit_exclusion_recorded(self):
        ex = {r[0] for r in self.q("SELECT excluded_text FROM course_credit_exclusion WHERE course_code='MATH 021'")}
        self.assertTrue({"MATH 031", "MATH 051", "MATH 081"} <= ex)

    def test_provenance_on_every_section_and_program(self):
        self.assertEqual(self.q("SELECT COUNT(*) FROM section s LEFT JOIN source_evidence e ON e.id=s.evidence_id WHERE e.id IS NULL")[0][0], 0)
        self.assertEqual(self.q("SELECT COUNT(*) FROM program p LEFT JOIN source_evidence e ON e.id=p.evidence_id WHERE e.id IS NULL")[0][0], 0)
        self.assertEqual({r[0] for r in self.q("SELECT origin FROM source_evidence")}, {"public_catalog", "authenticated_banner"})

    def test_rebuild_is_idempotent(self):
        tables = ("program", "requirement_group", "requirement_course_option", "course", "section", "meeting", "linked_section")
        before = {t: self.q(f"SELECT COUNT(*) FROM {t}")[0][0] for t in tables}
        other = Path(self.tmp.name) / "again.db"
        d2 = B.build(other)
        after = {t: d2.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in tables}
        d2.close()
        self.assertEqual(before, after)
        d3 = B.build(other)  # rebuild over an existing file: still no duplicates
        self.assertEqual(d3.execute("SELECT COUNT(*) FROM section").fetchone()[0], before["section"]); d3.close()

    def test_csv_matches_manifest_section_counts(self):
        m = B.read_json(HERE / "banner" / "progress_manifest.json")
        for code, info in m["courses"].items():
            n = self.q("SELECT COUNT(*) FROM section WHERE course_code=?", code)[0][0]
            self.assertEqual(n, info["sections"], code)

if __name__ == "__main__":
    unittest.main()
