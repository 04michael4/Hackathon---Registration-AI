\set ON_ERROR_STOP on
SET search_path TO cse007_pdf_demo;

-- This verifier checks the single-course fixture, not general scheduling or
-- prerequisite eligibility. Subtransactions roll back intentional bad writes.
DO $$
DECLARE
	pairs text[];
	after_ten text[];
	friday_free integer;
BEGIN
	IF (SELECT count(*) FROM course) <> 1
		OR (SELECT count(*) FROM term) <> 1
		OR (SELECT count(*) FROM section) <> 6
		OR (SELECT count(*) FROM meeting) <> 9
		OR (SELECT count(*) FROM section_link) <> 4
		OR (SELECT count(*) FROM student_profile) <> 1
		OR (SELECT count(*) FROM schedule_preference) <> 1 THEN
		RAISE EXCEPTION 'Unexpected fixture counts';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM course WHERE code = 'CSE-007' AND credits = 4)
		OR EXISTS (SELECT 1 FROM section WHERE source_status <> 'DEMO_SIMULATED' OR source_url IS NOT NULL OR verified_at IS NOT NULL)
		OR EXISTS (SELECT 1 FROM meeting WHERE source_status <> 'DEMO_SIMULATED' OR source_url IS NOT NULL OR verified_at IS NOT NULL) THEN
		RAISE EXCEPTION 'Catalog/provenance mismatch';
	END IF;
	IF EXISTS (SELECT 1 FROM prerequisite_rule)
		OR EXISTS (SELECT 1 FROM generated_schedule)
		OR EXISTS (SELECT 1 FROM generated_schedule_section) THEN
		RAISE EXCEPTION 'Do not invent prerequisites or finalized scores';
	END IF;
	IF EXISTS (SELECT 1 FROM section s WHERE s.meeting_status = 'SCHEDULED'
		AND NOT EXISTS (SELECT 1 FROM meeting m WHERE m.section_id = s.section_id)) THEN
		RAISE EXCEPTION 'SCHEDULED section has no meetings';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM section s WHERE s.section_code = 'DEMO-L03-TBA'
		AND s.meeting_status = 'TBA' AND s.delivery_type = 'UNKNOWN'
		AND NOT EXISTS (SELECT 1 FROM meeting m WHERE m.section_id = s.section_id)) THEN
		RAISE EXCEPTION 'Missing incomplete/TBA test case';
	END IF;
	IF EXISTS (
		SELECT 1 FROM section_link l
		JOIN section a ON a.section_id = l.primary_section_id
		JOIN section b ON b.section_id = l.compatible_section_id
		WHERE a.course_id <> b.course_id OR a.term_id <> b.term_id
			OR a.component_type <> 'LECTURE' OR b.component_type <> 'LAB'
			OR l.required_component_group <> 'LAB'
	) THEN
		RAISE EXCEPTION 'Invalid term/course/component link';
	END IF;
	IF EXISTS (
		WITH RECURSIVE paths AS (
			SELECT primary_section_id AS start_id, compatible_section_id AS next_id,
				ARRAY[primary_section_id, compatible_section_id] AS visited, false AS cycle
			FROM section_link
			UNION ALL
			SELECT p.start_id, l.compatible_section_id, p.visited || l.compatible_section_id,
				l.compatible_section_id = ANY(p.visited)
			FROM paths p JOIN section_link l ON l.primary_section_id = p.next_id
			WHERE NOT p.cycle
		) SELECT 1 FROM paths WHERE cycle
	) THEN
		RAISE EXCEPTION 'Section-link cycle';
	END IF;
	IF EXISTS (SELECT 1 FROM section_link l
		JOIN section a ON a.section_id = l.primary_section_id
		JOIN section b ON b.section_id = l.compatible_section_id
		JOIN course c ON c.course_id = a.course_id
		WHERE coalesce(a.credits, c.credits) + coalesce(b.credits, c.credits) IS DISTINCT FROM 4::numeric
			OR b.credits IS DISTINCT FROM 0::numeric) THEN
		RAISE EXCEPTION 'Lecture/lab credits were double-counted';
	END IF;
	SELECT array_agg(a.section_code || '/' || b.section_code ORDER BY a.section_code, b.section_code),
		array_agg(a.section_code || '/' || b.section_code ORDER BY a.section_code, b.section_code)
			FILTER (WHERE NOT EXISTS (SELECT 1 FROM meeting m
				WHERE m.section_id IN (a.section_id, b.section_id) AND m.start_time < TIME '10:00')),
		count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM meeting m
			WHERE m.section_id IN (a.section_id, b.section_id) AND m.day_of_week = 5
				AND m.start_time < TIME '12:00' AND m.end_time > TIME '08:00'))
	INTO pairs, after_ten, friday_free
	FROM section_link l JOIN section a ON a.section_id = l.primary_section_id
	JOIN section b ON b.section_id = l.compatible_section_id
	WHERE a.meeting_status = 'SCHEDULED' AND b.meeting_status = 'SCHEDULED'
		AND a.delivery_type <> 'UNKNOWN' AND b.delivery_type <> 'UNKNOWN'
		AND NOT EXISTS (SELECT 1 FROM meeting ma JOIN meeting mb ON ma.day_of_week = mb.day_of_week
			AND ma.start_time < mb.end_time AND mb.start_time < ma.end_time
			WHERE ma.section_id = a.section_id AND mb.section_id = b.section_id);
	IF pairs IS DISTINCT FROM ARRAY['DEMO-L01/DEMO-B01','DEMO-L01/DEMO-B02','DEMO-L02/DEMO-B02']
		OR after_ten IS DISTINCT FROM ARRAY['DEMO-L02/DEMO-B02'] OR friday_free <> 0 THEN
		RAISE EXCEPTION 'Unexpected feasible pairs: %, after 10: %, Friday free: %', pairs, after_ten, friday_free;
	END IF;
	-- A single selected lecture misses a lab; selecting both alternatives exceeds
	-- exactly-one. The actual allowed group is checked here against fixed IDs.
	IF (SELECT count(*) FROM section_link WHERE primary_section_id = '00000007-0002-4000-8000-000000000001') <> 2
		OR EXISTS (SELECT 1 FROM section_link
			WHERE primary_section_id = '00000007-0002-4000-8000-000000000002'
			AND compatible_section_id = '00000007-0002-4000-8000-000000000003') THEN
		RAISE EXCEPTION 'Unexpected LAB group alternatives';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM meeting a JOIN meeting b ON a.day_of_week = b.day_of_week
		AND a.start_time < b.end_time AND b.start_time < a.end_time
		WHERE a.section_id = '00000007-0002-4000-8000-000000000002'
		AND b.section_id = '00000007-0002-4000-8000-000000000005') THEN
		RAISE EXCEPTION 'Missing intentional L02/B03 time conflict';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM schedule_preference p JOIN student_profile s USING (profile_id)
		WHERE s.is_demo AND cardinality(s.completed_course_codes) = 0
		AND p.structured_constraints_json->'requiredCourseCodes' = '["CSE-007"]'::jsonb
		AND p.structured_constraints_json->>'minCredits' = '4'
		AND p.structured_constraints_json->>'maxCredits' = '4'
		AND jsonb_array_length(p.structured_constraints_json->'blockedTimes') = 5) THEN
		RAISE EXCEPTION 'Synthetic profile/preference mismatch';
	END IF;
	-- Exercise DB constraints with bad writes; each must fail and roll back.
	BEGIN
		UPDATE meeting SET section_id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
		RAISE EXCEPTION 'Orphan meeting accepted';
	EXCEPTION WHEN foreign_key_violation THEN NULL; END;
	BEGIN
		UPDATE section SET course_id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
		RAISE EXCEPTION 'Orphan section course accepted';
	EXCEPTION WHEN foreign_key_violation THEN NULL; END;
	BEGIN
		UPDATE section SET term_id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
		RAISE EXCEPTION 'Orphan section term accepted';
	EXCEPTION WHEN foreign_key_violation THEN NULL; END;
	BEGIN
		UPDATE meeting SET end_time = start_time;
		RAISE EXCEPTION 'Zero-length meeting accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE meeting SET day_of_week = 8;
		RAISE EXCEPTION 'Invalid weekday accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE meeting SET source_status = 'REAL_VERIFIED';
		RAISE EXCEPTION 'Verified meeting without provenance accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE section SET source_status = 'HISTORICAL';
		RAISE EXCEPTION 'Historical section without provenance accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE section_link SET compatible_section_id = primary_section_id;
		RAISE EXCEPTION 'Self-link accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE student_profile SET is_demo = false;
		RAISE EXCEPTION 'Non-demo profile accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	RAISE NOTICE 'PASS: PDF fixture: 1 course, 6 sections, 9 meetings, 4 links; 3 time-feasible pairs, 1 after 10, 0 Friday-free; credits, TBA, provenance and 9 rejection checks passed';
END $$;
