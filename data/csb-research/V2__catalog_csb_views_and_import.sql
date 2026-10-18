-- catalog_csb v2: cross-check views and the transactional importer. Needs V1.
--   * grid_mismatch / link_status_mismatch / credit_problem / program_problem are INDEPENDENT of the importer: days, times, components and
--     link confirmation are re-derived here in SQL from the verbatim rows. import_csb refuses to finish while any of them returns a row.
--   * import_csb replaces EVERYTHING in catalog_csb (except the append-only import_run history) from one payload, in the caller's transaction.
--     It reads and writes only catalog_csb, so a rerun can never touch catalog_research or any other schema.
\set ON_ERROR_STOP on

DO $$ BEGIN
	IF to_regclass('catalog_csb.section') IS NULL THEN RAISE EXCEPTION 'catalog_csb V1 must be installed first'; END IF;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- grid_observation: add the raw 'Linked Sections' cell (kept verbatim; a button label, not a relationship)
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE catalog_csb.grid_observation ADD COLUMN IF NOT EXISTS linked_cell text;

-- ---------------------------------------------------------------------------------------------------------
-- grid_mismatch: every place where a normalized row disagrees with the verbatim grid row. Must be empty.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_csb.grid_mismatch AS
WITH raw AS (
	SELECT g.term_code, g.crn, g.course_label, g.section_no, g.title, g.hours_grid, g.schedule_type,
		CASE g.schedule_type WHEN 'Lecture' THEN 'LECTURE' WHEN 'Lab' THEN 'LAB' WHEN 'Recitation' THEN 'RECITATION' WHEN 'Independent Study' THEN 'INDEPENDENT_STUDY' END AS component,
		substring(g.course_label from '([0-9]{3})$') AS course_number
	FROM catalog_csb.grid_observation g),
raw_meeting AS (
	SELECT g.term_code, g.crn, m.raw,
		split_part(m.raw, ' Type: ', 1) AS head, split_part(m.raw, ' Type: ', 2) AS rest
	FROM catalog_csb.grid_observation g CROSS JOIN LATERAL unnest(g.meetings) AS m(raw)),
parsed AS (
	SELECT r.term_code, r.crn, r.raw,
		split_part(r.head, ' S M T W T F S', 1) AS day_names,
		substring(r.head from '([0-9]{2}:[0-9]{2} [AP]M) - [0-9]{2}:[0-9]{2} [AP]M$') AS start_text,
		substring(r.head from '[0-9]{2}:[0-9]{2} [AP]M - ([0-9]{2}:[0-9]{2} [AP]M)$') AS end_text,
		replace(split_part(r.rest, ' Building: ', 1), ' On-Campus Only', '') AS mtype,
		split_part(split_part(r.rest, ' Building: ', 2), ' Room: ', 1) AS building,
		split_part(split_part(r.rest, ' Room: ', 2), ' Start Date: ', 1) AS room,
		to_date(split_part(split_part(r.rest, ' Start Date: ', 2), ' End Date: ', 1), 'MM/DD/YYYY') AS sd,
		to_date(split_part(r.rest, ' End Date: ', 2), 'MM/DD/YYYY') AS ed
	FROM raw_meeting r),
timed AS (
	SELECT p.term_code, p.crn, p.mtype, d.dow, p.start_text::time AS st, p.end_text::time AS en, p.sd, p.ed,
		COALESCE(NULLIF(btrim(concat_ws(' ', NULLIF(p.building, 'None'), NULLIF(p.room, 'None'))), ''), 'TBA') AS location,
		CASE WHEN p.sd = p.ed OR p.mtype = 'Common Hour Exam' THEN 'DATED_ONE_TIME' ELSE 'WEEKLY' END AS kind
	FROM parsed p CROSS JOIN LATERAL (SELECT CASE n WHEN 'Monday' THEN 1 WHEN 'Tuesday' THEN 2 WHEN 'Wednesday' THEN 3 WHEN 'Thursday' THEN 4 WHEN 'Friday' THEN 5 WHEN 'Saturday' THEN 6 WHEN 'Sunday' THEN 7 END AS dow
		FROM unnest(string_to_array(p.day_names, ',')) AS n) d
	WHERE p.start_text IS NOT NULL),
arranged AS (SELECT p.term_code, p.crn, p.mtype FROM parsed p WHERE p.start_text IS NULL)
SELECT s.term_code, s.crn, 'SECTION_NOT_IN_GRID'::text AS problem, s.offering_id AS stored, NULL::text AS grid
FROM catalog_csb.section s WHERE NOT EXISTS (SELECT 1 FROM catalog_csb.grid_observation g WHERE g.term_code = s.term_code AND g.crn = s.crn)
UNION ALL
SELECT g.term_code, g.crn, 'GRID_ROW_NOT_STORED', NULL, g.course_label
FROM catalog_csb.grid_observation g WHERE NOT EXISTS (SELECT 1 FROM catalog_csb.section s WHERE s.term_code = g.term_code AND s.crn = g.crn)
UNION ALL
SELECT s.term_code, s.crn, 'SECTION_FIELD_DIFFERS',
	concat_ws('/', o.course_number, s.section_code, s.component_type, s.credits_text, s.schedule_type),
	concat_ws('/', r.course_number, r.section_no, r.component, r.hours_grid, r.schedule_type)
