# ADR-0004: Separate schedule proposals from registration eligibility, and show every unresolved requirement

- **Status:** Proposed. Not implemented. Needs approval of the API and schema items in [Decisions to approve](#decisions-to-approve) by the Backend API owner and the Scheduling Engine owner.
- **Date:** 2026-10-16 (revises the first draft of this ADR, which proposed only an exact-FR rule that returned 422)
- **Scope:** `schedule` and `course` request/response records, a new eligibility evaluator in the backend, additive views in `catalog_live` (a V3 migration), the tier computed by `catalog_promoted.promotion_readiness`

## Problem
Most collected Spring 2027 courses carry requirements the scheduler cannot check: prerequisites (some concurrent), class standing, undergraduate/graduate level, college, major, department approval, linked components, credit exclusions and an extra team meeting. The first draft rejected FR students with a 422. The team's product policy is different: a proposed timetable may include an FR-restricted course when it is labelled as needing an approved CLASS override that is not guaranteed, while *registration* eligibility keeps the official restriction. Missing information must be shown as unresolved, never as eligible.

## Decision (proposed)
**1. Two questions, answered separately.**
- *Schedule proposal* (`POST /api/schedules/generate`, `check`, `suggest-electives`): never fails because of eligibility. Every requirement it cannot satisfy or verify is returned as a condition.
- *Registration eligibility* (a new strict endpoint): evaluates the official restrictions and returns `422` when a restriction is violated and no authorized override is recorded. Insufficient profile data is `UNRESOLVED`, never `ELIGIBLE`.

**2. Outcomes.** Per requirement: `SATISFIED`, `VIOLATED`, `OVERRIDE_REQUIRED` (violated, but an override exists in principle; never guaranteed), `UNRESOLVED` (the profile or the evidence is missing), `UNVERIFIED_SOURCE` (known only from user-supplied or unreviewed evidence). The roll-up per course and per schedule is the worst outcome, in this order: `VIOLATED` > `OVERRIDE_REQUIRED` > `UNRESOLVED` > `UNVERIFIED_SOURCE` > `SATISFIED`. Only `SATISFIED` for every requirement is `ELIGIBLE`. A schedule is never described as "ready to register".

**3. Optional student profile** (every field optional; absent means unknown): `classStanding` (`FR SO JR SR GR`; any other value is a 400), `level` (`UG GR`), `college`, `majors[]`, `completedCourses[]` with an `inProgress` flag (for concurrent prerequisites), `awardedCreditCourses[]` (for exclusions) and `overrides[]` (authorized, per CRN and type). Unknown standing is reported as unverified: it is not assumed eligible and not assumed to need an FR override.

**4. Requirement kinds**, each with an evidence level (`BANNER`, `CATALOG`, `USER_CLARIFICATION`, `TEAM_POLICY`; user-supplied evidence is never labelled Registrar-verified): `CLASS_STANDING` (allowed or excluded set), `LEVEL`, `COLLEGE`, `MAJOR`, `PREREQUISITE` and `COREQUISITE` (typed AND/OR tree with a `concurrent` flag per leaf), `DEPARTMENT_APPROVAL`, `RECOMMENDED_PREPARATION` (soft), `LINKED_COMPONENTS` (required count and allowed CRNs, or "unverified"), `CREDIT_EXCLUSION` (no-double-credit, or enrollment-after-credit), `ARRANGED_MEETING` (a mandatory meeting with no published time) and `DEGREE_APPLICABILITY` (a course that registers but does not count toward a degree).

**5. Where it runs.** In the backend, after the catalog lookup and, for conditions, after the solver. `SchedulingEngine`, `NativeSchedulingEngine` and the solver are unchanged. Conditions are computed from the returned schedule in a deterministic order. Credit-exclusion pairs among the *selected* courses are checked up front: two required courses that exclude each other are a 422 (a contradictory request); an excluded optional course or elective candidate is dropped with a diagnostic.

**6. Data and serving gate.** New additive views in `catalog_live` (below); `catalog_api` and David's snapshot are unchanged. `promotion_readiness` gains a `tier`: `READY` (every requirement satisfied, or none exist), `CONDITIONAL` (timetable data is evidenced and reviewed; one or more requirements are shown as conditions) and `BLOCKED` (essential timetable data is missing, or the offering cannot be represented). A `CONDITIONAL` offering is served by `catalog_live` only while the capability `CONDITIONS_DISPLAYED` is on, which is switched on in the same change as the response field that shows the conditions. Credits still need a person's recorded approval; no reviewer of record is designated yet, so no approval exists.

**7. Offerings are identified by CRN.** Special topics under one course number (CSE 398-042 and -052) get separate offering identities with their own title, credits and requirements. Recommended: the catalog course id for such a number carries the section code (`CSE-398-042`); other courses keep `CSE-NNN`.

## Additive schema (in `catalog_live`; read by the adapter only when `DB_CATALOG_SCHEMA=catalog_live`)
- `offering_tier(term_code, course_id, tier, blocked_reason)`
- `eligibility_rule(term_code, course_id, crn, rule_kind, operator, values text[], expression jsonb, evidence_level, source_note)`
- `credit_exclusion(term_code, course_a, course_b, exclusion_kind, evidence_level, source_note)`: one row per unordered pair per source; two sources that disagree stay as two rows
- `course_alias(canonical_course_id, alias_subject, alias_number, evidence_level)`: a catalog alias is not a section cross-list and carries no CRN
- `offering_identity(term_code, crn, course_id, topic_title)` and `section_arranged_meeting(term_code, crn, description)`

Prerequisite expression grammar: `{"op":"AND"|"OR","args":[...]}` or `{"course":"CSE-017","concurrent":true}`.

## Decisions to approve
**Backend API owner**
1. The optional `studentProfile` request object (point 3) on the three schedule endpoints. Backward compatible: absent means all unknown.
2. The response additions: a per-schedule `eligibility` (`status`, `conditions[]`) and per-section conditions, each `{code, courseId, crn, outcome, evidenceLevel, message}`. Existing fields are unchanged.
3. A strict eligibility endpoint and its status codes: `200` with results, `422` only when a restriction is `VIOLATED` and no override is recorded; `UNRESOLVED` is `200` and never `ELIGIBLE`.
4. The course-id scheme for special topics (point 7), including how the AI course resolver and `requiredCourseIds` accept it.
5. `arrangedMeetings` on a section and a `degreeApplicability` flag on course detail.
6. `DB_CATALOG_SCHEMA` (already in the local promotion commit).

**Scheduling Engine owner**
7. Confirm that no engine change is needed: conditions and exclusion handling stay in the backend, and zero-credit labs and `lectureLink` keep their current meaning.
8. Confirm that dropping excluded optional courses before the solver is acceptable, rather than adding a mutual-exclusion constraint to `SchedulingEngine.Constraints` (the alternative, which would let the solver choose between two exclusive optional courses).

**Both**
9. The expression grammar, the outcome vocabulary and the roll-up order above.

## Not decided here
Who the reviewer of record is (no approval can be recorded until this is decided), whether a student's `USER_CLARIFICATION` is enough for a real enrollment recommendation, and any connection to university records (needs consent and institutional authorization).
