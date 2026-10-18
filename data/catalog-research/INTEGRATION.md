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

## 2. Added (22 tables, 14 views, 5 functions; schema `catalog_research`)
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
  `grant_reader.sql` now gives the read-only role column-level `SELECT` on exactly the `cse007_snapshot` columns those two queries read (fixed 2026-10-17; before that
  both endpoints returned 503 for that role). One consequence remains for `catalog_live`: a promoted Banner course has no `cse007_snapshot` row, so its description/instructor lookups come back empty.
- **PR #7's head** (`integrate/database-into-pre-main`) conflicts with this branch's earlier promotion commits in `HANDOFF.md`,
  `README.md`, `docs/architecture.md` and `DatabaseCatalogIntegrationTest.java` (the same assertion was widened on both sides). Not touched here.
- **Types:** `catalog_live` columns are unbounded `varchar` (a `UNION` loses the length), so the candidate views cast to plain `varchar`.
  `catalog_api.section_offering.lecture_crn` is `text`; so is the candidate view's.
- **Term mapping:** stored `term_code` is the Banner code `202710`; the API term `2027-SPRING` is mapped explicitly by the backend.
- **Special topics:** the Banner evidence calls both CSE 398 topics `CSE-398`; stored offering ids are `CSE-398-<section>`. `promotion_readiness`
  therefore cannot tell them apart until the topic-id scheme is decided.

## 8. Unified database (CSE + non-CSE) and what changed relative to PR #8 / PR #9
- This branch (`integrate/unified-course-db`, based on `pre-main` `fa3f95e`) carries **cherry-picked copies** of the PR #8 commits (promotion layer, ADR-0004,
  reconcile tool, fixture builder, `catalog_research`; **not** the feature-flagged demo backend) and of the PR #9 commit (`data/degree-requirements`). The hashes are
  new, so merging PR #8 or #9 first will conflict textually with the cherry-picks (same content): rebase this branch onto whichever merges first.
- Relative to PR #8, `V1`/`V2` of `catalog_research` were **edited in place** (they are unmerged): `section.component_type` also allows `RECITATION`; new section columns
  `credit_source`, `link_status`, `banner_link_flag`; the lab-credit rule now exempts `credit_source = GRID_DISPLAY`; `offering.credits` may be NULL with
  `credits_status`; `offering.data_source` also allows `BANNER_GRID_STAGING`; `section_meeting` gets `meeting_type`, `meeting_kind`, `start_date`, `end_date` and a surrogate
  key (the old composite key became a unique index); `import_run` gets `kind`; `import_term` now replaces only research-sourced offerings. Everything PR #8 asserted
  (97 probes, 17 mutations) still holds unchanged; the new V3 adds the rest. `V3` is entirely new.
- A database already built from PR #8's `V1`/`V2` (for example the original local `registration_spring2027`) is **not** upgraded in place: rebuild it with
  `build_local_database.py`. Nothing in the original was changed.
- `catalog_live` and the backend are unchanged. The unified database serves exactly what the CSE-only one did (CSE 007); the 191 non-CSE sections are `BLOCKED`,
  absent from the `candidate_*` views, and every requirement of theirs is `UNKNOWN`.
- **Existing issue, surfaced by this integration (fixed 2026-10-17):** with the least-privilege reader role, `GET /api/courses/{id}` and `/api/terms` returned 503 on `pre-main`, because `JdbcCourseMetadataCatalog` reads `cse007_snapshot.term/course/section/source_asset` directly. `catalog-promotion/grant_reader.sql` now grants that role column-level `SELECT` on exactly the columns those queries read (PR #7's setup script got the same fix). Remaining: a promoted Banner course has no `cse007_snapshot` row, so it has no description or instructor lookup.
- Not imported by design: instructor names (redistribution from an authenticated Banner session is not verified), seat counts and waitlists, `section_details.md` (free text).
  The one section whose detail panels were read (CHM 030 lecture) therefore keeps UNKNOWN requirements here, not the "none listed" the CSE path derives from captures.
