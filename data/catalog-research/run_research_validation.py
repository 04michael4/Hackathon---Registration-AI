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
        probe("Banner silence is stored as BANNER_NO_INFORMATION, never as 'none'", "(SELECT string_agg(requirement_type || ':' || status, ',' ORDER BY requirement_type) FROM catalog_research.requirement_status WHERE offering_id = 'CSE-098') = 'COREQUISITE:BANNER_NO_INFORMATION,ENROLLMENT_RESTRICTION:BANNER_NO_INFORMATION,PREREQUISITE:BANNER_NO_INFORMATION'"),
        probe("the snapshot course's user-confirmed 'none' keeps its own label", "(SELECT string_agg(status, ',') FROM catalog_research.requirement_status WHERE offering_id = 'CSE-007') = 'USER_CONFIRMED_NONE,USER_CONFIRMED_NONE,USER_CONFIRMED_NONE'"),
        probe("a credit exclusion, an alias and a cross-listing status are stored", "(SELECT count(*) FROM catalog_research.credit_exclusion WHERE offering_id = 'CSE-097' AND excluded_course_id = 'CSE-098') = 1 AND (SELECT count(*) FROM catalog_research.course_alias WHERE offering_id = 'CSE-097') = 1"),
        probe("the candidate views carry every stored row", "(SELECT count(*) FROM catalog_research.candidate_course_offering) = 4 AND (SELECT count(*) FROM catalog_research.candidate_section_offering) = 9 AND (SELECT count(*) FROM catalog_research.candidate_section_meeting) = (SELECT count(*) FROM catalog_research.section_meeting)"),
        probe("only the verified snapshot course is schedulable in the candidate view", "(SELECT string_agg(course_id, ',' ORDER BY course_id) FROM catalog_research.candidate_course_offering WHERE schedulable) = 'CSE-007'"),
        probe("the snapshot course's candidate rows equal catalog_api's rows", "NOT EXISTS (SELECT 1 FROM (SELECT term_code, course_id, crn, section_code, credits, component_type, lecture_crn, status FROM catalog_research.candidate_section_offering WHERE course_id = 'CSE-007' EXCEPT SELECT term_code, course_id, crn, section_code, credits, component_type, lecture_crn, status FROM catalog_api.section_offering) x)"),
        probe("nothing stored here appears in catalog_live", "NOT EXISTS (SELECT 1 FROM catalog_live.course_offering WHERE course_id IN ('CSE-096', 'CSE-097', 'CSE-098'))"),
        probe("offering_status separates stored, approval and eligibility", "(SELECT string_agg(offering_id || ':' || serving_status || ':' || promotion_state, ',' ORDER BY offering_id) FROM catalog_research.offering_status) = 'CSE-007:SERVED:VERIFIED_CATALOG,CSE-096:STORED_NOT_SERVED:BLOCKED,CSE-097:STORED_NOT_SERVED:BLOCKED,CSE-098:STORED_NOT_SERVED:BLOCKED'"),
        probe("registration eligibility is never guaranteed", "NOT EXISTS (SELECT 1 FROM catalog_research.offering_status WHERE registration_eligibility_guaranteed)"),
        probe("unresolved requirements are listed", "EXISTS (SELECT 1 FROM catalog_research.unresolved_requirement WHERE offering_id = 'CSE-098' AND status = 'BANNER_NO_INFORMATION')"),
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
        probe("Banner's restriction text of one topic is not attributed to the other", "(SELECT status FROM catalog_research.requirement_status WHERE offering_id = 'CSE-094-010' AND requirement_type = 'ENROLLMENT_RESTRICTION') = 'BANNER_NO_INFORMATION' AND (SELECT status || ':' || (source_text LIKE '%Graduate (GR)%') FROM catalog_research.requirement_status WHERE offering_id = 'CSE-094-011' AND requirement_type = 'ENROLLMENT_RESTRICTION') = 'RULE_DEFINED:true'"),
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
    ("a lab may carry credits", "V1", lambda t: mutate(t, "CHECK (component_type = 'LECTURE' OR credits = 0)", "CHECK (true)", "labcredits")),
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
    ("Banner silence is stored as a user-confirmed 'none'", "V2", lambda t: mutate(t, "WHEN o.data_source = 'BANNER_EVIDENCE' AND be.n > 0 THEN 'BANNER_NO_INFORMATION'", "WHEN o.data_source = 'BANNER_EVIDENCE' AND be.n > 0 THEN 'USER_CONFIRMED_NONE'", "silence")),
    ("a topic's Banner text is read for the whole course", "V2", lambda t: mutate(t, "		WHERE e.term_code = o.term_code AND e.crn IN (SELECT s.crn FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id)\n			AND e.note_kind = k.note_kind AND NOT catalog_promoted.is_empty_requirement(e.note_kind, e.note_text)) bn", "		WHERE e.term_code = o.term_code AND e.course_id = o.course_id\n			AND e.note_kind = k.note_kind AND NOT catalog_promoted.is_empty_requirement(e.note_kind, e.note_text)) bn", "topictext")),
    ("the import erases policy decisions", "V2", lambda t: mutate(t, "	DELETE FROM catalog_research.section_arranged_meeting WHERE term_code = v_term;", "	DELETE FROM catalog_research.tier_policy_decision WHERE term_code = v_term;\n	DELETE FROM catalog_research.section_arranged_meeting WHERE term_code = v_term;", "erasedecisions")),
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
                    f1, f2 = Path(tmp) / "v1.sql", Path(tmp) / "v2.sql"
                    f1.write_text(v1, encoding="utf-8")
                    f2.write_text(v2, encoding="utf-8")
                    caught = False
                    for title, body in scenarios():
                        code, out, passed = execute(run_scenario(body, install_first=f"\\i {f1}\n\\i {f2}\n"))
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
