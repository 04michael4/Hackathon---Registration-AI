# catalog_api: read-only views for the Spring Boot course catalog

Five PostgreSQL views, in a new schema `catalog_api`, that flatten David's `cse007_snapshot` tables into exactly what
the backend's `CourseCatalog` and `SectionCatalog` need. **Additive and read-only:** the views never create, alter,
drop or write any `cse007_snapshot` object, and they reject writes themselves.

Status: **proposal for David's review.** No backend (Java) code uses these views yet. They cover the CSE 007 dataset
only; the other 49 CSE sections from `data/staging/` are not imported.

## Files

| File | Purpose |
|---|---|
| `V1__catalog_api_views.sql` | The views. Safe to rerun (`CREATE SCHEMA IF NOT EXISTS`, `CREATE OR REPLACE VIEW`). Stops with a clear error if `cse007_snapshot` is not loaded |
| `verify_catalog_api.sql` | Read-only assertions: exact CSE 007 sections, meetings, one-lab rule, term, what each view reads, no writes allowed |
| `install.sql` | Installs and verifies in one transaction; commits only if every assertion passes |
| `test_readiness.sql` | 9 negative probes covering all six readiness rules; always rolled back |
| `check_rules_matter.py` | Removes each rule in turn and requires the test to fail; always rolled back |

## Install and reproduce the tests

Prerequisite: `cse007_snapshot` loaded from `data/cse007-snapshot/database.sql`. Use any database that holds it
(David's is `registration_demo` on `127.0.0.1:55432`; set `PGHOST`, `PGPORT`, `PGUSER`, `PGDATABASE`, and put the
password in a `PGPASSFILE`, never on the command line or in Git). From the repository root:

```
psql -X -v ON_ERROR_STOP=1 -f data/catalog-api/install.sql          # install + verify (commits on success, rerunnable)
psql -X -v ON_ERROR_STOP=1 -f data/catalog-api/test_readiness.sql   # expect: PASS ... 9 probes recorded; 8 fail-closed probes hold
python3 -I data/catalog-api/check_rules_matter.py                   # expect: OK: all six rules are covered
psql -X -v ON_ERROR_STOP=1 -f data/cse007-snapshot/verify.sql       # David's own check, expect PASS
```

