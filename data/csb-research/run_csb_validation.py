"""Validates catalog_csb on a SCRATCH database it creates and drops (stdlib only; needs psql and a local role that can create databases).

    PGHOST=127.0.0.1 PGPORT=<local port> PGUSER=<local superuser> python3 -I data/csb-research/run_csb_validation.py

Everything runs on SYNTHETIC data (synthetic_fixture.py: fake CRNs 99xxx, invented names). Scenarios:
  install and reinstall; import; idempotent rerun; isolation (a stub catalog_research is byte-for-byte unchanged and a CRN collision is refused);
  tampering probes (every normalized row that disagrees with the verbatim grid, every wrong link status, every impossible row is refused or reported);
  read-only reader role; the coverage views over the stub; then MUTATION CHECKS: one rule is weakened at a time (a CHECK dropped, a cross-check view
  emptied) and the probe that depends on it must stop failing, so a rule that silently stopped working would be noticed.
It refuses the shared database names and port and any non-loopback host. Prints verdicts only.
"""
import copy
import hashlib
import json
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import synthetic_fixture as fx  # noqa: E402

SHARED_DATABASES = {"registration_cse007_local", "registration_unified_spring2027"}
SHARED_PORTS = {"55433"}
PROGRAM = json.loads((HERE / "csb_program_2026_27.json").read_text(encoding="utf-8"))
RESULTS = []


def env():
    if os.environ.get("PGPORT", "") in SHARED_PORTS or os.environ.get("PGHOST", "127.0.0.1") not in ("127.0.0.1", "localhost", "::1", ""):
        sys.exit("refused: shared port or non-loopback host")
    return dict(os.environ)


def psql(database, sql=None, file=None, check=True):
    cmd = ["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At", "-F", "|", "-d", database]
    cmd += ["-f", str(file)] if file else []
    r = subprocess.run(cmd, input=None if file else sql, capture_output=True, text=True, env=env(), timeout=300)
    if check and r.returncode != 0:
        raise RuntimeError(f"psql failed in {database}: {(r.stdout + r.stderr).strip()[:600]}")
    return r.returncode, (r.stdout + r.stderr).strip()


def lit(text):
    n = 0
    while f"$v{n}$" in text:
        n += 1
    return f"$v{n}${text}$v{n}$"


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""))


def import_sql(payload, label="synthetic validation import"):
    j = json.dumps(payload, sort_keys=True)
    return f"SELECT catalog_csb.import_csb({lit(j)}::jsonb, {lit(label)}, '{hashlib.sha256(j.encode()).hexdigest()}')"


STUB = """
CREATE SCHEMA catalog_research;
CREATE TABLE catalog_research.offering (term_code text, offering_id text, course_id text, data_source text, PRIMARY KEY (term_code, offering_id));
CREATE TABLE catalog_research.section (term_code text, crn text, offering_id text, component_type text, PRIMARY KEY (term_code, crn));
CREATE TABLE catalog_research.grid_search_result (term_code text, course text, result text);
CREATE VIEW catalog_research.offering_tier AS SELECT term_code, offering_id, 'BLOCKED'::text AS tier FROM catalog_research.offering;
INSERT INTO catalog_research.offering VALUES ('202710', 'PHY-011', 'PHY-011', 'BANNER_GRID_STAGING'), ('202710', 'CHM-030', 'CHM-030', 'BANNER_GRID_STAGING');
INSERT INTO catalog_research.section VALUES ('202710', '98001', 'PHY-011', 'LECTURE'), ('202710', '98002', 'PHY-011', 'RECITATION'), ('202710', '98003', 'CHM-030', 'LECTURE');
INSERT INTO catalog_research.grid_search_result VALUES ('202710', 'PHY 010', '0 classes');
"""


