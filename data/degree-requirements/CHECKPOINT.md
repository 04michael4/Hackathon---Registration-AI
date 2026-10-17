# Degree requirements + non-CSE course research: checkpoint

Updated 2026-10-17. Rebuild DB: `python3 build_staging_db.py`. Tests: `python3 -m unittest test_staging_db test_export` (from this folder).

## Programs (structured in `programs_2026_27.json`, public catalog 2026-27, re-read from the pages on 2026-10-17)
Rossin BS CS, CAS BS CS, CAS BA CS, Minor CS, Minor DS. Corrections to the first draft of this file:
- Rossin Advanced HSS: the college page says min 3 courses/12 credits; the CS table says 16. Both are Lehigh catalog text and they still disagree. 12 credits / 3 courses is a team working value chosen 2026-10-17 (status `USER_CHOSEN_CONFLICT_UNRESOLVED_WITH_SOURCE`), not confirmed by Lehigh or an advisor; the conflict is also listed on the program and in `unresolved`.
- MATH 241 is an accepted linear-algebra option for both BS programs, not the BA (BA allows MATH 043/205/242).
- Rossin BS needs ENGR 005 + ENGR 010 + CHM 030 + PHY 011/012; CAS programs do not.
- ECO 325 / MKT 325 are treated as cross-listed on a team assertion only (status `CROSS_LISTED_USER_ASSERTED_UNVERIFIED`); the Banner Cross Listed Courses panel was never opened.
Still unknown: min grade/GPA for the CS programs, the 3-credit "natural science with a natural-science prerequisite" list, A&S distribution course lists.

## Banner Spring 2027 (term 202710): collected locally, NOT in this branch
All 44 non-CSE course codes on the list were searched in Banner on 2026-10-17 (30 had Spring 2027 sections: 191 sections; 14 returned 0 classes), grid level only; detail panels were opened for one lecture. Instructor names were also read from the grid for every section. None of this is committed:
- Real section/meeting/offering data came from an authenticated Banner session and Lehigh's redistribution terms are unverified; `pre-main` says to use synthetic data only until that is checked. See `banner/README.md` for the local file formats and `HANDOFF_DAVID.md` ("Provenance and redistribution").
- The committed export therefore has `sections: []`, `banner_data.status = NOT_INCLUDED_REDISTRIBUTION_NOT_VERIFIED` and `instructor_data.status = NOT_INCLUDED_REDISTRIBUTION_NOT_VERIFIED`. The pipeline is tested on synthetic fixtures (`fixtures/banner_synthetic/`, fake CRNs 99xxx).
- Seat counts and waitlist numbers are never stored.
- "Not offered" means 0 classes for that subject + number only; a cross-listing under another subject was not checked.

## Not collected
Course-level prerequisites/corequisites/descriptions (every course is `NOT_COLLECTED`; a bulk catalog fetch was denied by the tool classifier, one-page-at-a-time reading is possible). Section restrictions, cross-lists and linked-lab lists beyond what a local data set records. "None shown" in Banner never means "no requirement".

## Tool limits seen
Auto-mode classifier denied: bulk in-page fetches, a local helper server, multi-action browser batches on Banner, and an early CSV save containing instructor names. Single clicks/typing on Banner worked.

Offline: `build_staging_db.py`, `export_import_ready.py`, `test_staging_db.py`, `test_export.py` (39 tests), `HANDOFF_DAVID.md`.
