"""Validation for catalog_promoted / catalog_live (stdlib only; needs `psql`; run it against a TEST database).

    python3 -I data/catalog-promotion/run_promotion_validation.py [--no-mutations]

Connection settings come from PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSFILE. It REFUSES to run against a database named
like the shared one (registration_cse007_local) unless ALLOW_SHARED_DATABASE=yes (never do that).

What it does:
  1. Installs the promotion layer for real in the test database (one committing transaction) and reinstalls it.
  2. Runs 13 scenarios on SYNTHETIC courses (fake CRNs 94xxx-99xxx). Each scenario imports synthetic Banner captures through
     the real collector importer, records approvals as a person would, promotes, and checks what catalog_live serves. Every
     scenario runs inside a transaction that is ALWAYS rolled back.
  3. Mutation checks: weakens one gate at a time in a rolled-back copy and requires at least one scenario to FAIL.
  4. Confirms nothing outside catalog_promoted / catalog_live changed (the catalog_api views, David's tables, evidence rows).
"""
import copy
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(ROOT / "data" / "banner-evidence" / "collector"))
import banner_collect as bc  # noqa: E402

V1 = (HERE / "V1__catalog_promoted_schema.sql").read_text(encoding="utf-8")
V2 = HERE / "V2__catalog_live_views.sql"
S = "Computer Science & Engineering"
TERM = "202710"
EMPTY = {"restrictions_text": "No course restriction information is available.",
         "prerequisites_text": "Catalog Prerequisites No prerequisite information available.",
         "corequisites_text": "Corequisites No corequisite course information available."}


def psql(args, stdin=None, timeout=300):
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


# ----------------------------------------------------------------------------------------------------------------------
# Synthetic Banner captures (same shape the extractors produce) and their importer SQL
# ----------------------------------------------------------------------------------------------------------------------

def meet(days, start, end, typ="Class FLEX", building="None", room="None"):
    return (f"{days} S M T W T F S {start} - {end} Type: {typ} Building: {building} Room: {room} "
            "Start Date: 01/25/2027 End Date: 05/07/2027")


def sec(crn, code, kind, grid, details, meeting, links=(), parent=None):
    return {"crn": crn, "code": code, "kind": kind, "grid": grid, "details": details, "meeting": meeting, "links": list(links), "parent": parent}


def capture_set(number, sections, req=None, stamp="2026-10-17T12:00:00.000Z"):
    """The grid plus one popup per section for a synthetic course. req overrides the three requirement texts."""
    req = {**EMPTY, **(req or {})}
    by_crn = {s["crn"]: s for s in sections}
    title = f"Synthetic Course {number}"
    grid = {"view": "SEARCH_GRID", "captured_at": stamp,
            "header": {"result_count": str(len(sections)), "term_label": "2027 Spring Semester", "subject_label": S, "course_number": number, "records": str(len(sections))},
            "rows": [{"crn": s["crn"], "subject_description": S, "course_number": number, "section": s["code"], "title": title, "hours": s["grid"],
                      "schedule_type": s["kind"], "link_label": "Lab - Linked" if s["kind"] == "Lab" else None, "meetings": [s["meeting"]]} for s in sections]}
    popups = []
    for s in sections:
        if s["kind"] == "Lecture" and s["links"]:
            options = [{"label": f"Option {i}", "sections": [{"subject_description": S, "schedule_type": "Lab", "section": by_crn[c]["code"], "crn": c}]}
                       for i, c in enumerate(s["links"], start=1)]
            panel = "Linked Sections " + " ".join(f"Option {i} Title Schedule Type Section CRN {S} Lab {by_crn[c]['code']} {c}" for i, c in enumerate(s["links"], start=1))
        elif s["kind"] == "Lab":
            p = by_crn[s["parent"]]
            options = [{"label": "Option 1", "sections": [{"subject_description": S, "schedule_type": "Lecture", "section": p["code"], "crn": p["crn"]}]}]
            panel = f"Linked Sections Option 1 Title Schedule Type Section CRN {S} Lecture {p['code']} {p['crn']}"
        else:
            options, panel = [], "No linked course information available."
        popups.append({"view": "CLASS_DETAILS_POPUP", "captured_at": stamp, "term_code": TERM, "crn": s["crn"],
                       "details": {"associated_term": "2027 Spring Semester", "campus": "Synthetic Campus", "schedule_type": s["kind"], "section": s["code"],
                                   "subject_description": S, "course_number": number, "title": title, "credit_hours": s["details"]},
                       **req, "linked": {"options": options, "panel_text": panel}})
    return [grid] + popups


def import_sql(*capture_lists):
    out = []
    for captures in capture_lists:
        plan = bc.build_plan([(c, "0" * 64) for c in captures])
        out.extend(bc.generate_sql(plan))
    return "\n".join(out)


def lecture_only_crns(prefix):
    return [sec(f"{prefix}01", "010", "Lecture", "3", "3", meet("Monday,Wednesday", "10:00 AM", "10:50 AM")),
            sec(f"{prefix}02", "011", "Lecture", "3", "3", meet("Tuesday,Thursday", "11:00 AM", "12:15 PM"))]


def lab_course(number, prefix, req=None, lab_details="3"):
    lecture = sec(f"{prefix}01", "010", "Lecture", "3", "3", meet("Monday,Wednesday", "09:00 AM", "09:50 AM"), links=[f"{prefix}02", f"{prefix}03"])
    lab1 = sec(f"{prefix}02", "060", "Lab", "0", lab_details, meet("Thursday", "01:00 PM", "02:15 PM", "Lab On-Campus Only", "001 Fixture Hall", "101"), parent=f"{prefix}01")
    lab2 = sec(f"{prefix}03", "061", "Lab", "0", lab_details, meet("Friday", "10:00 AM", "11:15 AM", "Lab On-Campus Only", "001 Fixture Hall", "102"), parent=f"{prefix}01")
    return capture_set(number, [lecture, lab1, lab2], req)


# ----------------------------------------------------------------------------------------------------------------------
# SQL helpers shared by every scenario
# ----------------------------------------------------------------------------------------------------------------------

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
	EXCEPTION WHEN integrity_constraint_violation OR object_not_in_prerequisite_state THEN PERFORM pg_temp.bump(); RETURN; END;
	RAISE EXCEPTION 'probe failed: % was accepted', label;
END $$;
-- does the course's blocker list contain this exact entry?
CREATE FUNCTION pg_temp.has(p_course text, p_blocker text) RETURNS boolean LANGUAGE sql AS $$
	SELECT EXISTS (SELECT 1 FROM catalog_promoted.promotion_readiness r WHERE r.course_id = p_course AND r.blockers @> ARRAY[p_blocker]) $$;
CREATE FUNCTION pg_temp.nblockers(p_course text) RETURNS int LANGUAGE sql AS $$
	SELECT blocker_count FROM catalog_promoted.promotion_readiness WHERE course_id = p_course $$;
CREATE FUNCTION pg_temp.state(p_course text) RETURNS text LANGUAGE sql AS $$
	SELECT state FROM catalog_promoted.promotion_readiness WHERE course_id = p_course $$;
