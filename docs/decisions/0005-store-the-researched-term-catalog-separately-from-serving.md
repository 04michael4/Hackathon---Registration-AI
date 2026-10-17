# ADR-0005: Store the complete researched term catalog separately from serving

- **Status:** Proposed. Implemented in [data/catalog-research](../../data/catalog-research/README.md) for an isolated local database only (2026-10-17, not pushed, not installed in any shared database). It changes no shared interface.
- **Scope:** a new additive schema `catalog_research`; no change to `catalog_api`, `catalog_promoted`, `catalog_live`, `banner_evidence`, `cse007_snapshot`, the backend or the engine.

## Problem
[ADR-0003](0003-promote-evidence-through-approvals-into-catalog-live.md) stores a course in the serving path only after recorded approvals, so a researched course that is not yet approved has no persistent, queryable record beyond raw evidence: its lecture/lab structure, credits, restrictions, exclusions, aliases and policy decisions exist only in a spreadsheet and a local file. The team needs the full Spring 2027 catalog in the database even while most of it must not be served.

## Decision
1. **Store, approve and register are three different facts.** `catalog_research` records what was researched, with an evidence level on every row. Approval to serve stays in `catalog_promoted` / `catalog_live`. Registration eligibility is never stated.
2. **`catalog_research` is read-only toward every other layer and unread by them.** The serving path is unchanged; the adapter allow-list is unchanged.
3. **Unknown stays unknown.** `UNKNOWN`, `BANNER_NO_INFORMATION`, `USER_CONFIRMED_NONE`, `TEXT_ONLY` and `RULE_DEFINED` are distinct statuses; an unparsed prerequisite is kept as text, never guessed into a rule.
4. **Every Banner-sourced row is cross-checked against the collected evidence, and every snapshot row against `catalog_api`.** The importer aborts on any difference.
5. **The tier is derived, not stored** (`READY` = served by `catalog_api`; `BLOCKED` = evidence mismatch, undecided topic identity, or an unsupported section type without an active policy decision; otherwise `CONDITIONAL`).
6. **A section-type policy decision is an append-only `TEAM_POLICY` row** with CHECK constraints that forbid claiming Registrar certification or a registration guarantee. It makes CSE 217-010 `CONDITIONAL`; it does not change `promotion_readiness`, which still blocks the course until the serving requirements in ADR-0004 hold.
7. **Catalog-shaped `candidate_*` views** (same columns and types as the three views the adapter reads) cover every stored offering, with `schedulable` true only for what `catalog_live` serves.

## Consequences
- Re-importing a term replaces its stored rows in one transaction (history: `import_run`); decisions persist.
- This implements the *storage* half of ADR-0004's "Additive schema" (`offering_tier`, rules, exclusions, aliases, offering identity, arranged meetings, policy decision) in `catalog_research` instead of `catalog_live`. Exposing conditions to the backend remains ADR-0004's open decision.
- Nothing is served that was not served before.

## Not decided here
How `catalog_live` will read approved rows from `catalog_research`; the special-topic course-id scheme; whether prerequisite/restriction enforcement ever moves into the database.
