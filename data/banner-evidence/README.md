# banner_evidence: an append-only evidence layer for Banner observations

A PostgreSQL schema, `banner_evidence`, for recording **what a source showed** (Lehigh Banner pages, a user's
screenshots, the department page), **what we claim from it**, and **what a person decided**. It is separate from the
catalog: `catalog_api` and `cse007_snapshot` never read it, it never reads them, and nothing in it makes a course
schedulable. Promotion into the catalog is a later, reviewed step.

Status: **implemented and verified.** Schema v1 and v2, a read-only Banner collector (browser extractors plus a
sanitizing importer), and the seven user-reported lecture/lab links. **No Banner records are published in this repository**
(see [Banner data is not included](#banner-data-is-not-included)). Nothing is promoted into the catalog, and the dataset is
**not** a 41-course catalog: only CSE 007 is schedule-ready.

## Files

| File | Purpose |
|---|---|
| `V1__banner_evidence_schema.sql` | The schema. Rerunnable (`IF NOT EXISTS` / `OR REPLACE`) |
| `V2__collector_fields.sql` | Additive, rerunnable: section title; meeting dates, type, building and room; `section_field_observation`; note kind `NO_COUNT_STATEMENT`; the `credit_display_comparison`, `link_reconciliation` and `meeting_reconciliation` views |
| `verify_structure.sql` | Read-only structural assertions (exact objects, append-only triggers, no writable views, isolation from the catalog schemas) |
| `install.sql` | V1, V2 and the structure checks in one transaction; commits only if every assertion passes |
| `seed_user_reported_links.sql` | The seven user-reported links (transaction-free body, idempotent) |
| `import_user_reported_links.sql` | Seed + data verification in one transaction |
| `verify_banner_evidence.sql` | Read-only assertions that the seven user-reported links and the deliberate UNKNOWNs are intact (filtered to the user-report sources, so later evidence does not break it) |
| `collector/extract_grid.js`, `collector/extract_popup.js` | Browser-side extractors (see Collector) |
| `collector/banner_collect.py` | Validates, sanitizes and imports captures; `--dry-run` always rolls back |
| `collector/test_banner_collect.py`, `collector/fixtures/` | 31 unit tests on synthetic fixtures (fake CRNs 99001-99004), including a deliberate conflict scenario |
| `probes_banner_evidence.sql`, `test_banner_evidence.sql` | 78 behavior probes, always rolled back |
| `run_validation.py` | Runs everything: install/reinstall, import twice, the probes, mutation checks (17 schema guards and 10 importer checks are weakened one at a time; each must make a test fail), the collector scenarios, and the catalog_api and cse007_snapshot checks |
| `captures/` | Normalized, sanitized extracts, one per source, named by content hash (contents are git-ignored) |

```
psql -X -v ON_ERROR_STOP=1 -f data/banner-evidence/install.sql
psql -X -v ON_ERROR_STOP=1 -f data/banner-evidence/import_user_reported_links.sql
psql -X -v ON_ERROR_STOP=1 -f data/banner-evidence/test_banner_evidence.sql
python3 -I data/banner-evidence/collector/test_banner_collect.py
python3 -I data/banner-evidence/run_validation.py        # needs PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSFILE; installs and imports
```

Remove (touches no other schema): `DROP SCHEMA banner_evidence CASCADE;`

## Model

| Table | Holds |
|---|---|
| `evidence_source` | One row per captured page or report: kind, term, date, who captured it, host and **path only**, sanitized extract path, SHA-256, provenance and sanitization notes |
| `section_observation`, `meeting_observation`, `link_observation`, `note_observation` | What a source showed, verbatim ("None" stays "None"; Banner's "Option 1" is stored as the link label). No seats, enrollment, instructors or student data |
| `section_field_observation` | A displayed value under its **original label and view**: the search grid's "Hours" and Class Details' "Credit Hours" are stored side by side and never merged. (`section_observation.credit_hours_text` is a legacy column the collector does not use) |
| `claim_type_policy` | Per claim type, whether a live Banner capture alone can verify it |
| `claim` | An assertion about a subject. Several values for one subject coexist. A NULL value is an explicit UNKNOWN |
| `claim_evidence` | Links a claim to a source as SUPPORTS or CONTRADICTS. A trigger requires the source to actually show what the claim is about |
| `claim_review` | A person's decision (CONFIRMED, REJECTED, NEEDS_MORE_EVIDENCE) with a reason. Automatic collection never writes here |

Views: `claim_status` (derived status and flags), `review_queue` (conflicts first), `course_evidence_summary` (counts per
course; deliberately has no schedule-ready column), `credit_display_comparison` (both credit displays per section),
`link_reconciliation` (reported links against Banner: `CONFIRMED_BY_BANNER`, `DISCREPANCY_BANNER_DOES_NOT_LIST`,
`BANNER_ONLY`, `NOT_YET_CHECKED_IN_BANNER`) and `meeting_reconciliation` (reported lab times against Banner).

### Derived status (never stored)

| Status | Meaning |
|---|---|
| `VERIFIED_BANNER` | A live Banner capture supports it, for a claim type Banner can state (links) |
| `TEAM_CONFIRMED` | A team confirmation or the user's own clarification supports it |
| `USER_REPORTED` | Only a user's screenshot report supports it |
| `PROVISIONAL` | Only a department page, or Banner for a type Banner cannot settle (required lab count, credits, standalone), supports it |
| `CONTRADICTED` | Some evidence contradicts it |
| `UNKNOWN` | NULL value, or no supporting evidence |

`review_flag` marks `CONFLICT` (another live claim with a different value, or contradicting evidence), `AWAITING_REVIEW`,
`NEEDS_MORE_EVIDENCE`, `UNKNOWN_FACT`, `REJECTED`, `SUPERSEDED` (an UNKNOWN placeholder replaced by a value) or `REVIEWED`.
`promotion_eligible` is true only with Banner or team evidence **and** a person's CONFIRMED review **and** no conflict. It
means "may be proposed", not "schedule-ready"; the six `catalog_api` readiness rules still decide that.

### Safety built into the schema

- **Append-only:** `UPDATE`, `DELETE` and `TRUNCATE` on evidence, observation, claim and review tables raise an error. A newer or conflicting observation is a new row.
- **Sanitized:** free text must pass `is_safe_text` (no HTML, cookies, authorization headers, session ids, tokens, 8+ digit runs such as student ids, e-mail addresses, or more than 1000 characters). Source hosts must be `*.lehigh.edu`; source paths carry no query string, fragment, `;` parameter or credential-like word. This is a tripwire, not proof of anonymity: sanitize before inserting.
- **Live captures** must be attributed to `CLAUDE_BROWSER`, be inspected, and have host, path, timestamp and an extract path.
- **No stored status and no readiness column:** status is derived; the structure check fails if a `schedulable` or `ready` column appears.

## Collector

Read-only collection from Banner's **Browse Classes** page, driven through the browser with the user's own session. It
never calls Banner's endpoints itself, never opens Add/Register/Drop/Submit/Save/Plan, and never reads cookies, tokens or
storage.

1. **Select the term explicitly** ("2027 Spring Semester (View Only)" through Select a Term, then Continue). A default
   term shown on the page returned 0 classes in testing; the importer rejects 0-class and wrong-term captures.
2. **Search** subject CSE and one course number through the normal form. At least 5 seconds between loads.
3. **`extract_grid.js`** reads the results grid through a whitelist of cells (CRN, subject, number, section, title, "Hours",
   schedule type, meeting text, and the "... - Linked" label inside the attribute cell). It never reads the instructor or
   status cells, where names and seat counts live.
4. **`extract_popup.js <crn>`** opens one section's Class Details popup and reads five allowlisted tabs (Class Details,
   Restrictions, Prerequisites, Corequisites, Linked Sections), 3 seconds apart, then closes it. It never opens
   Instructor/Meeting Times or Enrollment/Waitlist. Both scripts refuse to return anything if a tripwire matches (seat text,
   an instructor marker, an e-mail address, a run of 8+ digits).
5. The page keeps the sanitized JSON in memory with its SHA-256; it is read out in 900-character chunks (the browser tool
   truncates long output) and saved as a file. `banner_collect.py CAPTURE=SHA256` refuses a file that does not hash to what
   the page produced.
6. **`banner_collect.py`** re-validates every field (allowlisted keys only, formats, tripwires, term, counts, consistency
   between the grid and each popup), scans its own generated text, and splits a course into one source per Banner view:
   `SEARCH_RESULTS`, `SECTION_DETAILS` (per CRN) and `LINKED_SECTIONS` (per CRN, from the lecture and from each lab).
   Each source stores host and path only, a content hash of the normalized extract (no timestamp, so identical content is
   one row), the capture time, and a provenance note naming the view and CRN. All inserts are `ON CONFLICT DO NOTHING` in
   one transaction. It never writes a `claim_review`.

Claims from a capture: each listed lecture/lab pair gets `SUPPORTS` evidence on its `LINKED` claim (created if new). A
lecture view that does not list a lab with an existing `LINKED` claim adds `CONTRADICTS` evidence; both sides stay and the
claim shows `CONTRADICTED` / `CONFLICT`. Required lab count, lecture credits, lab credits and standalone status are created
as explicit UNKNOWN placeholders and never given a value by the collector. A Linked Sections view that states no number is
recorded as `NO_COUNT_STATEMENT`; "Option 1 / Option 2" is kept only as the link label. A lecture with no links is recorded
as `NONE_SHOWN`, which is not "standalone".

## Banner data is not included

A six-course read-only pilot (Spring 2027: CSE 017, 109, 202, 216, 241 and 003) was collected through an authenticated
browser session on 2026-10-16 and imported into the author's local database. **Those records, the raw captures and the
authenticated session are not published here.** Whether sanitized Banner records may be shared is not settled (Lehigh's terms
of use for Banner and the department site were not reviewed), so this branch ships the schema, the collector and
**synthetic** test fixtures (fake CRNs 99001-99004) only. Anyone who continues must collect or import evidence they are
authorized to use, with their own session, and must not commit anything under `captures/` (it is git-ignored).

What the pilot showed (findings, not data):

- Linked Sections confirmed every one of the seven reported lecture-to-lab links, and the reported lab meeting times matched.
  Banner numbers a lecture's linked labs "Option 1", "Option 2" in an arbitrary order and **states no required lab count**.
- Banner shows two different credit values for the same lab: the search grid's "Hours" (0) and Class Details' "Credit Hours"
  (the course credit). Both are stored under their original labels; which one is the academic credit is **undecided**.
- A lecture-only course (CSE 003) shows "No linked course information available."; that is recorded as `NONE_SHOWN`, not as
  "standalone".
- Searching with the term merely displayed by default returned 0 classes; selecting "2027 Spring Semester (View Only)"
  explicitly through Select a Term fixed it. The importer rejects 0-class and wrong-term captures.
- The collector assumes 10 grid rows per page (it rejects a partial page) and parseable meeting text; online or TBA meetings,
  larger courses and recitation chains need work before a full run.

## The seven user-reported links (term 202710)

Reported by Michael from Banner's Linked Sections pages on 2026-10-16 and shared as screenshots with ChatGPT. Claude has
not inspected those screenshots; the reports stay as `USER_SCREENSHOT` evidence, so each link's derived status is
`USER_REPORTED` (unreviewed, not promotable). On a database where live Banner captures were also imported, a link they
support shows `VERIFIED_BANNER` instead; either way nothing is promotable without a person's review. There is no screenshot file;
each source's hash covers the transcribed report. Banner's labels are kept ("Lab - Linked").

| Course | Lecture | Labs (reported meeting time) |
|---|---|---|
| CSE 017 | 13507 | 14855 (Thu 10:45-12:00), 14856 (Thu 13:35-14:50) |
| CSE 109 | 14857 | 13136 (Fri 09:20-10:35), 13137 (Fri 10:45-12:00) |
| CSE 202 | 11041 | 14861 (Wed 12:10-13:25) |
| CSE 216 | 13514 | 13186 (Fri 13:35-16:15) |
| CSE 241 | 13553 | 13554 (Fri 13:35-14:50) |

Recorded as explicit UNKNOWN (no value, no evidence): the required lab count (5), lecture credits (5) and lab credits (7).
The reported "Hours: 0" is kept as a note on each source; it is **not** treated as academic credit until its meaning is
confirmed. No standalone claim is recorded for these five lecture-plus-lab courses.

Totals after the import: 5 sources, 12 section observations, 7 meeting observations, 7 link observations, 5 notes,
24 claims (7 links + 17 UNKNOWN), 7 claim-evidence rows, 0 reviews.

## Open decisions

- Which credit display is the academic credit for a lab: the grid's "Hours" (0) or Class Details' "Credit Hours" (the course credit). Until decided, credit claims stay UNKNOWN and cap at `PROVISIONAL`.
- Whether sanitized Banner records may be shared with the team (terms of use not reviewed).
- Banner states no required lab count, so `REQUIRED_LAB_COUNT` stays UNKNOWN until a person decides it with a stated reason.
- Where promoted data goes: a new schema built from the `cse007_snapshot` DDL with `catalog_api` extended by a V2 migration, or the existing tables. Not decided; nothing is promoted yet.
- A verified-standalone link status in `cse007_snapshot` and a `data_issue.course_id` (carried from `data/catalog-api/README.md`).
