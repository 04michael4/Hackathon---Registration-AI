# csb-research: the Computer Science and Business (CSB) degree, stored additively

Status: **implemented and verified on isolated local copies of the unified database, the last one a fresh copy of the live team database** (2026-10-18). Nothing is installed in a shared database, nothing is approved, promoted or served, and the backend does not read it. Design: [ADR-0009](../../docs/decisions/0009-store-the-csb-research-additively-in-catalog-csb.md). The steps for installing it into `registration_unified_spring2027`, with the rollback and recovery procedures, are in [MERGE_INTO_UNIFIED_DATABASE.md](MERGE_INTO_UNIFIED_DATABASE.md) (written and rehearsed on a copy, not run on the shared database). How the backend could eventually consume validated CSB courses without bypassing `catalog_serving`: ADR-0009. Per-course results: [CHECKPOINT.md](CHECKPOINT.md).

## What it holds

| Group | Tables (schema `catalog_csb`) |
|---|---|
| Provenance | `import_run` (append-only), `source` (a row per source; authenticated Banner = `NOT_VERIFIED_LOCAL_ONLY`) |
| Catalog identities (public 2026-27 catalog) | `catalog_course` (title, credits as printed, NS/NW attributes, prerequisite text + parsed AND/OR expression, "can be taken concurrently", aliases), `NOT_FOUND_IN_2026_27_CATALOG` kept explicit |
| The degree | `program`, `requirement_group` / `requirement_option` (a tree: `ALL`, `ANY` alternatives, `CHOOSE_N`, `CREDITS` categories), `elective_list(_member)`, `ns_sequence(_course)`, `ns_optional_lab`, `ns_footnote`, `source_conflict`, `unresolved_item` |
| Spring 2027 Banner (local-only) | `banner_search` (the exact searches, incl. 0-class results), `offering`, `section`, `section_meeting` (weekly rows and dated one-time exams), `section_arranged_meeting`, `section_instructor`, `grid_observation` (the verbatim grid row), `section_detail` (Class Details text and which panels were read), `section_rule` (restrictions and prerequisites as rules), `section_link_option` (verbatim Linked Sections panels), `section_cross_list`, `prerequisite_comparison` (catalog vs Banner), `detail_discrepancy` |

Cross-check and analysis views: `grid_mismatch` and `link_status_mismatch` (must be empty), `link_pair`, `component_requirement`, `package_credit`, `credit_problem`, `offering_requirement_variant`, `course_term_status`, `program_problem`. Five more views (`course_availability`, `course_coverage`, `ns_sequence_course_status`, `ns_sequence_status`, `ns_sequence_dependency`) READ `catalog_research` and are therefore an **optional second install step** (`install_coverage_views.sql`): they couple the two schemas (an `ALTER`/`DROP` of the objects they read then fails).

The degree keeps its structure: 136 credits (35 CS + 32 business + 14 math/statistics + 12 natural world + 10 CSB + 9 professional electives + 18 humanities/social + 6 free), the alternatives (CSE 003+004 or 007; CSE 241 or 341; ECO 146 or 119; MATH 021 or 075+076; MATH 231 or ECO 045), and the categories that are *not* courses (HE/AL 6, SW 3, free 6, natural world 12 with its NS/lab/prerequisite-sequence rule). The 9-credit professional elective requirement is separate from the 3-credit CSE elective.

Banner restriction and prerequisite text is parsed by the existing fail-closed parser in [`../catalog-research/noncse_details.py`](../catalog-research/noncse_details.py) (imported, not modified; CSB adds subject names and the `Degrees`/`Cohorts` restriction kinds). `csb_dataset.py` holds the CSB-specific normalization.

## Rules the data follows

