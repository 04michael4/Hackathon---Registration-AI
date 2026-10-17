# CSE 007 database from screenshots and SuY corrections

**This is the active CSE 007 dataset.** It replaces the earlier synthetic fixture
for project use. Sources: all seven PNG files in `Images for courses/`, plus the
user's corrections in `Hackathon Ideas - SuY.pdf`, read on 2026-10-16 UTC
(2026-10-15 EDT). The PDF resolves the earlier screenshot review items.

- [fixture.json](fixture.json): readable, portable transcription and expected scenarios.
- [database.sql](database.sql): complete PostgreSQL schema, data and checks in one import.
- [example.sql](example.sql): queries for sections, confirmed pairs, snapshot counts and issues.
- [verify.sql](verify.sql): repeatable dataset/constraint checks.

## What the screenshots show

Course: **CSE-007, Introduction to Programming**. Term: **2027 Spring Semester**,
Banner term code **202710**. Primary instructor: **Chen, Brian**.

| Section | CRN | Type | Section-list Hours | Weekly meetings |
|---|---|---|---:|---|
| 010 | 12275 | Lecture | 4 | Monday 09:20–10:35 |
| 060 | 13132 | Lab | 0 | Wednesday/Friday 09:20–10:35 |
| 062 | 12638 | Lab | 0 | Wednesday/Friday 10:45–12:00 |

Times use `America/New_York`; weekday numbers are Monday=1 through Sunday=7.
Each meeting day is a separate row, for **5 meeting rows** in total.
Lab 060 is at **Asa Packer Campus, 019 Packard Lab, Room 112**. SuY supplies lab
062 at **Asa Packer Campus, 019 Packard Lab, Room 11**; room `11` is preserved
exactly as supplied. Both labs run **2027-01-25 through 2027-05-07**.
Lecture location displays **TBD**, with campus/building/room columns still NULL.
For the demo, the lecture uses that same term range under `DEMO_TERM_DEFAULT`;
this is explicitly distinguished from an observed lecture-specific date range.

**Credits resolved by the user:** section-list Hours controls component credits.
The lecture contributes **4**, each lab **0**, and a valid pair totals **4 credits**.
The detail panel's 4 refers to the course total. Both original readings remain in
`data_issue` with status `RESOLVED`; sections are marked `USER_CONFIRMED`.

**Required lab group:** select lecture **010** plus **exactly one** of **060 or 062**.
Both pairs are stored in `section_link`; `section_component_group` stores
`required_count = 1`. Lecture alone, lab alone and lecture with both labs are invalid.
SuY contains an earlier sentence naming only 060, but its final bold statement
explicitly permits either 060 or 062. The final clarification is the implemented
rule and is recorded in the source notes and issue resolution.

All three sections are **IN_PERSON**, following SuY's explanation of FLEX - Classroom.
The raw attribute remains stored. ONLINE/HYBRID remain supported for future data.
The course description now contains the supplied Java/OOP description and the
credit-exclusion note for CSE 003/004. The academic-year label remains NULL.

`course_requirement` contains three rows: `PREREQUISITE`, `COREQUISITE` and
`ENROLLMENT_RESTRICTION`. Their expressions are **NULL** with status
`USER_CONFIRMED_NONE`, as requested for CSE 007. Future courses can store JSONB
expression trees, for example `{"op":"COURSE","code":"CSE-007"}` or AND/OR
trees, with `RULE_DEFINED` status. NULL with `UNKNOWN` means unassessed, not none.
Application-level expression validation and evaluation remain future work.

## Provenance and seat counts

Every section and meeting references a `source_asset` containing the screenshot's
repository path, SHA-256 hash and transcription-check timestamp. Overrides also
reference the SuY PDF as `USER_CLARIFICATION`; it has its own hash and check time.
`source_url` and
`captured_at` are NULL because neither is visible in the provided images. Filename
timestamps are not treated as verified capture times. `verified_at` is the time
the transcription was checked, not a live Banner lookup.

`source_status = HISTORICAL` preserves the static origin of the offering data;
clarification fields identify the user's corrections. The term is Spring 2027.
The database has no live Banner integration.

