"""Exercises every rule of professor_ratings in an ISOLATED database, one rolled-back transaction per scenario (stdlib only; needs psql).

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner> PGDATABASE=<local database> python3 -I data/professor-ratings/run_ratings_validation.py

Uses synthetic instructors and RMP professors only; nothing is committed, so real rows are untouched. A scenario passes when its assertions hold
(prints nothing) or, for a refusal scenario, when the database raises the expected message. Refuses the shared database.
"""
import json
import os
import subprocess
import sys

SHARED = {"registration_cse007_local", "registration_unified_spring2027"}


def lit(value):
    return "$j$" + json.dumps(value, sort_keys=True) + "$j$"


def teacher(i, first, last, ratings=10, avg=4.0, dept="Art", wta=60):
    return {"legacyId": i, "nodeId": f"T{i}", "firstName": first, "lastName": last, "firstKey": first.lower(), "lastKey": last.lower(), "department": dept,
            "avgRating": avg, "avgDifficulty": 3.0, "wouldTakeAgainPercent": wta, "numRatings": ratings}


def person(last, first):
    return {"bannerName": f"{last.title()}, {first.title()}", "lastName": last.title(), "firstName": first.title(), "lastKey": last.lower(), "firstKey": first.lower()}


def load_instructors(*people, assignments=()):
    payload = {"instructors": list(people), "assignments": list(assignments)}
    return f"SELECT professor_ratings.import_instructors({lit(payload)}::jsonb, 'validation', repeat('a', 64));\n"


def load_rmp(*teachers):
    payload = {"schoolLegacyId": 509, "fetchedAt": "2026-10-17T00:00:00+00:00", "teachers": list(teachers)}
    return f"SELECT professor_ratings.import_rmp_observations({lit(payload)}::jsonb, 'validation', repeat('b', 64));\n"


def status_of(last, first="", expected_status="", expected_method=""):
    where = f"i.banner_name = '{last.title()}, {first.title()}'"
    return (f"DO $$ DECLARE s record; BEGIN SELECT m.status, m.method INTO s FROM professor_ratings.current_instructor_match m JOIN professor_ratings.instructor i USING (instructor_id) "
            f"WHERE {where}; IF s.status IS DISTINCT FROM '{expected_status}' OR s.method IS DISTINCT FROM '{expected_method}' THEN RAISE EXCEPTION 'got % / % for {last}', s.status, s.method; END IF; END $$;\n")


def assert_sql(condition, message):
    return f"DO $$ BEGIN IF NOT ({condition}) THEN RAISE EXCEPTION '{message}'; END IF; END $$;\n"


def instructor_id(last, first):
    return f"(SELECT instructor_id FROM professor_ratings.instructor WHERE banner_name = '{last.title()}, {first.title()}')"


PROPOSE = "SELECT professor_ratings.propose_matches('validation');\n"
Q = person
SCENARIOS = []


def scenario(name, sql, expect_error=None, user=None):
    SCENARIOS.append((name, sql, expect_error, user))


