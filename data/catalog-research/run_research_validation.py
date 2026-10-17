"""Validation for catalog_research on SYNTHETIC courses (stdlib only; needs psql and a LOCAL cluster you can create databases on).

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<superuser> python3 -I data/catalog-research/run_research_validation.py [--no-mutations]

It creates a throwaway database (research_validation_<pid>), installs every layer into it, runs the scenarios and mutation checks, and
drops it. It never touches any other database and refuses the shared database's port or name. Synthetic CRNs are 94xxx-98xxx.

  1. Install (one committing transaction), then reinstall: identical definitions.
  2. Scenarios, each inside a transaction that is ALWAYS rolled back: import, evidence cross-check, constraints, policy decisions,
     re-import, privileges, and "nothing outside catalog_research changed".
  3. Mutation checks: weakens one rule at a time in a rolled-back copy and requires at least one scenario to FAIL.
"""
import copy
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE.parent
sys.path.insert(0, str(DATA / "catalog-promotion"))
import run_promotion_validation as rpv  # noqa: E402  (synthetic Banner captures and their importer SQL)

V1 = (HERE / "V1__catalog_research_schema.sql").read_text(encoding="utf-8")
V2 = (HERE / "V2__catalog_research_views_and_import.sql").read_text(encoding="utf-8")
V3 = (HERE / "V3__noncse_grid_staging_and_programs.sql").read_text(encoding="utf-8")
TERM = "202710"

PREAMBLE = r"""
\set ON_ERROR_STOP on
CREATE FUNCTION pg_temp.bump() RETURNS void LANGUAGE sql AS $$
	SELECT set_config('t.passed', (COALESCE(NULLIF(current_setting('t.passed', true), ''), '0')::int + 1)::text, true) $$;
CREATE FUNCTION pg_temp.expect_true(label text, cond boolean) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
	IF cond IS TRUE THEN PERFORM pg_temp.bump(); ELSE RAISE EXCEPTION 'probe failed: %', label; END IF;
END $$;
CREATE FUNCTION pg_temp.expect_rejected(label text, stmt text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
	BEGIN EXECUTE stmt;
	EXCEPTION WHEN integrity_constraint_violation OR object_not_in_prerequisite_state OR insufficient_privilege OR invalid_parameter_value THEN PERFORM pg_temp.bump(); RETURN; END;
	RAISE EXCEPTION 'probe failed: % was accepted', label;
END $$;
"""


def psql(args, stdin=None, timeout=600):
    result = subprocess.run(["psql", "-X", "-v", "ON_ERROR_STOP=1", *args], input=stdin, capture_output=True, text=True, timeout=timeout)
    return result.returncode, result.stdout + result.stderr


def query(sql):
    code, out = psql(["-At", "-c", sql])
    if code != 0:
        sys.exit("query failed: " + out.strip()[:300])
    return out.strip()


def check(condition, message):
    if not condition:
        sys.exit("FAILED: " + message)
    print("  ok: " + message)


def probe(label, cond):
    return "SELECT pg_temp.expect_true('" + label.replace("'", "''") + f"', {cond});"


def reject(label, stmt):
    return "SELECT pg_temp.expect_rejected('" + label.replace("'", "''") + f"', $r$ {stmt} $r$);"


# ----------------------------------------------------------------------------------------------------------------------
# Synthetic evidence and the matching normalized payloads
# ----------------------------------------------------------------------------------------------------------------------

RESTRICT = {"restrictions_text": "Not all restrictions are applicable. Cannot be enrolled in one of the following Classes: First-Year Student (FR)",
            "prerequisites_text": "Catalog Prerequisites And/Or Test Score Subject Course Number Level Grade Computer Science & Engineering 017 Undergraduate TR C-"}
LECTURE_ONLY = rpv.capture_set("098", rpv.lecture_only_crns("980"))                       # CSE-098: 98001, 98002
LAB_COURSE = rpv.lab_course("097", "970", RESTRICT)                                        # CSE-097: lecture 97001, labs 97002, 97003
INDEPENDENT = rpv.capture_set("096", [rpv.sec("96001", "010", "Independent Study", "3", "3", rpv.meet("Monday,Wednesday", "03:00 PM", "04:15 PM"))])
TWO_LECTURES = rpv.capture_set("095", [                                                     # CSE-095: two lecture/lab pairs
    rpv.sec("95001", "010", "Lecture", "3", "3", rpv.meet("Monday", "09:00 AM", "09:50 AM"), links=["95003"]),
    rpv.sec("95002", "011", "Lecture", "3", "3", rpv.meet("Tuesday", "09:00 AM", "09:50 AM"), links=["95004"]),
    rpv.sec("95003", "060", "Lab", "0", "3", rpv.meet("Thursday", "01:00 PM", "02:15 PM", "Lab On-Campus Only", "001 Fixture Hall", "101"), parent="95001"),
    rpv.sec("95004", "061", "Lab", "0", "3", rpv.meet("Friday", "01:00 PM", "02:15 PM", "Lab On-Campus Only", "001 Fixture Hall", "102"), parent="95002")])
TOPIC_CAPTURES = rpv.capture_set("094", [                                                    # CSE-094: two special topics, different restrictions
    rpv.sec("94001", "010", "Lecture", "3", "3", rpv.meet("Monday,Wednesday", "10:00 AM", "10:50 AM")),
    rpv.sec("94002", "011", "Lecture", "3", "3", rpv.meet("Tuesday,Thursday", "11:00 AM", "12:15 PM"))])
for _capture in TOPIC_CAPTURES:
    if _capture.get("crn") == "94002":
        _capture["restrictions_text"] = "Not all restrictions are applicable. Must be enrolled in one of the following Classes: Graduate (GR) Junior (JR) Senior (SR)"

# ---- synthetic grid-staged (non-CSE) data: the committed synthetic fixtures run through the real staging pipeline and the real normalizer ----
import importlib.util  # noqa: E402
import sqlite3  # noqa: E402


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


DEGREE = DATA / "degree-requirements"
NONCSE = _load("import_noncse_catalog", HERE / "import_noncse_catalog.py")
_STAGE = _load("build_staging_db", DEGREE / "build_staging_db.py")
_EXPORT = _load("export_import_ready", DEGREE / "export_import_ready.py")
_FIXTURES = DEGREE / "fixtures" / "banner_synthetic"
_WORK = Path(tempfile.mkdtemp(prefix="noncse-synthetic-"))
_STAGE.build(_WORK / "staging.db", banner_dir=_FIXTURES, banner_origin="synthetic_fixture").close()
_export = _EXPORT.export(str(_WORK / "staging.db"), str(_WORK / "export.json"))
_con = sqlite3.connect(f"file:{_WORK / 'staging.db'}?mode=ro", uri=True)
_equivalences = [(a, b) for a, b in _con.execute("SELECT course_code, equivalent_code FROM course_equivalence ORDER BY 1, 2")]
_con.close()
GRID_PAYLOAD, PROGRAM_PAYLOAD = NONCSE.normalize(
    _export, TERM, NONCSE.read_csv(_FIXTURES / "sections_grid.csv", NONCSE.GRID_COLUMNS), NONCSE.read_csv(_FIXTURES / "meetings_extra.csv", NONCSE.EXTRA_COLUMNS),
    NONCSE.read_csv(_FIXTURES / "no_sections.csv", NONCSE.NOSEC_COLUMNS), _equivalences)
EVIDENCE = rpv.import_sql(LECTURE_ONLY, LAB_COURSE, INDEPENDENT, TWO_LECTURES, TOPIC_CAPTURES)


def m(day, start, end, location="TBA"):
    return {"day": day, "start": start, "end": end, "location": location}


def section(crn, code, component, credits, schedule_type, meetings, lecture_crn=None, arranged=None):
    return {"crn": crn, "sectionCode": code, "component": component, "credits": credits, "lectureCrn": lecture_crn, "scheduleType": schedule_type,
            "meetings": meetings, "arrangedMeeting": arranged}


def offering(number, title, credits, sections, components="COMPONENTS_UNVERIFIED", data_source="BANNER_EVIDENCE", admin_type="Lecture", topic=None,
             rules=(), prereq_text=None, prereq_expr=None, exclusions=(), aliases=(), cross="UNVERIFIED"):
    oid = f"CSE-{number}" if topic is None else f"CSE-{number}-{topic}"
    return {"offeringId": oid, "subject": "CSE", "number": number, "topic": topic, "title": title, "credits": credits, "administrativeType": admin_type,
            "dataSource": data_source, "componentsStatus": components, "requiredLabCount": {"LABS_USER_SUPPLIED": 1, "STANDALONE_USER_SUPPLIED": 0}.get(components),
            "crossListingStatus": cross, "identityStatus": "SINGLE" if topic is None else "TOPIC_IDENTITY_UNDECIDED", "sections": sections,
            "rules": list(rules), "prerequisiteText": prereq_text, "prerequisiteExpression": prereq_expr, "creditExclusions": list(exclusions), "aliases": list(aliases)}


def lecture_only():
    return offering("098", "Synthetic Course 098", 3, [
        section("98001", "010", "LECTURE", 3, "Lecture", [m(1, "10:00", "10:50"), m(3, "10:00", "10:50")]),
        section("98002", "011", "LECTURE", 3, "Lecture", [m(2, "11:00", "12:15"), m(4, "11:00", "12:15")])])


def lab_course():
    return offering("097", "Synthetic Course 097", 3, [
        section("97001", "010", "LECTURE", 3, "Lecture", [m(1, "09:00", "09:50"), m(3, "09:00", "09:50")]),
        section("97002", "060", "LAB", 0, "Lab", [m(4, "13:00", "14:15", "001 Fixture Hall 101")], "97001"),
        section("97003", "061", "LAB", 0, "Lab", [m(5, "10:00", "11:15", "001 Fixture Hall 102")], "97001")],
        components="LABS_USER_SUPPLIED", rules=[{"kind": "CLASS_STANDING", "polarity": "CANNOT", "values": ["FR"]}],
        prereq_text="CSE 017", prereq_expr={"course": "CSE-017"}, aliases=[{"subject": "BIOE", "number": "097"}],
        exclusions=[{"withCourseId": "CSE-098", "withText": "CSE 098", "kind": "NO_DOUBLE_CREDIT", "text": "no credit for both"}])


