-- catalog_live v1: what the backend can read once promotion is switched on (app.catalog.database.schema=catalog_live).
-- The three views the adapter reads from catalog_api (course_offering, section_offering, section_meeting) with the same
-- columns and types, over TWO sources (schedulable_course is code-keyed here, and section_link_resolved is not provided):
--   1. catalog_api (David's verified snapshot, all six readiness rules) - unchanged and always preferred;
--   2. catalog_promoted.servable_* (approved promotions that STILL pass every gate against current evidence).
-- A course or CRN already served by catalog_api is never served a second time. catalog_api itself is not touched.
-- Read-only views. Remove: DROP SCHEMA catalog_live CASCADE;

DO $$
BEGIN
	IF to_regclass('catalog_promoted.servable_course') IS NULL OR to_regclass('catalog_api.course_offering') IS NULL THEN
		RAISE EXCEPTION 'catalog_promoted and catalog_api must be installed first';
	END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS catalog_live;

CREATE OR REPLACE VIEW catalog_live.course_offering AS
SELECT o.term_code, o.course_id, o.title, o.credits, o.schedulable FROM catalog_api.course_offering o
UNION ALL
SELECT sc.term_code, sc.course_id, sc.title, sc.credits, true FROM catalog_promoted.servable_course sc
WHERE NOT EXISTS (SELECT 1 FROM catalog_api.course_offering o WHERE o.term_code = sc.term_code AND o.course_id = sc.course_id);

CREATE OR REPLACE VIEW catalog_live.section_offering AS
SELECT o.term_code, o.course_id, o.crn, o.section_code, o.credits, o.component_type, o.lecture_crn, o.status FROM catalog_api.section_offering o
UNION ALL
SELECT s.term_code, s.course_id, s.crn, s.section_code, s.credits, s.component_type, s.parent_crn, 'UNKNOWN'::text
FROM catalog_promoted.servable_section s
WHERE NOT EXISTS (SELECT 1 FROM catalog_api.course_offering c WHERE c.term_code = s.term_code AND c.course_id = s.course_id)
	AND NOT EXISTS (SELECT 1 FROM catalog_api.section_offering o WHERE o.term_code = s.term_code AND o.crn = s.crn);

CREATE OR REPLACE VIEW catalog_live.section_meeting AS
SELECT m.term_code, m.crn, m.day_of_week, m.start_time, m.end_time, m.location FROM catalog_api.section_meeting m
UNION ALL
SELECT pm.term_code, pm.crn, pm.day_of_week::smallint, to_char(pm.start_time, 'HH24:MI'), to_char(pm.end_time, 'HH24:MI'), pm.location
FROM catalog_promoted.servable_meeting pm
WHERE NOT EXISTS (SELECT 1 FROM catalog_api.section_offering o WHERE o.term_code = pm.term_code AND o.crn = pm.crn)
	AND NOT EXISTS (SELECT 1 FROM catalog_api.section_meeting m WHERE m.term_code = pm.term_code AND m.crn = pm.crn);

CREATE OR REPLACE VIEW catalog_live.schedulable_course AS
SELECT co.term_code, co.course_id FROM catalog_live.course_offering co WHERE co.schedulable;

-- Where each offered course comes from, for audit.
CREATE OR REPLACE VIEW catalog_live.offering_source AS
SELECT o.term_code, o.course_id, 'SNAPSHOT'::text AS source, NULL::bigint AS promotion_id FROM catalog_api.course_offering o
UNION ALL
SELECT sc.term_code, sc.course_id, 'PROMOTED', sc.promotion_id FROM catalog_promoted.servable_course sc
WHERE NOT EXISTS (SELECT 1 FROM catalog_api.course_offering o WHERE o.term_code = sc.term_code AND o.course_id = sc.course_id);

-- Registration requirements preserved with promoted sections (verbatim). Not read by the scheduler today.
CREATE OR REPLACE VIEW catalog_live.section_requirement AS
SELECT r.term_code, r.course_id, r.crn, r.requirement_kind, r.requirement_text FROM catalog_promoted.servable_requirement r
WHERE NOT EXISTS (SELECT 1 FROM catalog_api.course_offering o WHERE o.term_code = r.term_code AND o.course_id = r.course_id);

COMMENT ON SCHEMA catalog_live IS 'Read-only views the backend reads when promotion is enabled: catalog_api plus currently-servable promoted courses. No duplicates.';
COMMENT ON VIEW catalog_live.course_offering IS 'catalog_api.course_offering plus servable promoted courses (schedulable = true).';
