"""Proves the install, rollback and recovery procedures of catalog_csb on a SCRATCH database (stdlib only; needs psql, pg_dump, pg_restore).

    PGHOST=127.0.0.1 PGPORT=<local port> PGUSER=<local superuser> python3 -I data/csb-research/run_rollback_validation.py
        [--dump FILE --owner ROLE]    # start from a pg_dump -Fc of a COPY of the team database instead of a stand-in catalog_research

Everything outside catalog_csb is compared before and after each step at three levels: the full schema DDL (pg_dump -s, which includes owners and GRANTs),
the row content of every table (fingerprint_other_schemas.sql) and the cluster-level facts a schema drop could touch (default ACLs, extensions, role
memberships). Only the SYNTHETIC fixture is ever imported. Refuses the shared database's port and name and any non-loopback host. Prints verdicts only.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import run_csb_validation as v  # noqa: E402  (psql helper, STUB, import_sql, the synthetic payload)
import synthetic_fixture as fx  # noqa: E402

RESULTS = []
GLOBALS_SQL = """
SELECT 'default_acl|' || count(*) || '|' || coalesce(md5(string_agg(a.defaclrole::regrole::text || a.defaclobjtype::text || a.defaclacl::text, ',' ORDER BY a.oid::text COLLATE "C")), '') FROM pg_default_acl a
UNION ALL SELECT 'extensions|' || string_agg(extname, ',' ORDER BY extname COLLATE "C") FROM pg_extension
UNION ALL SELECT 'memberships|' || count(*) FROM pg_auth_members
UNION ALL SELECT 'schemas|' || string_agg(nspname || ':' || pg_get_userbyid(nspowner) || ':' || coalesce(nspacl::text, ''), ',' ORDER BY nspname COLLATE "C") FROM pg_namespace WHERE nspname NOT LIKE 'pg\\_%' AND nspname <> 'catalog_csb'
UNION ALL SELECT 'relations|' || count(*) || '|' || md5(string_agg(n.nspname || '.' || c.relname || ':' || c.relkind::text || ':' || pg_get_userbyid(c.relowner) || ':' || coalesce(c.relacl::text, ''), ',' ORDER BY n.nspname COLLATE "C", c.relname COLLATE "C"))
	FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname NOT LIKE 'pg\\_%' AND n.nspname NOT IN ('information_schema', 'catalog_csb')
