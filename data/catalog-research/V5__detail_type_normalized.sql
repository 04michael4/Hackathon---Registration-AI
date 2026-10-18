-- catalog_research V5: Banner's Class Details calls an ordinary class section "Lecture", while the grid-staged non-CSE data calls the same
-- thing "Class" (the grid's own wording). V4's detail_mismatch compared them literally, so every inspected course would have been blocked as a
-- SCHEDULE_TYPE_DIFFERS mismatch. Compare them as the same type; every other difference still blocks. Same columns as V4; run after V4.
\set ON_ERROR_STOP on

CREATE OR REPLACE VIEW catalog_research.detail_mismatch AS
SELECT s.term_code, s.crn, 'SCHEDULE_TYPE_DIFFERS'::text AS problem, s.schedule_type AS stored, d.schedule_type AS detail
FROM catalog_research.section s JOIN catalog_research.section_detail_observation d USING (term_code, crn)
WHERE (CASE WHEN s.schedule_type IN ('Class', 'Lecture') THEN 'Lecture' ELSE s.schedule_type END)
	IS DISTINCT FROM (CASE WHEN d.schedule_type IN ('Class', 'Lecture') THEN 'Lecture' ELSE d.schedule_type END)
UNION ALL
SELECT s.term_code, s.crn, 'CREDITS_DIFFER', s.credits::text, d.credit_hours::text
FROM catalog_research.section s JOIN catalog_research.section_detail_observation d USING (term_code, crn)
WHERE d.credit_hours IS NOT NULL AND s.component_type = 'LECTURE' AND s.credits::numeric <> d.credit_hours;
