# data

Demo catalog snapshots, section/meeting-time seed files, and sample inputs used for development and tests.

Guidelines:
- Commit only public or synthetic data. No real student records or personal information.
- Prefer plain, diffable formats (CSV or JSON) and note each file's source and retrieval date in this README.

## CSE 007 PostgreSQL demo

**Current version, based on the user's course screenshots:** use
[cse007-snapshot/README.md](cse007-snapshot/README.md) and
[cse007-snapshot/fixture.json](cse007-snapshot/fixture.json).
The active schema is `cse007_snapshot`: Spring 2027, CRNs 12275/13132/12638,
with traceable image sources and corrections from `Hackathon Ideas - SuY.pdf`.
Both labs are allowed alternatives, each worth 0 credits; six review items are
resolved and two accepted as demo limitations. The older
`cse007_demo` and `cse007_pdf_demo` schemas are archived synthetic fixtures.
The setup below describes the original synthetic dataset; all schemas share
the same local server.

Created and verified locally on 2026-10-15 EDT. Standalone database; the backend is not connected yet.

- Lehigh **CSE 007 — Introduction to Programming**. Source: https://catalog.lehigh.edu/courselisting/cse/, checked 2026-10-16 UTC.
- Catalog lists `0,4 Credits`; this demo uses **4 total credits**, counted once per lecture/lab pair. Description is a paraphrase.
- Term `DEMO-2027-SPRING`, five sections, nine meetings and lab coupling are invented. Every section and meeting is `DEMO_SIMULATED`. Instructors are NULL; locations and IDs are fictional.
- Course `verified_at` refers only to catalog metadata. Section timestamps remain NULL. No seats, enrollment status, prerequisite eligibility or student records are represented.

### Connect on this machine

Host `127.0.0.1`, port `55432`, database/user `registration_demo`, schema `cse007_demo`.
No password is required for this local demo cluster (trust authentication).
Keep this local; do not expose it to other machines or deploy with trust authentication.
It is separate from any existing PostgreSQL server on port 5432.

From the repository root in PowerShell:

```powershell
$pgBin = 'C:/Program Files/PostgreSQL/17/bin'
# Start after a restart; skip if already running:
& "$pgBin/pg_ctl.exe" -D data/.postgres-demo -l data/.postgres-demo/server.log -o '-h 127.0.0.1 -p 55432' -w start
# Inspect data and feasible pairs:
& "$pgBin/psql.exe" -h 127.0.0.1 -p 55432 -U registration_demo -d registration_demo -f data/cse007/example.sql
# Verify:
& "$pgBin/psql.exe" -h 127.0.0.1 -p 55432 -U registration_demo -d registration_demo -f data/cse007/verify.sql
# Stop only this server:
& "$pgBin/pg_ctl.exe" -D data/.postgres-demo -w stop
```

Cluster files live in ignored `data/.postgres-demo/`. SQL is the portable source of truth.
On another machine, create an empty PostgreSQL database and run
`psql -v ON_ERROR_STOP=1 -d <database> -f data/cse007/create.sql`.
Run `create.sql` once per empty database; it fails on existing tables.
`V1`/`V2` are ordered schema/seed scripts, not a backend Flyway integration.

### Expected outcomes

Days: ISO Monday=1 through Sunday=7. Times: America/New_York.
Choose one lecture and exactly one linked lab; count credits once.
Links express allowed components; the scheduler must independently check time conflicts.

| Constraint | Expected pairs (demo section suffixes) |
|---|---|
| No blocked times | L01/B01, L01/B02, L02/B02 |
| No classes before 10:00 | L02/B02 |
| Friday blocked 08:00–12:00 | None |
| L02 with B03 | Reject: Wednesday overlap 11:30–11:50 |

Foreign keys enforce existing courses, terms and sections, and same-course/term links.
Checks enforce valid days, positive credits and increasing times. `verify.sql` checks
fixture counts, component types, meetings, provenance and the three scenario counts.
No general scheduler or prerequisite evaluator is implemented.
