"""Imports the non-CSE staging data (five degree programs, grid-staged Banner sections) into catalog_research (stdlib only; needs `psql`).

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner> PGDATABASE=<local database> \
      python3 -I data/catalog-research/import_noncse_catalog.py --export <export JSON> --staging-db <staging.db> --banner-dir <banner CSV dir> [--dry-run]

Inputs (produced by data/degree-requirements/, the SQLite staging pipeline; the real files are local-only and never committed):
  --export       the JSON written by export_import_ready.py (programs, courses, sections with meetings, link lists, exclusions, open items)
  --staging-db   the SQLite staging database, opened READ-ONLY, only for the cross-list assertions the export does not carry
  --banner-dir   the verbatim grid CSVs (sections_grid.csv, meetings_extra.csv, no_sections.csv). They are stored as raw observations and every
                 normalized row is cross-checked against them inside the database (grid_mismatch). The `instructor` column is never read.

One transaction: import_noncse_term (replaces the GRID-STAGED offerings of the term only; CSE offerings are untouched, and the import aborts if
the result disagrees with the verbatim rows) and import_degree_programs. Nothing is approved, promoted or made schedulable. Instructor names are
never imported. It refuses the shared database and non-loopback hosts. --dry-run rolls back.
"""
import argparse
import csv
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

SHARED_DATABASES = {"registration_cse007_local"}
SHARED_PORTS = {"55433"}
COMPONENT = {"Class": "LECTURE", "Lab": "LAB", "Recitation": "RECITATION"}
DAY_LETTER = {"M": 1, "T": 2, "W": 3, "R": 4, "F": 5, "S": 6, "U": 7}
CODE = re.compile(r"^([A-Z]{2,4}) (\d{3})$")
GRID_COLUMNS = ("term", "course", "section", "crn", "title", "hours_grid", "days", "start", "end", "type", "building", "room", "start_date", "end_date", "linked_flag", "status")
EXTRA_COLUMNS = ("term", "crn", "meeting_type", "day", "start", "end", "start_date", "end_date", "building", "room")
NOSEC_COLUMNS = ("term", "course", "result")


class InputError(Exception):
    pass


def course_id(code):
    m = CODE.match(code or "")
    if not m:
        raise InputError(f"course code {code!r} is not 'SUBJ 123'")
    return m.group(1) + "-" + m.group(2)


def location(building, room):
    parts = [p for p in (building, room) if p and p != "None"]
    return " ".join(parts).strip() or "TBA"


