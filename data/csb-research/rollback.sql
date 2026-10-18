-- Removes catalog_csb completely, but only after proving that nothing OUTSIDE it depends on it. One transaction; run as the schema owner.
--   psql -X -v ON_ERROR_STOP=1 -f data/csb-research/rollback.sql
-- Why a guard: DROP SCHEMA ... CASCADE silently drops anything that depends on the schema, wherever it lives (a view, a foreign key, a column default
-- another team member added later). This script lists such dependents and aborts instead. With the default install there are none, and the schema depends on
-- nothing outside itself (the optional coverage views are the exception: they READ catalog_research, and dropping the schema never touches what they read).
-- Tested on a copy of the team database: after this script the whole database (DDL, owners, GRANTs, default ACLs, every row of every table) equals the state
-- before the install (run_rollback_validation.py). Take a backup first: MERGE_INTO_UNIFIED_DATABASE.md, step 2.
\set ON_ERROR_STOP on
BEGIN;
DO $$
DECLARE
	dependents text;
BEGIN
	IF to_regnamespace('catalog_csb') IS NULL THEN
		RAISE NOTICE 'catalog_csb does not exist: nothing to remove';
		RETURN;
	END IF;
	CREATE TEMP TABLE csb_inside ON COMMIT DROP AS
		SELECT 'pg_class'::regclass::oid AS classid, c.oid AS objid FROM pg_class c WHERE c.relnamespace = 'catalog_csb'::regnamespace
		UNION ALL SELECT 'pg_class'::regclass::oid, t.reltoastrelid FROM pg_class t WHERE t.relnamespace = 'catalog_csb'::regnamespace AND t.reltoastrelid <> 0         -- TOAST tables live in pg_toast but belong to their table
		UNION ALL SELECT 'pg_class'::regclass::oid, i.indexrelid FROM pg_index i WHERE i.indrelid IN (SELECT t.reltoastrelid FROM pg_class t WHERE t.relnamespace = 'catalog_csb'::regnamespace AND t.reltoastrelid <> 0)
		UNION ALL SELECT 'pg_type'::regclass::oid, ty.oid FROM pg_type ty WHERE ty.typrelid IN (SELECT t.reltoastrelid FROM pg_class t WHERE t.relnamespace = 'catalog_csb'::regnamespace AND t.reltoastrelid <> 0)
		UNION ALL SELECT 'pg_proc'::regclass::oid, p.oid FROM pg_proc p WHERE p.pronamespace = 'catalog_csb'::regnamespace
		UNION ALL SELECT 'pg_type'::regclass::oid, t.oid FROM pg_type t WHERE t.typnamespace = 'catalog_csb'::regnamespace
		UNION ALL SELECT 'pg_constraint'::regclass::oid, k.oid FROM pg_constraint k WHERE k.connamespace = 'catalog_csb'::regnamespace
		UNION ALL SELECT 'pg_trigger'::regclass::oid, g.oid FROM pg_trigger g JOIN pg_class c ON c.oid = g.tgrelid WHERE c.relnamespace = 'catalog_csb'::regnamespace
		UNION ALL SELECT 'pg_rewrite'::regclass::oid, r.oid FROM pg_rewrite r JOIN pg_class c ON c.oid = r.ev_class WHERE c.relnamespace = 'catalog_csb'::regnamespace
		UNION ALL SELECT 'pg_attrdef'::regclass::oid, a.oid FROM pg_attrdef a JOIN pg_class c ON c.oid = a.adrelid WHERE c.relnamespace = 'catalog_csb'::regnamespace
		UNION ALL SELECT 'pg_policy'::regclass::oid, y.oid FROM pg_policy y JOIN pg_class c ON c.oid = y.polrelid WHERE c.relnamespace = 'catalog_csb'::regnamespace
		UNION ALL SELECT 'pg_default_acl'::regclass::oid, a.oid FROM pg_default_acl a WHERE a.defaclnamespace = 'catalog_csb'::regnamespace
		UNION ALL SELECT 'pg_namespace'::regclass::oid, 'catalog_csb'::regnamespace::oid;
	SELECT string_agg(DISTINCT pg_describe_object(d.classid, d.objid, d.objsubid) || ' depends on ' || pg_describe_object(d.refclassid, d.refobjid, d.refobjsubid), E'\n')
		INTO dependents
		FROM pg_depend d
		WHERE EXISTS (SELECT 1 FROM csb_inside i WHERE i.classid = d.refclassid AND i.objid = d.refobjid)
		AND NOT EXISTS (SELECT 1 FROM csb_inside i WHERE i.classid = d.classid AND i.objid = d.objid)
		AND d.deptype IN ('n', 'a', 'i');
	IF dependents IS NOT NULL THEN
		RAISE EXCEPTION E'refusing to drop catalog_csb: objects outside it depend on it:\n%', dependents;
	END IF;
END $$;
DROP SCHEMA IF EXISTS catalog_csb CASCADE;
COMMIT;
