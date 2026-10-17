"""Validation tests for the isolated staging DB. Run: python3 -m unittest test_staging_db test_export -v  (from this folder).
Section/meeting/instructor behaviour is tested ONLY on synthetic fixtures (fake CRNs 99xxx); no real Banner data is committed."""
import json, sqlite3, tempfile, unittest
from pathlib import Path
import build_staging_db as B

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures" / "banner_synthetic"


class ProgramTests(unittest.TestCase):
    """Public-catalog requirement structure; built with NO Banner data."""
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.db = B.build(Path(cls.tmp.name) / "t.db")

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
        la = self.q("""SELECT COUNT(DISTINCT o.option_set) FROM requirement_course_option o JOIN requirement_group g ON g.id=o.group_id
                       WHERE g.program_id='ROSSIN_BS_CS' AND g.logic='ANY' AND o.course_code LIKE 'MATH 2%'""")[0][0]
        self.assertEqual(la, 3)

    def test_choose_n_and_credit_groups(self):
        self.assertEqual(self.q("SELECT n FROM requirement_group WHERE program_id='MINOR_DS' AND key='applied'"), [(1,)])
        self.assertEqual(self.q("SELECT credits_text FROM requirement_group WHERE program_id='ROSSIN_BS_CS' AND key='tech_electives'"), [("15",)])

    def test_unresolved_requirements_are_flagged(self):
        flagged = self.q("SELECT COUNT(*) FROM requirement_course_option WHERE status IN ('UNRESOLVED_LIST','CONFLICT_PRESERVED','USER_CHOSEN_CONFLICT_UNRESOLVED_WITH_SOURCE')")[0][0]
        self.assertGreaterEqual(flagged, 3)
        self.assertTrue(json.loads(self.q("SELECT conflicts_json FROM program WHERE id='MINOR_DS'")[0][0]))

    def test_rossin_advanced_hss_conflict_stays_labeled_unresolved(self):
        rows = self.q("SELECT credits, rule_text FROM requirement_course_option WHERE status='USER_CHOSEN_CONFLICT_UNRESOLVED_WITH_SOURCE'")
        self.assertEqual(len(rows), 1)
        credits, rule = rows[0]
        self.assertEqual(credits, 12)
        for phrase in ("still disagree", "16 credits", "not confirmed"):
            self.assertIn(phrase, rule)
        conflicts = json.loads(self.q("SELECT conflicts_json FROM program WHERE id='ROSSIN_BS_CS'")[0][0])
        self.assertTrue(any("16" in c and "12" in c for c in conflicts))
        self.assertEqual(self.q("SELECT COUNT(*) FROM unresolved WHERE subject LIKE 'ROSSIN_BS_CS%'")[0][0], 1)

    def test_eco_mkt_325_cross_list_is_asserted_not_verified(self):
        rows = self.q("SELECT course_code, equivalent_code, status FROM course_equivalence")
        self.assertEqual({(a, b) for a, b, _ in rows}, {("ECO 325", "MKT 325"), ("MKT 325", "ECO 325")})
        self.assertEqual({s for _, _, s in rows}, {"CROSS_LISTED_USER_ASSERTED_UNVERIFIED"})
        self.assertEqual(self.q("SELECT COUNT(*) FROM unresolved WHERE subject='ECO 325 / MKT 325'")[0][0], 1)

    def test_no_status_claims_confirmation_or_approval(self):
        for tbl, col in (("course_equivalence", "status"), ("requirement_course_option", "status")):  # status labels only; prose may say "not confirmed"
            for (v,) in self.q(f"SELECT {col} FROM {tbl} WHERE {col} IS NOT NULL"):
                self.assertNotIn("CONFIRMED", v.upper(), (tbl, v))
                self.assertNotIn("RESOLVED_BY", v.upper(), (tbl, v))

    def test_default_build_has_no_banner_or_instructor_data(self):
        for tbl in ("section", "meeting", "linked_section", "section_restriction", "instructor", "section_instructor"):
            self.assertEqual(self.q(f"SELECT COUNT(*) FROM {tbl}")[0][0], 0, tbl)
        self.assertEqual({r[0] for r in self.q("SELECT origin FROM source_evidence")}, {"public_catalog"})
        self.assertTrue(self.q("SELECT detail FROM unresolved WHERE subject='BANNER_SECTIONS'")[0][0].startswith("NOT_INCLUDED"))
        self.assertTrue(self.q("SELECT detail FROM unresolved WHERE subject='INSTRUCTORS'")[0][0].startswith("NOT_INCLUDED"))
        self.assertEqual({r[0] for r in self.q("SELECT spring2027_status FROM course")}, {"NOT_COLLECTED"})

    def test_prerequisites_stay_unknown(self):
        self.assertEqual({r[0] for r in self.q("SELECT status FROM course_prerequisite")}, {"NOT_COLLECTED"})
        self.assertEqual({r[0] for r in self.q("SELECT expression_json IS NULL FROM course_prerequisite")}, {1})

    def test_credit_exclusion_recorded(self):
        ex = {r[0] for r in self.q("SELECT excluded_text FROM course_credit_exclusion WHERE course_code='MATH 021'")}
        self.assertTrue({"MATH 031", "MATH 051", "MATH 081"} <= ex)

    def test_program_provenance_is_public_catalog(self):
        self.assertEqual(self.q("SELECT COUNT(*) FROM program p LEFT JOIN source_evidence e ON e.id=p.evidence_id WHERE e.id IS NULL")[0][0], 0)
        self.assertEqual({r[0] for r in self.q("SELECT e.origin FROM program p JOIN source_evidence e ON e.id=p.evidence_id")}, {"public_catalog"})

    def test_rebuild_is_idempotent(self):
        tables = ("program", "requirement_group", "requirement_course_option", "course", "section", "meeting", "linked_section")
        before = {t: self.q(f"SELECT COUNT(*) FROM {t}")[0][0] for t in tables}
        other = Path(self.tmp.name) / "again.db"
        d2 = B.build(other)
        after = {t: d2.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in tables}
        d2.close()
        self.assertEqual(before, after)
        d3 = B.build(other)  # rebuild over an existing file: still no duplicates
        self.assertEqual(d3.execute("SELECT COUNT(*) FROM requirement_group").fetchone()[0], before["requirement_group"]); d3.close()