- **Stored is not approved is not registrable.** No approval/promotion/serving/certification column exists (asserted by `verify_structure.sql`).
- **Nothing is invented.** `NONE_LISTED` = Banner printed "no ... information available" (not proof that no requirement exists); `TEXT_ONLY` = not recognized exactly, the words are kept and no rule is created; a link is `CONFIRMED_BOTH_SIDES` only when each side's panel lists the other. A Linked Sections panel lists *options*; it does not itself say registration in one is mandatory (`component_requirement.requirement_basis`).
- **Credits are not double-counted.** The grid's credit hours are stored per section; a linked lab/recitation shows 0 there but the lecture's bundled total in Class Details (60 sections): that is flagged (`credits_display_status`), not trusted, and `package_credit` counts the lecture once.
- **Sections are not assumed uniform.** Restrictions and prerequisites are stored per section (`offering_requirement_variant` shows when they differ).
- **Disagreements stay side by side.** Catalog vs Banner prerequisites (`prerequisite_comparison`); the catalog vs the program website vs the CSE department page (`source_conflict`).
- **Professional electives are unverified.** Only the catalog's 36-course CSE elective list is `CATALOG_LISTED`; every other candidate (CSB-prefixed courses, the website's example tracks, the CSE list used as a professional elective) is `APPROVAL_NOT_VERIFIED` (a CHECK and a second check in `program_problem`).
- **No professor is guessed.** `Staff, Teaching` and similar are stored as placeholders.

## Local-only data (never committed)

Real CRNs, instructor names, meeting times, Class Details text and the catalog's course prose come from an authenticated Banner session and Lehigh's pages; Lehigh's redistribution terms are unverified. They live in `~/.registration-csb/` (grids, packs, parsed catalog, the import payload, a PostgreSQL cluster) and are listed in `.gitignore`. The committed tests use `synthetic_fixture.py` (fake CRNs 99xxx, invented names). `CHECKPOINT.md` holds course-level counts only.

## Reproduce

```bash
# 1. Banner (read-only; your own session): open Browse Classes, choose "2027 Spring Semester (View Only)", paste collector/collector_local.js once per page load.
#    Per course: search the subject and number through the normal form, then run the queue. The collector clicks only the section's title link, the
#    allow-listed popup tabs (Class Details, Attributes, Restrictions, Prerequisites, Corequisites, Cross Listed Courses, Linked Sections) and Close.
#    It never opens Enrollment/Waitlist, Instructor/Meeting Times, Fees or any registration, add, drop or plan action. window.__csb.pack()/packAll() writes a
#    compact JSON string; read it with chunk(i) (900 characters) and verify the SHA-256 after copying it into ~/.registration-csb/packs/pack-NNN.json
#    (the browser blocked file downloads after the first background download, so this is how the 4 packs here were transferred; each matched its SHA-256).
# 2. The public catalog pages
python3 -I data/csb-research/catalog_courses.py fetch --cache-dir ~/.registration-csb/catalog
# 3. Build the import payload (local-only output)
python3 -I data/csb-research/build_csb_dataset.py --grids ~/.registration-csb/grids --packs ~/.registration-csb/packs \
    --catalog-cache ~/.registration-csb/catalog --out ~/.registration-csb/csb_import_202710.json
# 4. Install (isolated copy first) and import, as the schema owner. install.sql is self-contained; install_coverage_views.sql is the optional coupling step
PGHOST=127.0.0.1 PGPORT=<local port> PGUSER=catalog_owner PGDATABASE=<isolated copy> psql -X -v ON_ERROR_STOP=1 -f data/csb-research/install.sql
python3 -I data/csb-research/import_csb.py --payload ~/.registration-csb/csb_import_202710.json --dry-run
python3 -I data/csb-research/import_csb.py --payload ~/.registration-csb/csb_import_202710.json
# 5. Verify against the raw inputs, independently of the importer (read-only)
python3 -I data/csb-research/verify_csb_database.py --grids ~/.registration-csb/grids --packs ~/.registration-csb/packs --catalog-cache ~/.registration-csb/catalog \
    --other-schemas-baseline <fingerprint taken before the install>
python3 -I data/csb-research/render_checkpoint.py > data/csb-research/CHECKPOINT.md      # needs the coverage views
# 6. Before committing anything: scan it for real CRNs, instructor names, copied Banner/catalog text, credentials and home paths
python3 -I data/csb-research/check_no_restricted_data.py --payload ~/.registration-csb/csb_import_202710.json --grids ~/.registration-csb/grids --packs ~/.registration-csb/packs \
    --catalog-cache ~/.registration-csb/catalog --ignore-copied-in data/csb-research/synthetic_fixture.py --ignore-copied-in data/csb-research/test_csb_dataset.py
# 7. Remove it again (guards against dependents; see MERGE_INTO_UNIFIED_DATABASE.md)
PGUSER=catalog_owner psql -X -v ON_ERROR_STOP=1 -f data/csb-research/rollback.sql
```

