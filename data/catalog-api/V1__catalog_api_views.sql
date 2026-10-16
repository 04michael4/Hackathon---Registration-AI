-- catalog_api: read-only views that flatten David's cse007_snapshot tables into exactly what the Spring Boot
-- CourseCatalog and SectionCatalog need. Version 1.
--
-- * Additive: creates schema catalog_api and five views. Never creates, alters, drops or writes any
--   cse007_snapshot object. Needs cse007_snapshot to be loaded first (data/cse007-snapshot/database.sql).
-- * Safe to rerun: CREATE SCHEMA IF NOT EXISTS and CREATE OR REPLACE VIEW. To change a view's columns, add a
--   V2 file instead of editing this one once it is merged.
-- * Remove (views only): DROP SCHEMA catalog_api CASCADE;
-- * Term mapping (API "2027-SPRING" <-> Banner "202710") is NOT done here. The backend maps it with an explicit
--   property and queries by term_code; no formula derives one from the other.
-- * Fail closed: a course reaches section_offering / section_meeting only when all six readiness rules hold for
--   every one of its sections (see schedulable_course). A course that fails is absent there, so a lab is never
--   sent to the scheduler with a NULL lecture link by mistake.

DO $$
BEGIN
	IF to_regclass('cse007_snapshot.course') IS NULL OR to_regclass('cse007_snapshot.term') IS NULL
		OR to_regclass('cse007_snapshot.section') IS NULL OR to_regclass('cse007_snapshot.section_link') IS NULL
		OR to_regclass('cse007_snapshot.section_component_group') IS NULL OR to_regclass('cse007_snapshot.meeting') IS NULL
		OR to_regclass('cse007_snapshot.data_issue') IS NULL THEN
		RAISE EXCEPTION 'schema cse007_snapshot is missing or incomplete; load data/cse007-snapshot/database.sql first';
	END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS catalog_api;

-- One row per section with its lecture link resolved to a CRN.
-- confirmed_parents counts confirmed links in which this section is the lab/recitation side.
CREATE OR REPLACE VIEW catalog_api.section_link_resolved AS
SELECT s.section_id, s.course_id, s.term_id, s.crn, s.section_code, s.component_type, s.credits,
	s.credits_verification_status, s.link_status,
	count(l.link_id) FILTER (WHERE l.verification_status IN ('PAIR_CONFIRMED', 'USER_CONFIRMED')) AS confirmed_parents,
	min(p.crn) FILTER (WHERE l.verification_status IN ('PAIR_CONFIRMED', 'USER_CONFIRMED')) AS parent_crn,
	bool_and(p.component_type = 'LECTURE') FILTER (WHERE l.link_id IS NOT NULL) AS parent_is_lecture
FROM cse007_snapshot.section s
LEFT JOIN cse007_snapshot.section_link l ON l.compatible_section_id = s.section_id
LEFT JOIN cse007_snapshot.section p ON p.section_id = l.primary_section_id
GROUP BY s.section_id;

-- Courses whose sections are safe to schedule: every rule must hold for every section of the course.
-- COALESCE(..., false) everywhere, because bool_and ignores NULLs and would otherwise fail open.
CREATE OR REPLACE VIEW catalog_api.schedulable_course AS
SELECT r.course_id, r.term_id
FROM catalog_api.section_link_resolved r
GROUP BY r.course_id, r.term_id
HAVING
	-- 1. Credits known, whole, and not awaiting review (no repeated web-page credits on labs).
	bool_and(COALESCE(r.credits IS NOT NULL AND r.credits = trunc(r.credits)
		AND r.credits_verification_status <> 'NEEDS_REVIEW', false))
	-- 2. Every non-lecture section has exactly one confirmed parent, and that parent is a lecture.
	--    Unknown links are never treated as standalone: TARGET_UNKNOWN or zero parents fails here.
	AND bool_and(COALESCE(r.component_type = 'LECTURE' OR (r.confirmed_parents = 1 AND r.parent_is_lecture
		AND r.link_status <> 'TARGET_UNKNOWN'), false))
	-- 3. A lecture is never linked as another section's component.
	AND bool_and(COALESCE(r.component_type <> 'LECTURE' OR r.confirmed_parents = 0, false))
	-- 4. Every lecture with linked sections has a confirmed "exactly one per group" rule (what the engine enforces).
	AND bool_and(r.component_type <> 'LECTURE' OR NOT EXISTS (
		SELECT 1 FROM cse007_snapshot.section_link l
		LEFT JOIN cse007_snapshot.section_component_group g
			ON g.primary_section_id = l.primary_section_id AND g.required_component_group = l.required_component_group
		WHERE l.primary_section_id = r.section_id
			AND (g.primary_section_id IS NULL OR g.required_count IS DISTINCT FROM 1 OR g.assessment_status <> 'USER_CONFIRMED')))
	-- 5. No OPEN warning recorded against any of its sections.
	AND bool_and(NOT EXISTS (SELECT 1 FROM cse007_snapshot.data_issue i
		WHERE i.section_id = r.section_id AND i.status = 'OPEN' AND i.severity = 'WARNING'))
	-- 6. A lecture's own link status must not be unknown. TARGET_UNKNOWN means we cannot say whether it has
	--    labs, so it is never treated as a standalone lecture. (A verified-standalone status does not exist in
	--    the schema yet; until David adds one, lecture-only courses stay out of scheduling.)
	AND bool_and(COALESCE(r.component_type <> 'LECTURE' OR r.link_status <> 'TARGET_UNKNOWN', false));