FROM catalog_csb.section s JOIN catalog_csb.offering o USING (term_code, offering_id) JOIN raw r ON r.term_code = s.term_code AND r.crn = s.crn
WHERE (o.course_number, s.section_code, s.component_type, s.credits_text, s.schedule_type) IS DISTINCT FROM (r.course_number, r.section_no, r.component, r.hours_grid, r.schedule_type)
UNION ALL
SELECT s.term_code, s.crn, 'TITLE_DIFFERS', o.title, r.title
FROM catalog_csb.section s JOIN catalog_csb.offering o USING (term_code, offering_id) JOIN raw r ON r.term_code = s.term_code AND r.crn = s.crn
WHERE o.title IS DISTINCT FROM r.title AND s.component_type = 'LECTURE'
UNION ALL
SELECT m.term_code, m.crn, 'WEEKLY_MEETING_NOT_IN_GRID', m.day_of_week || ' ' || m.start_time || '-' || m.end_time || ' ' || m.location, NULL
FROM catalog_csb.section_meeting m
WHERE m.meeting_kind = 'WEEKLY' AND NOT EXISTS (SELECT 1 FROM timed t WHERE t.kind = 'WEEKLY' AND t.term_code = m.term_code AND t.crn = m.crn AND t.dow = m.day_of_week
	AND t.st = m.start_time AND t.en = m.end_time AND t.sd = m.start_date AND t.ed = m.end_date AND t.location = m.location AND t.mtype = m.meeting_type)
UNION ALL
SELECT t.term_code, t.crn, 'GRID_WEEKLY_MEETING_NOT_STORED', t.dow || ' ' || t.st || '-' || t.en || ' ' || t.location, NULL
FROM timed t WHERE t.kind = 'WEEKLY' AND NOT EXISTS (SELECT 1 FROM catalog_csb.section_meeting m WHERE m.meeting_kind = 'WEEKLY' AND m.term_code = t.term_code AND m.crn = t.crn
	AND m.day_of_week = t.dow AND m.start_time = t.st AND m.end_time = t.en AND m.start_date = t.sd AND m.end_date = t.ed AND m.location = t.location AND m.meeting_type = t.mtype)
UNION ALL
SELECT m.term_code, m.crn, 'DATED_MEETING_NOT_IN_GRID', m.meeting_type || ' ' || m.start_date || ' ' || m.start_time, NULL
FROM catalog_csb.section_meeting m
WHERE m.meeting_kind = 'DATED_ONE_TIME' AND NOT EXISTS (SELECT 1 FROM timed t WHERE t.kind = 'DATED_ONE_TIME' AND t.term_code = m.term_code AND t.crn = m.crn AND t.dow = m.day_of_week
	AND t.st = m.start_time AND t.en = m.end_time AND t.sd = m.start_date AND t.location = m.location AND t.mtype = m.meeting_type)
UNION ALL
SELECT t.term_code, t.crn, 'GRID_DATED_MEETING_NOT_STORED', t.mtype || ' ' || t.sd || ' ' || t.st, NULL
FROM timed t WHERE t.kind = 'DATED_ONE_TIME' AND NOT EXISTS (SELECT 1 FROM catalog_csb.section_meeting m WHERE m.meeting_kind = 'DATED_ONE_TIME' AND m.term_code = t.term_code AND m.crn = t.crn
	AND m.day_of_week = t.dow AND m.start_time = t.st AND m.end_time = t.en AND m.start_date = t.sd AND m.location = t.location AND m.meeting_type = t.mtype)
UNION ALL
SELECT a.term_code, a.crn, 'GRID_ARRANGED_MEETING_NOT_STORED', a.mtype, NULL
FROM arranged a WHERE NOT EXISTS (SELECT 1 FROM catalog_csb.section_arranged_meeting x WHERE x.term_code = a.term_code AND x.crn = a.crn)
UNION ALL
SELECT x.term_code, x.crn, 'ARRANGED_MEETING_NOT_IN_GRID', x.meeting_type, NULL
FROM catalog_csb.section_arranged_meeting x WHERE NOT EXISTS (SELECT 1 FROM arranged a WHERE a.term_code = x.term_code AND a.crn = x.crn)
UNION ALL
-- instructors: the stored names, in order, are exactly the grid cell's names (no name added, dropped or reordered)
SELECT g.term_code, g.crn, 'INSTRUCTORS_DIFFER',
	COALESCE((SELECT string_agg(i.instructor_name || ':' || i.role, '|' ORDER BY i.position) FROM catalog_csb.section_instructor i WHERE i.term_code = g.term_code AND i.crn = g.crn), ''),
	COALESCE((SELECT string_agg(e ->> 'name' || ':' || (e ->> 'role'), '|' ORDER BY n) FROM jsonb_array_elements(g.instructors) WITH ORDINALITY AS t(e, n)), '')
FROM catalog_csb.grid_observation g
WHERE COALESCE((SELECT string_agg(i.instructor_name || ':' || i.role, '|' ORDER BY i.position) FROM catalog_csb.section_instructor i WHERE i.term_code = g.term_code AND i.crn = g.crn), '')
	IS DISTINCT FROM COALESCE((SELECT string_agg(e ->> 'name' || ':' || (e ->> 'role'), '|' ORDER BY n) FROM jsonb_array_elements(g.instructors) WITH ORDINALITY AS t(e, n)), '')
