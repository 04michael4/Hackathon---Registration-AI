# Course and Schedule API verification

Verified locally on 2026-10-16. This report records the earlier completion draft on `feature/backend-course-schedule-completion-integrated`, based on `b715f86`.

The final consolidation was completed separately on `feature/backend-schedule-final-integration` and pushed to `pre-main` at `5aa3e82`. This older draft retains its independent persistence implementation for review; it is not the final production integration. The user authorized committing all remaining draft files locally. A fresh pre-commit `./mvnw -B test` run passed all 588 tests with zero failures, errors or skips. No additional push is part of this commit task.

For the subsequently resolved combination of this draft and pre-main, see [merge verification](merge-resolution.md). The architecture and counts below are the original draft checkpoint.

## Git and scope

- Feature branch: `feature/backend-course-schedule-completion-integrated`.
- Base: `origin/pre-main`, `b715f867a3fc49a9d25e9cb3128e2b266fd0f2ce` (PR #4 already merged by the team).
- At the original verification checkpoint, the working tree contained modified tracked files plus new implementation, tests and evidence files; some changes were staged from the preserved stash application. No unmerged paths existed.
- The original implementation task did not commit, push, merge, cherry-pick or create a PR. The later user-authorized local commit saves this draft separately from the final integration; `main` and `pre-main` are unchanged by that commit task.
- Work initially began on `feature/backend-course-schedule-completion` from `7ca189e`. The team integrated the solver into `pre-main` during the task. A backup stash and the original feature branch were retained, and a **new** feature branch was created from the integrated base before applying the task changes. No teammate branch was imported.
- Data's SQL was exported read-only to TEMP for verification and executed only in a disposable test schema; it is not copied into this feature branch. Production data provisioning remains Data-owned.

## Endpoint acceptance

| Endpoint | Implemented | Executed verification |
|---|---|---|
| `GET /api/terms` | Yes | Real PostgreSQL and HTTP; terms, actual capture dates and null dates |
| `GET /api/courses` | Yes | Real PostgreSQL and HTTP; code/title search, subject, stable pagination, counts, empty results and errors |
| `GET /api/courses/{courseId}` | Yes | Real PostgreSQL and HTTP; credits, description, instructors, sections and grouped meeting times |
| `POST /api/schedules/generate` | Yes | Real PostgreSQL, mapper and native solver over HTTP; feasible/infeasible, components, closed filtering, scores, overload, validation and cap |
| `POST /api/schedules/explain` | Yes | Real PostgreSQL, engine selection validation and official SDK over HTTP to a **local provider fake**; success, validation, refusal, retry and upstream errors |

Every documented Course/Schedule route is implemented and exercised. Local backend acceptance is complete for the documented MVP data model. This does not certify deployment, current seat availability, full-university data coverage or live model quality.

## Initial audit and resolution

| Area | Initial classification | Final evidence |
|---|---|---|
| Java 21 / Spring Boot 4.1.1 / Maven Wrapper | COMPLETE | Existing toolchain retained; clean build passes |
| Shared errors, strict JSON and CORS | COMPLETE | Existing common implementation reused; regressions pass |
| Course HTTP API | MISSING | Controller, service and response records added; all three routes verified |
| Database catalog | MISSING | Opt-in read-only JDBC provider; synthetic and original source schema verified |
| JPA entities/repositories | MISSING / not applicable | No JPA implementation existed; existing Data schema is queried directly without ORM DDL |
| Schedule validation and response mapping | PARTIAL | Retained; ten-optionals/200000 default required by this task verified |
| Native solver integration | Initially BLOCKED on teammate branch | Resolved when the team merged PR #4; new feature branch uses that base |
| Schedule explanation | MISSING | Existing documented route implemented; grounded facts and offline provider pipeline verified |
| DB/solver/HTTP end-to-end tests | MISSING | 40 profile tests, 3 original-source tests and 17 standalone HTTP checks pass |

## Actual service connections

`CourseController.terms/search/detail` calls `CourseService`, which resolves `CourseReadCatalog`.
`DatabaseCourseCatalog` implements that interface plus the existing `CourseCatalog`, `SectionCatalog`
and new exact-CRN `SectionLookupCatalog`. It delegates to `CatalogRepository`, using parameterized
`JdbcTemplate` queries and an enforced read-only `catalogTransactionManager`.

`ScheduleController.generate` calls `ScheduleService.generate`: Bean Validation runs before service/catalog/solver access;
original optional-entry count is validated before deduplication; required membership wins over optional membership.
Course IDs resolve through `DatabaseCourseCatalog.findCourse`; `sectionsFor` supplies the same catalog's sections.
`EngineMapper.prepare` filters term/status data and closed bundles and maps components, lecture CRNs and meetings.
`NativeSchedulingEngine.solve(problem, nodeCap)` translates the input to `ScheduleProblem` and calls the actual
`BacktrackingScheduleSolver(nodeCap).solve`. Existing response mapping copies score, breakdown and engine order,
assigns ranks, and derives only `overload = totalCredits > 18`. Infeasibility returns 200 with empty schedules and diagnostics.

`ScheduleExplanationController` calls `ScheduleExplanationService.explain`. It reloads term/CRNs, validates the
same preferences, builds actual catalog input and calls `NativeSchedulingEngine.checkSelection`, which delegates
to the existing native `SelectionChecker`. Incomplete/conflicting selections return 422 before AI access.
`AnthropicScheduleExplainer` sends only reloaded facts and engine totals/scores through the official SDK, validates
the structured response and uses the existing safe provider error mapping. No model output decides feasibility.
The dependency remains one-way: AI may use schedule validation; engine/schedule never depend on AI.

No native solver algorithm, scoring formula, shared preference record or common exception handler was changed.
`spring-jdbc` and the PostgreSQL JDBC driver are narrowly required for the repository; Maven Failsafe runs opt-in DB tests.
No JPA, startup DDL, production seeds, invented public fields, endpoints or filters were added.

## Contract and data behavior

- Credit bounds 1-21, ordered minimum/maximum; all weights 0-5; required list nonempty; maxResults 1-10.
- More than **10 original optional entries** returns 400, including duplicates/overlaps; exactly 10 is accepted. Invalid requests never invoke the solver.
- API `scheduling.node-cap=${SCHEDULING_NODE_CAP:200000}` reaches the solver; custom cap 1 proves the limit flag. Native class default 1M remains untouched.
- Closed lectures/labs/recitations are removed before solving when requested, including lectures whose required components are all closed. UNKNOWN is retained.
- Labs/recitations retain their lecture CRN; lectures have null lectureLink. Malformed associations fail safely.
- TBA/asynchronous sections have empty meetings. Multiple meetings, locations and instructor names survive mapping; no placeholder time is invented.
- Course reads retain fractional numeric precision and null descriptions/locations. Historical seat text remains UNKNOWN; a transcription verification date is never substituted for an unknown capture date.
- Prerequisite/corequisite JSON and UNKNOWN versus USER_CONFIRMED_NONE remain readable and verified internally. The established public Course response has no prerequisite/component/link fields; none were invented. Student eligibility evaluation remains outside MVP.
- Overload uses actual output credits: 18=false, 19=true, 21=true. Direct native cross-checks verify scores, breakdowns, weights and result order unchanged.
- Shared Problem Details handles 400/404/422/429/500/502/503 with request IDs and safe messages. Existing CORS behavior and anonymous MVP access are retained.

## Executed automated verification

Commands ran from `backend/`, with JDK 21 and the repository wrapper through Git Bash on Windows.
Windows `mvnw.cmd` has the existing pre-Maven error; no wrapper files were changed.

| Command | Actual final result |
|---|---|
| `./mvnw -B -Pdatabase-it clean verify` | Regular suite: `Tests run: 588, Failures: 0, Errors: 0, Skipped: 0`; DB suite: `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS` |
| `./mvnw -B -Dtest=SourceCatalogVerification test` | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS` |

**631 distinct automated test cases passed; 0 failed, 0 errors, 0 skipped, 0 blocked in the final runs.**
The 43 opt-in database cases include CourseDatabaseIT (13), ScheduleDatabaseIT (14),
SchedulingNodeCapDatabaseIT (1), ScheduleExplanationDatabaseIT (12), and SourceCatalogVerification (3).
Earlier targeted runs overlap and are not added to the total.
[Per-class counts and commands](verification/automated-results.json) are captured from actual XML reports.

Real PostgreSQL 17.6 ran on loopback port 55439 in a new TEMP cluster; Docker's daemon was unavailable.
The native bundle from Maven Central had its SHA-256 verified against its published checksum
(`C91D39B6DB91CF8811E3B20D941E74A0396E277360873821DAAB47CDB4C1282C`;
[bundle provenance](https://github.com/zonkyio/embedded-postgres-binaries)). No existing/production database was used.
Fixtures accept only loopback `course_schedule_test*` databases and validated disposable `course_api_*` schemas.
The test role can provision that isolated database; production application reads were proved unable to write.

Reproduce after provisioning your local PostgreSQL test server:

```bash
export TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/course_schedule_test'
export TEST_DATABASE_USER='postgres'
# Set TEST_DATABASE_PASSWORD in the environment if required.
./mvnw -B -Pdatabase-it clean verify
# Optional original-source check; provide Data's original database.sql, not a production connection:
export SOURCE_CATALOG_SQL='/path/to/Data/cse007-snapshot/database.sql'
./mvnw -B -Dtest=SourceCatalogVerification test
```

The source check executes Data's unmodified V1-V4 schema/seed SQL in a disposable schema, verifies
actual CSE 007 fields and three requirement assessment rows, generates both valid lecture/lab pairs,
and distinguishes earliest-start soft ranking from hard blocked-time exclusion.

## Standalone app and actual HTTP examples

Started with the documented `./mvnw -B spring-boot:run` command, plus loopback binding and a random port.
Configuration: `CATALOG_SOURCE=database`, test DB above, `CATALOG_SCHEMA=course_api_live`.
For explanation only, `AI_PROVIDER=claude` used a fake key and `app.ai.anthropic.base-url` pointing to a local
Anthropic HTTP fixture. The real SDK parsed its response; no external/paid AI call occurred.
The actual backend listened on **127.0.0.1:54382**.

```bash
python scripts/verify_course_schedule.py http://127.0.0.1:54382 ../docs/verification/course-schedule-http.json
```

Actual result: **14 passed, 0 failed**, plus **3 passed, 0 failed** standalone explanation requests.
All Course and Schedule routes were exercised; returned schedule CRNs are persisted fixture sections.
Complete bodies/statuses: [Course/generation HTTP](verification/course-schedule-http.json),
[explanation HTTP](verification/schedule-explanation-http.json).

Course request: `GET /api/courses?term=TEST-2027&q=cse007&size=2` -> **200**.

```json
{
  "items": [
    {
      "courseId": "CSE-007",
      "subject": "CSE",
      "number": "007",
      "title": "Synthetic Introduction to Programming",
      "credits": 4.0,
      "sectionCount": 4
    }
  ],
  "page": 0,
  "size": 2,
  "totalItems": 1,
  "totalPages": 1
}
```

Schedule request: `POST /api/schedules/generate`:

```json
{
  "term": "TEST-2027",
  "maxResults": 2,
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

Actual response: **200**.

```json
{
  "term": "TEST-2027",
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
          "title": "Synthetic Introduction to Programming",
          "credits": 4,
          "meetings": [
            {
              "days": [
                "MONDAY"
              ],
              "start": "09:20",
              "end": "10:35",
              "location": "Synthetic Hall 1"
            },
            {
              "days": [
                "THURSDAY"
              ],
              "start": "14:00",
              "end": "14:50",
              "location": null
            }
          ]
        },
        {
          "crn": "13132",
          "courseId": "CSE-007",
          "sectionCode": "060",
          "title": "Synthetic Introduction to Programming",
          "credits": 0,
          "meetings": [
            {
              "days": [
                "WEDNESDAY",
                "FRIDAY"
              ],
              "start": "09:20",
              "end": "10:35",
              "location": "Synthetic Lab 112"
            }
          ]
        }
      ],
      "scoreBreakdown": {}
    }
  ],
  "diagnostics": [],
  "searchLimitReached": false
}
```

Blocking all Monday meetings returned **200** with:

```json
{
  "term": "TEST-2027",
  "schedules": [],
  "diagnostics": [
    "CSE-007: every section overlaps your blocked times."
  ],
  "searchLimitReached": false
}
```

Actual explanation response, from the local provider fixture through the complete backend pipeline: **200**.

```json
{
  "explanation": "This 4-credit schedule pairs the lecture with its required lab.",
  "highlights": [
    "Includes CSE-007",
    "Lecture and lab have no time conflict"
  ]
}
```

## Failures investigated and resolved

- Initial base lacked the native solver; the first standalone check correctly failed six generation requests with 503. Resolved by creating a new branch from team-integrated pre-main, not importing a teammate branch. Final evidence overwrites the failed run with actual successful requests.
- Initial acceptance harness used Python Request.method, which is not present; changed to get_method and reran.
- A later full DB run failed initialization because temporary PostgreSQL timezone files were missing. The original-source run also exposed a missing plpgsql runtime library. Restored missing runtime files from the checksum-verified archive; the complete 628-case clean build and separate 3-case source verification both passed afterward. These were test infrastructure failures, not hidden by mocks, skips or changed assertions.

The test app was intentionally stopped after verification. Maven spring-boot:run consequently reported process termination with exit -1; this is the cleanup exit, separate from the successful clean verify build and HTTP results. The temporary PostgreSQL cluster and local provider fixture were also stopped.

## Remaining limits and handoff

1. Production schema/data must be provisioned separately. This feature does not merge Data's branch, deploy a database or supply a full-university/current-seat dataset.
2. The native public data model supports known integral credits, one confirmed lecture parent per linked section, and one required child per component group. Fractional/unknown scheduling credits and unsupported/unknown component cardinalities fail with a sanitized 500 instead of guessing or rounding. Public Course reads preserve fractional credits. Extending the native model requires Data/engine owner review; no solver algorithm or incompatible contract was silently changed.
3. Prerequisite/student eligibility evaluation and public prerequisite fields remain outside the established MVP contract. Stored expressions and assessment states are preserved and tested.
4. Live paid-provider explanation quality, frontend rendering, deployment and production infrastructure were not tested. Offline SDK/error behavior was tested; generation itself requires no AI.
5. The explicit task's ten-optionals/200000 API settings supersede the integrated branch's eight/1M settings. Frontend, Data, AI and engine owners should review the documented contract/config and new read-only repository boundary when a PR is authorized.
6. Temporary app/database/provider services are stopped after verification. Fixtures and source export remain only in TEMP for investigation; no native binaries or production secrets are added to Git.

## Created and modified files

Paths below are relative to the repository root. All are part of this uncommitted task.

| Change | File | Purpose |
|---|---|---|
| Modified | `.env.example` | Opt-in database configuration and 200000 API node-cap default. |
| Modified | `HANDOFF.md` | Implemented contract, setup, architecture, verification or team handoff documentation. |
| Modified | `README.md` | Implemented contract, setup, architecture, verification or team handoff documentation. |
| Modified | `backend/pom.xml` | Spring JDBC/PostgreSQL dependencies and opt-in Failsafe profile. |
| Created | `backend/scripts/verify_course_schedule.py` | Reproducible standalone HTTP acceptance checks; exits nonzero on failure. |
| Modified | `backend/src/main/java/io/github/michael04/registration/ai/AiConfiguration.java` | Grounded explanation route, validated SDK output or provider wiring. |
| Modified | `backend/src/main/java/io/github/michael04/registration/ai/AnthropicClaudeGateway.java` | Grounded explanation route, validated SDK output or provider wiring. |
| Created | `backend/src/main/java/io/github/michael04/registration/ai/AnthropicScheduleExplainer.java` | Grounded explanation route, validated SDK output or provider wiring. |
| Created | `backend/src/main/java/io/github/michael04/registration/ai/ExplainScheduleRequest.java` | Grounded explanation route, validated SDK output or provider wiring. |
| Created | `backend/src/main/java/io/github/michael04/registration/ai/ExplainScheduleResponse.java` | Grounded explanation route, validated SDK output or provider wiring. |
| Created | `backend/src/main/java/io/github/michael04/registration/ai/ScheduleExplainer.java` | Grounded explanation route, validated SDK output or provider wiring. |
| Created | `backend/src/main/java/io/github/michael04/registration/ai/ScheduleExplanationController.java` | Grounded explanation route, validated SDK output or provider wiring. |
| Created | `backend/src/main/java/io/github/michael04/registration/ai/ScheduleExplanationService.java` | Grounded explanation route, validated SDK output or provider wiring. |
| Modified | `backend/src/main/java/io/github/michael04/registration/course/CourseCatalog.java` | Course routes, service, response DTOs or shared read boundary. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/CourseController.java` | Course routes, service, response DTOs or shared read boundary. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/CourseReadCatalog.java` | Course routes, service, response DTOs or shared read boundary. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/CourseResponses.java` | Course routes, service, response DTOs or shared read boundary. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/CourseService.java` | Course routes, service, response DTOs or shared read boundary. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/SectionLookupCatalog.java` | Course routes, service, response DTOs or shared read boundary. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/persistence/CatalogDatabaseProperties.java` | Read-only PostgreSQL catalog configuration, queries or data mapping. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/persistence/CatalogPersistenceConfiguration.java` | Read-only PostgreSQL catalog configuration, queries or data mapping. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/persistence/CatalogRepository.java` | Read-only PostgreSQL catalog configuration, queries or data mapping. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/persistence/CatalogRequirement.java` | Read-only PostgreSQL catalog configuration, queries or data mapping. |
| Created | `backend/src/main/java/io/github/michael04/registration/course/persistence/DatabaseCourseCatalog.java` | Read-only PostgreSQL catalog configuration, queries or data mapping. |
| Modified | `backend/src/main/java/io/github/michael04/registration/schedule/ScheduleRequestValidator.java` | Native selection-check adapter or requested optional-course limit. |
| Modified | `backend/src/main/java/io/github/michael04/registration/schedule/integration/NativeSchedulingEngine.java` | Native selection-check adapter or requested optional-course limit. |
| Modified | `backend/src/main/resources/application.properties` | Opt-in database configuration and 200000 API node-cap default. |
| Created | `backend/src/test/java/io/github/michael04/registration/ai/ScheduleExplanationControllerTest.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/java/io/github/michael04/registration/ai/ScheduleExplanationDatabaseIT.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/java/io/github/michael04/registration/course/CourseControllerTest.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/java/io/github/michael04/registration/course/CourseDatabaseIT.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/java/io/github/michael04/registration/course/CourseUnavailableTest.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/java/io/github/michael04/registration/course/FixtureDatabase.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/java/io/github/michael04/registration/course/SourceCatalogVerification.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/java/io/github/michael04/registration/course/persistence/CatalogPersistenceConfigurationTest.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Modified | `backend/src/test/java/io/github/michael04/registration/schedule/EngineMapperTest.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Modified | `backend/src/test/java/io/github/michael04/registration/schedule/ScheduleControllerTest.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/java/io/github/michael04/registration/schedule/ScheduleDatabaseIT.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Modified | `backend/src/test/java/io/github/michael04/registration/schedule/SchedulingConfigurationTest.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/java/io/github/michael04/registration/schedule/SchedulingNodeCapDatabaseIT.java` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Created | `backend/src/test/resources/db/course-api-test-schema.sql` | Deterministic fixture or regression, controller, configuration, database and real HTTP verification. |
| Modified | `docs/api-contract.md` | Implemented contract, setup, architecture, verification or team handoff documentation. |
| Modified | `docs/architecture.md` | Implemented contract, setup, architecture, verification or team handoff documentation. |
| Created | `docs/course-schedule-verification.md` | Implemented contract, setup, architecture, verification or team handoff documentation. |
| Created | `docs/verification/automated-results.json` | Captured actual HTTP bodies/statuses or executed per-class test results. |
| Created | `docs/verification/course-schedule-http.json` | Captured actual HTTP bodies/statuses or executed per-class test results. |
| Created | `docs/verification/schedule-explanation-http.json` | Captured actual HTTP bodies/statuses or executed per-class test results. |