# ---- matching rules
scenario("one exact name is accepted automatically", load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv")) + PROPOSE + status_of("zyxwv", "alder", "AUTO_ACCEPTED", "EXACT_NAME"))
scenario("two RMP professors with the same name are ambiguous, never accepted", load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv"), teacher(9002, "Alder", "Zyxwv")) + PROPOSE + status_of("zyxwv", "alder", "NEEDS_REVIEW", "AMBIGUOUS"))
scenario("a nickname with the same initial needs a person", load_instructors(Q("zyxwv", "alexander")) + load_rmp(teacher(9001, "Alex", "Zyxwv")) + PROPOSE + status_of("zyxwv", "alexander", "NEEDS_REVIEW", "FIRST_NAME_VARIANT"))
scenario("an exact name beside a near name is still sent to a person", load_instructors(Q("zyxwv", "corvin")) + load_rmp(teacher(9001, "Corvin", "Zyxwv"), teacher(9002, "Cormac", "Zyxwv")) + PROPOSE + status_of("zyxwv", "corvin", "NEEDS_REVIEW", "AMBIGUOUS"))
scenario("a different first initial is no candidate", load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Quill", "Zyxwv")) + PROPOSE + status_of("zyxwv", "alder", "NO_MATCH", "NONE"))
scenario("an initial alone never counts as an exact name", load_instructors(Q("zyxwv", "a")) + load_rmp(teacher(9001, "A", "Zyxwv")) + PROPOSE + status_of("zyxwv", "a", "NEEDS_REVIEW", "FIRST_NAME_VARIANT"))
scenario("a middle name does not break an exact first name", load_instructors(Q("zyxwv", "alder james")) + load_rmp(teacher(9001, "Alder", "Zyxwv")) + PROPOSE + status_of("zyxwv", "alder james", "AUTO_ACCEPTED", "EXACT_NAME"))
scenario("a repeated run appends nothing", load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv")) + PROPOSE +
         "CREATE TEMP TABLE before AS SELECT count(*) n FROM professor_ratings.instructor_rmp_match;\n" + PROPOSE +
         assert_sql("(SELECT count(*) FROM professor_ratings.instructor_rmp_match) = (SELECT n FROM before)", "second run appended rows"))
scenario("a person's confirmation survives a new proposal run",
         load_instructors(Q("zyxwv", "alexander")) + load_rmp(teacher(9001, "Alex", "Zyxwv")) + PROPOSE +
         f"SELECT professor_ratings.review_match({instructor_id('zyxwv', 'alexander')}, 9001, 'CONFIRM', 'Pat Reviewer', 'checked the department page');\n" + PROPOSE +
         status_of("zyxwv", "alexander", "CONFIRMED", "MANUAL"))
scenario("a person's no-match survives a new proposal run",
         load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv")) + PROPOSE +
         f"SELECT professor_ratings.review_match({instructor_id('zyxwv', 'alder')}, NULL, 'NO_MATCH', 'Pat Reviewer', 'different person in another field');\n" + PROPOSE +
         status_of("zyxwv", "alder", "NO_MATCH", "MANUAL") + assert_sql("NOT EXISTS (SELECT 1 FROM professor_ratings.instructor_rating WHERE banner_name = 'Zyxwv, Alder')", "an unmatched instructor got a rating"))

# ---- ratings views
scenario("only accepted matches have ratings, with a sample note",
         load_instructors(Q("zyxwv", "alder"), Q("quillon", "bree"), Q("vorpal", "cade")) +
         load_rmp(teacher(9001, "Alder", "Zyxwv", ratings=20), teacher(9002, "Bree", "Quillon", ratings=3), teacher(9003, "Cade", "Vorpal"), teacher(9004, "Cadence", "Vorpal")) + PROPOSE +
         assert_sql("(SELECT sample_note FROM professor_ratings.instructor_rating WHERE banner_name = 'Zyxwv, Alder') = 'OK'", "20 ratings should be OK") +
         assert_sql("(SELECT sample_note FROM professor_ratings.instructor_rating WHERE banner_name = 'Quillon, Bree') = 'LOW_SAMPLE'", "3 ratings should be LOW_SAMPLE") +
         assert_sql("NOT EXISTS (SELECT 1 FROM professor_ratings.instructor_rating WHERE banner_name = 'Vorpal, Cade')", "an ambiguous match got a rating") +
         assert_sql("(SELECT count(*) FROM professor_ratings.review_queue WHERE banner_name = 'Vorpal, Cade') = 2", "the review queue should list both candidates"))
scenario("zero ratings store no averages and -1 would-take-again becomes NULL",
         load_instructors(Q("zyxwv", "alder"), Q("quillon", "bree")) + load_rmp(teacher(9001, "Alder", "Zyxwv", ratings=0, avg=0), teacher(9002, "Bree", "Quillon", wta=-1)) + PROPOSE +
         assert_sql("(SELECT sample_note FROM professor_ratings.instructor_rating WHERE banner_name = 'Zyxwv, Alder') = 'NO_RATINGS'", "no ratings") +
         assert_sql("(SELECT avg_rating FROM professor_ratings.instructor_rating WHERE banner_name = 'Zyxwv, Alder') IS NULL", "average kept for zero ratings") +
         assert_sql("(SELECT would_take_again_pct FROM professor_ratings.instructor_rating WHERE banner_name = 'Quillon, Bree') IS NULL", "-1 was stored"))
scenario("the newest observation wins",
         load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv", avg=2.0)) +
         load_rmp({**teacher(9001, "Alder", "Zyxwv", avg=4.5)}).replace("2026-10-17", "2026-10-18") + PROPOSE +
         assert_sql("(SELECT avg_rating FROM professor_ratings.instructor_rating WHERE banner_name = 'Zyxwv, Alder') = 4.5", "an older observation won"))

ASSIGN = lambda crn, name, source="BANNER_GRID", term="202710", role="PRIMARY": {"term": term, "crn": crn, "bannerName": name, "role": role, "source": source}


# ---- rating history
def load_ratings(legacy_id, *ratings):
    payload = {"fetchedAt": "2026-10-17T00:00:00+00:00", "teachers": [{"legacyId": legacy_id, "ratings": [
        {"ratingId": rid, "postedOn": day, "quality": q, "difficulty": d, "wouldTakeAgain": w, "classLabel": "X1", "isOnline": False} for rid, day, q, d, w in ratings]}]}
    return f"SELECT professor_ratings.import_rmp_ratings({lit(payload)}::jsonb, 'validation', repeat('c', 64));\n"


TERM_CASES = [("2025-01-05", 2024, "FALL"), ("2025-01-19", 2024, "FALL"), ("2025-01-20", 2025, "SPRING"), ("2025-06-15", 2025, "SPRING"),
              ("2025-06-16", 2025, "SUMMER"), ("2025-08-24", 2025, "SUMMER"), ("2025-08-25", 2025, "FALL"), ("2025-12-31", 2025, "FALL")]
scenario("a posting date maps to the semester it most likely describes",
         "".join(assert_sql(f"(SELECT (term_year, term_season) FROM professor_ratings.term_of_posting(DATE '{d}')) = ({y}, '{t}'::text)", f"{d} is not {y} {t}") for d, y, t in TERM_CASES))
RATED = load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv")) + PROPOSE
scenario("semester averages and the change from the previous rated semester",
         RATED + load_ratings(9001, (1, "2024-03-01", 2.0, 4, False), (2, "2024-04-01", 3.0, 4, True), (3, "2024-05-01", 4.0, 4, None),
                              (4, "2024-12-20", 4.0, 2, True), (5, "2025-01-10", 5.0, 2, True), (6, "2025-01-12", 4.5, 3, True), (7, "2025-03-03", 5.0, 1, True)) +
         assert_sql("(SELECT array_agg(term_label || ':' || num_ratings || ':' || avg_quality ORDER BY term_order) FROM professor_ratings.instructor_term_rating "
                    "WHERE banner_name = 'Zyxwv, Alder') = ARRAY['2024 Spring:3:3.00', '2024 Fall:3:4.50', '2025 Spring:1:5.00']", "per-semester averages are wrong") +
         assert_sql("(SELECT (prev_term_label, quality_change, change_is_reliable) FROM professor_ratings.instructor_term_rating WHERE banner_name = 'Zyxwv, Alder' "
                    "AND term_label = '2024 Fall') = ('2024 Spring'::text, 1.50, true)", "fall change is wrong") +
         assert_sql("(SELECT (sample_note, change_is_reliable) FROM professor_ratings.instructor_term_rating WHERE banner_name = 'Zyxwv, Alder' "
                    "AND term_label = '2025 Spring') = ('LOW_SAMPLE'::text, false)", "a one-rating semester must be LOW_SAMPLE and unreliable") +
         assert_sql("(SELECT would_take_again_pct FROM professor_ratings.instructor_term_rating WHERE banner_name = 'Zyxwv, Alder' AND term_label = '2024 Spring') = 50.0",
                    "would-take-again must ignore unanswered ratings") +
         assert_sql("(SELECT array_agg(term_year || ':' || num_ratings ORDER BY term_year) FROM professor_ratings.instructor_year_rating WHERE banner_name = 'Zyxwv, Alder') "
                    "= ARRAY['2024:6', '2025:1']", "yearly grouping is wrong"))
scenario("a professor who is not an accepted match has no trend",
         load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv"), teacher(9002, "Alder", "Zyxwv")) + PROPOSE +
         load_ratings(9001, (1, "2024-03-01", 2.0, 4, False)) +
         assert_sql("NOT EXISTS (SELECT 1 FROM professor_ratings.instructor_term_rating WHERE banner_name = 'Zyxwv, Alder')", "an ambiguous match got a trend"))
scenario("a new fetch replaces that professor's ratings (a removed rating disappears)",
         RATED + load_ratings(9001, (1, "2024-03-01", 2.0, 4, False), (2, "2024-04-01", 3.0, 4, True)) + load_ratings(9001, (2, "2024-04-01", 3.0, 4, True)) +
         assert_sql("(SELECT count(*) FROM professor_ratings.rmp_rating WHERE rmp_legacy_id = 9001) = 1", "the removed rating survived"))
scenario("a rating score outside 1-5 is refused", RATED + load_ratings(9001, (1, "2024-03-01", 6.0, 4, False)), expect_error="rmp_rating_quality_check")
scenario("ratings for an unknown RMP professor are refused", load_ratings(424242, (1, "2024-03-01", 3.0, 4, False)), expect_error="rmp_rating_rmp_legacy_id_fkey")
scenario("the reader role sees the trend views but not the ratings table",
         "SET ROLE catalog_reader;\nSELECT count(*) FROM professor_ratings.instructor_term_rating;\nSELECT count(*) FROM professor_ratings.instructor_year_rating;\n"
         "SELECT count(*) FROM professor_ratings.rmp_rating;\n", expect_error="permission denied for table rmp_rating", user="postgres")

# ---- ratings submitted in the app
def submit(ref, last, first, year, season, quality, difficulty=3, wta=True, comment=None, course="CSE-017"):
    c = "NULL" if comment is None else "'" + comment.replace("'", "''") + "'"
    crs = "NULL" if course is None else f"'{course}'"
    return (f"SELECT professor_ratings.submit_student_rating('{ref}', {instructor_id(last, first)}, {year}, '{season}', {crs}, {quality}, {difficulty}, "
            f"{'NULL' if wta is None else str(wta).lower()}, {c});\n")


def rating_id(ref):
    return f"(SELECT student_rating_id FROM professor_ratings.student_rating WHERE student_ref = '{ref}' LIMIT 1)"


TERM = "(SELECT {col} FROM professor_ratings.instructor_term_rating WHERE banner_name = 'Zyxwv, Alder' AND term_label = '{label}')"
scenario("app ratings accumulate per semester next to RMP ratings",
         RATED + load_ratings(9001, (1, "2024-03-01", 2.0, 4, False)) +
         submit("student-aaaa1", "zyxwv", "alder", 2024, "SPRING", 4) + submit("student-bbbb2", "zyxwv", "alder", 2024, "SPRING", 5) +
         assert_sql(TERM.format(col="(num_ratings, num_rmp_ratings, num_app_ratings, avg_quality)", label="2024 Spring") + " = (3, 1, 2, 3.67)", "sources were not combined"))
scenario("an app rating with no RMP match still makes a trend",
         load_instructors(Q("zyxwv", "alder")) + submit("student-aaaa1", "zyxwv", "alder", 2024, "FALL", 2) + submit("student-aaaa1", "zyxwv", "alder", 2025, "SPRING", 4) +
         assert_sql(TERM.format(col="(prev_term_label, semesters_apart, quality_change)", label="2025 Spring") + " = ('2024 Fall'::text, 1.0, 2.00)", "trend from app ratings is wrong"))
scenario("semesters_apart counts skipped semesters",
         load_instructors(Q("zyxwv", "alder")) + submit("student-aaaa1", "zyxwv", "alder", 2023, "SPRING", 2) + submit("student-aaaa1", "zyxwv", "alder", 2025, "SPRING", 4) +
         submit("student-aaaa1", "zyxwv", "alder", 2025, "SUMMER", 4) +
         assert_sql(TERM.format(col="semesters_apart", label="2025 Spring") + " = 4", "Spring 2023 -> Spring 2025 is 4 semesters") +
         assert_sql(TERM.format(col="semesters_apart", label="2025 Summer") + " = 0.5", "a summer counts as half"))
scenario("submitting again replaces the student's rating",
         load_instructors(Q("zyxwv", "alder")) + submit("student-aaaa1", "zyxwv", "alder", 2024, "FALL", 1, comment="first") +
         submit("student-aaaa1", "zyxwv", "alder", 2024, "FALL", 5, comment="changed my mind") +
         assert_sql("(SELECT (count(*), max(quality), max(comment)) FROM professor_ratings.student_rating WHERE student_ref = 'student-aaaa1') = (1::bigint, 5::smallint, 'changed my mind'::text)", "a resubmission duplicated the rating"))
scenario("visible comments are listed; a hidden comment keeps its score; a removed rating counts nowhere",
         load_instructors(Q("zyxwv", "alder")) + submit("student-aaaa1", "zyxwv", "alder", 2024, "FALL", 5, comment="clear lectures") +
         submit("student-bbbb2", "zyxwv", "alder", 2024, "FALL", 1, comment="rude text") + submit("student-cccc3", "zyxwv", "alder", 2024, "FALL", 3, comment="spam") +
         f"SELECT professor_ratings.moderate_student_rating({rating_id('student-bbbb2')}, 'COMMENT_HIDDEN', 'Pat Moderator', 'abusive wording');\n" +
         f"SELECT professor_ratings.moderate_student_rating({rating_id('student-cccc3')}, 'REMOVED', 'Pat Moderator', 'spam');\n" +
         assert_sql("(SELECT array_agg(comment) FROM professor_ratings.professor_comment WHERE banner_name = 'Zyxwv, Alder') = ARRAY['clear lectures']", "comment visibility is wrong") +
         assert_sql(TERM.format(col="(num_ratings, avg_quality)", label="2024 Fall") + " = (2, 3.00)", "hidden comment must keep its score; removed must not count") +
         assert_sql("(SELECT (app_num_ratings, app_avg_quality) FROM professor_ratings.professor_overview WHERE banner_name = 'Zyxwv, Alder') = (2, 3.00)", "overview counts are wrong"))
scenario("a blank comment is stored as no comment", load_instructors(Q("zyxwv", "alder")) + submit("student-aaaa1", "zyxwv", "alder", 2024, "FALL", 4, comment="   ") +
         assert_sql("(SELECT comment FROM professor_ratings.student_rating WHERE student_ref = 'student-aaaa1') IS NULL", "blank comment stored"))
scenario("an e-mail address is refused as a student reference", load_instructors(Q("zyxwv", "alder")) + submit("pat@lehigh.edu", "zyxwv", "alder", 2024, "FALL", 4),
         expect_error="student_rating_student_ref_check")
scenario("a score outside 1-5 is refused", load_instructors(Q("zyxwv", "alder")) + submit("student-aaaa1", "zyxwv", "alder", 2024, "FALL", 6), expect_error="student_rating_quality_check")
scenario("a comment over 1000 characters is refused", load_instructors(Q("zyxwv", "alder")) + submit("student-aaaa1", "zyxwv", "alder", 2024, "FALL", 4, comment="x" * 1001),
         expect_error="student_rating_comment_check")
scenario("a rating for a future year is refused", load_instructors(Q("zyxwv", "alder")) + submit("student-aaaa1", "zyxwv", "alder", 2099, "FALL", 4), expect_error="future year")
scenario("moderation needs a moderator and a reason", load_instructors(Q("zyxwv", "alder")) + submit("student-aaaa1", "zyxwv", "alder", 2024, "FALL", 4) +
         f"SELECT professor_ratings.moderate_student_rating({rating_id('student-aaaa1')}, 'REMOVED', '', '');\n", expect_error="moderation needs")
scenario("the overview links accepted matches to their RMP page",
         RATED + assert_sql("(SELECT rmp_url FROM professor_ratings.professor_overview WHERE banner_name = 'Zyxwv, Alder') = 'https://www.ratemyprofessors.com/professor/9001'", "rmp_url is wrong"))
WRITER_SETUP = ("CREATE ROLE validation_writer NOLOGIN;\nGRANT USAGE ON SCHEMA professor_ratings TO validation_writer;\n"
                "GRANT EXECUTE ON FUNCTION professor_ratings.submit_student_rating(text, bigint, integer, text, text, integer, integer, boolean, text) TO validation_writer;\n")
GET_ID = f"SELECT {instructor_id('zyxwv', 'alder')} AS wid \\gset\n"
scenario("a writer role can submit through the function only",
         load_instructors(Q("zyxwv", "alder")) + WRITER_SETUP + GET_ID + "SET ROLE validation_writer;\n"
         "SELECT professor_ratings.submit_student_rating('student-aaaa1', :wid, 2024, 'FALL', 'CSE-017', 4, 3, true, 'ok');\nRESET ROLE;\n" +
         assert_sql("EXISTS (SELECT 1 FROM professor_ratings.student_rating WHERE student_ref = 'student-aaaa1')", "the writer could not submit"), user="postgres")
scenario("a writer role cannot write the table directly",
         load_instructors(Q("zyxwv", "alder")) + WRITER_SETUP + GET_ID + "SET ROLE validation_writer;\n"
         "INSERT INTO professor_ratings.student_rating (student_ref, instructor_id, term_year, term_season, quality, difficulty) VALUES ('student-zzzz9', :wid, 2024, 'FALL', 5, 1);\n",
         expect_error="permission denied for table student_rating", user="postgres")
scenario("a writer role cannot moderate",
         load_instructors(Q("zyxwv", "alder")) + WRITER_SETUP + "SET ROLE validation_writer;\nSELECT professor_ratings.moderate_student_rating(1, 'REMOVED', 'Pat', 'nope');\n",
         expect_error="permission denied for function moderate_student_rating", user="postgres")

# ---- predictions (a synthetic term 209910 with two courses, so no real catalog row is touched)
FAKE_CATALOG = (
    "INSERT INTO catalog_research.import_run (kind, term_code, input_sha256, input_label, offering_count, section_count) VALUES ('TERM_CATALOG', '209910', repeat('d', 64), 'validation', 2, 5);\n"
    "INSERT INTO catalog_research.offering (term_code, offering_id, course_id, subject, course_number, title, credits, administrative_type, data_source, components_status, cross_listing_status, identity_status, run_id) "
    "SELECT '209910', c, c, 'ZZZ', right(c, 3), 'Synthetic ' || c, 3, 'Lecture', 'SNAPSHOT', 'COMPONENTS_UNVERIFIED', 'UNVERIFIED', 'SINGLE', (SELECT max(run_id) FROM catalog_research.import_run) "
    "FROM unnest(ARRAY['ZZZ-101', 'ZZZ-102']) c;\n"
    "INSERT INTO catalog_research.section (term_code, crn, offering_id, section_code, component_type, credits, schedule_type) VALUES "
    "('209910', '99101', 'ZZZ-101', '010', 'LECTURE', 3, 'Lecture'), ('209910', '99102', 'ZZZ-101', '011', 'LECTURE', 3, 'Lecture'), "
    "('209910', '99104', 'ZZZ-102', '010', 'LECTURE', 3, 'Lecture'), ('209910', '99105', 'ZZZ-102', '011', 'LECTURE', 3, 'Lecture');\n"
    "INSERT INTO catalog_research.section (term_code, crn, offering_id, section_code, component_type, credits, schedule_type, lecture_crn, link_status) VALUES "
    "('209910', '99103', 'ZZZ-101', '110', 'LAB', 0, 'Lab', '99101', 'CONFIRMED');\n")
FAKE_PEOPLE = load_instructors(Q("zyxwv", "alder"), Q("quillon", "bree"), assignments=[
    ASSIGN("99101", "Zyxwv, Alder", term="209910"), ASSIGN("99101", "Quillon, Bree", term="209910", role="ADDITIONAL"),
    ASSIGN("99102", "Zyxwv, Alder", term="209910"), ASSIGN("99104", "Quillon, Bree", term="209910"), ASSIGN("99105", "Zyxwv, Alder", term="209910")])


def record(quality, difficulty, course_quality, course_difficulty, method="RECENCY_WEIGHTED_SHRUNK_MEAN"):
    payload = {"targetTermCode": "209910", "targetYear": 2099, "targetSeason": "SPRING", "method": method, "parameters": {"halfLife": 2}, "backtest": {"pairs": 0},
               "ratingsUsed": 1, "instructors": [{"instructorId": 0, "quality": quality, "difficulty": difficulty, "basisRatings": 4, "effectiveRatings": 3.5,
               "lastRatedTerm": "2098 Fall", "confidence": "MEDIUM", "courses": [{"courseId": "ZZZ-101", "quality": course_quality, "difficulty": course_difficulty,
               "courseRatings": 2, "effectiveCourseRatings": 1.5}]}]}
    alder = "(SELECT instructor_id FROM professor_ratings.instructor WHERE banner_name = 'Zyxwv, Alder')"
    return f"SELECT professor_ratings.record_predictions(jsonb_set({lit(payload)}::jsonb, '{{instructors,0,instructorId}}', to_jsonb({alder})), repeat('e', 64));\n"


SEC = "(SELECT {col} FROM professor_ratings.section_prediction WHERE term_code = '209910' AND crn = '{crn}')"
CRS = "(SELECT {col} FROM professor_ratings.course_prediction WHERE term_code = '209910' AND offering_id = '{off}')"
PREDICTED = FAKE_CATALOG + FAKE_PEOPLE + record(4.0, 2.0, 4.5, 2.5)
scenario("section statuses: predicted, partial, no history, no instructor",
         PREDICTED +
         assert_sql(SEC.format(col="(status, instructors, pred_quality)", crn="99102") + " = ('PREDICTED'::text, 1, 4.50)", "99102 should use the course prediction") +
         assert_sql(SEC.format(col="(status, instructors, pred_quality)", crn="99101") + " = ('PARTIAL'::text, 2, 4.50)", "99101 has one predicted instructor of two") +
         assert_sql(SEC.format(col="(status, pred_quality IS NULL)", crn="99104") + " = ('NO_HISTORY'::text, true)", "99104 has no history") +
         assert_sql(SEC.format(col="status", crn="99103") + " = 'NO_INSTRUCTOR'", "the staff-only lab has no instructor") +
         assert_sql(SEC.format(col="pred_quality", crn="99105") + " = 4.00", "without a course prediction the instructor level is used"))
scenario("a course averages its lecture sections and says how many are predicted",
         PREDICTED +
         assert_sql(CRS.format(col="(sections, sections_predicted, pred_quality, pred_difficulty, status)", off="ZZZ-101") + " = (2, 2, 4.50, 2.50, 'PREDICTED'::text)", "ZZZ-101 aggregate is wrong (the lab must not count)") +
         assert_sql(CRS.format(col="(sections, sections_predicted, pred_quality, status)", off="ZZZ-102") + " = (2, 1, 4.00, 'PARTIAL'::text)", "ZZZ-102 aggregate is wrong"))
scenario("the newest run is the one shown",
         PREDICTED + record(3.0, 3.0, 3.5, 3.5) +
         assert_sql("(SELECT pred_quality FROM professor_ratings.professor_prediction WHERE target_term_code = '209910' AND banner_name = 'Zyxwv, Alder') = 3.00", "an older run is shown") +
         assert_sql(SEC.format(col="pred_quality", crn="99102") + " = 3.50", "sections show an older run"))
scenario("a prediction outside 1-5 is refused", FAKE_CATALOG + FAKE_PEOPLE + record(6.0, 2.0, 4.5, 2.5), expect_error="instructor_prediction_pred_quality_check")
scenario("an unknown method is refused", FAKE_CATALOG + FAKE_PEOPLE + record(4.0, 2.0, 4.5, 2.5, method="GUESS"), expect_error="prediction_run_method_check")
for table in ("prediction_run", "instructor_prediction", "instructor_course_prediction"):
    scenario(f"{table} is append-only", PREDICTED + f"DELETE FROM professor_ratings.{table};\n", expect_error="append-only")
scenario("the reader role sees section and course predictions but not the tables",
         "SET ROLE catalog_reader;\nSELECT count(*) FROM professor_ratings.course_prediction;\nSELECT count(*) FROM professor_ratings.section_prediction;\n"
         "SELECT count(*) FROM professor_ratings.professor_prediction;\nSELECT count(*) FROM professor_ratings.instructor_prediction;\n",
         expect_error="permission denied for table instructor_prediction", user="postgres")
scenario("a course label typed on RMP becomes a catalog course key",
         assert_sql("professor_ratings.course_key_of_label('cse 17') = 'CSE-017' AND professor_ratings.course_key_of_label('MATH021') = 'MATH-021' "
                    "AND professor_ratings.course_key_of_label('CSE-109') = 'CSE-109' AND professor_ratings.course_key_of_label('intro') IS NULL "
                    "AND professor_ratings.course_key_of_label(NULL) IS NULL", "course label parsing is wrong"))

# ---- assignments
scenario("re-importing a source replaces only that source's assignments",
         load_instructors(Q("zyxwv", "alder"), assignments=[ASSIGN("99001", "Zyxwv, Alder"), ASSIGN("99002", "Zyxwv, Alder", source="SNAPSHOT")]) +
         load_instructors(Q("zyxwv", "alder"), assignments=[ASSIGN("99003", "Zyxwv, Alder")]) +
         assert_sql("(SELECT array_agg(crn ORDER BY crn) FROM professor_ratings.instructor_section WHERE crn LIKE '990%') = ARRAY['99002', '99003']", "replacement was not scoped to the source"))
scenario("an assignment that names an unknown instructor aborts the import",
         load_instructors(Q("zyxwv", "alder"), assignments=[ASSIGN("99001", "Nobody, Such")]), expect_error="stored 0 assignments but the payload has 1")
scenario("an assignment for a section the catalog lacks is reported as an orphan",
         load_instructors(Q("zyxwv", "alder"), assignments=[ASSIGN("99001", "Zyxwv, Alder")]) +
         assert_sql("EXISTS (SELECT 1 FROM professor_ratings.instructor_section_orphan WHERE crn = '99001')", "orphan not reported"))

# ---- refusals
scenario("a placeholder name is refused", load_instructors(person("staff", "pat")), expect_error="instructor_banner_name_check")
scenario("a malformed CRN is refused", load_instructors(Q("zyxwv", "alder"), assignments=[ASSIGN("1", "Zyxwv, Alder")]), expect_error="instructor_section_crn_check")
scenario("an automated-looking reviewer name is refused",
         load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv")) +
         f"SELECT professor_ratings.review_match({instructor_id('zyxwv', 'alder')}, 9001, 'CONFIRM', 'import-script', 'looks right to me');\n", expect_error="decided_by")
scenario("a confirmation needs an RMP id", load_instructors(Q("zyxwv", "alder")) + f"SELECT professor_ratings.review_match({instructor_id('zyxwv', 'alder')}, NULL, 'CONFIRM', 'Pat Reviewer', 'looks right to me');\n", expect_error="CONFIRM needs an RMP professor id")
scenario("a rating outside 1-5 is refused", load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv", avg=7.0)), expect_error="avg_rating")
scenario("averages with zero ratings cannot be forced in",
         load_rmp(teacher(9001, "Alder", "Zyxwv")) + "INSERT INTO professor_ratings.rmp_rating_observation (rmp_legacy_id, run_id, avg_rating, avg_difficulty, num_ratings, fetched_at) "
         "SELECT 9001, max(run_id), 4.0, 3.0, 0, now() FROM professor_ratings.import_run;\n", expect_error="rmp_rating_observation_check")
for table, change in (("import_run", "UPDATE professor_ratings.import_run SET label = 'x'"), ("import_run", "DELETE FROM professor_ratings.import_run"),
                      ("rmp_rating_observation", "UPDATE professor_ratings.rmp_rating_observation SET num_ratings = 1"),
                      ("instructor_rmp_match", "DELETE FROM professor_ratings.instructor_rmp_match"), ("instructor_rmp_match", "TRUNCATE professor_ratings.instructor_rmp_match")):
    scenario(f"{table} is append-only ({change.split()[0]})", load_instructors(Q("zyxwv", "alder")) + load_rmp(teacher(9001, "Alder", "Zyxwv")) + PROPOSE + change + ";\n", expect_error="append-only")
scenario("a writer function is closed to other roles",
         "SET ROLE catalog_reader;\nSELECT professor_ratings.propose_matches('x');\n", expect_error="permission denied", user="postgres")
scenario("the reader role sees ratings but not the base tables",
         "SET ROLE catalog_reader;\nSELECT count(*) FROM professor_ratings.instructor_rating;\nSELECT count(*) FROM professor_ratings.instructor;\n", expect_error="permission denied for table instructor", user="postgres")


def run(sql, user):
    env = dict(os.environ)
    if user:
        env["PGUSER"] = user
    result = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At"], input="BEGIN;\n" + sql + "ROLLBACK;\n", capture_output=True, text=True, timeout=120, env=env)
    return result.returncode, (result.stdout + result.stderr).strip()


def main():
    if os.environ.get("PGDATABASE", "") in SHARED:
        sys.exit("refusing: run this against an isolated local database")
    failed = 0
    for name, sql, expect, user in SCENARIOS:
        code, out = run(sql, user)
        if expect is None:
            ok = code == 0
        else:
            ok = code != 0 and expect in out
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            failed += 1
            print("     expected:", expect or "success", "| got rc", code, "|", out[:300])
    print(f"{len(SCENARIOS) - failed}/{len(SCENARIOS)} scenarios passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
