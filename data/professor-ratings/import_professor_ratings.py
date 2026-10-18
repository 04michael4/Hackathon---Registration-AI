"""Loads instructors and Rate My Professors aggregates into professor_ratings (stdlib only; needs `psql`). Prints counts, never names.

    export PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner> PGDATABASE=<local database>
    python3 -I data/professor-ratings/import_professor_ratings.py instructors --noncse-csv <instructors.csv> --cse-fixture <fixture.json> --snapshot <fixture.json>
    python3 -I data/professor-ratings/import_professor_ratings.py fetch [--from-cache <roster.json>] [--max-requests 30]
    python3 -I data/professor-ratings/import_professor_ratings.py match
    python3 -I data/professor-ratings/import_professor_ratings.py fetch-ratings [--from-cache <ratings.json>] [--max-requests 80]
    python3 -I data/professor-ratings/import_professor_ratings.py decisions --export <file> | --apply <file>
    python3 -I data/professor-ratings/import_professor_ratings.py report

instructors  reads the Banner inputs, skips "Staff"/"TBA" placeholders, stores the people and their sections.
fetch        downloads the school's RMP roster once (cached outside the repository), keeps ONLY professors whose last name equals an instructor's,
             and stores their rating aggregates. Run `instructors` first.
match        proposes matches in the database (exact unique name -> accepted; near or ambiguous -> review queue; none -> no match).
fetch-ratings  downloads the individual ratings (date and scores only, no text) of every ACCEPTED professor that has ratings, for the
             semester trend views. Run `match` (and any review) first. Raw copy cached outside the repository.
decisions    exports the people's match decisions to a local file, or re-applies them (by instructor name) after a rebuild or into another database.
report       match and coverage counts.
Every command except `report` takes --dry-run (rolls back). The shared database is refused unless --allow-shared is given.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))   # python3 -I does not add the script directory

import collect_instructors
import rmp_client
from professor_names import rmp_name_keys

LEHIGH_SCHOOL_LEGACY_ID = 509
SHARED_DATABASES = {"registration_cse007_local", "registration_unified_spring2027"}
SHARED_PORTS = {"55433"}
DEFAULT_CACHE = Path.home() / ".registration-unified" / "rmp"


class ImportError_(Exception):
    pass


def sql_literal(value):
    n = 0
    while f"$q{n}$" in value:
        n += 1
    return f"$q{n}${value}$q{n}$"


def run_psql(sql):
    result = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At"], input=sql, capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        raise ImportError_("psql failed, nothing stored:\n" + (result.stdout + result.stderr).strip()[:1500])
    return result.stdout.strip()


def sha256_of(payload):
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()


def build_rmp_payload(teachers, wanted_last_keys, fetched_at, school_legacy_id=LEHIGH_SCHOOL_LEGACY_ID):
    """Keeps only professors whose folded last name matches an instructor's; converts RMP's field names to the SQL payload."""
    kept = []
    for t in teachers:
        keys = rmp_name_keys(t.get("firstName"), t.get("lastName"))
        if keys is None or keys[1] not in wanted_last_keys:
            continue
        kept.append({"legacyId": t["legacyId"], "nodeId": t["id"], "firstName": t["firstName"] or "", "lastName": t["lastName"], "firstKey": keys[0], "lastKey": keys[1],
                     "department": t.get("department"), "avgRating": t.get("avgRating"), "avgDifficulty": t.get("avgDifficulty"),
                     "wouldTakeAgainPercent": -1 if t.get("wouldTakeAgainPercent") is None else t["wouldTakeAgainPercent"], "numRatings": t.get("numRatings") or 0})
    kept.sort(key=lambda t: t["legacyId"])
    return {"schoolLegacyId": school_legacy_id, "fetchedAt": fetched_at, "teachers": kept}


def guard(args):
    if getattr(args, "allow_shared", False):
        return
    if os.environ.get("PGDATABASE", "") in SHARED_DATABASES or os.environ.get("PGPORT", "") in SHARED_PORTS:
        raise ImportError_("refusing: this is the shared database; use an isolated local database, or pass --allow-shared once the team has approved the install")
    if os.environ.get("PGHOST", "127.0.0.1") not in ("127.0.0.1", "localhost", "::1", ""):
        raise ImportError_("refusing: PGHOST is not loopback")


def finish(args, body, summary):
    out = run_psql("\n".join(["BEGIN;", body, "ROLLBACK;" if args.dry_run else "COMMIT;"]))
    print(("DRY RUN (rolled back): " if args.dry_run else "stored: ") + summary + (f" [{out.replace(chr(10), ' ')}]" if out else ""))


def cmd_instructors(args):
    payload, skipped = collect_instructors.collect(args.noncse_csv, args.cse_fixture, args.snapshot)
    if not payload["assignments"]:
        raise ImportError_("no instructor assignments found in the inputs")
    sha = sha256_of(payload)
    label = "Banner instructor assignments"
    body = f"SELECT 'run=' || professor_ratings.import_instructors({sql_literal(json.dumps(payload, sort_keys=True))}::jsonb, {sql_literal(label)}, '{sha}');"
    finish(args, body, f"{len(payload['instructors'])} instructors, {len(payload['assignments'])} section assignments, skipped {sum(skipped.values())} placeholder/unparseable entries; input sha256 {sha[:16]}…")


def cmd_fetch(args):
    wanted = set(run_psql("SELECT DISTINCT last_key FROM professor_ratings.instructor ORDER BY 1;").split("\n")) - {""}
    if not wanted:
        raise ImportError_("no instructors stored; run `instructors` first")
    if args.from_cache:
        cached = json.loads(Path(args.from_cache).read_text(encoding="utf-8"))
        teachers, fetched_at = cached["teachers"], cached["fetchedAt"]
        print(f"using cache {args.from_cache}: {len(teachers)} professors")
    else:
        teachers, _ = rmp_client.fetch_school_roster(LEHIGH_SCHOOL_LEGACY_ID, max_requests=args.max_requests)
        fetched_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
        cache_dir = Path(args.cache_dir)
        cache_dir.mkdir(parents=True, exist_ok=True)
        cache_file = cache_dir / f"roster-{fetched_at[:10]}.json"
        cache_file.write_text(json.dumps({"fetchedAt": fetched_at, "teachers": teachers}), encoding="utf-8")
        print(f"fetched {len(teachers)} professors; raw copy cached outside the repository")
    payload = build_rmp_payload(teachers, wanted, fetched_at)
    sha = sha256_of(payload)
    body = f"SELECT 'run=' || professor_ratings.import_rmp_observations({sql_literal(json.dumps(payload, sort_keys=True))}::jsonb, {sql_literal('RMP roster (school ' + str(LEHIGH_SCHOOL_LEGACY_ID) + ')')}, '{sha}');"
    finish(args, body, f"{len(payload['teachers'])} professors with a last name shared by an instructor (of {len(teachers)} fetched); input sha256 {sha[:16]}…")


def rating_payload(fetched, fetched_at):
    """Raw RMP rating nodes -> SQL payload. Quality is RMP's: the mean of clarity and helpfulness (whichever exist). A rating with no
    quality score or no difficulty is dropped and counted."""
    teachers, dropped = [], 0
    for legacy_id in sorted(fetched):
        rows = []
        for r in fetched[legacy_id]["ratings"]:
            scores = [v for v in (r.get("clarityRating"), r.get("helpfulRating")) if isinstance(v, (int, float)) and v > 0]
            difficulty = r.get("difficultyRating")
            if not scores or not isinstance(difficulty, (int, float)) or difficulty <= 0 or not r.get("date") or not r.get("legacyId"):
                dropped += 1
                continue
            wta = r.get("wouldTakeAgain")
            rows.append({"ratingId": r["legacyId"], "postedOn": str(r["date"])[:10], "quality": round(sum(scores) / len(scores), 1), "difficulty": int(difficulty),
                         "wouldTakeAgain": None if wta is None else bool(wta), "classLabel": (r.get("class") or "")[:40], "isOnline": r.get("isForOnlineClass")})
        rows.sort(key=lambda x: x["ratingId"])
        teachers.append({"legacyId": legacy_id, "ratings": rows})
    return {"fetchedAt": fetched_at, "teachers": teachers}, dropped


def cmd_fetch_ratings(args):
    rows = run_psql("SELECT r.rmp_legacy_id || '|' || t.rmp_node_id FROM professor_ratings.instructor_rating r JOIN professor_ratings.rmp_teacher t USING (rmp_legacy_id) "
                    "WHERE r.num_ratings > 0 ORDER BY 1;")
    targets = [(int(a), b) for a, b in (line.split("|") for line in rows.split("\n") if line)]
    if not targets:
        raise ImportError_("no accepted professor with ratings; run `fetch` and `match` first")
    if args.from_cache:
        cached = json.loads(Path(args.from_cache).read_text(encoding="utf-8"))
        fetched, fetched_at = {int(k): v for k, v in cached["teachers"].items()}, cached["fetchedAt"]
        missing = [lid for lid, _ in targets if lid not in fetched]
        if missing:
            raise ImportError_(f"the cache lacks {len(missing)} accepted professor(s); fetch again")
        fetched = {lid: fetched[lid] for lid, _ in targets}
        print(f"using cache {args.from_cache}: {len(fetched)} professors")
    else:
        fetched = rmp_client.fetch_teacher_ratings(targets, max_requests=args.max_requests)
        fetched_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
        cache_dir = Path(args.cache_dir)
        cache_dir.mkdir(parents=True, exist_ok=True)
        (cache_dir / f"ratings-{fetched_at[:10]}.json").write_text(json.dumps({"fetchedAt": fetched_at, "teachers": {str(k): v for k, v in fetched.items()}}), encoding="utf-8")
        print(f"fetched ratings of {len(fetched)} professors; raw copy cached outside the repository")
    payload, dropped = rating_payload(fetched, fetched_at)
    short = sum(1 for lid in fetched if len(fetched[lid]["ratings"]) != fetched[lid]["reported"])
    sha = sha256_of(payload)
    body = f"SELECT 'run=' || professor_ratings.import_rmp_ratings({sql_literal(json.dumps(payload, sort_keys=True))}::jsonb, {sql_literal('RMP individual ratings')}, '{sha}');"
    total = sum(len(t["ratings"]) for t in payload["teachers"])
    finish(args, body, f"{total} ratings of {len(payload['teachers'])} professors (dropped {dropped} without scores; {short} professor(s) where RMP's count differs from the ratings returned); input sha256 {sha[:16]}…")


def cmd_decisions(args):
    if bool(args.export) == bool(args.apply):
        raise ImportError_("give exactly one of --export or --apply")
    if args.export:
        out = run_psql("SELECT coalesce(json_agg(json_build_object('bannerName', i.banner_name, 'rmpLegacyId', m.rmp_legacy_id, "
                       "'decision', CASE WHEN m.status = 'CONFIRMED' THEN 'CONFIRM' ELSE 'NO_MATCH' END, 'decidedBy', m.decided_by, 'reason', m.reason) "
                       "ORDER BY i.banner_name), '[]') FROM professor_ratings.current_instructor_match m JOIN professor_ratings.instructor i USING (instructor_id) "
                       "WHERE m.method = 'MANUAL';")
        Path(args.export).write_text(out + "\n", encoding="utf-8")
        print(f"exported {len(json.loads(out))} decision(s)")
        return
    decisions = json.loads(Path(args.apply).read_text(encoding="utf-8"))
    statements = []
    for d in decisions:
        name, rmp, decision = d["bannerName"], d["rmpLegacyId"], d["decision"]
        status = "CONFIRMED" if decision == "CONFIRM" else "NO_MATCH"
        rmp_sql = "NULL" if rmp is None else str(int(rmp))
        statements.append(
            f"DO $$ DECLARE iid bigint; BEGIN SELECT instructor_id INTO iid FROM professor_ratings.instructor WHERE banner_name = {sql_literal(name)}; "
            f"IF iid IS NULL THEN RAISE EXCEPTION 'decision names an instructor this database does not have'; END IF; "
            f"IF NOT EXISTS (SELECT 1 FROM professor_ratings.current_instructor_match WHERE instructor_id = iid AND method = 'MANUAL' AND status = '{status}' "
            f"AND rmp_legacy_id IS NOT DISTINCT FROM {rmp_sql}) THEN PERFORM professor_ratings.review_match(iid, {rmp_sql}, {sql_literal(decision)}, "
            f"{sql_literal(d['decidedBy'])}, {sql_literal(d['reason'])}); END IF; END $$;")
    finish(args, "\n".join(statements), f"{len(decisions)} decision(s) applied (already-present ones are skipped)")


def cmd_match(args):
    finish(args, "SELECT 'run=' || professor_ratings.propose_matches('name-based match proposal');", "match proposals")
    cmd_report(args)


def cmd_report(args):
    print(run_psql("SELECT match_status || '=' || instructors FROM professor_ratings.rating_coverage ORDER BY 1;").replace("\n", ", ") or "no instructors")
    row = run_psql("SELECT (SELECT count(*) FROM professor_ratings.instructor_rating), (SELECT count(*) FROM professor_ratings.review_queue), "
                   "(SELECT count(*) FROM professor_ratings.instructor_section_orphan), (SELECT count(*) FROM professor_ratings.section_without_instructor);")
    rated, queue, orphan, uncovered = row.split("|")
    print(f"rated instructors={rated}, review-queue rows={queue}, orphan assignments={orphan}, catalog sections without an instructor={uncovered}")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="command", required=True)
    for name in ("instructors", "fetch", "match", "fetch-ratings", "decisions", "report"):
        p = sub.add_parser(name)
        p.add_argument("--allow-shared", action="store_true")
        if name != "report":
            p.add_argument("--dry-run", action="store_true")
    sub.choices["instructors"].add_argument("--noncse-csv")
    sub.choices["instructors"].add_argument("--cse-fixture")
    sub.choices["instructors"].add_argument("--snapshot")
    sub.choices["fetch"].add_argument("--from-cache")
    sub.choices["fetch"].add_argument("--cache-dir", default=str(DEFAULT_CACHE))
    sub.choices["fetch"].add_argument("--max-requests", type=int, default=30)
    sub.choices["fetch-ratings"].add_argument("--from-cache")
    sub.choices["fetch-ratings"].add_argument("--cache-dir", default=str(DEFAULT_CACHE))
    sub.choices["fetch-ratings"].add_argument("--max-requests", type=int, default=80)
    sub.choices["decisions"].add_argument("--export")
    sub.choices["decisions"].add_argument("--apply")
    args = ap.parse_args(argv)
    try:
        guard(args)
        {"instructors": cmd_instructors, "fetch": cmd_fetch, "match": cmd_match, "fetch-ratings": cmd_fetch_ratings, "decisions": cmd_decisions, "report": cmd_report}[args.command](args)
    except (ImportError_, rmp_client.FetchError, KeyError, ValueError, OSError) as exc:
        sys.exit("refused: " + str(exc))


if __name__ == "__main__":
    main()
