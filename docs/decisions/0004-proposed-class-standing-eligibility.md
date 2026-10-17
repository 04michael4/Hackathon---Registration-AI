# ADR-0004: Separate schedule proposals from registration eligibility, and show every unresolved requirement

- **Status:** Proposed. Not implemented. Needs approval of the API and schema items in [Decisions to approve](#decisions-to-approve) by the Backend API owner and the Scheduling Engine owner.
- **Date:** 2026-10-16 (revises the first draft, which proposed only an exact-FR rule that returned 422; updated the same day with the CSE 217 demo-policy decision and the 1 / 39 / 2 research classification)
- **Scope:** `schedule` and `course` request/response records, a new eligibility evaluator in the backend, additive views in `catalog_live` (a V3 migration), the tier computed by `catalog_promoted.promotion_readiness`

## Problem
Most collected Spring 2027 courses carry requirements the scheduler cannot check: prerequisites (some concurrent), class standing, undergraduate/graduate level, college, major, department approval, linked components, credit exclusions and an extra team meeting. The first draft rejected FR students with a 422. The team's product policy is different: a proposed timetable may include an FR-restricted course when it is labelled as needing an approved CLASS override that is not guaranteed, while *registration* eligibility keeps the official restriction. Missing information must be shown as unresolved, never as eligible.

## Decision (proposed)
**1. Two questions, answered separately.**
- *Schedule proposal* (`POST /api/schedules/generate`, `check`, `suggest-electives`): never fails because of eligibility. Every requirement it cannot satisfy or verify is returned as a condition.
- *Registration eligibility* (a new strict endpoint): evaluates the official restrictions and returns `422` when a restriction is violated and no authorized override is recorded. Insufficient profile data is `UNRESOLVED`, never `ELIGIBLE`.

**2. Outcomes.** Per requirement: `SATISFIED`, `VIOLATED`, `OVERRIDE_REQUIRED` (violated, but an override exists in principle; never guaranteed), `UNRESOLVED` (the profile or the evidence is missing), `UNVERIFIED_SOURCE` (known only from user-supplied or unreviewed evidence). The roll-up per course and per schedule is the worst outcome, in this order: `VIOLATED` > `OVERRIDE_REQUIRED` > `UNRESOLVED` > `UNVERIFIED_SOURCE` > `SATISFIED`. Only `SATISFIED` for every requirement is `ELIGIBLE`. A schedule is never described as "ready to register", and `CONDITIONAL` never means eligible to register.

**3. Optional student profile** (every field optional; absent means unknown): `classStanding` (`FR SO JR SR GR`; any other value is a 400), `level` (`UG GR`), `college`, `majors[]`, `completedCourses[]` with an `inProgress` flag (for concurrent prerequisites), `awardedCreditCourses[]` (for exclusions) and `overrides[]` (authorized, per CRN and type). Unknown standing is reported as unverified: it is not assumed eligible and not assumed to need an FR override.

**4. Requirement kinds**, each with an evidence level (`BANNER`, `CATALOG`, `USER_CLARIFICATION`, `TEAM_POLICY`; user-supplied evidence is never labelled Registrar-verified): `CLASS_STANDING` (allowed or excluded set), `LEVEL`, `COLLEGE`, `MAJOR`, `PREREQUISITE` and `COREQUISITE` (typed AND/OR tree with a `concurrent` flag per leaf), `DEPARTMENT_APPROVAL`, `RECOMMENDED_PREPARATION` (soft), `LINKED_COMPONENTS` (required count and allowed CRNs, or "unverified"), `CREDIT_EXCLUSION` (no-double-credit, or enrollment-after-credit), `ARRANGED_MEETING` (a mandatory meeting with no published time) and `DEGREE_APPLICABILITY` (a course that registers but does not count toward a degree).

**5. Where it runs.** In the backend, after the catalog lookup and, for conditions, after the solver. `SchedulingEngine`, `NativeSchedulingEngine` and the solver are unchanged. Conditions are computed from the returned schedule in a deterministic order. Credit-exclusion pairs among the *selected* courses are checked up front: two required courses that exclude each other are a 422 (a contradictory request); an excluded optional course or elective candidate is dropped with a diagnostic.

**6. Data and serving gate.** New additive views in `catalog_live` (below); `catalog_api` and David's snapshot are unchanged. `promotion_readiness` gains a `tier`: `READY` (every requirement satisfied, or none exist), `CONDITIONAL` (timetable data is evidenced and reviewed; one or more requirements are shown as conditions) and `BLOCKED` (essential timetable data is missing, or the offering cannot be represented). A `CONDITIONAL` offering is served by `catalog_live` only when every item in [Serving requirements](#serving-requirements-for-conditional-offerings) holds.

**7. Offerings are identified by CRN.** Special topics under one course number (CSE 398-042 and -052) get separate offering identities with their own title, credits and requirements. Recommended: the catalog course id for such a number carries the section code (`CSE-398-042`); other courses keep `CSE-NNN`.

**8. An administrative section type is metadata, not an automatic exclusion.** Banner's schedule type (`Lecture`, `Lab`, `Independent Study`, ...) is kept on the offering as `administrativeType` and shown to the student. A type outside lecture and lab blocks an offering **unless a recorded policy decision allows that CRN**; the decision is data (decided by a person, a date, the evidence level `TEAM_POLICY`, a rationale, append-only) and is read by `promotion_readiness`. It never alters the registration restrictions and does not make the offering eligible.

**9. A reviewer's demo-policy decision is not university authorization.** A decision by the designated academic evidence reviewer is `TEAM_POLICY` for the hackathon demo. It can move an offering to `CONDITIONAL` and decides how it is displayed. It does not certify that a student may register, does not waive any Banner restriction or override, and is never presented as Registrar approval. User-supplied statements (`USER_CLARIFICATION`) stay labelled as user-supplied evidence. The reviewer's identity belongs with the decision in the research workbook, not in this repository.

## Research classification (initial target, 2026-10-16)
**1 READY / 39 CONDITIONAL / 2 BLOCKED** of the 42 Spring 2027 offerings. This is a research-level classification, not a promotion: nothing is served by `catalog_live` for any of them except what `catalog_api` already serves.
- `READY` (1): CSE 007, the verified snapshot. Its "no prerequisite, corequisite or restriction" is user-confirmed, not Registrar-certified.
- `BLOCKED` (2): CSE 398-042 and CSE 398-052, until the topic-identity decision (point 7). Once it is approved they join `CONDITIONAL` (1 / 41 / 0).
- `CONDITIONAL` (39): every other offering, including **CSE 217**.

**CSE 217-010 (CRN 14003), the demo-policy decision** (the designated academic evidence reviewer, 2026-10-16; `TEAM_POLICY`, not Registrar approval): included in proposed schedules conditionally. Computer Science Projects, 3 credits, Monday/Wednesday 15:00-16:15. Prerequisite CSE 216. First-year students cannot normally enroll (Banner restriction). Instructor approval is not required for students who meet the prerequisite and the applicable standing restrictions (`USER_CLARIFICATION`). Banner reportedly classifies the section as Independent Study; that is preserved as `administrativeType` and does not exclude the course from proposed timetables. Registration eligibility is not guaranteed. Until the decision is recorded as data (point 8) and the serving requirements hold, the promotion layer still blocks it (`SECTION_TYPE_UNSUPPORTED`).

## Serving requirements for conditional offerings
All of these must hold before `catalog_live` serves a `CONDITIONAL` offering. None holds today.
1. The Backend API and Scheduling Engine owners have approved the items below, and the matching code is merged.
2. The V3 additive schema is applied to the **demo database** (a separate database; no shared-database install without a separate approval).
3. The capability `CONDITIONS_DISPLAYED` is on, switched on in the same change as the response field that shows the conditions to the student.
4. The offering's credits have a person's recorded approval (`approval` row, with the evidence fingerprint current). Lab credits keep their own approval and supporting source. Standalone, cross-listing and credit-exclusion claims that nobody has confirmed are shown as conditions and do not block; a claim a person does confirm is recorded with its supporting source.
5. For a section type outside lecture and lab (CSE 217), the recorded policy decision of point 8 exists for that CRN.
6. The evidence is still current: any new Banner capture for the course makes its approvals stale until re-reviewed, and a conflict blocks serving.
7. The reviewer's identity is recorded in the research workbook alongside each decision (a repository file does not name the reviewer). The stored decision names only the role (`DEMO_ACADEMIC_EVIDENCE_REVIEWER`) and points to the workbook entry.

## Additive schema (in `catalog_live`; read by the adapter only when `DB_CATALOG_SCHEMA=catalog_live`)
_Update 2026-10-17: the **storage** half exists as `catalog_research` ([ADR-0005](0005-store-the-researched-term-catalog-separately-from-serving.md), local database only): offering identity, eligibility rules, credit exclusions, aliases, arranged meetings and an append-only `TEAM_POLICY` decision table. The `catalog_live` views below, which would expose conditions to the backend, are still not implemented._
- `offering_tier(term_code, course_id, tier, blocked_reason)`
- `eligibility_rule(term_code, course_id, crn, rule_kind, operator, values text[], expression jsonb, evidence_level, source_note)`
- `credit_exclusion(term_code, course_a, course_b, exclusion_kind, evidence_level, source_note)`: one row per unordered pair per source; two sources that disagree stay as two rows
- `course_alias(canonical_course_id, alias_subject, alias_number, evidence_level)`: a catalog alias is not a section cross-list and carries no CRN
- `offering_identity(term_code, crn, course_id, topic_title, administrative_type)` and `section_arranged_meeting(term_code, crn, description)`
- in `catalog_promoted`: an append-only `tier_policy_decision(term_code, crn, decision, evidence_level, rationale, decided_on, recorded_by)` (point 8). It is written only by a person; no function writes it.

Prerequisite expression grammar: `{"op":"AND"|"OR","args":[...]}` or `{"course":"CSE-017","concurrent":true}`.

## Decisions to approve
**Backend API owner**
1. The optional `studentProfile` request object (point 3) on the three schedule endpoints. Backward compatible: absent means all unknown.
2. The response additions: a per-schedule `eligibility` (`status`, `conditions[]`) and per-section conditions, each `{code, courseId, crn, outcome, evidenceLevel, message}`. Existing fields are unchanged.
3. Eligibility checks: a strict endpoint and its status codes (`200` with results, `422` only when a restriction is `VIOLATED` and no override is recorded; `UNRESOLVED` is `200` and never `ELIGIBLE`).
4. The course-id scheme for special topics (point 7), including how the AI course resolver and `requiredCourseIds` accept it.
5. `administrativeType` on a section (point 8: shown, never silently dropped), `arrangedMeetings` on a section, and a `degreeApplicability` flag on course detail.
6. The wording of the conditions the student sees: a condition says what is unverified and by whom (Banner, catalog, user-supplied, team policy), and `CONDITIONAL` is never described as eligible.
7. `DB_CATALOG_SCHEMA` (already in the local promotion commit).

**Scheduling Engine owner**
8. Confirm that no engine change is needed: conditions and exclusion handling stay in the backend, so `SchedulingEngine` and the native solver keep their inputs and outputs.
9. Linked labs: confirm that a lecture with exactly one required lab, where the lab carries zero incremental credits (CSE 017, 109, 202, 216 and 241), is handled by the current `lectureLink` and credit semantics, including courses whose lecture meetings overlap a lab choice.
10. Credit exclusions: confirm that dropping an excluded optional course before the solver is acceptable, rather than adding a mutual-exclusion constraint to `SchedulingEngine.Constraints` (the alternative, which would let the solver choose between two exclusive optional courses).
11. Unscheduled mandatory meetings (CSE 367 and 467): confirm that a section with a fixed class time plus an unscheduled team meeting is solved on its fixed time only, and that the extra meeting stays a backend condition rather than an engine input.

**Both**
12. The expression grammar, the outcome vocabulary and the roll-up order above.

## Not decided here
Whether a student's `USER_CLARIFICATION` is enough for a real enrollment recommendation, and any connection to university records (needs consent and institutional authorization). The designated reviewer's demo-policy decisions are recorded as `TEAM_POLICY` and are separate from both.
