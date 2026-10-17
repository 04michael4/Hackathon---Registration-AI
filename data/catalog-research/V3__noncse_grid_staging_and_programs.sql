-- catalog_research v3: (a) a validated import path for grid-staged Banner data (the non-CSE sections, whose evidence is the Browse Classes
-- results grid, not the banner_evidence captures) and (b) degree programs with their requirement groups. Needs V1 and V2.
--
--   * Grid-staged data keeps its raw rows verbatim (grid_observation, grid_extra_meeting_observation, grid_search_result) with provenance
--     (grid_source). Every normalized row is cross-checked against them by grid_mismatch, which import_noncse_term enforces. It is never
--     pushed through the banner_evidence cross-check and never claims more than the grid shows.
--   * Nothing here is approved or schedulable: the offerings are BLOCKED (details never inspected), absent from the candidate_* views, and
--     every requirement is UNKNOWN (not collected). A "no linked sections / no restriction shown" in the grid is not proof of anything.
--   * Degree programs are separate from term offerings: a requirement option names a catalog course, which may or may not be offered.
--   * Reads only catalog_research objects; writes only catalog_research. Instructor names are never imported.
-- Rerunnable (IF NOT EXISTS / OR REPLACE). Remove with the rest of the schema: DROP SCHEMA catalog_research CASCADE;

DO $$
BEGIN
	IF to_regclass('catalog_research.offering_tier') IS NULL THEN
		RAISE EXCEPTION 'catalog_research V1 and V2 must be installed first';
	END IF;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- Provenance and raw grid rows (verbatim text, exactly as the CSV files carry them)
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS catalog_research.grid_source (
	grid_source_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code             text NOT NULL,
	origin                text NOT NULL CHECK (origin IN ('authenticated_banner', 'synthetic_fixture')),
	collected_on          date NOT NULL,
	locator               text NOT NULL CHECK (length(btrim(locator)) > 0),
	verification          text NOT NULL CHECK (verification IN ('GRID_ONLY_DETAILS_NOT_INSPECTED')),
	redistribution_status text NOT NULL CHECK (redistribution_status IN ('NOT_VERIFIED_LOCAL_ONLY', 'SYNTHETIC')),
	input_sha256          text NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
	run_id                bigint NOT NULL REFERENCES catalog_research.import_run,
	CHECK ((origin = 'authenticated_banner') = (redistribution_status = 'NOT_VERIFIED_LOCAL_ONLY'))
);
COMMENT ON TABLE catalog_research.grid_source IS 'Where grid-staged rows came from. authenticated_banner rows are local-only: redistribution is not verified.';

CREATE TABLE IF NOT EXISTS catalog_research.grid_observation (
	term_code      text NOT NULL,
	crn            text NOT NULL CHECK (crn ~ '^[0-9]{5}$'),
	grid_source_id bigint NOT NULL REFERENCES catalog_research.grid_source,
	course         text NOT NULL,       -- 'MATH 021' as the grid shows it
	section_no     text NOT NULL,
	title          text NOT NULL,
	hours_grid     text NOT NULL,
	days           text NOT NULL,       -- letters: M T W R(=Thursday) F S U
	start_time     text NOT NULL,
	end_time       text NOT NULL,
	schedule_type  text NOT NULL,       -- Class / Lab / Recitation
	building       text,
	room           text,
	start_date     text NOT NULL,
	end_date       text NOT NULL,
	linked_flag    text NOT NULL CHECK (linked_flag IN ('LINKED', 'NONE_SHOWN')),
	banner_status  text NOT NULL,
	PRIMARY KEY (term_code, crn)
);

CREATE TABLE IF NOT EXISTS catalog_research.grid_extra_meeting_observation (
	term_code      text NOT NULL,
	crn            text NOT NULL,
	grid_source_id bigint NOT NULL REFERENCES catalog_research.grid_source,
	meeting_type   text NOT NULL,       -- e.g. Common Hour Exam
	day_letter     text NOT NULL,
	start_time     text NOT NULL,
	end_time       text NOT NULL,
	start_date     text NOT NULL,
	end_date       text NOT NULL,
	building       text,
	room           text,
	PRIMARY KEY (term_code, crn, meeting_type, start_date, start_time),
	FOREIGN KEY (term_code, crn) REFERENCES catalog_research.grid_observation
);

CREATE TABLE IF NOT EXISTS catalog_research.grid_search_result (
	term_code      text NOT NULL,
	course         text NOT NULL,
	grid_source_id bigint NOT NULL REFERENCES catalog_research.grid_source,
	result         text NOT NULL,       -- what the search returned (0 classes ...); not proof of a cross-listed offering under another subject
	PRIMARY KEY (term_code, course)
);