class SectionFixtureTests(unittest.TestCase):
    """Section/meeting/link behaviour on SYNTHETIC fixtures only (fake CRNs 99xxx)."""
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.db = B.build(Path(cls.tmp.name) / "t.db", banner_dir=FIX, banner_origin="synthetic_fixture")

    @classmethod
    def tearDownClass(cls):
        cls.db.close(); cls.tmp.cleanup()

    def q(self, sql, *a):
        return self.db.execute(sql, a).fetchall()

    def test_fixture_crns_are_all_synthetic(self):
        self.assertTrue(all(c.startswith("99") for (c,) in self.q("SELECT crn FROM section")))
        self.assertEqual({r[0] for r in self.q("SELECT origin FROM source_evidence")}, {"public_catalog", "synthetic_fixture"})

    def test_required_noncse_course_statuses(self):
        allowed = {"COLLECTED_GRID", "NOT_OFFERED_IN_SPRING_2027", "NOT_COLLECTED"}
        for code in ("CHM 030", "ENGR 010", "ENGR 005", "MATH 021", "MATH 022", "MATH 231", "PHY 011", "PHY 012", "WRT 001", "WRT 002", "ECO 001", "MATH 312"):
            row = self.q("SELECT spring2027_status FROM course WHERE code=?", code)
            self.assertTrue(row, code)
            self.assertIn(row[0][0], allowed)
        self.assertEqual(self.q("SELECT spring2027_status FROM course WHERE code='ENGR 005'")[0][0], "NOT_OFFERED_IN_SPRING_2027")
        self.assertEqual(self.q("SELECT spring2027_status FROM course WHERE code='MATH 312'")[0][0], "COLLECTED_GRID")
        self.assertEqual(self.q("SELECT spring2027_status FROM course WHERE code='MATH 231'")[0][0], "NOT_COLLECTED")

    def test_crns_unique_within_term(self):
        self.assertEqual(self.q("SELECT COUNT(*) - COUNT(DISTINCT crn) FROM section WHERE term_code='202710'")[0][0], 0)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO section VALUES ('99001','202710','CHM 030','999','x','Class',4,'LINKED','x',1)")
        self.db.rollback()

    def test_sections_have_five_digit_crns(self):
        self.assertEqual(self.q("SELECT COUNT(*) FROM section WHERE crn NOT GLOB '[0-9][0-9][0-9][0-9][0-9]'")[0][0], 0)

    def test_course_has_multiple_sections_and_section_multiple_meetings(self):
        self.assertEqual(self.q("SELECT COUNT(*) FROM section WHERE course_code='MATH 312'")[0][0], 2)
        self.assertEqual(self.q("SELECT COUNT(*) FROM meeting WHERE crn='99010'")[0][0], 3)  # class + 2 common-hour exams

    def test_same_course_sections_can_differ_in_credits(self):
        self.assertEqual(sorted(r[0] for r in self.q("SELECT grid_hours FROM section WHERE course_code='MATH 312'")), [3, 4])

    def test_meetings_attach_to_correct_crn(self):
        self.assertEqual(self.q("SELECT days, start_time, end_time FROM meeting WHERE crn='99020' AND meeting_type='Class'"), [("MWF", "09:20", "10:10")])
        self.assertEqual(self.q("SELECT start_date FROM meeting WHERE crn='99010' AND meeting_type='Common Hour Exam' ORDER BY start_date"),
                         [("2027-02-25",), ("2027-04-08",)])
        self.assertEqual(self.q("SELECT COUNT(*) FROM meeting m LEFT JOIN section s ON s.term_code=m.term_code AND s.crn=m.crn WHERE s.crn IS NULL")[0][0], 0)

    def test_labs_have_zero_grid_credit_and_are_not_double_counted(self):
        self.assertEqual({r[0] for r in self.q("SELECT grid_hours FROM section WHERE course_code='CHM 030' AND schedule_type='Lab'")}, {0})
        self.assertEqual(self.q("SELECT SUM(grid_hours) FROM section WHERE crn IN ('99020','99021')")[0][0], 4)
        self.assertEqual({r[0] for r in self.q("SELECT grid_hours FROM section WHERE course_code='PHY 012'")}, {1})  # separate 1-credit lab course

    def test_linked_labs_only_as_observed(self):
        rows = self.q("SELECT linked_crn, status FROM linked_section WHERE lecture_crn='99020'")
        self.assertEqual(sorted(rows), [("99021", "OBSERVED_PARTIAL_LIST"), ("99022", "OBSERVED_PARTIAL_LIST")])
        self.assertEqual(self.q("SELECT COUNT(*) FROM linked_section WHERE lecture_crn='99001'")[0][0], 0)  # LINKED flag alone creates no link
        for (lab,) in self.q("SELECT linked_crn FROM linked_section"):
            self.assertEqual(self.q("SELECT schedule_type FROM section WHERE crn=?", lab)[0][0], "Lab")

    def test_unknown_restrictions_stay_unknown(self):
        self.assertEqual({r[0] for r in self.q("SELECT status FROM section_restriction")}, {"NOT_COLLECTED"})

    def test_no_seat_or_waitlist_columns(self):
        cols = {r[1] for r in self.q("PRAGMA table_info(section)")}
        self.assertFalse({c for c in cols if "seat" in c or "enroll" in c or "waitlist" in c})

    def test_every_section_has_provenance(self):
        self.assertEqual(self.q("SELECT COUNT(*) FROM section s LEFT JOIN source_evidence e ON e.id=s.evidence_id WHERE e.id IS NULL")[0][0], 0)

    def test_fixture_csv_row_counts_match_db(self):
        import csv
        with open(FIX / "sections_grid.csv", encoding="utf-8") as f:
            self.assertEqual(len(list(csv.DictReader(f))), self.q("SELECT COUNT(*) FROM section")[0][0])

    def test_banner_unresolved_note_says_synthetic(self):
        self.assertIn("Synthetic", self.q("SELECT detail FROM unresolved WHERE subject='BANNER_SECTIONS'")[0][0])


