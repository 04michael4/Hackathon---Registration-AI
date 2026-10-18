# Installing catalog_csb into `registration_unified_spring2027` (steps only: NOT run)

Do not run any step against the shared database without the project owner's approval, and not while another person is writing to it (Account 1 owns the database). The default install is **additive and self-contained**: it creates only the schema `catalog_csb`, changes no table, view, function, owner, GRANT or row anywhere else, depends on nothing outside itself, and nothing outside depends on it.

Everything below was rehearsed on 2026-10-18 on a **fresh copy of the live team database** (a read-only `pg_dump` taken that day, restored into a new cluster; the copy's 66 tables had exactly the live database's row content, the same schemas, owners and GRANTs, and `verify_serving.py` passed 22 of 22 on it; a text diff of the two DDL dumps differs in 90 lines that are only pg_dump's rendering of the same definitions: parentheses, line wrapping and `AS text` labels inside view definitions): install, dry-run, import, verify, a second import (identical content), the full rollback, a restore of `catalog_csb` from its own backup, and a restore of the whole database from the pre-install backup. Results: `README.md` § Tests.

## Before you start (all must hold)

- **Approval** of the project owner, and Account 1 not writing to the database (check step 1).
- **Branch:** `feature/data-csb-course-research` must be based on the then-current `origin/pre-main`, with `HANDOFF.md` and `docs/decisions/README.md` conflicts resolved by keeping both sides. It was rebased onto `3589fbd` (PR #18) on 2026-10-18; rebase again if `pre-main` has moved. The code in `data/csb-research` does not depend on anything that changed in `pre-main` since `3257607` (PR #17 and #18 changed the frontend, `.env.example`, `CLAUDE.md`, `PLANS.md`, `README.md`, `HANDOFF.md`, `docs/` and the Windows launcher `backend/start-local.ps1`; nothing in `data/` and no backend source).
- **Local-only inputs** on the machine that runs the import: `~/.registration-csb/csb_import_202710.json` (sha256 below; regenerate it with `build_csb_dataset.py` from `~/.registration-csb/{grids,packs,catalog}`; the result is byte-identical). Back that directory up: it is the only copy of the Banner capture and is not in git.
- **Role:** `catalog_owner` (owns the database, has CREATE on it). The schema must not be created by a superuser.
- **Tests pass on the rebased branch:** `python3 -I data/csb-research/test_csb_dataset.py`, `test_check_no_restricted_data.py`, `test_shared_db_guard.py`, and (scratch databases on a separate local cluster, never the shared server: both scripts refuse it) `run_csb_validation.py` and `run_rollback_validation.py`.
- **The approval is deliberate and stays explicit:** `import_csb.py` refuses this database without `--allow-shared`. It judges the connection it actually has, inside the import transaction (the database name, the server's own port 55437, or a server that holds a shared database), so it also refuses when `PGDATABASE` or `PGPORT` is unset and a service file or libpq's defaults lead there. It refuses a non-loopback server even with the flag: run the steps on the database host, over `127.0.0.1`.
- **Restricted-data check** of what will be committed: `python3 -I data/csb-research/check_no_restricted_data.py --payload ~/.registration-csb/csb_import_202710.json --grids ~/.registration-csb/grids --packs ~/.registration-csb/packs --catalog-cache ~/.registration-csb/catalog --ignore-copied-in data/csb-research/synthetic_fixture.py --ignore-copied-in data/csb-research/test_csb_dataset.py` (expected: no hit in any file this branch adds; two `LOCAL_PATH` lines in `HANDOFF.md` are older, other people's text).

## Steps (127.0.0.1:55437; adjust the port if the cluster moved)

```bash
cd <repo>   # on the rebased branch
export PGHOST=127.0.0.1 PGPORT=55437 PGDATABASE=registration_unified_spring2027
BAK=~/.registration-unified/backups; mkdir -p $BAK

# 1. who else is connected (informational; stop if Account 1 is writing)
psql -X -U postgres -At -c "SELECT usename, application_name, state FROM pg_stat_activity WHERE datname = current_database()"

# 2. backup of the WHOLE database, same convention as the earlier installs; check that it is readable
pg_dump -U postgres -Fc -f $BAK/registration_unified_spring2027-before-csb-$(date +%Y%m%d-%H%M%S).dump registration_unified_spring2027
pg_restore --list $BAK/registration_unified_spring2027-before-csb-*.dump | wc -l      # a non-zero object count
#    optional but recommended: prove the backup restores (scratch database on a LOCAL cluster, not the shared one):
#    run_rollback_validation.py --dump <that file> --owner catalog_owner   (it restores the dump into scratch databases and exercises everything below)

# 3. fingerprint of every table outside catalog_csb, in a read-only session, right before the install
PGOPTIONS='-c default_transaction_read_only=on' psql -X -U catalog_owner -At -f data/csb-research/fingerprint_other_schemas.sql > /tmp/csb-before.txt
psql -X -U catalog_owner -At -c "SELECT count(*) FROM catalog_research.section"          # 243 on 2026-10-18: pass any other number as --expected-research-sections

# 4. install (ONE transaction; commits only if the structure checks pass). Default: V1 + V2, no dependency on catalog_research
PGUSER=catalog_owner psql -X -v ON_ERROR_STOP=1 -f data/csb-research/install.sql

# 5. dry-run (rolls back), then import (one transaction; refuses on any disagreement with the verbatim grid rows or on a CRN already in catalog_research;
#    without --allow-shared both stop with "refusing: ... shared database server ..." and store nothing)
PGUSER=catalog_owner python3 -I data/csb-research/import_csb.py --payload ~/.registration-csb/csb_import_202710.json --dry-run --allow-shared
PGUSER=catalog_owner python3 -I data/csb-research/import_csb.py --payload ~/.registration-csb/csb_import_202710.json --allow-shared
#    expected counts: 132 catalog courses, 43 offerings, 213 sections, 348 weekly + 33 dated meetings, 2 arranged, 251 instructor entries,
#    190 link listings, 399 rules, 28 cross-list rows, 65 searches, 19 requirement groups, 17 NS sequences, gridMismatch 0, linkStatusMismatch 0
#    expected payload sha256 235936fe70964ea7b6ee5a8bdfdd030fda267e3614d1a385e795fd09274ba9d1 (import_run records it) and `fingerprint|de741fe710a9302bd2153b2a42ee8e54`
#    (collation-independent since 2026-10-18); running the import a second time must print the same fingerprint

# 6. verify against the raw inputs, independently of the importer; the baseline of step 3 proves nothing else changed
PGUSER=catalog_owner python3 -I data/csb-research/verify_csb_database.py --grids ~/.registration-csb/grids --packs ~/.registration-csb/packs \
    --catalog-cache ~/.registration-csb/catalog --other-schemas-baseline /tmp/csb-before.txt --save-fingerprint /tmp/csb-fingerprint.txt
#    expected: "38 of 38 checks passed" (saved content fingerprint a609476ec96ab6615a9e62c491c05918; a later run with --compare-fingerprint /tmp/csb-fingerprint.txt makes it 39). If someone wrote to the database between steps 3 and 6, the
#    comparison names the tables that differ: judge them, they are not caused by this import.

# 7. the serving path is unchanged (nothing reads catalog_csb)
PGUSER=catalog_owner  python3 -I data/catalog-serving/verify_serving.py     # 22 of 22
PGUSER=catalog_reader python3 -I data/catalog-serving/verify_serving.py     # 21 of 22: the one failure ("permission denied for table instructor_prediction") is the same before the install (verified on the live database, read-only).
#    It is by design, not a CSB effect: that check reads a professor_ratings base table and the reader is granted only views (HANDOFF.md, Known bugs #6). Change no GRANT for it.

# 8. backup of the new schema alone (the quick way back if it is ever removed by mistake)
pg_dump -U postgres -Fc -n catalog_csb -f $BAK/catalog_csb-only-$(date +%Y%m%d-%H%M%S).dump registration_unified_spring2027
```

### Optional, separate approval: the five coverage views
`install_coverage_views.sql` adds `course_coverage`, `course_availability` and three natural-science-sequence views that **read** `catalog_research.offering`, `.section`, `.offering_tier` and `.grid_search_result`. They are not part of the default install because they couple the two schemas: once they exist, an `ALTER ... TYPE` or `DROP` of those objects by the owner of `catalog_research` fails (tested), and a `DROP ... CASCADE` there would silently drop them (never data). Install only if the owner of `catalog_research` agrees:
```bash
PGUSER=catalog_owner psql -X -v ON_ERROR_STOP=1 -f data/csb-research/install_coverage_views.sql
#   then verify_csb_database.py adds the natural-science sequence-availability line to its output; the same checks pass. Remove again without touching data:
#   DROP VIEW catalog_csb.ns_sequence_dependency, catalog_csb.ns_sequence_status, catalog_csb.ns_sequence_course_status, catalog_csb.course_coverage, catalog_csb.course_availability;
#   DROP FUNCTION catalog_csb.expression_satisfied(jsonb, text[]), catalog_csb.expression_courses(jsonb);
```

## If something goes wrong (every level below was tested on a copy of the team database)

| Situation | What to do | Why it is safe |
|---|---|---|
| `install.sql` or the import fails or refuses | Nothing: each is one transaction and rolled back | tested: an install whose structure check fails leaves the database byte-identical; 20 tampered payloads are refused without change |
| The import succeeded but the result is wrong | Import again (it replaces only `catalog_csb`'s own data) | idempotent: same payload, same fingerprint |
| `catalog_csb` must go | `PGUSER=catalog_owner psql -X -v ON_ERROR_STOP=1 -f data/csb-research/rollback.sql` | It first lists anything OUTSIDE the schema that depends on it (a view, a foreign key, a column default) and aborts if there is any; only then `DROP SCHEMA catalog_csb CASCADE`. Do **not** run a bare `DROP SCHEMA ... CASCADE`: it would silently drop such dependents too. Tested: afterwards the whole database (DDL, owners, GRANTs, default ACLs, every row of 66 tables) equals the state before the install |
| `catalog_csb` was removed but is wanted back | `pg_restore -U postgres --no-owner --role=catalog_owner -d registration_unified_spring2027 $BAK/catalog_csb-only-*.dump` (step 8), then `verify_structure.sql` | tested: identical content fingerprint, all checks pass |
| Anything else in the database is damaged | Restore the step 2 backup into a NEW database (`createdb -O catalog_owner x; pg_restore --no-owner --role=catalog_owner -d x <dump>`), compare it with `fingerprint_other_schemas.sql`, and with the owner's approval swap names (`ALTER DATABASE ... RENAME`) while nobody is connected | tested. Note that a swap discards every write anyone made after the backup: ask first |

## Do not

- Do not run `grant_reader.sql` for the role the application uses until Lehigh's redistribution terms for Banner-derived data are settled: `catalog_csb` holds instructor names and CRNs from an authenticated session. The backend does not query the schema, so no grant is needed for it to work.
- Do not import twice concurrently, and do not edit `catalog_csb` by hand: the importer replaces the schema's own data on every run (history: `catalog_csb.import_run`).
- Do not point `DB_CATALOG_SCHEMA` at `catalog_csb`, and do not add `catalog_csb` to a serving view: see ADR-0009, "How the backend can eventually consume validated CSB courses".

## After

- Add to `HANDOFF.md` (Data section) that `catalog_csb` is installed, which dump preceded it, and the output of step 6.
- Report to the owner of the CSE catalog research the 27 CSE courses in `CHECKPOINT.md` § 6 (CSE 004 and CSE 341 first: they are CSB-required alternatives).