def main():
    env()
    name = "csb_validation_" + uuid.uuid4().hex[:8]
    if name in SHARED_DATABASES:
        sys.exit("refused")
    psql("postgres", f"CREATE DATABASE {name}")
    created = [name]
    try:
        run(name, created)
    finally:
        for db in created:
            psql("postgres", f"DROP DATABASE IF EXISTS {db} WITH (FORCE)", check=False)
    failed = [n for n, ok in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)} of {len(RESULTS)} checks passed" + (": FAILED " + ", ".join(failed) if failed else ""))
    sys.exit(1 if failed else 0)


def tamper(base, fn):
    p = copy.deepcopy(base)
    fn(p)
    return p


def run(db, created):
    base = fx.make_payload(PROGRAM)

    # ---- install, reinstall, import ----------------------------------------------------------------------------------------------
    psql(db, STUB)                                                    # an existing schema that must never be touched
    rc, out = psql(db, file=HERE / "install.sql", check=False)
    check("default install (V1, V2, structure checks) succeeds next to a catalog_research", rc == 0, out[-300:])
    reads_outside = ("SELECT count(*) FROM pg_depend d JOIN pg_rewrite r ON r.oid = d.objid AND d.classid = 'pg_rewrite'::regclass JOIN pg_class v ON v.oid = r.ev_class "
                     "JOIN pg_namespace vn ON vn.oid = v.relnamespace JOIN pg_class t ON t.oid = d.refobjid JOIN pg_namespace tn ON tn.oid = t.relnamespace "
                     "WHERE vn.nspname = 'catalog_csb' AND tn.nspname NOT IN ('catalog_csb', 'pg_catalog', 'information_schema')")
    check("the default install depends on NOTHING outside catalog_csb and installs no coverage view",
          psql(db, reads_outside)[1] == "0" and psql(db, "SELECT to_regclass('catalog_csb.course_coverage') IS NULL")[1] == "t")
    rc, out = psql(db, file=HERE / "install_coverage_views.sql", check=False)
    check("the optional coverage views install as a separate step", rc == 0 and psql(db, "SELECT to_regclass('catalog_csb.course_coverage') IS NOT NULL")[1] == "t", out[-300:])
    check("only then does catalog_csb read catalog_research (5 views over it)", int(psql(db, reads_outside)[1]) >= 5)
    rc, out = psql(db, file=HERE / "install.sql", check=False)
    check("reinstall is safe (also with the coverage views present) and keeps the structure checks passing", rc == 0, out[-300:])
    removal = ("DROP VIEW catalog_csb.ns_sequence_dependency, catalog_csb.ns_sequence_status, catalog_csb.ns_sequence_course_status, catalog_csb.course_coverage, catalog_csb.course_availability; "
               "DROP FUNCTION catalog_csb.expression_satisfied(jsonb, text[]), catalog_csb.expression_courses(jsonb);")
    rc, out = psql(db, removal, check=False)
    rc2, out2 = psql(db, file=HERE / "verify_structure.sql", check=False)
    check("the documented removal of the coverage views leaves a valid catalog_csb that depends on nothing outside",
          rc == 0 and rc2 == 0 and psql(db, reads_outside)[1] == "0", (out + out2)[-300:])
    rc, out = psql(db, file=HERE / "install_coverage_views.sql", check=False)
    check("the coverage views can be installed again", rc == 0, out[-300:])
    rc, out = psql(db, "ALTER TABLE catalog_research.offering ALTER COLUMN course_id TYPE varchar(40)", check=False)
    rc2, out2 = psql(db, "DROP TABLE catalog_research.section", check=False)
    check("with the coverage views installed, a type change or DROP of the catalog_research objects they read is REFUSED (the documented coupling)",
          rc != 0 and "used by a view" in out and rc2 != 0 and "depend" in out2, out + out2)
    fp_before = psql(db, file=HERE / "fingerprint_other_schemas.sql")[1]
    rc, out = psql(db, import_sql(base), check=False)
    check("synthetic import succeeds", rc == 0, out[-400:])
    counts = psql(db, "SELECT (SELECT count(*) FROM catalog_csb.section), (SELECT count(*) FROM catalog_csb.offering), (SELECT count(*) FROM catalog_csb.section_meeting WHERE meeting_kind='WEEKLY'), "
                      "(SELECT count(*) FROM catalog_csb.section_meeting WHERE meeting_kind='DATED_ONE_TIME'), (SELECT count(*) FROM catalog_csb.section_instructor), (SELECT count(*) FROM catalog_csb.section_link_option), "
                      "(SELECT count(*) FROM catalog_csb.grid_mismatch), (SELECT count(*) FROM catalog_csb.link_status_mismatch)")[1]
    check("stored counts: 8 sections, 5 offerings, 10 weekly + 1 dated meeting, 9 instructor entries, 4 link listings, both cross-check views empty", counts == "8|5|10|1|9|4|0|0", counts)
    fp_after = psql(db, file=HERE / "fingerprint_other_schemas.sql")[1]
    check("a stub catalog_research is identical after the import (isolation)", fp_before == fp_after)

    def fingerprint():
        return psql(db, "SELECT md5(string_agg(x, '|' ORDER BY x COLLATE \"C\")) FROM ("
                        "SELECT row(s.*)::text AS x FROM catalog_csb.section s "
                        "UNION ALL SELECT row(m.term_code, m.crn, m.day_of_week, m.start_time, m.end_time, m.location, m.meeting_type, m.meeting_kind, m.start_date, m.end_date)::text FROM catalog_csb.section_meeting m "
                        "UNION ALL SELECT row(i.*)::text FROM catalog_csb.section_instructor i UNION ALL SELECT row(l.*)::text FROM catalog_csb.section_link_option l "
                        "UNION ALL SELECT row(d.*)::text FROM catalog_csb.section_detail d "
                        "UNION ALL SELECT row(c.course_id, c.title, c.credits_text, c.prerequisite_status)::text FROM catalog_csb.catalog_course c) q")[1]
    f1 = fingerprint()
    runs1 = psql(db, "SELECT count(*) FROM catalog_csb.import_run")[1]
    psql(db, import_sql(base))
    check("rerunning the import gives identical content (idempotent)", fingerprint() == f1)
    check("each import adds exactly one append-only import_run row", int(psql(db, "SELECT count(*) FROM catalog_csb.import_run")[1]) == int(runs1) + 1)
    # the importer's content fingerprint must not depend on the database collation (the team database is C.UTF-8; an earlier copy was en_US.UTF-8)
    import_csb_src = (HERE / "import_csb.py").read_text(encoding="utf-8")
    fingerprint_sql = import_csb_src.split('FINGERPRINT_SQL = """')[1].split('"""')[0]
    other = db + "_collation"
    rc, out = psql("postgres", f"CREATE DATABASE {other} TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'en_US.UTF-8' LC_CTYPE 'en_US.UTF-8'", check=False)
    if rc != 0:
        print("  SKIP  fingerprint under a second collation (en_US.UTF-8 is not available here):", out[-120:])
    else:
        created.append(other)
        psql(other, STUB)
        psql(other, file=HERE / "install.sql")
        psql(other, import_sql(base))
        collations = psql("postgres", f"SELECT datcollate FROM pg_database WHERE datname IN ('{db}', '{other}') ORDER BY datname")[1].split("\n")
        check("the content fingerprint is identical in databases with different collations", collations[0] != collations[1]
              and psql(db, fingerprint_sql)[1] == psql(other, fingerprint_sql)[1], str(collations))

    # ---- the stored facts -----------------------------------------------------------------------------------------------------------
    q = lambda sql: psql(db, sql)[1]
    check("a linked recitation shows 0 in the grid, the bundled 4 in Class Details, and is flagged rather than trusted",
          q("SELECT credits_text || '/' || details_credit_hours_text || '/' || credits_display_status FROM catalog_csb.section WHERE crn='99011'") == "0/4/DETAILS_DIFFER_FROM_GRID")
    check("package credits count the lecture once (members add 0)", q("SELECT string_agg(package_credits::text, ',' ORDER BY option_no) FROM catalog_csb.package_credit WHERE lecture_crn='99010'") == "4.00,4.00")
    check("linked options: two alternatives, each one recitation; panel does not state that registration is mandatory",
          q("SELECT option_count || '|' || registration_shape || '|' || requirement_basis FROM catalog_csb.component_requirement WHERE crn='99010'")
          == "2|CHOOSE_ONE_OF_2_LISTED_OPTIONS_EACH: Recitation|OPTIONS_LISTED_BY_PANEL_MANDATORY_REGISTRATION_NOT_STATED")
    check("all four linked sections are CONFIRMED_BOTH_SIDES, the rest NO_LINKS_LISTED",
          q("SELECT string_agg(crn || ':' || link_status, ',' ORDER BY crn) FROM catalog_csb.section WHERE link_status <> 'NO_LINKS_LISTED'") == "99010:CONFIRMED_BOTH_SIDES,99011:CONFIRMED_BOTH_SIDES,99012:CONFIRMED_BOTH_SIDES")
    check("an exam is a dated one-time row (3 March 2027), not a weekly slot", q("SELECT meeting_type || '|' || start_date FROM catalog_csb.section_meeting WHERE meeting_kind='DATED_ONE_TIME'") == "Common Hour Exam|2027-03-03")
    check("the arranged meeting is stored as arranged, with no timetable row", q("SELECT (SELECT count(*) FROM catalog_csb.section_arranged_meeting WHERE crn='99030') || '|' || (SELECT count(*) FROM catalog_csb.section_meeting WHERE crn='99030')") == "1|0")
    check("a variable-credit course keeps '1 TO 3' as a range", q("SELECT credits_text || '|' || credits_min || '|' || credits_max || '|' || credits_status FROM catalog_csb.offering WHERE offering_id='CSB-392'") == "1 TO 3|1.00|3.00|VARIABLE_RANGE")
    check("the placeholder instructor is flagged, and no professor is invented for it", q("SELECT instructor_name || '|' || is_placeholder FROM catalog_csb.section_instructor WHERE crn='99010'") == "Staff, Teaching|true")
    check("Banner text stays verbatim and TEXT_ONLY creates no rule", q("SELECT prerequisite_status || '|' || (SELECT count(*) FROM catalog_csb.section_rule r WHERE r.crn = d.crn AND r.rule_kind = 'PREREQUISITE') FROM catalog_csb.section_detail d WHERE crn='99040'") == "TEXT_ONLY|0")
    check("prerequisites keep their AND/OR groups", q("SELECT expression::text FROM catalog_csb.section_rule WHERE crn='99001' AND rule_kind='PREREQUISITE'") ==
          '{"op": "AND", "args": [{"op": "OR", "args": [{"course": "ACCT-108"}, {"course": "ACCT-152"}]}, {"op": "OR", "args": [{"course": "CSE-241"}, {"course": "CSE-341"}]}]}')
    check("catalog and Banner statements are both kept, and a conflict is flagged", q("SELECT status || '|' || (catalog_text IS NOT NULL) || '|' || (banner_text IS NOT NULL) FROM catalog_csb.prerequisite_comparison WHERE course_id='PHY-021'") == "CONFLICT|true|true")
    check("zero-result searches are NOT_OFFERED_IN_SPRING_2027 for the exact code", q("SELECT term_status FROM catalog_csb.course_term_status WHERE course_id='CSB-010'") == "NOT_OFFERED_IN_SPRING_2027")
    check("every professional elective candidate is APPROVAL_NOT_VERIFIED; only the catalog's CSE list is CATALOG_LISTED",
          q("SELECT string_agg(x, ',' ORDER BY x COLLATE \"C\") FROM (SELECT DISTINCT l.list_key || ':' || m.member_status AS x FROM catalog_csb.elective_list l JOIN catalog_csb.elective_list_member m USING (list_key)) t")
          == "CSB_PROFESSIONAL_ELECTIVES:APPROVAL_NOT_VERIFIED,CS_ELECTIVE_LIST:CATALOG_LISTED")
    check("the degree totals 136 credits and its structure has no problem", q("SELECT p.total_credits || '|' || (SELECT sum(credits) FROM catalog_csb.requirement_group g WHERE g.program_id = p.program_id AND parent_group_id IS NULL) || '|' || (SELECT count(*) FROM catalog_csb.program_problem WHERE problem <> 'NS_SEQUENCE_COURSE_NOT_NS_DESIGNATED') FROM catalog_csb.program p") == "136.0|136.00|0")
    check("categories (HE/AL, SW, free, NW) hold no invented course", q("SELECT count(*) FROM catalog_csb.requirement_option o JOIN catalog_csb.requirement_group g USING (group_id) WHERE g.group_key IN ('HE_AL_ELECTIVES', 'SW_ELECTIVES', 'FREE_ELECTIVES_6', 'NW_12_CREDITS')") == "0")

    # ---- tampering: a payload that disagrees with the verbatim data is refused ------------------------------------------------------
    def refused(label, mutate, needle):
        rc, out = psql(db, import_sql(tamper(base, mutate)), check=False)
        check(f"import refused: {label}", rc != 0 and needle in out, out[-250:])

    def sec(p, crn):
        return next(s for s in p["sections"] if s["crn"] == crn)
    refused("a meeting start time that differs from the grid string", lambda p: sec(p, "99001")["meetings"][0].update(start="09:30"), "disagrees with the verbatim grid")
    refused("a weekday that differs from the grid string", lambda p: sec(p, "99001")["meetings"][0].update(day=2), "disagrees with the verbatim grid")
    refused("an exam stored as weekly", lambda p: sec(p, "99002")["meetings"][-1].update(kind="WEEKLY", endDate="2027-05-07"), "disagrees with the verbatim grid")
    refused("a section whose credit hours differ from the grid", lambda p: sec(p, "99001").update(creditsText="4", creditsMin=4, creditsMax=4), "disagrees with the verbatim grid")
    refused("an instructor name that differs from the grid", lambda p: sec(p, "99001")["instructors"][0].update(name="Not, Listed"), "disagrees with the verbatim grid")
    refused("an instructor dropped", lambda p: sec(p, "99002")["instructors"].pop(), "disagrees with the verbatim grid")
    refused("a section's schedule type changed", lambda p: sec(p, "99011").update(componentType="LAB", scheduleType="Lab"), "disagrees with the verbatim grid")
    refused("an arranged meeting dropped", lambda p: sec(p, "99030").update(arranged=[]), "disagrees with the verbatim grid")
    refused("a link status that the panels contradict", lambda p: sec(p, "99010").update(linkStatus="NO_LINKS_LISTED"), "disagree with the Linked Sections panels")
    refused("a link listing removed from one side only", lambda p: sec(p, "99010").update(links=[k for k in sec(p, "99010")["links"] if k["memberCrn"] != "99012"]), "disagree with the Linked Sections panels")
    refused("a link to a CRN that is not stored", lambda p: sec(p, "99010")["links"].append({"optionNo": 3, "memberCrn": "99555", "memberScheduleType": "Recitation", "memberSection": "1"}), "violates foreign key")
    refused("a duplicate CRN", lambda p: p["sections"].append(copy.deepcopy(sec(p, "99001"))), "duplicate key")
    refused("a section under an offering that does not exist", lambda p: sec(p, "99001").update(offeringId="XXX-000"), "violates foreign key")
    refused("a degree whose categories do not sum to the total", lambda p: p["groups"][0].update(credits=p["groups"][0]["credits"] + 1), "structurally inconsistent")
    refused("a professional elective candidate marked CATALOG_LISTED", lambda p: next(l for l in p["lists"] if l["key"] == "CSB_PROFESSIONAL_ELECTIVES")["members"][0].update(memberStatus="CATALOG_LISTED"), "violates check constraint")
    refused("a CS-list member marked APPROVAL_NOT_VERIFIED", lambda p: next(l for l in p["lists"] if l["key"] == "CS_ELECTIVE_LIST")["members"][0].update(memberStatus="APPROVAL_NOT_VERIFIED"), "violates check constraint")
    refused("an authenticated-Banner source marked redistributable", lambda p: p["sources"][1].update(level="BANNER_AUTHENTICATED", redistribution="PUBLIC"), "violates check constraint")
    refused("an instructor flagged as a placeholder whose name is not one", lambda p: sec(p, "99001")["instructors"][0].update(placeholder=True), "violates check constraint")
    refused("a payload missing its arrays", lambda p: p.pop("sections"), "payload needs")
    refused("a section that is also in catalog_research", lambda p: sec(p, "99011").update(crn="98002") or [k.update(memberCrn="98002") for s in p["sections"] for k in s["links"] if k["memberCrn"] == "99011"], "already exist in catalog_research")

    # ---- append-only history, read-only views, reader role -------------------------------------------------------------------------
    check("import_run refuses UPDATE", psql(db, "UPDATE catalog_csb.import_run SET input_label = 'changed label'", check=False)[0] != 0)
    check("import_run refuses DELETE", psql(db, "DELETE FROM catalog_csb.import_run", check=False)[0] != 0)
    check("import_run refuses TRUNCATE", psql(db, "TRUNCATE catalog_csb.import_run", check=False)[0] != 0)
    check("a cross-check view cannot be written", psql(db, "INSERT INTO catalog_csb.grid_mismatch VALUES ('202710', '1', 'x', 'x', 'x')", check=False)[0] != 0)
    psql(db, "CREATE ROLE csb_validation_reader NOLOGIN")
    rc = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-v", "reader=csb_validation_reader", "-d", db, "-f", str(HERE / "grant_reader.sql")],
                        capture_output=True, text=True, env=env()).returncode
    check("grant_reader.sql runs", rc == 0)
    can_read = psql(db, "SET ROLE csb_validation_reader; SELECT count(*) FROM catalog_csb.section", check=False)
    check("the reader role can read catalog_csb", can_read[0] == 0 and can_read[1].endswith("8"), can_read[1])
    check("the reader role cannot write", psql(db, "SET ROLE csb_validation_reader; DELETE FROM catalog_csb.section", check=False)[0] != 0)
    check("the reader role cannot run the importer", psql(db, "SET ROLE csb_validation_reader; SELECT catalog_csb.import_csb('{}'::jsonb, 'x', 'y')", check=False)[0] != 0)
    check("the reader role cannot read the stub catalog_research (no grant there)", psql(db, "SET ROLE csb_validation_reader; SELECT 1 FROM catalog_research.offering", check=False)[0] != 0)
    psql(db, "REVOKE ALL ON ALL TABLES IN SCHEMA catalog_csb FROM csb_validation_reader; REVOKE USAGE ON SCHEMA catalog_csb FROM csb_validation_reader; "
             f"REVOKE CONNECT ON DATABASE {db} FROM csb_validation_reader; DROP ROLE csb_validation_reader")

    # ---- direct damage to stored rows is caught by the views ----------------------------------------------------------------------
    def damaged(label, sql, query, needle):
        rc, out = psql(db, f"BEGIN; {sql}; {query}; ROLLBACK;", check=False)
        check(f"stored-row damage is reported: {label}", rc == 0 and needle in out, out[-250:])
    mismatch = "SELECT string_agg(DISTINCT problem, ',') FROM catalog_csb.grid_mismatch"
    damaged("a meeting moved an hour", "UPDATE catalog_csb.section_meeting SET start_time = start_time + interval '1 hour', end_time = end_time + interval '1 hour' WHERE crn = '99001'", mismatch, "WEEKLY_MEETING_NOT_IN_GRID")
    damaged("an exam deleted", "DELETE FROM catalog_csb.section_meeting WHERE meeting_kind = 'DATED_ONE_TIME'", mismatch, "GRID_DATED_MEETING_NOT_STORED")
    damaged("a credit changed", "UPDATE catalog_csb.section SET credits_text = '2', credits_min = 2, credits_max = 2 WHERE crn = '99001'", mismatch, "SECTION_FIELD_DIFFERS")
    damaged("an instructor renamed", "UPDATE catalog_csb.section_instructor SET instructor_name = 'Other, Name' WHERE crn = '99001'", mismatch, "INSTRUCTORS_DIFFER")
    damaged("a link option deleted from one side", "DELETE FROM catalog_csb.section_link_option WHERE panel_crn = '99010' AND member_crn = '99012'",
            "SELECT string_agg(crn || ':' || derived, ',' ORDER BY crn) FROM catalog_csb.link_status_mismatch", "99010:LISTED_BY_ONE_SIDE_ONLY")

    # ---- the coverage views over the stub ------------------------------------------------------------------------------------------
    check("coverage: PHY-011 is in catalog_research and not touched; PHY-021 and CSB-310 are CSB research; CSB-010 was searched with no result; PHY-010 was searched by catalog_research",
          q("SELECT string_agg(course_id || '=' || coverage, ',' ORDER BY course_id) FROM catalog_csb.course_coverage WHERE course_id IN ('PHY-011', 'PHY-021', 'CSB-310', 'CSB-010', 'PHY-010')")
          == "CSB-010=CSB_RESEARCH_SEARCHED_NOT_OFFERED,CSB-310=CSB_RESEARCH_OFFERED,PHY-010=CATALOG_RESEARCH_SEARCHED_NOT_OFFERED,PHY-011=IN_CATALOG_RESEARCH_OFFERED,PHY-021=CSB_RESEARCH_OFFERED")
    check("sequence PHY 011 + PHY 021 is COMPLETE (one course from each research set)", q("SELECT status FROM catalog_csb.ns_sequence_status WHERE sequence_key = 'PHYS_PHY011_PHY021'") == "COMPLETE_ALL_COURSES_OFFERED")
    check("a sequence containing a course nobody searched is UNKNOWN, never COMPLETE", q("SELECT status FROM catalog_csb.ns_sequence_status WHERE sequence_key = 'PHYS_PHY010_PHY013'") == "UNKNOWN_SOME_COURSES_NOT_SEARCHED")
    check("expression_satisfied handles AND / OR groups",
          q("SELECT catalog_csb.expression_satisfied('{\"op\":\"AND\",\"args\":[{\"op\":\"OR\",\"args\":[{\"course\":\"A-001\"},{\"course\":\"A-002\"}]},{\"course\":\"B-001\"}]}'::jsonb, ARRAY['A-002','B-001']), "
            "catalog_csb.expression_satisfied('{\"op\":\"AND\",\"args\":[{\"op\":\"OR\",\"args\":[{\"course\":\"A-001\"},{\"course\":\"A-002\"}]},{\"course\":\"B-001\"}]}'::jsonb, ARRAY['A-002'])") == "t|f")

    # ---- mutation checks: weaken one rule at a time; the probe that depends on it must stop failing -------------------------------
    def mutation(label, mutate_sql, mutated_payload, needle):
        scratch = "csb_mut_" + uuid.uuid4().hex[:8]
        psql("postgres", f"CREATE DATABASE {scratch} TEMPLATE {db}")
        created.append(scratch)
        psql(scratch, mutate_sql)
        rc, out = psql(scratch, import_sql(mutated_payload), check=False)
        check(f"mutation caught (the probe stops failing once the rule is weakened): {label}", rc == 0, out[-200:])
        psql("postgres", f"DROP DATABASE IF EXISTS {scratch} WITH (FORCE)", check=False)
    mutation("link cross-check emptied", "CREATE OR REPLACE VIEW catalog_csb.link_status_mismatch AS SELECT NULL::text AS term_code, NULL::text AS crn, NULL::text AS stored, NULL::text AS derived WHERE false",
             tamper(base, lambda p: sec(p, "99010").update(linkStatus="NO_LINKS_LISTED")), "")
    mutation("grid cross-check emptied", "CREATE OR REPLACE VIEW catalog_csb.grid_mismatch AS SELECT NULL::text AS term_code, NULL::text AS crn, NULL::text AS problem, NULL::text AS stored, NULL::text AS grid WHERE false",
             tamper(base, lambda p: sec(p, "99001")["meetings"][0].update(start="09:30")), "")
    drop_checks = ("DO $$ DECLARE c record; BEGIN FOR c IN SELECT conrelid::regclass AS t, conname FROM pg_constraint WHERE contype = 'c' AND conrelid = '%s'::regclass AND pg_get_constraintdef(oid) ~ '%s' "
                   "LOOP EXECUTE format('ALTER TABLE %%s DROP CONSTRAINT %%I', c.t, c.conname); END LOOP; END $$;")
    empty_problem = "CREATE OR REPLACE VIEW catalog_csb.program_problem AS SELECT NULL::text AS program_id, NULL::text AS subject, NULL::text AS problem, NULL::text AS detail WHERE false"
    promoted = tamper(base, lambda p: next(l for l in p["lists"] if l["key"] == "CSB_PROFESSIONAL_ELECTIVES")["members"][0].update(memberStatus="CATALOG_LISTED"))
    scratch = "csb_mut_" + uuid.uuid4().hex[:8]
    psql("postgres", f"CREATE DATABASE {scratch} TEMPLATE {db}")
    created.append(scratch)
    psql(scratch, drop_checks % ("catalog_csb.elective_list_member", "CS_ELECTIVE_LIST"))
    rc, out = psql(scratch, import_sql(promoted), check=False)
    check("defense in depth: with the table CHECK dropped, program_problem still refuses an 'approved' professional elective", rc != 0 and "ELECTIVE_APPROVAL_STATUS_NOT_UNVERIFIED" in out, out[-250:])
    psql("postgres", f"DROP DATABASE IF EXISTS {scratch} WITH (FORCE)", check=False)
    mutation("elective approval CHECK and view both weakened", drop_checks % ("catalog_csb.elective_list_member", "CS_ELECTIVE_LIST") + "; " + empty_problem, promoted, "")
    mutation("source redistribution CHECK dropped", drop_checks % ("catalog_csb.source", "BANNER_AUTHENTICATED"),
             tamper(base, lambda p: p["sources"][1].update(level="BANNER_AUTHENTICATED", redistribution="PUBLIC")), "")
    mutation("program credit check removed", "CREATE OR REPLACE VIEW catalog_csb.program_problem AS SELECT NULL::text AS program_id, NULL::text AS subject, NULL::text AS problem, NULL::text AS detail WHERE false",
             tamper(base, lambda p: p["groups"][0].update(credits=p["groups"][0]["credits"] + 1)), "")
    guard_removed = psql(db, "SELECT pg_get_functiondef('catalog_csb.import_csb(jsonb,text,text)'::regprocedure)")[1]
    guard_removed = re.sub(r"\tIF to_regclass\('catalog_research.section'\) IS NOT NULL THEN.*?\n\tEND IF;\n", "", guard_removed, count=1, flags=re.S)
    assert "catalog_research.section" not in guard_removed, "the guard block was not found in the function definition"
    collide = tamper(base, lambda p: sec(p, "99011").update(crn="98002") or [k.update(memberCrn="98002") for s in p["sections"] for k in s["links"] if k["memberCrn"] == "99011"])
    mutation("CRN collision guard removed", guard_removed, collide, "")


if __name__ == "__main__":
    main()