def independent():
    return offering("096", "Synthetic Course 096", 3, [section("96001", "010", "LECTURE", 3, "Independent Study", [m(1, "15:00", "16:15"), m(3, "15:00", "16:15")],
                                                             arranged="A team meeting has no published time.")], admin_type="Independent Study")


def two_lectures():
    return offering("095", "Synthetic Course 095", 3, [
        section("95001", "010", "LECTURE", 3, "Lecture", [m(1, "09:00", "09:50")]),
        section("95002", "011", "LECTURE", 3, "Lecture", [m(2, "09:00", "09:50")]),
        section("95003", "060", "LAB", 0, "Lab", [m(4, "13:00", "14:15", "001 Fixture Hall 101")], "95001"),
        section("95004", "061", "LAB", 0, "Lab", [m(5, "13:00", "14:15", "001 Fixture Hall 102")], "95002")], components="LABS_USER_SUPPLIED")


def snapshot_007():
    loc = "Asa Packer Campus | 019 Packard Lab | Room "
    return offering("007", "Introduction to Programming", 4, [
        section("12275", "010", "LECTURE", 4, "Lecture", [m(1, "09:20", "10:35", "TBD")]),
        section("12638", "062", "LAB", 0, "Lab", [m(3, "10:45", "12:00", loc + "11"), m(5, "10:45", "12:00", loc + "11")], "12275"),
        section("13132", "060", "LAB", 0, "Lab", [m(3, "09:20", "10:35", loc + "112"), m(5, "09:20", "10:35", loc + "112")], "12275")],
        components="LABS_USER_SUPPLIED", data_source="SNAPSHOT")


def topics():
    return [offering("094", "Topic One", 3, [section("94001", "010", "LECTURE", 3, "Lecture", [m(1, "10:00", "10:50"), m(3, "10:00", "10:50")])], topic="010"),
            offering("094", "Topic Two", 3, [section("94002", "011", "LECTURE", 3, "Lecture", [m(2, "11:00", "12:15"), m(4, "11:00", "12:15")])], topic="011",
                     rules=[{"kind": "CLASS_STANDING", "polarity": "MUST", "values": ["GR", "JR", "SR"]}])]


def payload(*offerings):
    return {"termCode": TERM, "offerings": list(offerings)}


def do_import(p, label="validation"):
    text = json.dumps(p, sort_keys=True)
    assert "$imp$" not in text
    return f"SELECT catalog_research.import_term($imp${text}$imp$::jsonb, '{label}', repeat('a', 64));"


def reject_import(label, p):
    text = json.dumps(p, sort_keys=True)
    return reject(label, f"SELECT catalog_research.import_term($imp${text}$imp$::jsonb, 'bad', repeat('b', 64))")


def mutated(p, change):
    q = copy.deepcopy(p)
    change(q)
    return q


def find(p, crn):
    return next(s for o in p["offerings"] for s in o["sections"] if s["crn"] == crn)


GOOD = payload(lecture_only(), lab_course(), independent(), snapshot_007())
DECISION = ("'{t}', '96001', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', '{d}', 'a reviewer allowed this section type in proposed schedules', "
            "'team policy only; not Registrar certification and not a guarantee', DATE '2026-10-16', 'workbook decision log'")



def do_import_noncse(p, label="grid"):
    text = json.dumps(p, sort_keys=True)
    assert "$imp$" not in text
    return f"SELECT catalog_research.import_noncse_term($imp${text}$imp$::jsonb, '{label}', repeat('c', 64));"


def reject_noncse(label, p):
    text = json.dumps(p, sort_keys=True)
    return reject(label, f"SELECT catalog_research.import_noncse_term($imp${text}$imp$::jsonb, 'bad', repeat('d', 64))")


def do_import_programs(p, label="programs"):
    text = json.dumps(p, sort_keys=True)
    assert "$imp$" not in text
    return f"SELECT catalog_research.import_degree_programs($imp${text}$imp$::jsonb, '{label}', repeat('e', 64));"


def reject_programs(label, p):
    text = json.dumps(p, sort_keys=True)
    return reject(label, f"SELECT catalog_research.import_degree_programs($imp${text}$imp$::jsonb, 'bad', repeat('f', 64))")


def gm(change):
    q = copy.deepcopy(GRID_PAYLOAD)
    change(q)
    return q


def pm(change):
    q = copy.deepcopy(PROGRAM_PAYLOAD)
    change(q)
    return q


def gsec(p, crn):
    return next(s for o in p["offerings"] for s in o["sections"] if s["crn"] == crn)


def graw(p, crn):
    return next(r for r in p["gridRows"] if r["crn"] == crn)


def goff(p, offering_id):
    return next(o for o in p["offerings"] if o["offeringId"] == offering_id)


def _walk(groups):
    for g in groups:
        yield g
        yield from _walk(g.get("children") or [])


def count_groups(p):
    return sum(1 for prog in p["programs"] for _ in _walk(prog["requirements"]))


def count_options(p):
    return sum(len(g.get("options") or []) for prog in p["programs"] for g in _walk(prog["requirements"]))


def first_group(p):
    return p["programs"][0]["requirements"][0]