UNION ALL
-- an offering's credits are the one value its lecture sections share (all sections when it has no lecture); a range for 'n TO m'; else VARIES_BY_SECTION
SELECT o.term_code, o.offering_id, 'OFFERING_CREDITS_DIFFER', concat_ws('/', o.credits_text, o.credits_status), v.vals::text
FROM catalog_csb.offering o
CROSS JOIN LATERAL (SELECT array_agg(DISTINCT s.credits_text ORDER BY s.credits_text) AS vals FROM catalog_csb.section s
	WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id
		AND (s.component_type <> 'LAB' AND s.component_type <> 'RECITATION' OR NOT EXISTS (SELECT 1 FROM catalog_csb.section l WHERE l.term_code = o.term_code AND l.offering_id = o.offering_id AND l.component_type = 'LECTURE'))) v
WHERE (cardinality(v.vals) = 1 AND (o.credits_text IS DISTINCT FROM v.vals[1] OR o.credits_status = 'VARIES_BY_SECTION'))
	OR (cardinality(v.vals) > 1 AND (o.credits_text IS NOT NULL OR o.credits_status <> 'VARIES_BY_SECTION'));
COMMENT ON VIEW catalog_csb.grid_mismatch IS 'Normalized rows that disagree with the verbatim grid rows. Must be empty; import_csb refuses to commit otherwise.';

-- ---------------------------------------------------------------------------------------------------------
-- Linked sections
-- ---------------------------------------------------------------------------------------------------------
-- every directed listing (panel_crn lists member_crn) and whether the member's own panel lists the panel's section back
CREATE OR REPLACE VIEW catalog_csb.link_pair AS
SELECT DISTINCT l.term_code, l.panel_crn AS crn, l.member_crn AS partner_crn, l.option_no,
	EXISTS (SELECT 1 FROM catalog_csb.section_link_option b WHERE b.term_code = l.term_code AND b.panel_crn = l.member_crn AND b.member_crn = l.panel_crn) AS listed_back
FROM catalog_csb.section_link_option l;
COMMENT ON VIEW catalog_csb.link_pair IS 'Each listing in a Linked Sections panel and whether the partner''s own panel lists this section back. listed_back = false means only one side was read or Banner lists it one-way.';

-- The link status each section SHOULD have, from the panels alone: NO_LINKS_LISTED (no panel mentions it), CONFIRMED_BOTH_SIDES (every listing that
-- involves it is mutual) or LISTED_BY_ONE_SIDE_ONLY (it lists a section that does not list it back, or a section lists it and it does not list that one).
CREATE OR REPLACE VIEW catalog_csb.link_status_derived AS
SELECT DISTINCT s.term_code, s.crn,
	CASE WHEN NOT EXISTS (SELECT 1 FROM catalog_csb.section_link_option a WHERE a.term_code = s.term_code AND (a.panel_crn = s.crn OR a.member_crn = s.crn)) THEN 'NO_LINKS_LISTED'
		WHEN EXISTS (SELECT 1 FROM catalog_csb.section_link_option a WHERE a.term_code = s.term_code AND a.panel_crn = s.crn
				AND NOT EXISTS (SELECT 1 FROM catalog_csb.section_link_option b WHERE b.term_code = a.term_code AND b.panel_crn = a.member_crn AND b.member_crn = a.panel_crn))
			OR EXISTS (SELECT 1 FROM catalog_csb.section_link_option a WHERE a.term_code = s.term_code AND a.member_crn = s.crn
				AND NOT EXISTS (SELECT 1 FROM catalog_csb.section_link_option b WHERE b.term_code = a.term_code AND b.panel_crn = s.crn AND b.member_crn = a.panel_crn)) THEN 'LISTED_BY_ONE_SIDE_ONLY'
		ELSE 'CONFIRMED_BOTH_SIDES' END AS derived
FROM catalog_csb.section s;

CREATE OR REPLACE VIEW catalog_csb.link_status_mismatch AS
SELECT DISTINCT s.term_code, s.crn, s.link_status AS stored, d.derived
FROM catalog_csb.section s JOIN catalog_csb.link_status_derived d ON d.term_code = s.term_code AND d.crn = s.crn
WHERE s.link_status IS DISTINCT FROM d.derived;
COMMENT ON VIEW catalog_csb.link_status_mismatch IS 'Stored link statuses that disagree with the panels. Must be empty; import_csb refuses to commit otherwise.';

-- what a student must register for together with a section: its panel's options, each described by the component types it contains
CREATE OR REPLACE VIEW catalog_csb.component_requirement AS
SELECT o.term_code, o.panel_crn AS crn, s.offering_id, s.component_type, count(DISTINCT o.option_no) AS option_count,
	string_agg(DISTINCT x.combo, ' OR ' ORDER BY x.combo) AS option_shapes,
	CASE WHEN count(DISTINCT x.combo) = 1 THEN 'CHOOSE_ONE_OF_' || count(DISTINCT o.option_no) || '_LISTED_OPTIONS_EACH: ' || min(x.combo)
		ELSE 'CHOOSE_ONE_OF_' || count(DISTINCT o.option_no) || '_LISTED_OPTIONS_OF_MIXED_SHAPES' END AS registration_shape,
	'OPTIONS_LISTED_BY_PANEL_MANDATORY_REGISTRATION_NOT_STATED'::text AS requirement_basis
