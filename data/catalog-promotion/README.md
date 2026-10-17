# catalog_promoted and catalog_live: promoting verified Banner evidence into the scheduler

Status: **implemented and tested on synthetic courses only. No real course has been promoted, nothing is promoted in any
shared database, and only CSE 007 is schedule-ready.** The backend ignores all of this unless it is started with
`DB_CATALOG_SCHEMA=catalog_live` (default `catalog_api`).

## What it is

```
banner_evidence  (append-only evidence)                    cse007_snapshot / catalog_api   (David's verified snapshot, untouched)
      |  derive (views, nothing typed by hand)                       |
      v                                                              |
catalog_promoted.promotion_blocker / promotion_readiness             |
      |  + a PERSON's recorded approvals (approval table)            |
      v                                                              |
catalog_promoted.promote_course()  -> immutable, versioned promotion |
      |                                                              |
      v  re-checked at READ time                                     v
catalog_promoted.servable_*  ------------>  catalog_live.*  <--  catalog_api.*     (same columns for the three views the adapter reads)
                                                  |
                                          JDBC adapter (schema property) -> Sunny's engine
```

- **Evidence is not approval.** Facts come only from evidence rows. The only values a person supplies are *approvals*, inserted
  by a person (`record_approval` or a plain `INSERT`) and never by any function in this schema; `promote_course` only reads them.
- **Nothing is inferred.** Standalone status, required lab counts, lab credits, requirements, cross-listing and credit exclusion
  each need an approval **and** a recorded supporting source (a `USER_CLARIFICATION` or `TEAM_CONFIRMATION` row in
  `banner_evidence`). Missing links and "Option" labels are never enough. Lab credits always need one: CSE 007's lab-credit
  convention is not generalized. A lecture's credit needs only the person's approval, and only when Banner's two displays agree.
- **Fail closed, at promotion and at every read.** A promoted course is served only while its package still equals what the
  evidence and approvals produce *now* and it has no blocker. A new or different Banner capture, a conflict, a revoked approval, a
  retraction, or a capability switched off stops it being served immediately, until it is re-reviewed.
- **Append-only and auditable.** Approvals, promotions and every promoted row (with the exact Banner captures and approval it
  came from) are never updated or deleted; a re-promotion is a new version, a retraction is a new row.
- **Never touches the verified catalog or the evidence.** This layer only reads `banner_evidence`, `catalog_api` and
  `cse007_snapshot`. `catalog_api` is unchanged and always preferred: a course or CRN it already serves is never served twice.

## Files

