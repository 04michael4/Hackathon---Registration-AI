# Database guide

How to build the local PostgreSQL catalog, run the backend against it, and what data it actually contains.

**Scope, stated plainly:** the database holds **one schedule-ready course, CSE 007**. The other CSE courses are *not*
schedule-ready (see [What is and is not schedule-ready](#what-is-and-is-not-schedule-ready)). This is not a 41-course
catalog, and no Banner records are published in this repository.

## Layers

| Layer | Where | What it is |
|---|---|---|
| `cse007_snapshot` | `data/cse007-snapshot/` | The verified CSE 007 schema and seed (3 sections, 5 meetings, one confirmed lecture/lab rule). Loaded unchanged and never modified in place |
| `catalog_api` | `data/catalog-api/` | Five read-only views over the snapshot with six fail-closed readiness rules. The only thing the backend reads |
| `banner_evidence` | `data/banner-evidence/` | An append-only evidence layer (what Banner or a user report showed, claims, human reviews) plus a read-only Banner collector. Not read by the backend; promotes nothing |
| JDBC adapter | `backend/.../course/persistence/` | `JdbcCourseCatalog` and `JdbcSectionCatalog`, selected by `CATALOG_SOURCE` (`database` is the default on `pre-main`) |

## 1. Create the database (one command)

Needs PostgreSQL **15 or newer** and Python 3 (`brew install postgresql@17`, or `apt install postgresql`). From the repository root:

```
data/setup-local-database.sh                 # add --full-validation for the complete check (a few minutes)
```

It creates a private cluster **outside the repository** (`~/.registration-local-pg`, or `--dir`), listening on
`127.0.0.1` only, with random passwords stored only in that directory's `pgpass` file (mode 600; never printed). It then
loads `cse007_snapshot`, installs `catalog_api` and `banner_evidence`, creates a read-only role for the backend, and runs
the verification. It is safe to rerun: it reuses the cluster and does not reload data. It never drops anything.
Port defaults to 55433 (`--port`). Stop it with the `pg_ctl ... stop` line the script prints.

Roles: `registration_admin` (cluster admin), `registration_app` (owns the database, used for loading and tests),
`registration_reader` (`SELECT` on `catalog_api` only; the backend uses this).

## 2. Run the backend in database mode

Configuration is **environment variables only**. The password stays in the `pgpass` file:

```
export PGPASSFILE=~/.registration-local-pg/pgpass
export CATALOG_SOURCE=database
export DB_URL=jdbc:postgresql://127.0.0.1:55433/registration_local
export DB_USERNAME=registration_reader        # leave DB_PASSWORD unset: the driver reads PGPASSFILE
cd backend && ./mvnw spring-boot:run
```

Startup fails with a clear message if `DB_URL`, `DB_USERNAME` or the term map is missing. The pool connects lazily, is
read-only, and a database outage returns a safe `503`. `app.catalog.terms[2027-SPRING]=202710` maps the API term to the
Banner term code (an explicit property, never a formula). **`CATALOG_SOURCE=database` is the default on `pre-main`
and there is no fallback catalog**: the made-up dummy catalogs now live in `src/test` and are used only by the Maven test profile, so the default
build and tests still never need a database.

Try it (expect two schedules, each lecture 12275 plus exactly one of labs 13132 or 12638):

```
curl -s -X POST localhost:8080/api/schedules/generate -H 'Content-Type: application/json' \
  -d '{"term":"2027-SPRING","preferences":{"hardConstraints":{"requiredCourseIds":["CSE-007"],"minCredits":4,"maxCredits":4}}}'
```

## 3. Verify

```
cd backend && ./mvnw -B clean test                      # default build: no database, no network, no API key
RUN_DB_IT=true DB_URL=... DB_USERNAME=registration_reader PGPASSFILE=... \
  ./mvnw -B test -Dtest=DatabaseCatalogIntegrationTest  # opt-in: real PostgreSQL + the real engine, end to end
psql -X -v ON_ERROR_STOP=1 -f data/cse007-snapshot/verify.sql
psql -X -v ON_ERROR_STOP=1 -f data/catalog-api/test_readiness.sql
python3 -I data/catalog-api/check_rules_matter.py                      # each of the six rules must matter
psql -X -v ON_ERROR_STOP=1 -f data/banner-evidence/test_banner_evidence.sql
python3 -I data/banner-evidence/collector/test_banner_collect.py
python3 -I data/banner-evidence/run_validation.py                      # everything, incl. mutation checks
```

(`psql` needs `PGHOST`, `PGPORT`, `PGUSER`, `PGDATABASE` and `PGPASSFILE`; the setup script prints a working set.)

## What is and is not schedule-ready

A course reaches the scheduler only if **every** section passes all six `catalog_api` rules (credits known and whole; each
lab has exactly one confirmed lecture; a lecture is never a component; a confirmed "exactly one lab" rule; no open warning;
a lecture's link status is known). Today **only CSE 007** passes. `CourseCatalog` lists only schedulable courses, so any
other course is "unknown in this term" (422). The branch does **not** make any other course schedulable:

- **CSE 017, 109, 202, 216, 241** (lecture + lab): the lecture/lab links were reported by a user and also read from Banner
  in a local pilot, but credits for the labs, the required lab count and a reviewed link status are unresolved, and the
  courses are not in `cse007_snapshot`.
- **Lecture-only courses (for example CSE 003):** the schema has no "verified standalone" link status, so they stay out.
- **All other CSE sections** (about 52 on the department page): never imported; only a provisional, untracked extraction exists.

## Banner data

No Banner records are published (their sharing is not clearly permitted; terms of use were not reviewed). The collector and
synthetic fixtures are. To continue, read [data/banner-evidence/README.md](../data/banner-evidence/README.md): collect only
with your own authorized session, read-only, at least 5 seconds between loads, select "2027 Spring Semester (View Only)"
explicitly, dry-run first (`banner_collect.py --dry-run --twice`), and never commit anything under `captures/`.
Evidence is not approval: a person must record a review before anything can be proposed for the catalog.

## Known limitations

- Credit semantics are undecided: for labs Banner shows "Hours" 0 in the search grid but "Credit Hours" equal to the course
  credit in Class Details. `catalog_api` assumes 4 / 0 / 0 for CSE 007 (confirmed by the team) and nothing else.
- Required lab count and standalone status are never inferred; Banner states neither.
- Lab 062's room: Banner shows Room 112 (and the department page "PA 112"); the CSE 007 seed has "Room 11". The seed is left unchanged.
- `section_offering.status` is always `UNKNOWN`: there is no live seat data.
- Course discovery vs readiness: an offered course that is not yet certified is invisible to `CourseCatalog`, so the AI
  reports it as unresolved. The two-step fix is in `data/catalog-api/README.md`; it needs a backward-compatible
  `CourseCatalog.isSchedulable` default method and the Backend API owner's approval.
- The Banner collector assumes 10 grid rows per page and parseable meeting text; it rejects anything else.
