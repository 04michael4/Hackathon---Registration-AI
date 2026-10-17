# banner/ : local-only data directory (nothing here is committed except this file)

Real Spring 2027 (term 202710) section data was read from the Lehigh Banner "Browse Classes" results grid in an authenticated
student session. **It is deliberately not in git.** `pre-main` documents a "synthetic data only until permission to redistribute
real section data is verified" rule, and Lehigh's terms for redistributing Banner-derived data have not been checked.
`.gitignore` blocks `banner/*.csv`, `banner/progress_manifest.json` and `banner/section_details.md`.

To work with real data **locally only**, put these files here (headers must match exactly), then run
`python3 build_staging_db.py --banner-dir banner [--instructors banner/instructors.csv]` and
`python3 export_import_ready.py` (it writes `export/local_only_banner_202710.json`, also git-ignored).

| File | Header |
|---|---|
| `sections_grid.csv` | `term,course,section,crn,title,hours_grid,days,start,end,type,building,room,start_date,end_date,instructor,linked_flag,collected,origin,status` |
| `meetings_extra.csv` | `term,crn,course,section,meeting_type,day,start,end,start_date,end_date,building,room,collected` (extra dated meetings such as Common Hour Exams) |
| `no_sections.csv` | `term,course,result,collected,origin` (searched, 0 classes returned) |
| `linked_sections.csv` | `term,lecture_crn,linked_crn,relation,status` (only links actually observed in a Linked Sections panel) |
| `instructors.csv` | `term,crn,course,instructor,role,collected,origin` (role Primary/Additional; "Staff, Teaching" is Banner's placeholder) |

Synthetic examples of every file (fake CRNs 99xxx) are in `../fixtures/banner_synthetic/`; the tests use only those.
Seat counts and waitlist numbers are never stored.