def read_csv(path, columns):
    with open(path, encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        missing = [c for c in columns if c not in (reader.fieldnames or [])]
        if missing:
            raise InputError(f"{Path(path).name} lacks columns {missing}")
        return [{c: row[c] for c in columns} for row in reader]   # the instructor column is never copied


def normalize(export, term_code, grid_rows, extra_rows, no_sections, equivalences):
    if export.get("format") != "non_cse_staging/1" or export.get("term") != term_code:
        raise InputError("the export is not a non_cse_staging/1 export for term " + term_code)
    sections = export["sections"]
    crns = [s["crn"] for s in sections]
    if len(crns) != len(set(crns)):
        raise InputError("a CRN appears twice in the export")
    grid_crns = {r["crn"] for r in grid_rows}
    if set(crns) != grid_crns or len(grid_rows) != len(grid_crns):
        raise InputError("the export's sections and the grid CSV's CRNs differ")
    if any(r["term"] != term_code for r in grid_rows + extra_rows + no_sections):
        raise InputError("a CSV row belongs to another term")
    by_course = {}
    for s in sections:
        by_course.setdefault(s["course_code"], []).append(s)
    # which lecture lists which lab/recitation (only as observed in a Linked Sections panel)
    listed_by = {}
    for s in sections:
        for link in s["linked_crns"]:
            listed_by.setdefault(link["linked_crn"], set()).add(s["crn"])
    assertions = {}
    for a, b in equivalences:
        assertions.setdefault(a, []).append(b)
    offerings = []
    for code in sorted(by_course):
        cid = course_id(code)
        subject, number = code.split(" ")
        rows = sorted(by_course[code], key=lambda s: s["section_no"])
        titles = {s["title"] for s in rows}
        if len(titles) != 1:
            raise InputError(f"{code}: sections disagree on the title")
        components = {s["schedule_type"] for s in rows}
        if not components <= set(COMPONENT):
            raise InputError(f"{code}: unknown schedule type {sorted(components - set(COMPONENT))}")
        lecture_credits = {s["grid_hours"] for s in rows if s["schedule_type"] == "Class"} or {s["grid_hours"] for s in rows}
        lectures = {s["crn"] for s in rows if s["schedule_type"] == "Class"}
        out_sections = []
        for s in rows:
            component = COMPONENT[s["schedule_type"]]
            lecture_crn, link_status = None, "NOT_APPLICABLE"
            if component != "LECTURE":
                parents = listed_by.get(s["crn"], set())
                if len(parents) > 1:
                    raise InputError(f"{s['crn']}: listed by more than one lecture; refusing to pick")
                if parents:
                    (lecture_crn,) = parents
                    if lecture_crn not in lectures:
                        raise InputError(f"{s['crn']}: its listing lecture is not a lecture of {code}")
                    link_status = "LISTED_BY_LECTURE_ONLY"
                else:
                    link_status = "NOT_COLLECTED"
            meetings = []
            for m in s["meetings"]:
                dated = m["meeting_type"] not in COMPONENT
                letters = m["days"]
                if dated and (len(letters) != 1 or m["start_date"] != m["end_date"]):
                    raise InputError(f"{s['crn']}: a {m['meeting_type']} meeting must be one dated day")
                if not dated and m["meeting_type"] != s["schedule_type"]:
                    raise InputError(f"{s['crn']}: a weekly meeting of another type")
                for letter in letters:
                    if letter not in DAY_LETTER:
                        raise InputError(f"{s['crn']}: unknown day letter {letter!r}")
                    meetings.append({"type": m["meeting_type"], "kind": "DATED_ONE_TIME" if dated else "WEEKLY", "day": DAY_LETTER[letter], "start": m["start_time"], "end": m["end_time"],
                                     "startDate": m["start_date"], "endDate": m["end_date"], "location": location(m["building"], m["room"])})
            out_sections.append({"crn": s["crn"], "sectionCode": s["section_no"], "component": component, "credits": int(s["grid_hours"]), "lectureCrn": lecture_crn,
                                 "linkStatus": link_status, "bannerLinkFlag": s["link_flag"], "scheduleType": s["schedule_type"], "meetings": meetings})
        single = len(lecture_credits) == 1
        aliases = [{"subject": b.split(" ")[0], "number": b.split(" ")[1], "status": "CROSS_LIST_USER_ASSERTED_UNVERIFIED"} for b in assertions.get(code, [])]
        offerings.append({"offeringId": cid, "subject": subject, "number": number, "title": rows[0]["title"], "credits": int(next(iter(lecture_credits))) if single else None,
                          "creditsStatus": "SINGLE_VALUE" if single else "VARIES_BY_SECTION", "administrativeType": "Class" if lectures else rows[0]["schedule_type"],
                          "dataSource": "BANNER_GRID_STAGING", "crossListingStatus": "CROSS_LISTED_USER_ASSERTED_UNVERIFIED" if aliases else "UNVERIFIED",
                          "aliases": aliases, "sections": out_sections})
    ev = next((e for e in export["evidence"] if e["origin"] != "public_catalog"), None)
    if ev is None or ev["verification"] != "GRID_ONLY_DETAILS_NOT_INSPECTED":
        raise InputError("the export carries no grid-only Banner evidence record")
    origin = ev["origin"]
    grid_payload = {"termCode": term_code, "source": {"origin": origin, "collectedOn": ev["collected"], "locator": ev["locator"], "verification": ev["verification"],
                                                       "redistribution": "SYNTHETIC" if origin == "synthetic_fixture" else "NOT_VERIFIED_LOCAL_ONLY"},
                    "gridRows": [dict(r) for r in grid_rows],
                    "extraMeetings": extra_rows, "noSections": no_sections, "offerings": offerings}
    pc = next((e for e in export["evidence"] if e["origin"] == "public_catalog"), None)
    if pc is None:
        raise InputError("the export carries no public-catalog evidence record")
    program_payload = {
        "source": {"locator": pc["locator"], "verification": pc["verification"]},
        "programs": [{"id": p["id"], "name": p["name"], "college": p["college"], "department": p["department"], "degreeType": p["degree_type"], "catalogYear": p["catalog_year"],
                      "totalCreditsText": p["total_credits_text"], "restriction": p["restriction"], "unknowns": p["unknowns"], "conflicts": p["conflicts"], "requirements": p["requirements"]}
                     for p in export["programs"]],
        "courses": [{"code": c["code"], "subject": c["subject"], "number": c["number"], "title": c["title"], "catalogStatus": c["catalog_status"], "prerequisiteStatus": c["prerequisite_status"]}
                    for c in export["courses"]],
        "exclusions": [{"course": x["course_code"], "excluded": x["excluded_text"], "status": x["status"]} for x in export["course_credit_exclusions"]],
        "equivalences": [{"course": a, "equivalent": b, "status": "CROSS_LISTED_USER_ASSERTED_UNVERIFIED"} for a, b in equivalences],
        "unresolved": [{"subject": u["subject"], "detail": u["detail"]} for u in export["unresolved"]]
                      + [{"subject": "INSTRUCTORS", "detail": "Instructor names are not stored in catalog_research; they live in professor_ratings (ADR-0006), which is for the team's own app only. Whether Banner names may be redistributed is still unverified."}],
    }
    return grid_payload, program_payload


def sql_literal(value):
    n = 0
    while f"$q{n}$" in value:
        n += 1
    return f"$q{n}${value}$q{n}$"


def build_sql(grid_payload, program_payload, label, sha, dry_run):
    return "\n".join([
        "BEGIN;",
        f"SELECT 'grid_run=' || catalog_research.import_noncse_term({sql_literal(json.dumps(grid_payload, sort_keys=True))}::jsonb, {sql_literal(label)}, '{sha}');",
        f"SELECT 'program_run=' || catalog_research.import_degree_programs({sql_literal(json.dumps(program_payload, sort_keys=True))}::jsonb, {sql_literal(label)}, '{sha}');",
        "ROLLBACK;" if dry_run else "COMMIT;"])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--export", required=True)
    ap.add_argument("--staging-db", required=True)
    ap.add_argument("--banner-dir", required=True)
    ap.add_argument("--term-code", default="202710")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    try:
        if os.environ.get("PGDATABASE", "") in SHARED_DATABASES or os.environ.get("PGPORT", "") in SHARED_PORTS:
            raise InputError("refusing: this looks like the shared database; import into an isolated local database")
        export = json.loads(Path(args.export).read_text(encoding="utf-8"))
        bd = Path(args.banner_dir)
        grid = read_csv(bd / "sections_grid.csv", GRID_COLUMNS)
        extra = read_csv(bd / "meetings_extra.csv", EXTRA_COLUMNS)
        nosec = read_csv(bd / "no_sections.csv", NOSEC_COLUMNS)
        con = sqlite3.connect(f"file:{args.staging_db}?mode=ro", uri=True)
        equivalences = [(a, b) for a, b in con.execute("SELECT course_code, equivalent_code FROM course_equivalence ORDER BY 1, 2")]
        con.close()
        grid_payload, program_payload = normalize(export, args.term_code, grid, extra, nosec, equivalences)
    except (InputError, KeyError, ValueError, OSError, sqlite3.Error) as exc:
        sys.exit("import refused: " + str(exc))
    sha = hashlib.sha256(json.dumps([grid_payload, program_payload], sort_keys=True).encode()).hexdigest()
    label = f"non-CSE staging export (term {args.term_code})"
    result = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At"], input=build_sql(grid_payload, program_payload, label, sha, args.dry_run), capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        sys.exit("import failed, nothing stored:\n" + (result.stdout + result.stderr).strip()[:1500])
    offerings = len(grid_payload["offerings"])
    sections = sum(len(o["sections"]) for o in grid_payload["offerings"])
    print(("DRY RUN (rolled back): " if args.dry_run else "stored: ") + f"{offerings} offerings, {sections} sections, {len(program_payload['programs'])} programs, "
          f"{len(program_payload['courses'])} catalog courses; input sha256 {sha[:16]}…; {result.stdout.strip().replace(chr(10), ' ')}")


if __name__ == "__main__":
    main()