-- ---------------------------------------------------------------------------------------------------------
-- Degree programs and their requirement groups
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS catalog_research.catalog_course (
	course_id           text PRIMARY KEY,        -- MATH-021
	subject             text NOT NULL,
	course_number       text NOT NULL,
	title               text,                    -- only when a source gave one
	catalog_status      text NOT NULL CHECK (catalog_status IN ('PROGRAM_TABLE_ONLY')),
	prerequisite_status text NOT NULL CHECK (prerequisite_status IN ('NOT_COLLECTED')),
	CHECK (course_id = subject || '-' || course_number)
);
COMMENT ON TABLE catalog_research.catalog_course IS 'Catalog courses named by degree programs or searched in the grid. prerequisite_status NOT_COLLECTED = nobody read this course''s catalog page; it is not "no prerequisite".';

CREATE TABLE IF NOT EXISTS catalog_research.program (
	program_id          text PRIMARY KEY,
	name                text NOT NULL,
	college             text,
	department          text,
	degree_type         text NOT NULL CHECK (degree_type IN ('BS', 'BA', 'MINOR')),
	catalog_year        text NOT NULL,
	total_credits_text  text,
	restriction         text,
	unknowns            jsonb NOT NULL DEFAULT '[]',     -- what the sources did not state
	conflicts           jsonb NOT NULL DEFAULT '[]',     -- sources that disagree, preserved side by side
	source_level        text NOT NULL CHECK (source_level IN ('PUBLIC_CATALOG')),
	source_locator      text NOT NULL,
	source_verification text NOT NULL,
	run_id              bigint NOT NULL REFERENCES catalog_research.import_run
);

CREATE TABLE IF NOT EXISTS catalog_research.requirement_group (
	group_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	program_id      text NOT NULL REFERENCES catalog_research.program,
	parent_group_id bigint REFERENCES catalog_research.requirement_group,
	group_key       text NOT NULL,
	logic           text NOT NULL CHECK (logic IN ('ALL', 'ANY', 'CHOOSE_N', 'CREDITS')),
	n               integer CHECK (n >= 1),
	credits_text    text,
	rule_text       text,
	status          text NOT NULL,
	note            text,
	UNIQUE (program_id, group_key),
	CHECK ((logic = 'CHOOSE_N') = (n IS NOT NULL))
);
COMMENT ON TABLE catalog_research.requirement_group IS 'ALL = every member; ANY = one of the members; CHOOSE_N = n of the members; CREDITS = a credit rule in words. Members: options (option_set) and child groups.';

CREATE TABLE IF NOT EXISTS catalog_research.program_requirement (
	program_id text NOT NULL REFERENCES catalog_research.program,
	group_id   bigint NOT NULL REFERENCES catalog_research.requirement_group,
	category   text NOT NULL,
	PRIMARY KEY (program_id, group_id)
);

