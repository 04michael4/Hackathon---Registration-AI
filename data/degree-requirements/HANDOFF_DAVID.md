# Non-CSE staging data: integration handoff (2026-10-17)

Isolated SQLite staging only. Nothing here touched PostgreSQL, `cse007_snapshot`, `catalog_api`, or `banner_evidence`.

## Files (this folder)
- `programs_2026_27.json`: five programs as AND/OR/choose-N/credit groups (public catalog 2026-27).
- `build_staging_db.py` -> `staging.db` (git-ignored, rebuildable). `export_import_ready.py` -> `export/non_cse_staging_202710.json` (catalog-only export: programs, courses, prerequisite statuses, unresolved items; `sections: []`).
- `fixtures/banner_synthetic/`: synthetic section/meeting/link/instructor files (fake CRNs 99xxx) that exercise the section pipeline in tests.
- `banner/README.md`: where real, local-only Banner data goes and its exact CSV formats (nothing else in `banner/` is committed).
- Tests: `python3 -m unittest test_staging_db test_export` (from this folder).

## Integrating with the CSE database
1. Map `course.code` ("MATH 021") to the existing course identity; keep program requirements separate from semester offerings.
2. When real section data is available locally and cleared for use, `section` + `meeting` map onto David's section/meeting tables. Common Hour Exams are extra meetings with a single date (not weekly): import them as dated meetings or skip them for weekly scheduling.
3. Lecture/recitation/lab relationships: `link_flag=LINKED` only says Banner showed a Linked Sections button. Pairings exist only where `linked_sections.csv` records them; never infer them.
4. Grid credits: labs/recitations showed 0 in the grid while the lecture carried the credits; Class Details credit for these was not inspected, so do not sum or infer. Two sections of one course can show different credits.

## Not collected (kept as NOT_COLLECTED / NONE_SHOWN, never guessed)
Prerequisites/corequisites/descriptions for every course; section restrictions; cross-lists; instructor names and all real section data are withheld from this branch (see below); seat counts and waitlists are never stored. "No Linked Sections / no restriction shown" is not proof of standalone or unrestricted.

## Provenance and redistribution (read before merging)
- **Catalog data** (`programs_2026_27.json`): public Lehigh catalog pages (catalog.lehigh.edu, 2026-27), recorded as source_evidence `public_catalog`.
- **Banner data: withheld.** CRNs, sections, meeting times/rooms, "not offered" results and linked-lab CRNs were read from the Browse Classes results grid in an authenticated student session. Lehigh's terms for redistributing Banner-derived data were not checked and cannot be established from this repo, and `pre-main` requires synthetic data only until that is verified, so none of it is committed. It exists locally (30 offered courses, 191 sections, 14 courses with no sections) and loads with `--banner-dir banner`. The branch owner must confirm redistribution rights before any of it is added.
- **Instructor names: withheld.** Read from the same grid for all 191 sections; kept only in a local file and loaded only with `--instructors`. They concern named individuals and the basis to publish them is not established. The committed export has no names and says so (`instructor_data.status`).
- **No credentials, cookies, session identifiers, student records, rosters, seat counts or waitlist numbers** are in any committed file.
- **Team decisions, not Lehigh confirmations**: (1) Rossin Advanced HSS uses 12 credits / 3 courses as a working value while the CS table says 16 (the conflict is kept in the data); (2) ECO 325 and MKT 325 are treated as cross-listed on a team assertion; the equivalence is `CROSS_LISTED_USER_ASSERTED_UNVERIFIED`.