| File | Purpose |
|---|---|
| `V1__catalog_promoted_schema.sql` | Schema `catalog_promoted`: 9 tables, 13 views, functions, triggers, privileges |
| `V2__catalog_live_views.sql` | Schema `catalog_live`: 6 read-only views (`course_offering`, `section_offering`, `section_meeting`, `schedulable_course`, `offering_source`, `section_requirement`) |
| `grant_reader.sql` | Gives the backend's read-only role (`psql -v reader=<role>`) schema `USAGE`, `SELECT` on the `catalog_api` and `catalog_live` views and `EXECUTE` on five hash/boolean functions; no table privileges Also column-level `SELECT` on the few `cse007_snapshot` columns the course-metadata catalog reads (`/api/terms`, course detail). |
| `install.sql` | Installs both and checks structure in one transaction (rerunnable) |
| `verify_structure.sql` | Exact objects, append-only triggers, capabilities off, no writable view, only `record_approval` inserts approvals, nothing writes evidence or the verified catalog |
| `run_promotion_validation.py` | 13 scenarios on synthetic courses (104 probes, each always rolled back) plus 20 mutation checks. Passes on a fresh database **and** after `load_synthetic_fixture.py` has committed its courses (it compares row counts before and after, and reuses the fixture's clarification row only if its content is exactly the expected one, otherwise it fails) |
| `tools/reconcile_tracker.py` (+ `test_reconcile_tracker.py`) | **Read-only** report comparing the exported research workbook with the collected Banner evidence (CRNs, credits, restrictions, time conflicts, missing review evidence, a tier preview). Never writes, approves or promotes; see below |
| `load_synthetic_fixture.py` | Loads synthetic CSE-092/091/090 into a **test** database for the Java end-to-end test. Refuses the shared database |

## Objects

| Object | Meaning |
|---|---|
| `evidence_grid / evidence_section / evidence_meeting / evidence_requirement / evidence_link` | The latest Banner captures, per course and section, as facts |
| `promotion_blocker` | One row per reason a course cannot be promoted or served (no row = no blocker) |
| `promotion_readiness` | One row per candidate course: `blockers[]`, `blocker_count`, and `state` (`BLOCKED`, `READY_TO_PROMOTE`, `PROMOTED_SERVING`, `PROMOTED_BLOCKED`). **Read-only** |
| `approval` | A person's decision (`APPROVE` / `REVOKE`), value, rationale, supporting source, and a fingerprint of the evidence reviewed |
| `promotion`, `promoted_course`, `promoted_section`, `promoted_meeting`, `promoted_requirement`, `promotion_approval`, `promotion_retraction` | The immutable promoted record, with provenance on every row |
| `capability` | What the application can enforce: all `false` today |

Blockers: `SECTION_EVIDENCE_INCOMPLETE`, `SECTION_TYPE_UNSUPPORTED` (only lecture and lab), `NO_MEETING_TIMES`, `EVIDENCE_INCONSISTENT`,
`TITLE_INCONSISTENT`, `NO_LECTURE_SECTION`, `CREDITS_NOT_WHOLE`, `CREDITS_NOT_APPROVED`, `LECTURE_CREDITS_DIFFER`,
`STANDALONE_NOT_APPROVED`, `LAB_LINKS_NOT_APPROVED`, `LAB_COUNT_NOT_APPROVED`, `LAB_WITHOUT_CONFIRMED_LECTURE`,
`REQUIREMENTS_UNKNOWN`, `REQUIREMENTS_UNENFORCED`, `REQUIREMENTS_NOT_APPROVED`, `CROSS_LISTING_NOT_APPROVED`, `CROSS_LISTED`,
`CREDIT_EXCLUSION_NOT_APPROVED`, `CREDIT_EXCLUSION_UNENFORCED`, `EVIDENCE_CONFLICT`, `APPROVAL_STALE`, `DUPLICATES_EXISTING_CATALOG`.

### Requirements fail closed
Any prerequisite, corequisite or registration-restriction text other than Banner's own three "no information" sentences is
`REQUIREMENTS_UNENFORCED`: the scheduler takes no transcript, class standing, major or college, so it cannot enforce them, and such a
course is never offered as an unconditionally valid schedule. The approval `NONE_ENFORCEABLE` is refused when Banner states any
requirement. Even Banner's "no information" sentences are not proof that nothing applies, so a `REQUIREMENTS` approval with
supporting evidence is still required. The only thing that unlocks an enforceable requirement is a `capability` flag, to be turned on in
the same change that adds the enforcement. Credit exclusions work the same way (`CREDIT_EXCLUSION_ENFORCED`): the engine cannot
stop a schedule containing two courses that cannot both count for credit.

### Privileges
The backend's read-only role gets `SELECT` on the `catalog_api` and `catalog_live` views and schema `USAGE` on `catalog_promoted`, plus `EXECUTE`
on five functions that return only hashes, codes or booleans (views run with their owner's rights but functions called from a view run as the
caller; the ones that read evidence are `SECURITY DEFINER` with a pinned search path). It can read **no table** in `catalog_promoted`,
`banner_evidence` or `cse007_snapshot`, and it cannot call `promote_course`, `record_approval` or `retract_promotion` (revoked from `PUBLIC`).

## How a person promotes a course

All as a role that may write (not the read-only backend role). **Do this only after a person has actually verified each fact.**

```sql
-- 1. What blocks it?
SELECT course_id, state, blockers FROM catalog_promoted.promotion_readiness WHERE course_id = 'CSE-NNN';

-- 2. Record the supporting evidence for each fact a gate needs (a person's statement of what they confirmed and from what source).
INSERT INTO banner_evidence.evidence_source (source_kind, page_kind, term_code, captured_on, captured_by_role, inspected_by_claude,
	content_sha256, sanitization_note, provenance_note)
VALUES ('USER_CLARIFICATION', 'OTHER', '202710', CURRENT_DATE, 'USER', false,
	encode(sha256(convert_to('<the exact statement>', 'UTF8')), 'hex'), 'Statement transcribed; no personal data.',
	'<who confirmed what, from which source, on which date>') RETURNING source_id;

-- 3. Record each approval (it stamps the CURRENT evidence fingerprint; stale or unsupported approvals are refused).
SELECT catalog_promoted.record_approval('202710', 'CSE-NNN', 'CREDITS', '<crn>', '<credits>', '<your name>', '<why>', NULL);
SELECT catalog_promoted.record_approval('202710', 'CSE-NNN', 'STANDALONE', '<crn>', 'STANDALONE', '<your name>', '<why>', '<source_id>');
-- ... REQUIREMENTS, CROSS_LISTING, CREDIT_EXCLUSION (and LAB_LINKS, REQUIRED_LAB_COUNT for a lecture with labs)

-- 4. When the blockers are gone:
SELECT catalog_promoted.promote_course('202710', 'CSE-NNN', '<your name>');

-- 5. Serve it: start the backend with DB_CATALOG_SCHEMA=catalog_live. Take it back at any time:
SELECT catalog_promoted.retract_promotion(<promotion_id>, '<your name>', '<why>');
```

Names that look automated (`script`, `bot`, `claude`, `auto`, `collector`, `system`) are refused for approvals and promotions.

## Reconciling the research workbook (read-only)

```
python3 -I data/catalog-promotion/tools/test_reconcile_tracker.py          # synthetic tests, no database, no network
PGHOST=127.0.0.1 PGPORT=... PGUSER=... PGDATABASE=<scratch copy> PGPASSFILE=... \
  python3 -I data/catalog-promotion/tools/reconcile_tracker.py --workbook ~/Downloads/TRACKER.xlsx --db \
  --export-evidence /path/outside/repo/evidence.json --out-dir /path/outside/repo/report
python3 -I data/catalog-promotion/tools/reconcile_tracker.py --workbook TRACKER.xlsx --evidence-json evidence.json    # offline rerun
```

It reads the exported `.xlsx` and evidence (a scratch copy of `banner_evidence`, in a forced read-only session; it refuses a database
named like the shared one), and prints: CRN agreement, credit consistency, Banner restrictions the workbook does not mention, whether each
row of the `BANNER AUDIT GAPS` tab is backed by Banner evidence, stated and related time conflicts, missing review evidence, what the
available evidence cannot resolve, and a **preview** of each offering's tier (`READY` / `CONDITIONAL` / `BLOCKED`, per ADR-0004). The
workbook and the evidence are treated as data. Nothing from the workbook is committed, report files must be written outside the repository,
and no reviewer or approval is ever named or implied. Keyword matching against workbook text is a heuristic: a person confirms each gap.

## Tests

```
python3 -I data/catalog-promotion/run_promotion_validation.py      # needs a TEST database (PG* variables); refuses the shared one
ALLOW_SYNTHETIC_FIXTURE=yes python3 -I data/catalog-promotion/load_synthetic_fixture.py   # then the opt-in Java tests:
RUN_LIVE_IT=true DB_URL=... DB_USERNAME=registration_reader PGPASSFILE=... ./mvnw test -Dtest='LiveCatalogIntegrationTest,DefaultSchemaHidesPromotedCoursesIntegrationTest'
```

## Known limits

- **"Latest capture"** is by `recorded_at`; evidence identical to an earlier capture is deduplicated by the collector, so a change that later reverts
  to older content is not seen as newer. The approval fingerprint covers *every* distinct capture, so any new distinct capture still invalidates approvals.
- The fingerprint is per course, not per fact: any new capture of any of a course's pages asks for re-review of all its approvals.
- Only lectures and labs are supported. Recitations, independent study, online/TBA meetings and variable or fractional credits are blockers. A reviewer's recorded policy decision for a specific CRN (for example CSE 217's Independent Study section) is proposed in [ADR-0004](../../docs/decisions/0004-proposed-class-standing-eligibility.md) point 8; it is not implemented, so such a section stays blocked.
- The scheduler cannot enforce prerequisites, restrictions or credit exclusions (see above).
- Supporting evidence is a person's recorded statement in `banner_evidence` (inserting it is a write to the append-only evidence by a person).
