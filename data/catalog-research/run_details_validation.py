"""Exercises every rule of catalog_research.import_noncse_details (and the serving gate) on an ISOLATED database, one rolled-back transaction per
scenario (stdlib only; needs psql). Uses the staged non-CSE sections already in the database and synthetic requirement text; commits nothing.

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner> PGDATABASE=<isolated copy> python3 -I data/catalog-research/run_details_validation.py

Needs catalog_research V1-V4 and (for the serving scenarios) catalog_serving installed. Refuses the shared database.
"""
import json
import os
import subprocess
import sys

SHARED = {"registration_cse007_local", "registration_unified_spring2027"}


def psql(sql, user=None):
    env = dict(os.environ)
    if user:
        env["PGUSER"] = user
    r = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At", "-F", "|"], input=sql, capture_output=True, text=True, timeout=120, env=env)
    return r.returncode, (r.stdout + r.stderr).strip()


def staged(course):
    code, out = psql(f"SELECT crn, component_type, credits, schedule_type FROM catalog_research.section WHERE term_code = '202710' AND offering_id = '{course}' ORDER BY component_type, crn;")
    if code != 0 or not out:
        sys.exit(f"cannot read staged sections of {course}: {out}")
    rows = [l.split("|") for l in out.splitlines()]
    return [{"crn": a, "type": b, "credits": int(c), "schedule": d} for a, b, c, d in rows]


def lit(value):
    return "$j$" + json.dumps(value, sort_keys=True) + "$j$"


NONE_R = {"status": "NONE_LISTED", "text": None, "rules": []}
NONE_P = {"status": "NONE_LISTED", "text": None, "expression": None}


def course(course_id, sections, links=(), restrictions=NONE_R, prerequisite=NONE_P, corequisite=NONE_P, credit_hours=None, schedule_type=None, inspected=None):
    lectures = [s for s in sections if s["type"] == "LECTURE"]
    use = inspected or [lectures[0]["crn"]]
    details = []
    for crn in use:
        s = next(x for x in sections if x["crn"] == crn)
        details.append({"crn": crn, "creditHours": str(credit_hours if credit_hours is not None else s["credits"]), "scheduleType": schedule_type or s["schedule"]})
    return {"term": "202710", "courseId": course_id, "inspectedCrns": use, "restrictions": restrictions, "prerequisite": prerequisite, "corequisite": corequisite,
            "links": [{"lab": a, "lecture": b, "bothSides": False} for a, b in links], "classDetails": details}


def run_import(*courses):
    payload = {"courses": list(courses), "captureSha256": "a" * 64}
    return f"SELECT catalog_research.import_noncse_details({lit(payload)}::jsonb, 'validation', repeat('b', 64));\n"


def assert_sql(condition, message):
    return f"DO $$ BEGIN IF NOT ({condition}) THEN RAISE EXCEPTION '{message}'; END IF; END $$;\n"


def tier(course_id):
    return f"(SELECT tier || ':' || tier_reason FROM catalog_research.offering_tier WHERE term_code = '202710' AND offering_id = '{course_id}')"


SCENARIOS = []


def scenario(name, sql, expect_error=None, user=None):
    SCENARIOS.append((name, sql, expect_error, user))