def scenarios():
    out = []
    # 1. A consistent import stores everything, cross-checks it, and serves nothing.
    out.append(("consistent import: stored, cross-checked against evidence, tiered, and nothing is served", "\n".join([
        do_import(GOOD),
        probe("4 offerings and 9 sections stored", "(SELECT count(*) FROM catalog_research.offering) = 4 AND (SELECT count(*) FROM catalog_research.section) = 9"),
        probe("evidence_mismatch is empty (Banner and snapshot rows agree)", "NOT EXISTS (SELECT 1 FROM catalog_research.evidence_mismatch)"),
        probe("tiers: lecture-only and lab course CONDITIONAL, independent study BLOCKED, snapshot READY",
              "(SELECT string_agg(offering_id || ':' || tier, ',' ORDER BY offering_id) FROM catalog_research.offering_tier) = 'CSE-007:READY,CSE-096:BLOCKED,CSE-097:CONDITIONAL,CSE-098:CONDITIONAL'"),
        probe("the blocked reason names the CRN and the unsupported section type", "(SELECT tier_reason FROM catalog_research.offering_tier WHERE offering_id = 'CSE-096') = 'SECTION_TYPE_UNSUPPORTED:96001'"),
        probe("the lab course keeps exactly one required lab and two labs at zero credits",
              "(SELECT required_lab_count FROM catalog_research.offering WHERE offering_id = 'CSE-097') = 1 AND (SELECT count(*) FROM catalog_research.section WHERE offering_id = 'CSE-097' AND component_type = 'LAB' AND credits = 0 AND lecture_crn = '97001') = 2"),
        probe("the arranged meeting is stored as a warning on its section", "(SELECT description FROM catalog_research.section_arranged_meeting WHERE crn = '96001') LIKE '%no published time%'"),
        probe("a Banner restriction is stored verbatim with its structured values", "EXISTS (SELECT 1 FROM catalog_research.eligibility_rule WHERE offering_id = 'CSE-097' AND rule_kind = 'CLASS_STANDING' AND rule_values = ARRAY['FR'] AND source_text LIKE '%First-Year Student (FR)%' AND evidence_level = 'BANNER')"),
        probe("a prerequisite keeps its text, its parsed expression and its research-workbook level", "EXISTS (SELECT 1 FROM catalog_research.eligibility_rule WHERE offering_id = 'CSE-097' AND rule_kind = 'PREREQUISITE' AND expression = '{\"course\": \"CSE-017\"}' AND evidence_level = 'RESEARCH_WORKBOOK')"),
        probe("a requirement Banner lists nothing for is NONE_LISTED, a user-supplied decision, not a Registrar fact", "(SELECT string_agg(requirement_type || ':' || status || ':' || evidence_level, ',' ORDER BY requirement_type) FROM catalog_research.requirement_status WHERE offering_id = 'CSE-098') = 'COREQUISITE:NONE_LISTED:USER_CLARIFICATION,ENROLLMENT_RESTRICTION:NONE_LISTED:USER_CLARIFICATION,PREREQUISITE:NONE_LISTED:USER_CLARIFICATION'"),
        probe("the snapshot course's user-confirmed 'none' keeps its own label", "(SELECT string_agg(status, ',') FROM catalog_research.requirement_status WHERE offering_id = 'CSE-007') = 'USER_CONFIRMED_NONE,USER_CONFIRMED_NONE,USER_CONFIRMED_NONE'"),
        probe("a credit exclusion, an alias and a cross-listing status are stored", "(SELECT count(*) FROM catalog_research.credit_exclusion WHERE offering_id = 'CSE-097' AND excluded_course_id = 'CSE-098') = 1 AND (SELECT count(*) FROM catalog_research.course_alias WHERE offering_id = 'CSE-097') = 1"),
        probe("a structured prerequisite names the same courses as Banner's text (the check view is empty)", "NOT EXISTS (SELECT 1 FROM catalog_research.prerequisite_banner_check)"),
        probe("the candidate views carry every stored row", "(SELECT count(*) FROM catalog_research.candidate_course_offering) = 4 AND (SELECT count(*) FROM catalog_research.candidate_section_offering) = 9 AND (SELECT count(*) FROM catalog_research.candidate_section_meeting) = (SELECT count(*) FROM catalog_research.section_meeting)"),
        probe("only the verified snapshot course is schedulable in the candidate view", "(SELECT string_agg(course_id, ',' ORDER BY course_id) FROM catalog_research.candidate_course_offering WHERE schedulable) = 'CSE-007'"),
        probe("the snapshot course's candidate rows equal catalog_api's rows", "NOT EXISTS (SELECT 1 FROM (SELECT term_code, course_id, crn, section_code, credits, component_type, lecture_crn, status FROM catalog_research.candidate_section_offering WHERE course_id = 'CSE-007' EXCEPT SELECT term_code, course_id, crn, section_code, credits, component_type, lecture_crn, status FROM catalog_api.section_offering) x)"),
        probe("nothing stored here appears in catalog_live", "NOT EXISTS (SELECT 1 FROM catalog_live.course_offering WHERE course_id IN ('CSE-096', 'CSE-097', 'CSE-098'))"),
        probe("offering_status separates stored, approval and eligibility", "(SELECT string_agg(offering_id || ':' || serving_status || ':' || promotion_state, ',' ORDER BY offering_id) FROM catalog_research.offering_status) = 'CSE-007:SERVED:VERIFIED_CATALOG,CSE-096:STORED_NOT_SERVED:BLOCKED,CSE-097:STORED_NOT_SERVED:BLOCKED,CSE-098:STORED_NOT_SERVED:BLOCKED'"),
        probe("registration eligibility is never guaranteed", "NOT EXISTS (SELECT 1 FROM catalog_research.offering_status WHERE registration_eligibility_guaranteed)"),
        probe("unresolved requirements are listed; NONE_LISTED ones are not", "EXISTS (SELECT 1 FROM catalog_research.unresolved_requirement WHERE offering_id = 'CSE-097' AND status = 'RULE_DEFINED') AND NOT EXISTS (SELECT 1 FROM catalog_research.unresolved_requirement WHERE offering_id = 'CSE-098')"),
        probe("no approval and no promotion was created", "NOT EXISTS (SELECT 1 FROM catalog_promoted.approval) AND NOT EXISTS (SELECT 1 FROM catalog_promoted.promotion)"),
    ])))
    # 2. Anything that disagrees with the evidence aborts the import.
    out.append(("an import that disagrees with the collected evidence or the verified snapshot is refused", "\n".join([
        reject_import("wrong lecture credits", mutated(GOOD, lambda p: find(p, "98001").update(credits=4))),
        reject_import("wrong meeting time", mutated(GOOD, lambda p: find(p, "98001")["meetings"][0].update(start="10:05"))),
        reject_import("wrong location", mutated(GOOD, lambda p: find(p, "97002")["meetings"][0].update(location="Somewhere Else 1"))),
        reject_import("wrong day", mutated(GOOD, lambda p: find(p, "98001")["meetings"][0].update(day=2))),
        reject_import("a meeting missing from the payload", mutated(GOOD, lambda p: find(p, "98001")["meetings"].pop())),
        reject_import("an evidence section missing from the payload", mutated(GOOD, lambda p: p["offerings"][0]["sections"].pop())),
        reject_import("a CRN the evidence has never seen", mutated(GOOD, lambda p: p["offerings"][0]["sections"].append(section("98009", "019", "LECTURE", 3, "Lecture", [m(5, "10:00", "10:50")])))),
        reject_import("a wrong section code", mutated(GOOD, lambda p: find(p, "98001").update(sectionCode="099"))),
        reject_import("a wrong Banner schedule type", mutated(GOOD, lambda p: find(p, "98001").update(scheduleType="Seminar"))),
        reject_import("a lab credit that is not the grid's zero is a different fact", mutated(GOOD, lambda p: find(p, "97002").update(credits=1))),
        reject_import("a snapshot lab credit that differs from the verified catalog", mutated(GOOD, lambda p: find(p, "12275").update(credits=3))),
        reject_import("a snapshot meeting that differs from the verified catalog", mutated(GOOD, lambda p: find(p, "12275")["meetings"][0].update(start="09:30"))),
        reject_import("a snapshot lab linked to nothing", mutated(GOOD, lambda p: find(p, "13132").update(lectureCrn=None))),
        probe("a refused import left nothing behind", "NOT EXISTS (SELECT 1 FROM catalog_research.offering)"),
        do_import(payload(two_lectures())),
        probe("the correct link pairs are accepted", "(SELECT count(*) FROM catalog_research.section WHERE offering_id = 'CSE-095') = 4"),
        reject_import("labs linked to the wrong lecture", mutated(payload(two_lectures()), lambda p: (find(p, "95003").update(lectureCrn="95002"), find(p, "95004").update(lectureCrn="95001")))),
        reject_import("a payload with no offerings", {"termCode": TERM, "offerings": []}),
        reject_import("a malformed term code", {"termCode": "2027-SPRING", "offerings": [lecture_only()]}),
    ])))
    # 3. The table rules refuse impossible data.
    base = do_import(GOOD)
    out.append(("table constraints refuse impossible rows", base + "\n" + "\n".join([
        reject("a lab with credits", "UPDATE catalog_research.section SET credits = 3 WHERE crn = '97002'"),
        reject("a lab with no lecture", "UPDATE catalog_research.section SET lecture_crn = NULL WHERE crn = '97002'"),
        reject("a lecture with a lecture link", "UPDATE catalog_research.section SET lecture_crn = '97001' WHERE crn = '98001'"),
        reject("a lab linked to a lab", "UPDATE catalog_research.section SET lecture_crn = '97003' WHERE crn = '97002'"),
        reject("a lab linked to another offering's lecture", "UPDATE catalog_research.section SET lecture_crn = '98001' WHERE crn = '97002'"),
        reject("a meeting on day 8", "INSERT INTO catalog_research.section_meeting VALUES ('202710', '98001', 8, '10:00', '11:00', 'x')"),
        reject("a meeting that ends before it starts", "INSERT INTO catalog_research.section_meeting VALUES ('202710', '98001', 5, '11:00', '10:00', 'x')"),
        reject("a meeting for a CRN that is not stored", "INSERT INTO catalog_research.section_meeting VALUES ('202710', '99999', 5, '10:00', '11:00', 'x')"),
        reject("an offering that claims user-supplied labs but no count", "UPDATE catalog_research.offering SET required_lab_count = NULL WHERE offering_id = 'CSE-097'"),
        reject("unverified components that already carry a count", "UPDATE catalog_research.offering SET required_lab_count = 1 WHERE offering_id = 'CSE-098'"),
        reject("a topic offering whose id is the plain course id", "UPDATE catalog_research.offering SET topic = 'Advanced' WHERE offering_id = 'CSE-098'"),
        reject("a plain offering that claims a single identity with a topic", "UPDATE catalog_research.offering SET identity_status = 'TOPIC_IDENTITY_UNDECIDED' WHERE offering_id = 'CSE-098'"),
        reject("negative credits", "UPDATE catalog_research.offering SET credits = -1 WHERE offering_id = 'CSE-098'"),
        reject("a class standing that does not exist", "UPDATE catalog_research.eligibility_rule SET rule_values = ARRAY['XX'] WHERE rule_kind = 'CLASS_STANDING'"),
        reject("a prerequisite that is not a REQUIRES rule", "UPDATE catalog_research.eligibility_rule SET polarity = 'MUST' WHERE rule_kind = 'PREREQUISITE'"),
        reject("a structured restriction with no values", "UPDATE catalog_research.eligibility_rule SET rule_values = '{}' WHERE rule_kind = 'CLASS_STANDING'"),
        reject("stated requirement text that is empty", "UPDATE catalog_research.requirement_status SET source_text = NULL WHERE status = 'TEXT_ONLY' OR status = 'RULE_DEFINED'"),
        reject("'unknown' dressed up with a Banner source", "UPDATE catalog_research.requirement_status SET status = 'UNKNOWN' WHERE offering_id = 'CSE-098'"),
        reject("a credit exclusion of an unknown kind", "UPDATE catalog_research.credit_exclusion SET exclusion_kind = 'MAYBE'"),
        reject("an alias status that claims verification", "UPDATE catalog_research.course_alias SET alias_status = 'VERIFIED'"),
        "UPDATE catalog_research.eligibility_rule SET expression = '{\"course\": \"CSE-099\"}' WHERE offering_id = 'CSE-097' AND rule_kind = 'PREREQUISITE';",
        probe("a structured prerequisite that names a course Banner's text does not is reported by the check view", "EXISTS (SELECT 1 FROM catalog_research.prerequisite_banner_check WHERE offering_id = 'CSE-097')"),
        reject("two sections with the same code in one offering", "UPDATE catalog_research.section SET section_code = '010' WHERE crn = '98002'"),
        reject("a CRN that is not five digits", "UPDATE catalog_research.section SET crn = '1234' WHERE crn = '98002'"),
    ])))
    # 4. Policy decisions.
    out.append(("policy decisions are one-writer, append-only, TEAM_POLICY, and never a certification", base + "\n" + "\n".join([
        reject("a decision for a section that is not stored", "SELECT catalog_research.record_tier_policy_decision('202710', '99999', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', 'ALLOW', 'a reviewer allowed it here', 'team policy only, not certification', DATE '2026-10-16', 'ref')"),
        reject("an ALLOW for an ordinary lecture", "SELECT catalog_research.record_tier_policy_decision('202710', '98001', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', 'ALLOW', 'a reviewer allowed it here', 'team policy only, not certification', DATE '2026-10-16', 'ref')"),
        reject("a future decision date", "SELECT catalog_research.record_tier_policy_decision('202710', '96001', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', 'ALLOW', 'a reviewer allowed it here', 'team policy only, not certification', CURRENT_DATE + 1, 'ref')"),
        reject("limitations that say nothing", "SELECT catalog_research.record_tier_policy_decision('202710', '96001', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', 'ALLOW', 'a reviewer allowed it here', 'short', DATE '2026-10-16', 'ref')"),
        reject("a decision type nobody defined", "SELECT catalog_research.record_tier_policy_decision('202710', '96001', 'WAIVE_RESTRICTION', 'ALLOW', 'a reviewer allowed it here', 'team policy only, not certification', DATE '2026-10-16', 'ref')"),
        probe("before any decision the independent-study offering is BLOCKED", "(SELECT tier FROM catalog_research.offering_tier WHERE offering_id = 'CSE-096') = 'BLOCKED'"),
        "SELECT set_config('t.d1', catalog_research.record_tier_policy_decision(" + DECISION.format(t=TERM, d="ALLOW").replace("DATE '2026-10-16'", "DATE '2026-10-16'") + ")::text, true);",
        probe("an ALLOW makes it CONDITIONAL and names the decision's CRN", "(SELECT tier || ':' || tier_reason FROM catalog_research.offering_tier WHERE offering_id = 'CSE-096') = 'CONDITIONAL:POLICY_DECISION_ALLOWS_SECTION_TYPE:96001'"),
        probe("the decision is TEAM_POLICY, by role, and certifies nothing", "(SELECT evidence_level || ':' || decided_by_role || ':' || registrar_certified || ':' || guarantees_registration FROM catalog_research.tier_policy_decision) = 'TEAM_POLICY:DEMO_ACADEMIC_EVIDENCE_REVIEWER:false:false'"),
        probe("the decision remembers the Banner schedule type it was about", "(SELECT observed_value FROM catalog_research.tier_policy_decision) = 'Independent Study'"),
        "SELECT set_config('t.d2', catalog_research.record_tier_policy_decision(" + DECISION.format(t=TERM, d="ALLOW") + ")::text, true);",
        probe("recording the identical current decision again changes nothing", "current_setting('t.d1') = current_setting('t.d2') AND (SELECT count(*) FROM catalog_research.tier_policy_decision) = 1"),
        reject("a decision that claims Registrar certification", "INSERT INTO catalog_research.tier_policy_decision (term_code, crn, offering_id, decision_type, decision, observed_value, evidence_level, decided_by_role, decided_on, rationale, limitations, source_ref, registrar_certified, guarantees_registration) VALUES ('202710', '96001', 'CSE-096', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', 'ALLOW', 'Independent Study', 'TEAM_POLICY', 'DEMO_ACADEMIC_EVIDENCE_REVIEWER', DATE '2026-10-16', 'a reviewer allowed it', 'team policy, no certification', 'ref', true, false)"),
        reject("a decision that guarantees registration", "INSERT INTO catalog_research.tier_policy_decision (term_code, crn, offering_id, decision_type, decision, observed_value, evidence_level, decided_by_role, decided_on, rationale, limitations, source_ref, registrar_certified, guarantees_registration) VALUES ('202710', '96001', 'CSE-096', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', 'ALLOW', 'Independent Study', 'TEAM_POLICY', 'DEMO_ACADEMIC_EVIDENCE_REVIEWER', DATE '2026-10-16', 'a reviewer allowed it', 'team policy, no certification', 'ref', false, true)"),
        reject("a decision with an evidence level other than TEAM_POLICY", "INSERT INTO catalog_research.tier_policy_decision (term_code, crn, offering_id, decision_type, decision, observed_value, evidence_level, decided_by_role, decided_on, rationale, limitations, source_ref, registrar_certified, guarantees_registration) VALUES ('202710', '96001', 'CSE-096', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', 'ALLOW', 'Independent Study', 'BANNER', 'DEMO_ACADEMIC_EVIDENCE_REVIEWER', DATE '2026-10-16', 'a reviewer allowed it', 'team policy, no certification', 'ref', false, false)"),
        reject("a decision by an unrecognized role", "INSERT INTO catalog_research.tier_policy_decision (term_code, crn, offering_id, decision_type, decision, observed_value, evidence_level, decided_by_role, decided_on, rationale, limitations, source_ref, registrar_certified, guarantees_registration) VALUES ('202710', '96001', 'CSE-096', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', 'ALLOW', 'Independent Study', 'TEAM_POLICY', 'ANYONE', DATE '2026-10-16', 'a reviewer allowed it', 'team policy, no certification', 'ref', false, false)"),
        reject("updating a decision", "UPDATE catalog_research.tier_policy_decision SET rationale = 'rewritten history here'"),
        reject("deleting a decision", "DELETE FROM catalog_research.tier_policy_decision"),
        reject("truncating the decisions", "TRUNCATE catalog_research.tier_policy_decision"),
        reject("changing an import record", "UPDATE catalog_research.import_run SET input_label = 'rewritten label'"),
        "SELECT catalog_research.record_tier_policy_decision(" + DECISION.format(t=TERM, d="REVOKE") + ");",
        reject("a second decision superseding the same one (a fork)", "INSERT INTO catalog_research.tier_policy_decision (term_code, crn, offering_id, decision_type, decision, observed_value, evidence_level, decided_by_role, decided_on, rationale, limitations, source_ref, registrar_certified, guarantees_registration, supersedes_decision_id) "
               "SELECT term_code, crn, offering_id, decision_type, 'REVOKE', observed_value, evidence_level, decided_by_role, decided_on, rationale, limitations, source_ref, false, false, current_setting('t.d1')::bigint FROM catalog_research.tier_policy_decision LIMIT 1"),
        probe("a REVOKE supersedes the ALLOW (two rows, one chain) and the offering is BLOCKED again", "(SELECT count(*) FROM catalog_research.tier_policy_decision) = 2 AND (SELECT tier FROM catalog_research.offering_tier WHERE offering_id = 'CSE-096') = 'BLOCKED' AND (SELECT count(*) FROM catalog_research.active_tier_policy_decision) = 1"),
        "SELECT catalog_research.record_tier_policy_decision(" + DECISION.format(t=TERM, d="ALLOW") + ");",
        probe("a later ALLOW supersedes the REVOKE", "(SELECT tier FROM catalog_research.offering_tier WHERE offering_id = 'CSE-096') = 'CONDITIONAL' AND (SELECT count(*) FROM catalog_research.tier_policy_decision) = 3"),
        "UPDATE catalog_research.section SET schedule_type = 'Seminar' WHERE crn = '96001';",
        probe("when Banner's schedule type changes the decision goes stale and stops applying", "NOT (SELECT is_fresh FROM catalog_research.active_tier_policy_decision) AND NOT (SELECT is_active FROM catalog_research.active_tier_policy_decision)"),
        "UPDATE catalog_research.section SET schedule_type = 'Independent Study' WHERE crn = '96001';",
        do_import(GOOD, "second import"),
        probe("re-importing keeps the decision and the offering stays CONDITIONAL", "(SELECT count(*) FROM catalog_research.tier_policy_decision) = 3 AND (SELECT tier FROM catalog_research.offering_tier WHERE offering_id = 'CSE-096') = 'CONDITIONAL'"),
        probe("re-importing replaces rather than duplicates, and records both runs", "(SELECT count(*) FROM catalog_research.offering) = 4 AND (SELECT count(*) FROM catalog_research.section) = 9 AND (SELECT count(*) FROM catalog_research.import_run) = 2"),
    ])))
    # 5. Special topics keep separate identities.
    out.append(("two special topics under one course number stay two offerings and are BLOCKED until the identity scheme is decided", "\n".join([
        "SELECT 1;",
        do_import(payload(*topics())),
        probe("two offerings share course CSE-094 and have their own CRN, title and identity", "(SELECT string_agg(offering_id || '/' || course_id || '/' || title, ',' ORDER BY offering_id) FROM catalog_research.offering) = 'CSE-094-010/CSE-094/Topic One,CSE-094-011/CSE-094/Topic Two'"),
        probe("both are BLOCKED for the undecided identity scheme", "(SELECT string_agg(tier || ':' || tier_reason, ',') FROM catalog_research.offering_tier) = 'BLOCKED:TOPIC_IDENTITY_UNDECIDED,BLOCKED:TOPIC_IDENTITY_UNDECIDED'"),
        probe("Banner's restriction text of one topic is not attributed to the other", "(SELECT status FROM catalog_research.requirement_status WHERE offering_id = 'CSE-094-010' AND requirement_type = 'ENROLLMENT_RESTRICTION') = 'NONE_LISTED' AND (SELECT status || ':' || (source_text LIKE '%Graduate (GR)%') FROM catalog_research.requirement_status WHERE offering_id = 'CSE-094-011' AND requirement_type = 'ENROLLMENT_RESTRICTION') = 'RULE_DEFINED:true'"),
        reject("a topic offering that reuses the other topic's CRN", "INSERT INTO catalog_research.section VALUES ('202710', '94002', 'CSE-094-010', '012', 'LECTURE', 3, NULL, 'Lecture')"),
    ])))
    # 6. Privileges and isolation.
    out.append(("a read-only role can read but not write; nothing outside catalog_research is touched", base + "\n" + "\n".join([
        "SET LOCAL ROLE research_test_reader;",
        probe("the reader can read the status view", "(SELECT count(*) FROM catalog_research.offering_status) = 4"),
        reject("the reader cannot insert", "INSERT INTO catalog_research.offering SELECT * FROM catalog_research.offering LIMIT 1"),
        reject("the reader cannot update", "UPDATE catalog_research.section SET credits = 9"),
        reject("the reader cannot delete", "DELETE FROM catalog_research.section"),
        reject("the reader cannot run the importer", "SELECT catalog_research.import_term('{}'::jsonb, 'x', repeat('a', 64))"),
        reject("the reader cannot record a decision", "SELECT catalog_research.record_tier_policy_decision('202710', '96001', 'ALLOW_SECTION_TYPE_AS_CONDITIONAL', 'ALLOW', 'a reviewer allowed it here', 'team policy only, not certification', DATE '2026-10-16', 'ref')"),
        "RESET ROLE;",
        probe("the import wrote no approval, promotion or evidence row", "NOT EXISTS (SELECT 1 FROM catalog_promoted.approval) AND NOT EXISTS (SELECT 1 FROM catalog_promoted.promotion)"),
        probe("catalog_live serves exactly what it served before (the snapshot course only)", "(SELECT string_agg(course_id, ',') FROM catalog_live.course_offering) = 'CSE-007'"),
    ])))
    # 7. Grid-staged (non-CSE) data: its own validated import path, kept apart from the research-sourced data.
    both = do_import(GOOD) + "\n" + do_import_noncse(GRID_PAYLOAD) + "\n" + do_import_programs(PROGRAM_PAYLOAD)
    n_off, n_sec = len(GRID_PAYLOAD["offerings"]), sum(len(o["sections"]) for o in GRID_PAYLOAD["offerings"])
    out.append(("grid-staged data is imported through its own cross-checked path, stays BLOCKED, and never reaches a scheduler", both + "\n" + "\n".join([
        probe("the grid-staged offerings and sections are stored next to the research-sourced ones", f"(SELECT count(*) FROM catalog_research.offering) = {n_off} + 4 AND (SELECT count(*) FROM catalog_research.section) = {n_sec} + 9"),
        probe("every CRN is unique across both datasets", "(SELECT count(DISTINCT (term_code, crn)) FROM catalog_research.section) = (SELECT count(*) FROM catalog_research.section)"),
        probe("the stored rows agree with the verbatim grid rows and, separately, with the Banner evidence", "NOT EXISTS (SELECT 1 FROM catalog_research.grid_mismatch) AND NOT EXISTS (SELECT 1 FROM catalog_research.evidence_mismatch)"),
        probe("every grid-staged offering is BLOCKED because its details were never inspected", "(SELECT string_agg(DISTINCT tier || ':' || tier_reason, ',') FROM catalog_research.offering_tier t JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source = 'BANNER_GRID_STAGING') = 'BLOCKED:GRID_ONLY_DETAILS_NOT_INSPECTED'"),
        probe("the research-sourced tiers are unchanged by the grid import", "(SELECT string_agg(offering_id || ':' || tier, ',' ORDER BY offering_id) FROM catalog_research.offering_tier t JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source <> 'BANNER_GRID_STAGING') = 'CSE-007:READY,CSE-096:BLOCKED,CSE-097:CONDITIONAL,CSE-098:CONDITIONAL'"),
        probe("none of it is in the candidate views, so none of it can be promoted by accident", "NOT EXISTS (SELECT 1 FROM catalog_research.candidate_section_offering WHERE crn LIKE '99%') AND NOT EXISTS (SELECT 1 FROM catalog_research.candidate_course_offering WHERE course_id IN ('MATH-021', 'CHM-030', 'PHY-012')) AND NOT EXISTS (SELECT 1 FROM catalog_research.candidate_section_meeting WHERE crn LIKE '99%')"),
        probe("none of it is served or schedulable, and eligibility is never guaranteed", "EXISTS (SELECT 1 FROM catalog_research.offering_status WHERE promotion_state = 'GRID_STAGING_ONLY') AND NOT EXISTS (SELECT 1 FROM catalog_research.offering_status WHERE promotion_state = 'GRID_STAGING_ONLY' AND (serving_status <> 'STORED_NOT_SERVED' OR registration_eligibility_guaranteed))"),
        probe("catalog_live still serves only the verified snapshot course", "(SELECT string_agg(course_id, ',') FROM catalog_live.course_offering) = 'CSE-007'"),
        probe("weekly meetings are expanded per day from the grid's day letters (MWF = 3 rows, TR = 2)", "(SELECT count(*) FROM catalog_research.section_meeting WHERE crn = '99020' AND meeting_kind = 'WEEKLY') = 3 AND (SELECT string_agg(day_of_week::text, ',' ORDER BY day_of_week) FROM catalog_research.section_meeting WHERE crn = '99001') = '2,4'"),
        probe("the dated exams stay dated: two one-time rows on their own Thursdays, in exam_meeting, never weekly", "(SELECT string_agg(meeting_date::text, ',' ORDER BY meeting_date) FROM catalog_research.exam_meeting) = '2027-02-25,2027-04-08' AND (SELECT count(*) FROM catalog_research.section_meeting WHERE crn = '99010' AND meeting_kind = 'WEEKLY') = 2 AND (SELECT count(*) FROM catalog_research.section_meeting WHERE meeting_kind = 'DATED_ONE_TIME' AND day_of_week = 4) = 2"),
        probe("recurrence dates and Banner meeting types are preserved", "(SELECT start_date || '/' || end_date || '/' || meeting_type FROM catalog_research.section_meeting WHERE crn = '99001' AND day_of_week = 2) = '2027-01-25/2027-05-07/Class'"),
        probe("a location of 'None' becomes TBA and a named place is kept", "(SELECT location FROM catalog_research.section_meeting WHERE crn = '99001' LIMIT 1) = 'TBA' AND (SELECT location FROM catalog_research.section_meeting WHERE crn = '99021') = 'Synthetic Lab 180' AND (SELECT location FROM catalog_research.exam_meeting LIMIT 1) = 'To Be Determined'"),
        probe("a lab listed by its lecture is LISTED_BY_LECTURE_ONLY; a recitation or lab nobody paired is NOT_COLLECTED with no lecture", "(SELECT string_agg(crn || ':' || link_status || ':' || COALESCE(lecture_crn, '-'), ',' ORDER BY crn) FROM catalog_research.section WHERE crn IN ('99002', '99021', '99022', '99030')) = '99002:NOT_COLLECTED:-,99021:LISTED_BY_LECTURE_ONLY:99020,99022:LISTED_BY_LECTURE_ONLY:99020,99030:NOT_COLLECTED:-'"),
        probe("Banner's 'NONE_SHOWN' link flag is kept as a flag, not read as standalone", "(SELECT banner_link_flag FROM catalog_research.section WHERE crn = '99030') = 'NONE_SHOWN' AND (SELECT components_status FROM catalog_research.offering WHERE offering_id = 'PHY-012') = 'COMPONENTS_UNVERIFIED'"),
        probe("recitations are stored as RECITATION, not forced into lecture or lab", "(SELECT component_type FROM catalog_research.section WHERE crn = '99002') = 'RECITATION'"),
        probe("grid credits are kept as shown (labs 0 or 1) with their source, nothing summed or inferred", "(SELECT string_agg(crn || ':' || credits || ':' || credit_source, ',' ORDER BY crn) FROM catalog_research.section WHERE crn IN ('99001', '99002', '99030')) = '99001:4:GRID_DISPLAY,99002:0:GRID_DISPLAY,99030:1:GRID_DISPLAY'"),
        probe("an offering whose lecture sections differ in credits has no single credit value", "(SELECT credits IS NULL AND credits_status = 'VARIES_BY_SECTION' FROM catalog_research.offering WHERE offering_id = 'MATH-312') AND (SELECT string_agg(credits::text, ',' ORDER BY crn) FROM catalog_research.section WHERE offering_id = 'MATH-312') = '4,3'"),
        probe("a lab-only course is an offering without a lecture", "(SELECT count(*) FROM catalog_research.section WHERE offering_id = 'PHY-012' AND component_type <> 'LAB') = 0 AND (SELECT credits FROM catalog_research.offering WHERE offering_id = 'PHY-012') = 1"),
        probe("nothing about requirements was collected: every kind is UNKNOWN, never 'none'", "(SELECT string_agg(DISTINCT status || ':' || evidence_level, ',') FROM catalog_research.requirement_status WHERE offering_id IN (SELECT offering_id FROM catalog_research.offering WHERE data_source = 'BANNER_GRID_STAGING')) = 'UNKNOWN:NONE' AND (SELECT count(*) FROM catalog_research.unresolved_requirement WHERE offering_id = 'MATH-021') = 3"),
        probe("a team-asserted cross-list is an unverified alias on both courses", "(SELECT string_agg(offering_id || '>' || alias_subject || alias_number || ':' || alias_status, ',' ORDER BY offering_id) FROM catalog_research.course_alias WHERE offering_id IN ('ECO-325', 'MKT-325')) = 'ECO-325>MKT325:CROSS_LIST_USER_ASSERTED_UNVERIFIED,MKT-325>ECO325:CROSS_LIST_USER_ASSERTED_UNVERIFIED' AND (SELECT cross_listing_status FROM catalog_research.offering WHERE offering_id = 'ECO-325') = 'CROSS_LISTED_USER_ASSERTED_UNVERIFIED'"),
        probe("the verbatim grid rows and their provenance are kept", f"(SELECT count(*) FROM catalog_research.grid_observation) = {n_sec} AND (SELECT days FROM catalog_research.grid_observation WHERE crn = '99001') = 'TR' AND (SELECT count(*) FROM catalog_research.grid_extra_meeting_observation) = 2 AND (SELECT origin || '/' || redistribution_status || '/' || verification FROM catalog_research.grid_source) = 'synthetic_fixture/SYNTHETIC/GRID_ONLY_DETAILS_NOT_INSPECTED'"),
        probe("no instructor column or name exists anywhere in the staged data", "NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'catalog_research' AND (column_name ILIKE '%instructor%'))"),
        reject_noncse("a grid CRN that is already a research-sourced section", gm(lambda p: (gsec(p, "99001").update(crn="98001"), graw(p, "99001").update(crn="98001")))),
        do_import(GOOD, "again"),
        probe("after re-importing the research data the grid-staged sections and exams are all still there", f"(SELECT count(*) FROM catalog_research.section WHERE offering_id IN (SELECT offering_id FROM catalog_research.offering WHERE data_source = 'BANNER_GRID_STAGING')) = {n_sec} AND (SELECT count(*) FROM catalog_research.exam_meeting) = 2 AND NOT EXISTS (SELECT 1 FROM catalog_research.grid_mismatch)"),
        do_import_noncse(GRID_PAYLOAD, "again"),
        probe("re-importing the grid data replaces rather than duplicates, and the research data and decisions survive", f"(SELECT count(*) FROM catalog_research.offering) = {n_off} + 4 AND (SELECT count(*) FROM catalog_research.section) = {n_sec} + 9 AND (SELECT count(*) FROM catalog_research.grid_observation) = {n_sec} AND (SELECT count(*) FROM catalog_research.import_run WHERE kind = 'GRID_STAGING') = 2 AND NOT EXISTS (SELECT 1 FROM catalog_research.grid_mismatch)"),
    ])))
    # 8. Anything that disagrees with the verbatim grid rows aborts the grid import.
    out.append(("a grid import that disagrees with the verbatim grid rows or breaks the rules is refused", "\n".join([
        reject_noncse("a wrong credit value", gm(lambda p: gsec(p, "99001").update(credits=3))),
        reject_noncse("a wrong day", gm(lambda p: gsec(p, "99001")["meetings"][0].update(day=3))),
        reject_noncse("a wrong start time", gm(lambda p: gsec(p, "99001")["meetings"][0].update(start="09:25"))),
        reject_noncse("a wrong location", gm(lambda p: gsec(p, "99021")["meetings"][0].update(location="Elsewhere 9"))),
        reject_noncse("wrong recurrence dates", gm(lambda p: gsec(p, "99001")["meetings"][0].update(startDate="2027-01-26"))),
        reject_noncse("a wrong Banner meeting type", gm(lambda p: gsec(p, "99021")["meetings"][0].update(type="Class"))),
        reject_noncse("a missing weekly meeting", gm(lambda p: gsec(p, "99020")["meetings"].pop())),
        reject_noncse("a missing exam", gm(lambda p: gsec(p, "99010")["meetings"].pop())),
        reject_noncse("an extra exam the grid does not have", gm(lambda p: gsec(p, "99001")["meetings"].append({"type": "Common Hour Exam", "kind": "DATED_ONE_TIME", "day": 4, "start": "16:25", "end": "17:40", "startDate": "2027-03-04", "endDate": "2027-03-04", "location": "TBA"}))),
        reject_noncse("an exam on the wrong date", gm(lambda p: gsec(p, "99010")["meetings"][-1].update(startDate="2027-04-15", endDate="2027-04-15"))),
        reject_noncse("an extra meeting the grid does not have", gm(lambda p: gsec(p, "99002")["meetings"].append({"type": "Recitation", "kind": "WEEKLY", "day": 5, "start": "12:10", "end": "13:00", "startDate": "2027-01-25", "endDate": "2027-05-07", "location": "TBA"}))),
        reject_noncse("a grid row with no stored section", gm(lambda p: goff(p, "ECO-325")["sections"].pop())),
        reject_noncse("a stored section with no grid row", gm(lambda p: goff(p, "ECO-325")["sections"].append(copy.deepcopy(gsec(p, "99051")) | {"crn": "99999", "sectionCode": "099"}))),
        reject_noncse("a wrong component", gm(lambda p: gsec(p, "99002").update(component="LAB"))),
        reject_noncse("a wrong link flag", gm(lambda p: gsec(p, "99030").update(bannerLinkFlag="LINKED"))),
        reject_noncse("a wrong title", gm(lambda p: goff(p, "MATH-022").update(title="Something Else"))),
        reject_noncse("an offering credit that hides a difference between sections", gm(lambda p: goff(p, "MATH-312").update(credits=4, creditsStatus="SINGLE_VALUE"))),
        reject_noncse("an offering credit that does not match its lecture sections", gm(lambda p: goff(p, "MATH-021").update(credits=3))),
        reject_noncse("a raw grid row that says something else", gm(lambda p: graw(p, "99001").update(hours_grid="5"))),
        reject_noncse("a raw day string that says something else", gm(lambda p: graw(p, "99001").update(days="MW"))),
        reject_noncse("a lab pointing at a lecture of another offering", gm(lambda p: gsec(p, "99021").update(lectureCrn="99001"))),
        reject_noncse("a lab marked NOT_COLLECTED that still names a lecture", gm(lambda p: gsec(p, "99021").update(linkStatus="NOT_COLLECTED"))),
        reject_noncse("a grid row from another term", gm(lambda p: graw(p, "99001").update(term="202720"))),
        reject_noncse("an offering that is not grid-staged", gm(lambda p: goff(p, "MATH-022").update(dataSource="BANNER_EVIDENCE"))),
        reject_noncse("a payload with no offerings", gm(lambda p: p.update(offerings=[]))),
        reject_noncse("a malformed term code", gm(lambda p: p.update(termCode="2027-SPRING"))),
        reject_noncse("an unknown source origin", gm(lambda p: p["source"].update(origin="somewhere"))),
        reject_noncse("a real Banner source claiming it can be redistributed", gm(lambda p: p["source"].update(origin="authenticated_banner", redistribution="SYNTHETIC"))),
        probe("every refused grid import left nothing behind", "NOT EXISTS (SELECT 1 FROM catalog_research.offering) AND NOT EXISTS (SELECT 1 FROM catalog_research.grid_observation) AND NOT EXISTS (SELECT 1 FROM catalog_research.grid_source)"),
    ])))
    # 9. Table rules for the new structures.
    out.append(("table rules refuse impossible grid, link, credit and dated-meeting rows", both + "\n" + "\n".join([
        reject("a recitation linked to a lab of another offering", "UPDATE catalog_research.section SET lecture_crn = '99021', link_status = 'CONFIRMED' WHERE crn = '99002'"),
        reject("a lecture that claims a confirmed link", "UPDATE catalog_research.section SET link_status = 'CONFIRMED' WHERE crn = '99020'"),
        reject("a research-sourced lab with credits (only grid-displayed credits may differ)", "UPDATE catalog_research.section SET credits = 1 WHERE crn = '97002'"),
        reject("a lab claiming to be a research credit while carrying the grid's 1", "UPDATE catalog_research.section SET credit_source = 'RESEARCH' WHERE crn = '99030'"),
        reject("a dated meeting spanning two dates", "INSERT INTO catalog_research.section_meeting (term_code, crn, day_of_week, start_time, end_time, location, meeting_type, meeting_kind, start_date, end_date) VALUES ('202710', '99001', 4, '18:00', '19:00', 'x', 'Common Hour Exam', 'DATED_ONE_TIME', '2027-03-04', '2027-03-05')"),
        reject("a dated meeting whose weekday is not its date's weekday", "INSERT INTO catalog_research.section_meeting (term_code, crn, day_of_week, start_time, end_time, location, meeting_type, meeting_kind, start_date, end_date) VALUES ('202710', '99001', 1, '18:00', '19:00', 'x', 'Common Hour Exam', 'DATED_ONE_TIME', '2027-03-04', '2027-03-04')"),
        reject("a dated meeting with no date", "INSERT INTO catalog_research.section_meeting (term_code, crn, day_of_week, start_time, end_time, location, meeting_type, meeting_kind) VALUES ('202710', '99001', 4, '18:00', '19:00', 'x', 'Common Hour Exam', 'DATED_ONE_TIME')"),
        reject("a weekly meeting that ends its recurrence before it starts", "UPDATE catalog_research.section_meeting SET start_date = '2027-06-01' WHERE crn = '99001'"),
        reject("a weekly meeting with only one recurrence date", "UPDATE catalog_research.section_meeting SET end_date = NULL WHERE crn = '99001'"),
        reject("the same dated exam twice", "INSERT INTO catalog_research.section_meeting (term_code, crn, day_of_week, start_time, end_time, location, meeting_type, meeting_kind, start_date, end_date) SELECT term_code, crn, day_of_week, start_time, end_time, location, meeting_type, meeting_kind, start_date, end_date FROM catalog_research.section_meeting WHERE meeting_kind = 'DATED_ONE_TIME' LIMIT 1"),
        reject("a single credit value hiding NULL", "UPDATE catalog_research.offering SET credits = NULL WHERE offering_id = 'MATH-021'"),
        reject("a credit value on an offering that varies", "UPDATE catalog_research.offering SET credits = 3 WHERE offering_id = 'MATH-312'"),
        reject("a data source nobody defined", "UPDATE catalog_research.offering SET data_source = 'SOMEWHERE' WHERE offering_id = 'MATH-021'"),
        reject("a grid row with a malformed CRN", "UPDATE catalog_research.grid_observation SET crn = '12' WHERE crn = '99001'"),
        reject("a real Banner source marked as redistributable", "UPDATE catalog_research.grid_source SET origin = 'authenticated_banner'"),
        reject("a requirement group choosing N without N", "UPDATE catalog_research.requirement_group SET n = NULL WHERE logic = 'CHOOSE_N'"),
        reject("a requirement group with N but not CHOOSE_N", "UPDATE catalog_research.requirement_group SET n = 2 WHERE logic = 'ALL'"),
        reject("an option naming neither a course nor a rule", "UPDATE catalog_research.requirement_course_option SET course_id = NULL, rule_text = NULL WHERE option_id = (SELECT min(option_id) FROM catalog_research.requirement_course_option WHERE course_id IS NOT NULL)"),
        reject("an option naming a course the catalog does not know", "UPDATE catalog_research.requirement_course_option SET course_id = 'ZZZ-999' WHERE option_id = (SELECT min(option_id) FROM catalog_research.requirement_course_option WHERE course_id IS NOT NULL)"),
        reject("a catalog course claiming its prerequisites were collected", "UPDATE catalog_research.catalog_course SET prerequisite_status = 'COLLECTED'"),
        reject("an unverified cross-list upgraded to verified", "UPDATE catalog_research.catalog_course_equivalence SET status = 'VERIFIED'"),
        reject("a program from a non-catalog source", "UPDATE catalog_research.program SET source_level = 'RANDOM_WEBSITE'"),
    ])))
    # 10. Programs.
    n_prog = len(PROGRAM_PAYLOAD["programs"])
    out.append(("degree programs keep their AND/OR/choose-N structure, their course links and their unknowns", both + "\n" + "\n".join([
        probe("every program, requirement group and option is stored", f"(SELECT count(*) FROM catalog_research.program) = {n_prog} AND (SELECT count(*) FROM catalog_research.requirement_group) = {count_groups(PROGRAM_PAYLOAD)} AND (SELECT count(*) FROM catalog_research.requirement_course_option) = {count_options(PROGRAM_PAYLOAD)}"),
        probe("the groups form a consistent tree, with ALL, ANY, CHOOSE_N and CREDITS logic preserved", "NOT EXISTS (SELECT 1 FROM catalog_research.program_structure_problem) AND (SELECT count(DISTINCT logic) FROM catalog_research.requirement_group) = 4 AND EXISTS (SELECT 1 FROM catalog_research.requirement_group WHERE parent_group_id IS NOT NULL)"),
        probe("every requirement option that names a course points at a catalog course (foreign key)", "NOT EXISTS (SELECT 1 FROM catalog_research.requirement_course_option o WHERE o.course_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM catalog_research.catalog_course c WHERE c.course_id = o.course_id))"),
        probe("a program's top-level groups carry their category", "NOT EXISTS (SELECT 1 FROM catalog_research.requirement_group g WHERE g.parent_group_id IS NULL AND NOT EXISTS (SELECT 1 FROM catalog_research.program_requirement pr WHERE pr.group_id = g.group_id))"),
        probe("conflicts and unknowns the sources left open are preserved, not resolved", "EXISTS (SELECT 1 FROM catalog_research.program WHERE jsonb_array_length(conflicts) > 0) AND EXISTS (SELECT 1 FROM catalog_research.program WHERE jsonb_array_length(unknowns) > 0)"),
        probe("catalog courses say their prerequisites were NOT_COLLECTED, never 'none'", "(SELECT string_agg(DISTINCT prerequisite_status, ',') FROM catalog_research.catalog_course) = 'NOT_COLLECTED'"),
        probe("credit exclusions and the cross-list assertion are kept with their status", "(SELECT count(*) FROM catalog_research.catalog_course_exclusion) = 4 AND (SELECT count(*) FROM catalog_research.catalog_course_equivalence) = 2 AND (SELECT string_agg(DISTINCT status, ',') FROM catalog_research.catalog_course_equivalence) = 'CROSS_LISTED_USER_ASSERTED_UNVERIFIED'"),
        probe("a program course's term status comes from what was searched: offered, searched with no result, or not collected", "(SELECT term_status FROM catalog_research.course_term_status WHERE course_id = 'MATH-021') = 'OFFERED' AND (SELECT term_status FROM catalog_research.course_term_status WHERE course_id = 'ENGR-005') = 'SEARCHED_NOT_OFFERED' AND (SELECT term_status FROM catalog_research.course_term_status WHERE course_id = 'MATH-241') = 'NOT_COLLECTED'"),
        probe("course-to-degree links show each course's tier without making it schedulable", "EXISTS (SELECT 1 FROM catalog_research.program_course_link WHERE course_id = 'MATH-021' AND offerings_and_tiers = 'MATH-021:BLOCKED')"),
        probe("open items are carried forward, and the staged data names no instructor", "EXISTS (SELECT 1 FROM catalog_research.unresolved_item WHERE subject = 'INSTRUCTORS' AND detail LIKE 'NOT_IMPORTED%')"),
        do_import_programs(PROGRAM_PAYLOAD, "programs again"),
        probe("re-importing the programs replaces rather than duplicates", f"(SELECT count(*) FROM catalog_research.program) = {n_prog} AND (SELECT count(*) FROM catalog_research.requirement_group) = {count_groups(PROGRAM_PAYLOAD)} AND (SELECT count(*) FROM catalog_research.requirement_course_option) = {count_options(PROGRAM_PAYLOAD)} AND (SELECT count(*) FROM catalog_research.import_run WHERE kind = 'PROGRAMS') = 2"),
        reject_programs("a CHOOSE_N that asks for more than it offers", pm(lambda p: first_group(p).update(logic="CHOOSE_N", n=99))),
        reject_programs("a group with a repeated key in one program", pm(lambda p: p["programs"][0]["requirements"].append(copy.deepcopy(p["programs"][0]["requirements"][0])))),
        reject_programs("a degree type nobody defined", pm(lambda p: p["programs"][0].update(degreeType="PHD"))),
        reject_programs("an option naming a course the payload does not define", pm(lambda p: first_group(p)["options"].append({"option_set": 99, "course_code": "ZZZ 999", "rule_text": None, "credits": None, "status": "STATED"}))),
        reject_programs("a program with no requirements", pm(lambda p: p["programs"][0].update(requirements=[]))),
        reject_programs("a payload with no programs", pm(lambda p: p.update(programs=[]))),
    ])))
    # 11. Privileges for the new objects.
    out.append(("the read-only role can read the grid and program data but cannot write or import", both + "\n" + "\n".join([
        "SET LOCAL ROLE research_test_reader;",
        probe("the reader can read grid observations, programs and exams", "(SELECT count(*) FROM catalog_research.grid_observation) > 0 AND (SELECT count(*) FROM catalog_research.program) > 0 AND (SELECT count(*) FROM catalog_research.exam_meeting) = 2"),
        reject("the reader cannot change a grid row", "UPDATE catalog_research.grid_observation SET days = 'MWF'"),
        reject("the reader cannot delete a program", "DELETE FROM catalog_research.program"),
        reject("the reader cannot run the grid importer", "SELECT catalog_research.import_noncse_term('{}'::jsonb, 'x', repeat('a', 64))"),
        reject("the reader cannot run the program importer", "SELECT catalog_research.import_degree_programs('{}'::jsonb, 'x', repeat('a', 64))"),
        "RESET ROLE;",
    ])))
    return out