FROM (SELECT DISTINCT term_code, panel_crn, option_no FROM catalog_csb.section_link_option) o
JOIN catalog_csb.section s ON s.term_code = o.term_code AND s.crn = o.panel_crn
CROSS JOIN LATERAL (SELECT string_agg(l.member_schedule_type, ' + ' ORDER BY l.member_schedule_type, l.member_crn) AS combo
	FROM catalog_csb.section_link_option l WHERE l.term_code = o.term_code AND l.panel_crn = o.panel_crn AND l.option_no = o.option_no) x
GROUP BY o.term_code, o.panel_crn, s.offering_id, s.component_type;
COMMENT ON VIEW catalog_csb.component_requirement IS 'Per section with a Linked Sections panel: how many alternative options and what each contains (options are alternatives; members of one option go together). The panel lists the options; it does not itself say that registering for one is mandatory (that is Banner''s linked-section behavior), so requirement_basis says so.';

-- credits: a lecture plus any one of its linked options must add up to the lecture's credits unless a member's grid credits are positive
CREATE OR REPLACE VIEW catalog_csb.package_credit AS
SELECT o.term_code, o.panel_crn AS lecture_crn, o.option_no, s.credits_min AS lecture_credits,
	COALESCE((SELECT sum(m.credits_min) FROM catalog_csb.section_link_option l JOIN catalog_csb.section m ON m.term_code = l.term_code AND m.crn = l.member_crn
		WHERE l.term_code = o.term_code AND l.panel_crn = o.panel_crn AND l.option_no = o.option_no), 0) AS members_grid_credits,
	s.credits_min + COALESCE((SELECT sum(m.credits_min) FROM catalog_csb.section_link_option l JOIN catalog_csb.section m ON m.term_code = l.term_code AND m.crn = l.member_crn
		WHERE l.term_code = o.term_code AND l.panel_crn = o.panel_crn AND l.option_no = o.option_no), 0) AS package_credits
FROM (SELECT DISTINCT term_code, panel_crn, option_no FROM catalog_csb.section_link_option) o
JOIN catalog_csb.section s ON s.term_code = o.term_code AND s.crn = o.panel_crn AND s.component_type = 'LECTURE';
COMMENT ON VIEW catalog_csb.package_credit IS 'Credits of a lecture plus one linked option, from the grid. members_grid_credits > 0 would mean a component adds credits; 0 means the package is the lecture''s credits, counted once.';

CREATE OR REPLACE VIEW catalog_csb.credit_problem AS
SELECT p.term_code, p.lecture_crn AS crn, 'LINKED_COMPONENT_ADDS_CREDITS_IN_GRID'::text AS problem, p.members_grid_credits::text AS detail
FROM catalog_csb.package_credit p WHERE p.members_grid_credits > 0
UNION ALL
SELECT s.term_code, s.crn, 'CREDITS_DISPLAY_DIFFERS_GRID_VS_DETAILS', s.credits_text || ' vs ' || s.details_credit_hours_text
FROM catalog_csb.section s WHERE s.credits_display_status = 'DETAILS_DIFFER_FROM_GRID'
UNION ALL
SELECT s.term_code, s.crn, 'CREDITS_DISPLAY_STATUS_WRONG', s.credits_display_status
FROM catalog_csb.section s WHERE (s.credits_display_status = 'GRID_AND_DETAILS_AGREE') IS DISTINCT FROM (s.details_credit_hours_text IS NOT DISTINCT FROM s.credits_text);
COMMENT ON VIEW catalog_csb.credit_problem IS 'Informational rows (not import failures): where credits must not be double-counted. CREDITS_DISPLAY_DIFFERS_GRID_VS_DETAILS is expected for linked labs and recitations whose Class Details show the bundled total.';

-- ---------------------------------------------------------------------------------------------------------
-- Requirement variants and the course-level status
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_csb.offering_requirement_variant AS
SELECT d.term_code, s.offering_id, s.component_type, count(*) AS sections,
	count(DISTINCT (d.restrictions_text, d.prerequisites_text, d.corequisites_text)) AS distinct_variants,
	count(DISTINCT d.prerequisites_text) AS distinct_prerequisite_texts, count(DISTINCT d.restrictions_text) AS distinct_restriction_texts
FROM catalog_csb.section_detail d JOIN catalog_csb.section s USING (term_code, crn)
GROUP BY d.term_code, s.offering_id, s.component_type;
COMMENT ON VIEW catalog_csb.offering_requirement_variant IS 'Sections of one offering are NOT assumed to share requirements: distinct_variants > 1 means they differ.';

CREATE OR REPLACE VIEW catalog_csb.course_term_status AS
SELECT t.term_code, c.course_id,
	CASE WHEN EXISTS (SELECT 1 FROM catalog_csb.offering o WHERE o.term_code = t.term_code AND o.course_id = c.course_id) THEN 'OFFERED'
		WHEN EXISTS (SELECT 1 FROM catalog_csb.banner_search b WHERE b.term_code = t.term_code AND b.course_id = c.course_id AND b.result = 'NOT_OFFERED')
			THEN CASE t.term_code WHEN '202710' THEN 'NOT_OFFERED_IN_SPRING_2027' ELSE 'NOT_OFFERED_IN_TERM_' || t.term_code END
		ELSE 'NOT_SEARCHED_BY_CSB_RESEARCH' END AS term_status
FROM catalog_csb.catalog_course c CROSS JOIN (SELECT DISTINCT term_code FROM catalog_csb.banner_search) t;
COMMENT ON VIEW catalog_csb.course_term_status IS 'For each catalog course: offered this term, searched with no result (NOT_OFFERED_IN_SPRING_2027), or not searched by this research (it may be covered by catalog_research; see course_coverage).';

