# ADR-0007: Serve the researched READY and CONDITIONAL courses to the backend, with their conditions (`catalog_serving`)

- **Status:** Proposed. Implemented on `feature/backend-unified-database` (2026-10-17). Needs review by the Backend API and Scheduling Engine owners; installing `catalog_serving` in the team database is a separate step.
- **Scope:** a new additive schema `catalog_serving` ([data/catalog-serving](../../data/catalog-serving/README.md)), one more allowed value for `DB_CATALOG_SCHEMA`, and an additive `conditions` list on `GET /api/courses/{courseId}`. No change to `catalog_api`, `catalog_live`, `catalog_promoted`, `catalog_research`, the engine or the schedule endpoints.

## Problem
Through `catalog_api` or `catalog_live` the backend serves one course (CSE 007): the others are researched and stored in `catalog_research` but nothing reaches the app until a person approves each fact and promotes the course ([ADR-0003](0003-promote-evidence-through-approvals-into-catalog-live.md)). The team needs a working course-search-to-schedule path for the Spring 2027 CSE courses now. [ADR-0004](0004-proposed-class-standing-eligibility.md) already decided how such courses should appear: a `CONDITIONAL` course may be proposed, but only together with what is unverified about it, and never as eligible.

## Decision
1. **A third schema the backend may read, `catalog_serving`,** with the exact three views and column types of `catalog_live` (`course_offering`, `section_offering`, `section_meeting`), so the existing queries, catalogs and engine adapter run unchanged.
2. **What it serves:** every offering whose `catalog_research.offering_tier` is `READY` or `CONDITIONAL`, sourced from Banner evidence or the verified snapshot, with a lecture, a credit value and no recorded evidence mismatch. `BLOCKED` offerings (grid-only non-CSE courses; CSE 398 until its topic identity is decided) are never served. On 2026-10-17 that is 40 courses and 50 sections.
3. **Conditions travel with the course.** `course_condition` renders each unverified fact in words: prerequisites (from the stored expression), class standing, level, college and major restrictions, department approval, credit exclusions, a mandatory meeting without a time, unverified lab/recitation requirements, asserted cross-listing and a non-lecture section type. Each carries an evidence level (`BANNER`, `CATALOG`, `USER_CLARIFICATION`, `TEAM_POLICY`) and the outcome `UNVERIFIED`. The conditions appear in the course detail response; the application does **not** check a student against them, and a course with no listed condition is still not a registration guarantee.
4. **The CSE 127 / CSE 327 dual-credit conflict is not shown as an exclusion** (decision of the project owner, 2026-10-17: follow the current catalog).
5. **Approval stays separate.** Serving a CONDITIONAL course is a team-policy decision for the demo and Spring 2027 planning, not the approval and promotion process of ADR-0003, which remains available and unchanged. A person's later approvals can make `catalog_live` serve a course independently of this schema.
6. **Read-only.** The views and functions only read; the structure check asserts no table, no writable view and no object outside the schema depending on it. The backend still opens read-only sessions.

## Consequences
- The app can search, show and schedule 40 Spring 2027 CSE courses with real times, labs and conditions. Schedules are still proposals: prerequisites and restrictions are not enforced (ADR-0004 point 1).
- Descriptions and instructors still come from the CSE 007 snapshot, so most served courses have a null description and no instructor until the ratings/instructor data is connected.
- Schedule endpoints do not return conditions yet; the frontend can show them from the course detail.
- When the 30 non-CSE courses' requirements are collected and they become `CONDITIONAL`, they appear here without a backend change.

## Not decided here
Enforcing prerequisites and class standing from a student's transcript ([ADR-0004](0004-proposed-class-standing-eligibility.md)), conditions on schedule responses, and installing `catalog_serving` in any shared database beyond the team's.
