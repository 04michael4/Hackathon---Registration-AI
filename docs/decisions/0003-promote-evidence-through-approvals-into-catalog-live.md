# ADR-0003: Promote Banner evidence through recorded approvals into `catalog_live`

- **Status:** Accepted for implementation (2026-10-16); no real course has been promoted
- **Scope:** `data/catalog-promotion/`, the adapter's `app.catalog.database.schema` property

## Decision

Evidence from Banner (`banner_evidence`, append-only) is never read by the scheduler directly and is never treated as approval.
A person records explicit approvals (with supporting evidence for anything Banner cannot state); a guarded `promote_course`
writes an immutable, versioned, provenance-bearing copy into `catalog_promoted`; and the backend can read it through
`catalog_live`, a view layer whose `course_offering`, `section_offering` and `section_meeting` have the same columns and types as in `catalog_api` (the three views the adapter reads) that combines David's verified snapshot with only the
promoted courses that **still** pass every gate against current evidence. `catalog_api` and `cse007_snapshot` are never changed.
The adapter reads `catalog_api` unless `DB_CATALOG_SCHEMA=catalog_live`; only those two values are accepted.

## Why

- Correctness first: a course reaches the engine only if every fact it needs was approved by a person and still matches current
  evidence. Unknown or unenforceable facts (standalone status, lab counts, lab credits, prerequisites, restrictions, credit
  exclusions) block, and are never inferred from missing links or "Option" labels.
- David's tables cannot hold Banner-sourced rows (`source_kind`, `source_status = 'HISTORICAL'`, a LAB-only component group, no
  standalone status) and must not be altered, so promotion needs a new schema.
- Auditability: every promoted row cites the exact captures and approval it came from; nothing is updated or deleted.

## Costs

- New evidence for a course invalidates its approvals until re-reviewed (deliberate, and coarse: per course).
- The scheduler cannot enforce prerequisites, class standing, majors or credit exclusions, so courses that have them stay blocked until
  the application can (`catalog_promoted.capability`). That includes CSE 003 (credit exclusion with CSE 007).
- One new configuration key (`app.catalog.database.schema` / `DB_CATALOG_SCHEMA`), defaulting to today's behaviour.