-- a recorded clarification from a person (the supporting evidence the gates require)
-- It is also what load_synthetic_fixture.py commits, so it must give the same answer on a fresh database and after the fixture
-- exists: reuse the committed row only if it is exactly what this helper writes, otherwise fail explicitly.
CREATE FUNCTION pg_temp.clarify(p_label text) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE
	v_sha text := encode(sha256(convert_to(p_label, 'UTF8')), 'hex');
	v_row banner_evidence.evidence_source%ROWTYPE;
	v_id uuid;
BEGIN
	SELECT * INTO v_row FROM banner_evidence.evidence_source
	WHERE source_kind = 'USER_CLARIFICATION' AND page_kind = 'OTHER' AND term_code = '202710' AND subject_crn IS NULL AND content_sha256 = v_sha;
	IF FOUND THEN
		IF v_row.captured_on IS DISTINCT FROM DATE '2026-10-17' OR v_row.captured_by_role IS DISTINCT FROM 'USER'
			OR v_row.inspected_by_claude IS DISTINCT FROM false OR v_row.sanitization_note IS DISTINCT FROM 'synthetic test clarification'
			OR v_row.provenance_note IS DISTINCT FROM 'synthetic: ' || p_label THEN
			RAISE EXCEPTION 'an evidence source with this natural key exists but is not the expected synthetic clarification (%)', p_label
				USING ERRCODE = 'integrity_constraint_violation';
		END IF;
		RETURN v_row.source_id;
	END IF;
	INSERT INTO banner_evidence.evidence_source (source_kind, page_kind, term_code, captured_on, captured_by_role, inspected_by_claude,
		content_sha256, sanitization_note, provenance_note)
	VALUES ('USER_CLARIFICATION', 'OTHER', '202710', DATE '2026-10-17', 'USER', false, v_sha, 'synthetic test clarification', 'synthetic: ' || p_label)
	RETURNING source_id INTO v_id;
	RETURN v_id;
END $$;
CREATE FUNCTION pg_temp.approve(p_course text, p_type text, p_subject text, p_value text, p_support uuid DEFAULT NULL) RETURNS bigint LANGUAGE sql AS $$
	SELECT catalog_promoted.record_approval('202710', p_course, p_type, p_subject, p_value, 'probe-reviewer', 'probe: reviewed the current evidence', p_support) $$;
