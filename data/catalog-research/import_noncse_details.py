"""Imports Banner Class Details captures of the grid-staged (non-CSE) courses into catalog_research (stdlib only; needs `psql`).

    export PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner> PGDATABASE=<database>
    python3 -I data/catalog-research/import_noncse_details.py --captures <directory of popup JSON files> [--dry-run] [--allow-shared]

--captures   a directory (outside the repository) of RAW outputs of data/banner-evidence/collector/extract_popup.js: one JSON file per CRN, or the
             array file downloaded by collect_visible.js. Every capture is re-validated here (view, term, CRN, no e-mail address or long digit run). A file that reports an extractor error is refused.
Reads only. Writes (in one transaction, rolled back by --dry-run): requirement statuses, eligibility rules, lab/recitation links, the
inspection record. It prints course IDs and counts, never page text. Nothing is approved, promoted or served by this import; the tier of an
inspected course becomes CONDITIONAL only through catalog_research.offering_tier. The shared database is refused unless --allow-shared.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))   # python3 -I does not add the script directory

import noncse_details as nd

SHARED_DATABASES = {"registration_cse007_local", "registration_unified_spring2027"}
SHARED_PORTS = {"55433"}
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}")
LONG_DIGITS = re.compile(r"\d{8,}")


class ImportRefused(Exception):
    pass


def sql_literal(value):
    n = 0
    while f"$q{n}$" in value:
        n += 1
    return f"$q{n}${value}$q{n}$"


def run_psql(sql):
    r = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At", "-F", "|"], input=sql, capture_output=True, text=True, timeout=600)
    if r.returncode != 0:
        raise ImportRefused("psql failed, nothing stored:\n" + (r.stdout + r.stderr).strip()[:1500])
    return r.stdout.strip()


def load_popups(directory):
    files = sorted(Path(directory).rglob("*.json"))
    if not files:
        raise ImportRefused(f"no .json files under {directory}")
    popups, hashes = {}, []
    for f in files:
        raw = f.read_bytes()
        hashes.append(hashlib.sha256(raw).hexdigest())
        try:
            d = json.loads(raw.decode("utf-8"))
        except ValueError:
            raise ImportRefused(f"{f.name}: not valid JSON")
        items = d if isinstance(d, list) else [d]                  # one popup per file, or an array of popups (collect_visible.js)
        text = raw.decode("utf-8")
        if EMAIL.search(text) or LONG_DIGITS.search(text):
            raise ImportRefused(f"{f.name}: contains an e-mail address or a long digit run (personal-data tripwire)")
        for d in items:
            if not isinstance(d, dict) or "error" in d:
                raise ImportRefused(f"{f.name}: the extractor reported {d.get('error') if isinstance(d, dict) else 'a non-object'}")
            if d.get("view") != "CLASS_DETAILS_POPUP":
                raise ImportRefused(f"{f.name}: not a Class Details capture")
            if d.get("term_code") != nd.TERM or not re.fullmatch(r"\d{5}", str(d.get("crn", ""))):
                raise ImportRefused(f"{f.name}: wrong term or CRN")
            if d["crn"] in popups:
                raise ImportRefused(f"{f.name}: CRN {d['crn']} appears twice")
            popups[d["crn"]] = d
    return popups, hashlib.sha256("".join(sorted(hashes)).encode()).hexdigest()


def staged_sections():
    rows = run_psql("SELECT s.crn, s.offering_id, s.component_type FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id) "
                    "WHERE o.data_source = 'BANNER_GRID_STAGING' AND s.term_code = '202710' ORDER BY 1;")
    return {crn: (off, comp) for crn, off, comp in (line.split("|") for line in rows.splitlines() if line)}


def build(popups, staged):
    unknown = sorted(set(popups) - set(staged))
    if unknown:
        raise ImportRefused(f"{len(unknown)} captured CRN(s) are not staged non-CSE sections: {', '.join(unknown[:8])}")
    by_course = {}
    for crn, p in popups.items():
        by_course.setdefault(staged[crn][0], []).append(p)
    courses, problems = [], []
    for course in sorted(by_course):
        payload = nd.build_course_payload(course, by_course[course])
        lecture_of = {}
        for link in payload["links"]:
            if lecture_of.setdefault(link["lab"], link["lecture"]) != link["lecture"]:
                problems.append(f"{course}: lab {link['lab']} is linked to more than one lecture")
            if staged.get(link["lab"], ("", ""))[0] != course or staged.get(link["lecture"], ("", ""))[0] != course:
                problems.append(f"{course}: link {link['lab']} -> {link['lecture']} leaves the course")
        courses.append(payload)
    if problems:
        raise ImportRefused("; ".join(problems))
    return courses


def guard(allow_shared):
    if not allow_shared and (os.environ.get("PGDATABASE", "") in SHARED_DATABASES or os.environ.get("PGPORT", "") in SHARED_PORTS):
        raise ImportRefused("refusing: this is the shared database; use an isolated copy, or pass --allow-shared once the team has approved the install")
    if os.environ.get("PGHOST", "127.0.0.1") not in ("127.0.0.1", "localhost", "::1", ""):
        raise ImportRefused("refusing: PGHOST is not loopback")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--captures", required=True)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--allow-shared", action="store_true")
    args = ap.parse_args(argv)
    try:
        guard(args.allow_shared)
        popups, capture_sha = load_popups(args.captures)
        courses = build(popups, staged_sections())
        payload = {"courses": courses, "captureSha256": capture_sha}
        sha = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
        label = f"non-CSE Class Details captures ({len(popups)} sections)"
        ids = ", ".join(sql_literal(c["courseId"]) for c in courses)
        out = run_psql("\n".join([
            "BEGIN;",
            f"SELECT 'run=' || catalog_research.import_noncse_details({sql_literal(json.dumps(payload, sort_keys=True))}::jsonb, {sql_literal(label)}, '{sha}');",
            f"SELECT 'tier|' || tier || '|' || tier_reason || '|' || n FROM (SELECT tier, tier_reason, count(*) AS n FROM catalog_research.offering_tier WHERE term_code = '202710' AND offering_id IN ({ids}) GROUP BY tier, tier_reason ORDER BY 1, 2) t;",
            f"SELECT 'mismatch|' || m.crn || '|' || m.problem || '|stored ' || m.stored || ' vs details ' || m.detail FROM catalog_research.detail_mismatch m WHERE m.term_code = '202710';",
            "ROLLBACK;" if args.dry_run else "COMMIT;"]))
    except (ImportRefused, KeyError, OSError, ValueError) as exc:
        sys.exit("refused: " + str(exc))
    text_only = {k: [c["courseId"] for c in courses if c[key]["status"] == "TEXT_ONLY"] for k, key in (("restrictions", "restrictions"), ("prerequisites", "prerequisite"), ("corequisites", "corequisite"))}
    unmapped = sorted({u for c in courses for key in ("prerequisite", "corequisite") for u in c[key].get("unmapped", [])})
    disagree = {c["courseId"]: c["disagreements"] for c in courses if c["disagreements"]}
    print(("DRY RUN (rolled back): " if args.dry_run else "stored: ") + f"{len(courses)} courses from {len(popups)} captured sections; capture sha256 {capture_sha[:16]}…")
    for line in out.splitlines():
        if line.startswith("tier|"):
            _, tier, reason, n = line.split("|")
            print(f"  {n} course(s) now {tier} ({reason})")
        elif line.startswith("mismatch|"):
            print("  MISMATCH " + line[9:])
    for k, v in text_only.items():
        if v:
            print(f"  kept as words only (not a rule), {k}: {', '.join(v)}")
    if unmapped:
        print(f"  subject names not in the map (add them to noncse_details.SUBJECTS): {', '.join(unmapped)}")
    for course, d in disagree.items():
        print(f"  sections of {course} differ: " + ", ".join(f"{x['crn']} ({x['what']})" for x in d) + " (the first section was recorded; look at the others)")


if __name__ == "__main__":
    main()