-- ---------------------------------------------------------------------------------------------------------
-- Program structure checks
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_csb.program_problem AS
SELECT p.program_id, p.program_id AS subject, 'TOP_LEVEL_CREDITS_DO_NOT_SUM_TO_TOTAL'::text AS problem,
	(SELECT sum(g.credits) FROM catalog_csb.requirement_group g WHERE g.program_id = p.program_id AND g.parent_group_id IS NULL)::text || ' vs ' || p.total_credits::text AS detail
FROM catalog_csb.program p
WHERE (SELECT sum(g.credits) FROM catalog_csb.requirement_group g WHERE g.program_id = p.program_id AND g.parent_group_id IS NULL) IS DISTINCT FROM p.total_credits
UNION ALL
SELECT g.program_id, g.group_key, 'EMPTY_GROUP', NULL FROM catalog_csb.requirement_group g
WHERE NOT EXISTS (SELECT 1 FROM catalog_csb.requirement_option o WHERE o.group_id = g.group_id) AND NOT EXISTS (SELECT 1 FROM catalog_csb.requirement_group c WHERE c.parent_group_id = g.group_id)
	AND g.logic <> 'CREDITS'
UNION ALL
SELECT g.program_id, g.group_key, 'CHOOSE_N_FROM_UNKNOWN_LIST', o.list_key FROM catalog_csb.requirement_group g JOIN catalog_csb.requirement_option o ON o.group_id = g.group_id
WHERE o.list_key IS NOT NULL AND NOT EXISTS (SELECT 1 FROM catalog_csb.elective_list l WHERE l.list_key = o.list_key)
UNION ALL
SELECT 'LEHIGH_CSB_HONORS', s.sequence_key, 'NS_SEQUENCE_COURSE_NOT_NS_DESIGNATED', c.course_id
FROM catalog_csb.ns_sequence s JOIN catalog_csb.ns_sequence_course sc USING (sequence_key) JOIN catalog_csb.catalog_course c ON c.course_id = sc.course_id
WHERE c.catalog_status = 'CATALOG_PAGE_READ' AND NOT c.is_ns
UNION ALL
SELECT 'LEHIGH_CSB_HONORS', l.list_key, 'ELECTIVE_APPROVAL_STATUS_NOT_UNVERIFIED', m.course_id
FROM catalog_csb.elective_list l JOIN catalog_csb.elective_list_member m USING (list_key) WHERE l.status = 'APPROVAL_NOT_VERIFIED' AND m.member_status <> 'APPROVAL_NOT_VERIFIED';
COMMENT ON VIEW catalog_csb.program_problem IS 'Structural problems in the stored degree. Must be empty (an NS sequence course the catalog does not designate NS shows up here as a problem to read, not as an import failure; import_csb lists it).';

-- ---------------------------------------------------------------------------------------------------------
-- import_csb: replaces everything in catalog_csb (except the import_run history) from one payload, in the caller's transaction.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION catalog_csb.import_csb(p_payload jsonb, p_label text, p_sha256 text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	v_term text := p_payload ->> 'termCode';
	v_run bigint;
	v_bad text;
	g jsonb;
	l jsonb;
	v_gid bigint;
