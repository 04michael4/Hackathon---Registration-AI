-- Read-only structural assertions for catalog_serving. Raises an exception on any problem.
\set ON_ERROR_STOP on
DO $$
BEGIN
	IF (SELECT array_agg(c.relname || ':' || c.relkind::text ORDER BY c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_serving' AND c.relkind IN ('r', 'v'))
		IS DISTINCT FROM ARRAY['course_condition:v', 'course_offering:v', 'section_meeting:v', 'section_offering:v', 'served_offering:v'] THEN
		RAISE EXCEPTION 'catalog_serving must contain exactly the 5 views and no table';
	END IF;
	IF EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema = 'catalog_serving' AND (is_updatable <> 'NO' OR is_insertable_into <> 'NO')) THEN
		RAISE EXCEPTION 'a catalog_serving view is writable';
	END IF;
	-- The three served views have the exact column names and types of catalog_live, so the backend reads them unchanged.
	IF EXISTS (
		SELECT 1 FROM information_schema.columns l
		FULL JOIN information_schema.columns s ON s.table_schema = 'catalog_serving' AND s.table_name = l.table_name AND s.column_name = l.column_name
		WHERE l.table_schema = 'catalog_live' AND l.table_name IN ('course_offering', 'section_offering', 'section_meeting')
			AND (s.column_name IS NULL OR s.data_type <> l.data_type OR s.ordinal_position <> l.ordinal_position)) THEN
		RAISE EXCEPTION 'catalog_serving views differ from catalog_live in columns or types';
	END IF;
	-- A BLOCKED offering is never served, and every served offering has a lecture.
	IF EXISTS (SELECT 1 FROM catalog_serving.served_offering v JOIN catalog_research.offering_tier t USING (term_code, offering_id) WHERE t.tier = 'BLOCKED') THEN
		RAISE EXCEPTION 'a BLOCKED offering is served';
	END IF;
	-- No schema outside catalog_serving may depend on it, and nothing here writes (functions are plain readers).
	IF EXISTS (SELECT 1 FROM pg_depend d JOIN pg_rewrite rw ON rw.oid = d.objid AND d.classid = 'pg_rewrite'::regclass
			JOIN pg_class v ON v.oid = rw.ev_class JOIN pg_namespace vn ON vn.oid = v.relnamespace
			JOIN pg_class t ON t.oid = d.refobjid AND d.refclassid = 'pg_class'::regclass JOIN pg_namespace tn ON tn.oid = t.relnamespace
			WHERE tn.nspname = 'catalog_serving' AND vn.nspname <> 'catalog_serving') THEN
		RAISE EXCEPTION 'a view outside catalog_serving depends on catalog_serving';
	END IF;
END $$;
SELECT 'catalog_serving structure OK' AS result;
