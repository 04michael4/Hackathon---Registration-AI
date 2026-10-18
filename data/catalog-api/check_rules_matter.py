"""Proves that each of the six readiness rules matters (stdlib only; needs `psql` on PATH).

For every rule it takes a temporary COPY of V1__catalog_api_views.sql with that one rule replaced by `true`,
installs the copy and runs test_readiness.sql inside a transaction that is always rolled back. The test must
FAIL; if it still passes, that rule is not covered by a probe. A baseline run with the unmodified file must pass.

Safety: nothing is committed. The script only drops and recreates the catalog_api views inside the rolled-back
transaction (never a cse007_snapshot object), and it checks afterwards that the installed views are unchanged.
Connection settings come from the usual PG* variables (PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSFILE).

    python3 -I data/catalog-api/check_rules_matter.py
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
V1 = (HERE / "V1__catalog_api_views.sql").read_text(encoding="utf-8")
FAIL_MESSAGE = "a fail-closed readiness rule did not hold"
RULES = ["1 credits known, whole, reviewed", "2 each lab has one confirmed lecture",
         "3 a lecture is never someone's component", "4 confirmed 'exactly one lab' rule",
         "5 no OPEN warning on its sections", "6 lecture link status not unknown"]


def psql(file_path):
    result = subprocess.run(["psql", "-X", "-v", "ON_ERROR_STOP=1", "-f", str(file_path)],
                            capture_output=True, text=True, timeout=120)
    return result.returncode, result.stdout + result.stderr


def views_fingerprint():
    result = subprocess.run(
        ["psql", "-X", "-At", "-c",
         "select coalesce(md5(string_agg(c.relname || pg_get_viewdef(c.oid), ',' order by c.relname)), 'none') "
         "from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'catalog_api' and c.relkind = 'v'"],
        capture_output=True, text=True, timeout=60)
    if result.returncode != 0:
        sys.exit("cannot query the database: " + result.stderr.strip()[:200])
    return result.stdout.strip()


def remove_rule(sql, number):
    start = sql.index("HAVING") + len("HAVING")
    end = sql.index(";\n", start)
    parts = [p for p in re.split(r"\n\t(?:AND )?(?=bool_and)", sql[start:end]) if p.strip().startswith("bool_and")]
    if len(parts) != 6:
        sys.exit(f"expected 6 readiness rules in V1, found {len(parts)}")
    return sql.replace(parts[number - 1], "true", 1)


def attempt(workdir, label, sql):
    view_file = workdir / "view.sql"
    view_file.write_text(sql, encoding="utf-8")
    driver = workdir / "driver.sql"
    driver.write_text("\\set ON_ERROR_STOP on\nBEGIN;\nDROP SCHEMA IF EXISTS catalog_api CASCADE;\n"
                      f"\\i {view_file}\n\\i {HERE / 'test_readiness.sql'}\nROLLBACK;\n", encoding="utf-8")
    return psql(driver)


def main():
    before = views_fingerprint()
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        workdir = Path(tmp)
        code, output = attempt(workdir, "baseline", V1)
        print(f"baseline (unmodified rules): {'PASS' if code == 0 else 'FAIL'}")
        if code != 0:
            problems.append("baseline test failed:\n" + output[-600:])
        for number, name in enumerate(RULES, start=1):
            code, output = attempt(workdir, name, remove_rule(V1, number))
            caught = code != 0 and FAIL_MESSAGE in output
            print(f"rule {name}: removed -> test {'FAILS as it should' if caught else 'STILL PASSES (not covered)'}")
            if not caught:
                problems.append(f"rule {number} is not covered by any probe")
    after = views_fingerprint()
    print(f"installed views unchanged afterwards: {'yes' if before == after else 'NO'}")
    if before != after:
        problems.append("the installed catalog_api views changed")
    if problems:
        sys.exit("\n".join(problems))
    print("OK: all six rules are covered")


if __name__ == "__main__":
    main()