The fully readable enrollment panel belongs only to lab 060: **0 enrolled,
maximum 38, available 38; waitlist capacity/actual/available all 0**. These values
are stored in `enrollment_snapshot` with `is_live = false`. Lecture 010 and lab 062
retain only their truncated search-status strings (`70 of 70 seats...` and
`37 of 37 seats...`); those strings are not converted into live seat counts.
Precise/current seat availability is explicitly out of demo scope per SuY.
It is not a scheduling constraint in these examples.

The original eight `data_issue` rows are retained for history, now **six RESOLVED
and two ACCEPTED** (lecture location TBD and seat precision out of scope).
There are **zero OPEN review items**. Consumers should filter `status = 'OPEN'`
instead of presenting old issue text as an unresolved warning.

## Database connection

Already loaded on this machine:

| Setting | Value |
|---|---|
| Host | `127.0.0.1` |
| Port | `55432` |
| Database | `registration_demo` |
| User | `registration_demo` |
| Password | None, local demo trust authentication |
| Active schema | **`cse007_snapshot`** |

Keep the local trust-authentication server bound to loopback. The backend is not
connected yet. The old `cse007_demo` and `cse007_pdf_demo` schemas are retained as
archived test fixtures, not the current screenshot data.

From the repository root in PowerShell:

```powershell
$pgBin = 'C:/Program Files/PostgreSQL/17/bin'
& "$pgBin/psql.exe" -h 127.0.0.1 -p 55432 -U registration_demo -d registration_demo -f data/cse007-snapshot/example.sql
& "$pgBin/psql.exe" -h 127.0.0.1 -p 55432 -U registration_demo -d registration_demo -f data/cse007-snapshot/verify.sql
```

If restarting the local server is necessary:

```powershell
& "$pgBin/pg_ctl.exe" -D data/.postgres-demo -l data/.postgres-demo/server.log -o '-h 127.0.0.1 -p 55432' -w start
```

On a different machine, use a PostgreSQL database where `cse007_snapshot` does not
exist. Open `database.sql` in pgAdmin's Query Tool and run it, or use
`psql -v ON_ERROR_STOP=1 -d <database> -f data/cse007-snapshot/database.sql`.
The import is transactional and does not drop/overwrite existing schemas.
The local database is already imported and upgraded, so do not run creation or
upgrade there again. For an earlier screenshot-only installation, run
`psql -v ON_ERROR_STOP=1 -d <database> -f data/cse007-snapshot/upgrade.sql` once.
This applies V3 schema changes and V4 corrected data without replacing IDs.
V1/V2 are retained as the original migration history.

To update the transcription, edit JSON, regenerate SQL and reimport with stable IDs:

```powershell
& 'C:/Program Files/PostgreSQL/17/pgAdmin 4/python/python.exe' data/cse007-snapshot/build_seed.py
& "$pgBin/psql.exe" -h 127.0.0.1 -p 55432 -U registration_demo -d registration_demo -v ON_ERROR_STOP=1 -1 -f data/cse007-snapshot/V4__clarified_seed.sql -f data/cse007-snapshot/verify.sql
```

The generator needs Python 3.9+ and no third-party packages. It checks source
hashes for all eight sources before generating V4 and the full SQL bundle. Upserts preserve IDs; they do not delete
rows that disappear from JSON. Update verification expectations deliberately if
new screenshots change the dataset.

## Checks and expected outcomes

- 3 sections, 5 meetings, 8 attributes, 2 confirmed pairs, 1 required LAB group,
  3 nullable requirement rows, 1 enrollment snapshot and 8 source documents.
- Both 010/060 and 010/062 have no time overlap and total 4 credits.
- Lecture alone, lab alone, or lecture with both labs fails the required-component rule.
- Requiring no classes before 10 yields no complete pair: the only lecture is at 09:20.
- Fridays off yields no complete pair: both labs meet Friday.
- The two labs are 10 minutes apart on Wednesday/Friday; this does not authorize selecting both.
- SQL checks exercise missing references, invalid days/times, source references,
  wrong-term links, rejection of a live-seat flag, nullable-rule consistency and
  required provenance on resolved issues (8 intentional rejection checks).

The schema adds document provenance, attributes, enrollment snapshots, resolved
review history, nullable requirements and explicit component groups. It uses UUIDs internally while keeping
actual CRNs, section numbers and Banner term code as separate strings. No student
records, prerequisite evaluator, general scheduler, live integration or API changes
are included. Ordered SQL files are standalone; no backend migration runner is added.
