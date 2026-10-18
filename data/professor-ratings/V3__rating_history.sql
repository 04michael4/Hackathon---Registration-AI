-- Rating history: each RMP rating's posting date and numbers, so a professor's ratings can be followed semester by semester.
-- Stored per rating: RMP's rating id, posting date, quality, difficulty, would-take-again, the course label the student typed, online flag.
-- NOT stored: the comment, tags, grade, attendance or anything else a student wrote about themselves (the structure check refuses such columns).
-- Run after V1 and V2.
\set ON_ERROR_STOP on

-- A cache of RMP, not an audit log: a fetch REPLACES the ratings of every professor it covers, so a rating RMP has removed disappears too.
-- Provenance is the run that fetched it (import_run, kind RMP_RATINGS).
CREATE TABLE IF NOT EXISTS professor_ratings.rmp_rating (
	rmp_rating_id    integer PRIMARY KEY CHECK (rmp_rating_id > 0),
	rmp_legacy_id    integer NOT NULL REFERENCES professor_ratings.rmp_teacher,
	posted_on        date NOT NULL CHECK (posted_on BETWEEN DATE '2000-01-01' AND DATE '2100-01-01'),
	quality          numeric(2,1) NOT NULL CHECK (quality BETWEEN 1 AND 5),   -- RMP's overall quality: the mean of its clarity and helpfulness scores
	difficulty       smallint NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
	would_take_again boolean,                                               -- NULL when the student did not answer
	class_label      text CHECK (length(class_label) <= 40),                -- as typed by the student (e.g. "CSE17"); not a verified course id
	is_online        boolean,
	run_id           bigint NOT NULL REFERENCES professor_ratings.import_run
);
CREATE INDEX IF NOT EXISTS rmp_rating_teacher_idx ON professor_ratings.rmp_rating (rmp_legacy_id, posted_on);

-- Which semester a rating most likely describes. RMP records when a review was POSTED, not when the course was taken, so this is an
-- approximation tied to Lehigh's calendar: reviews written over winter break describe the fall term that just ended, and reviews written
-- right after spring finals describe spring.
--   Jan 1 - Jan 19  -> Fall of the previous year      Jan 20 - Jun 15 -> Spring
--   Jun 16 - Aug 24 -> Summer                         Aug 25 - Dec 31 -> Fall
-- Change the rule here only; every view uses this function.
CREATE OR REPLACE FUNCTION professor_ratings.term_of_posting(posted date, OUT term_year integer, OUT term_season text, OUT term_order integer)
LANGUAGE sql IMMUTABLE AS $$
	SELECT y, s, y * 10 + CASE s WHEN 'SPRING' THEN 1 WHEN 'SUMMER' THEN 2 ELSE 3 END
	FROM (SELECT
		CASE WHEN to_char(posted, 'MMDD') < '0120' THEN extract(year FROM posted)::integer - 1 ELSE extract(year FROM posted)::integer END AS y,
		CASE WHEN to_char(posted, 'MMDD') < '0120' THEN 'FALL'
			WHEN to_char(posted, 'MMDD') <= '0615' THEN 'SPRING'
			WHEN to_char(posted, 'MMDD') <= '0824' THEN 'SUMMER'
			ELSE 'FALL' END AS s) t
$$;

-- Replaces the stored ratings of every professor in the payload.
-- payload: {"fetchedAt":"<timestamp>","teachers":[{"legacyId":1,"ratings":[{ratingId,postedOn,quality,difficulty,wouldTakeAgain,classLabel,isOnline}]}]}
CREATE OR REPLACE FUNCTION professor_ratings.import_rmp_ratings(payload jsonb, label text, sha text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	rid bigint;
	expected integer;
	stored integer;
BEGIN
	IF jsonb_typeof(payload -> 'teachers') IS DISTINCT FROM 'array' THEN
		RAISE EXCEPTION 'payload needs a "teachers" array';
	END IF;
	SELECT coalesce(sum(jsonb_array_length(t -> 'ratings')), 0) INTO expected FROM jsonb_array_elements(payload -> 'teachers') t;
	INSERT INTO professor_ratings.import_run (kind, label, input_sha256, row_count) VALUES ('RMP_RATINGS', label, sha, expected) RETURNING run_id INTO rid;
	DELETE FROM professor_ratings.rmp_rating WHERE rmp_legacy_id IN (SELECT (t ->> 'legacyId')::integer FROM jsonb_array_elements(payload -> 'teachers') t);
	INSERT INTO professor_ratings.rmp_rating (rmp_rating_id, rmp_legacy_id, posted_on, quality, difficulty, would_take_again, class_label, is_online, run_id)
		SELECT (r ->> 'ratingId')::integer, (t ->> 'legacyId')::integer, (r ->> 'postedOn')::date, (r ->> 'quality')::numeric, (r ->> 'difficulty')::smallint,
			(r ->> 'wouldTakeAgain')::boolean, nullif(btrim(r ->> 'classLabel'), ''), (r ->> 'isOnline')::boolean, rid
		FROM jsonb_array_elements(payload -> 'teachers') t, jsonb_array_elements(t -> 'ratings') r;
	GET DIAGNOSTICS stored = ROW_COUNT;
	IF stored <> expected THEN
		RAISE EXCEPTION 'stored % ratings but the payload has %', stored, expected;
	END IF;
	RETURN rid;
END $$;
REVOKE EXECUTE ON FUNCTION professor_ratings.import_rmp_ratings(jsonb, text, text) FROM PUBLIC;

-- Every stored rating with its semester.
CREATE OR REPLACE VIEW professor_ratings.rating_term AS
SELECT r.rmp_rating_id, r.rmp_legacy_id, r.posted_on, r.quality, r.difficulty, r.would_take_again, r.class_label, r.is_online,
	p.term_year, p.term_season, p.term_order, p.term_year || ' ' || initcap(p.term_season) AS term_label
FROM professor_ratings.rmp_rating r CROSS JOIN LATERAL professor_ratings.term_of_posting(r.posted_on) p;

-- The per-semester and per-year views live in V4: they combine these RMP ratings with ratings submitted in the app.