def build():
    plain = staged("ISE-121")                       # one lecture, no components
    phy = staged("PHY-011")                         # one lecture, recitations
    math = staged("MATH-021")
    ml = [s for s in math if s["type"] == "LECTURE"]
    pl = [s for s in phy if s["type"] == "LECTURE"][0]["crn"]
    precs = [s["crn"] for s in phy if s["type"] == "RECITATION"]
    cse = psql("SELECT crn FROM catalog_research.section WHERE offering_id = 'CSE-017' AND component_type = 'LECTURE' LIMIT 1;")[1]
    cse_before = "(SELECT count(*) FROM catalog_research.requirement_status WHERE offering_id = 'CSE-217')"

    scenario("a grid-staged course is BLOCKED until inspected", assert_sql(tier("ISE-121") + " = 'BLOCKED:GRID_ONLY_DETAILS_NOT_INSPECTED'", "not blocked before"))
    scenario("an inspected course is judged like the others (CONDITIONAL)", run_import(course("ISE-121", plain)) + assert_sql(tier("ISE-121") + " = 'CONDITIONAL:UNRESOLVED_CONDITIONS_REMAIN'", "not conditional after"))
    scenario("recitations that are not linked keep the course BLOCKED",
             run_import(course("PHY-011", phy)) + assert_sql(tier("PHY-011") + " = 'BLOCKED:COMPONENT_LINKS_NOT_COLLECTED'", "unlinked recitations did not block"))
    scenario("linking every recitation makes it CONDITIONAL, and a one-sided link is LISTED_BY_LECTURE_ONLY",
             run_import(course("PHY-011", phy, links=[(r, pl) for r in precs])) + assert_sql(tier("PHY-011") + " = 'CONDITIONAL:UNRESOLVED_CONDITIONS_REMAIN'", "linked course not conditional") +
             assert_sql("(SELECT count(*) FROM catalog_research.section WHERE offering_id = 'PHY-011' AND link_status = 'LISTED_BY_LECTURE_ONLY' AND lecture_crn = '" + pl + "') = " + str(len(precs)), "links not stored"))
    scenario("a credit difference between Class Details and the grid blocks the course",
             run_import(course("ISE-121", plain, credit_hours=plain[0]["credits"] + 1)) + assert_sql(tier("ISE-121") + " = 'BLOCKED:DETAIL_MISMATCH'", "credit mismatch did not block"))
    scenario("a schedule-type difference blocks the course",
             run_import(course("ISE-121", plain, schedule_type="Lab")) + assert_sql(tier("ISE-121") + " = 'BLOCKED:DETAIL_MISMATCH'", "type mismatch did not block"))
    scenario("silence is recorded as NONE_LISTED with user-supplied evidence and no text",
             run_import(course("ISE-121", plain)) +
             assert_sql("NOT EXISTS (SELECT 1 FROM catalog_research.requirement_status WHERE offering_id = 'ISE-121' AND NOT (status = 'NONE_LISTED' AND evidence_level = 'USER_CLARIFICATION' AND source_text IS NULL))", "silence mapped wrongly") +
             assert_sql("NOT EXISTS (SELECT 1 FROM catalog_research.eligibility_rule WHERE offering_id = 'ISE-121')", "silence created rules"))
    rules = {"status": "RULE_DEFINED", "text": "Cannot be enrolled in one of the following Classes: Senior (SR)", "rules": [{"kind": "CLASS_STANDING", "polarity": "CANNOT", "values": ["SR"]}]}
    expr = {"status": "RULE_DEFINED", "text": "Mathematics 021", "expression": {"op": "OR", "args": [{"course": "MATH-021"}, {"course": "MATH-031"}]}}
    words = {"status": "TEXT_ONLY", "text": "Mathematics 021 Or AP Score 4", "expression": None}
    scenario("rules, expressions and words-only text are stored as what they are",
             run_import(course("ISE-121", plain, restrictions=rules, prerequisite=expr, corequisite=words)) +
             assert_sql("(SELECT count(*) FROM catalog_research.eligibility_rule WHERE offering_id = 'ISE-121' AND rule_kind = 'CLASS_STANDING' AND polarity = 'CANNOT' AND rule_values = '{SR}' AND evidence_level = 'BANNER') = 1", "class rule") +
             assert_sql("(SELECT count(*) FROM catalog_research.eligibility_rule WHERE offering_id = 'ISE-121' AND rule_kind = 'PREREQUISITE' AND parse_status = 'STRUCTURED' AND expression ->> 'op' = 'OR') = 1", "prerequisite expression") +
             assert_sql("(SELECT count(*) FROM catalog_research.eligibility_rule WHERE offering_id = 'ISE-121' AND rule_kind = 'COREQUISITE' AND parse_status = 'TEXT_ONLY' AND expression IS NULL) = 1", "text-only corequisite") +
             assert_sql("(SELECT status FROM catalog_research.requirement_status WHERE offering_id = 'ISE-121' AND requirement_type = 'COREQUISITE') = 'TEXT_ONLY'", "text-only status"))
    scenario("importing twice changes nothing",
             run_import(course("ISE-121", plain, restrictions=rules, prerequisite=expr)) + "CREATE TEMP TABLE before AS SELECT (SELECT count(*) FROM catalog_research.eligibility_rule) r, (SELECT count(*) FROM catalog_research.requirement_status) q, (SELECT count(*) FROM catalog_research.detail_inspection) i;\n" +
             run_import(course("ISE-121", plain, restrictions=rules, prerequisite=expr)) +
             assert_sql("(SELECT (r, q, i) FROM before) = ((SELECT count(*) FROM catalog_research.eligibility_rule), (SELECT count(*) FROM catalog_research.requirement_status), (SELECT count(*) FROM catalog_research.detail_inspection))", "a second import changed rows"))
    scenario("a re-import replaces the course's earlier rules instead of adding to them",
             run_import(course("ISE-121", plain, restrictions=rules, prerequisite=expr)) + run_import(course("ISE-121", plain)) +
             assert_sql("NOT EXISTS (SELECT 1 FROM catalog_research.eligibility_rule WHERE offering_id = 'ISE-121')", "old rules survived"))
    scenario("an import leaves every other offering untouched",
             "CREATE TEMP TABLE b AS SELECT * FROM catalog_research.requirement_status WHERE offering_id NOT IN ('ISE-121');\n" + run_import(course("ISE-121", plain, restrictions=rules)) +
             assert_sql("NOT EXISTS (SELECT 1 FROM (SELECT * FROM b EXCEPT SELECT * FROM catalog_research.requirement_status WHERE offering_id <> 'ISE-121') x)", "another offering changed"))
    scenario("the CSE offerings keep their tier", assert_sql("(SELECT count(*) FROM catalog_research.offering_tier t JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source <> 'BANNER_GRID_STAGING' AND t.tier = 'CONDITIONAL') = 39", "CSE tiers moved"))

    scenario("a CSE offering is refused", run_import(course("CSE-017", [{"crn": cse, "type": "LECTURE", "credits": 3, "schedule": "Lecture"}])), expect_error="is not a grid-staged offering")
    scenario("a CRN of another course is refused", run_import(course("ISE-121", plain + [{"crn": ml[0]["crn"], "type": "LECTURE", "credits": 4, "schedule": "Class"}], inspected=[ml[0]["crn"]])), expect_error="is not a stored section")
    scenario("a lab linked to two lectures is refused", run_import(course("MATH-021", math, links=[(next(s["crn"] for s in math if s["type"] == "RECITATION"), ml[0]["crn"]), (next(s["crn"] for s in math if s["type"] == "RECITATION"), ml[1]["crn"])])), expect_error="more than one lecture")
    scenario("a link whose lab is a lecture is refused", run_import(course("MATH-021", math, links=[(ml[1]["crn"], ml[0]["crn"])])), expect_error="does not name a stored lab")
    scenario("a link to a lecture of another course is refused", run_import(course("ISE-121", plain, links=[(plain[0]["crn"], ml[0]["crn"])])), expect_error="does not name a stored lab")
    scenario("a made-up status is refused", run_import(course("ISE-121", plain, restrictions={"status": "UNKNOWN", "text": None, "rules": []})), expect_error="unexpected ENROLLMENT_RESTRICTION status")
    scenario("an empty import is refused", "SELECT catalog_research.import_noncse_details('{\"courses\": []}'::jsonb, 'validation', repeat('b', 64));\n", expect_error="non-empty")
    scenario("the writer is closed to a read-only role", "SET ROLE catalog_reader;\n" + run_import(course("ISE-121", plain)), expect_error="permission denied", user="postgres")

    # serving
    scenario("an inspected, linked course is served with its conditions; words-only restrictions are shown",
             run_import(course("ISE-121", plain, restrictions={"status": "TEXT_ONLY", "text": "Students must see an advisor.", "rules": []}, prerequisite=expr, corequisite=expr)) +
             assert_sql("EXISTS (SELECT 1 FROM catalog_serving.course_offering WHERE course_id = 'ISE-121')", "not served") +
             assert_sql("EXISTS (SELECT 1 FROM catalog_serving.course_condition WHERE course_id = 'ISE-121' AND code = 'ENROLLMENT_RESTRICTION')", "words-only restriction hidden") +
             assert_sql("EXISTS (SELECT 1 FROM catalog_serving.course_condition WHERE course_id = 'ISE-121' AND code = 'COREQUISITE' AND message = 'Corequisite: MATH 021 or MATH 031')", "corequisite not rendered") +
             assert_sql("EXISTS (SELECT 1 FROM catalog_serving.course_condition WHERE course_id = 'ISE-121' AND code = 'PREREQUISITE' AND message = 'Prerequisite: MATH 021 or MATH 031')", "prerequisite not rendered"))
    scenario("a course with unlinked recitations is not served", run_import(course("PHY-011", phy)) + assert_sql("NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering WHERE course_id = 'PHY-011')", "served with unlinked recitations"))
    scenario("an uninspected course is not served", assert_sql("NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering WHERE course_id = 'MATH-043')", "served uninspected"))
    scenario("a course whose credits vary by section is not served even when inspected",
             run_import(course("MATH-312", staged("MATH-312"), credit_hours=4)) + assert_sql("NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering WHERE course_id = 'MATH-312')", "served with unknown credits"))
    scenario("a mismatched course is not served", run_import(course("ISE-121", plain, credit_hours=plain[0]["credits"] + 1)) + assert_sql("NOT EXISTS (SELECT 1 FROM catalog_serving.course_offering WHERE course_id = 'ISE-121')", "served with a mismatch"))
    del cse_before


def main():
    if os.environ.get("PGDATABASE", "") in SHARED:
        sys.exit("refusing: run this against an isolated copy")
    build()
    failed = 0
    for name, sql, expect, user in SCENARIOS:
        code, out = psql("BEGIN;\n" + sql + "ROLLBACK;\n", user)
        ok = code == 0 if expect is None else (code != 0 and expect in out)
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            failed += 1
            print("     expected:", expect or "success", "| got rc", code, "|", out[:300])
    print(f"{len(SCENARIOS) - failed}/{len(SCENARIOS)} scenarios passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
