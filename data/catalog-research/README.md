# catalog_research: the complete stored term catalog

Status: **implemented and verified in an isolated local database** (see [Verification](#verification)). It stores all 42 researched
Spring 2027 CSE offerings and their 52 sections. **It serves nothing**: the backend does not read this schema, no course is approved or
promoted, and `catalog_live` still serves only the verified CSE 007 snapshot. See
[ADR-0005](../../docs/decisions/0005-store-the-researched-term-catalog-separately-from-serving.md).

## Three questions, kept apart

| Question | Answer lives in | Today |
|---|---|---|
| Is the researched record **stored**? | `catalog_research` (this schema) | yes, all 42 offerings / 52 sections |
| Is it **approved for the scheduler**? | `catalog_promoted` (approvals, promotion) and `catalog_live` (what the backend reads) | only CSE 007 (the verified snapshot); the 40 Banner courses are `BLOCKED` |
| Can a student **register**? | the university only | never claimed anywhere in this schema |

Missing registration certification does not stop a researched record from being stored. It also never opens a serving gate.

## Where it sits

```
cse007_snapshot -> catalog_api                    verified snapshot (CSE 007), unchanged
banner_evidence -> catalog_promoted -> catalog_live   evidence -> approvals -> serving, unchanged
                         |   (read-only: evidence_*, promotion_readiness, catalog_live.*)
                         v
                  catalog_research            complete stored catalog + evidence levels; reads the above, written by nobody else
```

`catalog_research` only **reads** the other layers, and none of them reads it (`verify_structure.sql` asserts both). The JDBC adapter's
schema allow-list is still `catalog_api` / `catalog_live`.

## What is stored

| Table | Content |
|---|---|
| `offering` | one row per offering: title, credits (the published total, on the lecture), Banner schedule type (`administrative_type`), data source, `components_status`, `required_lab_count`, cross-listing status, `identity_status` (special topics) |
| `section` | CRN, section code, `LECTURE`/`LAB`, credits (a lab is always 0), `lecture_crn` (a lab points at a lecture of the same offering), Banner schedule type |
| `section_meeting` | one row per day: day (1 = Monday), start, end, location |
| `section_arranged_meeting` | a mandatory meeting with no published time (CSE 367 / 467); a warning, never a timetable slot |
| `eligibility_rule` | class-standing / level / college / major / department-approval restrictions (structured values, verbatim Banner text) and prerequisites (text plus a parsed `{"op":"AND"/"OR","args":[...],"course":..,"concurrent":..}` expression, from the strict parser or from `prerequisite_expressions.json`, hand-structured entries whose course set must equal Banner's own prerequisite text) |
| `requirement_status` | for each offering and each of prerequisite / corequisite / enrollment restriction: `UNKNOWN`, `NONE_LISTED` (Banner lists nothing, and the designated reviewer decided on 2026-10-17 to treat that as none; user-supplied, labelled `USER_CLARIFICATION`, **not** Registrar certification), `USER_CONFIRMED_NONE`, `TEXT_ONLY`, `RULE_DEFINED`, with the evidence level and the verbatim Banner text |
| `credit_exclusion` | one row per statement an offering makes; sources that disagree stay as separate rows (`SOURCES_CONFLICT`) |
| `course_alias` | catalog aliases (another department's number); not a cross-list, no CRN, always unverified |
| `tier_policy_decision` | append-only `TEAM_POLICY` decisions (CSE 217-010). CHECK constraints make `registrar_certified` and `guarantees_registration` impossible; one writer function; a changed Banner schedule type makes a decision stale |
| `import_run` | append-only record of every import (hash of the normalized input, counts, time, database user) |

Evidence levels: `BANNER` (Banner text), `RESEARCH_WORKBOOK` (stated in the research workbook, which cites the Banner/catalog text),
`USER_CLARIFICATION` (user-supplied: exactly one lab, lab credits 0, "no prerequisite", standalone), `TEAM_POLICY` (a person's decision
for the demo). None of them is Registrar certification.

Views: `evidence_mismatch` and `prerequisite_banner_check` (both must be empty), `offering_tier`, `offering_status`, `unresolved_requirement`, `active_tier_policy_decision`,
`candidate_course_offering` / `candidate_section_offering` / `candidate_section_meeting` (research-sourced offerings only), and, from V3, `grid_mismatch`,
`exam_meeting`, `course_term_status`, `program_course_link` and `program_structure_problem`.

### Tier (derived, never stored)
`READY` = served by `catalog_api` today (CSE 007). `BLOCKED` = stored data disagrees with the evidence, or the special-topic course-id
scheme is undecided (CSE 398-042, 398-052), or a section type other than lecture/lab has no active policy decision. `CONDITIONAL` =
everything else (it is stored and consistent; its unresolved conditions stay visible). Today: **1 READY / 39 CONDITIONAL / 2 BLOCKED**.
This is a research classification, not a promotion, and `CONDITIONAL` never means eligible to register.

### Catalog-shaped candidate views
`candidate_*` have exactly the columns and types of `catalog_live.course_offering` / `section_offering` / `section_meeting` (checked by
`verify_structure.sql`) over **every** stored offering; `schedulable` is true only for what `catalog_live` serves. A special topic's
`course_id` is its offering id (`CSE-398-042`). They are not read by the backend. They let the stored rows be compared with, and later
promoted into, the serving path without a second catalog architecture.

## Build and use (isolated local database only)

Needs `psql` and `pg_dump` (PostgreSQL 17). The research export is the JSON written by
[`../catalog-promotion/tools/build_demo_fixture.py`](../catalog-promotion/tools/build_demo_fixture.py) from the research workbook and the
collected Banner evidence. It holds real CRNs, titles and times, so it stays **outside the repository** and is never committed.

```bash
# 1. a local cluster you control (loopback only), then:
PGHOST=127.0.0.1 PGPORT=55437 PGUSER=postgres \
  python3 -I data/catalog-research/build_local_database.py --database registration_spring2027 \
    --owner catalog_owner --reader catalog_reader --input ~/.registration-demo/spring-2027.json \
    --evidence-from 127.0.0.1:55433/registration_cse007_local/<user>      # optional: copies banner_evidence rows, source opened READ-ONLY

# 2. verify the stored data against the export, and again after a restart
PGHOST=127.0.0.1 PGPORT=55437 PGUSER=catalog_reader PGDATABASE=registration_spring2027 \
  python3 -I data/catalog-research/verify_local_database.py --input ~/.registration-demo/spring-2027.json --save-fingerprint /tmp/fp.txt
#    ... restart PostgreSQL ...      then the same command with --compare-fingerprint /tmp/fp.txt

# 3. the synthetic validation (creates and drops its own database)
PGHOST=127.0.0.1 PGPORT=55437 PGUSER=postgres python3 -I data/catalog-research/run_research_validation.py
python3 -I data/catalog-research/test_import_research_catalog.py
```

Re-importing: `import_research_catalog.py` replaces one term's stored catalog in a single transaction and **aborts if the result
disagrees with the collected evidence or the verified snapshot** (`evidence_mismatch`). Policy decisions are never erased. Re-run it
when the export changes; `--dry-run` rolls back. To install only the schema into a database that already has the other layers:
`psql -X -v ON_ERROR_STOP=1 -f data/catalog-research/install.sql` (one transaction; commits only if the structure checks pass).

The builder, importer and validators refuse the shared database's name and port and any non-loopback host. Install as a non-superuser
owner; `grant_reader.sql` gives a read-only role `SELECT` only (no `EXECUTE` on `import_term` or `record_tier_policy_decision`).

## Verification

`run_research_validation.py`: install and reinstall (identical definitions); 6 scenarios, **195 probes** on synthetic courses (consistent
import, 18 probes on evidence disagreement and malformed payloads, 22 impossible rows refused, policy decisions, special topics, privileges and isolation),
each rolled back; **31 mutation checks** (one rule weakened at a time, each caught); the three serving-layer view sets unchanged.
`test_import_research_catalog.py` (24) and `test_import_noncse_catalog.py` (22) unit tests. `verify_local_database.py` (CSE only database): 32 / 30 checks as the owner / the read-only role. `verify_unified_database.py`
(CSE + non-CSE database): 45 checks as the owner and 43 as the read-only role (it skips what that role may not read). Actual results are in
[HANDOFF.md](../../HANDOFF.md).

## Unified database: CSE + non-CSE (V3)

The same schema also holds the non-CSE Spring 2027 sections and the five degree programs, through two further validated import paths
(`V3__noncse_grid_staging_and_programs.sql`, `import_noncse_catalog.py`). Differences from the CSE path:

| | CSE (research-sourced) | non-CSE (grid-staged) |
|---|---|---|
| Evidence | `banner_evidence` captures (details, linked sections, restrictions) | the Banner results **grid** only, kept verbatim in `grid_observation` / `grid_extra_meeting_observation` / `grid_search_result` with `grid_source` provenance (local-only, details never inspected) |
| Cross-check | `evidence_mismatch` (against `banner_evidence`) | `grid_mismatch` (against the verbatim grid rows; days, locations and components are re-derived in SQL, independently of the importer) |
| `data_source` | `BANNER_EVIDENCE` / `SNAPSHOT` | `BANNER_GRID_STAGING` |
| Components | `LECTURE`, `LAB` with a confirmed link | also `RECITATION`; a pairing is `CONFIRMED`, `LISTED_BY_LECTURE_ONLY` (only a lecture's Linked Sections list shows it) or `NOT_COLLECTED`, never inferred |
| Credits | researched; labs add 0 | the grid's number, `credit_source = GRID_DISPLAY`; a course whose lectures differ has `credits NULL / VARIES_BY_SECTION` |
| Requirements | reviewed statuses | every kind `UNKNOWN` (nothing collected; "no restriction shown" is not proof) |
| Tier | `READY` / `CONDITIONAL` / `BLOCKED` | always `BLOCKED` (`GRID_ONLY_DETAILS_NOT_INSPECTED`); absent from the `candidate_*` views |

Meetings: a weekly meeting is stored one row per day (`meeting_kind = WEEKLY`, with Banner's meeting type and recurrence dates); a Common Hour
Exam is a `DATED_ONE_TIME` row on its own date (its weekday must be that date's weekday), listed in `exam_meeting` and never in a weekly view.
Instructor names are never imported. Each importer replaces only its own data, so importing CSE never touches grid-staged rows and the reverse.

Programs: `program`, `requirement_group` (tree; `ALL` / `ANY` / `CHOOSE_N` / `CREDITS`), `program_requirement` (category), `requirement_course_option`
(options sharing an `option_set` are taken together), `catalog_course` (prerequisites `NOT_COLLECTED`), `catalog_course_exclusion`,
`catalog_course_equivalence` (team-asserted cross-lists, unverified) and `unresolved_item`. `course_term_status` says for each program course whether it
is offered this term, was searched with no result, or was never searched; `program_course_link` joins programs to offerings and tiers (study only,
nothing schedulable); `program_structure_problem` must be empty.

```bash
PGHOST=127.0.0.1 PGPORT=<local port> PGUSER=<local superuser> python3 -I data/catalog-research/build_local_database.py \
  --database registration_unified_spring2027 --input <CSE export JSON> --evidence-from <host:port/database/user> \
  --noncse-export <non-CSE export JSON> --noncse-staging-db <staging.db> --noncse-banner-dir <grid CSV directory>
PGHOST=127.0.0.1 PGPORT=<local port> PGUSER=catalog_reader PGDATABASE=registration_unified_spring2027 python3 -I data/catalog-research/verify_unified_database.py \
  --cse-export <CSE export JSON> --noncse-export <non-CSE export JSON> --banner-dir <grid CSV directory> [--instructors-csv <file>]
```
The non-CSE inputs come from `data/degree-requirements` (SQLite staging, `export_import_ready.py`); the real files are local-only and never committed.
`verify_unified_database.py` rebuilds its expectations from the raw CSVs and exports and prints only counts and verdicts (the optional instructor
file is used only to prove no name appears in the stored data).

## What this does not do
- It does not approve, promote or serve anything, and it does not change `catalog_api`, `catalog_promoted`, `catalog_live`,
  `banner_evidence` or `cse007_snapshot`.
- It does not enforce prerequisites, restrictions or credit exclusions (the application enforces none; `catalog_promoted.capability` is all
  `false`). Storing a rule is not enforcing it.
- It does not decide the special-topic course-id scheme, the student-profile model or the response conditions; those are open items for the
  backend API and scheduling-engine modules in [ADR-0004](../../docs/decisions/0004-proposed-class-standing-eligibility.md).
- Credits, the lab structure and "no prerequisite" for the Banner courses remain user-supplied or unreviewed; no `approval` row exists.

## Open items before any stored offering can be served
1. The backend API and scheduling-engine modules decide the ADR-0004 items (student profile, conditions in responses, topic ids,
   how unscheduled mandatory meetings and credit exclusions are handled).
2. A person records the approvals `catalog_promoted` requires (credits, lab links, required lab count, requirements, cross-listing,
   exclusions, each with a supporting source), and the `CONDITIONS_DISPLAYED` capability is added together with the response field that
   shows the conditions.
3. A decision on how `catalog_live` reads `catalog_research` (for example views over the approved subset). Until then the stored data is
   read through `offering_status` and the `candidate_*` views.
