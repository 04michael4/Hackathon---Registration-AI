"""Verifies the REAL CSB data stored in catalog_csb against the raw collected inputs, independently of the importer (stdlib only; needs `psql`; READ-ONLY).

    export PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<role that can read> PGDATABASE=<database>
    python3 -I data/csb-research/verify_csb_database.py --grids ~/.registration-csb/grids --packs ~/.registration-csb/packs \
        --catalog-cache ~/.registration-csb/catalog [--other-schemas-baseline FILE | --save-other-schemas-baseline FILE] [--save-fingerprint F | --compare-fingerprint F]

It rebuilds what the database must contain from the grid files, the Class Details packs, the catalog pages and the program file, and compares: every
mandatory CSB course has a catalog identity; every offered course has exactly its observed sections; no duplicate or colliding CRN; instructors on
the right sections; lab/recitation links valid and mutual; credits not double-counted; prerequisite AND/OR structure equal to the Banner text;
natural-science sequences and elective approval statuses; nothing outside catalog_csb changed (row-content fingerprint) and the 243 original
catalog_research sections are intact. Prints counts and verdicts only: no name, CRN list or page text.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_csb_dataset as build  # noqa: E402
import csb_dataset as ds  # noqa: E402

RESULTS = []


def psql(sql, file=None):
    cmd = ["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At", "-F", "|"]
    cmd += ["-f", str(file)] if file else ["-c", sql]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if r.returncode != 0:
        sys.exit("psql failed: " + (r.stdout + r.stderr).strip()[:500])
    return r.stdout.strip()


def rows(sql):
    out = psql(f"SELECT COALESCE(json_agg(t), '[]'::json) FROM ({sql}) t")
    return json.loads(out)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""))


def course_rows_in_text(text):
    return len(re.findall(r"\b(?:Undergraduate|Graduate)\s+[A-Z]{1,2}\b", text or ""))


def leaves_and_operators(expr):
    if expr is None:
        return 0, 0
    if "course" in expr:
        return 1, 0
    leaves = ops = 0
    for a in expr["args"]:
        l, o = leaves_and_operators(a)
        leaves, ops = leaves + l, ops + o
    return leaves, ops + len(expr["args"]) - 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--grids", required=True)
    ap.add_argument("--packs", required=True)
    ap.add_argument("--catalog-cache", required=True)
    ap.add_argument("--program", default=str(HERE / "csb_program_2026_27.json"))
    ap.add_argument("--other-schemas-baseline")
    ap.add_argument("--save-other-schemas-baseline")
    ap.add_argument("--expected-research-sections", type=int, default=243, help="catalog_research.section rows that must still exist (243 on 2026-10-18; pass the current count if the CSE/non-CSE owner has added more)")
    ap.add_argument("--save-fingerprint")
    ap.add_argument("--compare-fingerprint")
    args = ap.parse_args(argv)

    program = json.loads(Path(args.program).read_text(encoding="utf-8"))
    grid_rows, searches, caps = build.collect(args.grids, args.packs)
    sections, offerings = ds.build_sections(grid_rows, caps, "BANNER_202710")      # the expectation, rebuilt from the raw inputs

    # ---- identity ------------------------------------------------------------------------------------------------------------
    db_courses = {r["course_id"]: r for r in rows("SELECT course_id, catalog_status, prerequisite_status, title, is_ns FROM catalog_csb.catalog_course")}
    required = {"CSB-010", "CSB-310", "CSB-312", "CSB-313", "BUS-001", "ACCT-151", "ACCT-152", "ECO-001", "ECO-146", "ECO-119", "FIN-125", "LAW-201", "SCM-186", "MGT-043", "MKT-111",
                "MGT-301", "MATH-021", "MATH-075", "MATH-076", "MATH-022", "MATH-205", "MATH-231", "ECO-045", "BUS-003", "WRT-001", "BUS-203", "CSE-252", "CSE-003", "CSE-004", "CSE-007",
                "CSE-017", "CSE-109", "CSE-140", "CSE-202", "CSE-216", "CSE-241", "CSE-341", "CSE-262", "CSE-303", "CSE-340"}
    check(f"every mandatory CSB course and alternative ({len(required)}) has a catalog identity", required <= set(db_courses), str(sorted(required - set(db_courses))))
    check("every course the program file names has a catalog identity", ds.program_ids(program) <= set(db_courses))
    not_found = sorted(c for c, r in db_courses.items() if r["catalog_status"] != "CATALOG_PAGE_READ")
    print(f"      courses not found on the 2026-27 catalog pages (kept as NOT_FOUND, never guessed): {not_found}")
    check("every offered course (Banner) has a catalog_course row", {o["offeringId"] for o in offerings} <= set(db_courses))

    # ---- sections ------------------------------------------------------------------------------------------------------------
    db_sections = rows("SELECT crn, offering_id, section_code, component_type, schedule_type, credits_text, link_status FROM catalog_csb.section")
    expect = {s["crn"]: s for s in sections}
    check(f"sections: the database holds exactly the {len(expect)} observed sections", {r["crn"] for r in db_sections} == set(expect) and len(db_sections) == len(expect))
    check("no duplicate CRN in catalog_csb", len({r["crn"] for r in db_sections}) == len(db_sections))
    check("every stored section matches its observed offering, section code, component, schedule type, credits and link status",
          all((r["offering_id"], r["section_code"], r["component_type"], r["schedule_type"], r["credits_text"], r["link_status"])
              == (expect[r["crn"]]["offeringId"], expect[r["crn"]]["sectionCode"], expect[r["crn"]]["componentType"], expect[r["crn"]]["scheduleType"], expect[r["crn"]]["creditsText"], expect[r["crn"]]["linkStatus"])
              for r in db_sections))
    by_offering = {}
    for r in db_sections:
        by_offering[r["offering_id"]] = by_offering.get(r["offering_id"], 0) + 1
    observed = {}
    for g in grid_rows.values():
        key = ds.course_code(g["subject"], g["number"])[2]
        observed[key] = observed.get(key, 0) + 1
    check("every course returned by Banner has exactly the number of sections that Banner returned", by_offering == observed)
    zero = sorted(s["courseId"] for s in ds.build_searches(searches, "x") if s["result"] == "NOT_OFFERED" and s["courseId"])
    db_searches = {r["course_id"]: r["result"] for r in rows("SELECT course_id, result FROM catalog_csb.banner_search WHERE search_kind = 'EXACT_COURSE'")}
    db_status = {r["course_id"]: r["term_status"] for r in rows("SELECT course_id, term_status FROM catalog_csb.course_term_status")}
    check(f"every exact search that returned 0 classes ({len(zero)}) is stored as NOT_OFFERED", all(db_searches.get(c) == "NOT_OFFERED" for c in zero))
    check("every such course that has a catalog identity reads NOT_OFFERED_IN_SPRING_2027", all(db_status.get(c) == "NOT_OFFERED_IN_SPRING_2027" for c in zero if c in db_courses),
          str([c for c in zero if c in db_courses and db_status.get(c) != "NOT_OFFERED_IN_SPRING_2027"]))
    check("every offered course is OFFERED in course_term_status", all(db_status.get(o["offeringId"]) == "OFFERED" for o in offerings))

    # ---- meetings, instructors ----------------------------------------------------------------------------------------------------
    exp_weekly = sum(1 for s in sections for m in s["meetings"] if m["kind"] == "WEEKLY")
    exp_dated = sum(1 for s in sections for m in s["meetings"] if m["kind"] == "DATED_ONE_TIME")
    got = rows("SELECT count(*) FILTER (WHERE meeting_kind = 'WEEKLY') AS w, count(*) FILTER (WHERE meeting_kind = 'DATED_ONE_TIME') AS d FROM catalog_csb.section_meeting")[0]
    check(f"meetings: {exp_weekly} weekly rows and {exp_dated} dated one-time rows (exams never weekly)", (got["w"], got["d"]) == (exp_weekly, exp_dated), str(got))
    check("arranged meetings are stored as arranged (no timetable row)", rows("SELECT count(*) AS n FROM catalog_csb.section_arranged_meeting")[0]["n"] == sum(len(s["arranged"]) for s in sections))
    db_instructors = {}
    for r in rows("SELECT crn, position, instructor_name, role, is_placeholder FROM catalog_csb.section_instructor"):
        db_instructors.setdefault(r["crn"], []).append((r["position"], r["instructor_name"], r["role"], r["is_placeholder"]))
    exp_instructors = {s["crn"]: [(i["position"], i["name"], i["role"], i["placeholder"]) for i in s["instructors"]] for s in sections if s["instructors"]}
    check("instructor associations: every section carries exactly its grid names, in order, with roles (no name added, dropped or moved to another section)", db_instructors == exp_instructors)
    names = {n for v in db_instructors.values() for _, n, _, p in v if not p}
    placeholders = sum(1 for v in db_instructors.values() for _, _, _, p in v if p)
    print(f"      {sum(len(v) for v in db_instructors.values())} instructor entries, {len(names)} distinct named instructors, {placeholders} placeholder entries (no professor guessed)")
    check("no section without a listed instructor was given one", all(s["crn"] in db_instructors for s in sections if s["instructors"]) and not any(c in db_instructors for c in expect if not expect[c]["instructors"]))

    # ---- links -------------------------------------------------------------------------------------------------------------------
    pairs = rows("SELECT crn, partner_crn, listed_back FROM catalog_csb.link_pair")
    check(f"{len(pairs)} link listings, every one mutual (the partner's own panel lists it back)", pairs and all(p["listed_back"] for p in pairs))
    exp_links = sum(len(s["links"]) for s in sections)
    check("link listings equal the Linked Sections panels", len(pairs) == exp_links, f"{len(pairs)} vs {exp_links}")
    lone = rows("SELECT s.crn FROM catalog_csb.section s WHERE s.component_type IN ('LAB', 'RECITATION') AND s.link_status = 'NO_LINKS_LISTED'")
    lone_offerings = sorted({expect[r["crn"]]["offeringId"] for r in lone})
    print(f"      lab/recitation sections with no Linked Sections panel at all (stand-alone lab courses): {lone_offerings}")
    check("every lab or recitation of a course that has linked components is linked to a lecture",
          all(expect[r["crn"]]["offeringId"] not in {s["offeringId"] for s in sections if s["linkStatus"] != "NO_LINKS_LISTED"} for r in lone))
    check("credits are not double-counted (a linked component adds 0 in the grid, so a package is the lecture's credits)",
          rows("SELECT count(*) AS n FROM catalog_csb.package_credit WHERE members_grid_credits > 0")[0]["n"] == 0)
    flagged = rows("SELECT count(*) AS n FROM catalog_csb.section WHERE credits_display_status = 'DETAILS_DIFFER_FROM_GRID'")[0]["n"]
    print(f"      {flagged} sections show different credit hours in Class Details than in the grid (bundled total; flagged, not trusted)")
    check("the SQL cross-checks (grid_mismatch, link_status_mismatch) are empty", rows("SELECT (SELECT count(*) FROM catalog_csb.grid_mismatch) + (SELECT count(*) FROM catalog_csb.link_status_mismatch) AS n")[0]["n"] == 0)

    # ---- requirements ------------------------------------------------------------------------------------------------------------
    rules = rows("SELECT d.crn, d.prerequisites_text, r.expression FROM catalog_csb.section_detail d JOIN catalog_csb.section_rule r ON r.crn = d.crn AND r.rule_kind = 'PREREQUISITE'")
    bad = []
    for r in rules:
        leaves, ops = leaves_and_operators(r["expression"])
        connectors = len(re.findall(r"\b(?:And|Or)\b", r["prerequisites_text"].replace("And/Or", "")))
        if leaves != course_rows_in_text(r["prerequisites_text"]) or ops != connectors:
            bad.append(r["crn"])
    check(f"prerequisite AND/OR logic: {len(rules)} structured prerequisites each have exactly the courses and connectors of the Banner text", not bad, f"{len(bad)} differ")
    cmp = {r["status"]: r["n"] for r in rows("SELECT status, count(*) AS n FROM catalog_csb.prerequisite_comparison GROUP BY status")}
    print(f"      catalog vs Banner prerequisite comparison: {cmp}")
    check("a catalog/Banner conflict, if any, keeps both statements", all(r["both"] for r in rows("SELECT (catalog_text IS NOT NULL AND banner_text IS NOT NULL) AS both FROM catalog_csb.prerequisite_comparison WHERE status IN ('CONFLICT', 'SAME_COURSES_DIFFERENT_LOGIC')")))
    texts = rows("SELECT count(*) FILTER (WHERE restrictions_text IS NULL OR prerequisites_text IS NULL OR corequisites_text IS NULL) AS missing, count(*) AS n FROM catalog_csb.section_detail")[0]
    check(f"all {texts['n']} sections have their Class Details text stored (restrictions, prerequisites, corequisites) and the panels inspected recorded", texts["missing"] == 0 and texts["n"] == len(sections))
    panels = rows("SELECT count(*) AS n FROM catalog_csb.section_detail WHERE NOT (panels_inspected @> ARRAY['Class Details', 'Restrictions', 'Prerequisites', 'Corequisites', 'Linked Sections', 'Cross Listed Courses'])")[0]["n"]
    check("every section records that Class Details, Restrictions, Prerequisites, Corequisites, Linked Sections and Cross Listed Courses were inspected", panels == 0)

    # ---- catalog vs Banner, beyond prerequisites ----------------------------------------------------------------------------------
    title_diff = rows("SELECT o.offering_id FROM catalog_csb.offering o JOIN catalog_csb.catalog_course c USING (course_id) WHERE c.catalog_status = 'CATALOG_PAGE_READ' AND lower(btrim(c.title)) <> lower(btrim(o.title))")
    check("every offered course's Banner title equals its catalog title", not title_diff, str([r["offering_id"] for r in title_diff]))
    credit_diff = rows("SELECT o.offering_id FROM catalog_csb.offering o JOIN catalog_csb.catalog_course c USING (course_id) WHERE c.catalog_status = 'CATALOG_PAGE_READ' AND o.credits_status = 'SINGLE_VALUE' "
                       "AND o.credits_min <> ALL (string_to_array(translate(c.credits_text, '-', ','), ',')::numeric[]) AND NOT (o.credits_min BETWEEN COALESCE(c.credits_min, -1) AND COALESCE(c.credits_max, -1))")
    check("every offered course's Banner credits are among the credit values the catalog prints", not credit_diff, str([r["offering_id"] for r in credit_diff]))
    ns_diff = rows("SELECT o.offering_id FROM catalog_csb.offering o JOIN catalog_csb.catalog_course c USING (course_id) WHERE c.is_ns AND NOT EXISTS (SELECT 1 FROM catalog_csb.section s WHERE s.offering_id = o.offering_id AND s.attribute_text ILIKE '%Natural Science%')")
    check("every offered course the catalog designates NS carries Banner's Natural Science Requirement attribute", not ns_diff, str([r["offering_id"] for r in ns_diff]))

    # ---- degree ---------------------------------------------------------------------------------------------------------------------
    prog = rows("SELECT p.total_credits::float AS total, (SELECT sum(credits)::float FROM catalog_csb.requirement_group g WHERE g.program_id = p.program_id AND parent_group_id IS NULL) AS s FROM catalog_csb.program p")[0]
    check("the 136-credit degree structure is stored and its categories sum to 136", prog["total"] == 136 and prog["s"] == 136)
    alt = {r["group_key"]: r["sets"] for r in rows("SELECT g.group_key, count(DISTINCT o.option_set) AS sets FROM catalog_csb.requirement_group g JOIN catalog_csb.requirement_option o USING (group_id) WHERE g.logic = 'ANY' GROUP BY g.group_key")}
    check("alternatives are preserved (CSE 003+004 or 007, CSE 241 or 341, ECO 146 or 119, MATH 021 or 075+076, MATH 231 or ECO 045)",
          alt == {"INTRO_PROGRAMMING": 2, "DATABASE": 2, "ECO_INTERMEDIATE": 2, "CALCULUS_1": 2, "STATISTICS": 2}, str(alt))
    check("the 9-credit professional elective requirement is separate from the 3-credit CSE elective",
          rows("SELECT count(*) AS n FROM catalog_csb.requirement_group WHERE group_key IN ('CSE_ELECTIVE', 'PROFESSIONAL_ELECTIVES_9') AND credits IN (3, 9)")[0]["n"] == 2)
    st = {r["k"]: r["n"] for r in rows("SELECT l.list_key || ':' || m.member_status AS k, count(*) AS n FROM catalog_csb.elective_list l JOIN catalog_csb.elective_list_member m USING (list_key) GROUP BY 1")}
    print(f"      elective lists: {st}")
    check("only the catalog's 36 CSE elective list entries are CATALOG_LISTED; every professional elective candidate is APPROVAL_NOT_VERIFIED",
          st.get("CS_ELECTIVE_LIST:CATALOG_LISTED") == 36 and set(st) == {"CS_ELECTIVE_LIST:CATALOG_LISTED", "CSB_PROFESSIONAL_ELECTIVES:APPROVAL_NOT_VERIFIED"})
    seq = rows("SELECT count(*) AS n, count(*) FILTER (WHERE special_rule IS NOT NULL) AS special FROM catalog_csb.ns_sequence")[0]
    check("17 suggested natural-science sequences are stored, with the EES 102 special rule", seq["n"] == 17 and seq["special"] == 1)
    check("every natural-science course the sequences name carries the NS designation in the catalog (or is not in the catalog)", rows("SELECT count(*) AS n FROM catalog_csb.program_problem WHERE problem = 'NS_SEQUENCE_COURSE_NOT_NS_DESIGNATED'")[0]["n"] == 0)
    status = {r["status"]: r["n"] for r in rows("SELECT status, count(*) AS n FROM catalog_csb.ns_sequence_status GROUP BY 1")} if psql("SELECT to_regclass('catalog_csb.ns_sequence_status') IS NOT NULL") == "t" else {}
    if status:
        print(f"      sequence availability this term: {status}")

    # ---- other schemas ------------------------------------------------------------------------------------------------------------
    fp = psql("", file=HERE / "fingerprint_other_schemas.sql")
    if args.save_other_schemas_baseline:
        Path(args.save_other_schemas_baseline).write_text(fp + "\n")
        print(f"      saved the fingerprint of {len(fp.splitlines())} tables outside catalog_csb")
    if args.other_schemas_baseline:
        base = Path(args.other_schemas_baseline).read_text().strip().splitlines()
        check(f"nothing outside catalog_csb changed: all {len(base)} other tables have the same row count and content hash as before the import", fp.splitlines() == base)
    if psql("SELECT to_regclass('catalog_research.section') IS NOT NULL") == "t":
        n = rows("SELECT count(*) AS n FROM catalog_research.section")[0]["n"]
        check(f"catalog_research still holds its {args.expected_research_sections} sections", n == args.expected_research_sections, str(n))
        clash = rows("SELECT count(*) AS n FROM catalog_csb.section c JOIN catalog_research.section r USING (term_code, crn)")[0]["n"]
        check("no CSB CRN collides with a catalog_research CRN", clash == 0)
        xl = rows("SELECT count(DISTINCT x.cross_crn) AS n FROM catalog_csb.section_cross_list x JOIN catalog_research.section r ON r.term_code = x.term_code AND r.crn = x.cross_crn")[0]["n"]
        print(f"      {xl} cross-listed CRN(s) named by CSB sections already exist in catalog_research (a reference, not a duplicate)")

    # every column named like a CRN in every base table of every other schema (banner_evidence, catalog_promoted, cse007_snapshot, professor_ratings, ...)
    sweep = rows("""SELECT table_schema || '.' || table_name || '.' || column_name AS col,
			(xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I where %I in (select crn from catalog_csb.section)', table_schema, table_name, column_name), false, true, '')))[1]::text::int AS n
		FROM information_schema.columns c
		WHERE column_name ~ '(^|_)crn$' AND table_schema NOT IN ('catalog_csb', 'pg_catalog', 'information_schema')
			AND EXISTS (SELECT 1 FROM information_schema.tables t WHERE t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE')""")
    clashes = [r["col"] for r in sweep if r["n"]]
    check(f"no CSB CRN occurs in any of the {len(sweep)} CRN columns of the other schemas' tables", not clashes, ", ".join(clashes))
    if psql("SELECT to_regclass('catalog_research.offering') IS NOT NULL") == "t":
        same = rows("SELECT count(*) AS n FROM catalog_csb.offering o JOIN catalog_research.offering r USING (term_code, offering_id)")[0]["n"]
        same_course = rows("SELECT count(*) AS n FROM catalog_csb.offering o JOIN catalog_research.offering r ON r.term_code = o.term_code AND r.course_id = o.course_id")[0]["n"]
        check("no CSB offering is also an offering of catalog_research (course identity is not duplicated)", same == 0 and same_course == 0, f"{same} {same_course}")
        differ = rows("SELECT count(*) AS n FROM catalog_csb.catalog_course a JOIN catalog_research.catalog_course b USING (course_id) WHERE b.title IS NOT NULL AND lower(btrim(a.title)) <> lower(btrim(b.title))")[0]["n"]
        check("where catalog_csb and catalog_research both name a course identity, no title differs", differ == 0, str(differ))

    # ---- persistence ---------------------------------------------------------------------------------------------------------------
    fingerprint = psql("SELECT md5(string_agg(x, '|' ORDER BY x COLLATE \"C\")) FROM (SELECT row(s.*)::text AS x FROM catalog_csb.section s UNION ALL SELECT row(m.term_code, m.crn, m.day_of_week, m.start_time, m.end_time, m.location, m.meeting_type, m.meeting_kind, m.start_date, m.end_date)::text FROM catalog_csb.section_meeting m "
                       "UNION ALL SELECT row(i.*)::text FROM catalog_csb.section_instructor i UNION ALL SELECT row(l.*)::text FROM catalog_csb.section_link_option l UNION ALL SELECT row(d.*)::text FROM catalog_csb.section_detail d "
                       "UNION ALL SELECT row(r.term_code, r.crn, r.rule_kind, r.polarity, r.rule_values, r.expression, r.source_text)::text FROM catalog_csb.section_rule r UNION ALL SELECT row(c.course_id, c.title, c.credits_text, c.prerequisite_status, c.prerequisite_text)::text FROM catalog_csb.catalog_course c) q")
    if args.save_fingerprint:
        Path(args.save_fingerprint).write_text(fingerprint + "\n")
        print(f"      saved content fingerprint {fingerprint}")
    if args.compare_fingerprint:
        check("the stored CSB content is identical to the saved fingerprint (records survived the restart)", Path(args.compare_fingerprint).read_text().strip() == fingerprint)
    failed = [n for n, ok in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)} of {len(RESULTS)} checks passed" + (": FAILED " + "; ".join(failed) if failed else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
