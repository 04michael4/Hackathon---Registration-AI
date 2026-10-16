"""Validation for banner_evidence (stdlib only; needs `psql` on PATH).

Connection settings come from the usual PG* variables (PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSFILE); nothing
secret is read or printed here.

    python3 -I data/banner-evidence/run_validation.py

What it does, in order (it stops at the first failure):
  1. Records fingerprints of David's 11 tables and of the five catalog_api views.
  2. Proves the install works in a transaction that is ROLLED BACK (and leaves nothing behind).
  3. Installs for real (one committing transaction), reinstalls, and shows the definitions are identical.
  4. Imports the seven user-reported links twice and shows the second run changes nothing.
  5. Runs the data verification and the 78 probes (the probes always roll back).
  6. Mutation checks: weakens one guard at a time in a rolled-back copy and requires the probes to FAIL; then the
     collector unit tests, the weakened-importer checks and the synthetic database scenarios.
  7. Runs David's own verify.sql and the catalog_api checks (read-only or always rolled back).
  8. Shows David's tables and the catalog_api views are byte-for-byte as before.
It never touches a cse007_snapshot or catalog_api object except to read it.
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
V1 = (HERE / "V1__banner_evidence_schema.sql").read_text(encoding="utf-8")
COLLECTOR = HERE / "collector"
EVIDENCE_TABLES = ["evidence_source", "section_observation", "meeting_observation", "link_observation",
                   "note_observation", "section_field_observation", "claim_type_policy", "claim", "claim_evidence", "claim_review"]
PROBE_FAILURE = re.compile(r"probe failed|FAIL: expected|division by zero")


def psql(args, timeout=180):
    result = subprocess.run(["psql", "-X", "-v", "ON_ERROR_STOP=1", *args], capture_output=True, text=True,
                            timeout=timeout, cwd=ROOT)
    return result.returncode, result.stdout + result.stderr


def query(sql):
    code, out = psql(["-At", "-c", sql])
    if code != 0:
        sys.exit("query failed: " + out.strip()[:300])
    return out.strip()


def run_file(path):
    return psql(["-f", str(path)])


def step(title):
    print(f"\n== {title}")


def check(condition, message):
    if not condition:
        sys.exit("FAILED: " + message)
    print("  ok: " + message)


def david_fingerprint():
    return query(
        "SELECT string_agg(tablename || ' ' || (xpath('/row/h/text()', query_to_xml(format("
        "'select md5(coalesce(string_agg(r, E''\\n'' ORDER BY r), '''')) as h from (select t::text as r from %I.%I t) q', "
        "schemaname, tablename), false, true, '')))[1]::text || ' ' || "
        "(xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', schemaname, tablename), false, true, '')))[1]::text, "
        "E'\\n' ORDER BY tablename) FROM pg_tables WHERE schemaname = 'cse007_snapshot'")


def catalog_views_fingerprint():
    return query(
        "SELECT coalesce(md5(string_agg(c.relname || pg_get_viewdef(c.oid), ',' ORDER BY c.relname)), 'none') FROM pg_class c "
        "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'catalog_api' AND c.relkind = 'v'")


def schema_exists():
    return query("SELECT to_regnamespace('banner_evidence') IS NOT NULL") == "t"


DDL_SQL = """
SELECT md5(string_agg(x, E'\\n' ORDER BY x)) FROM (
  SELECT 'col|' || c.table_name || '|' || c.column_name || '|' || c.data_type || '|' || c.is_nullable || '|' || coalesce(c.column_default, '') AS x
    FROM information_schema.columns c WHERE c.table_schema = 'banner_evidence'
  UNION ALL SELECT 'con|' || conrelid::regclass::text || '|' || conname || '|' || pg_get_constraintdef(oid)
    FROM pg_constraint WHERE connamespace = 'banner_evidence'::regnamespace
  UNION ALL SELECT 'idx|' || indexdef FROM pg_indexes WHERE schemaname = 'banner_evidence'
  UNION ALL SELECT 'trg|' || pg_get_triggerdef(t.oid) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
    WHERE c.relnamespace = 'banner_evidence'::regnamespace AND NOT t.tgisinternal
  UNION ALL SELECT 'view|' || c.relname || pg_get_viewdef(c.oid) FROM pg_class c
    WHERE c.relnamespace = 'banner_evidence'::regnamespace AND c.relkind = 'v'
  UNION ALL SELECT 'fn|' || pg_get_functiondef(p.oid) FROM pg_proc p WHERE p.pronamespace = 'banner_evidence'::regnamespace
  UNION ALL SELECT 'policy|' || claim_type || banner_can_verify::text FROM banner_evidence.claim_type_policy
) q
"""


def ddl_fingerprint():
    return query(DDL_SQL)


def row_fingerprint():
    parts = []
    for table in EVIDENCE_TABLES:
        parts.append(table + ":" + query(
            f"SELECT count(*) || ' ' || md5(coalesce(string_agg(t::text, E'\\n' ORDER BY t::text), '')) FROM banner_evidence.{table} t"))
    return "\n".join(parts)


def driver(workdir, name, *body):
    path = workdir / name
    path.write_text("\\set ON_ERROR_STOP on\nBEGIN;\n" + "\n".join(body) + "\nROLLBACK;\n", encoding="utf-8")
    return path


def mutate(text, old, new, label):
    if text.count(old) != 1:
        sys.exit(f"mutation '{label}' does not apply: expected exactly one occurrence, found {text.count(old)}")
    return text.replace(old, new, 1)


MUTATIONS = [
    ("append-only trigger does nothing",
     lambda s: re.sub(r"RAISE EXCEPTION 'banner_evidence\.% is append-only.*?integrity_constraint_violation';", "RETURN NULL;", s, count=1, flags=re.S)),
    ("is_safe_text accepts everything",
     lambda s: mutate(s, "SELECT value IS NULL OR (", "SELECT true OR (", "safe text")),
    ("Banner alone can verify any claim type",
     lambda s: mutate(s, "CASE WHEN p.banner_can_verify THEN 5 ELSE 2 END", "5", "banner verify")),
    ("promotion no longer needs a human review",
     lambda s: mutate(s, "AND review_state = 'CONFIRMED' AND NOT in_conflict", "AND NOT in_conflict", "review gate")),
    ("promotion accepts user-reported evidence",
     lambda s: mutate(s, "evidence_status IN ('VERIFIED_BANNER', 'TEAM_CONFIRMED')\n\t\tAND", "evidence_status IN ('VERIFIED_BANNER', 'TEAM_CONFIRMED', 'USER_REPORTED')\n\t\tAND", "user report")),
    ("conflicting claims are not detected",
     lambda s: mutate(s, "o.asserted_value <> b.asserted_value", "false", "conflict")),
    ("contradiction does not override support",
     lambda s: mutate(s, "WHEN b.contradicted THEN 'CONTRADICTED'", "WHEN false THEN 'CONTRADICTED'", "contradiction")),
    ("source path keyword guard removed",
     lambda s: mutate(s, " AND source_path !~* '(session|token|auth|cookie)'", "", "path guard")),
    ("live capture rules removed",
     lambda s: mutate(s, "WHEN 'BANNER_LIVE' THEN captured_by_role = 'CLAUDE_BROWSER' AND inspected_by_claude", "WHEN 'BANNER_LIVE' THEN true", "live rules")),
    ("evidence relevance trigger disabled",
     lambda s: mutate(s, "\tSELECT * INTO c FROM banner_evidence.claim WHERE claim_id = NEW.claim_id;", "\tRETURN NEW;\n\tSELECT * INTO c FROM banner_evidence.claim WHERE claim_id = NEW.claim_id;", "relevance")),
    ("UNKNOWN claims may carry evidence",
     lambda s: mutate(s, "IF c.asserted_value IS NULL THEN", "IF false THEN", "unknown evidence")),
    ("duplicate UNKNOWN claims allowed",
     lambda s: mutate(s, "UNIQUE NULLS NOT DISTINCT", "UNIQUE", "nulls distinct")),
    ("evidence term check removed",
     lambda s: mutate(s, "IF c.term_code <> s.term_code THEN", "IF false THEN", "term check")),
    ("evidence section-observation check removed",
     lambda s: mutate(s, "IF NOT EXISTS (SELECT 1 FROM banner_evidence.section_observation o WHERE o.source_id = NEW.source_id AND o.crn = c.crn) THEN", "IF false THEN", "section check")),
    ("link-presence check removed",
     lambda s: mutate(s, "IF NOT has_link THEN", "IF false THEN", "has_link")),
    ("'linked sections were read' check removed",
     lambda s: mutate(s, "ELSIF has_link OR NOT checked THEN", "ELSIF has_link THEN", "checked")),
    ("supersession ignored",
     lambda s: mutate(s, "(b.asserted_value IS NULL AND EXISTS", "(false AND EXISTS", "superseded")),
]


IMPORTER_MUTATIONS = [
    ("tripwires disabled", lambda s: mutate(s, "for pattern in TRIPWIRES:", "for pattern in []:", "tripwires")),
    ("unknown keys accepted", lambda s: mutate(s, "if extra or missing:", "if False:", "keys")),
    ("wrong term accepted", lambda s: mutate(s, 'if h["term_label"] != TERM_LABEL:', "if False:", "term")),
    ("partial page accepted", lambda s: mutate(s, 'if not (h["result_count"] == h["records"] == str(len(rows))):', "if False:", "count")),
    ("link orientation no longer fails closed", lambda s: re.sub(r'        else:\n            raise CollectError\(f"cannot orient[^\n]*\n', '        else:\n            pairs.append((subject_crn, s["crn"]))\n', s, count=1)),
    ("importer's own text not scanned", lambda s: mutate(s, "    validate_generated(plan)\n    return plan", "    return plan", "generated")),
    ("popup may disagree with the grid", lambda s: mutate(s, '        if (d["section"], d["title"]', '        if False and (d["section"], d["title"]', "popup")),
    ("transcription hash not checked", lambda s: mutate(s, "if expected_sha and actual != expected_sha.lower():", "if False:", "sha")),
    ("lab views may contradict", lambda s: mutate(s, 'if d["schedule_type"] == "Lecture":\n            claims["contradictions"]', 'if True:\n            claims["contradictions"]', "contradict")),
    ("hash covers the timestamp", lambda s: mutate(s, "    text = canonical(content)\n", "    text = canonical(dict(content, captured_at=captured_at))\n", "timestamp")),
]


def main():
    before_david = david_fingerprint()
    before_views = catalog_views_fingerprint()
    was_installed = schema_exists()
    before_rows = row_fingerprint() if was_installed else None
    before_ddl = ddl_fingerprint() if was_installed else None
    check(before_david.count("\n") == 10, "recorded fingerprints of David's 11 tables")
    check(before_views not in ("", "none"), "recorded the catalog_api view fingerprint")

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)

        step("1. Install in a transaction that is rolled back")
        code, out = run_file(driver(work, "rollback_install.sql", "DROP SCHEMA IF EXISTS banner_evidence CASCADE;",
                                    f"\\i {HERE / 'V1__banner_evidence_schema.sql'}", f"\\i {HERE / 'V2__collector_fields.sql'}", f"\\i {HERE / 'verify_structure.sql'}"))
        check(code == 0 and "PASS: banner_evidence structure" in out, "install + structure checks pass inside the rolled-back transaction")
        check(schema_exists() == was_installed, "the rollback left the schema exactly as it was (installed: %s)" % was_installed)
        if was_installed:
            check(row_fingerprint() == before_rows and ddl_fingerprint() == before_ddl, "existing evidence rows and definitions are untouched by the dry run")

        step("2. Install for real (one committing transaction), then reinstall")
        code, out = psql(["-f", str(HERE / "install.sql")])
        check(code == 0 and "PASS: banner_evidence structure" in out, "install.sql committed")
        first_ddl = ddl_fingerprint()
        code, out = psql(["-f", str(HERE / "install.sql")])
        check(code == 0 and ddl_fingerprint() == first_ddl, "reinstall is safe and the definitions are byte-identical")

        step("3. Import the seven user-reported links twice")
        code, out = psql(["-f", str(HERE / "import_user_reported_links.sql")])
        check(code == 0 and "PASS: banner_evidence Step 1 content intact" in out, "first import committed and verified")
        rows_after_first = row_fingerprint()
        code, out = psql(["-f", str(HERE / "import_user_reported_links.sql")])
        check(code == 0 and row_fingerprint() == rows_after_first, "second import changed no table (no duplicate claims or evidence)")
        print("  rows per table after import:")
        for line in rows_after_first.splitlines():
            print("    " + line.split(" ")[0])

        step("4. Data verification and the probes")
        code, out = psql(["-f", str(HERE / "verify_banner_evidence.sql")])
        check(code == 0 and "PASS: banner_evidence Step 1 content intact" in out, "verify_banner_evidence.sql passes")
        code, out = psql(["-f", str(HERE / "test_banner_evidence.sql")])
        passed = re.search(r"PASS: banner_evidence probes: (\d+) probes held", out)
        check(code == 0 and passed is not None, "test_banner_evidence.sql: %s probes held" % (passed.group(1) if passed else "?"))
        check(row_fingerprint() == rows_after_first, "the probes were rolled back and left every table unchanged")

        step("5. Mutation checks: each weakened guard must make the probes fail")
        for label, change in MUTATIONS:
            mutated = work / "mutated.sql"
            mutated.write_text(change(V1), encoding="utf-8")
            code, out = psql(["-f", str(driver(work, "mutation.sql", "DROP SCHEMA IF EXISTS banner_evidence CASCADE;",
                                                f"\\i {mutated}", f"\\i {HERE / 'V2__collector_fields.sql'}", "SELECT set_config('t.passed', '0', true);",
                                                f"\\i {HERE / 'seed_user_reported_links.sql'}", f"\\i {HERE / 'probes_banner_evidence.sql'}"))])
            caught = code != 0 and PROBE_FAILURE.search(out) is not None
            print(f"  {'caught ' if caught else 'MISSED '} {label}")
            if not caught:
                sys.exit("FAILED: a weakened guard went unnoticed: " + label)
        check(row_fingerprint() == rows_after_first and ddl_fingerprint() == first_ddl,
              "the mutation runs were rolled back (installed schema unchanged)")

    step("5b. The collector: unit tests, weakened-importer checks, and database scenarios on synthetic fixtures")
    result = subprocess.run([sys.executable, "-I", str(COLLECTOR / "test_banner_collect.py")], capture_output=True, text=True, timeout=120)
    ran = re.search(r"Ran (\d+) tests", result.stderr)
    check(result.returncode == 0 and ran is not None, "collector unit tests: %s tests pass" % (ran.group(1) if ran else "?"))
    original = (COLLECTOR / "banner_collect.py").read_text(encoding="utf-8")
    for label, change in IMPORTER_MUTATIONS:
        with tempfile.TemporaryDirectory() as tmp2:
            copy = Path(tmp2) / "collector"
            shutil.copytree(COLLECTOR, copy, ignore=shutil.ignore_patterns("__pycache__"))
            (copy / "banner_collect.py").write_text(change(original), encoding="utf-8")
            outcome = subprocess.run([sys.executable, "-I", str(copy / "test_banner_collect.py")], capture_output=True, text=True, timeout=120)
            caught = outcome.returncode != 0
            print(f"  {'caught ' if caught else 'MISSED '} importer weakened: {label}")
            if not caught:
                sys.exit("FAILED: a weakened importer went unnoticed: " + label)
    fixtures = [str(COLLECTOR / "fixtures" / f) for f in ("synthetic-grid.json", "synthetic-popup-99001.json", "synthetic-popup-99002.json", "synthetic-popup-99003.json")]

    def importer(*extra):
        run = subprocess.run([sys.executable, "-I", str(COLLECTOR / "banner_collect.py"), "--dry-run", "--twice", *extra, *fixtures],
                             capture_output=True, text=True, timeout=300, cwd=ROOT)
        return run.returncode, run.stdout + run.stderr

    rows_before = row_fingerprint()
    code, out = importer()
    check(code == 0 and "second run in the same transaction added: nothing (idempotent)" in out and "99001 -> 99002 | BANNER_ONLY" in out,
          "synthetic import: validates, rolls back, and a second run in the same transaction adds nothing")
    code, out = importer("--setup-sql", str(COLLECTOR / "fixtures" / "setup_user_report_conflict.sql"))
    check(code == 0 and "99001 -> 99002 | CONFIRMED_BY_BANNER" in out and "99001 -> 99003 | BANNER_ONLY" in out
          and "99001 -> 99004 | DISCREPANCY_BANNER_DOES_NOT_LIST" in out and "99001>99004 = LINKED | CONTRADICTED | CONFLICT" in out,
          "a reported link Banner does not list is flagged (both sides kept): 99002 confirmed, 99003 Banner-only, 99004 discrepancy")
    check(row_fingerprint() == rows_before, "both scenarios were rolled back (no row changed)")
    count = query("SELECT count(*) FROM banner_evidence.evidence_source WHERE source_kind = 'BANNER_LIVE'")
    pilot_check = HERE / "verify_pilot_import.sql"  # local-only: it hard-codes records that are not published
    if int(count) > 0 and pilot_check.exists():
        code, out = run_file(pilot_check)
        check(code == 0 and "PASS: Banner pilot" in out, "the local Banner pilot data verifies (%s Banner sources)" % count)
    else:
        print("  skipped: no local Banner pilot data or pilot check on this database (expected on a fresh clone)")

    step("6. David's verification and the catalog_api checks")
    code, out = psql(["-f", str(ROOT / "data/cse007-snapshot/verify.sql")])
    check(code == 0 and "PASS: SuY corrections" in out, "David's verify.sql passes")
    code, out = psql(["-f", str(ROOT / "data/catalog-api/verify_catalog_api.sql")])
    check(code == 0 and "PASS: 3 sections" in out, "catalog_api read-only verification passes")
    code, out = psql(["-f", str(ROOT / "data/catalog-api/test_readiness.sql")])
    check(code == 0 and "8 fail-closed probes hold" in out, "catalog_api readiness test passes (9 probes, 8 fail-closed)")
    result = subprocess.run([sys.executable, "-I", str(ROOT / "data/catalog-api/check_rules_matter.py")], capture_output=True,
                            text=True, timeout=300, cwd=ROOT)
    check(result.returncode == 0 and "OK: all six rules are covered" in result.stdout, "all six fail-closed readiness rules are still covered")

    step("7. Nothing outside banner_evidence changed")
    check(david_fingerprint() == before_david, "David's 11 tables: identical row counts and content hashes")
    check(catalog_views_fingerprint() == before_views, "the five catalog_api views are unchanged")
    schemas = query("SELECT string_agg(nspname, ',' ORDER BY nspname) FROM pg_namespace WHERE nspname !~ '^(pg_|information_schema)'")
    check(schemas == "banner_evidence,catalog_api,cse007_snapshot,public", "schemas: " + schemas)
    print("\nALL CHECKS PASSED")


if __name__ == "__main__":
    main()
