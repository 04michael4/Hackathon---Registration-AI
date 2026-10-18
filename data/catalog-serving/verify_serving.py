"""Read-only checks of catalog_serving against catalog_research (stdlib only; needs psql). Prints PASS/FAIL per check; runs only SELECTs.

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<role> PGDATABASE=<database> python3 -I data/catalog-serving/verify_serving.py

Safe on the team database: it changes nothing.
"""
import subprocess
import sys

CHECKS = [
    ("exactly the READY and CONDITIONAL offerings are served (a grid-staged one only when inspected, with every component linked and its credits known)",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering c FULL JOIN (SELECT o.term_code, o.offering_id FROM catalog_research.offering o "
     "JOIN catalog_research.offering_tier t USING (term_code, offering_id) WHERE t.tier IN ('READY', 'CONDITIONAL') AND o.credits IS NOT NULL "
     "AND (o.data_source IN ('BANNER_EVIDENCE', 'SNAPSHOT') OR EXISTS (SELECT 1 FROM catalog_research.detail_inspection i WHERE i.term_code = o.term_code AND i.offering_id = o.offering_id)) "
     "AND EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id AND s.component_type = 'LECTURE') "
     "AND NOT EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id AND s.component_type <> 'LECTURE' AND s.lecture_crn IS NULL)) x "
     "ON x.term_code = c.term_code AND x.offering_id = c.course_id WHERE c.course_id IS NULL OR x.offering_id IS NULL)"),
    ("no BLOCKED offering is served, and no grid-staged course whose components are unlinked or credits unknown",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering c JOIN catalog_research.offering_tier t ON t.term_code = c.term_code AND t.offering_id = c.course_id "
     "WHERE t.tier = 'BLOCKED') AND NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering c WHERE c.course_id IN ('CHM-030', 'PHY-012', 'MATH-312', 'MATH-334'))"),
    ("a special topic is served only when its identity was decided (never while TOPIC_IDENTITY_UNDECIDED)",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering c JOIN catalog_research.offering o ON o.term_code = c.term_code AND o.offering_id = c.course_id "
     "WHERE o.identity_status = 'TOPIC_IDENTITY_UNDECIDED')"),
    ("common evening exams are shown as a warning for the five courses that have them",
     "SELECT (SELECT count(DISTINCT course_id) FROM catalog_serving.course_condition WHERE code = 'COMMON_EXAMS') = (SELECT count(DISTINCT e.offering_id) FROM catalog_research.exam_meeting e "
     "JOIN catalog_serving.course_offering c ON c.term_code = e.term_code AND c.course_id = e.offering_id)"),
    ("every served course has a lecture and every lab links to a served lecture of the same course",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering c WHERE NOT EXISTS (SELECT 1 FROM catalog_serving.section_offering s WHERE s.term_code = c.term_code "
     "AND s.course_id = c.course_id AND s.component_type = 'LECTURE')) AND NOT EXISTS (SELECT 1 FROM catalog_serving.section_offering l WHERE l.component_type <> 'LECTURE' "
     "AND NOT EXISTS (SELECT 1 FROM catalog_serving.section_offering p WHERE p.crn = l.lecture_crn AND p.course_id = l.course_id AND p.term_code = l.term_code AND p.component_type = 'LECTURE'))"),
    ("every served section and meeting belongs to a served course",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.section_offering s LEFT JOIN catalog_serving.course_offering c ON c.term_code = s.term_code AND c.course_id = s.course_id WHERE c.course_id IS NULL) "
     "AND NOT EXISTS (SELECT 1 FROM catalog_serving.section_meeting m LEFT JOIN catalog_serving.section_offering s ON s.term_code = m.term_code AND s.crn = m.crn WHERE s.crn IS NULL)"),
    ("served sections equal the stored sections of served offerings",
     "SELECT (SELECT count(*) FROM catalog_serving.section_offering) = (SELECT count(*) FROM catalog_research.section s JOIN catalog_serving.served_offering v USING (term_code, offering_id))"),
    ("no served section has an impossible meeting (end before start, bad weekday)",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.section_meeting WHERE start_time >= end_time OR day_of_week NOT BETWEEN 1 AND 7 OR start_time !~ '^[0-2][0-9]:[0-5][0-9]$')"),
    ("every condition has a message and a known evidence level and outcome",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_condition WHERE message IS NULL OR length(btrim(message)) < 10 OR evidence_level NOT IN ('BANNER', 'CATALOG', 'USER_CLARIFICATION', 'TEAM_POLICY') OR outcome <> 'UNVERIFIED')"),
    ("every condition belongs to a served course", 
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_condition c LEFT JOIN catalog_serving.course_offering o ON o.term_code = c.term_code AND o.course_id = c.course_id WHERE o.course_id IS NULL)"),
    ("every CONDITIONAL course shows at least one condition",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.served_offering v WHERE v.tier = 'CONDITIONAL' AND NOT EXISTS (SELECT 1 FROM catalog_serving.course_condition c WHERE c.term_code = v.term_code AND c.course_id = v.offering_id))"),
    ("a structured prerequisite is rendered from its expression (CSE-216 needs CSE 017)",
     "SELECT EXISTS (SELECT 1 FROM catalog_serving.course_condition WHERE course_id = 'CSE-216' AND code = 'PREREQUISITE' AND message = 'Prerequisite: CSE 017')"),
    ("the CSE 127/327 conflict is not shown as an exclusion (current catalog followed)",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_condition WHERE course_id IN ('CSE-127', 'CSE-327') AND code = 'CREDIT_EXCLUSION')"),
    ("the schedule-time TBA meeting of CSE 367 is shown as a condition",
     "SELECT EXISTS (SELECT 1 FROM catalog_serving.course_condition WHERE course_id = 'CSE-367' AND code = 'ARRANGED_MEETING')"),
    ("the Independent Study section type is shown with its team-policy level (CSE-217)",
     "SELECT EXISTS (SELECT 1 FROM catalog_serving.course_condition WHERE course_id = 'CSE-217' AND code = 'SECTION_TYPE' AND evidence_level = 'TEAM_POLICY')"),
    ("every predicted rating is within 1-5, with a confidence and a rating count",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.section_instructor WHERE pred_quality IS NOT NULL AND (pred_quality NOT BETWEEN 1 AND 5 OR pred_difficulty NOT BETWEEN 1 AND 5 OR confidence IS NULL OR rating_count IS NULL OR rating_count < 1)) "
     "AND NOT EXISTS (SELECT 1 FROM catalog_serving.course_rating WHERE pred_quality NOT BETWEEN 1 AND 5 OR pred_difficulty NOT BETWEEN 1 AND 5 OR confidence IS NULL)"),
    ("an instructor with no earlier rating has NO prediction (never a made-up number)",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.section_instructor si WHERE si.pred_quality IS NOT NULL AND NOT EXISTS (SELECT 1 FROM professor_ratings.instructor_prediction p JOIN professor_ratings.instructor i ON i.instructor_id = p.instructor_id WHERE i.banner_name = si.instructor_name))"),
    ("instructors and course ratings belong to served sections and courses only",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.section_instructor si LEFT JOIN catalog_serving.section_offering s ON s.term_code = si.term_code AND s.crn = si.crn WHERE s.crn IS NULL) "
     "AND NOT EXISTS (SELECT 1 FROM catalog_serving.course_rating r LEFT JOIN catalog_serving.course_offering c ON c.term_code = r.term_code AND c.course_id = r.course_id WHERE c.course_id IS NULL)"),
    ("a course rating agrees with the stored professor_ratings course prediction",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_rating r JOIN professor_ratings.course_prediction p ON p.term_code = r.term_code AND p.offering_id = r.course_id WHERE r.pred_quality IS DISTINCT FROM p.pred_quality OR r.pred_difficulty IS DISTINCT FROM p.pred_difficulty)"),
    ("a grid-staged course is served only after its Class Details were inspected",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering c JOIN catalog_research.offering o ON o.term_code = c.term_code AND o.offering_id = c.course_id "
     "WHERE o.data_source = 'BANNER_GRID_STAGING' AND NOT EXISTS (SELECT 1 FROM catalog_research.detail_inspection i WHERE i.term_code = o.term_code AND i.offering_id = o.offering_id))"),
    ("no served course has a requirement left UNKNOWN",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering c JOIN catalog_research.requirement_status q ON q.term_code = c.term_code AND q.offering_id = c.course_id WHERE q.status = 'UNKNOWN')"),
    ("a words-only restriction or prerequisite of a served course is shown as a condition, never dropped",
     "SELECT NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering c JOIN catalog_research.requirement_status q ON q.term_code = c.term_code AND q.offering_id = c.course_id "
     "WHERE q.status = 'TEXT_ONLY' AND NOT EXISTS (SELECT 1 FROM catalog_serving.course_condition k WHERE k.term_code = c.term_code AND k.course_id = c.course_id "
     "AND k.code = CASE q.requirement_type WHEN 'ENROLLMENT_RESTRICTION' THEN 'ENROLLMENT_RESTRICTION' ELSE q.requirement_type END))"),
]


def main():
    failed = 0
    for name, sql in CHECKS:
        r = subprocess.run(["psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1"], input="SET default_transaction_read_only = on;\n" + sql + ";\n", capture_output=True, text=True, timeout=60)
        ok = r.returncode == 0 and r.stdout.strip().splitlines()[-1:] == ["t"]
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            failed += 1
            print("     ", (r.stdout + r.stderr).strip()[:300])
    print(f"{len(CHECKS) - failed}/{len(CHECKS)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