`import_csb.py` refuses the shared database names (`registration_cse007_local`, `registration_unified_spring2027`), port 55433 and any non-loopback host unless `--allow-shared` is passed. A fingerprint of every row of every table outside `catalog_csb` is produced by `fingerprint_other_schemas.sql`; it must be identical before and after an import.

## Tests (2026-10-18)

| What | Command | Result (2026-10-18) |
|---|---|---|
| Unit tests (no database, synthetic) | `python3 -I data/csb-research/test_csb_dataset.py` | **33 tests OK** |
| Tests of the restricted-data scanner (synthetic) | `python3 -I data/csb-research/test_check_no_restricted_data.py` | **7 tests OK** |
| Database validation on a scratch database (synthetic): default install, optional coverage views, removal of the views, reinstall, import, idempotent rerun, content fingerprint under two collations, isolation from a stub `catalog_research`, the documented coupling, 20 refused tampered payloads, 5 stored-row damage probes, append-only history, read-only reader role, 7 mutation checks | `PGHOST=127.0.0.1 PGPORT=<local> PGUSER=<superuser> python3 -I data/csb-research/run_csb_validation.py` | **74 of 74 passed** |
| Install, import, rollback and recovery on a scratch database (synthetic data), once with a stand-in `catalog_research` and once restored from a dump of the team database: a failed install commits nothing; nothing outside `catalog_csb` changes at any step (DDL, owners, GRANTs, default ACLs, every row; 3 negative controls); `rollback.sql` refuses while a view, foreign key or column default outside depends on the schema; after it the database equals the pre-install state; schema-only restore; full restore and rename swap | `run_rollback_validation.py` and `run_rollback_validation.py --dump <pg_dump -Fc of a copy> --owner catalog_owner` | **26 of 26 passed, in both modes** |
| Real data against the raw inputs, on the isolated copy of the live database (default install; also after a restart, after the real-data rollback and a restore of the schema from its own backup) | `verify_csb_database.py` | **38 of 38 passed** (39 with `--compare-fingerprint`) |
| The serving path with `catalog_csb` installed | `data/catalog-serving/verify_serving.py` as owner / as reader; `ServingCatalogIntegrationTest` (`RUN_SERVING_IT=true`); `./mvnw test` | **22 of 22** / 21 of 22 (the same single "permission denied for table instructor_prediction" failure the live database gives a reader) / **10 run, 0 failures** / **801 run, 0 failures, 0 errors, 40 skipped** |

What the real-data run proved: every one of the 40 mandatory CSB course identities (alternatives included) has a catalog row; the 213 stored sections are exactly the observed ones and every offered course has the number Banner returned; no CRN is duplicated or collides with `catalog_research`; instructors are on exactly their grid sections in order; the 190 link listings are all mutual; the 137 structured prerequisites each have the courses and AND/OR connectors of the Banner text; both SQL cross-checks are empty; the 66 tables outside `catalog_csb` have identical row content before and after (against the live team database's own fingerprint); no CSB CRN occurs in any of the 24 CRN columns of the other schemas' tables; no CSB offering is also a `catalog_research` offering, and no title differs where both name a course; `catalog_research` still has its 243 sections; the content fingerprint is unchanged after a restart.

## Not covered

- CSE courses (27 named by the CSB degree) that no one has searched in Banner, including the CSB-required alternatives CSE 004 and CSE 341: see `CHECKPOINT.md` § 6; they belong to the CSE catalog research.
- Cross-listed partner sections under another subject (graduate sections, PHY 019, CSE 280's cross-list with CSB 312): the Cross Listed Courses panel is stored, the partner's own details are `NOT_COLLECTED`.
- Whether Banner enforces the CSB-specific EES 102 rule, what Banner's "TR" prerequisite grade means (kept as printed), and whether a linked component adds credits.
- Registration: nothing here says a student can enrol in anything.