BEGIN
	IF v_term IS NULL OR v_term !~ '^[0-9]{6}$' THEN
		RAISE EXCEPTION 'payload.termCode must be a six-digit Banner term code' USING ERRCODE = 'invalid_parameter_value';
	END IF;
	IF jsonb_typeof(p_payload -> 'catalogCourses') IS DISTINCT FROM 'array' OR jsonb_typeof(p_payload -> 'offerings') IS DISTINCT FROM 'array'
		OR jsonb_typeof(p_payload -> 'sections') IS DISTINCT FROM 'array' OR jsonb_typeof(p_payload -> 'groups') IS DISTINCT FROM 'array' THEN
		RAISE EXCEPTION 'payload needs catalogCourses, groups, offerings and sections arrays' USING ERRCODE = 'invalid_parameter_value';
	END IF;
	-- a section the payload carries must not already exist in catalog_research with the same CRN: the two datasets are distinct sections
	IF to_regclass('catalog_research.section') IS NOT NULL THEN
		EXECUTE 'SELECT string_agg(DISTINCT s ->> ''crn'', '','') FROM jsonb_array_elements($1 -> ''sections'') s WHERE EXISTS (SELECT 1 FROM catalog_research.section x WHERE x.term_code = $2 AND x.crn = s ->> ''crn'')'
			INTO v_bad USING p_payload, v_term;
		IF v_bad IS NOT NULL THEN
			RAISE EXCEPTION 'CRN(s) % already exist in catalog_research; the CSB import will not shadow them', v_bad USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	END IF;

	TRUNCATE catalog_csb.detail_discrepancy, catalog_csb.prerequisite_comparison, catalog_csb.section_cross_list, catalog_csb.section_link_option, catalog_csb.section_rule,
		catalog_csb.section_detail, catalog_csb.grid_observation, catalog_csb.section_instructor, catalog_csb.section_arranged_meeting, catalog_csb.section_meeting,
		catalog_csb.section, catalog_csb.offering, catalog_csb.banner_search, catalog_csb.unresolved_item, catalog_csb.source_conflict, catalog_csb.ns_footnote,
		catalog_csb.ns_optional_lab, catalog_csb.ns_sequence_course, catalog_csb.ns_sequence, catalog_csb.elective_list_member, catalog_csb.elective_list,
		catalog_csb.requirement_option, catalog_csb.requirement_group, catalog_csb.program, catalog_csb.catalog_course, catalog_csb.source RESTART IDENTITY;

	INSERT INTO catalog_csb.import_run (term_code, input_sha256, input_label, counts)
	VALUES (v_term, p_sha256, p_label, jsonb_build_object(
		'catalogCourses', jsonb_array_length(p_payload -> 'catalogCourses'), 'offerings', jsonb_array_length(p_payload -> 'offerings'),
		'sections', jsonb_array_length(p_payload -> 'sections'), 'groups', jsonb_array_length(p_payload -> 'groups')))
	RETURNING run_id INTO v_run;

	INSERT INTO catalog_csb.source (source_key, level, locator, observed_on, authority, redistribution_status)
	SELECT x ->> 'key', x ->> 'level', x ->> 'locator', (x ->> 'observedOn')::date, x ->> 'authority', x ->> 'redistribution'
	FROM jsonb_array_elements(p_payload -> 'sources') x;

	INSERT INTO catalog_csb.catalog_course (course_id, subject, course_number, title, credits_text, credits_min, credits_max, attributes, prerequisite_text, prerequisite_status,
		prerequisite_expression, concurrent_text, corequisite_text, description, catalog_aliases, repeat_status, catalog_status, catalog_year, source_key)
	SELECT x ->> 'courseId', x ->> 'subject', x ->> 'number', x ->> 'title', x ->> 'creditsText', (x ->> 'creditsMin')::numeric, (x ->> 'creditsMax')::numeric,
		COALESCE(ARRAY(SELECT jsonb_array_elements_text(x -> 'attributes')), '{}'), x ->> 'prerequisiteText', x ->> 'prerequisiteStatus', NULLIF(x -> 'prerequisiteExpression', 'null'::jsonb),
		x ->> 'concurrentText', x ->> 'corequisiteText', x ->> 'description', COALESCE(ARRAY(SELECT jsonb_array_elements_text(x -> 'aliases')), '{}'), x ->> 'repeatStatus',
		x ->> 'catalogStatus', COALESCE(x ->> 'catalogYear', '2026-27'), x ->> 'sourceKey'
	FROM jsonb_array_elements(p_payload -> 'catalogCourses') x;

	INSERT INTO catalog_csb.program (program_id, name, degree_type, catalog_year, total_credits, no_pass_fail, source_key, notes)
	SELECT p_payload #>> '{program,id}', p_payload #>> '{program,name}', p_payload #>> '{program,degreeType}', p_payload #>> '{program,catalogYear}',
		(p_payload #>> '{program,totalCredits}')::numeric, (p_payload #>> '{program,noPassFail}')::boolean, p_payload #>> '{program,sourceKey}', COALESCE(p_payload #> '{program,notes}', '{}');

	-- groups arrive parents first
	FOR g IN SELECT x FROM jsonb_array_elements(p_payload -> 'groups') x LOOP
		INSERT INTO catalog_csb.requirement_group (program_id, parent_group_id, group_key, title, logic, n, credits, rule_text, status, note)
		VALUES (p_payload #>> '{program,id}',
			(SELECT group_id FROM catalog_csb.requirement_group WHERE program_id = p_payload #>> '{program,id}' AND group_key = g ->> 'parentKey'),
			g ->> 'key', g ->> 'title', g ->> 'logic', (g ->> 'n')::int, (g ->> 'credits')::numeric, g ->> 'ruleText', g ->> 'status', g ->> 'note')
		RETURNING group_id INTO v_gid;
		INSERT INTO catalog_csb.requirement_option (group_id, option_set, option_order, course_id, list_key, rule_text)
		SELECT v_gid, (o ->> 'optionSet')::int, (o ->> 'optionOrder')::int, o ->> 'courseId', o ->> 'listKey', o ->> 'ruleText'
		FROM jsonb_array_elements(COALESCE(g -> 'options', '[]'::jsonb)) o;
	END LOOP;

	FOR l IN SELECT x FROM jsonb_array_elements(p_payload -> 'lists') x LOOP
		INSERT INTO catalog_csb.elective_list (list_key, title, authority, status, statement) VALUES (l ->> 'key', l ->> 'title', l ->> 'authority', l ->> 'status', l ->> 'statement');
		INSERT INTO catalog_csb.elective_list_member (list_key, course_id, member_group, track, listed_credits_text, member_status, basis)
		SELECT l ->> 'key', m ->> 'courseId', m ->> 'memberGroup', COALESCE(m ->> 'track', ''), m ->> 'listedCreditsText', m ->> 'memberStatus', m ->> 'basis'
		FROM jsonb_array_elements(COALESCE(l -> 'members', '[]'::jsonb)) m;
	END LOOP;

	INSERT INTO catalog_csb.ns_footnote (mark, text) SELECT x ->> 'mark', x ->> 'text' FROM jsonb_array_elements(COALESCE(p_payload -> 'nsFootnotes', '[]'::jsonb)) x;
	INSERT INTO catalog_csb.ns_sequence (sequence_key, family, listed_credits_text, footnote_marks, special_rule)
	SELECT x ->> 'key', x ->> 'family', x ->> 'listedCreditsText', COALESCE(ARRAY(SELECT jsonb_array_elements_text(x -> 'footnoteMarks')), '{}'), x ->> 'specialRule'
	FROM jsonb_array_elements(COALESCE(p_payload -> 'nsSequences', '[]'::jsonb)) x;
	INSERT INTO catalog_csb.ns_sequence_course (sequence_key, ordinal, course_id)
	SELECT x ->> 'key', c.n::int, c.course FROM jsonb_array_elements(COALESCE(p_payload -> 'nsSequences', '[]'::jsonb)) x,
		jsonb_array_elements_text(x -> 'courses') WITH ORDINALITY AS c(course, n);
	INSERT INTO catalog_csb.ns_optional_lab (course_id, credits_text, applies_to_footnote)
	SELECT x ->> 'courseId', x ->> 'creditsText', x ->> 'appliesToFootnote' FROM jsonb_array_elements(COALESCE(p_payload -> 'nsLabs', '[]'::jsonb)) x;

	INSERT INTO catalog_csb.source_conflict (conflict_key, catalog_statement, other_statement, status)
	SELECT x ->> 'key', x ->> 'catalogStatement', x ->> 'otherStatement', x ->> 'status' FROM jsonb_array_elements(COALESCE(p_payload -> 'conflicts', '[]'::jsonb)) x;
	INSERT INTO catalog_csb.unresolved_item (source, subject, detail)
	SELECT x ->> 'source', x ->> 'subject', x ->> 'detail' FROM jsonb_array_elements(COALESCE(p_payload -> 'unresolved', '[]'::jsonb)) x;

	INSERT INTO catalog_csb.banner_search (term_code, search_kind, subject_label, course_number, course_id, class_count, result, source_key)
	SELECT v_term, x ->> 'kind', x ->> 'subjectLabel', x ->> 'courseNumber', x ->> 'courseId', (x ->> 'classCount')::int, x ->> 'result', x ->> 'sourceKey'
	FROM jsonb_array_elements(COALESCE(p_payload -> 'searches', '[]'::jsonb)) x;

	INSERT INTO catalog_csb.offering (term_code, offering_id, course_id, subject, course_number, title, credits_text, credits_min, credits_max, credits_status,
		administrative_type, components_status, data_source, run_id)
	SELECT v_term, x ->> 'offeringId', x ->> 'courseId', x ->> 'subject', x ->> 'number', x ->> 'title', x ->> 'creditsText', (x ->> 'creditsMin')::numeric,
		(x ->> 'creditsMax')::numeric, x ->> 'creditsStatus', x ->> 'administrativeType', x ->> 'componentsStatus', 'BANNER_CSB_DETAILS', v_run
	FROM jsonb_array_elements(p_payload -> 'offerings') x;

	INSERT INTO catalog_csb.section (term_code, crn, offering_id, section_code, component_type, schedule_type, credits_text, credits_min, credits_max, attribute_text, campus,
		grade_mode, details_credit_hours_text, credits_display_status, link_status)
	SELECT v_term, s ->> 'crn', s ->> 'offeringId', s ->> 'sectionCode', s ->> 'componentType', s ->> 'scheduleType', s ->> 'creditsText', (s ->> 'creditsMin')::numeric,
		(s ->> 'creditsMax')::numeric, s ->> 'attributeText', s ->> 'campus', s ->> 'gradeMode', s ->> 'detailsCreditHoursText', s ->> 'creditsDisplayStatus', s ->> 'linkStatus'
	FROM jsonb_array_elements(p_payload -> 'sections') s;

	INSERT INTO catalog_csb.section_meeting (term_code, crn, day_of_week, start_time, end_time, location, meeting_type, meeting_kind, start_date, end_date)
	SELECT v_term, s ->> 'crn', (m ->> 'day')::smallint, (m ->> 'start')::time, (m ->> 'end')::time, m ->> 'location', m ->> 'type', m ->> 'kind', (m ->> 'startDate')::date, (m ->> 'endDate')::date
	FROM jsonb_array_elements(p_payload -> 'sections') s, jsonb_array_elements(COALESCE(s -> 'meetings', '[]'::jsonb)) m;
	INSERT INTO catalog_csb.section_arranged_meeting (term_code, crn, meeting_type, description, start_date, end_date)
	SELECT v_term, s ->> 'crn', a ->> 'type', a ->> 'description', (a ->> 'startDate')::date, (a ->> 'endDate')::date
	FROM jsonb_array_elements(p_payload -> 'sections') s, jsonb_array_elements(COALESCE(s -> 'arranged', '[]'::jsonb)) a;
	INSERT INTO catalog_csb.section_instructor (term_code, crn, position, instructor_name, role, is_placeholder, source_key)
	SELECT v_term, s ->> 'crn', (i ->> 'position')::smallint, i ->> 'name', i ->> 'role', (i ->> 'placeholder')::boolean, s #>> '{grid,sourceKey}'
	FROM jsonb_array_elements(p_payload -> 'sections') s, jsonb_array_elements(COALESCE(s -> 'instructors', '[]'::jsonb)) i;

	INSERT INTO catalog_csb.grid_observation (term_code, crn, course_label, section_no, title, hours_grid, schedule_type, attribute, linked_cell, meetings, instructors, source_key)
	SELECT v_term, s ->> 'crn', s #>> '{grid,courseLabel}', s #>> '{grid,sectionNo}', s #>> '{grid,title}', s #>> '{grid,hoursGrid}', s #>> '{grid,scheduleType}',
		s #>> '{grid,attribute}', s #>> '{grid,linkedCell}', COALESCE(ARRAY(SELECT jsonb_array_elements_text(s #> '{grid,meetings}')), '{}'), COALESCE(s #> '{grid,instructors}', '[]'::jsonb), s #>> '{grid,sourceKey}'
	FROM jsonb_array_elements(p_payload -> 'sections') s;

	INSERT INTO catalog_csb.section_detail (term_code, crn, panels_inspected, panels_not_inspected, restrictions_text, prerequisites_text, corequisites_text, cross_list_text, linked_text,
		restriction_status, prerequisite_status, corequisite_status, source_key)
	SELECT v_term, s ->> 'crn', ARRAY(SELECT jsonb_array_elements_text(s #> '{detail,panelsInspected}')), ARRAY(SELECT jsonb_array_elements_text(s #> '{detail,panelsNotInspected}')),
		s #>> '{detail,restrictionsText}', s #>> '{detail,prerequisitesText}', s #>> '{detail,corequisitesText}', s #>> '{detail,crossListText}', s #>> '{detail,linkedText}',
		s #>> '{detail,restrictionStatus}', s #>> '{detail,prerequisiteStatus}', s #>> '{detail,corequisiteStatus}', s #>> '{detail,sourceKey}'
	FROM jsonb_array_elements(p_payload -> 'sections') s;

	INSERT INTO catalog_csb.section_rule (term_code, crn, rule_kind, polarity, rule_values, expression, min_grade_shown, source_text)
	SELECT v_term, s ->> 'crn', r ->> 'kind', r ->> 'polarity', COALESCE(ARRAY(SELECT jsonb_array_elements_text(r -> 'values')), '{}'), NULLIF(r -> 'expression', 'null'::jsonb),
		COALESCE(ARRAY(SELECT jsonb_array_elements_text(r -> 'minGradeShown')), '{}'), r ->> 'sourceText'
	FROM jsonb_array_elements(p_payload -> 'sections') s, jsonb_array_elements(COALESCE(s -> 'rules', '[]'::jsonb)) r;

	INSERT INTO catalog_csb.section_link_option (term_code, panel_crn, option_no, member_crn, member_schedule_type, member_section)
	SELECT v_term, s ->> 'crn', (k ->> 'optionNo')::smallint, k ->> 'memberCrn', k ->> 'memberScheduleType', k ->> 'memberSection'
	FROM jsonb_array_elements(p_payload -> 'sections') s, jsonb_array_elements(COALESCE(s -> 'links', '[]'::jsonb)) k;

	INSERT INTO catalog_csb.section_cross_list (term_code, crn, cross_crn, cross_subject, cross_course_number, cross_title, cross_section)
	SELECT v_term, s ->> 'crn', x ->> 'crn', x ->> 'subject', x ->> 'courseNumber', x ->> 'title', x ->> 'section'
	FROM jsonb_array_elements(p_payload -> 'sections') s, jsonb_array_elements(COALESCE(s -> 'crossList', '[]'::jsonb)) x;

	INSERT INTO catalog_csb.detail_discrepancy (term_code, crn, field, grid_value, details_value)
	SELECT v_term, s ->> 'crn', d ->> 'field', d ->> 'gridValue', d ->> 'detailsValue'
	FROM jsonb_array_elements(p_payload -> 'sections') s, jsonb_array_elements(COALESCE(s -> 'discrepancies', '[]'::jsonb)) d;

	INSERT INTO catalog_csb.prerequisite_comparison (term_code, course_id, status, crns, catalog_text, banner_text, catalog_expression, banner_expression)
	SELECT v_term, x ->> 'courseId', x ->> 'status', ARRAY(SELECT jsonb_array_elements_text(x -> 'crns')), x ->> 'catalogText', x ->> 'bannerText', NULLIF(x -> 'catalogExpression', 'null'::jsonb), NULLIF(x -> 'bannerExpression', 'null'::jsonb)
	FROM jsonb_array_elements(COALESCE(p_payload -> 'comparisons', '[]'::jsonb)) x;

	SELECT string_agg(DISTINCT m.problem || ':' || m.crn, ', ') INTO v_bad FROM catalog_csb.grid_mismatch m;
	IF v_bad IS NOT NULL THEN
		RAISE EXCEPTION 'imported data disagrees with the verbatim grid rows (%); nothing is stored', v_bad USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	SELECT string_agg(m.crn || ':' || m.stored || '<>' || m.derived, ', ') INTO v_bad FROM catalog_csb.link_status_mismatch m;
	IF v_bad IS NOT NULL THEN
		RAISE EXCEPTION 'stored link statuses disagree with the Linked Sections panels (%); nothing is stored', v_bad USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	SELECT string_agg(p.problem || ':' || p.program_id || '/' || p.subject, ', ') INTO v_bad FROM catalog_csb.program_problem p WHERE p.problem <> 'NS_SEQUENCE_COURSE_NOT_NS_DESIGNATED';
	IF v_bad IS NOT NULL THEN
		RAISE EXCEPTION 'the imported program is structurally inconsistent (%); nothing is stored', v_bad USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	RETURN v_run;
END $$;
COMMENT ON FUNCTION catalog_csb.import_csb(jsonb, text, text) IS 'Replaces the whole catalog_csb dataset from one payload; aborts if it disagrees with the verbatim grid rows or the Linked Sections panels. Touches no other schema.';
REVOKE ALL ON FUNCTION catalog_csb.import_csb(jsonb, text, text) FROM PUBLIC;