Remove (views only; David's tables are untouched): `DROP SCHEMA catalog_api CASCADE;`

`verify_catalog_api.sql` hard-codes the CSE 007 expectations, like David's `verify.sql`. Update both deliberately when
more courses are imported.

## Readiness rules (fail closed)

A course reaches `section_offering` and `section_meeting` only if **every** section of the course passes all six.
Anything unknown fails the rule. An unconfirmed lab link or an unknown credit keeps the **whole course** away from the scheduler.

| # | Rule | Probe in `test_readiness.sql` |
|---|---|---|
| 1 | Credits are non-null, whole numbers, and not `NEEDS_REVIEW` | `lab 060 credits unresolved`; `DEMO-900` |
| 2 | Every non-lecture section has exactly one confirmed link to a lecture, and its `link_status` isn't `TARGET_UNKNOWN` | `lab 062 loses its link`; `lab 062 link status unknown`; `DEMO-900` |
| 3 | A lecture is never linked as another section's component | `a lecture linked as a component` |
| 4 | Every lecture with linked sections has a confirmed "exactly one per group" rule (`required_count = 1`, `USER_CONFIRMED`) | `component group unconfirmed` |
| 5 | No `OPEN` warning in `data_issue` for any of its sections | `open warning on lab 060` |
| 6 | A lecture's own `link_status` isn't `TARGET_UNKNOWN` (we can't say whether it has labs) | `lecture with unknown link status` |

`DEMO-900` is a synthetic course shaped like the unresolved labs from `data/staging/`: a lecture plus an unlinked
lab with NULL credits. It exists only inside the test's rolled-back transaction.

Limits: course-level issues have no `section_id` (for example `PREREQUISITES_UNKNOWN`), so rule 5 can't see them.
Rule 6 means lecture-only courses stay out of scheduling until a "verified standalone" link status exists. Both need a
schema decision from David. `status` is always `UNKNOWN`: there is no live open/closed data and `enrollment_snapshot` is never used.

## Queries and Java mappings

`?` is a JDBC placeholder. Queries take the **Banner term code**; the Java side maps the API term to it with an explicit
property (`2027-SPRING` -> `202710`, never a formula) and sets `CatalogSection.term` to the API term. The raw API string
finds nothing in SQL. The backend only needs `SELECT` on schema `catalog_api`.

| Method | SQL |
|---|---|
| `CourseCatalog.termExists` | `SELECT EXISTS (SELECT 1 FROM catalog_api.course_offering WHERE term_code = ?)` |
| `CourseCatalog.listCourses` | `SELECT course_id, title, credits FROM catalog_api.course_offering WHERE term_code = ? ORDER BY course_id` |
| `CourseCatalog.findCourse` | `SELECT course_id, title, credits FROM catalog_api.course_offering WHERE term_code = ? AND course_id = ?` |
| `SectionCatalog.sectionsFor` (1) | `SELECT course_id, crn, section_code, credits, component_type, lecture_crn, status FROM catalog_api.section_offering WHERE term_code = ? AND course_id = ANY(?) ORDER BY course_id, crn` |
| `SectionCatalog.sectionsFor` (2) | `SELECT crn, array_to_string(array_agg(day_of_week ORDER BY day_of_week), ',') AS days, start_time, end_time, location FROM catalog_api.section_meeting WHERE term_code = ? AND crn = ANY(?) GROUP BY crn, start_time, end_time, location ORDER BY crn, start_time, end_time, location` |

The two `CourseCatalog` queries above are the **recommended discovery version**; see the next section. The strict
version adds `AND schedulable` to `listCourses` and `findCourse`. Bind `ANY(?)` with
`connection.createArrayOf("text", ids.toArray())`.

| Column | Java |
|---|---|
| `course_id`, `title`, `credits` | `CourseSummary(courseId, title, credits)` |
| the API term (not a column) | `CatalogSection.term` = `2027-SPRING` |
| `course_id`, `crn`, `section_code`, `credits`, `component_type` | same-named `CatalogSection` fields |
| `lecture_crn` (SQL NULL for lectures) | `lectureLink` (`null` for lectures; the lecture's CRN for labs) |
| `status` | `CatalogSection.Status.valueOf(status)` (always `UNKNOWN`) |
| `days` (`"3,5"`), `start_time`, `end_time`, `location` | `new Meeting(days.map(DayOfWeek::of), LocalTime.parse(start), LocalTime.parse(end), location)`, one `Meeting` per query-2 row |

Result for CSE 007 under Banner term `202710`:

| CRN | Section | Component | Credits | lectureLink | Meetings |
|---|---|---|---|---|---|
| 12275 | 010 | LECTURE | 4 | null | Monday 09:20-10:35, TBD |
| 13132 | 060 | LAB | 0 | 12275 | Wednesday, Friday 09:20-10:35, Packard Room 112 |
| 12638 | 062 | LAB | 0 | 12275 | Wednesday, Friday 10:45-12:00, Packard Room 11 |

## Course discovery vs scheduling readiness

**The problem.** If `CourseCatalog` returned only schedule-ready courses (the strict version), a course that is
offered but not yet certified would be invisible. `CourseMentionResolver` uses `findCourse` and `listCourses`, so the
AI would put "CSE 17" under `unresolved`, as if the course did not exist. `ScheduleService` uses `findCourse`, so
selecting it would give 422 "unknown in this term". Both are wrong: the course is offered, only its lecture/lab data
isn't certified. This matters as soon as the other 49 sections are imported, because they will not pass readiness yet.

**The minimal fix, in two steps.**

1. **No interface change (this version).** `CourseCatalog` returns every offered course that has a title and a whole
   credit value (queries above, no `AND schedulable`). `SectionCatalog` stays fail-closed: it returns sections only for
   schedulable courses. The AI then recognizes the course, and the scheduler can never receive an uncertified
   course's sections. A schedule request that selects one gets `200` with no schedules and the engine's diagnostic
   "no sections available", which is safe but misleading.
2. **Additive, needs Marpu's approval.** Give `CourseCatalog` a backward-compatible default method such as
   `default boolean isSchedulable(String term, String courseId) { return true; }`. The database catalog overrides it
   with `course_offering.schedulable`; `ScheduleService` checks it before calling the engine and answers with a clear
   message ("offered, but its sections are not certified for scheduling yet"). No existing implementation or test
   has to change. Not recommended: adding a field to `CourseSummary` (many constructor call sites) or hiding
   uncertified courses.

Courses with a NULL title or a fractional or NULL credit stay hidden from `CourseCatalog` because `CourseSummary.credits`
is an `int`. Showing them needs a contract decision (variable-credit representation is still open in the API contract).

## Verified on Michael's machine (PostgreSQL 17.11, 2026-10-16)

- Fresh install from these files, proven in a rolled-back transaction: assertions pass.
- `install.sql` committed and rerun twice: view definitions byte-identical (same hash).
- `test_readiness.sql`: 9 probes, all as expected. `check_rules_matter.py`: baseline passes, all six mutations fail the test.
- `data/cse007-snapshot/verify.sql`: `PASS: SuY corrections: 2 valid lecture/lab choices at 4 credits; ...`
- David's 11 tables: identical row counts and content hashes before and after.
- Missing precondition (`cse007_snapshot` absent): `ERROR: schema cse007_snapshot is missing or incomplete`.

## Questions for David and Marpu

- David: approve the six rules; add a verified-standalone link status and `data_issue.course_id`.
- David: lab 062's room. The department web page shows `PA 112`; your data has `Room 11` from SuY. Unresolved, unchanged.
- Marpu: approve the discovery approach (step 1, then step 2), and the read-only database role.
