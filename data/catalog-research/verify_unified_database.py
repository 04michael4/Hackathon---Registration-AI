"""Verifies the unified (CSE + non-CSE) local database against the original local research files (stdlib only; needs psql; SELECTs only).

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner or reader> PGDATABASE=<unified database> python3 -I data/catalog-research/verify_unified_database.py \
      --cse-export <CSE export JSON> --noncse-export <export JSON> --banner-dir <grid CSV dir> [--instructors-csv F] [--save-fingerprint F | --compare-fingerprint F]

Expectations are rebuilt here straight from the raw grid CSVs and the exports, by code that does not share the importers' normalization, and
compared with what PostgreSQL returns. It prints counts and verdicts only: no course data, no instructor names. --instructors-csv is used only
to prove that no instructor name occurs anywhere in the stored data. The session is forced read-only and the shared database is refused.
"""
import argparse
import csv
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

TERM = "202710"
LETTERS = {"M": 1, "T": 2, "W": 3, "R": 4, "F": 5, "S": 6, "U": 7}
CSE_DAYS = {"MONDAY": 1, "TUESDAY": 2, "WEDNESDAY": 3, "THURSDAY": 4, "FRIDAY": 5, "SATURDAY": 6, "SUNDAY": 7}
RESULTS = []


def run(statement, tolerate_denied=False):
    env = {**os.environ, "PGOPTIONS": "-c default_transaction_read_only=on"}
    r = subprocess.run(["psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-c", statement], capture_output=True, text=True, env=env, timeout=300)
    if r.returncode != 0:
        if tolerate_denied and "permission denied" in r.stderr:
            return None
        sys.exit("query failed: " + r.stderr.strip()[:300])
    return r.stdout.strip()


def rows(statement):
    return json.loads(run(f"SELECT COALESCE(json_agg(t), '[]'::json) FROM ({statement}) t"))


def check(ok, label, detail=""):
    RESULTS.append(bool(ok))
    print(("  ok:   " if ok else "  FAIL: ") + label + (f"  [{detail}]" if detail else ""))