def run_scenario(body, install_first=None):
    parts = [PREAMBLE, "BEGIN;", "SELECT set_config('t.passed', '0', true);"]
    # synthetic Banner evidence for the scenario, committed to the rolled-back transaction only
    parts.append(EVIDENCE)
    if install_first is not None:
        parts += ["DROP SCHEMA IF EXISTS catalog_research CASCADE;", install_first]
    parts.append(body)
    parts += ["SELECT current_setting('t.passed')::int AS passed \\gset", "\\echo SCENARIO_PROBES :passed", "ROLLBACK;"]
    return "\n".join(parts) + "\n"


def expected_probes(body):
    return body.count("SELECT pg_temp.expect_true(") + body.count("SELECT pg_temp.expect_rejected(")


def execute(sql):
    code, out = psql(["-f", "-"], stdin=sql)
    found = re.search(r"SCENARIO_PROBES (\d+)", out)
    return code, out, int(found.group(1)) if found else None


def mutate(text, old, new, label):
    if text.count(old) != 1:
        sys.exit(f"mutation '{label}' does not apply: expected exactly one occurrence, found {text.count(old)}")
    return text.replace(old, new, 1)


MUTATIONS = [
    ("import no longer checks the evidence", "V2", lambda t: mutate(t, "	IF v_bad IS NOT NULL THEN\n		RAISE EXCEPTION 'imported data disagrees", "	IF false THEN\n		RAISE EXCEPTION 'imported data disagrees", "importcheck")),
    ("grid credits are not compared", "V2", lambda t: mutate(t, "OR s.credits::numeric <> e.grid_hours::numeric)", "OR false)", "gridcredits")),
    ("a lab may carry credits", "V1", lambda t: mutate(t, "CHECK (component_type = 'LECTURE' OR credits = 0 OR credit_source = 'GRID_DISPLAY')", "CHECK (true)", "labcredits")),
    ("a lab may point at anything", "V1", lambda t: mutate(t, "FOREIGN KEY (term_code, offering_id, lecture_crn, lecture_component)\n		REFERENCES catalog_research.section (term_code, offering_id, crn, component_type),", "", "labfk")),
    ("a decision may claim Registrar certification", "V1", lambda t: mutate(t, "CHECK (NOT registrar_certified)", "CHECK (true)", "certified")),
    ("a decision may guarantee registration", "V1", lambda t: mutate(t, "CHECK (NOT guarantees_registration)", "CHECK (true)", "guarantee")),
    ("a decision may carry any evidence level", "V1", lambda t: mutate(t, "CHECK (evidence_level = 'TEAM_POLICY')", "CHECK (true)", "level")),
    ("decisions may be rewritten", "V1", lambda t: mutate(t, "	RAISE EXCEPTION 'catalog_research.% is append-only: % is not allowed (record a new row instead)', TG_TABLE_NAME, TG_OP\n		USING ERRCODE = 'integrity_constraint_violation';", "	RETURN NULL;", "appendonly")),
    ("decisions may fork", "V1", lambda t: mutate(t, "CREATE UNIQUE INDEX IF NOT EXISTS tier_policy_decision_chain_idx", "CREATE INDEX IF NOT EXISTS tier_policy_decision_chain_idx", "fork")),
    ("unsupported section types are not blocked", "V2", lambda t: mutate(t, "		WHEN u.crns IS NOT NULL THEN 'BLOCKED'", "		WHEN false THEN 'BLOCKED'", "unsupported")),
    ("special topics are not blocked", "V2", lambda t: mutate(t, "		WHEN o.identity_status <> 'SINGLE' THEN 'BLOCKED'", "		WHEN false THEN 'BLOCKED'", "topics")),
    ("a stale decision still applies", "V2", lambda t: mutate(t, "(d.decision = 'ALLOW' AND s.crn IS NOT NULL AND s.schedule_type = d.observed_value) AS is_active", "(d.decision = 'ALLOW') AS is_active", "stale")),
    ("an ordinary lecture may be 'allowed'", "V2", lambda t: mutate(t, "IF p_type = 'ALLOW_SECTION_TYPE_AS_CONDITIONAL' AND sec.schedule_type IN ('Lecture', 'Lab') THEN", "IF false THEN", "allowlecture")),
    ("an identical decision is recorded twice", "V2", lambda t: mutate(t, "		RETURN prev.decision_id;      -- the identical current decision: nothing to record", "		NULL;", "idempotent")),
    ("an unlisted requirement is stored as unknown", "V2", lambda t: mutate(t, "WHEN o.data_source = 'BANNER_EVIDENCE' AND be.n > 0 THEN 'NONE_LISTED'", "WHEN o.data_source = 'BANNER_EVIDENCE' AND be.n > 0 THEN 'UNKNOWN'", "silence")),
    ("a topic's Banner text is read for the whole course", "V2", lambda t: mutate(t, "		WHERE e.term_code = o.term_code AND e.crn IN (SELECT s.crn FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id)\n			AND e.note_kind = k.note_kind AND NOT catalog_promoted.is_empty_requirement(e.note_kind, e.note_text)) bn", "		WHERE e.term_code = o.term_code AND e.course_id = o.course_id\n			AND e.note_kind = k.note_kind AND NOT catalog_promoted.is_empty_requirement(e.note_kind, e.note_text)) bn", "topictext")),
    ("the import erases policy decisions", "V2", lambda t: mutate(t, "	DELETE FROM catalog_research.section_arranged_meeting WHERE term_code = v_term AND crn = ANY (v_crns);", "	DELETE FROM catalog_research.tier_policy_decision WHERE term_code = v_term;\n	DELETE FROM catalog_research.section_arranged_meeting WHERE term_code = v_term AND crn = ANY (v_crns);", "erasedecisions")),
    ("the research import erases grid-staged offerings", "V2", lambda t: mutate(t, "INTO v_off FROM catalog_research.offering WHERE term_code = v_term AND data_source IN ('BANNER_EVIDENCE', 'SNAPSHOT');", "INTO v_off FROM catalog_research.offering WHERE term_code = v_term;", "wipecse")),
    ("the grid import erases research-sourced offerings", "V3", lambda t: mutate(t, "INTO v_off FROM catalog_research.offering WHERE term_code = v_term AND data_source = 'BANNER_GRID_STAGING';", "INTO v_off FROM catalog_research.offering WHERE term_code = v_term;", "wipegrid")),
    ("the grid import no longer cross-checks the verbatim rows", "V3", lambda t: mutate(t, "	IF v_bad IS NOT NULL THEN\n		RAISE EXCEPTION 'imported data disagrees with the verbatim grid rows", "	IF false THEN\n		RAISE EXCEPTION 'imported data disagrees with the verbatim grid rows", "gridcheck")),
    ("grid-staged credits are not compared", "V3", lambda t: mutate(t, "OR s.credits IS DISTINCT FROM CASE WHEN g.hours_grid ~ '^[0-9]+$' THEN g.hours_grid::smallint END", "OR false", "gridcredits")),
    ("a stored exam the grid lacks goes unnoticed", "V3", lambda t: mutate(t, "WHERE m.meeting_kind = 'DATED_ONE_TIME' AND NOT EXISTS (SELECT 1 FROM extra_days e", "WHERE false AND NOT EXISTS (SELECT 1 FROM extra_days e", "extraexam")),
    ("a grid exam that is not stored goes unnoticed", "V3", lambda t: mutate(t, "FROM extra_days e WHERE NOT EXISTS (SELECT 1 FROM catalog_research.section_meeting m", "FROM extra_days e WHERE false AND NOT EXISTS (SELECT 1 FROM catalog_research.section_meeting m", "missingexam")),
    ("a weekly meeting the grid lacks goes unnoticed", "V3", lambda t: mutate(t, "WHERE m.meeting_kind = 'WEEKLY' AND NOT EXISTS (SELECT 1 FROM grid_days d", "WHERE false AND NOT EXISTS (SELECT 1 FROM grid_days d", "extraweekly")),
    ("grid-staged offerings are not blocked", "V2", lambda t: mutate(t, "		WHEN o.data_source = 'BANNER_GRID_STAGING' THEN 'BLOCKED'\n", "		WHEN false THEN 'BLOCKED'\n", "gridtier")),
    ("grid-staged sections reach the candidate views", "V2", lambda t: mutate(t, "FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id)\nWHERE o.data_source IN ('BANNER_EVIDENCE', 'SNAPSHOT');", "FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id);", "gridcandidate")),
    ("unknown requirements are stored as a reviewer's 'none'", "V3", lambda t: mutate(t, "SELECT v_term, o.j ->> 'offeringId', k, 'UNKNOWN', 'NONE', NULL", "SELECT v_term, o.j ->> 'offeringId', k, 'NONE_LISTED', 'USER_CLARIFICATION', NULL", "unknownasnone")),
    ("a real Banner source may claim to be redistributable", "V3", lambda t: mutate(t, "CHECK ((origin = 'authenticated_banner') = (redistribution_status = 'NOT_VERIFIED_LOCAL_ONLY'))", "CHECK (true)", "redistribution")),
    ("program structure is not checked", "V3", lambda t: mutate(t, "	IF v_bad IS NOT NULL THEN\n		RAISE EXCEPTION 'the imported programs are structurally inconsistent", "	IF false THEN\n		RAISE EXCEPTION 'the imported programs are structurally inconsistent", "programcheck")),
    ("link status may contradict the lecture link", "V1", lambda t: mutate(t, "	CHECK ((component_type = 'LECTURE' AND lecture_crn IS NULL AND link_status = 'NOT_APPLICABLE')", "	CHECK (true OR (component_type = 'LECTURE' AND lecture_crn IS NULL AND link_status = 'NOT_APPLICABLE')", "linkcheck")),
    ("a dated meeting may sit on the wrong weekday", "V1", lambda t: mutate(t, "AND day_of_week = extract(isodow FROM start_date)))", "))", "weekday")),
]


