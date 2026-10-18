-- Predicted quality and difficulty for a coming term (Spring 2027 first), per instructor, per instructor and course, per section and per course.
-- A prediction is computed by predict_ratings.py from the ratings in semester_rating_input and stored as one immutable run: the method, its
-- parameters, the backtest that chose them and the input hash are kept with it, so every shown number can be traced and reproduced.
-- A prediction is an estimate from past student opinion, not a fact about the course. Run after V1-V4.
\set ON_ERROR_STOP on

CREATE TABLE IF NOT EXISTS professor_ratings.prediction_run (
	run_id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	target_term_code   text NOT NULL CHECK (target_term_code ~ '^[0-9]{6}$'),          -- the catalog term the prediction is for (202710 = Spring 2027)
	target_term_year   integer NOT NULL CHECK (target_term_year BETWEEN 2000 AND 2100),
	target_term_season text NOT NULL CHECK (target_term_season IN ('SPRING', 'SUMMER', 'FALL')),
	method             text NOT NULL CHECK (method IN ('RECENCY_WEIGHTED_SHRUNK_MEAN')),
	parameters         jsonb NOT NULL CHECK (jsonb_typeof(parameters) = 'object'),
	backtest           jsonb NOT NULL CHECK (jsonb_typeof(backtest) = 'object'),
	ratings_used       integer NOT NULL CHECK (ratings_used >= 0),
	input_sha256       text NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
	created_at         timestamptz NOT NULL DEFAULT now(),
	created_by         text NOT NULL DEFAULT session_user
);

-- Only instructors with at least one earlier rating get a row; everyone else is "no history" in the views, never a made-up number.
CREATE TABLE IF NOT EXISTS professor_ratings.instructor_prediction (
	run_id            bigint NOT NULL REFERENCES professor_ratings.prediction_run,
	instructor_id     bigint NOT NULL REFERENCES professor_ratings.instructor,
	pred_quality      numeric(3,2) NOT NULL CHECK (pred_quality BETWEEN 1 AND 5),
	pred_difficulty   numeric(3,2) NOT NULL CHECK (pred_difficulty BETWEEN 1 AND 5),
	basis_ratings     integer NOT NULL CHECK (basis_ratings > 0),                 -- ratings used
	effective_ratings numeric(7,2) NOT NULL CHECK (effective_ratings > 0),        -- after recency weighting
	last_rated_term   text NOT NULL,
	confidence        text NOT NULL CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH')),
	PRIMARY KEY (run_id, instructor_id)
);

-- The same, for the courses the instructor teaches in the target term: leans toward ratings of that very course when students named it.
CREATE TABLE IF NOT EXISTS professor_ratings.instructor_course_prediction (
	run_id                   bigint NOT NULL,
	instructor_id            bigint NOT NULL,
	course_id                text NOT NULL CHECK (course_id ~ '^[A-Z]{2,4}-[0-9]{3}$'),
	pred_quality             numeric(3,2) NOT NULL CHECK (pred_quality BETWEEN 1 AND 5),
	pred_difficulty          numeric(3,2) NOT NULL CHECK (pred_difficulty BETWEEN 1 AND 5),
	course_ratings           integer NOT NULL CHECK (course_ratings >= 0),       -- earlier ratings that name this course
	effective_course_ratings numeric(7,2) NOT NULL CHECK (effective_course_ratings >= 0),
	PRIMARY KEY (run_id, instructor_id, course_id),
	FOREIGN KEY (run_id, instructor_id) REFERENCES professor_ratings.instructor_prediction
);

DO $$
DECLARE
	t text;
BEGIN
	FOREACH t IN ARRAY ARRAY['prediction_run', 'instructor_prediction', 'instructor_course_prediction'] LOOP
		IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = t || '_append_only' AND tgrelid = ('professor_ratings.' || t)::regclass) THEN
			EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON professor_ratings.%I FOR EACH ROW EXECUTE FUNCTION professor_ratings.refuse_change()', t || '_append_only', t);
		END IF;
		IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = t || '_no_truncate' AND tgrelid = ('professor_ratings.' || t)::regclass) THEN
			EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON professor_ratings.%I FOR EACH STATEMENT EXECUTE FUNCTION professor_ratings.refuse_change()', t || '_no_truncate', t);
		END IF;
	END LOOP;
