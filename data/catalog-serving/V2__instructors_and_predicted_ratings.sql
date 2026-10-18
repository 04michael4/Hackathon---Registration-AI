-- catalog_serving, part 2: the instructors of each served section and the PREDICTED quality/difficulty (professor_ratings) for the term.
-- Needs professor_ratings (V1-V5) installed. One-way: catalog_serving reads professor_ratings; professor_ratings never reads catalog_serving.
-- Ratings are student opinion predicted from earlier terms (ADR-0006), shown with a confidence and the number of ratings behind them.
-- Instructor names are real people's names: expose them only inside the team's own app (ADR-0006 point 2).
\set ON_ERROR_STOP on

-- One row per instructor of a served section. Predicted values: the instructor's value for THIS course when students named it, else the
-- instructor's overall value; NULL when the instructor has no earlier rating (never a made-up number).
DROP VIEW IF EXISTS catalog_serving.course_rating;
DROP VIEW IF EXISTS catalog_serving.section_instructor;

CREATE VIEW catalog_serving.section_instructor AS
SELECT so.term_code, so.course_id, so.crn, i.banner_name::text AS instructor_name, s.role::text AS role,
	coalesce(cp.pred_quality, ip.pred_quality)::numeric(3,2) AS pred_quality,
	coalesce(cp.pred_difficulty, ip.pred_difficulty)::numeric(3,2) AS pred_difficulty,
	ip.confidence::text AS confidence,
	ip.basis_ratings::integer AS rating_count,
	(cp.course_ratings IS NOT NULL AND cp.course_ratings > 0) AS course_specific
FROM catalog_serving.section_offering so
JOIN professor_ratings.instructor_section s ON s.term_code = so.term_code AND s.crn = so.crn
JOIN professor_ratings.instructor i ON i.instructor_id = s.instructor_id
LEFT JOIN professor_ratings.latest_prediction_run r ON r.target_term_code = so.term_code
LEFT JOIN professor_ratings.instructor_prediction ip ON ip.run_id = r.run_id AND ip.instructor_id = s.instructor_id
LEFT JOIN professor_ratings.instructor_course_prediction cp ON cp.run_id = r.run_id AND cp.instructor_id = s.instructor_id AND cp.course_id = so.course_id;

-- One row per served course: the average over its lecture sections (professor_ratings.course_prediction), with the lowest confidence among them.
CREATE VIEW catalog_serving.course_rating AS
SELECT c.term_code, c.course_id, p.pred_quality::numeric(3,2) AS pred_quality, p.pred_difficulty::numeric(3,2) AS pred_difficulty,
	p.sections::integer AS sections, p.sections_predicted::integer AS sections_predicted, p.status::text AS status,
	(SELECT CASE min(CASE sp.confidence WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'HIGH' THEN 3 END) WHEN 1 THEN 'LOW' WHEN 2 THEN 'MEDIUM' WHEN 3 THEN 'HIGH' END
		FROM professor_ratings.section_prediction sp
		WHERE sp.term_code = c.term_code AND sp.offering_id = c.course_id AND sp.component_type = 'LECTURE' AND sp.pred_quality IS NOT NULL)::text AS confidence
FROM catalog_serving.course_offering c
JOIN professor_ratings.course_prediction p ON p.term_code = c.term_code AND p.offering_id = c.course_id
WHERE p.status <> 'NO_DATA';
