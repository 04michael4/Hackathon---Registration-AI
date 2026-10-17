# Non-CSE staging data: integration handoff (2026-10-17)

Isolated SQLite staging only. Nothing here touched PostgreSQL, `cse007_snapshot`, `catalog_api`, or `banner_evidence`.

## Files (this folder)
- `programs_2026_27.json`: five programs as AND/OR/choose-N/credit groups (catalog 2026-27, public catalog).
- `banner/sections_grid.csv`, `banner/meetings_extra.csv`, `banner/no_sections.csv`, `banner/section_details.md`, `banner/progress_manifest.json`: Spring 2027 (202710) Banner grid data (authenticated Banner, read-only, collected 2026-10-17).
- `build_staging_db.py` -> `staging.db` (git-ignored, rebuildable). `export_import_ready.py` -> `export/non_cse_staging_202710.json` (import-ready).
- Tests: `python3 -m unittest test_staging_db test_export` (from this folder).

## Integrating with the CSE database
1. Map `course.code` ("MATH 021") to the existing course identity; keep program requirements separate from semester offerings.
2. `section` + `meeting` map onto David's section/meeting tables. Common Hour Exams are extra meetings with a single date (not weekly): import them as dated meetings or skip them for weekly scheduling.
3. Lecture/recitation/lab relationships: `link_flag=LINKED` only says Banner showed a Linked Sections button. Actual pairings are NOT_COLLECTED except 4 options of CHM 030 CRN 12305.
4. Grid credits: labs/recitations show 0 in the grid (CHM 030, ENGR 010, MATH 021/022, PHY 011, ECO 001). Class Details credit for these was NOT inspected, so do not sum or infer. PHY 012 is a separate 1-credit lab course.

## Not collected (kept as NOT_COLLECTED / NONE_SHOWN, never guessed)
Prerequisites/corequisites/descriptions for every course; section restrictions; cross-lists; instructor names are included in the export from the results grid only (`sections[].instructors`, with `role` Primary/Additional and `is_placeholder` for Banner's 'Staff, Teaching'; seat counts are not stored); detail panels for all but one section. "No Linked Sections / no restriction shown" is not proof of standalone or unrestricted.
Courses not yet searched in Banner: see `banner/progress_manifest.json` `next`.