-- Courses offered in a term (CourseSummary fields), plus whether they are schedulable. Credits must be a
-- whole number (CourseSummary.credits is an int); a NULL or fractional credit hides the course rather than guessing.
CREATE OR REPLACE VIEW catalog_api.course_offering AS
SELECT DISTINCT t.term_code, c.code AS course_id, c.title, c.credits::int AS credits,
	(sc.course_id IS NOT NULL) AS schedulable
FROM cse007_snapshot.course c
JOIN cse007_snapshot.section s ON s.course_id = c.course_id
JOIN cse007_snapshot.term t ON t.term_id = s.term_id
LEFT JOIN catalog_api.schedulable_course sc ON sc.course_id = c.course_id AND sc.term_id = t.term_id
WHERE c.title IS NOT NULL AND c.credits IS NOT NULL AND c.credits = trunc(c.credits);

-- Sections of schedulable courses, shaped like CatalogSection minus term (the backend sets the API term).
-- lecture_crn is the parent lecture's CRN for linked sections and NULL for lectures.
-- status is always UNKNOWN: there is no live open/closed data, and enrollment_snapshot is not live.
CREATE OR REPLACE VIEW catalog_api.section_offering AS
SELECT t.term_code, c.code AS course_id, r.crn, r.section_code, r.credits::int AS credits, r.component_type,
	CASE WHEN r.component_type = 'LECTURE' THEN NULL ELSE r.parent_crn END AS lecture_crn,
	'UNKNOWN'::text AS status
FROM catalog_api.section_link_resolved r
JOIN catalog_api.schedulable_course sc ON sc.course_id = r.course_id AND sc.term_id = r.term_id
JOIN cse007_snapshot.course c ON c.course_id = r.course_id
JOIN cse007_snapshot.term t ON t.term_id = r.term_id;

-- Meetings of those sections, one row per day as stored. Times as HH:mm text for the API.
CREATE OR REPLACE VIEW catalog_api.section_meeting AS
SELECT t.term_code, s.crn, m.day_of_week, to_char(m.start_time, 'HH24:MI') AS start_time,
	to_char(m.end_time, 'HH24:MI') AS end_time, m.location
FROM cse007_snapshot.meeting m
JOIN cse007_snapshot.section s ON s.section_id = m.section_id
JOIN cse007_snapshot.term t ON t.term_id = s.term_id
JOIN catalog_api.schedulable_course sc ON sc.course_id = s.course_id AND sc.term_id = s.term_id;

COMMENT ON SCHEMA catalog_api IS 'Read-only API views over cse007_snapshot for CourseCatalog/SectionCatalog. Fail-closed readiness.';
COMMENT ON VIEW catalog_api.course_offering IS 'Courses offered per Banner term_code. schedulable = passes all readiness rules. Credits must be whole numbers.';
COMMENT ON VIEW catalog_api.section_offering IS 'Sections of schedulable courses only. lecture_crn is NULL for lectures. status is always UNKNOWN.';
COMMENT ON VIEW catalog_api.section_meeting IS 'One row per meeting day, for sections of schedulable courses. Times are HH:mm text.';
COMMENT ON VIEW catalog_api.schedulable_course IS 'Courses where every section passes all six readiness rules.';
COMMENT ON VIEW catalog_api.section_link_resolved IS 'Internal helper: each section with its confirmed lecture parent resolved to a CRN.';
