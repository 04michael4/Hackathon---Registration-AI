# CSE 007 dummy data following the Course Scheduling App PDF

**Archived synthetic fixture.** For the current screenshot-based course data, use
[cse007-snapshot](../cse007-snapshot/README.md) and schema `cse007_snapshot`.

Use **[fixture.json](fixture.json)** as the portable dummy dataset. It follows the
physical table/field names in section 6.4.1 of the supplied six-page
`Course Scheduling App — Proposed Database Structure.pdf`.
This is a one-course demo, as requested, rather than the broader 20–40 course dataset.

## Included data

| Entity | Seed rows | Contents |
|---|---:|---|
| course | 1 | `CSE-007`, Introduction to Programming |
| term | 1 | `DEMO-2027-SPRING`, an invented term offering |
| section | 6 | Two scheduled lectures, three labs, one TBA lecture |
| meeting | 9 | One row per meeting day; Monday=1 through Sunday=7 |
| section_link | 4 | Exactly-one LAB alternatives for each scheduled lecture |
| prerequisite_rule | 0 | Unknown eligibility; missing rule does not mean no prerequisite |
| student_profile | 1 | Synthetic CSB profile; no completed courses or real personal data |
| schedule_preference | 1 | CSE-007, 4 credits, no meetings before 10 AM |
| generated_schedule | 0 | Scores await team agreement, as requested by the PDF |
| generated_schedule_section | 0 | Supports future seeded schedules through foreign keys |

All IDs are stable UUID strings. Course codes and term codes are separate external
keys. JSON table records use snake_case. The preference object uses the PDF's
proposed camelCase keys; it is fixture data, **not an agreed HTTP DTO contract**.
The extra `term_id` on the schedule/section join enforces matching terms through
composite foreign keys. No backend API or save endpoint has been implemented.

## Course facts and simulation boundary

Title and catalog credit text were checked at
https://catalog.lehigh.edu/courselisting/cse/ on 2026-10-16 UTC (2026-10-15 EDT).
The description is a paraphrase. Catalog verification time and the original
`0,4 Credits` label are preserved in `fixture_metadata`.

The demo allocates **4 credits to a lecture and 0 to a lab**. This section-level
allocation, the term offering, times, rooms and coupling are all simulated.
Every section and meeting is explicitly `DEMO_SIMULATED`, with NULL `source_url`
and `verified_at`. There are no invented professor identities, ratings, seat counts
or enrollment status. Local meeting times use `America/New_York`.

| Section | Component | Credits | Meetings | Compatible LAB choices |
|---|---|---:|---|---|
| DEMO-L01 | Lecture | 4 | M/W/F 09:00–09:50 | B01 or B02 |
| DEMO-L02 | Lecture | 4 | M/W/F 11:00–11:50 | B02 or B03 |
| DEMO-B01 | Lab | 0 | Tue 14:00–15:00 | — |
| DEMO-B02 | Lab | 0 | Thu 14:00–15:00 | — |
| DEMO-B03 | Lab | 0 | Wed 11:30–12:30 | — |
| DEMO-L03-TBA | Lecture | 4 | Unknown | Incomplete; excluded |

L02/B03 is intentionally linked but overlaps. Allowed linkage alone does not prove
time feasibility. The TBA row has unknown delivery and no meetings, so it must
produce `INCOMPLETE_MEETING_METADATA` / `UNKNOWN_DELIVERY_TYPE` warnings.

## Expected scenarios

The JSON contains eight examples under `expected_scenarios`, including explicit
UUIDs. Warning/error names describe expected fixture behavior; endpoints are not
implemented. Even time-feasible pairs retain `PREREQUISITES_UNKNOWN`.

| Scenario | Expected outcome |
|---|---|
| All complete pairs | L01/B01, L01/B02, L02/B02; each totals 4 credits |
| No classes before 10 | L02/B02 only |
| Friday blocked 08:00–12:00 | No feasible pair |
| L01 alone | Missing required lab |
| L01 with B01 and B02 | More than one component in the LAB group |
| L02 with B01 | Incompatible component |
| L02 with B03 | Wednesday time conflict |
| L03-TBA | Incomplete; cannot assume conflict-free |

## Use the database

Already loaded locally: host `127.0.0.1`, port `55432`, database/user
`registration_demo`, **schema `cse007_pdf_demo`**. The earlier `cse007_demo` schema
is retained as a legacy fixture. Use this new schema for the PDF structure.
The local demo uses passwordless trust authentication and must stay local.

From the repository root in PowerShell:

```powershell
$pgBin = 'C:/Program Files/PostgreSQL/17/bin'
& "$pgBin/psql.exe" -h 127.0.0.1 -p 55432 -U registration_demo -d registration_demo -f data/cse007-pdf/example.sql
& "$pgBin/psql.exe" -h 127.0.0.1 -p 55432 -U registration_demo -d registration_demo -f data/cse007-pdf/verify.sql
```

For a fresh PostgreSQL database, run `psql -d <database> -f data/cse007-pdf/create.sql`.
It creates and seeds the schema in one transaction and rolls back on failure.
It deliberately fails if the schema already exists. Scripts are ordered SQL
migrations; no Flyway dependency is installed in the backend.

To edit the fixture, modify JSON and regenerate the seed using Python 3:

```powershell
& 'C:/Program Files/PostgreSQL/17/pgAdmin 4/python/python.exe' data/cse007-pdf/build_seed.py
# Apply stable-key upserts and verify atomically:
& "$pgBin/psql.exe" -h 127.0.0.1 -p 55432 -U registration_demo -d registration_demo -v ON_ERROR_STOP=1 -1 -f data/cse007-pdf/V2__seed.sql -f data/cse007-pdf/verify.sql
```

On another machine, use its Python executable for `build_seed.py`. The generator
uses only the standard library. Upserts preserve UUIDs and references and do not
delete rows absent from JSON. If expanding the fixture, update expected outcomes
and verification counts intentionally. Keep approved catalog history out of destructive resets.

`verify.sql` checks references via foreign keys, counts, provenance, component
types, same-course/term linkage, cycles, scheduled-meeting completeness, credit
totals and known time-feasibility outcomes. Nine intentional invalid writes prove
key constraints reject bad data. These checks validate the seeded fixture;
general importer/service validation, prerequisite evaluation and scoring remain
future backend work. No verified no-prerequisite claim is made for CSE-007.
