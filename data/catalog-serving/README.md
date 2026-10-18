# catalog_serving

The read-only views the backend uses to serve the **researched** Spring 2027 catalog: `DB_CATALOG_SCHEMA=catalog_serving`. Decision record: [ADR-0007](../../docs/decisions/0007-serve-researched-courses-with-their-conditions.md).

It reads `catalog_research` and writes nothing. An offering is served when its tier is `READY` or `CONDITIONAL` (and it has a lecture, a credit value and no evidence mismatch). `BLOCKED` offerings are never served. A served `CONDITIONAL` course comes with its **conditions**: what the application has not verified about taking it. A condition never says a student is eligible.

## Views
| View | What it is |
|---|---|
| `course_offering`, `section_offering`, `section_meeting` | The exact columns and types of `catalog_live`; what the backend queries unchanged |
| `course_condition` | One row per condition: `code`, `message`, `evidence_level`, `outcome` (`UNVERIFIED`), ordered by `seq` |
| `course_rating` | Predicted quality and difficulty per served course (average over its lecture sections) with the lowest confidence; needs `professor_ratings` |
| `section_instructor` | One row per instructor of a served section with their predicted quality/difficulty, confidence and rating count; needs `professor_ratings` |
| `served_offering` | The gate: which offerings are served and at which tier |

## Install (database that already has `catalog_research` and `professor_ratings`)
```
psql -X -v ON_ERROR_STOP=1 -f data/catalog-serving/install.sql                       # as the owner (V1-V3); one transaction, structure check inside; rerunnable
psql -X -v ON_ERROR_STOP=1 -v reader=catalog_reader -f data/catalog-serving/grant_reader.sql
python3 -I data/catalog-serving/verify_serving.py                                    # 18 read-only checks (safe on the team database)
```
`install.sql` re-applies `SELECT` on the views to every role that already has `USAGE` on the schema (`regrant.sql`), because it drops and recreates some views; the structure check fails the install if a role with `USAGE` cannot read a view. The reader needs no access to `catalog_research`: the views read it as their owner. The backend also reads a few `cse007_snapshot` columns for term names and descriptions, which `data/catalog-promotion/grant_reader.sql` grants.

## Run the backend on it
```
DB_URL=jdbc:postgresql://<host>:55437/registration_unified_spring2027 DB_USERNAME=catalog_reader DB_PASSWORD=<password> \
  DB_CATALOG_SCHEMA=catalog_serving ./mvnw spring-boot:run          # from backend/
curl "http://localhost:8080/api/courses?term=2027-SPRING"             # 40 courses
curl "http://localhost:8080/api/courses/CSE-216?term=2027-SPRING"     # sections plus conditions
```

## Conditions
| `code` | Source | Example message |
|---|---|---|
| `PREREQUISITE` | stored expression, or "in words only" for text Banner did not structure (CSE 303) | Prerequisite: CSE 004 or CSE 007 |
| `CLASS_STANDING`, `LEVEL`, `COLLEGE`, `MAJOR`, `DEPARTMENT_APPROVAL` | Banner enrollment restrictions | Not open to these class standings: first-year |
| `CREDIT_EXCLUSION` | catalog text (the CSE 127/327 conflict is left out) | No credit for both this course and CSE 413. |
| `ARRANGED_MEETING` | a mandatory meeting without a published time (CSE 367, 467) | Includes a required meeting at a time to be arranged |
| `LINKED_COMPONENTS` | whether a lab or recitation is required is unverified, or was reported by the team | Whether this course also requires a lab or recitation has not been verified. |
| `CROSS_LISTING` | asserted by the team, not confirmed | Reported as cross-listed with another subject |
| `SECTION_TYPE` | a non-lecture Banner section type allowed by team policy (CSE 217) | Banner lists this section as "Independent Study" |

## Inspected non-CSE courses (V3)
`served_offering` also admits a grid-staged (non-CSE) course once its Class Details were imported (`catalog_research` V4, [README](../catalog-research/README.md#class-details-for-the-non-cse-courses-v4)), and only when every lab or recitation is linked to a lecture (the backend refuses a component without its lecture) and its credits are known. `course_condition` now also renders corequisites, shows an enrollment restriction Banner states only in words, and flags any requirement still `UNKNOWN`, so nothing is dropped silently. The first real import (2026-10-18) serves 26 non-CSE courses; CHM 030 and PHY 012 (components that cannot be linked to one lecture) and MATH 312 and 334 (credits vary by section) are not served.

## Predicted ratings and instructors (V2)
`course_rating` and `section_instructor` read `professor_ratings` (predictions for Spring 2027, [ADR-0006](../../docs/decisions/0006-store-professor-ratings-separately-from-the-catalog.md)). The backend returns them as `predictedRating` on course search and detail and `instructorRatings` on each section. If the views cannot be read the backend returns no ratings and the course endpoints still work. Instructor names are real people's names, so they are for the team's own app only.

## Tests
Backend (default, no database): `JdbcCatalogDaoSqlTest`, `CatalogDatabaseConfigurationTest`, `JdbcConditionCatalogTest`, `CourseConditionsControllerTest`, `JdbcRatingCatalogTest`, `CourseRatingsControllerTest`. Opt-in against a real database: `ServingCatalogIntegrationTest` (`RUN_SERVING_IT=true`, see the class comment). Data: `verify_serving.py` and the structure check inside `install.sql`.

Results on 2026-10-17: `./mvnw test` 793 run, 0 failures, 37 skipped; `ServingCatalogIntegrationTest` 7 run, 0 failures; `verify_serving.py` 18/18.

## Known limits
- Descriptions still come from the CSE 007 snapshot, so served courses other than CSE 007 have none yet. Instructors now come from `section_instructor`.
- Prerequisites and restrictions are shown, not enforced; schedule endpoints do not return conditions yet.
- CSE 303's prerequisite is still "words only" (the grouping decision of 2026-10-17 is not yet applied to `catalog_research`).
