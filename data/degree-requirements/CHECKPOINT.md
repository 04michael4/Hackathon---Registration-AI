# Degree requirements + non-CSE course research: checkpoint

Updated 2026-10-17. Machine-readable progress: `banner/progress_manifest.json`. Rebuild DB: `python3 build_staging_db.py`. Tests: `python3 -m unittest test_staging_db`.

## Programs (structured in `programs_2026_27.json`, catalog 2026-27, public catalog, re-read from the pages on 2026-10-17)
Rossin BS CS, CAS BS CS, CAS BA CS, Minor CS, Minor DS. Corrections to the first draft of this file:
- Rossin Advanced HSS: college page says min 3 courses/12 credits; the CS table says 16. Both kept (status CONFLICT_PRESERVED).
- MATH 241 is an accepted linear-algebra option for both BS programs, not the BA (BA allows MATH 043/205/242).
- Rossin BS needs ENGR 005 + ENGR 010 + CHM 030 + PHY 011/012; CAS programs do not.
Still unknown: min grade/GPA for the CS programs, the 3-credit "natural science with a natural-science prerequisite" list, A&S distribution course lists.

## Banner Spring 2027 (term 202710), grid-level only, files in `banner/`
Collected from the Browse Classes results grid via the connected Banner tab (read-only). Detail panels were opened only for CHM 030 lecture CRN 12305 (see `section_details.md`). Instructors were re-read from the grid on 2026-10-17 on the user's instruction and are stored in `banner/instructors.csv` (225 rows, all 191 sections have a Primary; 'Staff, Teaching' is Banner's placeholder, flagged `is_placeholder`; extra lab/recitation instructors have role Additional). Seat counts and waitlists are never stored.
Done (sections): CHM 030 (14), ENGR 010 (12), MATH 021 (10), MATH 022 (20), MATH 205 (7), MATH 231 (3), PHY 011 (8), PHY 012 (8), WRT 001 (8), WRT 002 (39), ECO 001 (26), ECO 325 (2), MATH 043 (1), MATH 312 (2), MATH 334 (2), MATH 051 (3), MATH 052 (2), MATH 062 (1), MATH 076 (2), MATH 081 (7), ISE 111 (1), ISE 121 (1), ISE 224 (1), ISE 365 (1), BUAN 348 (1), BUAN 352 (1), MKT 325 (2), MKT 326 (1), BIS 324 (1), WRT 005 (4) = 191 sections, 30 courses.
Not offered Spring 2027 (0 results, 14): ENGR 005, MATH 241, MATH 242, MATH 309, MATH 031, MATH 032, MATH 082, STAT 342, ECO 357, ECO 360, ECO 367, ISE 309, ISE 364, WRT 003.
Not yet searched: none. All 44 non-CSE codes on the list have been searched (resumed 2026-10-17 after Banner access returned). 'Not offered' means 0 classes for that subject + number only; a title-level cross-list (e.g. STAT 342 under another subject) was not checked.

## Not collected
Course-level prerequisites/corequisites/descriptions (catalog course pages: a bulk in-page fetch was denied by the auto-mode classifier; one-page-at-a-time reading is possible). Section restrictions, cross-lists, linked-lab lists (except 4 options of CHM 030 CRN 12305), instructors.

## Tool limits seen
Auto-mode classifier denied: bulk in-page fetches, a local helper server, multi-action browser batches on Banner, and a first CSV save containing instructor names (PII). Single clicks/typing on Banner work.

Offline: `build_staging_db.py`, `export_import_ready.py`, `test_staging_db.py`, `test_export.py` (22 tests pass), `HANDOFF_DAVID.md`.
