"""Imports the CSB research payload into catalog_csb (stdlib only; needs `psql`).

    export PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner> PGDATABASE=<database>
    python3 -I data/csb-research/import_csb.py --payload ~/.registration-csb/csb_import_202710.json [--dry-run] [--allow-shared]

--payload    the JSON written by build_csb_dataset.py (LOCAL-ONLY: real CRNs, instructor names, catalog prose; never in the repository).
Writes only catalog_csb, in ONE transaction that --dry-run rolls back: catalog_csb.import_csb replaces the schema's own data from the payload and
refuses to finish if it disagrees with the verbatim grid rows or the Linked Sections panels. Rerunning with the same payload gives identical
content (compare `fingerprint` before and after). It prints counts and verdicts, never page text or names.
Refused unless --allow-shared: the shared database names and port, and any non-loopback host.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

SHARED_DATABASES = {"registration_cse007_local", "registration_unified_spring2027"}
SHARED_PORTS = {"55433"}


class ImportRefused(Exception):
    pass


def sql_literal(value):
    n = 0
    while f"$q{n}$" in value:
        n += 1
    return f"$q{n}${value}$q{n}$"


def run_psql(sql):
    r = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At", "-F", "|"], input=sql, capture_output=True, text=True, timeout=900)
    if r.returncode != 0:
        raise ImportRefused("psql failed, nothing stored:\n" + (r.stdout + r.stderr).strip()[:1500])
    return r.stdout.strip()


def guard(allow_shared):
    if not allow_shared and (os.environ.get("PGDATABASE", "") in SHARED_DATABASES or os.environ.get("PGPORT", "") in SHARED_PORTS):
        raise ImportRefused("refusing: this is the shared database; use an isolated copy, or pass --allow-shared once the team has approved the install")
    if os.environ.get("PGHOST", "127.0.0.1") not in ("127.0.0.1", "localhost", "::1", ""):
        raise ImportRefused("refusing: PGHOST is not loopback")


FINGERPRINT_SQL = """
SELECT 'fingerprint|' || md5(string_agg(t, '' ORDER BY k COLLATE "C")) FROM (
	SELECT 'a' || x.crn AS k, row_to_json(x)::text AS t FROM (SELECT * FROM catalog_csb.section ORDER BY crn) x
	UNION ALL SELECT 'b' || m.term_code || m.crn || m.meeting_kind || m.day_of_week || m.start_time || m.start_date, row_to_json(m)::text FROM (SELECT term_code, crn, day_of_week, start_time, end_time, location, meeting_type, meeting_kind, start_date, end_date FROM catalog_csb.section_meeting) m
	UNION ALL SELECT 'c' || i.crn || i.position, row_to_json(i)::text FROM catalog_csb.section_instructor i
	UNION ALL SELECT 'd' || d.crn, row_to_json(d)::text FROM catalog_csb.section_detail d
	UNION ALL SELECT 'e' || l.panel_crn || l.option_no || l.member_crn, row_to_json(l)::text FROM catalog_csb.section_link_option l
	UNION ALL SELECT 'f' || c.course_id, row_to_json(c)::text FROM catalog_csb.catalog_course c
	UNION ALL SELECT 'g' || o.offering_id, row_to_json(o)::text FROM (SELECT term_code, offering_id, course_id, title, credits_text, credits_status, administrative_type, components_status FROM catalog_csb.offering) o
	UNION ALL SELECT 'h' || g.group_key, row_to_json(g)::text FROM (SELECT group_key, logic, n, credits, status FROM catalog_csb.requirement_group) g
	UNION ALL SELECT 'i' || e.list_key || e.course_id || e.member_group || e.track, row_to_json(e)::text FROM catalog_csb.elective_list_member e
	UNION ALL SELECT 'j' || n.sequence_key || n.ordinal, row_to_json(n)::text FROM catalog_csb.ns_sequence_course n
) z;
"""
COUNT_SQL = """
SELECT 'counts|' || json_build_object(
	'catalogCourses', (SELECT count(*) FROM catalog_csb.catalog_course), 'offerings', (SELECT count(*) FROM catalog_csb.offering), 'sections', (SELECT count(*) FROM catalog_csb.section),
	'weeklyMeetings', (SELECT count(*) FROM catalog_csb.section_meeting WHERE meeting_kind = 'WEEKLY'), 'datedMeetings', (SELECT count(*) FROM catalog_csb.section_meeting WHERE meeting_kind = 'DATED_ONE_TIME'),
	'arrangedMeetings', (SELECT count(*) FROM catalog_csb.section_arranged_meeting), 'instructorEntries', (SELECT count(*) FROM catalog_csb.section_instructor),
	'linkListings', (SELECT count(*) FROM catalog_csb.section_link_option), 'rules', (SELECT count(*) FROM catalog_csb.section_rule), 'crossListRows', (SELECT count(*) FROM catalog_csb.section_cross_list),
	'searches', (SELECT count(*) FROM catalog_csb.banner_search), 'requirementGroups', (SELECT count(*) FROM catalog_csb.requirement_group), 'nsSequences', (SELECT count(*) FROM catalog_csb.ns_sequence),
	'gridMismatch', (SELECT count(*) FROM catalog_csb.grid_mismatch), 'linkStatusMismatch', (SELECT count(*) FROM catalog_csb.link_status_mismatch))::text;
"""


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--payload", required=True)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--allow-shared", action="store_true")
    args = ap.parse_args(argv)
    try:
        guard(args.allow_shared)
        raw = Path(args.payload).read_bytes()
        sha = hashlib.sha256(raw).hexdigest()
        payload = json.loads(raw.decode("utf-8"))
        label = f"CSB research term {payload['termCode']} ({len(payload['sections'])} sections, {len(payload['offerings'])} offerings)"
        out = run_psql("\n".join([
            "BEGIN;",
            f"SELECT 'run=' || catalog_csb.import_csb({sql_literal(json.dumps(payload, sort_keys=True))}::jsonb, {sql_literal(label)}, '{sha}');",
            COUNT_SQL, FINGERPRINT_SQL,
            "ROLLBACK;" if args.dry_run else "COMMIT;"]))
    except (ImportRefused, KeyError, OSError, ValueError) as exc:
        sys.exit("refused: " + str(exc))
    print(("DRY RUN (rolled back): " if args.dry_run else "stored: ") + label + f"; payload sha256 {sha[:16]}…")
    for line in out.splitlines():
        if line.startswith("counts|"):
            print("  counts " + line[7:])
        elif line.startswith("fingerprint|"):
            print("  " + line)
        elif line.startswith("run="):
            print("  import_run " + line[4:])


if __name__ == "__main__":
    main()