CREATE TABLE IF NOT EXISTS catalog_research.requirement_course_option (
	option_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	group_id   bigint NOT NULL REFERENCES catalog_research.requirement_group,
	option_set integer NOT NULL,                       -- options sharing a set are taken together (AND); different sets are alternatives under the group's logic
	course_id  text REFERENCES catalog_research.catalog_course,
	rule_text  text,
	credits    integer,
	status     text NOT NULL,
	CHECK (course_id IS NOT NULL OR rule_text IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS catalog_research.catalog_course_exclusion (
	course_id     text NOT NULL REFERENCES catalog_research.catalog_course,
	excluded_text text NOT NULL,
	status        text NOT NULL,
	source_level  text NOT NULL CHECK (source_level IN ('PUBLIC_CATALOG')),
	PRIMARY KEY (course_id, excluded_text)
);

CREATE TABLE IF NOT EXISTS catalog_research.catalog_course_equivalence (
	course_id            text NOT NULL REFERENCES catalog_research.catalog_course,
	equivalent_course_id text NOT NULL,
	status               text NOT NULL CHECK (status IN ('CROSS_LISTED_USER_ASSERTED_UNVERIFIED')),
	PRIMARY KEY (course_id, equivalent_course_id)
);

CREATE TABLE IF NOT EXISTS catalog_research.unresolved_item (
	item_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	source  text NOT NULL,
	subject text NOT NULL,
	detail  text NOT NULL
);
COMMENT ON TABLE catalog_research.unresolved_item IS 'Open questions the import carries forward (not collected, conflicting, unverified).';

-- ---------------------------------------------------------------------------------------------------------
-- Views
-- ---------------------------------------------------------------------------------------------------------

-- Every place where a normalized grid-staged row disagrees with the verbatim grid rows. Independent of the importer: days, locations and
-- components are re-derived here in SQL. Must be empty; import_noncse_term refuses to finish otherwise.
CREATE OR REPLACE VIEW catalog_research.grid_mismatch AS
WITH staged AS (
	SELECT s.term_code, s.crn, s.offering_id, s.section_code, s.component_type, s.credits, s.lecture_crn, s.schedule_type, s.banner_link_flag, s.credit_source, o.course_id, o.title
	FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.data_source = 'BANNER_GRID_STAGING'),
grid AS (
	SELECT g.*, replace(g.course, ' ', '-') AS course_id,
		CASE g.schedule_type WHEN 'Class' THEN 'LECTURE' WHEN 'Lab' THEN 'LAB' WHEN 'Recitation' THEN 'RECITATION' END AS component,
		COALESCE(NULLIF(btrim(concat_ws(' ', NULLIF(g.building, 'None'), NULLIF(g.room, 'None'))), ''), 'TBA') AS location
	FROM catalog_research.grid_observation g),
grid_days AS (
	SELECT g.term_code, g.crn, g.schedule_type AS meeting_type, CASE d WHEN 'M' THEN 1 WHEN 'T' THEN 2 WHEN 'W' THEN 3 WHEN 'R' THEN 4 WHEN 'F' THEN 5 WHEN 'S' THEN 6 WHEN 'U' THEN 7 END AS dow,
		g.start_time::time AS st, g.end_time::time AS en, g.start_date::date AS sd, g.end_date::date AS ed, g.location
	FROM grid g CROSS JOIN LATERAL regexp_split_to_table(g.days, '') d),
extra_days AS (
	SELECT e.term_code, e.crn, e.meeting_type, CASE e.day_letter WHEN 'M' THEN 1 WHEN 'T' THEN 2 WHEN 'W' THEN 3 WHEN 'R' THEN 4 WHEN 'F' THEN 5 WHEN 'S' THEN 6 WHEN 'U' THEN 7 END AS dow,
		e.start_time::time AS st, e.end_time::time AS en, e.start_date::date AS sd, e.end_date::date AS ed,
		COALESCE(NULLIF(btrim(concat_ws(' ', NULLIF(e.building, 'None'), NULLIF(e.room, 'None'))), ''), 'TBA') AS location
	FROM catalog_research.grid_extra_meeting_observation e)
SELECT s.term_code, s.crn, 'SECTION_NOT_IN_GRID'::text AS problem, s.offering_id AS stored, NULL::text AS grid
FROM staged s WHERE NOT EXISTS (SELECT 1 FROM catalog_research.grid_observation g WHERE g.term_code = s.term_code AND g.crn = s.crn)
UNION ALL
SELECT g.term_code, g.crn, 'GRID_ROW_NOT_STORED', NULL, g.course
FROM grid g WHERE NOT EXISTS (SELECT 1 FROM staged s WHERE s.term_code = g.term_code AND s.crn = g.crn)
UNION ALL
SELECT s.term_code, s.crn, 'SECTION_FIELD_DIFFERS',
	concat_ws('/', s.course_id, s.section_code, s.component_type, s.credits, s.schedule_type, s.banner_link_flag, s.credit_source),
	concat_ws('/', g.course_id, g.section_no, g.component, g.hours_grid, g.schedule_type, g.linked_flag, 'GRID_DISPLAY')
FROM staged s JOIN grid g ON g.term_code = s.term_code AND g.crn = s.crn
WHERE (s.course_id, s.section_code, s.component_type, s.schedule_type, s.banner_link_flag, s.credit_source)
	IS DISTINCT FROM (g.course_id, g.section_no, g.component, g.schedule_type, g.linked_flag, 'GRID_DISPLAY')
	OR s.credits IS DISTINCT FROM CASE WHEN g.hours_grid ~ '^[0-9]+$' THEN g.hours_grid::smallint END
UNION ALL
SELECT s.term_code, s.crn, 'OFFERING_TITLE_DIFFERS', s.title, g.title
FROM staged s JOIN grid g ON g.term_code = s.term_code AND g.crn = s.crn WHERE s.title IS DISTINCT FROM g.title
UNION ALL
SELECT m.term_code, m.crn, 'WEEKLY_MEETING_NOT_IN_GRID', m.day_of_week || ' ' || m.start_time || '-' || m.end_time || ' ' || m.location, NULL
FROM catalog_research.section_meeting m JOIN staged s ON s.term_code = m.term_code AND s.crn = m.crn
WHERE m.meeting_kind = 'WEEKLY' AND NOT EXISTS (SELECT 1 FROM grid_days d WHERE d.term_code = m.term_code AND d.crn = m.crn AND d.dow = m.day_of_week
	AND d.st = m.start_time AND d.en = m.end_time AND d.sd = m.start_date AND d.ed = m.end_date AND d.location = m.location AND d.meeting_type = m.meeting_type)
UNION ALL
SELECT d.term_code, d.crn, 'GRID_MEETING_NOT_STORED', d.dow || ' ' || d.st || '-' || d.en || ' ' || d.location, NULL
FROM grid_days d WHERE NOT EXISTS (SELECT 1 FROM catalog_research.section_meeting m WHERE m.term_code = d.term_code AND m.crn = d.crn AND m.meeting_kind = 'WEEKLY'
	AND m.day_of_week = d.dow AND m.start_time = d.st AND m.end_time = d.en AND m.start_date = d.sd AND m.end_date = d.ed AND m.location = d.location AND m.meeting_type = d.meeting_type)
UNION ALL
SELECT m.term_code, m.crn, 'DATED_MEETING_NOT_IN_GRID', m.meeting_type || ' ' || m.start_date || ' ' || m.start_time, NULL
FROM catalog_research.section_meeting m JOIN staged s ON s.term_code = m.term_code AND s.crn = m.crn
WHERE m.meeting_kind = 'DATED_ONE_TIME' AND NOT EXISTS (SELECT 1 FROM extra_days e WHERE e.term_code = m.term_code AND e.crn = m.crn AND e.meeting_type = m.meeting_type
	AND e.dow = m.day_of_week AND e.st = m.start_time AND e.en = m.end_time AND e.sd = m.start_date AND e.ed = m.end_date AND e.location = m.location)
UNION ALL
SELECT e.term_code, e.crn, 'GRID_DATED_MEETING_NOT_STORED', e.meeting_type || ' ' || e.sd || ' ' || e.st, NULL
FROM extra_days e WHERE NOT EXISTS (SELECT 1 FROM catalog_research.section_meeting m WHERE m.term_code = e.term_code AND m.crn = e.crn AND m.meeting_kind = 'DATED_ONE_TIME'
	AND m.meeting_type = e.meeting_type AND m.day_of_week = e.dow AND m.start_time = e.st AND m.end_time = e.en AND m.start_date = e.sd AND m.end_date = e.ed AND m.location = e.location)
UNION ALL
-- an offering's credits are the one credit value its lecture sections share (all sections when it has no lecture); otherwise NULL / VARIES_BY_SECTION
SELECT o.term_code, o.offering_id, 'OFFERING_CREDITS_DIFFER', COALESCE(o.credits::text, 'NULL') || '/' || o.credits_status, v.vals::text
FROM catalog_research.offering o
CROSS JOIN LATERAL (SELECT array_agg(DISTINCT s.credits ORDER BY s.credits) AS vals FROM catalog_research.section s
	WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id
		AND (s.component_type = 'LECTURE' OR NOT EXISTS (SELECT 1 FROM catalog_research.section l WHERE l.term_code = o.term_code AND l.offering_id = o.offering_id AND l.component_type = 'LECTURE'))) v
WHERE o.data_source = 'BANNER_GRID_STAGING'
	AND ((cardinality(v.vals) = 1 AND (o.credits IS DISTINCT FROM v.vals[1] OR o.credits_status <> 'SINGLE_VALUE'))
		OR (cardinality(v.vals) > 1 AND (o.credits IS NOT NULL OR o.credits_status <> 'VARIES_BY_SECTION')));
COMMENT ON VIEW catalog_research.grid_mismatch IS 'Normalized grid-staged rows that disagree with the verbatim grid rows. Must be empty; import_noncse_term refuses to commit otherwise.';

CREATE OR REPLACE VIEW catalog_research.exam_meeting AS
SELECT m.term_code, s.offering_id, m.crn, m.meeting_type, m.start_date AS meeting_date, to_char(m.start_time, 'HH24:MI') AS start_time, to_char(m.end_time, 'HH24:MI') AS end_time, m.location
FROM catalog_research.section_meeting m JOIN catalog_research.section s USING (term_code, crn)
WHERE m.meeting_kind = 'DATED_ONE_TIME';
COMMENT ON VIEW catalog_research.exam_meeting IS 'One-time dated meetings (Common Hour Exams). Never a weekly slot, and not in the candidate_* views.';

-- The term status of each catalog course, from what was actually searched: OFFERED (an offering exists), SEARCHED_NOT_OFFERED (the grid search returned
-- no classes for that subject and number), or NOT_COLLECTED (nobody searched).
CREATE OR REPLACE VIEW catalog_research.course_term_status AS
SELECT t.term_code, c.course_id,
	CASE WHEN EXISTS (SELECT 1 FROM catalog_research.offering o WHERE o.term_code = t.term_code AND o.course_id = c.course_id) THEN 'OFFERED'
		WHEN EXISTS (SELECT 1 FROM catalog_research.grid_search_result r WHERE r.term_code = t.term_code AND replace(r.course, ' ', '-') = c.course_id) THEN 'SEARCHED_NOT_OFFERED'
		ELSE 'NOT_COLLECTED' END AS term_status
FROM catalog_research.catalog_course c CROSS JOIN (SELECT DISTINCT term_code FROM catalog_research.offering) t;

-- Course-to-degree relationships: every option of every requirement group with what is known about the course this term.
CREATE OR REPLACE VIEW catalog_research.program_course_link AS
SELECT p.program_id, g.group_key, g.logic, g.n, ro.option_set, ro.course_id, cts.term_code, cts.term_status,
	(SELECT string_agg(o.offering_id || ':' || t.tier, ', ' ORDER BY o.offering_id) FROM catalog_research.offering o
		JOIN catalog_research.offering_tier t ON t.term_code = o.term_code AND t.offering_id = o.offering_id
		WHERE o.term_code = cts.term_code AND o.course_id = ro.course_id) AS offerings_and_tiers
FROM catalog_research.requirement_course_option ro
JOIN catalog_research.requirement_group g ON g.group_id = ro.group_id
JOIN catalog_research.program p ON p.program_id = g.program_id
LEFT JOIN catalog_research.course_term_status cts ON cts.course_id = ro.course_id
WHERE ro.course_id IS NOT NULL;

-- Structural problems in the stored programs (must be empty): a CHOOSE_N asking for more than it offers, a group with nothing in it, a
-- program with no requirement.
CREATE OR REPLACE VIEW catalog_research.program_structure_problem AS
SELECT g.program_id, g.group_key, 'CHOOSE_N_EXCEEDS_MEMBERS'::text AS problem
FROM catalog_research.requirement_group g
WHERE g.logic = 'CHOOSE_N' AND g.n > (SELECT count(DISTINCT option_set) FROM catalog_research.requirement_course_option ro WHERE ro.group_id = g.group_id)
	+ (SELECT count(*) FROM catalog_research.requirement_group c WHERE c.parent_group_id = g.group_id)
UNION ALL
SELECT g.program_id, g.group_key, 'EMPTY_GROUP'
FROM catalog_research.requirement_group g
WHERE g.logic <> 'CREDITS' AND NOT EXISTS (SELECT 1 FROM catalog_research.requirement_course_option ro WHERE ro.group_id = g.group_id)
	AND NOT EXISTS (SELECT 1 FROM catalog_research.requirement_group c WHERE c.parent_group_id = g.group_id)
UNION ALL
SELECT p.program_id, p.program_id, 'PROGRAM_WITHOUT_REQUIREMENTS'
FROM catalog_research.program p WHERE NOT EXISTS (SELECT 1 FROM catalog_research.program_requirement pr WHERE pr.program_id = p.program_id)
UNION ALL
SELECT g.program_id, g.group_key, 'CHILD_GROUP_IN_OTHER_PROGRAM'
FROM catalog_research.requirement_group g JOIN catalog_research.requirement_group par ON par.group_id = g.parent_group_id WHERE par.program_id <> g.program_id;

-- ---------------------------------------------------------------------------------------------------------
-- import_noncse_term: replaces the GRID-STAGED offerings of one term from a normalized payload plus the verbatim grid rows, in the caller's
-- transaction, and refuses to finish if the result disagrees with the verbatim rows. Research-sourced offerings are never touched.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION catalog_research.import_noncse_term(p_payload jsonb, p_label text, p_sha256 text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	v_term text := p_payload ->> 'termCode';
	v_run bigint;
	v_src bigint;
	v_off text[];
	v_crns text[];
	v_bad text;
BEGIN
	IF v_term IS NULL OR v_term !~ '^[0-9]{6}$' THEN
		RAISE EXCEPTION 'payload.termCode must be a six-digit Banner term code' USING ERRCODE = 'invalid_parameter_value';
	END IF;
	IF jsonb_typeof(p_payload -> 'offerings') IS DISTINCT FROM 'array' OR jsonb_array_length(p_payload -> 'offerings') = 0
		OR jsonb_typeof(p_payload -> 'gridRows') IS DISTINCT FROM 'array' THEN
		RAISE EXCEPTION 'payload needs offerings and gridRows arrays' USING ERRCODE = 'invalid_parameter_value';
	END IF;
	IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_payload -> 'offerings') o(j) WHERE o.j ->> 'dataSource' <> 'BANNER_GRID_STAGING') THEN
		RAISE EXCEPTION 'import_noncse_term takes only BANNER_GRID_STAGING offerings' USING ERRCODE = 'invalid_parameter_value';
	END IF;
	-- a grid CRN may never collide with a research-sourced section (the two datasets are distinct sections)
	SELECT string_agg(DISTINCT s ->> 'crn', ',') INTO v_bad
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'sections') s
	WHERE EXISTS (SELECT 1 FROM catalog_research.section x JOIN catalog_research.offering xo USING (term_code, offering_id)
		WHERE x.term_code = v_term AND x.crn = s ->> 'crn' AND xo.data_source <> 'BANNER_GRID_STAGING');
	IF v_bad IS NOT NULL THEN
		RAISE EXCEPTION 'CRN(s) % already belong to research-sourced sections', v_bad USING ERRCODE = 'integrity_constraint_violation';
	END IF;

	SELECT COALESCE(array_agg(offering_id), '{}') INTO v_off FROM catalog_research.offering WHERE term_code = v_term AND data_source = 'BANNER_GRID_STAGING';
	SELECT COALESCE(array_agg(crn), '{}') INTO v_crns FROM catalog_research.section WHERE term_code = v_term AND offering_id = ANY (v_off);
	DELETE FROM catalog_research.section_meeting WHERE term_code = v_term AND crn = ANY (v_crns);
	DELETE FROM catalog_research.course_alias WHERE term_code = v_term AND offering_id = ANY (v_off);
	DELETE FROM catalog_research.requirement_status WHERE term_code = v_term AND offering_id = ANY (v_off);
	DELETE FROM catalog_research.section WHERE term_code = v_term AND crn = ANY (v_crns) AND component_type <> 'LECTURE';
	DELETE FROM catalog_research.section WHERE term_code = v_term AND crn = ANY (v_crns);
	DELETE FROM catalog_research.offering WHERE term_code = v_term AND offering_id = ANY (v_off);
	DELETE FROM catalog_research.grid_extra_meeting_observation WHERE term_code = v_term;
	DELETE FROM catalog_research.grid_observation WHERE term_code = v_term;
	DELETE FROM catalog_research.grid_search_result WHERE term_code = v_term;
	DELETE FROM catalog_research.grid_source WHERE term_code = v_term;     -- the import_run rows keep the history; the stored rows are one snapshot

	INSERT INTO catalog_research.import_run (kind, term_code, input_sha256, input_label, offering_count, section_count)
	SELECT 'GRID_STAGING', v_term, p_sha256, p_label, jsonb_array_length(p_payload -> 'offerings'),
		(SELECT count(*) FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'sections'))
	RETURNING run_id INTO v_run;
	INSERT INTO catalog_research.grid_source (term_code, origin, collected_on, locator, verification, redistribution_status, input_sha256, run_id)
	VALUES (v_term, p_payload #>> '{source,origin}', (p_payload #>> '{source,collectedOn}')::date, p_payload #>> '{source,locator}', p_payload #>> '{source,verification}',
		p_payload #>> '{source,redistribution}', p_sha256, v_run) RETURNING grid_source_id INTO v_src;

	INSERT INTO catalog_research.grid_observation (term_code, crn, grid_source_id, course, section_no, title, hours_grid, days, start_time, end_time, schedule_type,
		building, room, start_date, end_date, linked_flag, banner_status)
	SELECT g ->> 'term', g ->> 'crn', v_src, g ->> 'course', g ->> 'section', g ->> 'title', g ->> 'hours_grid', g ->> 'days', g ->> 'start', g ->> 'end', g ->> 'type',
		g ->> 'building', g ->> 'room', g ->> 'start_date', g ->> 'end_date', g ->> 'linked_flag', g ->> 'status'
	FROM jsonb_array_elements(p_payload -> 'gridRows') g;
	INSERT INTO catalog_research.grid_extra_meeting_observation (term_code, crn, grid_source_id, meeting_type, day_letter, start_time, end_time, start_date, end_date, building, room)
	SELECT x ->> 'term', x ->> 'crn', v_src, x ->> 'meeting_type', x ->> 'day', x ->> 'start', x ->> 'end', x ->> 'start_date', x ->> 'end_date', x ->> 'building', x ->> 'room'
	FROM jsonb_array_elements(COALESCE(p_payload -> 'extraMeetings', '[]'::jsonb)) x;
	INSERT INTO catalog_research.grid_search_result (term_code, course, grid_source_id, result)
	SELECT n ->> 'term', n ->> 'course', v_src, n ->> 'result' FROM jsonb_array_elements(COALESCE(p_payload -> 'noSections', '[]'::jsonb)) n;
	IF EXISTS (SELECT 1 FROM catalog_research.grid_observation WHERE grid_source_id = v_src AND term_code <> v_term) THEN
		RAISE EXCEPTION 'a grid row belongs to another term' USING ERRCODE = 'integrity_constraint_violation';
	END IF;

	INSERT INTO catalog_research.offering (term_code, offering_id, course_id, subject, course_number, topic, title, credits, credits_status, administrative_type,
		data_source, components_status, required_lab_count, cross_listing_status, identity_status, run_id)
	SELECT v_term, j ->> 'offeringId', (j ->> 'subject') || '-' || (j ->> 'number'), j ->> 'subject', j ->> 'number', NULL, j ->> 'title', (j ->> 'credits')::smallint,
		j ->> 'creditsStatus', j ->> 'administrativeType', 'BANNER_GRID_STAGING', 'COMPONENTS_UNVERIFIED', NULL, j ->> 'crossListingStatus', 'SINGLE', v_run
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j);

	INSERT INTO catalog_research.section (term_code, crn, offering_id, section_code, component_type, credits, credit_source, lecture_crn, link_status, banner_link_flag, schedule_type)
	SELECT v_term, s ->> 'crn', o.j ->> 'offeringId', s ->> 'sectionCode', s ->> 'component', (s ->> 'credits')::smallint, 'GRID_DISPLAY', s ->> 'lectureCrn',
		s ->> 'linkStatus', s ->> 'bannerLinkFlag', s ->> 'scheduleType'
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'sections') s;

	INSERT INTO catalog_research.section_meeting (term_code, crn, day_of_week, start_time, end_time, location, meeting_type, meeting_kind, start_date, end_date)
	SELECT v_term, s ->> 'crn', (m ->> 'day')::smallint, (m ->> 'start')::time, (m ->> 'end')::time, m ->> 'location', m ->> 'type', m ->> 'kind',
		(m ->> 'startDate')::date, (m ->> 'endDate')::date
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'sections') s, jsonb_array_elements(s -> 'meetings') m;

	INSERT INTO catalog_research.course_alias (term_code, offering_id, alias_subject, alias_number, alias_status)
	SELECT v_term, o.j ->> 'offeringId', a ->> 'subject', a ->> 'number', a ->> 'status'
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'aliases') a;

	-- Nothing about requirements was collected for these sections: every kind is UNKNOWN, never "none".
	INSERT INTO catalog_research.requirement_status (term_code, offering_id, requirement_type, status, evidence_level, source_text)
	SELECT v_term, o.j ->> 'offeringId', k, 'UNKNOWN', 'NONE', NULL
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j) CROSS JOIN (VALUES ('PREREQUISITE'), ('COREQUISITE'), ('ENROLLMENT_RESTRICTION')) r(k);

	SELECT string_agg(DISTINCT m.problem || ':' || m.crn, ', ') INTO v_bad FROM catalog_research.grid_mismatch m WHERE m.term_code = v_term;
	IF v_bad IS NOT NULL THEN
		RAISE EXCEPTION 'imported data disagrees with the verbatim grid rows (%); nothing is stored', v_bad USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	RETURN v_run;
