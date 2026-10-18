-- catalog_serving: the read-only views the backend uses to serve the RESEARCHED term catalog (DB_CATALOG_SCHEMA=catalog_serving).
-- Same three views and column types as catalog_api / catalog_live (course_offering, section_offering, section_meeting), plus course_condition.
-- It only READS catalog_research and writes nothing. It serves an offering when catalog_research.offering_tier says READY or CONDITIONAL;
-- BLOCKED offerings (grid-only non-CSE courses, undecided topics) are never served. This is a team-policy decision (docs/decisions/0007):
-- a CONDITIONAL offering is served together with its conditions (course_condition), never as an eligibility guarantee.
-- Needs catalog_research installed. Install with install.sql as a non-superuser owner.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS catalog_serving;
COMMENT ON SCHEMA catalog_serving IS 'Read-only serving views over catalog_research (READY and CONDITIONAL offerings). Conditions travel with the courses; nothing here guarantees registration eligibility.';

-- Fail closed beyond the tier: an offering with any recorded evidence mismatch, no lecture, or a missing credit value is not served.
CREATE OR REPLACE VIEW catalog_serving.served_offering AS
SELECT o.term_code, o.offering_id, t.tier
FROM catalog_research.offering o
JOIN catalog_research.offering_tier t ON t.term_code = o.term_code AND t.offering_id = o.offering_id
WHERE o.data_source IN ('BANNER_EVIDENCE', 'SNAPSHOT')
	AND t.tier IN ('READY', 'CONDITIONAL')
	AND o.credits IS NOT NULL
	AND EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id AND s.component_type = 'LECTURE')
	AND NOT EXISTS (SELECT 1 FROM catalog_research.evidence_mismatch m
		JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
		WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id);

CREATE OR REPLACE VIEW catalog_serving.course_offering AS
SELECT o.term_code::varchar AS term_code, o.offering_id::varchar AS course_id, o.title::text AS title, o.credits::integer AS credits, true AS schedulable
FROM catalog_research.offering o
JOIN catalog_serving.served_offering v ON v.term_code = o.term_code AND v.offering_id = o.offering_id;

CREATE OR REPLACE VIEW catalog_serving.section_offering AS
SELECT s.term_code::varchar AS term_code, s.offering_id::varchar AS course_id, s.crn::varchar AS crn, s.section_code::varchar AS section_code,
	s.credits::integer AS credits, s.component_type::text AS component_type, s.lecture_crn::text AS lecture_crn, 'UNKNOWN'::text AS status
FROM catalog_research.section s
JOIN catalog_serving.served_offering v ON v.term_code = s.term_code AND v.offering_id = s.offering_id;

CREATE OR REPLACE VIEW catalog_serving.section_meeting AS
SELECT DISTINCT m.term_code::varchar AS term_code, m.crn::varchar AS crn, m.day_of_week::smallint AS day_of_week,
	to_char(m.start_time::interval, 'HH24:MI')::text AS start_time, to_char(m.end_time::interval, 'HH24:MI')::text AS end_time, m.location::text AS location
FROM catalog_research.section_meeting m
JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
JOIN catalog_serving.served_offering v ON v.term_code = s.term_code AND v.offering_id = s.offering_id
WHERE m.meeting_kind = 'WEEKLY';

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Conditions: what is NOT verified about a student's ability to take a served course, in words the app can show. Every row means "the app has not
-- checked this for the student"; none says the student is eligible, and none is a Registrar statement.
-- evidence_level: BANNER (what Banner shows), CATALOG, USER_CLARIFICATION (reported by the team, not confirmed by Banner), TEAM_POLICY.

