-- Verification of the catalog_api views for the CSE 007 dataset. Read-only: SELECT and assertions only.
-- Hard-codes the CSE 007 expectations (like data/cse007-snapshot/verify.sql), so update it deliberately
-- when more courses are imported.
-- Raises an exception (aborting the surrounding transaction) if anything is off.
\set ON_ERROR_STOP on

\echo '--- term: Banner code only; the API term is mapped in Java'
SELECT term_code, name FROM cse007_snapshot.term ORDER BY term_code;
\echo '--- listCourses / findCourse (term_code 202710)'
SELECT term_code, course_id, title, credits, schedulable FROM catalog_api.course_offering ORDER BY term_code, course_id;
\echo '--- sectionsFor(202710, [CSE-007])'
SELECT course_id, crn, section_code, credits, component_type, lecture_crn, status
FROM catalog_api.section_offering WHERE term_code = '202710' AND course_id = ANY (ARRAY['CSE-007']) ORDER BY crn;
\echo '--- meetings (5 rows, one per day)'
SELECT crn, day_of_week, start_time, end_time, location FROM catalog_api.section_meeting ORDER BY crn, day_of_week;
\echo '--- confirmed one-lab requirement'
SELECT p.crn AS lecture_crn, g.required_component_group AS grp, g.required_count, g.assessment_status,
	array_agg(c.crn ORDER BY c.crn) AS allowed_labs, array_agg(DISTINCT l.verification_status) AS link_status
FROM cse007_snapshot.section_component_group g
JOIN cse007_snapshot.section p ON p.section_id = g.primary_section_id
JOIN cse007_snapshot.section_link l ON l.primary_section_id = g.primary_section_id AND l.required_component_group = g.required_component_group
JOIN cse007_snapshot.section c ON c.section_id = l.compatible_section_id
GROUP BY p.crn, g.required_component_group, g.required_count, g.assessment_status;
\echo '--- what each view depends on (from the PostgreSQL catalog, not a text scan)'
SELECT v.relname AS view, string_agg(DISTINCT dn.nspname || '.' || d.relname || ' (' || d.relkind::text || ')', ', ' ORDER BY dn.nspname || '.' || d.relname || ' (' || d.relkind::text || ')') AS reads
FROM pg_class v
JOIN pg_namespace vn ON vn.oid = v.relnamespace AND vn.nspname = 'catalog_api'
JOIN pg_rewrite rw ON rw.ev_class = v.oid
JOIN pg_depend dep ON dep.objid = rw.oid AND dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass
JOIN pg_class d ON d.oid = dep.refobjid AND d.oid <> v.oid
JOIN pg_namespace dn ON dn.oid = d.relnamespace
WHERE v.relkind = 'v'
GROUP BY v.relname ORDER BY v.relname;
\echo '--- JDBC column types'
SELECT table_name, column_name, data_type FROM information_schema.columns
WHERE table_schema = 'catalog_api' AND table_name IN ('course_offering', 'section_offering', 'section_meeting')
ORDER BY table_name, ordinal_position;

DO $$
DECLARE
	bad text;