-- the recorded clarification used as supporting evidence (set by the scenario; usable inside quoted statements)
CREATE FUNCTION pg_temp.sup() RETURNS uuid LANGUAGE sql AS $$ SELECT current_setting('t.sup')::uuid $$;
-- simulate "an older capture": move every source of a course back in time (test transaction only)
CREATE FUNCTION pg_temp.age_sources(p_course text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
	ALTER TABLE banner_evidence.evidence_source DISABLE TRIGGER evidence_source_append_only;
	UPDATE banner_evidence.evidence_source SET recorded_at = recorded_at - interval '1 hour'
	WHERE source_kind = 'BANNER_LIVE' AND (source_id IN (SELECT source_id FROM banner_evidence.section_observation WHERE course_id = p_course)
		OR subject_crn IN (SELECT crn FROM banner_evidence.section_observation WHERE course_id = p_course));
	ALTER TABLE banner_evidence.evidence_source ENABLE TRIGGER evidence_source_append_only;
END $$;
"""

ALL_BUT = ["CREDITS", "STANDALONE", "REQUIREMENTS", "CROSS_LISTING", "CREDIT_EXCLUSION"]


def approve_lecture_only(course, crns, skip=(), support="pg_temp.sup()"):
    """SQL that records every approval a lecture-only course needs, except the types in skip."""
    lines = []
    for crn in crns:
        if "CREDITS" not in skip:
            lines.append(f"SELECT pg_temp.approve('{course}', 'CREDITS', '{crn}', '3');")
        if "STANDALONE" not in skip:
            lines.append(f"SELECT pg_temp.approve('{course}', 'STANDALONE', '{crn}', 'STANDALONE', {support});")
    if "REQUIREMENTS" not in skip:
        lines.append(f"SELECT pg_temp.approve('{course}', 'REQUIREMENTS', NULL, 'NONE_ENFORCEABLE', {support});")
    if "CROSS_LISTING" not in skip:
        lines.append(f"SELECT pg_temp.approve('{course}', 'CROSS_LISTING', NULL, 'NOT_CROSS_LISTED', {support});")
    if "CREDIT_EXCLUSION" not in skip:
        lines.append(f"SELECT pg_temp.approve('{course}', 'CREDIT_EXCLUSION', NULL, 'NONE', {support});")
    return "\n".join(lines)


def approve_lab_course(course, lecture, labs, skip=(), support="pg_temp.sup()"):
    lines = []
    if "CREDITS" not in skip:
        lines.append(f"SELECT pg_temp.approve('{course}', 'CREDITS', '{lecture}', '3');")
        for lab in labs:
            lines.append(f"SELECT pg_temp.approve('{course}', 'CREDITS', '{lab}', '0', {support});")
    if "LAB_LINKS" not in skip:
        lines.append(f"SELECT pg_temp.approve('{course}', 'LAB_LINKS', '{lecture}', 'LABS:{','.join(labs)}');")
    if "REQUIRED_LAB_COUNT" not in skip:
        lines.append(f"SELECT pg_temp.approve('{course}', 'REQUIRED_LAB_COUNT', '{lecture}', '1', {support});")
    for kind, value, typ in (("REQUIREMENTS", "NONE_ENFORCEABLE", "REQUIREMENTS"), ("CROSS_LISTING", "NOT_CROSS_LISTED", "CROSS_LISTING"), ("CREDIT_EXCLUSION", "NONE", "CREDIT_EXCLUSION")):
        if kind not in skip:
            lines.append(f"SELECT pg_temp.approve('{course}', '{typ}', NULL, '{value}', {support});")
    return "\n".join(lines)


SUP = "SELECT set_config('t.sup', pg_temp.clarify('a person confirmed this with a registrar-level source')::text, true);\n"


def probe(label, cond):
    return "SELECT pg_temp.expect_true('" + label.replace("'", "''") + f"', {cond});"


def reject(label, stmt):
    return "SELECT pg_temp.expect_rejected('" + label.replace("'", "''") + f"', $q$ {stmt} $q$);"


def served(course):
    return f"EXISTS (SELECT 1 FROM catalog_live.course_offering WHERE course_id = '{course}')"


# ----------------------------------------------------------------------------------------------------------------------
# Scenarios: (name, expected probe count, sql body). Each runs inside BEGIN ... ROLLBACK.
# ----------------------------------------------------------------------------------------------------------------------

def scenarios():
    out = []
    # 0. The supporting-evidence helper gives the same answer on a fresh database and after the synthetic fixture was committed.
    shared_label = "a person confirmed this with a registrar-level source"
    out.append(("supporting-evidence helper is order independent (fresh database, or the synthetic fixture already loaded)", "\n".join([
        "SELECT set_config('t.c1', pg_temp.clarify('order independence label')::text, true);",
        "SELECT set_config('t.c2', pg_temp.clarify('order independence label')::text, true);",
        probe("asking twice for one clarification returns the same source", "current_setting('t.c1') = current_setting('t.c2')"),
        probe("it is the expected synthetic user clarification", "(SELECT source_kind || captured_by_role || inspected_by_claude::text || provenance_note FROM banner_evidence.evidence_source "
              "WHERE source_id = current_setting('t.c1')::uuid) = 'USER_CLARIFICATIONUSERfalsesynthetic: order independence label'"),
        f"SELECT set_config('t.c3', pg_temp.clarify('{shared_label}')::text, true);",
        probe("the label every scenario and the fixture loader use resolves to the one committed row, if there is one, else to a new row",
              f"current_setting('t.c3')::uuid = (SELECT source_id FROM banner_evidence.evidence_source WHERE source_kind = 'USER_CLARIFICATION' "
              f"AND content_sha256 = encode(sha256(convert_to('{shared_label}', 'UTF8')), 'hex'))"),
        "INSERT INTO banner_evidence.evidence_source (source_kind, page_kind, term_code, captured_on, captured_by_role, inspected_by_claude, content_sha256, "
        "sanitization_note, provenance_note) VALUES ('USER_CLARIFICATION', 'OTHER', '202710', DATE '2026-10-17', 'USER', false, "
        "encode(sha256(convert_to('tampered label', 'UTF8')), 'hex'), 'synthetic test clarification', 'written by someone else');",
        reject("an existing source with the same key but different content is refused, not silently reused", "SELECT pg_temp.clarify('tampered label')"),
    ]), None))
    lo = capture_set("098", lecture_only_crns("980"))  # CRNs 98001, 98002
    C = "CSE-098"
    crns = ["98001", "98002"]

    # 1. A fully approved lecture-only course becomes schedule-ready; repeated promotion creates no duplicates.
    out.append(("fully approved lecture-only course becomes schedule-ready; repeated promotion is idempotent", import_sql(lo) + "\n" + SUP + "\n".join([
        probe("starts BLOCKED", f"pg_temp.state('{C}') = 'BLOCKED'"),
        probe("every missing approval is named", " AND ".join(f"pg_temp.has('{C}', '{b}')" for b in (
            "STANDALONE_NOT_APPROVED:98001", "STANDALONE_NOT_APPROVED:98002", "CREDITS_NOT_APPROVED:98001", "CREDITS_NOT_APPROVED:98002",
            "REQUIREMENTS_NOT_APPROVED", "CROSS_LISTING_NOT_APPROVED", "CREDIT_EXCLUSION_NOT_APPROVED"))),
        probe("not served before approval", f"NOT {served(C)}"),
        reject("promoting before approval", f"SELECT catalog_promoted.promote_course('202710', '{C}', 'probe-reviewer')"),
        approve_lecture_only(C, crns),
        probe("no blocker once every approval is recorded", f"pg_temp.nblockers('{C}') = 0 AND pg_temp.state('{C}') = 'READY_TO_PROMOTE'"),
        probe("approving alone does not serve the course", f"NOT {served(C)}"),
        "SELECT catalog_promoted.promote_course('202710', 'CSE-098', 'probe-reviewer') AS pid \\gset",
        probe("now PROMOTED_SERVING", f"pg_temp.state('{C}') = 'PROMOTED_SERVING'"),
        probe("catalog_live offers the course with the approved credits", f"(SELECT credits || title FROM catalog_live.course_offering WHERE course_id = '{C}' AND schedulable) = '3Synthetic Course 098'"),
        probe("both lecture sections are served as independent lectures", f"(SELECT array_agg(crn || ':' || component_type || ':' || credits || ':' || COALESCE(lecture_crn, '-') ORDER BY crn) FROM catalog_live.section_offering WHERE course_id = '{C}') = ARRAY['98001:LECTURE:3:-', '98002:LECTURE:3:-']"),
        probe("meetings come from the evidence, one row per day", f"(SELECT array_agg(crn || ':' || day_of_week || ':' || start_time || '-' || end_time ORDER BY crn, day_of_week) FROM catalog_live.section_meeting WHERE crn IN ('98001','98002')) = ARRAY['98001:1:10:00-10:50','98001:3:10:00-10:50','98002:2:11:00-12:15','98002:4:11:00-12:15']"),
        probe("registration requirements are preserved verbatim with each section", f"(SELECT count(*) FROM catalog_live.section_requirement WHERE course_id = '{C}') = 6"),
        probe("the offering is marked PROMOTED with its promotion id", f"(SELECT source || promotion_id FROM catalog_live.offering_source WHERE course_id = '{C}') = 'PROMOTED' || :pid"),
        probe("every promoted section cites existing Banner captures and an approval", f"(SELECT count(*) FROM catalog_promoted.promoted_section ps JOIN banner_evidence.evidence_source g ON g.source_id = ps.grid_source_id JOIN banner_evidence.evidence_source d ON d.source_id = ps.details_source_id JOIN banner_evidence.evidence_source l ON l.source_id = ps.linked_source_id JOIN catalog_promoted.approval a ON a.approval_id = ps.credits_approval_id WHERE ps.promotion_id = :pid) = 2"),
        "SELECT (SELECT count(*) FROM catalog_promoted.promotion) AS np, (SELECT count(*) FROM catalog_promoted.promoted_section) AS ns, (SELECT count(*) FROM catalog_promoted.promoted_meeting) AS nm, (SELECT count(*) FROM catalog_promoted.promotion_approval) AS na \\gset",
        "SELECT catalog_promoted.promote_course('202710', 'CSE-098', 'probe-reviewer') AS pid2 \\gset",
        probe("promoting again returns the same promotion", ":pid2 = :pid"),
        probe("and adds no rows anywhere", "(SELECT count(*) FROM catalog_promoted.promotion) = :np AND (SELECT count(*) FROM catalog_promoted.promoted_section) = :ns AND (SELECT count(*) FROM catalog_promoted.promoted_meeting) = :nm AND (SELECT count(*) FROM catalog_promoted.promotion_approval) = :na"),
        import_sql(lo),
        probe("re-importing identical captures keeps the approvals fresh and the course served", f"pg_temp.state('{C}') = 'PROMOTED_SERVING'"),
        probe("catalog_live never lists a course or CRN twice", "(SELECT count(*) FROM (SELECT course_id FROM catalog_live.course_offering GROUP BY term_code, course_id HAVING count(*) > 1) d) = 0 AND (SELECT count(*) FROM (SELECT crn FROM catalog_live.section_offering GROUP BY term_code, crn HAVING count(*) > 1) d) = 0"),
    ]), 20))

    # 2. UNKNOWN standalone status blocks promotion; it cannot be inferred from missing links.
    out.append(("UNKNOWN standalone status blocks promotion and cannot be inferred", import_sql(lo) + "\n" + SUP + "\n".join([
        approve_lecture_only(C, crns, skip=("STANDALONE",)),
        probe("only the standalone approvals are missing", f"pg_temp.nblockers('{C}') = 2 AND pg_temp.has('{C}', 'STANDALONE_NOT_APPROVED:98001') AND pg_temp.has('{C}', 'STANDALONE_NOT_APPROVED:98002')"),
        reject("promoting with unknown standalone status", f"SELECT catalog_promoted.promote_course('202710', '{C}', 'probe-reviewer')"),
        probe("not served", f"NOT {served(C)}"),
        reject("a standalone approval with no supporting evidence (Banner showing no links is not enough)", f"SELECT pg_temp.approve('{C}', 'STANDALONE', '98001', 'STANDALONE')"),
        reject("a supporting source that is not a recorded clarification", f"SELECT pg_temp.approve('{C}', 'STANDALONE', '98001', 'STANDALONE', (SELECT source_id FROM banner_evidence.evidence_source WHERE page_kind = 'SEARCH_RESULTS' LIMIT 1))"),
        probe("still blocked after the refused attempts", f"pg_temp.nblockers('{C}') = 2"),
    ]), 5))

    # 3. Unapproved credits block promotion; credit approvals are validated against the displays.
    out.append(("unapproved credits block promotion; credit approvals are validated", import_sql(lo) + "\n" + SUP + "\n".join([
        approve_lecture_only(C, crns, skip=("CREDITS",)),
        probe("only the credits are missing", f"pg_temp.nblockers('{C}') = 2 AND pg_temp.has('{C}', 'CREDITS_NOT_APPROVED:98001') AND pg_temp.has('{C}', 'CREDITS_NOT_APPROVED:98002')"),
        reject("promoting with unapproved credits", f"SELECT catalog_promoted.promote_course('202710', '{C}', 'probe-reviewer')"),
        reject("approving a value that is not the displayed credit without supporting evidence", f"SELECT pg_temp.approve('{C}', 'CREDITS', '98001', '4')"),
        reject("a fractional credit", f"SELECT pg_temp.approve('{C}', 'CREDITS', '98001', '2.5')"),
        reject("an approval by an automated-sounding name", f"SELECT catalog_promoted.record_approval('202710', '{C}', 'CREDITS', '98001', '3', 'claude-script', 'probe: automatic approval attempt', NULL)"),
        reject("an approval prepared against stale evidence", f"INSERT INTO catalog_promoted.approval (term_code, course_id, approval_type, subject_crn, decision, approved_value, approved_by, rationale, evidence_fingerprint) VALUES ('202710', '{C}', 'CREDITS', '98001', 'APPROVE', '3', 'probe-reviewer', 'probe: stale fingerprint attempt', repeat('0', 64))"),
        reject("a REVOKE-less approval for a section that is not part of the course", f"SELECT pg_temp.approve('{C}', 'CREDITS', '55555', '3')"),
        probe("nothing was approved by the refused attempts", f"pg_temp.nblockers('{C}') = 2"),
    ]), 8))

    # 4. Lab course: unresolved lab count and lab credits block; labs need supporting evidence; combos preserved.
    lc = lab_course("097", "970")
    L, lec, labs = "CSE-097", "97001", ["97002", "97003"]
    lc_agree = lab_course("094", "940", lab_details="0")  # a lab whose two displays AGREE (0 and 0)
    out.append(("unresolved lab counts block promotion; lecture/lab combinations are preserved", import_sql(lc, lc_agree) + "\n" + SUP + "\n".join([
        reject("lab credits without supporting evidence even when both displays agree and equal the value", "SELECT pg_temp.approve('CSE-094', 'CREDITS', '94002', '0')"),
        probe("with supporting evidence the same lab credit is accepted", "pg_temp.approve('CSE-094', 'CREDITS', '94002', '0', pg_temp.sup()) > 0"),
        probe("a lecture with listed labs is not treated as standalone", f"NOT pg_temp.has('{L}', 'STANDALONE_NOT_APPROVED:97001')"),
        probe("links, count and lab credits are all unresolved", " AND ".join(f"pg_temp.has('{L}', '{b}')" for b in (
            "LAB_LINKS_NOT_APPROVED:97001", "LAB_COUNT_NOT_APPROVED:97001", "CREDITS_NOT_APPROVED:97002", "CREDITS_NOT_APPROVED:97003"))),
        reject("lab credits without supporting evidence (the CSE 007 convention is not generalized)", f"SELECT pg_temp.approve('{L}', 'CREDITS', '97002', '0')"),
        reject("lab credits even when the value equals the grid display", f"SELECT pg_temp.approve('{L}', 'CREDITS', '97003', '0')"),
        reject("approving a lab list that is not exactly what Banner lists", f"SELECT pg_temp.approve('{L}', 'LAB_LINKS', '97001', 'LABS:97002')"),
        reject("a required lab count without supporting evidence (Option labels do not state one)", f"SELECT pg_temp.approve('{L}', 'REQUIRED_LAB_COUNT', '97001', '1')"),
        reject("a lab count other than exactly one", f"SELECT pg_temp.approve('{L}', 'REQUIRED_LAB_COUNT', '97001', '2', pg_temp.sup())"),
        approve_lab_course(L, lec, labs, skip=("REQUIRED_LAB_COUNT",)),
        probe("only the unresolved lab count blocks", f"pg_temp.nblockers('{L}') = 1 AND pg_temp.has('{L}', 'LAB_COUNT_NOT_APPROVED:97001')"),
        reject("promoting with an unresolved lab count", f"SELECT catalog_promoted.promote_course('202710', '{L}', 'probe-reviewer')"),
        probe("not served", f"NOT {served(L)}"),
        f"SELECT pg_temp.approve('{L}', 'REQUIRED_LAB_COUNT', '97001', '1', pg_temp.sup());",
        "SELECT catalog_promoted.promote_course('202710', 'CSE-097', 'probe-reviewer');",
        probe("served with the lecture and both labs, labs linked to the lecture", f"(SELECT array_agg(crn || ':' || component_type || ':' || credits || ':' || COALESCE(lecture_crn, '-') ORDER BY crn) FROM catalog_live.section_offering WHERE course_id = '{L}') = ARRAY['97001:LECTURE:3:-', '97002:LAB:0:97001', '97003:LAB:0:97001']"),
        probe("the course credit is the lecture's approved credit", f"(SELECT credits FROM catalog_live.course_offering WHERE course_id = '{L}') = 3"),
        probe("lab meetings are preserved with their rooms", f"(SELECT location FROM catalog_live.section_meeting WHERE crn = '97002') = '001 Fixture Hall 101'"),
    ]), 13))

    # 5. Unenforced prerequisites and restrictions block unconditional scheduling.
    req = capture_set("096", lecture_only_crns("960"), req={
        "prerequisites_text": "Catalog Prerequisites And/Or Test Score Subject Course Number Level Grade Computer Science & Engineering 017 Undergraduate TR",
        "restrictions_text": "Not all restrictions are applicable. Cannot be enrolled in one of the following Classes: First-Year Student (FR)"})
    R = "CSE-096"
    out.append(("unenforced prerequisites and restrictions block unconditional scheduling", import_sql(req) + "\n" + SUP + "\n".join([
        probe("each unenforced requirement is named", f"pg_temp.has('{R}', 'REQUIREMENTS_UNENFORCED:96001 PREREQUISITE_TEXT') AND pg_temp.has('{R}', 'REQUIREMENTS_UNENFORCED:96001 REGISTRATION_RESTRICTION')"),
        approve_lecture_only(R, ["96001", "96002"], skip=("REQUIREMENTS",)),
        reject("approving the requirements away", f"SELECT pg_temp.approve('{R}', 'REQUIREMENTS', NULL, 'NONE_ENFORCEABLE', pg_temp.sup())"),
        reject("claiming the app enforces them when it does not", f"SELECT pg_temp.approve('{R}', 'REQUIREMENTS', NULL, 'ENFORCED_BY_APP', pg_temp.sup())"),
        probe("the course stays blocked", f"pg_temp.nblockers('{R}') >= 4 AND pg_temp.has('{R}', 'REQUIREMENTS_NOT_APPROVED')"),
        reject("promoting it", f"SELECT catalog_promoted.promote_course('202710', '{R}', 'probe-reviewer')"),
        probe("not served", f"NOT {served(R)}"),
        # The only thing that unlocks it is real enforcement in the application.
        "UPDATE catalog_promoted.capability SET enabled = true WHERE name IN ('PREREQUISITES_ENFORCED', 'RESTRICTIONS_ENFORCED');",
        f"SELECT pg_temp.approve('{R}', 'REQUIREMENTS', NULL, 'ENFORCED_BY_APP', pg_temp.sup());",
        probe("with enforcement available the requirement blockers clear", f"pg_temp.nblockers('{R}') = 0"),
        f"SELECT catalog_promoted.promote_course('202710', '{R}', 'probe-reviewer');",
        probe("and the course is served with its requirement text", f"{served(R)} AND (SELECT count(*) FROM catalog_live.section_requirement WHERE course_id = '{R}') = 6"),
        "UPDATE catalog_promoted.capability SET enabled = false;",
        probe("switching enforcement off stops serving it immediately", f"NOT {served(R)} AND pg_temp.state('{R}') = 'PROMOTED_BLOCKED'"),
    ]), 10))

    # 6. Conflicting evidence blocks readiness even when everything else is approved.
    cf = capture_set("099", [sec("99001", "010", "Lecture", "3", "3", meet("Monday", "09:20 AM", "10:35 AM"), links=["99002", "99003"]),
                             sec("99002", "060", "Lab", "0", "3", meet("Wednesday", "09:20 AM", "10:35 AM"), parent="99001"),
                             sec("99003", "061", "Lab", "0", "3", meet("Friday", "10:45 AM", "12:00 PM"), parent="99001")])
    CF = "CSE-099"
    setup = (HERE.parent / "banner-evidence" / "collector" / "fixtures" / "setup_user_report_conflict.sql").read_text(encoding="utf-8")
    out.append(("conflicting evidence blocks readiness", setup + "\n" + import_sql(cf) + "\n" + SUP + "\n".join([
        approve_lab_course(CF, "99001", ["99002", "99003"]),
        probe("everything is approved, yet the conflict is the one blocker", f"pg_temp.nblockers('{CF}') = 1 AND (SELECT blockers[1] LIKE 'EVIDENCE_CONFLICT:claim %' FROM catalog_promoted.promotion_readiness WHERE course_id = '{CF}')"),
        reject("promoting despite the conflict", f"SELECT catalog_promoted.promote_course('202710', '{CF}', 'probe-reviewer')"),
        probe("not served", f"NOT {served(CF)}"),
    ]), 4))

    # 7. New evidence after promotion stops the course being served until it is re-reviewed.
    changed = copy.deepcopy(lo)
    changed[0]["rows"][0]["meetings"] = [meet("Monday,Wednesday", "11:00 AM", "11:50 AM")]
    changed[0]["captured_at"] = "2026-10-18T09:00:00.000Z"
    out.append(("new or changed evidence blocks a promoted course until it is re-reviewed", import_sql(lo) + "\n" + SUP + "\n".join([
        approve_lecture_only(C, crns),
        "SELECT catalog_promoted.promote_course('202710', 'CSE-098', 'probe-reviewer') AS pid \\gset",
        probe("served", f"pg_temp.state('{C}') = 'PROMOTED_SERVING' AND {served(C)}"),
        f"SELECT pg_temp.age_sources('{C}');",
        import_sql(changed),
        probe("a different capture arrived: the course is no longer served", f"NOT {served(C)} AND pg_temp.state('{C}') = 'PROMOTED_BLOCKED'"),
        probe("every approval is flagged stale", f"pg_temp.has('{C}', 'APPROVAL_STALE:CREDITS 98001') AND pg_temp.has('{C}', 'APPROVAL_STALE:STANDALONE 98001') AND pg_temp.has('{C}', 'APPROVAL_STALE:REQUIREMENTS')"),
        reject("promoting on stale approvals", f"SELECT catalog_promoted.promote_course('202710', '{C}', 'probe-reviewer')"),
        reject("approving against the old evidence", f"INSERT INTO catalog_promoted.approval (term_code, course_id, approval_type, subject_crn, decision, approved_value, approved_by, rationale, evidence_fingerprint) VALUES ('202710', '{C}', 'CREDITS', '98001', 'APPROVE', '3', 'probe-reviewer', 'probe: stale on purpose', (SELECT evidence_fingerprint FROM catalog_promoted.approval ORDER BY approval_id LIMIT 1))"),
        approve_lecture_only(C, crns),
        probe("after re-review it is ready again but not served until promoted", f"pg_temp.state('{C}') = 'READY_TO_PROMOTE' AND NOT {served(C)}"),
        "SELECT catalog_promoted.promote_course('202710', 'CSE-098', 'probe-reviewer') AS pid2 \\gset",
        probe("re-promotion is a NEW version, serving the new evidence", f":pid2 <> :pid AND (SELECT version FROM catalog_promoted.promotion WHERE promotion_id = :pid2) = 2 AND (SELECT start_time FROM catalog_live.section_meeting WHERE crn = '98001' AND day_of_week = 1) = '11:00'"),
        probe("the old version is preserved, not served", "(SELECT count(*) FROM catalog_promoted.promotion WHERE course_id = 'CSE-098') = 2 AND (SELECT count(*) FROM catalog_live.course_offering WHERE course_id = 'CSE-098') = 1"),
    ]), 10))

    # 8. The verified snapshot is never duplicated.
    dup_course = capture_set("007", [sec("94001", "010", "Lecture", "3", "3", meet("Monday", "09:00 AM", "09:50 AM"))])
    dup_crn = capture_set("095", [sec("12275", "010", "Lecture", "3", "3", meet("Tuesday", "09:00 AM", "09:50 AM"))])
    out.append(("a course or CRN the verified snapshot already serves is never promoted or duplicated", import_sql(dup_course, dup_crn) + "\n" + SUP + "\n".join([
        approve_lecture_only("CSE-007", ["94001"]),
        approve_lecture_only("CSE-095", ["12275"]),
        probe("a second CSE-007 is blocked", "pg_temp.has('CSE-007', 'DUPLICATES_EXISTING_CATALOG:course')"),
        probe("a new course reusing CSE 007's CRN is blocked", "pg_temp.has('CSE-095', 'DUPLICATES_EXISTING_CATALOG:crn 12275')"),
        reject("promoting the duplicate course", "SELECT catalog_promoted.promote_course('202710', 'CSE-007', 'probe-reviewer')"),
        reject("promoting the duplicate CRN", "SELECT catalog_promoted.promote_course('202710', 'CSE-095', 'probe-reviewer')"),
        probe("catalog_live still offers exactly the one snapshot CSE-007", "(SELECT count(*) FROM catalog_live.course_offering WHERE course_id = 'CSE-007') = 1 AND (SELECT count(*) FROM catalog_live.section_offering WHERE crn = '12275') = 1"),
    ]), 5))

    # 9. CSE 007 is unchanged by the promotion layer; credit exclusions block.
    cx = capture_set("003", [sec("93001", "010", "Lecture", "2", "2", meet("Monday,Wednesday", "09:20 AM", "10:35 AM"))])
    X = "CSE-003"
    out.append(("CSE 007 is unchanged; a catalog credit exclusion with CSE 007 blocks CSE 003", import_sql(cx) + "\n" + SUP + "\n".join([
        probe("catalog_live serves every catalog_api row unchanged", " AND ".join(
            f"(SELECT count(*) FROM (SELECT * FROM catalog_api.{v} EXCEPT SELECT * FROM catalog_live.{v}) b) = 0"
            for v in ("course_offering", "section_offering", "section_meeting"))),
        probe("anything else catalog_live serves is marked PROMOTED (none when no course was promoted before this run)",
              "NOT EXISTS (SELECT 1 FROM catalog_live.course_offering c LEFT JOIN catalog_live.offering_source o USING (course_id) "
              "WHERE c.course_id NOT IN (SELECT course_id FROM catalog_api.course_offering) AND o.source IS DISTINCT FROM 'PROMOTED')"),
        probe("exactly the three CSE 007 sections and five meetings, and CSE 007 is the only SNAPSHOT course",
              "(SELECT count(*) FROM catalog_live.section_offering WHERE course_id = 'CSE-007') = 3 AND (SELECT count(*) FROM catalog_live.section_meeting "
              "WHERE crn IN (SELECT crn FROM catalog_api.section_offering)) = 5 AND (SELECT count(*) FROM catalog_live.offering_source WHERE source = 'SNAPSHOT') = 1"),
        probe("the catalog names CSE 007 as excluding CSE 003 and the blocker says so", f"pg_temp.has('{X}', 'CREDIT_EXCLUSION_UNENFORCED:CSE-007')"),
        approve_lecture_only(X, ["93001"], skip=("CREDITS", "CREDIT_EXCLUSION")),
        f"SELECT pg_temp.approve('{X}', 'CREDITS', '93001', '2');",
        reject("approving 'no credit exclusion' against the catalog's own text", f"SELECT pg_temp.approve('{X}', 'CREDIT_EXCLUSION', NULL, 'NONE', pg_temp.sup())"),
        f"SELECT pg_temp.approve('{X}', 'CREDIT_EXCLUSION', NULL, 'EXCLUDES:CSE-007,CSE-004', pg_temp.sup());",
        probe("recording the exclusion still blocks, naming each excluded course: the engine cannot enforce it", f"pg_temp.nblockers('{X}') = 2 AND pg_temp.has('{X}', 'CREDIT_EXCLUSION_UNENFORCED:CSE-007') AND pg_temp.has('{X}', 'CREDIT_EXCLUSION_UNENFORCED:CSE-004')"),
        reject("promoting CSE 003 while the exclusion is unenforced", f"SELECT catalog_promoted.promote_course('202710', '{X}', 'probe-reviewer')"),
        probe("CSE 007 is still served exactly as before", "(SELECT count(*) FROM catalog_live.section_offering WHERE course_id = 'CSE-007') = 3"),
    ]), 9))

    # 10. Append-only, revocation, retraction.
    out.append(("approvals and promotions are append-only; revoking or retracting stops serving", import_sql(lo) + "\n" + SUP + "\n".join([
        approve_lecture_only(C, crns),
        "SELECT catalog_promoted.promote_course('202710', 'CSE-098', 'probe-reviewer') AS pid \\gset",
        reject("UPDATE approval", "UPDATE catalog_promoted.approval SET rationale = 'edited after the fact'"),
        reject("DELETE approval", "DELETE FROM catalog_promoted.approval"),
        reject("UPDATE promotion", "UPDATE catalog_promoted.promotion SET promoted_by = 'someone-else'"),
        reject("DELETE promoted_section", "DELETE FROM catalog_promoted.promoted_section"),
        reject("UPDATE promoted_meeting", "UPDATE catalog_promoted.promoted_meeting SET location = 'Elsewhere'"),
        reject("TRUNCATE promotion", "TRUNCATE catalog_promoted.promotion CASCADE"),
        reject("a promotion by an automated-sounding name", f"SELECT catalog_promoted.promote_course('202710', '{C}', 'auto-bot')"),
        f"SELECT catalog_promoted.record_approval('202710', '{C}', 'CREDITS', '98001', NULL, 'probe-reviewer', 'probe: revoking the credit approval', NULL, 'REVOKE');",
        probe("revoking an approval stops the course being served at once", f"NOT {served(C)} AND pg_temp.has('{C}', 'CREDITS_NOT_APPROVED:98001')"),
        f"SELECT pg_temp.approve('{C}', 'CREDITS', '98001', '3');",
        probe("re-approving makes it ready; it is served only after an explicit new promotion", f"NOT {served(C)} AND pg_temp.state('{C}') = 'READY_TO_PROMOTE'"),
        "SELECT catalog_promoted.promote_course('202710', 'CSE-098', 'probe-reviewer') AS pid2 \\gset",
        probe("served again as version 2", f"{served(C)} AND (SELECT version FROM catalog_promoted.promotion WHERE promotion_id = :pid2) = 2"),
        "SELECT catalog_promoted.retract_promotion(:pid2, 'probe-reviewer', 'probe: retracting the current promotion') \\gset",
        probe("a retracted promotion is not served", f"NOT {served(C)} AND NOT EXISTS (SELECT 1 FROM catalog_promoted.current_promotion WHERE course_id = '{C}')"),
    ]), 13))

    # 11. Nothing here approves anything automatically.
    out.append(("promotion never creates an approval; approvals are only a person's explicit rows", import_sql(lo) + "\n" + SUP + "\n".join([
        approve_lecture_only(C, crns),
        "SELECT count(*) AS n_approvals FROM catalog_promoted.approval \\gset",
        "SELECT catalog_promoted.promote_course('202710', 'CSE-098', 'probe-reviewer');",
        probe("promote_course added no approval", "(SELECT count(*) FROM catalog_promoted.approval) = :n_approvals"),
        probe("no claim_review was written by the promotion layer", "NOT EXISTS (SELECT 1 FROM banner_evidence.claim_review)"),
        probe("every approval names a person, a reason and the evidence it reviewed", "NOT EXISTS (SELECT 1 FROM catalog_promoted.approval WHERE approved_by IS NULL OR length(rationale) < 10 OR evidence_fingerprint !~ '^[0-9a-f]{64}$')"),
        probe("approvals are stamped with who actually wrote them", "NOT EXISTS (SELECT 1 FROM catalog_promoted.approval WHERE decided_by IS NULL)"),
        probe("the promotion cites every approval it relied on", "(SELECT count(*) FROM catalog_promoted.promotion_approval) = (SELECT count(*) FROM catalog_promoted.approval)"),
    ]), 5))

    # 12. promotion_readiness is read-only and lists every candidate with its blockers.
    out.append(("promotion_readiness is read-only and lists candidates with blockers", import_sql(lo, lc) + "\n" + "\n".join([
        probe("both synthetic candidates appear", "(SELECT count(*) FROM catalog_promoted.promotion_readiness WHERE course_id IN ('CSE-098', 'CSE-097')) = 2"),
        probe("a blocked course shows its section count and a non-empty blocker list", "(SELECT section_count = 2 AND blocker_count > 0 AND cardinality(blockers) = blocker_count FROM catalog_promoted.promotion_readiness WHERE course_id = 'CSE-098')"),
        reject("writing to the readiness view", "UPDATE catalog_promoted.promotion_readiness SET state = 'PROMOTED_SERVING'"),
        reject("inserting into the blocker view", "INSERT INTO catalog_promoted.promotion_blocker VALUES ('202710', 'CSE-098', 'X', 'Y')"),
        probe("candidate courses never appear in catalog_live before promotion", "NOT EXISTS (SELECT 1 FROM catalog_live.course_offering WHERE course_id IN ('CSE-098', 'CSE-097'))"),
    ]), 5))
    return out


def run_scenario(body, install_first=None, extra_sql=""):
    """Wrap a scenario body: preamble, (optional) fresh install of a possibly-mutated schema, body, final count; always roll back."""
    parts = [PREAMBLE, "BEGIN;", "SELECT set_config('t.passed', '0', true);"]
    if install_first is not None:
        parts += ["DROP SCHEMA IF EXISTS catalog_live CASCADE;", "DROP SCHEMA IF EXISTS catalog_promoted CASCADE;", install_first]
    parts.append(body)
    parts += ["SELECT current_setting('t.passed')::int AS passed \\gset", "\\echo SCENARIO_PROBES :passed", "ROLLBACK;"]
    return "\n".join(parts) + "\n"


def expected_probes(body):
    """Every probe the scenario text contains; the run must record exactly this many (none silently skipped)."""
    return body.count("SELECT pg_temp.expect_true(") + body.count("SELECT pg_temp.expect_rejected(")


def execute(sql):
    code, out = psql(["-f", "-"], stdin=sql)
    m = re.search(r"SCENARIO_PROBES (\d+)", out)
    return code, out, int(m.group(1)) if m else None


# ----------------------------------------------------------------------------------------------------------------------
# Mutations: each weakens one gate; at least one scenario must fail
# ----------------------------------------------------------------------------------------------------------------------

def mutate(text, old, new, label):
    if text.count(old) != 1:
        sys.exit(f"mutation '{label}' does not apply: expected exactly one occurrence, found {text.count(old)}")
    return text.replace(old, new, 1)


MUTATIONS = [
    ("standalone blocker removed", lambda t: mutate(t, "WHERE s.schedule_type = 'Lecture' AND s.linked_outcome = 'NONE_SHOWN'\n\tAND NOT EXISTS", "WHERE false AND NOT EXISTS", "standalone")),
    ("credits-not-approved blocker removed", lambda t: mutate(t, "SELECT s.term_code, s.course_id, 'CREDITS_NOT_APPROVED', s.crn\nFROM catalog_promoted.evidence_section s\nWHERE NOT EXISTS", "SELECT s.term_code, s.course_id, 'CREDITS_NOT_APPROVED', s.crn\nFROM catalog_promoted.evidence_section s\nWHERE false AND NOT EXISTS", "credits")),
    ("lab-count blocker removed", lambda t: mutate(t, "SELECT s.term_code, s.course_id, 'LAB_COUNT_NOT_APPROVED', s.crn\nFROM catalog_promoted.evidence_section s\nWHERE s.schedule_type = 'Lecture'", "SELECT s.term_code, s.course_id, 'LAB_COUNT_NOT_APPROVED', s.crn\nFROM catalog_promoted.evidence_section s\nWHERE false AND s.schedule_type = 'Lecture'", "labcount")),
    ("unenforced-requirement blocker removed", lambda t: mutate(t, "WHERE NOT catalog_promoted.is_empty_requirement(r.note_kind, r.note_text)\n\tAND NOT EXISTS", "WHERE false AND NOT EXISTS", "requirements")),
    ("evidence-conflict blocker removed", lambda t: mutate(t, "WHERE c.review_flag = 'CONFLICT'", "WHERE false AND c.review_flag = 'CONFLICT'", "conflict")),
    ("stale-approval blocker removed", lambda t: mutate(t, "WHERE NOT a.is_fresh AND EXISTS (SELECT 1 FROM catalog_promoted.evidence_grid", "WHERE false AND NOT a.is_fresh AND EXISTS (SELECT 1 FROM catalog_promoted.evidence_grid", "stale")),
    ("duplicate-course blocker removed", lambda t: mutate(t, "WHERE EXISTS (SELECT 1 FROM catalog_api.course_offering o WHERE o.term_code = g.term_code AND o.course_id = g.course_id)", "WHERE false", "dupcourse")),
    ("credit-exclusion blocker removed", lambda t: mutate(t, "WHERE NOT EXISTS (SELECT 1 FROM catalog_promoted.capability c WHERE c.name = 'CREDIT_EXCLUSION_ENFORCED' AND c.enabled)", "WHERE false", "exclusion")),
    ("stale approvals accepted at insert", lambda t: mutate(t, "IF NEW.evidence_fingerprint <> catalog_promoted.evidence_fingerprint(NEW.term_code, NEW.course_id) THEN", "IF false THEN", "fingerprint")),
    ("standalone needs no supporting evidence", lambda t: mutate(t, "IF NEW.supporting_source_id IS NULL THEN\n\t\t\tRAISE EXCEPTION 'standalone status cannot be inferred", "IF false THEN\n\t\t\tRAISE EXCEPTION 'standalone status cannot be inferred", "standalonesupport")),
    ("lab credits need no supporting evidence", lambda t: mutate(t, "(sec.schedule_type <> 'Lecture' OR sec.grid_hours IS DISTINCT FROM sec.details_credit_hours", "(false OR sec.grid_hours IS DISTINCT FROM sec.details_credit_hours", "labcredits")),
    ("lab count needs no supporting evidence", lambda t: mutate(t, "IF NEW.supporting_source_id IS NULL THEN\n\t\t\tRAISE EXCEPTION 'the required lab count", "IF false THEN\n\t\t\tRAISE EXCEPTION 'the required lab count", "countsupport")),
    ("requirements can be approved away", lambda t: mutate(t, "IF NEW.approved_value = 'NONE_ENFORCEABLE' AND nonempty_kinds IS NOT NULL THEN", "IF false THEN", "approveaway")),
    ("served without checking the package still matches", lambda t: mutate(t, "WHERE r.blocker_count = 0 AND cp.package_sha256 = catalog_promoted.package_sha(cp.term_code, cp.course_id);", "WHERE r.blocker_count = 0;", "servesha")),
    ("served even with blockers", lambda t: mutate(t, "WHERE r.blocker_count = 0 AND cp.package_sha256 = catalog_promoted.package_sha(cp.term_code, cp.course_id);", "WHERE cp.package_sha256 = catalog_promoted.package_sha(cp.term_code, cp.course_id);", "serveblock")),
    ("promote_course is not idempotent", lambda t: mutate(t, "\t\tRETURN latest.promotion_id;  -- idempotent: this exact package is already the current promotion", "\t\tNULL;", "idempotent")),
    ("promote_course ignores blockers", lambda t: mutate(t, "IF r.blocker_count > 0 THEN", "IF false THEN", "ignoreblockers")),
    ("append-only protection removed", lambda t: mutate(t, "\tRAISE EXCEPTION 'catalog_promoted.% is append-only: % is not allowed (record a new row instead)', TG_TABLE_NAME, TG_OP\n\t\tUSING ERRCODE = 'integrity_constraint_violation';", "\tRETURN NULL;", "appendonly")),
    ("automated-sounding approvers accepted", lambda t: mutate(t, "AND approved_by !~* '(^|[^a-z])(script|bot|claude|auto|automatic|collector|system)([^a-z]|$)'),\n\trationale", "),\n\trationale", "automation")),
    ("lab links not checked against Banner's list", lambda t: mutate(t, "IF sec.schedule_type <> 'Lecture' OR listed IS DISTINCT FROM NEW.approved_value THEN", "IF false THEN", "linkslist")),
]


def main():
    skip_mutations = "--no-mutations" in sys.argv
    db = os.environ.get("PGDATABASE", "")
    if "cse007_local" in db and os.environ.get("ALLOW_SHARED_DATABASE") != "yes":
        sys.exit("refusing to run against the shared database (" + db + "). Use a separate test database.")
    before_catalog = query("SELECT coalesce(md5(string_agg(c.relname || pg_get_viewdef(c.oid), ',' ORDER BY c.relname)), 'none') FROM pg_class c "
                           "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'catalog_api' AND c.relkind = 'v'")
    before_evidence = query("SELECT count(*) FROM banner_evidence.evidence_source")
    before_promotions = query("SELECT count(*) FROM catalog_promoted.promotion") if query("SELECT to_regclass('catalog_promoted.promotion') IS NOT NULL") == "t" else "0"
    before_approvals = query("SELECT count(*) FROM catalog_promoted.approval") if query("SELECT to_regclass('catalog_promoted.approval') IS NOT NULL") == "t" else "0"
    print("== 1. Install (one committing transaction), then reinstall")
    code, out = psql(["-f", str(HERE / "install.sql")])
    check(code == 0 and "PASS: catalog_promoted" in out, "install.sql committed with its structure checks")
    fp1 = query("SELECT md5(string_agg(pg_get_functiondef(p.oid), '|' ORDER BY p.proname)) FROM pg_proc p WHERE p.pronamespace IN ('catalog_promoted'::regnamespace, 'catalog_live'::regnamespace)")
    views1 = query("SELECT md5(string_agg(c.relname || pg_get_viewdef(c.oid), '|' ORDER BY c.relnamespace, c.relname)) FROM pg_class c WHERE c.relnamespace IN ('catalog_promoted'::regnamespace, 'catalog_live'::regnamespace) AND c.relkind = 'v'")
    code, out = psql(["-f", str(HERE / "install.sql")])
    fp2 = query("SELECT md5(string_agg(pg_get_functiondef(p.oid), '|' ORDER BY p.proname)) FROM pg_proc p WHERE p.pronamespace IN ('catalog_promoted'::regnamespace, 'catalog_live'::regnamespace)")
    views2 = query("SELECT md5(string_agg(c.relname || pg_get_viewdef(c.oid), '|' ORDER BY c.relnamespace, c.relname)) FROM pg_class c WHERE c.relnamespace IN ('catalog_promoted'::regnamespace, 'catalog_live'::regnamespace) AND c.relkind = 'v'")
    check(code == 0 and fp1 == fp2 and views1 == views2, "reinstall is safe and every function and view definition is identical")

    print("\n== 2. Scenarios on synthetic courses (each always rolled back)")
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        total = 0
        for name, body, _ in scenarios():
            expected = expected_probes(body)
            code, out, passed = execute(run_scenario(body))
            ok = code == 0 and passed == expected
            print(f"  {'ok:   ' if ok else 'FAIL: '}{name} ({passed} probes{'' if passed == expected else f', expected {expected}'})")
            if not ok:
                print("\n".join(l for l in out.splitlines() if "probe failed" in l or "ERROR" in l or "DETAIL" in l)[:1200])
                sys.exit("FAILED: scenario did not hold: " + name)
            total += passed
        print(f"  all scenarios held: {total} probes")

        if not skip_mutations:
            print("\n== 3. Mutation checks: each weakened gate must make a scenario fail")
            scenario_list = scenarios()
            for label, change in MUTATIONS:
                mutated = work / "_mutated_v1.sql"
                mutated.write_text(change(V1), encoding="utf-8")
                caught = False
                for name, body, _ in scenario_list:
                    expected = expected_probes(body)
                    sql = run_scenario(body, install_first=f"\\i {mutated}\n\\i {V2}\n")
                    code, out, passed = execute(sql)
                    if code != 0 or passed != expected:
                        caught = True
                        break
                print(f"  {'caught ' if caught else 'MISSED '} {label}")
                if not caught:
                    sys.exit("FAILED: a weakened gate went unnoticed: " + label)

    print("\n== 4. Nothing outside the promotion layer changed")
    after_catalog = query("SELECT coalesce(md5(string_agg(c.relname || pg_get_viewdef(c.oid), ',' ORDER BY c.relname)), 'none') FROM pg_class c "
                          "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'catalog_api' AND c.relkind = 'v'")
    check(after_catalog == before_catalog, "the five catalog_api views are unchanged")
    check(query("SELECT count(*) FROM banner_evidence.evidence_source") == before_evidence, "no evidence row was added or changed by the scenarios")
    check(query("SELECT count(*) FROM catalog_promoted.promotion") == before_promotions and query("SELECT count(*) FROM catalog_promoted.approval") == before_approvals,
          "no promotion or approval persisted from the scenarios (all rolled back; rows that existed before the run, such as the synthetic fixture, are unchanged)")
    print("\nALL PROMOTION CHECKS PASSED")


if __name__ == "__main__":
    main()
