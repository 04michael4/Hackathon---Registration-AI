"""Loads SYNTHETIC courses into a TEST database so the Java end-to-end test can read them through catalog_live.

    ALLOW_SYNTHETIC_FIXTURE=yes python3 -I data/catalog-promotion/load_synthetic_fixture.py

It does exactly what a person would do, with fake data (fake CRNs 90xxx-92xxx, titles "Synthetic Course ..."):
  CSE-092  lecture-only, two sections          -> imported, approved, PROMOTED (served)
  CSE-091  one lecture with two linked labs    -> imported, approved, PROMOTED (served)
  CSE-090  lecture-only with a prerequisite and a restriction -> imported only; BLOCKED (never served)

The committed rows are permanent in that database (everything is append-only), so use a disposable test database only.
It refuses to run against a database named like the shared one, and it needs ALLOW_SYNTHETIC_FIXTURE=yes. Idempotent:
if CSE-092 is already promoted it does nothing. Connection settings come from the PG* variables.
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_promotion_validation as v  # noqa: E402


def main():
    db = os.environ.get("PGDATABASE", "")
    if os.environ.get("ALLOW_SYNTHETIC_FIXTURE") != "yes":
        sys.exit("refusing to load synthetic data: set ALLOW_SYNTHETIC_FIXTURE=yes (test databases only)")
    if "cse007_local" in db:
        sys.exit("refusing to load synthetic data into the shared database (" + db + ")")
    if v.query("SELECT count(*) FROM catalog_promoted.promotion WHERE course_id = 'CSE-092'") != "0":
        print("synthetic fixture already loaded; nothing to do")
        return
    lo = v.capture_set("092", v.lecture_only_crns("920"))
    lab = v.lab_course("091", "910")
    blocked = v.capture_set("090", v.lecture_only_crns("900"), req={
        "prerequisites_text": "Catalog Prerequisites And/Or Test Score Subject Course Number Level Grade Computer Science & Engineering 017 Undergraduate TR",
        "restrictions_text": "Not all restrictions are applicable. Cannot be enrolled in one of the following Classes: First-Year Student (FR)"})
    body = "\n".join([
        v.import_sql(lo, lab, blocked),
        v.SUP,
        v.approve_lecture_only("CSE-092", ["92001", "92002"]),
        v.approve_lab_course("CSE-091", "91001", ["91002", "91003"]),
        "SELECT catalog_promoted.promote_course('202710', 'CSE-092', 'synthetic-fixture-reviewer');",
        "SELECT catalog_promoted.promote_course('202710', 'CSE-091', 'synthetic-fixture-reviewer');",
    ])
    sql = v.PREAMBLE + "\nBEGIN;\n" + body + "\nCOMMIT;\n"
    code, out = v.psql(["-f", "-"], stdin=sql)
    if code != 0:
        sys.exit("loading failed (nothing was committed):\n" + out[-800:])
    print(v.query("SELECT string_agg(course_id || ' ' || state, ', ' ORDER BY course_id) FROM catalog_promoted.promotion_readiness "
                  "WHERE course_id IN ('CSE-090', 'CSE-091', 'CSE-092')"))


if __name__ == "__main__":
    main()
