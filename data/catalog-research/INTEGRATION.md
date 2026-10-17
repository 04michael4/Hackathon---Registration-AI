# Integrating catalog_research with the existing database architecture

Short technical note. Nothing here has been applied to a shared database, and nothing existing is modified.

## 1. How it relates to the existing schemas

```
cse007_snapshot -> catalog_api                       unchanged (verified snapshot, five views)
banner_evidence -> catalog_promoted -> catalog_live  unchanged (evidence -> approvals -> serving)
                         |  read-only dependencies
                         v
                  catalog_research                   NEW, additive; reads the five schemas above, is read by none of them
```

`catalog_research` creates only its own objects. It never writes, alters or drops anything outside itself, and no existing view or
function references it; `verify_structure.sql` asserts both. The backend's schema allow-list is still `catalog_api` / `catalog_live`
(`DB_CATALOG_SCHEMA=catalog_research` fails at startup, checked 2026-10-17).

## 2. Added (10 tables, 9 views, 3 functions; schema `catalog_research`)
Tables: `import_run`, `offering`, `section`, `section_meeting`, `section_arranged_meeting`, `requirement_status`, `eligibility_rule`,
`credit_exclusion`, `course_alias`, `tier_policy_decision`. Views: `evidence_mismatch`, `active_tier_policy_decision`, `offering_tier`,
`offering_status`, `unresolved_requirement`, `prerequisite_banner_check`, `candidate_course_offering`, `candidate_section_offering`, `candidate_section_meeting`.
Functions: `import_term` and `record_tier_policy_decision` (the only writers; closed to `PUBLIC`) and the `reject_change` trigger.

## 3. Reproduce (isolated local database only)
```bash
PGHOST=127.0.0.1 PGPORT=<local port> PGUSER=<local superuser> python3 -I data/catalog-research/build_local_database.py \
  --database registration_spring2027 --input <research export JSON, outside the repo> --evidence-from <host:port/database/user>
PGHOST=127.0.0.1 PGPORT=<local port> PGUSER=catalog_reader PGDATABASE=registration_spring2027 \
  python3 -I data/catalog-research/verify_local_database.py --input <research export JSON>
```
The script creates the roles and database, installs `cse007-snapshot/database.sql`, `catalog-api`, `banner-evidence`, `catalog-promotion`
and `catalog-research` in that order as a non-superuser owner, copies the `banner_evidence` rows read-only (optional), runs the importer
and grants the reader role. The research export is produced by `data/catalog-promotion/tools/build_demo_fixture.py` from the research
workbook and the evidence; it holds real CRNs and times and is never committed. To install only the schema into a database that already
has the other layers: `psql -X -v ON_ERROR_STOP=1 -f data/catalog-research/install.sql` (one transaction, structure checks inside).
The importer is idempotent: a second import replaces the term's rows and leaves the stored content, and the policy decision, unchanged.

## 4. How data moves toward serving (nothing moves automatically)
1. Banner capture -> `banner_evidence` (unchanged).
2. Research workbook + evidence -> export JSON -> `import_term` -> `catalog_research` (**stored**; cross-checked against the evidence).
3. A person records `catalog_promoted.approval` rows with supporting sources; `promote_course` writes an immutable promotion
   (**approved**); `catalog_live` serves it only while every gate still holds at read time.
4. `candidate_*` views have the exact columns and types of `catalog_live.course_offering` / `section_offering` / `section_meeting`, so a
   later step can read the approved subset from `catalog_research`. They are not read by anything today, and `schedulable` is true only
   for what `catalog_live` serves.

## 5. Changes that need review by whoever maintains the existing schemas
- None are required to **store** the data. No existing file was changed except documentation and the shared README/HANDOFF/architecture notes.
- Needed before any stored offering is **served** (not done): (a) how `catalog_live` reads approved rows from `catalog_research`
  (a new migration over the existing views); (b) the ADR-0004 V3 views for conditions; (c) `CONDITIONS_DISPLAYED`; (d) installing `catalog_promoted` / `catalog_research` in a shared database, which is a separate approval. This branch's `data/banner-evidence/verify_structure.sql` was edited to permit `catalog_promoted` as a reader of the evidence; the working copy in the separate banner-evidence worktree (uncommitted there) does not have that edit, so its structure check would fail once `catalog_promoted` exists. Reconcile the two before any shared install.

## 6. How the backend can consume approved data without bypassing the gates
Unchanged: start with `DB_CATALOG_SCHEMA=catalog_live`. A course reaches `catalog_live` only through `promote_course` after a person's
approvals, and is re-checked on every read; the backend needs no change for that. `catalog_research` is for storage, audit and review
(`offering_status`, `unresolved_requirement`, `evidence_mismatch`). Conditions (restrictions, prerequisites, exclusions, arranged
meetings) are not exposed to the backend until the ADR-0004 decisions are made.

## 7. Compatibility issues and conflicts
- **`origin/pre-main` has moved** (now `fa3f95e`; this work is based on `5aa3e82`). A test merge of the committed branch is clean. The
  new `JdbcCourseMetadataCatalog` reads `cse007_snapshot.term`, `.course` and `.section` directly (term names, descriptions, instructors).
  Two consequences for `catalog_live`: a promoted Banner course has no `cse007_snapshot` row, so its description/instructor lookups would
  come back empty; and `catalog-promotion/grant_reader.sql` deliberately gives the read-only role **no** privilege on `cse007_snapshot`,
  so the new class needs either a grant or a different source. Decide before serving any promoted course.
- **PR #7's head** (`integrate/database-into-pre-main`) conflicts with this branch's earlier promotion commits in `HANDOFF.md`,
  `README.md`, `docs/architecture.md` and `DatabaseCatalogIntegrationTest.java` (the same assertion was widened on both sides). Not touched here.
- **Types:** `catalog_live` columns are unbounded `varchar` (a `UNION` loses the length), so the candidate views cast to plain `varchar`.
  `catalog_api.section_offering.lecture_crn` is `text`; so is the candidate view's.
- **Term mapping:** stored `term_code` is the Banner code `202710`; the API term `2027-SPRING` is mapped explicitly by the backend.
- **Special topics:** the Banner evidence calls both CSE 398 topics `CSE-398`; stored offering ids are `CSE-398-<section>`. `promotion_readiness`
  therefore cannot tell them apart until the topic-id scheme is decided.