"""


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else ("\n      " + str(detail)[:500] if detail else "")))
    return ok


def tool(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=900, **kw)
    return r.returncode, (r.stdout + r.stderr)


def ddl(db, exclude_csb):
    cmd = ["pg_dump", "-s", "--no-comments"] + (["--exclude-schema=catalog_csb"] if exclude_csb else []) + [db]
    rc, out = tool(cmd)
    if rc != 0:
        raise RuntimeError("pg_dump failed: " + out[-300:])
    # pg_dump orders objects by their dependencies, and a view in another schema can change that order without changing any object, so compare
    # the statements as a sorted multiset (every changed, added or removed statement still shows)
    lines = [line for line in out.splitlines() if line.strip() and not line.startswith(("--", "\\restrict", "\\unrestrict", "SET ", "SELECT pg_catalog.set_config"))]
    return "\n".join(sorted(lines))


def state(db, exclude_csb=True):
    rows = v.psql(db, file=HERE / "fingerprint_other_schemas.sql")[1]
    globals_ = v.psql(db, GLOBALS_SQL)[1]
    return {"ddl": ddl(db, exclude_csb), "rows": rows, "globals": globals_}


def same(a, b):
    return a["ddl"] == b["ddl"] and a["rows"] == b["rows"] and a["globals"] == b["globals"]


def csb_fingerprint(db):
    sql = (HERE / "import_csb.py").read_text(encoding="utf-8").split('FINGERPRINT_SQL = """')[1].split('"""')[0]
    return v.psql(db, sql)[1]


def as_user(user, fn):
    old = os.environ.get("PGUSER")
    if user:
        os.environ["PGUSER"] = user
    try:
        return fn()
    finally:
        if user:
            if old is None:
                os.environ.pop("PGUSER", None)
            else:
                os.environ["PGUSER"] = old


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dump")
    ap.add_argument("--owner")
    args = ap.parse_args()
    v.env()
    if bool(args.dump) != bool(args.owner):
        sys.exit("--dump and --owner go together")
    tag = uuid.uuid4().hex[:8]
    db, db_restored, db_failed = f"csb_rb_{tag}", f"csb_rb_{tag}_restored", f"csb_rb_{tag}_failed"
    names = [db, db_restored, db_failed, f"{db}_old"]
    try:
        run(args, tag, db, db_restored, db_failed)
    finally:
        for n in names:
            v.psql("postgres", f"DROP DATABASE IF EXISTS {n} WITH (FORCE)", check=False)
    failed = [n for n, ok in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)} of {len(RESULTS)} checks passed" + (": FAILED " + ", ".join(failed) if failed else ""))
    sys.exit(1 if failed else 0)


def build(args, db):
    v.psql("postgres", f"CREATE DATABASE {db}" + (f" OWNER {args.owner}" if args.owner else ""))
    if args.dump:
        rc, out = tool(["pg_restore", "--no-owner", f"--role={args.owner}", "-d", db, args.dump])
        if rc != 0:
            raise RuntimeError("pg_restore failed: " + out[-400:])
    else:
        v.psql(db, v.STUB)


def run(args, tag, db, db_restored, db_failed):
    owner = args.owner
    base = fx.make_payload(v.PROGRAM)
    build(args, db)
    p0 = state(db, exclude_csb=False)
    check("start: the database has no catalog_csb", v.psql(db, "SELECT to_regnamespace('catalog_csb') IS NULL")[1] == "t")
    with tempfile.TemporaryDirectory() as tmp:
        backup0 = str(Path(tmp) / "before-csb.dump")
        rc, out = tool(["pg_dump", "-Fc", "-f", backup0, db])
        check("backup before the install (pg_dump -Fc of the whole database)", rc == 0, out)

        # ---- a failed install leaves nothing behind (one transaction) ---------------------------------------------------------------
        build(args, db_failed)
        pf = state(db_failed, exclude_csb=False)
        sabotage = Path(tmp) / "sabotaged"
        sabotage.mkdir()
        for f in HERE.glob("*.sql"):
            (sabotage / f.name).write_text("DO $$ BEGIN RAISE EXCEPTION 'sabotaged structure check'; END $$;\n" if f.name == "verify_structure.sql" else f.read_text(encoding="utf-8"), encoding="utf-8")
        rc, out = as_user(owner, lambda: v.psql(db_failed, file=sabotage / "install.sql", check=False))
        check("an install whose structure check fails commits nothing (no schema, nothing else changed)",
              rc != 0 and "sabotaged" in out and v.psql(db_failed, "SELECT to_regnamespace('catalog_csb') IS NULL")[1] == "t" and same(state(db_failed, exclude_csb=False), pf), out[-300:])

        # negative controls: the comparison must notice a one-table, a one-row and a GRANT difference (so "unchanged" means something)
        v.psql(db_failed, "CREATE TABLE public.rb_canary (x int)")
        check("negative control: a new table outside catalog_csb is noticed", not same(pf, state(db_failed, exclude_csb=False)))
        v.psql(db_failed, "INSERT INTO public.rb_canary VALUES (1)")
        pc = state(db_failed, exclude_csb=False)
        v.psql(db_failed, "UPDATE public.rb_canary SET x = 2")
        check("negative control: a changed row is noticed", not same(pc, state(db_failed, exclude_csb=False)))
        v.psql(db_failed, "GRANT SELECT ON public.rb_canary TO PUBLIC")
        check("negative control: a changed GRANT is noticed", not same(pc, state(db_failed, exclude_csb=False)))

        # ---- install, import, coverage views: nothing outside catalog_csb changes ------------------------------------------------------
        rc, out = as_user(owner, lambda: v.psql(db, file=HERE / "install.sql", check=False))
        check("install.sql (default: no coverage views)", rc == 0, out[-300:])
        p1 = state(db)
        check("after the install: every table's rows, the whole DDL outside catalog_csb, owners, GRANTs and cluster facts are unchanged", same(p0, p1) or _diff("install", p0, p1))
        rc, out = as_user(owner, lambda: v.psql(db, v.import_sql(base), check=False))
        check("synthetic import succeeds", rc == 0, out[-300:])
        check("after the import: nothing outside catalog_csb changed", same(p0, state(db)) or _diff("import", p0, state(db)))
        rc, out = as_user(owner, lambda: v.psql(db, file=HERE / "install_coverage_views.sql", check=False))
        check("install_coverage_views.sql (optional second step)", rc == 0, out[-300:])
        check("after the coverage views: nothing outside catalog_csb changed", same(p0, state(db)) or _diff("coverage", p0, state(db)))
        installed = csb_fingerprint(db)
        backup_csb = str(Path(tmp) / "catalog_csb-only.dump")
        rc, out = tool(["pg_dump", "-Fc", "-n", "catalog_csb", "-f", backup_csb, db])
        check("schema-only backup of catalog_csb (pg_dump -n catalog_csb)", rc == 0, out)

        # ---- what a drop would do ---------------------------------------------------------------------------------------------------------
        rc, out = as_user(owner, lambda: v.psql(db, "DROP SCHEMA catalog_csb", check=False))
        check("DROP SCHEMA catalog_csb (RESTRICT) refuses, and every object it lists is inside catalog_csb",
              rc != 0 and "depend" in out and not [n for n in re.findall(r"(?:table|view|function|sequence|type) ([a-z_]+)\.", out) if n != "catalog_csb"], out[-400:])
        rc, out = as_user(owner, lambda: v.psql(db, "BEGIN; DROP SCHEMA catalog_csb CASCADE; ROLLBACK;", check=False))
        check("DROP SCHEMA ... CASCADE inside a transaction that is rolled back removes nothing",
              rc == 0 and csb_fingerprint(db) == installed and same(p0, state(db)), out[-300:])

        # ---- rollback.sql refuses while anything outside catalog_csb depends on the schema ----------------------------------------------
        for label, create, drop in (
                ("a view", "CREATE VIEW public.rb_probe AS SELECT crn FROM catalog_csb.section", "DROP VIEW public.rb_probe"),
                ("a foreign key", "CREATE TABLE public.rb_probe (term_code text, crn text, FOREIGN KEY (term_code, crn) REFERENCES catalog_csb.section (term_code, crn))", "DROP TABLE public.rb_probe"),
                ("a column default", "CREATE TABLE public.rb_probe (x bigint DEFAULT nextval('catalog_csb.import_run_run_id_seq'))", "DROP TABLE public.rb_probe")):
            ok_probe = v.psql(db, create, check=False)[0] == 0
            rc, out = as_user(owner, lambda: v.psql(db, file=HERE / "rollback.sql", check=False))
            still = csb_fingerprint(db) == installed
            v.psql(db, drop, check=False)
            check(f"rollback.sql refuses to drop catalog_csb while {label} outside it depends on it, and drops nothing", ok_probe and rc != 0 and "refusing to drop catalog_csb" in out and still, out[-300:])

        # ---- the rollback ---------------------------------------------------------------------------------------------------------------------
        rc, out = as_user(owner, lambda: v.psql(db, file=HERE / "rollback.sql", check=False))
        check("rollback.sql (guard, then DROP SCHEMA catalog_csb CASCADE) succeeds", rc == 0 and v.psql(db, "SELECT to_regnamespace('catalog_csb') IS NULL")[1] == "t", out[-300:])
        rc, out = as_user(owner, lambda: v.psql(db, file=HERE / "rollback.sql", check=False))
        check("rollback.sql is safe to run again when catalog_csb is already gone", rc == 0, out[-300:])
        p_after_drop = state(db, exclude_csb=False)
        check("after the rollback the whole database equals the state before the install (DDL, rows, owners, GRANTs, default ACLs)", same(p0, p_after_drop) or _diff("rollback", p0, p_after_drop))

        # ---- restore only the schema again --------------------------------------------------------------------------------------------------
        rc, out = tool(["pg_restore", "--no-owner"] + ([f"--role={owner}"] if owner else []) + ["-d", db, backup_csb])
        check("pg_restore of the schema-only backup re-creates catalog_csb", rc == 0 and csb_fingerprint(db) == installed, out[-300:])
        rc, out = as_user(owner, lambda: v.psql(db, file=HERE / "verify_structure.sql", check=False))
        check("the restored schema passes the structure checks", rc == 0, out[-300:])
        check("and nothing outside catalog_csb changed", same(p0, state(db)) or _diff("schema restore", p0, state(db)))

        # ---- restore the whole database from the pre-install backup, then swap the names ------------------------------------------------
        v.psql("postgres", f"CREATE DATABASE {db_restored}" + (f" OWNER {owner}" if owner else ""))
        rc, out = tool(["pg_restore", "--no-owner"] + ([f"--role={owner}"] if owner else []) + ["-d", db_restored, backup0])
        p_restored = state(db_restored, exclude_csb=False)
        check("a full restore of the pre-install backup into a new database equals the original state", rc == 0 and (same(p0, p_restored) or _diff("full restore", p0, p_restored)), out[-300:])
        rc1, o1 = v.psql("postgres", f"ALTER DATABASE {db} RENAME TO {db}_old", check=False)
        rc2, o2 = v.psql("postgres", f"ALTER DATABASE {db_restored} RENAME TO {db}", check=False)
        check("swapping the restored database in by renaming works when nobody is connected", rc1 == 0 and rc2 == 0 and same(p0, state(db, exclude_csb=False)), o1 + o2)


def _diff(step, a, b):
    for key in ("ddl", "rows", "globals"):
        if a[key] != b[key]:
            x, y = a[key].splitlines(), b[key].splitlines()
            gone = [line for line in x if line not in set(y)][:3]
            new = [line for line in y if line not in set(x)][:3]
            print(f"      {step}: {key} differs; first removed {gone}; first added {new}")
    return False


if __name__ == "__main__":
    main()