def main():
    skip_mutations = "--no-mutations" in sys.argv
    if os.environ.get("PGHOST", "") not in ("127.0.0.1", "localhost", "::1"):
        sys.exit("refusing: PGHOST must be a loopback address (a local cluster)")
    if os.environ.get("PGPORT", "") in rpv.__dict__.get("SHARED_PORTS", {"55433"}) or os.environ.get("PGPORT", "") == "55433":
        sys.exit("refusing: this is the shared database's port")
    name = f"research_validation_{os.getpid()}"
    os.environ["PGDATABASE"] = "postgres"
    code, out = psql(["-c", f"CREATE DATABASE {name}"])
    if code != 0:
        sys.exit("cannot create the throwaway database: " + out.strip()[:300])
    os.environ["PGDATABASE"] = name
    code, out = psql(["-c", "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'research_test_reader') THEN CREATE ROLE research_test_reader; END IF; END $$"])
    try:
        print("== 1. Install every layer into a throwaway database, then catalog_research twice")
        for rel in ("cse007-snapshot/database.sql", "catalog-api/install.sql", "banner-evidence/install.sql", "catalog-promotion/install.sql"):
            code, out = psql(["-q", "-f", str(DATA / rel)])
            check(code == 0, "installed " + rel)
        code, out = psql(["-q", "-f", str(HERE / "install.sql")])
        check(code == 0 and "PASS: catalog_research" in out, "install.sql committed with its structure checks")
        defs = "SELECT md5(string_agg(pg_get_functiondef(p.oid), '|' ORDER BY p.proname)) || md5(string_agg(c.relname || pg_get_viewdef(c.oid), '|' ORDER BY c.relname)) FROM pg_proc p, pg_class c WHERE p.pronamespace = 'catalog_research'::regnamespace AND c.relnamespace = 'catalog_research'::regnamespace AND c.relkind = 'v'"
        first = query(defs)
        code, out = psql(["-q", "-f", str(HERE / "install.sql")])
        check(code == 0 and first == query(defs), "reinstall is safe and every function and view definition is identical")
        code, out = psql(["-q", "-f", str(HERE / "grant_reader.sql"), "-v", "reader=research_test_reader"])
        check(code == 0, "grant_reader.sql gave the read-only role its privileges")
        before = query("SELECT md5(string_agg(c.relname || pg_get_viewdef(c.oid), ',' ORDER BY c.relnamespace, c.relname)) FROM pg_class c "
                       "WHERE c.relnamespace IN ('catalog_api'::regnamespace, 'catalog_promoted'::regnamespace, 'catalog_live'::regnamespace) AND c.relkind = 'v'")

        print("\n== 2. Scenarios on synthetic courses (each always rolled back)")
        total = 0
        for title, body in scenarios():
            expected = expected_probes(body)
            code, out, passed = execute(run_scenario(body))
            ok = code == 0 and passed == expected
            print(f"  {'ok:   ' if ok else 'FAIL: '}{title} ({passed} probes{'' if passed == expected else f', expected {expected}'})")
            if not ok:
                print("\n".join(l for l in out.splitlines() if "probe failed" in l or "ERROR" in l or "DETAIL" in l or "CONTEXT" in l)[:1500])
                sys.exit("FAILED: scenario did not hold: " + title)
            total += passed
        print(f"  all scenarios held: {total} probes")

        if not skip_mutations:
            print("\n== 3. Mutation checks: each weakened rule must make a scenario fail")
            with tempfile.TemporaryDirectory() as tmp:
                for label, which, change in MUTATIONS:
                    v1 = change(V1) if which == "V1" else V1
                    v2 = change(V2) if which == "V2" else V2
                    v3 = change(V3) if which == "V3" else V3
                    f1, f2, f3 = Path(tmp) / "v1.sql", Path(tmp) / "v2.sql", Path(tmp) / "v3.sql"
                    f1.write_text(v1, encoding="utf-8")
                    f2.write_text(v2, encoding="utf-8")
                    f3.write_text(v3, encoding="utf-8")
                    caught = False
                    for title, body in scenarios():
                        code, out, passed = execute(run_scenario(body, install_first=f"\\i {f1}\n\\i {f2}\n\\i {f3}\n"))
                        if code != 0 or passed != expected_probes(body):
                            caught = True
                            break
                    print(f"  {'caught ' if caught else 'MISSED '} {label}")
                    if not caught:
                        sys.exit("FAILED: a weakened rule went unnoticed: " + label)

        print("\n== 4. Nothing outside catalog_research changed")
        after = query("SELECT md5(string_agg(c.relname || pg_get_viewdef(c.oid), ',' ORDER BY c.relnamespace, c.relname)) FROM pg_class c "
                      "WHERE c.relnamespace IN ('catalog_api'::regnamespace, 'catalog_promoted'::regnamespace, 'catalog_live'::regnamespace) AND c.relkind = 'v'")
        check(after == before, "the catalog_api, catalog_promoted and catalog_live view definitions are unchanged")
        check(query("SELECT count(*) FROM catalog_research.offering") == "0" and query("SELECT count(*) FROM banner_evidence.evidence_source") == "0",
              "no scenario persisted anything (all rolled back)")
        print("\nALL CATALOG_RESEARCH CHECKS PASSED")
    finally:
        os.environ["PGDATABASE"] = "postgres"
        psql(["-q", "-c", f"DROP DATABASE IF EXISTS {name} WITH (FORCE)"])
        psql(["-q", "-c", "DROP ROLE IF EXISTS research_test_reader"])


if __name__ == "__main__":
    main()