class InstructorLoadingTests(unittest.TestCase):
    """Opt-in instructor loading, exercised with synthetic names only (no real instructor data is committed)."""
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.db = B.build(Path(cls.tmp.name) / "t.db", instructors_csv=FIX / "instructors.csv", banner_dir=FIX, banner_origin="synthetic_fixture")

    @classmethod
    def tearDownClass(cls):
        cls.db.close(); cls.tmp.cleanup()

    def q(self, sql, *a):
        return self.db.execute(sql, a).fetchall()

    def test_roles_and_placeholder_flag(self):
        rows = self.q("SELECT i.display_name, si.role, i.is_placeholder FROM section_instructor si JOIN instructor i ON i.id=si.instructor_id WHERE si.crn='99021' ORDER BY si.role")
        self.assertEqual(rows, [("Fixture, Extra", "Additional", 0), ("Staff, Teaching", "Primary", 1)])
        self.assertEqual({r[0] for r in self.q("SELECT display_name FROM instructor WHERE is_placeholder=1")}, {"Staff, Teaching"})

    def test_loading_instructors_flags_the_local_only_caveat(self):
        self.assertIn("do not commit", self.q("SELECT detail FROM unresolved WHERE subject='INSTRUCTORS'")[0][0])
        self.assertEqual(self.q("SELECT COUNT(*) FROM section_instructor")[0][0], 4)


class RedistributionGuardTests(unittest.TestCase):
    def test_gitignore_keeps_real_banner_data_and_instructors_local(self):
        text = (HERE / ".gitignore").read_text()
        for pattern in ("banner/*.csv", "banner/progress_manifest.json", "banner/section_details.md", "export/local_only_*", "staging.db"):
            self.assertIn(pattern, text)

    def test_only_synthetic_csv_files_outside_the_ignored_banner_folder(self):
        import csv
        csvs = [p for p in HERE.rglob("*.csv") if "banner" not in p.relative_to(HERE).parts[:1]]
        self.assertTrue(csvs)
        for p in csvs:
            self.assertEqual(p.relative_to(HERE).parts[:2], ("fixtures", "banner_synthetic"), p)
            with open(p, encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    if "crn" in row:
                        self.assertTrue(row["crn"].startswith("99"), (p.name, row["crn"]))
                    if "lecture_crn" in row:
                        self.assertTrue(row["lecture_crn"].startswith("99") and row["linked_crn"].startswith("99"), p.name)


if __name__ == "__main__":
    unittest.main()
