"""Builds an ISOLATED local PostgreSQL database with every catalog layer and the researched term catalog (stdlib only; needs psql, pg_dump).

    PGHOST=127.0.0.1 PGPORT=55437 PGUSER=postgres \
      python3 -I data/catalog-research/build_local_database.py --database registration_spring2027 --owner catalog_owner --reader catalog_reader \
        --input ~/.registration-demo/spring-2027.json \
        [--evidence-from host:port/database/user]   # copy the collected Banner evidence, read-only, from another database
        [--reuse]                                   # install into an existing local database instead of creating it

Run as a role that can create roles and databases on a LOCAL cluster. In order, it:
  1. creates the owner and reader roles (no password; local trust authentication) and the database (owned by the non-superuser owner);
  2. installs, as that owner, data/cse007-snapshot, data/catalog-api, data/banner-evidence, data/catalog-promotion, data/catalog-research;
  3. optionally copies banner_evidence's rows from --evidence-from through pg_dump in a FORCED READ-ONLY session (the source is never written);
  4. imports --input (data/catalog-research/import_research_catalog.py) and grants the reader role;
  5. checks that the stored data is consistent with the evidence.
It refuses the shared database name and port, and refuses a non-loopback host. Nothing here approves, promotes or serves a course.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE.parent
SHARED_DATABASES = {"registration_cse007_local"}
SHARED_PORTS = {"55433"}
LOOPBACK = {"127.0.0.1", "localhost", "::1"}
IDENT = re.compile(r"^[a-z][a-z0-9_]{2,40}$")


def fail(message):
    sys.exit("build refused: " + message)


def psql(args, env=None, stdin=None, check=True):
    result = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", *args], input=stdin, capture_output=True, text=True,
                            env={**os.environ, **(env or {})}, timeout=900)
    if check and result.returncode != 0:
        fail("psql failed: " + (result.stdout + result.stderr).strip()[:800])
    return (result.stdout + result.stderr).strip()


def scalar(sql, env=None, database="postgres"):
    return psql(["-At", "-d", database, "-c", sql], env)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--database", required=True)
    ap.add_argument("--owner", default="catalog_owner")
    ap.add_argument("--reader", default="catalog_reader")
    ap.add_argument("--input", required=True, help="research export JSON (outside the repository)")
    ap.add_argument("--evidence-from", help="host:port/database/user of a database to copy banner_evidence rows from (read-only)")
    ap.add_argument("--reuse", action="store_true")
    args = ap.parse_args(argv)

    host, port = os.environ.get("PGHOST", ""), os.environ.get("PGPORT", "5432")
    if host not in LOOPBACK:
        fail("PGHOST must be a loopback address (a local cluster)")
    if port in SHARED_PORTS or args.database in SHARED_DATABASES:
        fail("this is the shared database's port or name; build an isolated local database")
    for name in (args.database, args.owner, args.reader):
        if not IDENT.match(name):
            fail(f"{name!r} is not a plain lowercase identifier")

    # 1. roles and database
    for role in (args.owner, args.reader):
        scalar(f"DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{role}') THEN CREATE ROLE {role} LOGIN; END IF; END $$;")
    exists = scalar(f"SELECT 1 FROM pg_database WHERE datname = '{args.database}'") == "1"
    if exists and not args.reuse:
        fail(f"database {args.database} already exists (use --reuse to install into it)")
    if not exists:
        scalar(f"CREATE DATABASE {args.database} OWNER {args.owner}")
    print(f"database {args.database}: {'reused' if exists else 'created'}, owner {args.owner}")

    # 2. layers, installed by the non-superuser owner
    owner = {"PGUSER": args.owner, "PGDATABASE": args.database}
    has_snapshot = scalar("SELECT to_regclass('cse007_snapshot.course') IS NOT NULL", owner, args.database) == "t"
    if not has_snapshot:
        psql(["-f", str(DATA / "cse007-snapshot" / "database.sql")], owner)
    for rel in ("catalog-api/install.sql", "banner-evidence/install.sql", "catalog-promotion/install.sql", "catalog-research/install.sql"):
        psql(["-f", str(DATA / rel)], owner)
        print("installed " + rel)

    # 3. evidence copy (read-only source)
    if args.evidence_from:
        m = re.fullmatch(r"([^:/]+):(\d+)/([a-z0-9_]+)/([a-z0-9_]+)", args.evidence_from)
        if not m:
            fail("--evidence-from must look like host:port/database/user")
        src_host, src_port, src_db, src_user = m.groups()
        if src_host not in LOOPBACK:
            fail("the evidence source must be on a loopback address")
        if scalar("SELECT count(*) FROM banner_evidence.evidence_source", owner, args.database) != "0":
            fail("banner_evidence already has rows; not copying evidence over them")
        with tempfile.TemporaryDirectory() as tmp:
            dump = Path(tmp) / "evidence.sql"
            subprocess.run(["pg_dump", "-w", "-h", src_host, "-p", src_port, "-U", src_user, "-d", src_db, "--data-only", "--schema=banner_evidence",
                            "--exclude-table-data=banner_evidence.claim_type_policy", "--no-owner", "--no-privileges", "-f", str(dump)],
                           check=True, capture_output=True, timeout=900, env={**os.environ, "PGOPTIONS": "-c default_transaction_read_only=on"})
            # The copy is loaded as the cluster administrator with triggers off: it is a faithful copy of rows that already passed them.
            psql(["-d", args.database, "--single-transaction"], stdin="SET session_replication_role = replica;\n" + dump.read_text(encoding="utf-8"))
        print("copied banner_evidence rows (source opened read-only)")

    # 4. import and grant
    env = {**os.environ, **owner}
    result = subprocess.run([sys.executable, "-I", str(HERE / "import_research_catalog.py"), "--input", args.input], env=env, capture_output=True, text=True)
    if result.returncode != 0:
        fail((result.stdout + result.stderr).strip()[:1200])
    print(result.stdout.strip())
    psql(["-f", str(DATA / "catalog-promotion" / "grant_reader.sql"), "-v", f"reader={args.reader}"], owner)
    psql(["-f", str(HERE / "grant_reader.sql"), "-v", f"reader={args.reader}"], owner)

    # 5. consistency
    mismatches = scalar("SELECT count(*) FROM catalog_research.evidence_mismatch", owner, args.database)
    if mismatches != "0":
        fail(f"{mismatches} stored rows disagree with the evidence")
    print("evidence_mismatch: 0 rows")


if __name__ == "__main__":
    main()