CREATE OR REPLACE FUNCTION catalog_serving.course_label(course_id text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT replace(course_id, '-', ' ') $$;

-- Renders a prerequisite expression: {"op":"AND"|"OR","args":[...]} or {"course":"CSE-017","concurrent":true}.
CREATE OR REPLACE FUNCTION catalog_serving.expression_text(expr jsonb, nested boolean DEFAULT false) RETURNS text
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
	parts text;
	joined text;
BEGIN
	IF expr ? 'course' THEN
		RETURN catalog_serving.course_label(expr ->> 'course') || CASE WHEN (expr ->> 'concurrent')::boolean IS TRUE THEN ' (may be taken at the same time)' ELSE '' END;
	END IF;
	SELECT string_agg(catalog_serving.expression_text(a, true), CASE WHEN expr ->> 'op' = 'AND' THEN ' and ' ELSE ' or ' END ORDER BY ord)
		INTO parts FROM jsonb_array_elements(expr -> 'args') WITH ORDINALITY AS t(a, ord);
	joined := coalesce(parts, '');
	RETURN CASE WHEN nested AND jsonb_array_length(expr -> 'args') > 1 THEN '(' || joined || ')' ELSE joined END;
END $$;

CREATE OR REPLACE FUNCTION catalog_serving.standing_label(code text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT CASE code WHEN 'FR' THEN 'first-year' WHEN 'SO' THEN 'sophomore' WHEN 'JR' THEN 'junior' WHEN 'SR' THEN 'senior' WHEN 'GR' THEN 'graduate' ELSE code END $$;

CREATE OR REPLACE FUNCTION catalog_serving.level_label(code text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT CASE code WHEN 'UG' THEN 'undergraduate' WHEN 'GR' THEN 'graduate' ELSE code END $$;

-- Recreated (not replaced) so a rerun can change its columns; nothing depends on it.
DROP VIEW IF EXISTS catalog_serving.course_condition;
CREATE VIEW catalog_serving.course_condition AS
SELECT c.term_code, c.course_id, c.ord AS seq, c.code, c.message, c.evidence_level, 'UNVERIFIED'::text AS outcome
FROM (
	-- Prerequisites that the app can read as a rule
	SELECT r.term_code, r.offering_id AS course_id, 1 AS ord, 'PREREQUISITE'::text AS code,
		'Prerequisite: ' || catalog_serving.expression_text(r.expression) AS message, 'BANNER'::text AS evidence_level
	FROM catalog_research.eligibility_rule r
	WHERE r.rule_kind = 'PREREQUISITE' AND r.parse_status = 'STRUCTURED' AND r.expression IS NOT NULL
	UNION ALL
	-- A prerequisite Banner states only in words: shown, never checked
	SELECT r.term_code, r.offering_id, 1, 'PREREQUISITE',
		'Prerequisite listed by Banner in words only; the app does not check it. See Banner for the exact wording.', 'BANNER'
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'PREREQUISITE' AND r.parse_status = 'TEXT_ONLY'
	UNION ALL
	SELECT r.term_code, r.offering_id, 2, 'CLASS_STANDING',
		CASE r.polarity WHEN 'MUST' THEN 'Open to these class standings only: ' ELSE 'Not open to these class standings: ' END
			|| (SELECT string_agg(catalog_serving.standing_label(v), ', ' ORDER BY v) FROM unnest(r.rule_values) v), r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'CLASS_STANDING'
	UNION ALL
	SELECT r.term_code, r.offering_id, 3, 'LEVEL',
		'Open to these levels only: ' || (SELECT string_agg(catalog_serving.level_label(v), ', ' ORDER BY v) FROM unnest(r.rule_values) v), r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'LEVEL'
	UNION ALL
	SELECT r.term_code, r.offering_id, 4, 'COLLEGE',
		'Open to students in these colleges only (Banner codes): ' || (SELECT string_agg(v, ', ' ORDER BY v) FROM unnest(r.rule_values) v), r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'COLLEGE'
	UNION ALL
	SELECT r.term_code, r.offering_id, 5, 'MAJOR',
		'Open to students with these majors only (Banner codes): ' || (SELECT string_agg(v, ', ' ORDER BY v) FROM unnest(r.rule_values) v), r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'MAJOR'
	UNION ALL
	SELECT r.term_code, r.offering_id, 6, 'DEPARTMENT_APPROVAL', 'Department permission is required.', r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'DEPARTMENT_APPROVAL'
	UNION ALL
	-- Credit exclusions. SOURCES_CONFLICT is left out: the team follows the current catalog (decision of 2026-10-17), which omits that exclusion.
	SELECT x.term_code, x.offering_id, 7, 'CREDIT_EXCLUSION',
		CASE x.exclusion_kind
			WHEN 'CREDIT_AND_ENROLLMENT' THEN 'Cannot be taken with existing credit for ' || x.excluded_text || '.'
			ELSE 'No credit for both this course and ' || x.excluded_text || '.' END,
		CASE x.evidence_level WHEN 'RESEARCH_WORKBOOK' THEN 'CATALOG' ELSE x.evidence_level END
	FROM catalog_research.credit_exclusion x WHERE x.exclusion_kind <> 'SOURCES_CONFLICT'
	UNION ALL
	-- A mandatory meeting without a published time
	SELECT DISTINCT s.term_code, s.offering_id, 8, 'ARRANGED_MEETING',
		'Includes a required meeting at a time to be arranged (no published time); it is not part of the generated schedule.', 'BANNER'
	FROM catalog_research.section_arranged_meeting a JOIN catalog_research.section s ON s.term_code = a.term_code AND s.crn = a.crn
	UNION ALL
	-- Whether a lab or recitation is required
	SELECT o.term_code, o.offering_id, 9, 'LINKED_COMPONENTS',
		'Whether this course also requires a lab or recitation has not been verified.', 'BANNER'
	FROM catalog_research.offering o WHERE o.components_status = 'COMPONENTS_UNVERIFIED'
	UNION ALL
	SELECT o.term_code, o.offering_id, 9, 'LINKED_COMPONENTS',
		'The lab requirement was reported by the team and is not confirmed by Banner.', 'USER_CLARIFICATION'
	FROM catalog_research.offering o WHERE o.components_status IN ('LABS_USER_SUPPLIED', 'STANDALONE_USER_SUPPLIED')
	UNION ALL
	SELECT o.term_code, o.offering_id, 10, 'CROSS_LISTING',
		'Reported as cross-listed with another subject; this is not confirmed by Banner.', 'USER_CLARIFICATION'
	FROM catalog_research.offering o WHERE o.cross_listing_status = 'CROSS_LISTED_USER_ASSERTED_UNVERIFIED'
	UNION ALL
	-- An administrative section type Banner does not treat as an ordinary class, allowed by a recorded team decision
	SELECT o.term_code, o.offering_id, 11, 'SECTION_TYPE',
		'Banner lists this section as "' || o.administrative_type || '". The team includes it in proposed schedules; this does not mean registration is allowed.', 'TEAM_POLICY'
	FROM catalog_research.offering o WHERE o.administrative_type NOT IN ('Lecture', 'Class', 'Lab', 'Recitation')
) c
JOIN catalog_serving.served_offering v ON v.term_code = c.term_code AND v.offering_id = c.course_id
ORDER BY c.term_code, c.course_id, c.ord, c.message;
