-- Ratings and comments submitted by students in OUR app, and the views a "Professors" tab would read. Basic structure: no endpoint or UI uses
-- it yet. Run after V1-V3.
--
-- An app rating names the semester the student actually took the course (unlike RMP, which only has a posting date), so it is stored as
-- term_year + term_season directly. Ratings accumulate: every semester view counts RMP and app ratings together, with the count per source.
-- Comments are student free text: show them as plain text, never as HTML, and treat them as untrusted data if an AI prompt ever reads them.
-- RMP comments are NOT stored (see ADR-0006); only comments written in the app are.
\set ON_ERROR_STOP on

CREATE TABLE IF NOT EXISTS professor_ratings.student_rating (
	student_rating_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	student_ref       text NOT NULL CHECK (student_ref ~ '^[A-Za-z0-9_-]{8,128}$'),   -- opaque app user id; never a name or an e-mail address
	instructor_id     bigint NOT NULL REFERENCES professor_ratings.instructor,
	term_year         integer NOT NULL CHECK (term_year BETWEEN 2000 AND 2100),
	term_season       text NOT NULL CHECK (term_season IN ('SPRING', 'SUMMER', 'FALL')),
	course_id         text CHECK (course_id ~ '^[A-Z]{2,4}-[0-9]{3}$'),             -- e.g. CSE-017; optional, no foreign key (past courses are not in the catalog)
	quality           smallint NOT NULL CHECK (quality BETWEEN 1 AND 5),
	difficulty        smallint NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
	would_take_again  boolean,
	comment           text CHECK (comment IS NULL OR length(btrim(comment)) BETWEEN 1 AND 1000),
	status            text NOT NULL DEFAULT 'VISIBLE' CHECK (status IN ('VISIBLE', 'COMMENT_HIDDEN', 'REMOVED')),
	submitted_at      timestamptz NOT NULL DEFAULT now(),
	updated_at        timestamptz NOT NULL DEFAULT now(),
	moderated_by      text,
	moderated_at      timestamptz,
	moderation_reason text,
	CHECK ((moderated_by IS NULL) = (moderated_at IS NULL))
);
-- One rating per student, professor, semester and course; submitting again replaces it.
CREATE UNIQUE INDEX IF NOT EXISTS student_rating_one_per_student_idx
	ON professor_ratings.student_rating (student_ref, instructor_id, term_year, term_season, coalesce(course_id, ''));
CREATE INDEX IF NOT EXISTS student_rating_instructor_idx ON professor_ratings.student_rating (instructor_id, term_year, term_season);

-- The only way a rating is written. Returns the rating id. A resubmission replaces the scores and comment but keeps a moderator's status.
-- SECURITY DEFINER so a backend role can be given EXECUTE on this function alone (grant_writer.sql) without any table privilege;
-- search_path is pinned and every name is schema-qualified.
CREATE OR REPLACE FUNCTION professor_ratings.submit_student_rating(p_student_ref text, p_instructor_id bigint, p_term_year integer, p_term_season text,
	p_course_id text, p_quality integer, p_difficulty integer, p_would_take_again boolean, p_comment text) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
	rid bigint;
	clean_comment text := nullif(btrim(p_comment), '');
BEGIN
	IF p_term_year > extract(year FROM now())::integer THEN
		RAISE EXCEPTION 'a rating cannot be for a future year';
	END IF;
	INSERT INTO professor_ratings.student_rating (student_ref, instructor_id, term_year, term_season, course_id, quality, difficulty, would_take_again, comment)
		VALUES (p_student_ref, p_instructor_id, p_term_year, upper(p_term_season), p_course_id, p_quality, p_difficulty, p_would_take_again, clean_comment)
		ON CONFLICT (student_ref, instructor_id, term_year, term_season, coalesce(course_id, '')) DO UPDATE
			SET quality = EXCLUDED.quality, difficulty = EXCLUDED.difficulty, would_take_again = EXCLUDED.would_take_again,
				comment = EXCLUDED.comment, updated_at = now()
		RETURNING student_rating_id INTO rid;
	RETURN rid;
END $$;

