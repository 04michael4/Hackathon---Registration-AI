-- Views and the only writers of professor_ratings. Run after V1. Every function is owner-only (no EXECUTE for PUBLIC or the reader role).
\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Writers
-- ---------------------------------------------------------------------------------------------------------------------------------------

-- Replaces the instructor assignments of every (term, source) pair in the payload; instructors are added, never removed.
-- payload: {"instructors":[{bannerName,lastName,firstName,lastKey,firstKey}], "assignments":[{term,crn,bannerName,role,source}]}
CREATE OR REPLACE FUNCTION professor_ratings.import_instructors(payload jsonb, label text, sha text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	rid bigint;
	expected integer;
	stored integer;
BEGIN
	IF jsonb_typeof(payload -> 'instructors') IS DISTINCT FROM 'array' OR jsonb_typeof(payload -> 'assignments') IS DISTINCT FROM 'array' THEN
		RAISE EXCEPTION 'payload needs "instructors" and "assignments" arrays';
	END IF;
	expected := jsonb_array_length(payload -> 'assignments');
	INSERT INTO professor_ratings.import_run (kind, label, input_sha256, row_count) VALUES ('INSTRUCTORS', label, sha, expected) RETURNING run_id INTO rid;
	INSERT INTO professor_ratings.instructor (banner_name, last_name, first_name, last_key, first_key)
		SELECT x ->> 'bannerName', x ->> 'lastName', x ->> 'firstName', x ->> 'lastKey', x ->> 'firstKey' FROM jsonb_array_elements(payload -> 'instructors') x
		ON CONFLICT (banner_name) DO NOTHING;
	DELETE FROM professor_ratings.instructor_section
		WHERE (term_code, source) IN (SELECT DISTINCT a ->> 'term', a ->> 'source' FROM jsonb_array_elements(payload -> 'assignments') a);
	INSERT INTO professor_ratings.instructor_section (term_code, crn, instructor_id, role, source, run_id)
		SELECT a ->> 'term', a ->> 'crn', i.instructor_id, a ->> 'role', a ->> 'source', rid
		FROM jsonb_array_elements(payload -> 'assignments') a JOIN professor_ratings.instructor i ON i.banner_name = a ->> 'bannerName';
	GET DIAGNOSTICS stored = ROW_COUNT;
	IF stored <> expected THEN
		RAISE EXCEPTION 'stored % assignments but the payload has % (an assignment names an unknown instructor)', stored, expected;
	END IF;
	RETURN rid;
END $$;

-- Stores one aggregate observation per RMP professor. -1 "would take again" and a zero average with zero ratings mean "none reported".
-- payload: {"schoolLegacyId":509,"fetchedAt":"<timestamp>","teachers":[{legacyId,nodeId,firstName,lastName,firstKey,lastKey,department,
--           avgRating,avgDifficulty,wouldTakeAgainPercent,numRatings}]}
CREATE OR REPLACE FUNCTION professor_ratings.import_rmp_observations(payload jsonb, label text, sha text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	rid bigint;
	school integer := (payload ->> 'schoolLegacyId')::integer;
	fetched timestamptz := (payload ->> 'fetchedAt')::timestamptz;
BEGIN
	IF jsonb_typeof(payload -> 'teachers') IS DISTINCT FROM 'array' OR school IS NULL OR fetched IS NULL THEN
		RAISE EXCEPTION 'payload needs schoolLegacyId, fetchedAt and a "teachers" array';
	END IF;
	INSERT INTO professor_ratings.import_run (kind, label, input_sha256, row_count) VALUES ('RMP_FETCH', label, sha, jsonb_array_length(payload -> 'teachers')) RETURNING run_id INTO rid;
	INSERT INTO professor_ratings.rmp_teacher (rmp_legacy_id, rmp_node_id, school_legacy_id, first_name, last_name, first_key, last_key, department)
		SELECT (t ->> 'legacyId')::integer, t ->> 'nodeId', school, t ->> 'firstName', t ->> 'lastName', t ->> 'firstKey', t ->> 'lastKey', t ->> 'department'
		FROM jsonb_array_elements(payload -> 'teachers') t
		ON CONFLICT (rmp_legacy_id) DO UPDATE SET rmp_node_id = EXCLUDED.rmp_node_id, first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
			first_key = EXCLUDED.first_key, last_key = EXCLUDED.last_key, department = EXCLUDED.department;
	INSERT INTO professor_ratings.rmp_rating_observation (rmp_legacy_id, run_id, avg_rating, avg_difficulty, would_take_again_pct, num_ratings, fetched_at)
		SELECT (t ->> 'legacyId')::integer, rid,
			CASE WHEN (t ->> 'numRatings')::integer = 0 THEN NULL ELSE (t ->> 'avgRating')::numeric END,
			CASE WHEN (t ->> 'numRatings')::integer = 0 THEN NULL ELSE (t ->> 'avgDifficulty')::numeric END,
			CASE WHEN (t ->> 'numRatings')::integer = 0 OR (t ->> 'wouldTakeAgainPercent')::numeric < 0 THEN NULL ELSE (t ->> 'wouldTakeAgainPercent')::numeric END,
			(t ->> 'numRatings')::integer, fetched
		FROM jsonb_array_elements(payload -> 'teachers') t;
	RETURN rid;
END $$;

-- Proposes a match for every instructor from the stored names. Rules, in order:
--   exactly one RMP professor with the same folded last name and the same first name, and no other candidate -> AUTO_ACCEPTED (EXACT_NAME)
--   no candidate (same last name and same first initial)                                                   -> NO_MATCH (NONE)
--   anything else                                                                                          -> NEEDS_REVIEW (FIRST_NAME_VARIANT when no exact candidate, else AMBIGUOUS)
-- A person's decision (CONFIRMED, or a manual NO_MATCH) is never overwritten. A repeated run that changes nothing appends nothing.
CREATE OR REPLACE FUNCTION professor_ratings.propose_matches(label text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	rid bigint;
	r record;
	cur record;
	new_status text;
	new_method text;
	new_rmp integer;
	new_reason text;
BEGIN
	INSERT INTO professor_ratings.import_run (kind, label, input_sha256, row_count)
		VALUES ('MATCH', label,
			encode(sha256(convert_to(coalesce((SELECT string_agg(i.banner_name, '|' ORDER BY i.banner_name) FROM professor_ratings.instructor i), '') || '#' ||
				coalesce((SELECT string_agg(t.rmp_legacy_id::text || ':' || t.last_key || ':' || t.first_key, '|' ORDER BY t.rmp_legacy_id) FROM professor_ratings.rmp_teacher t), ''), 'UTF8')), 'hex'),
			(SELECT count(*) FROM professor_ratings.instructor))
		RETURNING run_id INTO rid;
	FOR r IN
		SELECT i.instructor_id,
			count(c.rmp_legacy_id) AS candidates,
			count(c.rmp_legacy_id) FILTER (WHERE c.kind = 'EXACT') AS exact_candidates,
			min(c.rmp_legacy_id) FILTER (WHERE c.kind = 'EXACT') AS exact_id
		FROM professor_ratings.instructor i LEFT JOIN professor_ratings.match_candidate c ON c.instructor_id = i.instructor_id
		GROUP BY i.instructor_id ORDER BY i.instructor_id
	LOOP
		IF r.exact_candidates = 1 AND r.candidates = 1 THEN
			new_status := 'AUTO_ACCEPTED'; new_method := 'EXACT_NAME'; new_rmp := r.exact_id;
			new_reason := 'one RMP professor has the same last name and first name, and no other candidate exists';
		ELSIF r.candidates = 0 THEN
			new_status := 'NO_MATCH'; new_method := 'NONE'; new_rmp := NULL;
			new_reason := 'no RMP professor with the same last name and first initial';
		ELSE
			new_status := 'NEEDS_REVIEW'; new_rmp := NULL;
			new_method := CASE WHEN r.exact_candidates = 0 THEN 'FIRST_NAME_VARIANT' ELSE 'AMBIGUOUS' END;
			new_reason := r.candidates || ' candidate(s) need a person to choose (see match_candidate)';
		END IF;
		SELECT status, method, rmp_legacy_id INTO cur FROM professor_ratings.current_instructor_match WHERE instructor_id = r.instructor_id;
		IF FOUND AND cur.method = 'MANUAL' THEN CONTINUE; END IF;
		IF FOUND AND cur.status = new_status AND cur.method = new_method AND cur.rmp_legacy_id IS NOT DISTINCT FROM new_rmp THEN CONTINUE; END IF;
		INSERT INTO professor_ratings.instructor_rmp_match (instructor_id, rmp_legacy_id, status, method, reason, run_id)
			VALUES (r.instructor_id, new_rmp, new_status, new_method, new_reason, rid);
	END LOOP;
	RETURN rid;
END $$;

-- A person's decision. decision 'CONFIRM' needs the RMP professor; 'NO_MATCH' says none of the candidates is this instructor.
CREATE OR REPLACE FUNCTION professor_ratings.review_match(p_instructor_id bigint, p_rmp_legacy_id integer, decision text, p_decided_by text, p_reason text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	mid bigint;
BEGIN
	IF decision = 'CONFIRM' THEN
		IF p_rmp_legacy_id IS NULL THEN RAISE EXCEPTION 'CONFIRM needs an RMP professor id'; END IF;
		INSERT INTO professor_ratings.instructor_rmp_match (instructor_id, rmp_legacy_id, status, method, reason, decided_by)
			VALUES (p_instructor_id, p_rmp_legacy_id, 'CONFIRMED', 'MANUAL', p_reason, p_decided_by) RETURNING match_id INTO mid;
	ELSIF decision = 'NO_MATCH' THEN
		IF p_rmp_legacy_id IS NOT NULL THEN RAISE EXCEPTION 'NO_MATCH takes no RMP professor id'; END IF;
		INSERT INTO professor_ratings.instructor_rmp_match (instructor_id, rmp_legacy_id, status, method, reason, decided_by)
			VALUES (p_instructor_id, NULL, 'NO_MATCH', 'MANUAL', p_reason, p_decided_by) RETURNING match_id INTO mid;
	ELSE
		RAISE EXCEPTION 'decision must be CONFIRM or NO_MATCH';
	END IF;
	RETURN mid;
END $$;

REVOKE EXECUTE ON FUNCTION professor_ratings.import_instructors(jsonb, text, text), professor_ratings.import_rmp_observations(jsonb, text, text),
	professor_ratings.propose_matches(text), professor_ratings.review_match(bigint, integer, text, text, text) FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Views (read-only)
-- ---------------------------------------------------------------------------------------------------------------------------------------

-- Same folded last name and same first initial. EXACT: the first names (first word) are equal as well and are more than one letter (an initial
-- alone never proves identity).
CREATE OR REPLACE VIEW professor_ratings.match_candidate AS
SELECT i.instructor_id, t.rmp_legacy_id,
	CASE WHEN split_part(t.first_key, ' ', 1) = split_part(i.first_key, ' ', 1) AND length(split_part(i.first_key, ' ', 1)) > 1 THEN 'EXACT' ELSE 'VARIANT' END AS kind
FROM professor_ratings.instructor i
JOIN professor_ratings.rmp_teacher t ON t.last_key = i.last_key AND left(t.first_key, 1) = left(i.first_key, 1);

CREATE OR REPLACE VIEW professor_ratings.current_instructor_match AS
SELECT DISTINCT ON (instructor_id) match_id, instructor_id, rmp_legacy_id, status, method, reason, decided_by, decided_at
FROM professor_ratings.instructor_rmp_match ORDER BY instructor_id, match_id DESC;

CREATE OR REPLACE VIEW professor_ratings.latest_rmp_observation AS
SELECT DISTINCT ON (rmp_legacy_id) rmp_legacy_id, avg_rating, avg_difficulty, would_take_again_pct, num_ratings, fetched_at, run_id
FROM professor_ratings.rmp_rating_observation ORDER BY rmp_legacy_id, fetched_at DESC, observation_id DESC;

-- One row per instructor whose match is accepted (automatically on an exact unique name, or by a person) and has an observation.
CREATE OR REPLACE VIEW professor_ratings.instructor_rating AS
SELECT i.instructor_id, i.banner_name, m.status AS match_status, m.rmp_legacy_id, t.department AS rmp_department,
	o.avg_rating, o.avg_difficulty, o.would_take_again_pct, o.num_ratings,
	CASE WHEN o.num_ratings = 0 THEN 'NO_RATINGS' WHEN o.num_ratings < 5 THEN 'LOW_SAMPLE' ELSE 'OK' END AS sample_note,
	o.fetched_at, 'RMP_STUDENT_OPINION'::text AS evidence_level
FROM professor_ratings.instructor i
JOIN professor_ratings.current_instructor_match m ON m.instructor_id = i.instructor_id AND m.status IN ('AUTO_ACCEPTED', 'CONFIRMED')
JOIN professor_ratings.rmp_teacher t ON t.rmp_legacy_id = m.rmp_legacy_id
JOIN professor_ratings.latest_rmp_observation o ON o.rmp_legacy_id = m.rmp_legacy_id;

-- Every assigned instructor of every section, with the rating when there is an accepted match (an unrated instructor still appears).
CREATE OR REPLACE VIEW professor_ratings.section_instructor_rating AS
SELECT s.term_code, s.crn, s.role, s.source, i.instructor_id, i.banner_name,
	coalesce(m.status, 'NOT_MATCHED') AS match_status,
	r.avg_rating, r.avg_difficulty, r.would_take_again_pct, r.num_ratings, r.sample_note, r.fetched_at, r.evidence_level
FROM professor_ratings.instructor_section s
JOIN professor_ratings.instructor i ON i.instructor_id = s.instructor_id
LEFT JOIN professor_ratings.current_instructor_match m ON m.instructor_id = i.instructor_id
LEFT JOIN professor_ratings.instructor_rating r ON r.instructor_id = i.instructor_id;

-- What a person still has to decide, with each candidate.
CREATE OR REPLACE VIEW professor_ratings.review_queue AS
SELECT i.instructor_id, i.banner_name, c.rmp_legacy_id, c.kind AS candidate_kind, t.first_name AS rmp_first_name, t.last_name AS rmp_last_name,
	t.department AS rmp_department, o.num_ratings
FROM professor_ratings.current_instructor_match m
JOIN professor_ratings.instructor i ON i.instructor_id = m.instructor_id
JOIN professor_ratings.match_candidate c ON c.instructor_id = i.instructor_id
JOIN professor_ratings.rmp_teacher t ON t.rmp_legacy_id = c.rmp_legacy_id
LEFT JOIN professor_ratings.latest_rmp_observation o ON o.rmp_legacy_id = c.rmp_legacy_id
WHERE m.status = 'NEEDS_REVIEW';

-- Assignments that name a section the catalog does not have (a re-imported catalog can leave these behind).
CREATE OR REPLACE VIEW professor_ratings.instructor_section_orphan AS
SELECT s.term_code, s.crn, s.instructor_id
FROM professor_ratings.instructor_section s
LEFT JOIN catalog_research.section cs ON cs.term_code = s.term_code AND cs.crn = s.crn
WHERE cs.crn IS NULL;

-- Catalog sections that have no instructor assignment at all (so ratings cannot show for them).
CREATE OR REPLACE VIEW professor_ratings.section_without_instructor AS
SELECT DISTINCT cs.term_code, cs.crn, cs.offering_id, cs.component_type
FROM catalog_research.section cs
WHERE NOT EXISTS (SELECT 1 FROM professor_ratings.instructor_section s WHERE s.term_code = cs.term_code AND s.crn = cs.crn);

CREATE OR REPLACE VIEW professor_ratings.rating_coverage AS
SELECT coalesce(m.status, 'NO_DECISION') AS match_status, count(*)::integer AS instructors
FROM professor_ratings.instructor i LEFT JOIN professor_ratings.current_instructor_match m ON m.instructor_id = i.instructor_id
GROUP BY 1;

-- A name match is a guess: this puts every accepted match next to the subjects that instructor teaches here and the department RMP lists, so a person
-- can spot a different professor with the same name (a CSE instructor matched to a Health Science profile, for example).
CREATE OR REPLACE VIEW professor_ratings.accepted_match_context AS
SELECT i.instructor_id, i.banner_name, m.match_id, m.status AS match_status, m.rmp_legacy_id, t.department AS rmp_department,
	(SELECT string_agg(DISTINCT o.subject, '/' ORDER BY o.subject)
		FROM professor_ratings.instructor_section s
		JOIN catalog_research.section cs ON cs.term_code = s.term_code AND cs.crn = s.crn
		JOIN catalog_research.offering o ON o.term_code = cs.term_code AND o.offering_id = cs.offering_id
		WHERE s.instructor_id = i.instructor_id) AS taught_subjects
FROM professor_ratings.instructor i
JOIN professor_ratings.current_instructor_match m ON m.instructor_id = i.instructor_id AND m.status IN ('AUTO_ACCEPTED', 'CONFIRMED')
JOIN professor_ratings.rmp_teacher t ON t.rmp_legacy_id = m.rmp_legacy_id;
