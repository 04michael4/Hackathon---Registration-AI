-- OPTIONAL second step: the five coverage views (V3). They read catalog_research (offering, section, offering_tier, grid_search_result), so once installed
-- an ALTER/DROP of those objects by the owner of catalog_research fails (or, with CASCADE, silently drops these views, never data).
-- Install them only after the owner of catalog_research agrees. Remove them without touching any data:
--   DROP VIEW catalog_csb.ns_sequence_dependency, catalog_csb.ns_sequence_status, catalog_csb.ns_sequence_course_status, catalog_csb.course_coverage, catalog_csb.course_availability;
--   DROP FUNCTION catalog_csb.expression_satisfied(jsonb, text[]), catalog_csb.expression_courses(jsonb);   (their exact signatures: \df catalog_csb.*)
-- One transaction; requires catalog_csb to be installed (install.sql) and catalog_research to exist.
\set ON_ERROR_STOP on
BEGIN;
DO $$ BEGIN
	IF to_regclass('catalog_csb.section') IS NULL OR to_regclass('catalog_research.offering_tier') IS NULL THEN
		RAISE EXCEPTION 'catalog_csb must be installed (install.sql) and catalog_research must exist';
	END IF;
END $$;
\ir V3__catalog_csb_coverage_views.sql
\ir verify_structure.sql
COMMIT;
