-- Readiness tests for catalog_api. Runs in ONE transaction and ROLLS BACK at the end, so nothing persists:
-- not the probe rows, and not the views if they were created here. Works before or after install.
-- Each probe breaks one thing in David's data; the course must then disappear from section_offering.
--   psql -X -v ON_ERROR_STOP=1 -f data/catalog-api/test_readiness.sql
--
-- Rule -> probe:
--   1 credits known, whole, reviewed ........ 'lab 060 credits unresolved'; DEMO-900 (NULL lab credits)
--   2 each lab has one confirmed lecture .... 'lab 062 loses its link'; 'lab 062 link status unknown'; DEMO-900
--   3 a lecture is never someone's component  'a lecture linked as a component'
--   4 confirmed 'exactly one lab' rule ...... 'component group unconfirmed'
--   5 no OPEN warning on its sections ....... 'open warning on lab 060'
--   6 lecture link status not unknown ....... 'lecture with unknown link status'
\set ON_ERROR_STOP on
BEGIN;
-- Works both before the views are installed (creates them in this transaction) and after (tests the installed views).
SELECT NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'catalog_api') AS need_install \gset
\if :need_install
\ir V1__catalog_api_views.sql
\endif

\echo '--- Q2 listCourses(term_code 202710)'
SELECT course_id, title, credits, schedulable FROM catalog_api.course_offering WHERE term_code = '202710' ORDER BY course_id;
\echo '--- Q4 sectionsFor(202710, [CSE-007])'
SELECT course_id, crn, section_code, credits, component_type, lecture_crn, status
FROM catalog_api.section_offering WHERE term_code = '202710' AND course_id = ANY (ARRAY['CSE-007']) ORDER BY crn;
\echo '--- Q5 meetings, grouped by identical time and location'
SELECT crn, array_agg(day_of_week ORDER BY day_of_week) AS days, start_time, end_time, location
FROM catalog_api.section_meeting WHERE term_code = '202710' AND crn = ANY (ARRAY['12275', '12638', '13132'])
GROUP BY crn, start_time, end_time, location ORDER BY crn, start_time, end_time, location;

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM cse007_snapshot.term WHERE term_code = '202710')
		OR EXISTS (SELECT 1 FROM cse007_snapshot.term WHERE term_code = '2027-SPRING') THEN
		RAISE EXCEPTION 'term: expected Banner code 202710 only';
	END IF;
	IF (SELECT array_agg(course_id || ':' || title || ':' || credits || ':' || schedulable) FROM catalog_api.course_offering)
		IS DISTINCT FROM ARRAY['CSE-007:Introduction to Programming:4:true'] THEN
		RAISE EXCEPTION 'course_offering mismatch';
	END IF;
	IF (SELECT array_agg(crn || ':' || component_type || ':' || credits || ':' || coalesce(lecture_crn, 'NULL') || ':' || status ORDER BY crn)
		FROM catalog_api.section_offering)
		IS DISTINCT FROM ARRAY['12275:LECTURE:4:NULL:UNKNOWN', '12638:LAB:0:12275:UNKNOWN', '13132:LAB:0:12275:UNKNOWN'] THEN
		RAISE EXCEPTION 'section_offering mismatch';
	END IF;
	IF (SELECT count(*) FROM catalog_api.section_meeting) <> 5 THEN
		RAISE EXCEPTION 'expected 5 meeting rows';
	END IF;
	IF EXISTS (SELECT 1 FROM catalog_api.section_offering WHERE component_type <> 'LECTURE' AND lecture_crn IS NULL) THEN
		RAISE EXCEPTION 'a non-lecture section was exposed without a lecture link';
	END IF;
	RAISE NOTICE 'PASS: CSE-007 exposes 3 sections (lecture 4 cr, labs 0 cr linked to 12275) and 5 meetings';
END $$;

-- Negative probes. Each one breaks a rule; the course must disappear from scheduling. Rolled back individually.
CREATE TEMP TABLE probe(name text, cse007_sections int);
SAVEPOINT p; DELETE FROM cse007_snapshot.section_link WHERE compatible_section_id = '00000007-0002-4000-8000-000000012638';
SELECT count(*) AS n0 FROM catalog_api.section_offering WHERE course_id = 'CSE-007' \gset
ROLLBACK TO p; INSERT INTO probe VALUES ('lab 062 loses its link', :n0);
SAVEPOINT p; UPDATE cse007_snapshot.section_component_group SET assessment_status = 'UNKNOWN', required_count = NULL;
SELECT count(*) AS n1 FROM catalog_api.section_offering WHERE course_id = 'CSE-007' \gset
ROLLBACK TO p; INSERT INTO probe VALUES ('component group unconfirmed', :n1);
SAVEPOINT p; UPDATE cse007_snapshot.section SET credits = NULL, credits_verification_status = 'NEEDS_REVIEW' WHERE crn = '13132';
SELECT count(*) AS n2 FROM catalog_api.section_offering WHERE course_id = 'CSE-007' \gset
ROLLBACK TO p; INSERT INTO probe VALUES ('lab 060 credits unresolved', :n2);
SAVEPOINT p; UPDATE cse007_snapshot.section SET link_status = 'TARGET_UNKNOWN' WHERE crn = '12638';
SELECT count(*) AS n3 FROM catalog_api.section_offering WHERE course_id = 'CSE-007' \gset
ROLLBACK TO p; INSERT INTO probe VALUES ('lab 062 link status unknown', :n3);
SAVEPOINT p; INSERT INTO cse007_snapshot.data_issue (issue_id, section_id, code, field_name, severity, details, observations)
	VALUES ('dddddddd-0000-4000-8000-000000000001', '00000007-0002-4000-8000-000000013132', 'PROBE', 'x', 'WARNING', 'probe', '[]');
