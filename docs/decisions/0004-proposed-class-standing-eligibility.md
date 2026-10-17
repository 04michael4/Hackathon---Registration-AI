# ADR-0004: Enforce class-standing restrictions in the backend before scheduling

- **Status:** Proposed. Not implemented. Needs approval of the API change by the Backend API owner, and a heads-up to the Scheduling Engine owner (the engine is not changed).
- **Date:** 2026-10-16
- **Scope:** `schedule` request records and `ScheduleService`, a new `course` eligibility catalog, `data/catalog-promotion/` (a V3 migration)

## Problem
Most collected Banner courses carry registration restrictions the scheduler cannot enforce, so `catalog_promoted` blocks them (`REQUIREMENTS_UNENFORCED`). The smallest useful step is one exactly-recognized rule: Banner's "Cannot be enrolled in one of the following Classes: First-Year Student (FR)". 13 collected courses use that exact sentence (CSE 252 among them).

## Decision (proposed)
1. **Request.** An optional `classStanding` on the generate, check and suggest-electives requests only (not in `SchedulePreferences`, which the AI extracts). A closed enum `FR, SO, JR, SR, GR`; any other value is a `400` (the existing `ResponseEntityExceptionHandler` already maps unreadable JSON to `400`). Absent means "unknown", not "any". Existing requests without the field stay valid. It is self-declared and is not verified against a student record; responses should say so.
2. **Rule.** `EXCLUDED_CLASS_STANDING {FR}`. A course with this rule is eligible only if `classStanding` is present and not in the excluded set. Missing standing: not eligible. A course with no rule behaves as today.
3. **Where.** In `ScheduleService`, right after the catalog lookup and before `mapper.prepare`: an ineligible required or optional course is a `422` with a sanitized message naming the course and reason; the solver is never called. Elective suggestions drop ineligible candidates. `check` rejects sections of ineligible courses. `SchedulingEngine`, `NativeSchedulingEngine` and the solver are unchanged.
4. **Data.** A new `EligibilityCatalog` read interface with a JDBC implementation over a new view `catalog_live.course_eligibility(term_code, course_id, rule_kind, rule_value)`. Under the default `catalog_api` schema it returns no rules (only CSE 007 is served there). Only text that matches the exact sentence becomes a rule; any other restriction text stays an unenforced blocker. The rule is part of the promoted package and its hash, so it is re-checked on every read.
5. **Capability.** Replace the single `RESTRICTIONS_ENFORCED` flag with a per-kind `CLASS_EXCLUSION_ENFORCED`, switched on in the same change as the enforcement. Prerequisites, corequisites, majors, colleges, levels and credit exclusions stay unenforced and blocked.

## Consequences
- Unlocks only courses whose every requirement text is covered (restriction-only, exact FR sentence). It does not unlock courses with prerequisites or level/college restrictions.
- A person's approvals and supporting evidence are still required for each promoted course; this ADR changes what the application can enforce, not who approves.
- Contract change: `docs/api-contract.md` gets the field, the `422` reason and a change-log entry when this is implemented.
