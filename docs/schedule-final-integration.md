# Final Schedule API integration and acceptance

Verified 2026-10-16. This report records implementation acceptance before publication. The user subsequently authorized a normal fast-forward push to pre-main; see the handoff pre-push check. No merge, cherry-pick or PR is needed for the unchanged upstream base.

## Git and preservation

- Branch: `feature/backend-schedule-final-integration`.
- Base: approved `origin/pre-main` at `4fb4ff2b97dbab2323532aa342b97a1a13c402c7`, containing merged engine, PostgreSQL and Catalog API work (PRs #4, #5 and #6).
- Linked worktree: `C:/Users/puram/AppData/Local/Temp/smartcourse-schedule-final-integration`.
- The original checkout remains on `feature/backend-course-schedule-completion-integrated` with its prior uncommitted/staged/untracked work intact. No stash/reset/discard or overwrite was used in this task.
- No local `pre-main` branch existed. Its remote reference was fetched and used directly as the feature base; neither `pre-main` nor `main` was directly developed on or modified.
- Current integration worktree contains tracked modifications/deletions and new files, no unresolved merge paths. The prior independent Course/persistence/explanation drafts remain in the original checkout, not imported into this branch.

## Authoritative components

All paths below start at `backend/src/main/java/io/github/michael04/registration/` unless stated otherwise.

| Component | Class/file | Source and disposition |
|---|---|---|
| Catalog repository | `course/persistence/CatalogDao.java`, `JdbcCatalogDao.java` | pre-main, unchanged; parameterized JdbcClient queries over catalog_api |
| Course catalog | `course/persistence/JdbcCourseCatalog.java` | pre-main, unchanged; stable API IDs and explicit term mapping |
| Section catalog | `course/persistence/JdbcSectionCatalog.java` | pre-main, unchanged; component/lecture-link/status/meeting mapping |
| Datasource/configuration | `course/persistence/CatalogDatabaseConfiguration.java`, `CatalogProperties.java`, `TermMapping.java` | pre-main, unchanged; Hikari read-only bounded/lazy pool and explicit term mapping |
| Data model/migrations | `data/cse007-snapshot/` V1-V4 and `data/catalog-api/V1__catalog_api_views.sql` | pre-main, unchanged; existing UUID tables, readiness views, links, requirements and meetings |
| Native solver | `engine/ScheduleSolver.java`, `BacktrackingScheduleSolver.java`, input/output records and `ScheduleScorer.java` | pre-main, unchanged; no algorithm/scoring modifications |
| Solver adapter | `schedule/integration/NativeSchedulingEngine.java`, `SchedulingEngine.java` | pre-main, unchanged; real native solve/check/elective operations |
| Controller | `schedule/ScheduleController.java` | inherited unchanged on integration branch; exact `/api/schedules/generate` route |
| Service | `schedule/ScheduleService.java` | inherited unchanged on integration branch; catalog preparation and actual solver invocation |
| Mapper | `schedule/EngineMapper.java` | pre-main, unchanged; closed-bundle filtering and response mapping |
| Request cap/config | `schedule/ScheduleRequestValidator.java`, `backend/src/main/resources/application.properties` | updated only for explicit ten-original-optionals / 200000 API default |

There are no JPA entities or Spring Data repositories in this authoritative implementation. No new ORM,
production database, schema, datasource or persistence model was introduced. Spring JDBC/Hikari/PostgreSQL
were already dependencies in pre-main and remain necessary; no production dependency was added or removed.
Maven Failsafe was added only to run explicit integration tests.

## Cleanup inventory

| Classification | Component/path | Previous use/references | Replacement and safety |
|---|---|---|---|
| REMOVE from production / KEEP as test fixture | `course/DummyCourseCatalog.java` | Hardcoded courses were auto-scanned and selected by default; also used by AI resolution and controller tests | Moved from src/main to the same package in src/test. Production uses unchanged JdbcCourseCatalog; all test references retained and pass. JAR check proves absence. |
| REMOVE from production / KEEP as test fixture | `course/DummySectionCatalog.java` | Hardcoded sections fed real solving in default demo mode; used by demo/manual/filtering regression tests | Moved to src/test. Production uses unchanged JdbcSectionCatalog; fixture content/tests retained. JAR check proves absence. |
| REPLACE | `application.properties` / `.env.example` catalog default | Default dummy mode could serve synthetic sections in the deployed application | Default is database. DB_URL and DB_USERNAME required. No production fallback; actual DB outage verified as 503. |
| KEEP | `NativeSchedulingEngine`, `SchedulingEngine`, `EngineMapper` | Existing API boundary and real solver adapter | Not placeholder engines; already invoke authoritative BacktrackingScheduleSolver and preserve its output. No duplicate solver existed to delete. |
| KEEP | `CatalogDatabaseConfiguration` and all pre-main JDBC classes | Actual production database configuration and repositories | Unchanged; no duplicate datasource/config was brought across from the earlier local draft. |
| KEEP, original checkout only | Previous uncommitted `CatalogPersistenceConfiguration`, `CatalogRepository`, `DatabaseCourseCatalog` and richer Course/explanation draft | Independent prior work, outside latest pre-main | Not copied into this integration branch. Original user work preserved, not deleted without approval. |
| KEEP, test-only | `FakeCatalogDao`, recording engine in `DatabaseCatalogIntegrationTest`, Mockito mocks, `EngineFixtures`, AI provider fakes | Isolated query, mapper, controller, error and AI tests | No production references. Existing recording engine fixture is explicitly Primary to avoid ambiguous DI beside the real adapter; real-pipeline tests use neither fake nor mock. |
| KEEP, test-only | Moved demo catalogs, `DemoCatalogScheduleTest`, manual-mode/AI fixture tests, new PostgreSQL fixtures | Deterministic regressions and isolated DB/HTTP tests | Maven test profile selects test-only fixtures. Profile/fixture classes are absent from the application JAR. |
| KEEP | Data migrations, seed records, prerequisite/link/meeting schema, catalog_api readiness rules | Authoritative Data-owned infrastructure | Unmodified; no production record/schema/volume was deleted or migrated. |
| KEEP, unrelated AI development option | `ai/DummyPreferenceExtractor.java` | Explicit AI_PROVIDER=dummy for preference extraction; never called by schedule/generate | Preserved teammate-owned functionality. It is not a catalog or solver fallback. AI_PROVIDER defaults to none in verification. |

No shared branch, database, schema, volume, user record or cloud resource was deleted. New test databases
are retained for inspection; the task-owned local PostgreSQL process and app were stopped after verification.

## Final execution pipeline

1. `ScheduleController.generate` applies nested/cross-field Bean Validation before entering the service.
2. `ScheduleService.generate` validates term existence through `JdbcCourseCatalog`, deduplicates IDs, keeps required membership and resolves selected courses.
3. `JdbcSectionCatalog.sectionsFor` calls `JdbcCatalogDao.sections/meetings` through the authoritative Hikari datasource. Java maps API `2027-SPRING` to Banner `202710` through TermMapping.
4. `EngineMapper.prepare` preserves component type, credits, lecture CRN and meeting blocks. Every closed component is removed when requested using the existing native bundle-safe exclusion; a lecture cannot survive after losing every required lab.
5. `NativeSchedulingEngine.solve(problem, properties.nodeCap())` translates to `ScheduleProblem` and invokes `new BacktrackingScheduleSolver(nodeCap).solve(...)`.
6. `EngineMapper.response` copies score, breakdown, credits, diagnostics and engine order unchanged, assigns ranks and derives only overload = totalCredits > 18. The existing electiveAdvice field is preserved.
7. Shared `GlobalExceptionHandler` renders safe Problem Details for invalid requests, unknown terms/courses and failures. Infeasibility remains HTTP 200 with schedules=[] and diagnostics.

The production path uses a real catalog, persistence layer, mapper and solver. The packaged application
contains exactly the authoritative provider implementation for each catalog and one native adapter.
Database failures never activate a demo repository or fabricated schedule response.

## Configuration and local run

- `app.catalog.source=${CATALOG_SOURCE:database}`; hardcoded Course/Section providers are not packaged.
- Existing DB_URL, DB_USERNAME and optional DB_PASSWORD / PGPASSFILE configure the unchanged Hikari datasource.
- Existing `app.catalog.terms[2027-SPRING]=202710` maps identifiers explicitly; raw Banner codes remain invalid API terms.
- Existing pool defaults: max 4, minimum idle 0, connection timeout 3s, query timeout 5s, server-enforced read-only sessions, lazy initialization.
- `scheduling.node-cap=${SCHEDULING_NODE_CAP:200000}`. Positive 32-bit integer; custom property reaches the real solver. The native class's own 1M default remains untouched.
- At most 10 **original** optional entries, before deduplication/required overlap removal; 11 returns 400. This explicit task requirement supersedes the previous eight/1M API settings. Consumers must review the documented config/contract changes when a PR is authorized.
- AI is not needed by generation. No external AI call was made.

Provision Data's checked-in schema and views in your development database using the README commands, then:

```bash
export DB_URL='jdbc:postgresql://localhost:5432/registration'
export DB_USERNAME='registration'  # provisioned catalog reader
# Set DB_PASSWORD or PGPASSFILE privately if required.
cd backend
./mvnw -B spring-boot:run
```

Missing URL/username fails startup. A configured but unavailable database allows health startup and returns
sanitized 503 on catalog use. No application startup schema/seed writes occur.

## Verification

Baseline `./mvnw -B test` on pristine pre-main: `Tests run: 683, Failures: 0, Errors: 0, Skipped: 10`, BUILD SUCCESS.
After moving fixtures and applying config/caps, the ordinary suite again passed with the same ten opt-in DB skips.
Then **all existing DB tests were enabled** and the final clean build ran with:

```bash
# Isolated PostgreSQL 17.6 server, role course_test, loopback 55439.
export TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55439/course_schedule_test_final'
export TEST_DATABASE_USER='course_test'
export RUN_DB_IT=true
export DB_URL='jdbc:postgresql://127.0.0.1:55439/course_schedule_test_be6b7bd5db594d639ee0b125776deeeb'
export DB_USERNAME='course_test'
./mvnw -B -Pdatabase-it clean verify
```

| Suite | Actual result |
|---|---|
| Regular suite, including existing opt-in DatabaseCatalogIntegrationTest | `Tests run: 683, Failures: 0, Errors: 0, Skipped: 0` |
| Failsafe integration/packaging | `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` |
| Build | `BUILD SUCCESS` |

**702 distinct cases passed, 0 failures/errors/skips, 0 blocked.** The 19 profile cases are ScheduleDatabaseIT (16),
SchedulingNodeCapDatabaseIT (1), SourceCatalogDatabaseIT (1), ProductionPackagingIT (1).
Ten existing real-repository cases also ran in the regular suite. A total of 28 cases exercise real PostgreSQL;
one additional packaging case proves test fixtures/profile are absent. Earlier targeted runs overlap and are not added.
[Captured per-class test counts](verification/schedule-final-automated.json).

Covered: invalid bounds/weights/maxResults/required list and no solver invocation; 10/11 original optionals;
required/optional normalization; real persisted courses/prerequisites; lecture/lab mapping; open/closed components;
TBA/async empty meetings; multiple meetings; conflicts; credit feasibility; normal zero results; exact native
scores/breakdowns/CRNs/rank cross-check; 18/19/21 overload; actual cap=1 limit flag; deterministic responses;
real readonly sessions; safe errors; CORS; existing manual/elective endpoints.

SourceCatalogDatabaseIT loads the **unchanged** Data schema/seed and views and obtains both original CSE 007
lecture/lab schedules with UNKNOWN historical status. For additional deterministic coverage,
AuthoritativeCatalogFixture imports that same schema into a new loopback test database and adds only test rows.
The test-only availability overlay supplies OPEN/CLOSED labels because production source views truthfully report
UNKNOWN. It never changes the checked-in Data files or production queries. No mocked DAO/catalog/solver replaces
the main integration pipeline. The fixture loader does not drop databases/schemas and only accepts guarded loopback
course_schedule_test database prefixes. No production instance or data was accessed.

One test-loader failure occurred: psql display/ON_ERROR_STOP directives were initially sent as SQL through JDBC.
The loader now separates those client directives and executes all verification SQL assertions. The failed tests
were rerun successfully; no test/assertion was disabled and no production schema was changed to accommodate a test.

## Standalone actual HTTP acceptance

Prepared a fresh synthetic fixture using the test-only AuthoritativeCatalogFixture.main, then ran the actual backend
with `./mvnw -B spring-boot:run`, its production default database mode, loopback binding and no test profile/AI key.
It listened on **127.0.0.1:65311**, using the authoritative pre-main datasource/DAO/catalogs/native solver.

```bash
python scripts/verify_schedule_integration.py http://127.0.0.1:65311 ../docs/verification/schedule-final-http.json
```

**14 passed, 0 failed.** Checks include the real lecture/lab pair, multiple required courses, closed lab inclusion,
blocked-time infeasibility, credits/overload, TBA/async, duplicate normalization, exactly ten optionals, eleven-entry
400, bad credits, unknown course 422 and manual-mode regression.

After those successful checks, only the task-owned PostgreSQL process was stopped. The same running application
returned **503** for a valid generation request and **200** for health, with no schedule/fallback data. Those **2 checks
passed**, giving **16 standalone HTTP checks, 0 failures**.
[Successful/validation HTTP bodies](verification/schedule-final-http.json) and
[database-unavailable HTTP evidence](verification/schedule-database-unavailable-http.json).
The app and temporary database service are now stopped; fixture data is retained. Deliberately terminating the
standalone app makes Maven's run goal report a process-termination exit; this is cleanup after the successful checks,
separate from the successful clean verification build.

## Actual API examples

`POST /api/schedules/generate` request:

```json
{
  "term": "2027-SPRING",
  "maxResults": 10,
  "includeClosedSections": false,
  "preferences": {
    "hardConstraints": {
      "requiredCourseIds": [
        "CSE-007"
      ],
      "minCredits": 4,
      "maxCredits": 4,
      "blockedTimes": []
    },
    "softPreferences": {}
  }
}
```

Actual response: **HTTP 200**.

```json
{
  "term": "2027-SPRING",
  "schedules": [
    {
      "rank": 1,
      "score": 100,
      "totalCredits": 4,
      "overload": false,
      "sections": [
        {
          "crn": "12275",
          "courseId": "CSE-007",
          "sectionCode": "010",
          "title": "Introduction to Programming",
          "credits": 4,
          "meetings": [
            {
              "days": [
                "MONDAY"
              ],
              "start": "09:20",
              "end": "10:35",
              "location": "TBD"
            },
            {
              "days": [
                "THURSDAY"
              ],
              "start": "14:00",
              "end": "14:50",
              "location": "Synthetic extra meeting"
            }
          ]
        },
        {
          "crn": "13132",
          "courseId": "CSE-007",
          "sectionCode": "060",
          "title": "Introduction to Programming",
          "credits": 0,
          "meetings": [
            {
              "days": [
                "WEDNESDAY",
                "FRIDAY"
              ],
              "start": "09:20",
              "end": "10:35",
              "location": "Asa Packer Campus | 019 Packard Lab | Room 112"
            }
          ]
        }
      ],
      "scoreBreakdown": {}
    }
  ],
  "diagnostics": [],
  "searchLimitReached": false,
  "electiveAdvice": []
}
```

Eleven original optional entries returned **HTTP 400** with this actual standard error body:

```json
{
  "detail": "Request validation failed",
  "instance": "/api/schedules/generate",
  "status": 400,
  "title": "Bad Request",
  "type": "about:blank",
  "requestId": "8185d6b9-dbba-4d70-a18c-8e9cc169a9a1",
  "errors": [
    {
      "field": "preferences.softPreferences.optionalCourseIds.value",
      "message": "must contain at most 10 optional courses"
    }
  ]
}
```

## Acceptance checklist

- [x] Dedicated feature branch from approved pre-main; original user work preserved.
- [x] Authoritative database configuration, views, repositories and native engine reused.
- [x] Production hardcoded catalogs removed; no temporary repository or mock solver active.
- [x] Valid mocks/isolated fixtures retained, with no production data/resource deletion.
- [x] Validation ordering, ten optional entries and required overlap normalization verified.
- [x] Closed lectures/labs filtered safely; component/lecture links and unscheduled empty meetings preserved.
- [x] Configured node cap reaches real solver; API default 200000 verified.
- [x] Overload uses actual credits >18; native score/breakdown/ranking unchanged.
- [x] Clean build and all relevant tests pass; real source catalog, database and solver exercised.
- [x] Feasible/infeasible schedules and actual HTTP success/error/outage responses captured.
- [x] Architecture, API/setup docs, cleanup inventory and handoff updated.

## Remaining issues and boundaries

- No unresolved integration defect or blocked acceptance check remains for schedule/generate.
- The authoritative shipped dataset covers CSE 007 only. Full-university catalog coverage, current seats, deployment
  and student prerequisite eligibility are not provided by this integration.
- The unchanged authoritative readiness views intentionally hide uncertified/fractional/unknown-credit courses.
  Discovery versus schedulability and verified standalone link statuses remain documented Data/API design decisions;
  no incompatible model or readiness rule was silently introduced.
- The original checkout's uncommitted richer Course/explanation work was preserved separately. This integration
  does not bring its competing datasource or AI stretch implementation into the pre-main-based branch.
- No frontend, live AI evaluation, production deployment or external infrastructure deletion was performed.
- The user explicitly authorized committing and pushing this integration to pre-main after verification. No PR or main-branch action is part of that authorization. Publication status is confirmed after the remote push.
