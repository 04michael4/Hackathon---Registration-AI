# Local pre-main merge resolution

Verified 2026-10-16 20:34 EDT on `feature/backend-course-schedule-completion-integrated`.
Local HEAD `4969f25` is being merged with `origin/pre-main` / MERGE_HEAD `5aa3e82`.
The user initiated the pull; this task resolves its 14 conflicted paths without reset, stash, force push or discarded work.
The merge changes are left staged for review. No additional commit or push was authorized/performed.

## Resolution

- Keep pre-main's `CatalogDatabaseConfiguration`, Hikari datasource, `JdbcCatalogDao`, `JdbcCourseCatalog`,
  `JdbcSectionCatalog`, explicit API/Banner term mapping, readiness views and real native engine.
- Preserve pre-main's manual-check/elective routes, electiveAdvice, section componentType/credits/lectureLink,
  lecture-only sectionCount, ten original optional entries and 200000-node API default.
- Keep the local `/api/terms` and `/api/schedules/explain` features. `ScheduleExplanationService` now delegates
  to the existing `SchedulingEngine.check`, eliminating its duplicate native check helper. CRNs are reloaded
  through the authoritative catalogs before feasibility/scoring or an AI call.
- Retain actual descriptions, instructor names and capture dates using `JdbcCourseMetadataCatalog` and the same
  authoritative read-only pool. These display queries use existing qualified Data tables; they do not supply
  scheduling eligibility, override credits, infer availability or bypass readiness filtering. The reader role
  needs SELECT on cse007_snapshot.term, source_asset, course and section in addition to the catalog_api views.
- Move six obsolete draft support types to src/test, preserving their source and isolated repository/configuration
  fixtures: CourseReadCatalog, CatalogDatabaseProperties, CatalogPersistenceConfiguration, CatalogRepository,
  CatalogRequirement and DatabaseCourseCatalog. The configuration is now explicitly @TestConfiguration so it is
  never activated by application component scanning. Packaging tests verify the old datasource/catalog/repository
  are absent. One production datasource and one CourseCatalog/SectionCatalog/solver remain.
- Deduplicate JDBC/PostgreSQL dependencies and datasource property keys. No Data migration/view, engine algorithm,
  score formula, production data, backup stash or teammate session-log entry was changed or deleted.
- Adapt local database/HTTP regressions to the authoritative fixtures and retain the incoming controller/manual/
  elective/engine tests. Add combined-field, terms and validation-before-catalog controller regressions.

## Executed verification

Windows JDK 21, repository Maven wrapper through Git Bash, from backend:

```bash
export TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55439/course_schedule_test_merge'
export TEST_DATABASE_USER='course_test'
export RUN_DB_IT=true
export DB_URL='jdbc:postgresql://127.0.0.1:55439/course_schedule_test_be6b7bd5db594d639ee0b125776deeeb'
export DB_USERNAME='course_test'
./mvnw -B -Pdatabase-it clean verify
./mvnw -B -Dtest=SourceCatalogVerification test
```

The first command reported:

```
Tests run: 715, Failures: 0, Errors: 0, Skipped: 0
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The separate retained original-source verification reported:

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**763 distinct tests passed, zero failures/errors/skips.** [Per-suite results](verification/merge-resolution-tests.json).
The 45 profile cases include real HTTP Course (13), explanation (13), generation (16), node-cap (1), original source
(1), and executable-JAR packaging (1). Ten existing repository integration cases also ran in the regular suite.
The separate three source cases exercise unchanged checked-in Data SQL through HTTP and the actual solver.
No catalog, database or solver is mocked in these primary end-to-end cases. Only external AI is a local provider fake;
no paid Claude call was made.

The first regression attempt found three context errors in the older unavailable-Catalog test: its assumed
provider-free application mode is incompatible with pre-main's required AI CourseCatalog injection. The test now
uses the production configuration with an unreachable database, verifying safe 503 and no synthetic fallback.
All three cases passed in the complete rerun; no tests were disabled to obtain a successful build.

Whitespace and conflict-marker checks are clean. Both branches' complete HANDOFF session histories are retained.
The isolated test databases are retained for inspection; no production database was accessed. Earlier verification
reports remain historical checkpoints and do not describe this resolved merge's architecture.

## Completion state

All conflicted paths are resolved and staged; the Git merge still needs its final commit.
No commit, push or modification of the shared pre-main branch is performed by this resolution task.
Production deployment, live seat data, frontend and paid AI quality remain outside this merge verification.