SELECT count(*) AS n4 FROM catalog_api.section_offering WHERE course_id = 'CSE-007' \gset
ROLLBACK TO p; INSERT INTO probe VALUES ('open warning on lab 060', :n4);
SAVEPOINT p;
INSERT INTO cse007_snapshot.section (section_id, course_id, term_id, crn, section_code, component_type, credits,
	credits_verification_status, delivery_type, meeting_status, link_status, source_status, source_id) VALUES
	('dddddddd-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000001', '00000007-0001-4000-8000-000000202710',
	 '99903', '011', 'LECTURE', 4, 'USER_CONFIRMED', 'UNKNOWN', 'SCHEDULED', 'GROUP_CONFIRMED', 'HISTORICAL', '00000007-0010-4000-8000-000000000002');
INSERT INTO cse007_snapshot.section_link (link_id, primary_section_id, required_component_group, compatible_section_id,
	course_id, term_id, source_id, verification_status, evidence_note) VALUES
	('dddddddd-0000-4000-8000-000000000104', '00000007-0002-4000-8000-000000012275', 'LAB', 'dddddddd-0000-4000-8000-000000000103',
	 '00000007-0000-4000-8000-000000000001', '00000007-0001-4000-8000-000000202710', '00000007-0010-4000-8000-000000000008', 'USER_CONFIRMED', 'probe');
SELECT count(*) AS n7 FROM catalog_api.section_offering WHERE course_id = 'CSE-007' \gset
ROLLBACK TO p; INSERT INTO probe VALUES ('a lecture linked as a component', :n7);
SAVEPOINT p; UPDATE cse007_snapshot.section SET link_status = 'TARGET_UNKNOWN' WHERE crn = '12275';
SELECT count(*) AS n8 FROM catalog_api.section_offering WHERE course_id = 'CSE-007' \gset
ROLLBACK TO p; INSERT INTO probe VALUES ('lecture with unknown link status', :n8);
-- A course shaped like the seven staging labs: lecture plus an unlinked lab with NULL credits.
SAVEPOINT p;
INSERT INTO cse007_snapshot.course (course_id, code, subject, course_number, title, credits, prerequisite_status, source_id)
	VALUES ('dddddddd-0000-4000-8000-000000000100', 'DEMO-900', 'DEMO', '900', 'Probe Course', 3, 'UNKNOWN', '00000007-0010-4000-8000-000000000002');
INSERT INTO cse007_snapshot.section (section_id, course_id, term_id, crn, section_code, component_type, credits,
	credits_verification_status, delivery_type, meeting_status, link_status, source_status, source_id) VALUES
	('dddddddd-0000-4000-8000-000000000101', 'dddddddd-0000-4000-8000-000000000100', '00000007-0001-4000-8000-000000202710',
	 '99901', '010', 'LECTURE', 3, 'TRANSCRIBED', 'UNKNOWN', 'SCHEDULED', 'TARGET_UNKNOWN', 'HISTORICAL', '00000007-0010-4000-8000-000000000002'),
	('dddddddd-0000-4000-8000-000000000102', 'dddddddd-0000-4000-8000-000000000100', '00000007-0001-4000-8000-000000202710',
	 '99902', '060', 'LAB', NULL, 'NEEDS_REVIEW', 'UNKNOWN', 'SCHEDULED', 'TARGET_UNKNOWN', 'HISTORICAL', '00000007-0010-4000-8000-000000000002');
SELECT count(*) AS n5 FROM catalog_api.section_offering WHERE course_id = 'DEMO-900' \gset
SELECT count(*) AS n6 FROM catalog_api.section_offering WHERE course_id = 'CSE-007' \gset
ROLLBACK TO p; INSERT INTO probe VALUES ('unlinked lab course (DEMO-900) exposed sections', :n5); INSERT INTO probe VALUES ('CSE-007 unaffected by DEMO-900', :n6);
\echo '--- negative probes (expected 0, except the last row = 3)'
SELECT * FROM probe;
DO $$
BEGIN
	-- Exactly 9 recorded probes, so an empty or partial table can never pass.
	IF (SELECT count(*) FROM probe) <> 9
		OR EXISTS (SELECT 1 FROM probe WHERE name <> 'CSE-007 unaffected by DEMO-900' AND cse007_sections IS DISTINCT FROM 0)
		OR (SELECT cse007_sections FROM probe WHERE name = 'CSE-007 unaffected by DEMO-900') IS DISTINCT FROM 3 THEN
		RAISE EXCEPTION 'a fail-closed readiness rule did not hold';
	END IF;
	RAISE NOTICE 'PASS: 9 probes recorded; 8 fail-closed probes hold and CSE-007 is unaffected; unlinked labs are never exposed as standalone';
END $$;
ROLLBACK;