BEGIN
	-- Term: Banner code 202710 exists exactly once; the API term string is never stored.
	IF (SELECT array_agg(term_code) FROM cse007_snapshot.term) IS DISTINCT FROM ARRAY['202710']::varchar[] THEN
		RAISE EXCEPTION 'term table must contain exactly the Banner code 202710';
	END IF;
	-- Course under 202710.
	IF (SELECT array_agg(term_code || '|' || course_id || '|' || title || '|' || credits || '|' || schedulable) FROM catalog_api.course_offering)
		IS DISTINCT FROM ARRAY['202710|CSE-007|Introduction to Programming|4|true'] THEN
		RAISE EXCEPTION 'course_offering mismatch';
	END IF;
	IF EXISTS (SELECT 1 FROM catalog_api.section_offering WHERE term_code <> '202710') THEN
		RAISE EXCEPTION 'section_offering must only carry Banner term 202710';
	END IF;
	-- The three sections, exactly as required.
	IF (SELECT array_agg(crn || '|' || component_type || '|' || credits || '|' || coalesce(lecture_crn, 'NULL') || '|' || status ORDER BY crn)
		FROM catalog_api.section_offering)
		IS DISTINCT FROM ARRAY['12275|LECTURE|4|NULL|UNKNOWN', '12638|LAB|0|12275|UNKNOWN', '13132|LAB|0|12275|UNKNOWN'] THEN
		RAISE EXCEPTION 'section_offering mismatch';
	END IF;
	-- The five meeting rows.
	IF (SELECT array_agg(crn || '|' || day_of_week || '|' || start_time || '|' || end_time ORDER BY crn, day_of_week) FROM catalog_api.section_meeting)
		IS DISTINCT FROM ARRAY['12275|1|09:20|10:35', '12638|3|10:45|12:00', '12638|5|10:45|12:00', '13132|3|09:20|10:35', '13132|5|09:20|10:35'] THEN
		RAISE EXCEPTION 'section_meeting mismatch';
	END IF;
	-- Confirmed "exactly one lab" rule, with both labs allowed.
	IF NOT EXISTS (SELECT 1 FROM cse007_snapshot.section_component_group g JOIN cse007_snapshot.section s ON s.section_id = g.primary_section_id
		WHERE s.crn = '12275' AND g.required_component_group = 'LAB' AND g.required_count = 1 AND g.assessment_status = 'USER_CONFIRMED')
		OR (SELECT count(*) FROM cse007_snapshot.section_link WHERE verification_status IN ('PAIR_CONFIRMED', 'USER_CONFIRMED')) <> 2 THEN
		RAISE EXCEPTION 'one-lab requirement or the two confirmed links are missing';
	END IF;
	-- Read-only by construction: every relation a view reads is a view in catalog_api or a table in cse007_snapshot.
	SELECT string_agg(dn.nspname || '.' || d.relname, ', ') INTO bad
	FROM pg_class v JOIN pg_namespace vn ON vn.oid = v.relnamespace AND vn.nspname = 'catalog_api'
	JOIN pg_rewrite rw ON rw.ev_class = v.oid
	JOIN pg_depend dep ON dep.objid = rw.oid AND dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass
	JOIN pg_class d ON d.oid = dep.refobjid AND d.oid <> v.oid
	JOIN pg_namespace dn ON dn.oid = d.relnamespace
	WHERE v.relkind = 'v' AND NOT ((dn.nspname = 'cse007_snapshot' AND d.relkind = 'r') OR (dn.nspname = 'catalog_api' AND d.relkind = 'v'));
	IF bad IS NOT NULL THEN
		RAISE EXCEPTION 'a view reads something outside cse007_snapshot tables / catalog_api views: %', bad;
	END IF;
	IF (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'catalog_api') <> 5
		OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'catalog_api' AND c.relkind <> 'v') THEN
		RAISE EXCEPTION 'catalog_api must contain exactly 5 views and nothing else';
	END IF;
	-- None of the views accepts writes.
	IF EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema = 'catalog_api'
		AND (is_updatable <> 'NO' OR is_insertable_into <> 'NO')) THEN
		RAISE EXCEPTION 'a catalog_api view is writable';
	END IF;
	-- JDBC-friendly types: credits are integers, times are text.
	-- Only the three views the backend reads; the internal helper section_link_resolved keeps NUMERIC credits on purpose.
	IF (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'catalog_api'
			AND table_name IN ('course_offering', 'section_offering') AND column_name = 'credits' AND data_type <> 'integer') > 0
		OR (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'catalog_api' AND table_name = 'section_meeting'
			AND column_name IN ('start_time', 'end_time') AND data_type <> 'text') > 0 THEN
		RAISE EXCEPTION 'unexpected column types';
	END IF;
	RAISE NOTICE 'PASS: 3 sections, 5 meetings, one-lab rule, term 202710, 5 read-only views over cse007_snapshot tables only';
END $$;
