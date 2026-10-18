"""Collects Banner instructor assignments from the local research inputs (stdlib only). Names are read and normalized; nothing is printed.

Sources (all local; the real files are never committed):
  --noncse-csv   Account 2's instructors.csv (columns term, crn, instructor, role): origin authenticated Banner grid, role Primary/Additional
  --cse-fixture  the CSE Banner staging fixture (JSON with a "section" list: term_id, crn, instructor, instructor_is_primary)
  --snapshot     the CSE 007 snapshot fixture (same section shape, plus a "term" list that maps term_id to term_code)
"""
import csv
import json
from pathlib import Path

from professor_names import parse_banner_name, split_listed

ROLE = {"Primary": "PRIMARY", "Additional": "ADDITIONAL"}


def _add(instructors, assignments, skipped, term, crn, raw, role, source):
    person = parse_banner_name(raw)
    if person is None:
        skipped["placeholder_or_unparseable"] = skipped.get("placeholder_or_unparseable", 0) + 1
        return
    instructors.setdefault(person["bannerName"], person)
    assignments[(term, str(crn), person["bannerName"], source)] = {"term": term, "crn": str(crn), "bannerName": person["bannerName"], "role": role, "source": source}


def from_noncse_csv(path, instructors, assignments, skipped):
    with open(path, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            _add(instructors, assignments, skipped, row["term"], row["crn"], row["instructor"], ROLE.get(row["role"], "LISTED"), "BANNER_GRID")


def _term_codes(fixture):
    return {t["term_id"]: t["term_code"] for t in fixture.get("term", [])}


def from_section_fixture(path, source, instructors, assignments, skipped, default_term=None):
    fixture = json.loads(Path(path).read_text(encoding="utf-8"))
    codes = _term_codes(fixture)
    for s in fixture["section"]:
        term = codes.get(s.get("term_id")) or default_term or (s.get("term_id") or "")[-6:]
        listed = split_listed(s.get("instructor"))
        role = "PRIMARY" if len(listed) == 1 and s.get("instructor_is_primary") else "LISTED"
        for raw in listed:
            _add(instructors, assignments, skipped, term, s["crn"], raw, role, source)


def collect(noncse_csv=None, cse_fixture=None, snapshot=None):
    instructors, assignments, skipped = {}, {}, {}
    if noncse_csv:
        from_noncse_csv(noncse_csv, instructors, assignments, skipped)
    if cse_fixture:
        from_section_fixture(cse_fixture, "BANNER_EVIDENCE", instructors, assignments, skipped)
    if snapshot:
        from_section_fixture(snapshot, "SNAPSHOT", instructors, assignments, skipped)
    payload = {"instructors": [instructors[k] for k in sorted(instructors)],
               "assignments": [assignments[k] for k in sorted(assignments)]}
    return payload, skipped