def location(building, room):
    parts = [p for p in (building, room) if p and p != "None"]
    return " ".join(parts).strip() or "TBA"


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--cse-export", required=True)
    ap.add_argument("--noncse-export", required=True)
    ap.add_argument("--banner-dir", required=True)
    ap.add_argument("--instructors-csv")
    ap.add_argument("--save-fingerprint")
    ap.add_argument("--compare-fingerprint")
    a = ap.parse_args()
    if os.environ.get("PGDATABASE") == "registration_cse007_local" or os.environ.get("PGPORT") == "55433":
        sys.exit("refusing the shared database")
    cse = json.loads(Path(a.cse_export).read_text(encoding="utf-8"))["offerings"]
    nx = json.loads(Path(a.noncse_export).read_text(encoding="utf-8"))
    bd = Path(a.banner_dir)
    grid, extra, nosec = read_csv(bd / "sections_grid.csv"), read_csv(bd / "meetings_extra.csv"), read_csv(bd / "no_sections.csv")
    print("== Verifying", run("SELECT current_user || ' on ' || current_database()"), "\n")

    print("1. Identities and counts")
    cse_crns = {s["crn"] for o in cse for s in o["sections"]}
    non_crns = {r["crn"] for r in grid}
    check(len(cse) == 42 and len(cse_crns) == 52, f"CSE input: {len(cse)} offerings, {len(cse_crns)} sections")
    check(len(non_crns) == 191 == len(grid), f"non-CSE input: {len(non_crns)} distinct CRNs in {len(grid)} grid rows, {len({r['course'] for r in grid})} courses")
    check(not (cse_crns & non_crns), "no CRN occurs in both datasets")
    by_src = {r["data_source"]: r for r in rows("SELECT o.data_source, count(DISTINCT o.offering_id)::int AS offerings, count(s.crn)::int AS sections FROM catalog_research.offering o "
                                                  "LEFT JOIN catalog_research.section s USING (term_code, offering_id) GROUP BY 1")}
    check(by_src.get("BANNER_EVIDENCE", {}).get("offerings", 0) + by_src.get("SNAPSHOT", {}).get("offerings", 0) == 42
          and by_src.get("BANNER_EVIDENCE", {}).get("sections", 0) + by_src.get("SNAPSHOT", {}).get("sections", 0) == 52, "database: 42 CSE offerings and 52 CSE sections")
    check(by_src.get("BANNER_GRID_STAGING", {}).get("offerings") == len({r['course'] for r in grid}) and by_src.get("BANNER_GRID_STAGING", {}).get("sections") == 191,
          "database: 30 non-CSE offerings and 191 non-CSE sections")
    check(run("SELECT count(*) FROM catalog_research.section") == "243" and run("SELECT count(DISTINCT (term_code, crn)) FROM catalog_research.section") == "243",
          "243 sections in total, 243 unique (term, CRN) identities")
    subjects = {r["subject"]: r["n"] for r in rows("SELECT o.subject, count(*)::int AS n FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id) GROUP BY 1")}
    want = {}
    for r in grid:
        want[r["course"].split(" ")[0]] = want.get(r["course"].split(" ")[0], 0) + 1
    want["CSE"] = 52
    check(subjects == want, f"sections by subject match the files ({len(subjects)} subjects)")
    print("        " + ", ".join(f"{k}={v}" for k, v in sorted(subjects.items())))

    print("\n2. Sections and components")
    db_sec = {r["crn"]: r for r in rows("SELECT s.crn, o.course_id, s.section_code, s.component_type, s.credits, s.credit_source, s.schedule_type, s.link_status, s.banner_link_flag, s.lecture_crn FROM catalog_research.section s "
                                       "JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source = 'BANNER_GRID_STAGING'")}
    comp = {"Class": "LECTURE", "Lab": "LAB", "Recitation": "RECITATION"}
    bad = [r["crn"] for r in grid if (db_sec[r["crn"]]["course_id"], db_sec[r["crn"]]["section_code"], db_sec[r["crn"]]["component_type"], db_sec[r["crn"]]["credits"],
                                     db_sec[r["crn"]]["credit_source"], db_sec[r["crn"]]["schedule_type"], db_sec[r["crn"]]["banner_link_flag"])
           != (r["course"].replace(" ", "-"), r["section"], comp[r["type"]], int(r["hours_grid"]), "GRID_DISPLAY", r["type"], r["linked_flag"])]
    check(not bad, "course, section code, component, grid credits, schedule type and link flag match for every non-CSE CRN", f"{len(bad)} differ")
    counts = {r["component_type"]: r["n"] for r in rows("SELECT component_type, count(*)::int AS n FROM catalog_research.section GROUP BY 1")}
    check(counts == {"LECTURE": 43 + 100, "LAB": 9 + 29, "RECITATION": 62}, "lectures, labs and recitations are all represented", str(counts))
    linked_export = sum(len(s["linked_crns"]) for s in nx["sections"])
    link = {r["link_status"]: r["n"] for r in rows("SELECT link_status, count(*)::int AS n FROM catalog_research.section GROUP BY 1")}
    check(link.get("LISTED_BY_LECTURE_ONLY", 0) == linked_export and link.get("CONFIRMED", 0) == 9 and link.get("NOT_COLLECTED", 0) == 91 - linked_export,
          f"links: {link.get('CONFIRMED', 0)} confirmed (CSE), {link.get('LISTED_BY_LECTURE_ONLY', 0)} listed by a lecture only, {link.get('NOT_COLLECTED', 0)} not collected (never inferred)")
    check(run("SELECT count(*) FROM catalog_research.section WHERE component_type = 'LECTURE' AND link_status <> 'NOT_APPLICABLE'") == "0", "no lecture has a parent")
    sums = {r["offering_id"]: r for r in rows("SELECT offering_id, credits, credits_status FROM catalog_research.offering WHERE data_source = 'BANNER_GRID_STAGING'")}
    varying = sorted(k for k, v in sums.items() if v["credits_status"] == "VARIES_BY_SECTION")
    expect_var = sorted({r["course"].replace(" ", "-") for r in grid if r["type"] == "Class" and len({x["hours_grid"] for x in grid if x["course"] == r["course"] and x["type"] == "Class"}) > 1})
    check(varying == expect_var and all(sums[k]["credits"] is None for k in varying), "offerings whose lecture sections differ in credits have no single credit value", ",".join(varying))

    print("\n3. Meetings: days, times, dates, locations")
    want_weekly = set()
    for r in grid:
        for letter in r["days"]:
            want_weekly.add((r["crn"], LETTERS[letter], r["start"], r["end"], r["start_date"], r["end_date"], location(r["building"], r["room"]), r["type"]))
    got_weekly = {(r["crn"], r["day_of_week"], r["st"], r["en"], r["sd"], r["ed"], r["location"], r["meeting_type"]) for r in rows(
        "SELECT m.crn, m.day_of_week, to_char(m.start_time,'HH24:MI') AS st, to_char(m.end_time,'HH24:MI') AS en, m.start_date::text AS sd, m.end_date::text AS ed, m.location, m.meeting_type "
        "FROM catalog_research.section_meeting m JOIN catalog_research.section s USING (term_code, crn) JOIN catalog_research.offering o USING (term_code, offering_id) "
        "WHERE o.data_source = 'BANNER_GRID_STAGING' AND m.meeting_kind = 'WEEKLY'")}
    check(got_weekly == want_weekly, f"{len(got_weekly)} weekly non-CSE meeting rows equal the grid's day-expanded rows")
    want_exam = {(r["crn"], LETTERS[r["day"]], r["start"], r["end"], r["start_date"], location(r["building"], r["room"]), r["meeting_type"]) for r in extra}
    got_exam = {(r["crn"], r["day_of_week"], r["st"], r["en"], r["sd"], r["location"], r["meeting_type"]) for r in rows(
        "SELECT crn, day_of_week, to_char(start_time,'HH24:MI') AS st, to_char(end_time,'HH24:MI') AS en, start_date::text AS sd, location, meeting_type FROM catalog_research.section_meeting WHERE meeting_kind = 'DATED_ONE_TIME'")}
    check(len(extra) == 36 and got_exam == want_exam and all(r["start_date"] == r["end_date"] for r in extra), f"{len(got_exam)} dated Common Hour Exam meetings equal meetings_extra.csv (each one date, not weekly)")
    check(run("SELECT count(*) FROM catalog_research.section_meeting WHERE meeting_kind = 'DATED_ONE_TIME' AND day_of_week <> extract(isodow FROM start_date)") == "0", "every exam's weekday is its date's weekday")
    check(run("SELECT count(*) FROM catalog_research.candidate_section_meeting WHERE crn IN (SELECT crn FROM catalog_research.section_meeting WHERE meeting_kind = 'DATED_ONE_TIME')") == "0", "no dated exam is exposed as a weekly slot")
    staging_meetings = len(grid) + len(extra)
    check(staging_meetings == 227, f"the staging pipeline's {staging_meetings} meeting records = {len(grid)} grid meetings + {len(extra)} exams; PostgreSQL stores them as {len(got_weekly)} per-day weekly rows + {len(got_exam)} dated rows")
    cse_want = {(s["crn"], CSE_DAYS[d], m["start"], m["end"]) for o in cse for s in o["sections"] for m in s["meetings"] for d in m["days"]}
    cse_got = {(r["crn"], r["day_of_week"], r["st"], r["en"]) for r in rows("SELECT m.crn, m.day_of_week, to_char(m.start_time,'HH24:MI') AS st, to_char(m.end_time,'HH24:MI') AS en FROM catalog_research.section_meeting m "
                                                                           "JOIN catalog_research.section s USING (term_code, crn) JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source <> 'BANNER_GRID_STAGING'")}
    check(cse_got == cse_want, f"{len(cse_got)} CSE meeting rows equal the CSE export")

    print("\n4. Unknown requirements and open items")
    st = {r["status"]: r["n"] for r in rows("SELECT rs.status, count(*)::int AS n FROM catalog_research.requirement_status rs JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source = 'BANNER_GRID_STAGING' GROUP BY 1")}
    check(st == {"UNKNOWN": 90}, "all 90 non-CSE requirement statuses (30 offerings x prerequisite / corequisite / restriction) are UNKNOWN: nothing was collected", str(st))
    cse_st = {r["status"]: r["n"] for r in rows("SELECT rs.status, count(*)::int AS n FROM catalog_research.requirement_status rs JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source <> 'BANNER_GRID_STAGING' GROUP BY 1")}
    check(sum(cse_st.values()) == 126 and "UNKNOWN" not in cse_st, "the CSE requirement statuses are unchanged (126, with their reviewed statuses)", str(dict(sorted(cse_st.items()))))
    check(run("SELECT count(*) FROM catalog_research.eligibility_rule r JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source = 'BANNER_GRID_STAGING'") == "0", "no non-CSE restriction or prerequisite was invented")
    searched = {r["course"] for r in rows("SELECT course FROM catalog_research.grid_search_result")}
    check(searched == {r["course"] for r in nosec} and len(searched) == 14, f"{len(searched)} searched-with-no-classes courses are kept as search results")
    check(run("SELECT count(*) FROM catalog_research.grid_observation") == "191" and run("SELECT count(*) FROM catalog_research.grid_extra_meeting_observation") == "36", "the verbatim grid rows (191) and exam rows (36) are kept with their provenance")
    src = rows("SELECT origin, redistribution_status, verification FROM catalog_research.grid_source ORDER BY grid_source_id DESC LIMIT 1")[0]
    check((src["origin"], src["redistribution_status"], src["verification"]) == ("authenticated_banner", "NOT_VERIFIED_LOCAL_ONLY", "GRID_ONLY_DETAILS_NOT_INSPECTED"), "provenance: authenticated Banner grid, local-only, details never inspected")

    print("\n5. Degree programs")
    progs = {r["program_id"]: r for r in rows("SELECT program_id, name, degree_type, catalog_year, total_credits_text, restriction, unknowns, conflicts FROM catalog_research.program")}
    check(set(progs) == {p["id"] for p in nx["programs"]} and len(progs) == 5, f"5 degree programs: {', '.join(sorted(progs))}")
    check(all(progs[p["id"]]["unknowns"] == p["unknowns"] and progs[p["id"]]["conflicts"] == p["conflicts"] and progs[p["id"]]["total_credits_text"] == p["total_credits_text"] for p in nx["programs"]),
          "each program's total credits, unknowns and preserved conflicts equal the export")

    def walk(gs):
        for g in gs:
            yield g
            yield from walk(g["children"])
    exp_groups = [g for p in nx["programs"] for g in walk(p["requirements"])]
    exp_logic = {}
    for g in exp_groups:
        exp_logic[g["logic"]] = exp_logic.get(g["logic"], 0) + 1
    got_logic = {r["logic"]: r["n"] for r in rows("SELECT logic, count(*)::int AS n FROM catalog_research.requirement_group GROUP BY 1")}
    check(got_logic == exp_logic and sum(got_logic.values()) == 28, f"{sum(got_logic.values())} requirement groups with their AND/OR/choose-N/credits logic: {got_logic}")
    check(run("SELECT count(*) FROM catalog_research.requirement_course_option") == str(sum(len(g["options"]) for g in exp_groups)) == "121", "121 requirement options")
    exp_n = sorted((g["key"], g["n"]) for g in exp_groups if g["logic"] == "CHOOSE_N")
    got_n = sorted((r["group_key"], r["n"]) for r in rows("SELECT group_key, n FROM catalog_research.requirement_group WHERE logic = 'CHOOSE_N'"))
    check(exp_n == got_n and len(got_n) > 0, f"{len(got_n)} choose-N groups keep their N")
    check(run("SELECT count(*) FROM catalog_research.program_structure_problem") == "0", "the program trees are structurally consistent")
    check(run("SELECT count(*) FROM catalog_research.requirement_course_option o WHERE o.course_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM catalog_research.catalog_course c WHERE c.course_id = o.course_id)") == "0",
          "every course named by a program exists as a catalog course (valid program-to-course references)")
    check(run("SELECT count(*) FROM catalog_research.catalog_course") == "73" and run("SELECT string_agg(DISTINCT prerequisite_status, ',') FROM catalog_research.catalog_course") == "NOT_COLLECTED",
          "73 catalog courses; none claims its prerequisites were collected")
    check(run("SELECT count(*) FROM catalog_research.catalog_course_exclusion") == str(len(nx["course_credit_exclusions"])) and run("SELECT count(*) FROM catalog_research.catalog_course_equivalence") == "2",
          f"{len(nx['course_credit_exclusions'])} credit exclusions and 2 unverified cross-list assertions kept")
    ts = {r["term_status"]: r["n"] for r in rows("SELECT term_status, count(*)::int AS n FROM catalog_research.course_term_status GROUP BY 1")}
    check(sum(ts.values()) == 73, f"each program course has a term status derived from what was searched: {ts}")
    check(run("SELECT count(*) FROM catalog_research.unresolved_item") == str(len(nx["unresolved"]) + 1), f"{len(nx['unresolved']) + 1} open items carried forward (including the instructor-names note)")

    print("\n6. Nothing approved, promoted or served")
    approvals, promotions = run("SELECT count(*) FROM catalog_promoted.approval", True), run("SELECT count(*) FROM catalog_promoted.promotion", True)
    if approvals is None:
        print("  skip: approval / promotion counts (this role cannot read those tables)")
    else:
        check(approvals == "0" and promotions == "0", "no approval and no promotion exists")
    check(run("SELECT string_agg(course_id, ',') FROM catalog_live.course_offering") == "CSE-007", "catalog_live (what the backend reads) serves only the verified CSE 007")
    stage = {r["tier"] + ":" + r["tier_reason"]: r["n"] for r in rows("SELECT t.tier, t.tier_reason, count(*)::int AS n FROM catalog_research.offering_tier t JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source = 'BANNER_GRID_STAGING' GROUP BY 1, 2")}
    check(stage == {"BLOCKED:GRID_ONLY_DETAILS_NOT_INSPECTED": 30}, "all 30 non-CSE offerings are BLOCKED (grid only, details never inspected)")
    cse_tier = {r["tier"]: r["n"] for r in rows("SELECT t.tier, count(*)::int AS n FROM catalog_research.offering_tier t JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source <> 'BANNER_GRID_STAGING' GROUP BY 1")}
    check(cse_tier == {"READY": 1, "CONDITIONAL": 39, "BLOCKED": 2}, f"CSE research tiers unchanged: {cse_tier}")
    check(run("SELECT count(*) FROM catalog_research.candidate_course_offering WHERE schedulable") == "1" and run("SELECT count(*) FROM catalog_research.candidate_section_offering WHERE crn IN (SELECT crn FROM catalog_research.grid_observation)") == "0",
          "the candidate views hold no non-CSE section, and only the served CSE 007 is schedulable")
    check(run("SELECT count(*) FROM catalog_research.offering_status WHERE registration_eligibility_guaranteed OR (data_source_served)".replace("data_source_served", "serving_status = 'SERVED' AND course_id <> 'CSE-007'")) == "0", "nothing but CSE 007 is served, and eligibility is guaranteed for none")
    check(run("SELECT count(*) FROM catalog_research.evidence_mismatch") == "0" and run("SELECT count(*) FROM catalog_research.grid_mismatch") == "0",
          "no CSE row disagrees with the Banner evidence and no non-CSE row disagrees with the verbatim grid")
    ready = run("SELECT count(*) FROM catalog_promoted.promotion_readiness WHERE state <> 'BLOCKED'", True)
    if ready is not None:
        check(ready == "0" and run("SELECT count(*) FROM catalog_promoted.promotion_readiness") == "40", "all 40 Banner CSE courses are still BLOCKED in promotion_readiness")

    print("\n7. Privacy")
    check(run("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'catalog_research' AND column_name ILIKE '%instructor%'") == "0", "there is no instructor column or table")
    if a.instructors_csv:
        names = set()
        for r in read_csv(a.instructors_csv):
            n = r["instructor"].strip()
            if n and not n.startswith("Staff,"):
                names.add(n)
        dump = subprocess.run(["pg_dump", "--data-only", "--schema=catalog_research"], capture_output=True, text=True, env={**os.environ, "PGOPTIONS": "-c default_transaction_read_only=on"}, timeout=300).stdout
        hits = sum(1 for n in names if n in dump)
        check(len(names) > 0 and hits == 0, f"none of the {len(names)} instructor names occurs anywhere in the stored data")

    print("\n8. Fingerprint of the stored content")
    drop = {"offering": ["run_id"], "eligibility_rule": ["rule_id"], "credit_exclusion": ["exclusion_id"], "section_meeting": ["meeting_id"], "tier_policy_decision": ["decision_id", "supersedes_decision_id", "recorded_at", "recorded_by"],
            "grid_source": ["grid_source_id", "run_id"], "grid_observation": ["grid_source_id"], "grid_extra_meeting_observation": ["grid_source_id"], "grid_search_result": ["grid_source_id"],
            "program": ["run_id"], "requirement_group": ["group_id", "parent_group_id"], "program_requirement": ["group_id"], "requirement_course_option": ["option_id", "group_id"], "unresolved_item": ["item_id"]}
    parts = []
    for table in ("offering", "section", "section_meeting", "section_arranged_meeting", "eligibility_rule", "requirement_status", "credit_exclusion", "course_alias", "tier_policy_decision",
                  "grid_source", "grid_observation", "grid_extra_meeting_observation", "grid_search_result", "catalog_course", "program", "requirement_group", "program_requirement",
                  "requirement_course_option", "catalog_course_exclusion", "catalog_course_equivalence", "unresolved_item"):
        removed = "ARRAY[" + ",".join(f"'{c}'" for c in drop.get(table, [])) + "]::text[]" if table in drop else "ARRAY[]::text[]"
        text = run(f"SELECT COALESCE(string_agg(j, E'\\n' ORDER BY j), '') FROM (SELECT (to_jsonb(t) - {removed})::text AS j FROM catalog_research.{table} t) q")
        parts.append(table + ":" + hashlib.sha256(text.encode()).hexdigest())
        if os.environ.get("SHOW_PARTS"): print("        part", table, parts[-1].split(":")[1][:12])
    fp = hashlib.sha256("\n".join(parts).encode()).hexdigest()
    print(f"        content fingerprint {fp[:32]}…")
    if a.save_fingerprint:
        Path(a.save_fingerprint).write_text(fp + "\n", encoding="utf-8")
        print("        saved")
    if a.compare_fingerprint:
        check(Path(a.compare_fingerprint).read_text(encoding="utf-8").strip() == fp, "the stored content is identical to the fingerprint saved before")
    failed = RESULTS.count(False)
    print(f"\n{len(RESULTS) - failed} checks passed, {failed} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