END $$;
COMMENT ON FUNCTION catalog_research.import_noncse_term(jsonb, text, text) IS 'Replaces the grid-staged offerings of one term; aborts if the result disagrees with the verbatim grid rows.';

-- ---------------------------------------------------------------------------------------------------------
-- import_degree_programs: replaces the stored programs named in the payload (and the catalog courses, exclusions, equivalences and open items
-- they carry) in the caller's transaction. Group keys are unique within a program; groups arrive as a tree.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION catalog_research.import_degree_programs(p_payload jsonb, p_label text, p_sha256 text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	v_run bigint;
	v_prog jsonb;
	v_req jsonb;
	v_bad text;
BEGIN
	IF jsonb_typeof(p_payload -> 'programs') IS DISTINCT FROM 'array' OR jsonb_array_length(p_payload -> 'programs') = 0 THEN
		RAISE EXCEPTION 'payload.programs must be a non-empty array' USING ERRCODE = 'invalid_parameter_value';
	END IF;
	DELETE FROM catalog_research.requirement_course_option WHERE group_id IN (SELECT group_id FROM catalog_research.requirement_group
		WHERE program_id IN (SELECT j ->> 'id' FROM jsonb_array_elements(p_payload -> 'programs') j));
	DELETE FROM catalog_research.program_requirement WHERE program_id IN (SELECT j ->> 'id' FROM jsonb_array_elements(p_payload -> 'programs') j);
	DELETE FROM catalog_research.requirement_group WHERE program_id IN (SELECT j ->> 'id' FROM jsonb_array_elements(p_payload -> 'programs') j) AND parent_group_id IS NOT NULL;
	DELETE FROM catalog_research.requirement_group WHERE program_id IN (SELECT j ->> 'id' FROM jsonb_array_elements(p_payload -> 'programs') j);
	DELETE FROM catalog_research.program WHERE program_id IN (SELECT j ->> 'id' FROM jsonb_array_elements(p_payload -> 'programs') j);
	DELETE FROM catalog_research.catalog_course_equivalence;
	DELETE FROM catalog_research.catalog_course_exclusion;
	DELETE FROM catalog_research.unresolved_item WHERE source = 'PROGRAM_IMPORT';

	INSERT INTO catalog_research.import_run (kind, term_code, input_sha256, input_label, offering_count, section_count)
	VALUES ('PROGRAMS', NULL, p_sha256, p_label, jsonb_array_length(p_payload -> 'programs'), 0) RETURNING run_id INTO v_run;

	INSERT INTO catalog_research.catalog_course (course_id, subject, course_number, title, catalog_status, prerequisite_status)
	SELECT replace(c ->> 'code', ' ', '-'), c ->> 'subject', c ->> 'number', c ->> 'title', c ->> 'catalogStatus', c ->> 'prerequisiteStatus'
	FROM jsonb_array_elements(p_payload -> 'courses') c
	ON CONFLICT (course_id) DO UPDATE SET title = EXCLUDED.title, catalog_status = EXCLUDED.catalog_status, prerequisite_status = EXCLUDED.prerequisite_status;

	FOR v_prog IN SELECT j FROM jsonb_array_elements(p_payload -> 'programs') j LOOP
		INSERT INTO catalog_research.program (program_id, name, college, department, degree_type, catalog_year, total_credits_text, restriction, unknowns, conflicts,
			source_level, source_locator, source_verification, run_id)
		VALUES (v_prog ->> 'id', v_prog ->> 'name', v_prog ->> 'college', v_prog ->> 'department', v_prog ->> 'degreeType', v_prog ->> 'catalogYear', v_prog ->> 'totalCreditsText',
			v_prog ->> 'restriction', COALESCE(v_prog -> 'unknowns', '[]'), COALESCE(v_prog -> 'conflicts', '[]'), 'PUBLIC_CATALOG',
			p_payload #>> '{source,locator}', p_payload #>> '{source,verification}', v_run);
		FOR v_req IN SELECT r FROM jsonb_array_elements(v_prog -> 'requirements') r LOOP
			PERFORM catalog_research._import_requirement_group(v_prog ->> 'id', NULL, v_req, v_req ->> 'category');
		END LOOP;
	END LOOP;

	INSERT INTO catalog_research.catalog_course_exclusion (course_id, excluded_text, status, source_level)
	SELECT replace(x ->> 'course', ' ', '-'), x ->> 'excluded', x ->> 'status', 'PUBLIC_CATALOG' FROM jsonb_array_elements(COALESCE(p_payload -> 'exclusions', '[]'::jsonb)) x;
	INSERT INTO catalog_research.catalog_course_equivalence (course_id, equivalent_course_id, status)
	SELECT replace(x ->> 'course', ' ', '-'), replace(x ->> 'equivalent', ' ', '-'), x ->> 'status' FROM jsonb_array_elements(COALESCE(p_payload -> 'equivalences', '[]'::jsonb)) x;
	INSERT INTO catalog_research.unresolved_item (source, subject, detail)
	SELECT 'PROGRAM_IMPORT', u ->> 'subject', u ->> 'detail' FROM jsonb_array_elements(COALESCE(p_payload -> 'unresolved', '[]'::jsonb)) u;

	SELECT string_agg(DISTINCT problem || ':' || program_id || '/' || group_key, ', ') INTO v_bad FROM catalog_research.program_structure_problem;
	IF v_bad IS NOT NULL THEN
		RAISE EXCEPTION 'the imported programs are structurally inconsistent (%); nothing is stored', v_bad USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	RETURN v_run;
END $$;

CREATE OR REPLACE FUNCTION catalog_research._import_requirement_group(p_program text, p_parent bigint, p_group jsonb, p_category text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	v_id bigint;
	v_child jsonb;
BEGIN
	INSERT INTO catalog_research.requirement_group (program_id, parent_group_id, group_key, logic, n, credits_text, rule_text, status, note)
	VALUES (p_program, p_parent, p_group ->> 'key', p_group ->> 'logic', (p_group ->> 'n')::int, p_group ->> 'credits_text', p_group ->> 'rule_text', p_group ->> 'status', p_group ->> 'note')
	RETURNING group_id INTO v_id;
	INSERT INTO catalog_research.requirement_course_option (group_id, option_set, course_id, rule_text, credits, status)
	SELECT v_id, (o ->> 'option_set')::int, replace(o ->> 'course_code', ' ', '-'), o ->> 'rule_text', (o ->> 'credits')::int, o ->> 'status'
	FROM jsonb_array_elements(COALESCE(p_group -> 'options', '[]'::jsonb)) o;
	IF p_parent IS NULL THEN
		INSERT INTO catalog_research.program_requirement (program_id, group_id, category) VALUES (p_program, v_id, p_category);
	END IF;
	FOR v_child IN SELECT c FROM jsonb_array_elements(COALESCE(p_group -> 'children', '[]'::jsonb)) c LOOP
		PERFORM catalog_research._import_requirement_group(p_program, v_id, v_child, NULL);
	END LOOP;
	RETURN v_id;
END $$;

REVOKE ALL ON FUNCTION catalog_research.import_noncse_term(jsonb, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_research.import_degree_programs(jsonb, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_research._import_requirement_group(text, bigint, jsonb, text) FROM PUBLIC;
