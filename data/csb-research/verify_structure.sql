-- Read-only structural assertions for catalog_csb. Raises an exception on any problem.
\set ON_ERROR_STOP on
DO $$
DECLARE
	has_research boolean := to_regclass('catalog_csb.course_coverage') IS NOT NULL;   -- the optional coverage views (V3) are installed
	expected_tables text[] := ARRAY['banner_search:r', 'catalog_course:r', 'detail_discrepancy:r', 'elective_list:r', 'elective_list_member:r', 'grid_observation:r', 'import_run:r',
		'ns_footnote:r', 'ns_optional_lab:r', 'ns_sequence:r', 'ns_sequence_course:r', 'offering:r', 'prerequisite_comparison:r', 'program:r', 'requirement_group:r', 'requirement_option:r',
		'section:r', 'section_arranged_meeting:r', 'section_cross_list:r', 'section_detail:r', 'section_instructor:r', 'section_link_option:r', 'section_meeting:r', 'section_rule:r',
		'source:r', 'source_conflict:r', 'unresolved_item:r'];
	expected_views text[] := ARRAY['component_requirement:v', 'course_term_status:v', 'credit_problem:v', 'grid_mismatch:v', 'link_pair:v', 'link_status_derived:v', 'link_status_mismatch:v', 'offering_requirement_variant:v',
		'package_credit:v', 'program_problem:v'];
	research_views text[] := ARRAY['course_availability:v', 'course_coverage:v', 'ns_sequence_course_status:v', 'ns_sequence_dependency:v', 'ns_sequence_status:v'];
	actual text[];
BEGIN
	SELECT array_agg(c.relname || ':' || c.relkind::text ORDER BY (c.relname || ':' || c.relkind::text) COLLATE "C") INTO actual FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
		WHERE n.nspname = 'catalog_csb' AND c.relkind IN ('r', 'v');
	IF actual IS DISTINCT FROM (SELECT array_agg(x ORDER BY x COLLATE "C") FROM unnest(expected_tables || expected_views || CASE WHEN has_research THEN research_views ELSE '{}' END) x) THEN
		RAISE EXCEPTION 'catalog_csb has an unexpected set of tables and views: %', actual;
	END IF;
	-- the import history is append-only
	IF (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_csb' AND NOT t.tgisinternal AND c.relname = 'import_run') <> 2 THEN
		RAISE EXCEPTION 'import_run needs its append-only and no-truncate triggers';
	END IF;
	-- no view is writable
	IF EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema = 'catalog_csb' AND (is_updatable <> 'NO' OR is_insertable_into <> 'NO')) THEN
		RAISE EXCEPTION 'a catalog_csb view is writable';
	END IF;
	-- no foreign key leaves the schema, and no object outside the schema depends on it (additive and removable)
	IF EXISTS (SELECT 1 FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_class f ON f.oid = k.confrelid JOIN pg_namespace fn ON fn.oid = f.relnamespace
			WHERE k.contype = 'f' AND ((n.nspname = 'catalog_csb') <> (fn.nspname = 'catalog_csb'))) THEN
		RAISE EXCEPTION 'a foreign key crosses the catalog_csb schema boundary';
	END IF;
	IF EXISTS (SELECT 1 FROM pg_depend d JOIN pg_rewrite r ON r.oid = d.objid JOIN pg_class v ON v.oid = r.ev_class JOIN pg_namespace vn ON vn.oid = v.relnamespace
			JOIN pg_class t ON t.oid = d.refobjid JOIN pg_namespace tn ON tn.oid = t.relnamespace
			WHERE vn.nspname <> 'catalog_csb' AND tn.nspname = 'catalog_csb') THEN
		RAISE EXCEPTION 'a view outside catalog_csb reads catalog_csb';
	END IF;
	-- the only catalog_csb code that reads another schema is the optional coverage views and the CRN-collision guard in import_csb
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'catalog_csb' AND p.proname NOT IN ('import_csb', 'reject_change', 'expression_satisfied', 'expression_courses')) THEN
		RAISE EXCEPTION 'unexpected function in catalog_csb';
	END IF;
	-- nothing here writes outside catalog_csb
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'catalog_csb'
			AND p.prosrc ~* '(insert\s+into|update|delete\s+from|truncate(\s+table)?)\s+(catalog_(research|serving|promoted|live|api)|banner_evidence|professor_ratings|cse007_snapshot)\.') THEN
		RAISE EXCEPTION 'a catalog_csb function writes outside catalog_csb';
	END IF;
	-- the three flags that must never become true: this schema stores researched data, it approves, promotes and serves nothing
	IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'catalog_csb' AND column_name ~ '(approved|promoted|schedulable|servable|registrar_certified)') THEN
		RAISE EXCEPTION 'catalog_csb must not carry approval, promotion or serving columns';
	END IF;
	-- the verbatim-grid cross-checks hold for whatever is stored
	IF EXISTS (SELECT 1 FROM catalog_csb.grid_mismatch) THEN RAISE EXCEPTION 'grid_mismatch is not empty'; END IF;
	IF EXISTS (SELECT 1 FROM catalog_csb.link_status_mismatch) THEN RAISE EXCEPTION 'link_status_mismatch is not empty'; END IF;
	IF EXISTS (SELECT 1 FROM catalog_csb.program_problem WHERE problem <> 'NS_SEQUENCE_COURSE_NOT_NS_DESIGNATED') THEN RAISE EXCEPTION 'program_problem is not empty'; END IF;
END $$;