END $$;

-- The only writer. payload: {targetTermCode, targetYear, targetSeason, method, parameters{}, backtest{}, ratingsUsed,
--   instructors:[{instructorId, quality, difficulty, basisRatings, effectiveRatings, lastRatedTerm, confidence,
--                 courses:[{courseId, quality, difficulty, courseRatings, effectiveCourseRatings}]}]}
CREATE OR REPLACE FUNCTION professor_ratings.record_predictions(payload jsonb, sha text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	rid bigint;
	expected integer;
	stored integer;
BEGIN
	IF jsonb_typeof(payload -> 'instructors') IS DISTINCT FROM 'array' THEN
		RAISE EXCEPTION 'payload needs an "instructors" array';
	END IF;
	INSERT INTO professor_ratings.prediction_run (target_term_code, target_term_year, target_term_season, method, parameters, backtest, ratings_used, input_sha256)
		VALUES (payload ->> 'targetTermCode', (payload ->> 'targetYear')::integer, payload ->> 'targetSeason', payload ->> 'method',
			payload -> 'parameters', payload -> 'backtest', (payload ->> 'ratingsUsed')::integer, sha)
		RETURNING run_id INTO rid;
	INSERT INTO professor_ratings.instructor_prediction (run_id, instructor_id, pred_quality, pred_difficulty, basis_ratings, effective_ratings, last_rated_term, confidence)
		SELECT rid, (x ->> 'instructorId')::bigint, (x ->> 'quality')::numeric, (x ->> 'difficulty')::numeric, (x ->> 'basisRatings')::integer,
			(x ->> 'effectiveRatings')::numeric, x ->> 'lastRatedTerm', x ->> 'confidence'
		FROM jsonb_array_elements(payload -> 'instructors') x;
	SELECT coalesce(sum(jsonb_array_length(x -> 'courses')), 0) INTO expected FROM jsonb_array_elements(payload -> 'instructors') x;
	INSERT INTO professor_ratings.instructor_course_prediction (run_id, instructor_id, course_id, pred_quality, pred_difficulty, course_ratings, effective_course_ratings)
		SELECT rid, (x ->> 'instructorId')::bigint, c ->> 'courseId', (c ->> 'quality')::numeric, (c ->> 'difficulty')::numeric, (c ->> 'courseRatings')::integer,
			(c ->> 'effectiveCourseRatings')::numeric
		FROM jsonb_array_elements(payload -> 'instructors') x, jsonb_array_elements(x -> 'courses') c;
	GET DIAGNOSTICS stored = ROW_COUNT;
	IF stored <> expected THEN
		RAISE EXCEPTION 'stored % course predictions but the payload has %', stored, expected;
	END IF;
	RETURN rid;
END $$;
REVOKE EXECUTE ON FUNCTION professor_ratings.record_predictions(jsonb, text) FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- The newest run per target term is the one shown.
CREATE OR REPLACE VIEW professor_ratings.latest_prediction_run AS
SELECT DISTINCT ON (target_term_code) run_id, target_term_code, target_term_year, target_term_season, method, parameters, backtest, ratings_used, created_at
FROM professor_ratings.prediction_run ORDER BY target_term_code, run_id DESC;

-- Per instructor (professor tab): the predicted level for the target term.
CREATE OR REPLACE VIEW professor_ratings.professor_prediction AS
SELECT r.target_term_code, r.target_term_year || ' ' || initcap(r.target_term_season) AS target_term_label, p.instructor_id, i.banner_name,
	p.pred_quality, p.pred_difficulty, p.basis_ratings, p.effective_ratings, p.last_rated_term, p.confidence, r.run_id, r.method
FROM professor_ratings.latest_prediction_run r
JOIN professor_ratings.instructor_prediction p ON p.run_id = r.run_id
JOIN professor_ratings.instructor i ON i.instructor_id = p.instructor_id;

-- Per section of the target term. A section with several instructors gets their average. status:
--   PREDICTED     every listed instructor has a prediction
--   PARTIAL       some do
--   NO_HISTORY    instructors are listed but none has any earlier rating
--   NO_INSTRUCTOR Banner lists only "Staff"/TBA
CREATE OR REPLACE VIEW professor_ratings.section_prediction AS
SELECT cs.term_code, r.target_term_year || ' ' || initcap(r.target_term_season) AS term_label, cs.crn, cs.offering_id, o.course_id, o.title,
	cs.section_code, cs.component_type,
	count(s.instructor_id)::integer AS instructors,
	string_agg(i.banner_name, '; ' ORDER BY i.banner_name) AS instructor_names,
	round(avg(coalesce(cp.pred_quality, ip.pred_quality)), 2) AS pred_quality,
	round(avg(coalesce(cp.pred_difficulty, ip.pred_difficulty)), 2) AS pred_difficulty,
	coalesce(sum(ip.basis_ratings), 0)::integer AS basis_ratings,
	coalesce(sum(cp.course_ratings), 0)::integer AS course_ratings,
	CASE min(CASE ip.confidence WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'HIGH' THEN 3 END) WHEN 1 THEN 'LOW' WHEN 2 THEN 'MEDIUM' WHEN 3 THEN 'HIGH' END AS confidence,
	CASE WHEN count(s.instructor_id) = 0 THEN 'NO_INSTRUCTOR'
		WHEN count(ip.instructor_id) = 0 THEN 'NO_HISTORY'
		WHEN count(ip.instructor_id) < count(s.instructor_id) THEN 'PARTIAL'
		ELSE 'PREDICTED' END AS status,
	r.run_id
FROM professor_ratings.latest_prediction_run r
JOIN catalog_research.section cs ON cs.term_code = r.target_term_code
JOIN catalog_research.offering o ON o.term_code = cs.term_code AND o.offering_id = cs.offering_id
LEFT JOIN professor_ratings.instructor_section s ON s.term_code = cs.term_code AND s.crn = cs.crn
LEFT JOIN professor_ratings.instructor i ON i.instructor_id = s.instructor_id
LEFT JOIN professor_ratings.instructor_prediction ip ON ip.run_id = r.run_id AND ip.instructor_id = s.instructor_id
LEFT JOIN professor_ratings.instructor_course_prediction cp ON cp.run_id = r.run_id AND cp.instructor_id = s.instructor_id AND cp.course_id = o.course_id
GROUP BY cs.term_code, r.target_term_year, r.target_term_season, cs.crn, cs.offering_id, o.course_id, o.title, cs.section_code, cs.component_type, r.run_id;

-- Per course offering of the target term (what a course list shows): the average over its lecture sections (all sections when it has no
-- lecture), the range across those sections, and how many of them have a prediction.
CREATE OR REPLACE VIEW professor_ratings.course_prediction AS
WITH used AS (
	SELECT sp.*, bool_or(sp.component_type = 'LECTURE') OVER (PARTITION BY sp.term_code, sp.offering_id) AS has_lecture
	FROM professor_ratings.section_prediction sp
)
SELECT term_code, term_label, offering_id, course_id, title,
	count(*) FILTER (WHERE component_type = 'LECTURE' OR NOT has_lecture)::integer AS sections,
	count(*) FILTER (WHERE (component_type = 'LECTURE' OR NOT has_lecture) AND pred_quality IS NOT NULL)::integer AS sections_predicted,
	round(avg(pred_quality) FILTER (WHERE component_type = 'LECTURE' OR NOT has_lecture), 2) AS pred_quality,
	round(avg(pred_difficulty) FILTER (WHERE component_type = 'LECTURE' OR NOT has_lecture), 2) AS pred_difficulty,
	min(pred_quality) FILTER (WHERE component_type = 'LECTURE' OR NOT has_lecture) AS min_section_quality,
	max(pred_quality) FILTER (WHERE component_type = 'LECTURE' OR NOT has_lecture) AS max_section_quality,
	CASE WHEN count(*) FILTER (WHERE (component_type = 'LECTURE' OR NOT has_lecture) AND pred_quality IS NOT NULL) = 0 THEN 'NO_DATA'
		WHEN count(*) FILTER (WHERE (component_type = 'LECTURE' OR NOT has_lecture) AND pred_quality IS NULL) > 0 THEN 'PARTIAL'
		ELSE 'PREDICTED' END AS status,
	min(run_id) AS run_id
FROM used
GROUP BY term_code, term_label, offering_id, course_id, title;
