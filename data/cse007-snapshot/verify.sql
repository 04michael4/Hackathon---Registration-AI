\set ON_ERROR_STOP on
SET search_path TO cse007_snapshot;
DO $$
DECLARE
	total_credits numeric;
	confirmed_pairs integer;
	after_ten integer;
	friday_free integer;
BEGIN
	IF (SELECT count(*) FROM source_asset) <> 8
		OR (SELECT count(*) FROM course) <> 1
		OR (SELECT count(*) FROM term) <> 1
		OR (SELECT count(*) FROM section) <> 3
		OR (SELECT count(*) FROM meeting) <> 5
		OR (SELECT count(*) FROM section_link) <> 2
		OR (SELECT count(*) FROM section_component_group) <> 1
		OR (SELECT count(*) FROM course_requirement) <> 3
		OR (SELECT count(*) FROM section_attribute) <> 8
		OR (SELECT count(*) FROM enrollment_snapshot) <> 1
		OR (SELECT count(*) FROM data_issue) <> 8 THEN
		RAISE EXCEPTION 'Unexpected screenshot dataset counts';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM course WHERE code = 'CSE-007' AND course_number = '007'
		AND title = 'Introduction to Programming' AND credits = 4 AND prerequisite_status = 'USER_CONFIRMED_NONE'
		AND description LIKE 'Introduction to problem-solving%'
		AND description LIKE '%CSE 003 nor CSE 004.')
		OR NOT EXISTS (SELECT 1 FROM term WHERE term_code = '202710' AND name = '2027 Spring Semester') THEN
		RAISE EXCEPTION 'Course/term does not match screenshots';
	END IF;
	IF (SELECT array_agg(crn ORDER BY crn) FROM section) IS DISTINCT FROM ARRAY['12275','12638','13132']::varchar[]
		OR EXISTS (SELECT 1 FROM section WHERE instructor IS DISTINCT FROM 'Chen, Brian'
			OR source_status <> 'HISTORICAL' OR NOT instructor_is_primary) THEN
		RAISE EXCEPTION 'Section identifiers/instructor/provenance mismatch';
	END IF;
	IF (SELECT array_agg(s.section_code || ':' || m.day_of_week || ':' ||
		to_char(m.start_time,'HH24:MI') || '-' || to_char(m.end_time,'HH24:MI')
		ORDER BY s.section_code, m.day_of_week)
		FROM meeting m JOIN section s USING (section_id)) IS DISTINCT FROM
		ARRAY['010:1:09:20-10:35','060:3:09:20-10:35','060:5:09:20-10:35',
			'062:3:10:45-12:00','062:5:10:45-12:00'] THEN
		RAISE EXCEPTION 'Meeting transcription mismatch';
	END IF;
	IF EXISTS (SELECT 1 FROM meeting m JOIN section s USING (section_id)
		WHERE m.start_date IS DISTINCT FROM DATE '2027-01-25' OR m.end_date IS DISTINCT FROM DATE '2027-05-07'
		OR (s.section_code = '060' AND (m.building IS DISTINCT FROM '019 Packard Lab'
			OR m.room IS DISTINCT FROM '112' OR m.date_basis <> 'SCREENSHOT'))
		OR (s.section_code = '062' AND (m.building IS DISTINCT FROM '019 Packard Lab'
			OR m.room IS DISTINCT FROM '11' OR m.date_basis <> 'USER_CLARIFICATION'))
		OR (s.section_code = '010' AND (m.location IS DISTINCT FROM 'TBD' OR m.building IS NOT NULL
			OR m.room IS NOT NULL OR m.date_basis <> 'DEMO_TERM_DEFAULT'))) THEN
		RAISE EXCEPTION 'Clarified meeting details or date provenance mismatch';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM section WHERE section_code = '060' AND credits = 0
		AND credits_verification_status = 'USER_CONFIRMED')
		OR NOT EXISTS (SELECT 1 FROM data_issue WHERE code = 'CREDITS_CONFLICT'
			AND observations @> '[{"label":"Hours","value":0},{"label":"Credit Hours","value":4}]'::jsonb AND status = 'RESOLVED')
		OR NOT EXISTS (SELECT 1 FROM section WHERE section_code = '010' AND credits = 4 AND link_status = 'GROUP_CONFIRMED')
		OR NOT EXISTS (SELECT 1 FROM section WHERE section_code = '062' AND credits = 0 AND link_status = 'PAIR_CONFIRMED')
		OR EXISTS (SELECT 1 FROM section WHERE delivery_type <> 'IN_PERSON' OR clarification_source_id IS NULL) THEN
		RAISE EXCEPTION 'User corrections or original credit evidence were lost';
	END IF;
	IF EXISTS (SELECT 1 FROM data_issue WHERE status = 'OPEN' OR resolution_source_id IS NULL)
		OR (SELECT count(*) FROM data_issue WHERE status = 'RESOLVED') <> 6
		OR (SELECT count(*) FROM data_issue WHERE status = 'ACCEPTED') <> 2 THEN
		RAISE EXCEPTION 'Resolved/accepted issues are still presented as open';
	END IF;
	IF (SELECT array_agg(requirement_type ORDER BY requirement_type) FROM course_requirement)
		IS DISTINCT FROM ARRAY['COREQUISITE','ENROLLMENT_RESTRICTION','PREREQUISITE']
		OR EXISTS (SELECT 1 FROM course_requirement WHERE requirement_expression IS NOT NULL
			OR assessment_status <> 'USER_CONFIRMED_NONE' OR source_id IS NULL) THEN
		RAISE EXCEPTION 'No-requirement rows must be explicit NULLs with user-confirmed status';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM section_component_group g JOIN section s ON s.section_id = g.primary_section_id
		WHERE s.section_code = '010' AND g.required_component_group = 'LAB'
		AND g.required_count = 1 AND g.assessment_status = 'USER_CONFIRMED') THEN
		RAISE EXCEPTION 'Missing exactly-one LAB rule';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM section_link l JOIN section a ON a.section_id = l.primary_section_id
		JOIN section b ON b.section_id = l.compatible_section_id
		WHERE a.crn = '12275' AND b.crn = '13132' AND a.component_type = 'LECTURE'
		AND b.component_type = 'LAB' AND l.verification_status = 'USER_CONFIRMED') THEN
		RAISE EXCEPTION 'Linked-section screenshot was not preserved';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM section_link l JOIN section a ON a.section_id = l.primary_section_id
		JOIN section b ON b.section_id = l.compatible_section_id
		WHERE a.crn = '12275' AND b.crn = '12638' AND l.verification_status = 'USER_CONFIRMED') THEN
		RAISE EXCEPTION 'The final SuY clarification allowing 062 was not applied';
	END IF;
	SELECT count(*), min(a.credits+b.credits),
		count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM meeting m
			WHERE m.section_id IN (a.section_id,b.section_id) AND m.start_time < TIME '10:00')),
		count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM meeting m
			WHERE m.section_id IN (a.section_id,b.section_id) AND m.day_of_week = 5))
	INTO confirmed_pairs, total_credits, after_ten, friday_free
	FROM section_link l JOIN section a ON a.section_id = l.primary_section_id
	JOIN section b ON b.section_id = l.compatible_section_id
	WHERE NOT EXISTS (SELECT 1 FROM meeting ma JOIN meeting mb ON ma.day_of_week = mb.day_of_week
		AND ma.start_time < mb.end_time AND mb.start_time < ma.end_time
		WHERE ma.section_id = a.section_id AND mb.section_id = b.section_id);
	IF confirmed_pairs <> 2 OR total_credits IS DISTINCT FROM 4::numeric OR after_ten <> 0 OR friday_free <> 0 THEN
		RAISE EXCEPTION 'Known scenario mismatch';
	END IF;
	-- Fixed selections prove the required-group interpretation of the fixture.
	IF EXISTS (
		WITH cases(selected, expected) AS (VALUES
			(ARRAY['010','060'], true), (ARRAY['010','062'], true),
			(ARRAY['010'], false), (ARRAY['010','060','062'], false), (ARRAY['060'], false)
		), evaluated AS (
			SELECT expected,
				('010' = ANY(selected) AND
				 (SELECT count(*) FROM section_link l JOIN section b ON b.section_id = l.compatible_section_id
				  WHERE b.section_code = ANY(selected)) =
				 (SELECT required_count FROM section_component_group)) AS actual
			FROM cases
		) SELECT 1 FROM evaluated WHERE actual IS DISTINCT FROM expected
	) THEN
		RAISE EXCEPTION 'Required-component selection scenarios failed';
	END IF;
	IF EXISTS (SELECT 1 FROM meeting a JOIN meeting b ON a.day_of_week = b.day_of_week
		AND a.start_time < b.end_time AND b.start_time < a.end_time
		WHERE a.section_id <> b.section_id) THEN
		RAISE EXCEPTION 'Screenshotted sections unexpectedly overlap';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM meeting a JOIN meeting b ON a.day_of_week = b.day_of_week
		JOIN section sa ON sa.section_id = a.section_id JOIN section sb ON sb.section_id = b.section_id
		WHERE sa.section_code = '060' AND sb.section_code = '062'
		AND b.start_time - a.end_time = INTERVAL '10 minutes') THEN
		RAISE EXCEPTION 'Lab time gap differs from screenshot';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM enrollment_snapshot e JOIN section s USING (section_id)
		WHERE s.crn = '13132' AND e.enrollment_actual = 0 AND e.enrollment_maximum = 38
		AND e.enrollment_seats_available = 38 AND e.waitlist_capacity = 0
		AND e.waitlist_actual = 0 AND e.waitlist_seats_available = 0 AND NOT e.is_live)
		OR EXISTS (SELECT 1 FROM source_asset WHERE captured_at IS NOT NULL OR source_url IS NOT NULL) THEN
		RAISE EXCEPTION 'Snapshot was misrepresented as live or given an invented capture time/URL';
	END IF;
	-- Rejected writes roll back inside each exception block.
	BEGIN
		UPDATE meeting SET section_id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
		RAISE EXCEPTION 'Orphan meeting accepted';
	EXCEPTION WHEN foreign_key_violation THEN NULL; END;
	BEGIN
		UPDATE meeting SET day_of_week = 8;
		RAISE EXCEPTION 'Invalid weekday accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE meeting SET end_time = start_time;
		RAISE EXCEPTION 'Invalid meeting time accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE section SET source_id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
		RAISE EXCEPTION 'Missing provenance accepted';
	EXCEPTION WHEN foreign_key_violation THEN NULL; END;
	BEGIN
		UPDATE section_link SET term_id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
		RAISE EXCEPTION 'Wrong-term link accepted';
	EXCEPTION WHEN foreign_key_violation THEN NULL; END;
	BEGIN
		UPDATE enrollment_snapshot SET is_live = true;
		RAISE EXCEPTION 'Live availability claim accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE course_requirement SET assessment_status = 'RULE_DEFINED';
		RAISE EXCEPTION 'A defined rule without an expression was accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE data_issue SET resolution_source_id = NULL;
		RAISE EXCEPTION 'Resolved issue without provenance accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	RAISE NOTICE 'PASS: SuY corrections: 2 valid lecture/lab choices at 4 credits; 3 nullable requirement rows; 0 open issues; 5 selection scenarios and 8 rejection checks passed';
END $$;
