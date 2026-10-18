-- Read-only structural assertions for professor_ratings. Raises an exception on any problem.
\set ON_ERROR_STOP on
DO $$
BEGIN
	IF (SELECT array_agg(c.relname || ':' || c.relkind::text ORDER BY c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'professor_ratings' AND c.relkind IN ('r', 'v'))
		IS DISTINCT FROM ARRAY['accepted_match_context:v', 'course_prediction:v', 'current_instructor_match:v', 'import_run:r', 'instructor:r', 'instructor_course_prediction:r',
			'instructor_prediction:r', 'instructor_rating:v', 'instructor_rmp_match:r', 'instructor_section:r',
			'instructor_section_orphan:v', 'instructor_term_rating:v', 'instructor_year_rating:v', 'latest_prediction_run:v', 'latest_rmp_observation:v', 'match_candidate:v',
			'prediction_run:r', 'professor_comment:v', 'professor_overview:v', 'professor_prediction:v', 'rating_coverage:v', 'rating_term:v', 'review_queue:v', 'rmp_rating:r', 'rmp_rating_observation:r', 'rmp_teacher:r',
			'section_instructor_rating:v', 'section_prediction:v', 'section_without_instructor:v', 'semester_rating_input:v', 'student_rating:r'] THEN
		RAISE EXCEPTION 'professor_ratings must contain exactly 11 tables and 20 views';
	END IF;
	-- Runs, observations and match decisions are append-only (update/delete and truncate are refused).
	IF (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'professor_ratings' AND NOT t.tgisinternal AND t.tgname LIKE '%\_append\_only') <> 6
		OR (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'professor_ratings' AND NOT t.tgisinternal AND t.tgname LIKE '%\_no\_truncate') <> 6 THEN
		RAISE EXCEPTION 'import_run, rmp_rating_observation, instructor_rmp_match and the three prediction tables need their append-only and no-truncate triggers';
	END IF;
	-- No view is writable.
	IF EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema = 'professor_ratings' AND (is_updatable <> 'NO' OR is_insertable_into <> 'NO')) THEN
		RAISE EXCEPTION 'a professor_ratings view is writable';
	END IF;
	-- The eight writers exist and nobody but the owner can run them.
	IF (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
			WHERE n.nspname = 'professor_ratings' AND p.proname IN ('import_instructors', 'import_rmp_observations', 'import_rmp_ratings', 'propose_matches', 'review_match', 'submit_student_rating',
				'moderate_student_rating', 'record_predictions')) <> 8 THEN
		RAISE EXCEPTION 'one of the eight writer functions is missing';
	END IF;
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
			WHERE n.nspname = 'professor_ratings' AND p.proname IN ('import_instructors', 'import_rmp_observations', 'import_rmp_ratings', 'propose_matches', 'review_match', 'submit_student_rating',
					'moderate_student_rating', 'record_predictions')
				AND (p.proacl IS NULL OR has_function_privilege('public', p.oid, 'EXECUTE'))) THEN
		RAISE EXCEPTION 'a writer function can be executed by PUBLIC';
	END IF;
	-- One-way dependency: no object outside professor_ratings may depend on it (eligibility, tiers, approvals and serving never read ratings).
	IF EXISTS (SELECT 1 FROM pg_depend d
			JOIN pg_rewrite rw ON rw.oid = d.objid AND d.classid = 'pg_rewrite'::regclass
			JOIN pg_class v ON v.oid = rw.ev_class JOIN pg_namespace vn ON vn.oid = v.relnamespace
			JOIN pg_class t ON t.oid = d.refobjid AND d.refclassid = 'pg_class'::regclass JOIN pg_namespace tn ON tn.oid = t.relnamespace
			WHERE tn.nspname = 'professor_ratings' AND vn.nspname <> 'professor_ratings') THEN
		RAISE EXCEPTION 'a view outside professor_ratings depends on professor_ratings';
	END IF;
	IF EXISTS (SELECT 1 FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			JOIN pg_class r ON r.oid = k.confrelid JOIN pg_namespace rn ON rn.oid = r.relnamespace
			WHERE k.contype = 'f' AND ((n.nspname = 'professor_ratings') <> (rn.nspname = 'professor_ratings'))) THEN
		RAISE EXCEPTION 'a foreign key crosses the professor_ratings boundary';
	END IF;
	-- RMP data carries no review text: the only free-text comment is the one a student writes in the app (student_rating and its view).
	IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'professor_ratings'
			AND column_name ~* '(comment|review|text|body|tag|grade|attendance|textbook)'
			AND NOT (table_name IN ('student_rating', 'professor_comment') AND column_name = 'comment')) THEN
		RAISE EXCEPTION 'professor_ratings must not store RMP review text (only app comments, in student_rating.comment)';
	END IF;
END $$;
SELECT 'professor_ratings structure OK' AS result;