-- Moderation: COMMENT_HIDDEN keeps the scores and hides the text; REMOVED drops the rating from every average; VISIBLE restores it.
CREATE OR REPLACE FUNCTION professor_ratings.moderate_student_rating(p_student_rating_id bigint, p_status text, p_moderator text, p_reason text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
	IF coalesce(length(btrim(p_moderator)), 0) < 2 OR coalesce(length(btrim(p_reason)), 0) < 3 THEN
		RAISE EXCEPTION 'moderation needs a moderator and a reason';
	END IF;
	UPDATE professor_ratings.student_rating
		SET status = p_status, moderated_by = p_moderator, moderated_at = now(), moderation_reason = p_reason
		WHERE student_rating_id = p_student_rating_id;
	IF NOT FOUND THEN
		RAISE EXCEPTION 'no student rating %', p_student_rating_id;
	END IF;
END $$;

REVOKE EXECUTE ON FUNCTION professor_ratings.submit_student_rating(text, bigint, integer, text, text, integer, integer, boolean, text),
	professor_ratings.moderate_student_rating(bigint, text, text, text) FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Every rating that counts, from both sources, with its semester. RMP: accepted matches only, semester from the posting date (approximate).
-- App: not REMOVED, semester as the student gave it. semester_index: year * 2, + 0.5 for summer, + 1 for fall (consecutive semesters differ by 1).
-- course_key: the course in catalog form (CSE-017). App ratings give it directly; for RMP it is parsed from the label the student typed
-- ("CSE17", "cse 017") and is NULL when the label does not look like a course.
CREATE OR REPLACE FUNCTION professor_ratings.course_key_of_label(label text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
	SELECT m[1] || '-' || lpad(m[2], 3, '0')
	FROM regexp_match(upper(regexp_replace(coalesce(label, ''), '[^A-Za-z0-9]', '', 'g')), '^([A-Z]{2,4})0*([0-9]{1,3})$') m
$$;

CREATE OR REPLACE VIEW professor_ratings.semester_rating_input AS
SELECT m.instructor_id, 'RMP'::text AS source, rt.term_year, rt.term_season, rt.term_order, rt.term_label,
	rt.quality::numeric AS quality, rt.difficulty::numeric AS difficulty, rt.would_take_again,
	rt.term_year * 2 + CASE rt.term_season WHEN 'SPRING' THEN 0 WHEN 'SUMMER' THEN 0.5 ELSE 1 END AS semester_index,
	professor_ratings.course_key_of_label(rt.class_label) AS course_key
FROM professor_ratings.current_instructor_match m
JOIN professor_ratings.rating_term rt ON rt.rmp_legacy_id = m.rmp_legacy_id
WHERE m.status IN ('AUTO_ACCEPTED', 'CONFIRMED')
UNION ALL
SELECT s.instructor_id, 'APP', s.term_year, s.term_season,
	s.term_year * 10 + CASE s.term_season WHEN 'SPRING' THEN 1 WHEN 'SUMMER' THEN 2 ELSE 3 END,
	s.term_year || ' ' || initcap(s.term_season), s.quality, s.difficulty, s.would_take_again,
	s.term_year * 2 + CASE s.term_season WHEN 'SPRING' THEN 0 WHEN 'SUMMER' THEN 0.5 ELSE 1 END,
	s.course_id
FROM professor_ratings.student_rating s
WHERE s.status <> 'REMOVED';

-- One row per instructor and semester that has any rating, with the previous rated semester for a trend line.
-- semesters_apart: 1 = consecutive (Fall -> Spring or Spring -> Fall); a summer counts as half a semester.
CREATE OR REPLACE VIEW professor_ratings.instructor_term_rating AS
WITH per_term AS (
	SELECT instructor_id, term_year, term_season, term_order, term_label,
		count(*)::integer AS num_ratings,
		count(*) FILTER (WHERE source = 'RMP')::integer AS num_rmp_ratings,
		count(*) FILTER (WHERE source = 'APP')::integer AS num_app_ratings,
		round(avg(quality), 2) AS avg_quality,
		round(avg(difficulty), 2) AS avg_difficulty,
		round(100.0 * avg(CASE WHEN would_take_again THEN 1 ELSE 0 END) FILTER (WHERE would_take_again IS NOT NULL), 1) AS would_take_again_pct,
		term_year * 2 + CASE term_season WHEN 'SPRING' THEN 0 WHEN 'SUMMER' THEN 0.5 ELSE 1 END AS semester_index
	FROM professor_ratings.semester_rating_input
	GROUP BY instructor_id, term_year, term_season, term_order, term_label
)
SELECT i.banner_name, p.instructor_id, p.term_year, p.term_season, p.term_order, p.term_label,
	p.num_ratings, p.num_rmp_ratings, p.num_app_ratings, p.avg_quality, p.avg_difficulty, p.would_take_again_pct,
	CASE WHEN p.num_ratings < 3 THEN 'LOW_SAMPLE' ELSE 'OK' END AS sample_note,
	lag(p.term_label) OVER w AS prev_term_label,
	p.semester_index - lag(p.semester_index) OVER w AS semesters_apart,
	lag(p.avg_quality) OVER w AS prev_avg_quality,
	p.avg_quality - lag(p.avg_quality) OVER w AS quality_change,
	p.avg_difficulty - lag(p.avg_difficulty) OVER w AS difficulty_change,
	(p.num_ratings >= 3 AND lag(p.num_ratings) OVER w >= 3) AS change_is_reliable
FROM per_term p JOIN professor_ratings.instructor i ON i.instructor_id = p.instructor_id
WINDOW w AS (PARTITION BY p.instructor_id ORDER BY p.term_order);

-- The same per calendar year of the semester (Spring, Summer and Fall of 2024 are year 2024).
CREATE OR REPLACE VIEW professor_ratings.instructor_year_rating AS
WITH per_year AS (
	SELECT instructor_id, term_year,
		count(*)::integer AS num_ratings,
		count(*) FILTER (WHERE source = 'RMP')::integer AS num_rmp_ratings,
		count(*) FILTER (WHERE source = 'APP')::integer AS num_app_ratings,
		round(avg(quality), 2) AS avg_quality,
		round(avg(difficulty), 2) AS avg_difficulty,
		round(100.0 * avg(CASE WHEN would_take_again THEN 1 ELSE 0 END) FILTER (WHERE would_take_again IS NOT NULL), 1) AS would_take_again_pct
	FROM professor_ratings.semester_rating_input
	GROUP BY instructor_id, term_year
)
SELECT i.banner_name, p.*,
	CASE WHEN p.num_ratings < 3 THEN 'LOW_SAMPLE' ELSE 'OK' END AS sample_note,
	lag(p.term_year) OVER w AS prev_year,
	lag(p.avg_quality) OVER w AS prev_avg_quality,
	p.avg_quality - lag(p.avg_quality) OVER w AS quality_change,
	p.avg_difficulty - lag(p.avg_difficulty) OVER w AS difficulty_change,
	(p.num_ratings >= 3 AND lag(p.num_ratings) OVER w >= 3) AS change_is_reliable
FROM per_year p JOIN professor_ratings.instructor i ON i.instructor_id = p.instructor_id
WINDOW w AS (PARTITION BY p.instructor_id ORDER BY p.term_year);

-- Comments other students may see: app ratings that are VISIBLE and have a comment. Order by submitted_at DESC when reading.
CREATE OR REPLACE VIEW professor_ratings.professor_comment AS
SELECT s.student_rating_id, s.instructor_id, i.banner_name, s.term_year, s.term_season, s.term_year || ' ' || initcap(s.term_season) AS term_label,
	s.course_id, s.quality, s.difficulty, s.would_take_again, s.comment, s.submitted_at, s.updated_at
FROM professor_ratings.student_rating s JOIN professor_ratings.instructor i ON i.instructor_id = s.instructor_id
WHERE s.status = 'VISIBLE' AND s.comment IS NOT NULL;

-- The header of a professor tab: overall numbers from both sources, the RMP link for attribution, and what the professor teaches this term.
CREATE OR REPLACE VIEW professor_ratings.professor_overview AS
SELECT i.instructor_id, i.banner_name,
	coalesce(m.status, 'NOT_MATCHED') AS rmp_match_status,
	CASE WHEN m.status IN ('AUTO_ACCEPTED', 'CONFIRMED') THEN 'https://www.ratemyprofessors.com/professor/' || m.rmp_legacy_id END AS rmp_url,
	r.avg_rating AS rmp_avg_rating, r.avg_difficulty AS rmp_avg_difficulty, r.would_take_again_pct AS rmp_would_take_again_pct, r.num_ratings AS rmp_num_ratings,
	a.app_avg_quality, a.app_avg_difficulty, a.app_num_ratings,
	(SELECT string_agg(DISTINCT cs.offering_id, ', ' ORDER BY cs.offering_id)
		FROM professor_ratings.instructor_section s JOIN catalog_research.section cs ON cs.term_code = s.term_code AND cs.crn = s.crn
		WHERE s.instructor_id = i.instructor_id) AS teaches_now
FROM professor_ratings.instructor i
LEFT JOIN professor_ratings.current_instructor_match m ON m.instructor_id = i.instructor_id
LEFT JOIN professor_ratings.instructor_rating r ON r.instructor_id = i.instructor_id
LEFT JOIN (SELECT instructor_id, round(avg(quality), 2) AS app_avg_quality, round(avg(difficulty), 2) AS app_avg_difficulty, count(*)::integer AS app_num_ratings
		FROM professor_ratings.student_rating WHERE status <> 'REMOVED' GROUP BY instructor_id) a ON a.instructor_id = i.instructor_id;
