# professor_ratings

Instructors as Banner lists them, the sections they teach, Rate My Professors (RMP) ratings, and ratings and comments that students submit in **our** app, all per semester so a professor's trend can be graphed and new ratings accumulate, plus **predicted quality and difficulty for a coming term** (Spring 2027 first) per professor, section and course.

It is opinion data kept apart from the catalog. Nothing in `catalog_api`, `catalog_promoted`, `catalog_live` or `catalog_research` reads it, and it never affects a tier, an approval, eligibility or a schedule. The decision record, including the open risks (redistributing Banner names, RMP's terms), is [ADR-0006](../../docs/decisions/0006-store-professor-ratings-separately-from-the-catalog.md).

**Local or team database only.** It holds real instructor names, so the database, its caches and the chart stay private, and no repository file contains a real name. Tests use synthetic names.

## Contents
- [For teammates: open it in DBeaver](#for-teammates-open-it-in-dbeaver)
- [Files](#files)
- [Data model](#data-model)
- [What was done (2026-10-17)](#what-was-done-2026-10-17)
- [Rebuild from the local caches (no network)](#rebuild-from-the-local-caches-no-network)
- [Refresh from RMP](#refresh-from-rmp)
- [Reviewing matches](#reviewing-matches)
- [App ratings and comments (basic structure, for later)](#app-ratings-and-comments-basic-structure-for-later)
- [Spring 2027 predictions](#spring-2027-predictions)
- [Trend chart preview](#trend-chart-preview)
- [Verify](#verify)
- [Open items](#open-items)

## For teammates: open it in DBeaver
Installed in the team database on 2026-10-17. Use the connection you already have (host = the project owner's Tailscale address, port 55437, database `registration_unified_spring2027`, user `catalog_reader`), then right-click the connection > **Refresh**. A new schema `professor_ratings` appears; `catalog_reader` can read these 9 views and nothing else in it:

| View | Start here for |
|---|---|
| `course_prediction` | predicted Spring 2027 quality and difficulty per course |
| `section_prediction` | the same per section, with instructors and confidence |
| `professor_prediction` | predicted Spring 2027 level per professor |
| `professor_overview` | professor tab header: RMP numbers and link, app numbers, courses taught |
| `professor_comment` | app comments (empty until the app collects ratings) |
| `instructor_term_rating`, `instructor_year_rating` | per-semester and per-year history for trend graphs |
| `instructor_rating`, `section_instructor_rating` | RMP overall rating per professor and per section |

```
SELECT offering_id, title, pred_quality, pred_difficulty, sections_predicted, sections, status
FROM professor_ratings.course_prediction WHERE term_code = '202710' ORDER BY offering_id;
```
Rules for everyone: the data holds real instructor names and RMP data, so do not export it, paste it into public places, or put it in the repository. Label predictions as predicted. Only the database owner changes it (imports, matches, predictions); ask them to rerun a step instead of editing.

## Files
| File | What it does |
|---|---|
| `V1__professor_ratings_schema.sql` | Tables: import runs, instructors, section assignments, RMP professors, RMP aggregate observations, match decisions. Append-only triggers |
| `V2__professor_ratings_views_and_import.sql` | Writers (`import_instructors`, `import_rmp_observations`, `propose_matches`, `review_match`) and the matching/coverage views |
| `V3__rating_history.sql` | Individual RMP ratings (date and scores only), `term_of_posting` (posting date to semester), `import_rmp_ratings` |
| `V4__student_ratings_and_comments.sql` | App ratings and comments (`student_rating`, `submit_student_rating`, `moderate_student_rating`), the combined semester/year trend views, the professor-tab views |
| `V5__term_predictions.sql` | Stored prediction runs (`prediction_run`, `instructor_prediction`, `instructor_course_prediction`, writer `record_predictions`) and the per-professor, per-section and per-course prediction views |
| `install.sql` | Runs V1-V5 and `verify_structure.sql` in one transaction; rerunnable |
| `verify_structure.sql` | 11 tables, 20 views, append-only triggers, writers closed to PUBLIC, one-way dependency, no RMP review text |
| `grant_reader.sql` | Read-only role: `SELECT` on 9 views, no base table |
| `grant_writer.sql` | For later: one backend role may call `submit_student_rating` and nothing else |
| `import_professor_ratings.py` | The command line: `instructors`, `fetch`, `match`, `decisions`, `fetch-ratings`, `report` |
| `collect_instructors.py`, `professor_names.py`, `rmp_client.py` | Banner input parsing, name folding, the low-volume RMP client |
| `predict_ratings.py` | Predicts a coming term from earlier ratings: backtest, parameter choice, one stored run |
| `make_trend_chart.py` | Writes a local HTML trend chart with the predicted next term (outside the repository) |
| `test_professor_ratings.py`, `test_predict_ratings.py` | 19 + 14 unit tests (no database, no network) |
| `run_ratings_validation.py` | 61 database scenarios, each in a rolled-back transaction |

## Data model
Tables:

| Table | Holds | Written by |
|---|---|---|
| `import_run` | one row per import, with input hash (append-only) | every import |
| `instructor` | one row per person Banner lists ("Staff"/"TBA" are never stored) | `instructors` |
| `instructor_section` | who teaches which CRN in which term | `instructors` (replaces per term and source) |
| `rmp_teacher` | RMP professors whose last name equals an instructor's | `fetch` |
| `rmp_rating_observation` | RMP's overall aggregates per fetch (append-only) | `fetch` |
| `instructor_rmp_match` | which RMP professor is which instructor (append-only; latest row wins) | `match`, `review_match`, `decisions --apply` |
| `rmp_rating` | each RMP rating: id, posting date, quality, difficulty, would-take-again, course label, online flag. No text | `fetch-ratings` (replaces per professor) |
| `student_rating` | a rating submitted in our app: student ref, professor, semester, course, scores, optional comment, moderation status | `submit_student_rating`, `moderate_student_rating` |
| `prediction_run` | one prediction run: target term, method, parameters, backtest, input hash (append-only) | `predict_ratings.py` |
| `instructor_prediction`, `instructor_course_prediction` | predicted quality and difficulty per instructor, and per instructor and course of the target term (append-only) | `predict_ratings.py` |

Views:

| View | Use |
|---|---|
| `instructor_term_rating` | **Trend graph, per semester.** Per instructor and semester: ratings (total, RMP, app), average quality and difficulty, would-take-again %, previous rated semester, `semesters_apart`, `quality_change`, `difficulty_change` |
| `instructor_year_rating` | The same per year (Spring, Summer and Fall 2024 are 2024) |
| `professor_overview` | **Professor tab header.** RMP overall numbers, the RMP link (`rmp_url`, for attribution), app averages and count, courses taught this term |
| `professor_comment` | **Professor tab comments.** Visible app comments with semester, course and scores. Read with `ORDER BY submitted_at DESC` |
| `course_prediction` | **Course list, Spring 2027.** Per course offering: predicted quality and difficulty (average of its lecture sections), range across sections, sections predicted / total, `status` |
| `section_prediction` | Per section: instructors, predicted quality and difficulty, `confidence`, `status` (`PREDICTED`, `PARTIAL`, `NO_HISTORY`, `NO_INSTRUCTOR`) |
| `professor_prediction` | Per professor: the predicted level, ratings it rests on, last rated semester, `confidence` |
| `latest_prediction_run` | The run shown (newest per target term), with its parameters and backtest |
| `instructor_rating`, `section_instructor_rating` | RMP overall rating per instructor, and per section |
| `semester_rating_input`, `rating_term` | Every counted rating with its semester (building blocks) |
| `review_queue`, `accepted_match_context`, `match_candidate`, `current_instructor_match`, `latest_rmp_observation` | Matching and review |
| `instructor_section_orphan`, `section_without_instructor`, `rating_coverage` | Integrity and coverage checks |

**How a semester is decided.** An app rating carries the semester the student chose. An RMP rating only has the date it was *posted*, so `term_of_posting` maps it to the semester it most likely describes:

| Posted | Semester |
|---|---|
| Jan 1-19 | Fall of the previous year (winter-break reviews) |
| Jan 20-Jun 15 | Spring |
| Jun 16-Aug 24 | Summer |
| Aug 25-Dec 31 | Fall |

To change the rule, edit that one function; every view uses it.

`semesters_apart` is 1 for consecutive semesters (Fall to Spring, Spring to Fall); a summer counts as half. `sample_note` and `change_is_reliable` (both periods have 3+ ratings) are informational: every rating is kept and counted, however few.

## What was done (2026-10-17)
In order, all against an isolated copy (`ratings_scratch`) of the unified database `registration_unified_spring2027` on port 55437, made with `pg_dump` (the source was only read):

1. **Installed** the schema (`install.sql`) and the reader grant.
2. **Instructors** from three local Banner inputs: the non-CSE `instructors.csv` (term, CRN, instructor, Primary/Additional), the CSE Banner staging fixture (`data/staging/cse-202710-pilot/cse_full_fixture.json`, untracked) and the CSE 007 snapshot fixture (`data/cse007-snapshot/fixture.json`). Result: 66 instructors, 193 section assignments; 94 "Staff"/TBA entries skipped, so 60 non-CSE sections have no named instructor and every CSE section has one.
3. **RMP roster** for Lehigh (school id 509): 14 requests, 1,320 professors; the 87 whose last name equals an instructor's were stored.
4. **Matching**: 42 accepted automatically (exact, unique name), 5 to review, 19 with no RMP profile.
5. **Review** by the project owner: 5 confirmed, 1 rejected (a same-name profile in an unrelated department). Saved to a local decisions file. Now 46 instructors are matched.
6. **Individual RMP ratings** of the 43 matched professors that have ratings: about 46 requests, 1,226 ratings, posted 2003 to 2026-10-16, none dropped. For 19 professors RMP's own summary count differs from the ratings returned by one.
7. **App ratings and comments**: the tables, functions and views were added (V4). No app rating exists yet.
8. **Chart** written to `~/.registration-unified/rmp/trend-chart.html`.
9. **Spring 2027 predictions** (V5, `predict_ratings.py`): backtest over 259 professor-semesters since 2019 chose a 4-semester half-life, no pull toward the overall mean and a course strength of 3. Stored as prediction run 1: 43 of the 66 Spring 2027 instructors predicted (10 HIGH, 16 MEDIUM, 17 LOW confidence), 66 instructor-course predictions; 47 courses fully predicted, 7 partly, 18 without data; 141 sections predicted, 1 partly, 41 whose instructors have no ratings, 60 with only "Staff". The chart shows the prediction as a dashed point.

Local files outside the repository (never commit them):

| File | What |
|---|---|
| `~/.registration-unified/rmp/roster-2026-10-17.json` | raw RMP roster |
| `~/.registration-unified/rmp/ratings-2026-10-17.json` | raw individual ratings (only the fields listed above) |
| `~/.registration-unified/rmp/review-decisions.json` | the owner's 6 match decisions |
| `~/.registration-unified/rmp/trend-chart.html` | the chart |
| `~/.registration-unified/backups/` | backup of the team database taken before the install |

10. **Installed into the team database** `registration_unified_spring2027` (same steps, with `--allow-shared`), after a full backup (`~/.registration-unified/backups/registration_unified_spring2027-before-professor-ratings-20261017-204818.dump`). Every input hash and the prediction and trend checksums equal the scratch run's; a sorted comparison with the backup shows every other schema's data and definitions unchanged; `catalog_reader` reads the 9 views and is refused on the base tables and on every writer; its existing catalog access still works. The scratch-only validation scenarios are not run against the team database (the runner refuses it).

Not done yet: any backend endpoint or frontend view, and a push or pull request.

## Rebuild from the local caches (no network)
```
export PATH=/opt/homebrew/opt/postgresql@17/bin:$PATH PGHOST=127.0.0.1 PGPORT=55437 PGUSER=catalog_owner PGDATABASE=<isolated database>
cd data/professor-ratings
C=~/.registration-unified/rmp
psql -X -v ON_ERROR_STOP=1 -f install.sql
psql -X -v ON_ERROR_STOP=1 -v reader=catalog_reader -f grant_reader.sql
python3 -I import_professor_ratings.py instructors --noncse-csv <instructors.csv> --cse-fixture <cse_full_fixture.json> --snapshot ../cse007-snapshot/fixture.json
python3 -I import_professor_ratings.py fetch --from-cache $C/roster-2026-10-17.json
python3 -I import_professor_ratings.py match
python3 -I import_professor_ratings.py decisions --apply $C/review-decisions.json
python3 -I import_professor_ratings.py fetch-ratings --from-cache $C/ratings-2026-10-17.json
python3 -I import_professor_ratings.py report
python3 -I predict_ratings.py                    # Spring 2027 predictions (no network)
python3 -I make_trend_chart.py                   # the chart
```
The database must already contain `catalog_research`. Every step except `report` takes `--dry-run` (rolls back). The shared database (`registration_unified_spring2027`) is refused unless `--allow-shared` is passed, which needs the team's approval. Output is counts and hashes, never names. Re-running a step is safe: matching appends nothing when nothing changed, and decisions already present are skipped.

## Refresh from RMP
```
python3 -I import_professor_ratings.py fetch            # roster again (~14 requests, 2 s apart): adds a new aggregate observation
python3 -I import_professor_ratings.py match            # a person's decisions are never overwritten
python3 -I import_professor_ratings.py fetch-ratings    # individual ratings again (~46 requests): replaces each professor's ratings
```
Each fetch stops on the first HTTP error, has a request cap (`--max-requests`), and writes a dated raw copy next to the others. RMP's endpoint is unofficial and may change; a changed field makes the fetch fail and store nothing.

## Reviewing matches
`NEEDS_REVIEW` instructors have no RMP rating until a person decides:
```
SELECT * FROM professor_ratings.review_queue;
SELECT professor_ratings.review_match(<instructor_id>, <rmp_legacy_id>, 'CONFIRM', '<your name>', '<why>');
SELECT professor_ratings.review_match(<instructor_id>, NULL, 'NO_MATCH', '<your name>', '<why>');
python3 -I import_professor_ratings.py decisions --export ~/.registration-unified/rmp/review-decisions.json   # keep them for rebuilds
```
Also check `accepted_match_context` for accepted matches whose RMP department does not fit the subjects taught. The reviewer must be a person (names like script, bot, claude, auto, import are refused).

## App ratings and comments (basic structure, for later)
Nothing calls this yet. The likely place is the course page's "Professors" tab once the frontend is finished.

- **Submit** (one rating per student, professor, semester and course; submitting again replaces it):
  ```
  SELECT professor_ratings.submit_student_rating('<opaque student id>', <instructor_id>, 2026, 'FALL', 'CSE-017', <quality 1-5>, <difficulty 1-5>, <would take again: true/false/NULL>, '<comment or NULL>');
  ```
  The student id must be an opaque app id (8-128 letters, digits, `_` or `-`); an e-mail address or name is refused. A comment is up to 1,000 characters; a blank one is stored as none. A future year is refused.
- **Read** for the tab: `professor_overview` (header), `instructor_term_rating` (graph; app and RMP ratings together, counted per source), `professor_comment` (comments, newest first).
- **Moderate**: `moderate_student_rating(<id>, 'COMMENT_HIDDEN' | 'REMOVED' | 'VISIBLE', '<moderator>', '<reason>')`. Hidden keeps the scores; removed counts nowhere. Owner only.
- **Backend access, later**: the backend's read-only role gets the views through `grant_reader.sql`. Writing needs a separate role: `grant_writer.sql` gives it `EXECUTE` on `submit_student_rating` only (the function runs with the owner's rights; the role gets no table access). The backend would also need `professor_ratings` added to its allowed schemas, and an endpoint documented in `docs/api-contract.md`.
- **Comments are untrusted text**: render them as plain text, never HTML, and never put them into an AI prompt unescaped.
- **RMP comments are not stored.** Showing RMP reviews would mean copying text the reviewers wrote on RMP; that is a separate decision (copyright and RMP's terms), not a volume question.

## Spring 2027 predictions
**What it answers:** how good (quality) and how hard (difficulty) a course is likely to be next term, given who teaches it. It is an estimate from earlier student opinion, not a fact about the course; show it with its `confidence` and say it is predicted.

**Run it** (no network; the prediction is stored as a new run only if its inputs changed, `--force` stores anyway):
```
python3 -I predict_ratings.py --target-term-code 202710 --target-year 2027 --target-season SPRING   # these are the defaults
```
It prints the chosen parameters, the backtest error and the baselines, and counts. Run it again whenever ratings change (a new RMP fetch, new app ratings); the views always show the newest run.

**How it predicts** (`RECENCY_WEIGHTED_SHRUNK_MEAN`), per instructor teaching in the target term:
1. Every earlier rating, from RMP and from the app, gets the weight `0.5 ^ (semesters before the target / half-life)`: recent semesters count more, so a professor who improved or declined moves the prediction.
2. The weighted mean can be pulled toward everyone's overall mean with the strength of `prior strength` ratings (helps professors with very few ratings).
3. For each course the instructor teaches in the target term, ratings that name that course (app ratings carry it; RMP labels such as "CSE17" are parsed by `course_key_of_label`) pull the course value toward them with the strength `course strength`.
4. Only ratings from before the target term are used. An instructor with no earlier rating gets no prediction (`NO_HISTORY`), never a made-up number.
5. `confidence`: effective (recency-weighted) ratings below 3 is LOW, below 10 MEDIUM, else HIGH.

**How the parameters were chosen (2026-10-17):** every past semester since 2019 was predicted from the semesters before it only, for every grid setting (half-life 1, 2, 4, 8 semesters or none; prior strength 0, 2, 5, 10; course strength 1, 3, 5, 10 or none), and the lowest mean absolute error (quality + difficulty) won:

| Method | MAE quality | MAE difficulty |
|---|---|---|
| **Chosen**: half-life 4, prior strength 0, course strength 3 | **0.889** | **0.703** |
| All-time average of the professor | 0.910 | 0.748 |
| Last rated semester only | 0.940 | 0.790 |
| Everyone's overall average | 1.227 | 0.815 |

Per course (327 professor-course-semesters): 0.903 / 0.747 with course strength 3, against 0.907 / 0.764 ignoring course labels. The gain over a professor's plain average is real but modest: semester-to-semester opinion is noisy (an error near 0.9 on a 1-5 scale), so treat the numbers as a guide. The full grid and baselines are stored in `prediction_run.backtest`.

**Reading it:**
```
SELECT * FROM professor_ratings.course_prediction  WHERE term_code = '202710' ORDER BY offering_id;   -- course list
SELECT * FROM professor_ratings.section_prediction WHERE term_code = '202710' AND offering_id = 'CSE-017';
SELECT * FROM professor_ratings.professor_prediction;                                                  -- professor tab
```
**Show every prediction, whatever its confidence** (project owner, 2026-10-17): new professors naturally have few reviews, so a LOW prediction is still shown; `confidence` stays available if the UI wants to mark it. A section with several instructors gets their average. A course averages its lecture sections (all sections when it has no lecture), so a staff-run lab does not hide the lecturer's value; `sections_predicted` / `sections` and `status` say how complete it is.

## Trend chart preview
```
python3 -I make_trend_chart.py [--out ~/.registration-unified/rmp/trend-chart.html] [--since 2019]
open ~/.registration-unified/rmp/trend-chart.html
```
One self-contained page (no external scripts): pick a professor, switch between semester and year, see quality and difficulty lines (dot size = number of ratings, hollow = fewer than 3), the predicted next term as a dashed point, and a table with the change from the previous rated period. It contains real names, so it is written outside the repository (a path inside is refused) and must not be published. It is a preview for the team, not the app's UI.

## Verify
```
python3 -m unittest -v test_professor_ratings test_predict_ratings   # 33 tests, no database, no network
python3 -I run_ratings_validation.py             # 61 scenarios on the isolated database (all rolled back)
psql -X -f verify_structure.sql                  # structure assertions
```
On 2026-10-17 all passed. The scenarios were also checked against deliberately broken rules (mutation checks, run by hand): matching 8 (7 caught), rating history 6 (5 caught), app ratings 6 (6 caught), predictions 9 (9 caught: 4 in the views, 5 in the model, including future ratings leaking into a prediction). The two misses are the same harmless case: an unaccepted match has no RMP id, so it cannot reach a rating.

## Open items
- To undo the install in the team database: `DROP SCHEMA professor_ratings CASCADE;` as the owner (nothing else depends on it), or restore the backup.
- Backend endpoint(s) and frontend Professors tab (after the frontend is finished).
- Whether Banner instructor names may be shown to users (ADR-0006, point 2) and whether RMP comments are wanted at all.
- 60 non-CSE sections list only "Staff" and stay unrated and unpredicted; 23 named Spring 2027 instructors have no ratings yet, so their courses show no prediction until app ratings arrive.
