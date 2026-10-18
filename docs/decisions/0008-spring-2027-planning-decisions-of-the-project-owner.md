# ADR-0008: Spring 2027 planning decisions of the project owner

- **Status:** Accepted by the project owner (2026-10-17). Applied to the team database on 2026-10-18; two of the eleven need backend or engine work that is **not done**.
- **Scope:** data in `catalog_research`, the `catalog_serving` views, one backend id rule. Each decision is also stored in `catalog_research.owner_decision` (append-only), with who decided, when and where it is implemented. They are the owner's statements, evidence level `USER_CLARIFICATION`, never Registrar statements.

## Decisions and where they stand
| Decision | Effect | Status |
|---|---|---|
| **CSE 303 prerequisite.** Banner's "ECE 201 Or ( CSE 201 Or CSE 202 ) And CSE 109" is read as (ECE 201 or CSE 201 or CSE 202) **and** CSE 109 | Stored as a structured rule; Banner's wording stays as the source text | ✅ database |
| **CSE 127 / 327.** Follow the current catalog; a student may take both | The older catalog's exclusion stays stored as `SOURCES_CONFLICT` and is not shown | ✅ serving |
| **CSE 398 topics** are separate courses `CSE-398-042` and `CSE-398-052` | New identity status `TOPIC_SEPARATE_OFFERING`; both become `CONDITIONAL` and served; the backend accepts the topic suffix in a course id (`GET /api/courses/CSE-398-042`) and keeps the course number `398` | ✅ database, serving, backend |
| **ECO 325 / MKT 325 are one class** under two codes, taken once, never both in a schedule, never in conflict with each other | Stored as a no-double-credit pair both ways; students see "No credit for both this course and …" | 🟡 shown only: the scheduler still allows both and reports their identical times as a conflict. Needs a backend/engine change (the exclusion handling of [ADR-0004](0004-proposed-class-standing-eligibility.md) point 5) |
| **Non-CSE requirements are collected** and the courses become `CONDITIONAL` | Done from Banner Class Details (not the public catalog pages): 28 of 30 courses (PRs 14 and 15) | ✅ database |
| **Instructor note.** The "names withheld" note is out of date | The unresolved-item note points to `professor_ratings` | ✅ database |
| **Common evening exams warn only** (ECO 001, MATH 022, 052, 205, 231) | A `COMMON_EXAMS` condition lists the dates; exams are not meetings and not conflicts | ✅ serving |
| **CSE 367 / 467 team meeting** with no published time: note only | Already an `ARRANGED_MEETING` condition | ✅ serving |
| **MATH 312 / 334 credits per section** (4 or 3 as the grid shows) | Stored per section, but the courses are **not served**: the backend reads one credit value per course | 🟡 not served; needs backend and engine support for per-section credits |
| **Unlinked recitations: any pairing, flagged** | Superseded by the data: Banner lists every lecture's recitations. A course whose components cannot be linked to one lecture (CHM 030's labs are shared by four lectures, PHY 012 is lab-only) stays blocked | recorded only |
| **Show every rating prediction** whatever its confidence | `catalog_serving` returns all of them with their confidence | ✅ serving |

## Consequences
- 68 courses are served (40 CSE + 2 CSE 398 topics + 26 non-CSE).
- **Contract change (additive):** a course id may carry a topic suffix, `^[A-Z]+-[0-9]+(-[0-9]+)?$`; the API's `number` stays `398`. The AI course resolver is **not** updated: a typed "CSE 398" now matches two courses.
- Open for the backend/engine owners: the ECO/MKT "never both" rule, per-section credits (MATH 312/334), shared labs (CHM 030) and lab-only courses (PHY 012).
