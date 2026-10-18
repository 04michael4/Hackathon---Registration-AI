-- Read-only structural assertions for catalog_promoted and catalog_live. Raises an exception on any problem.
\set ON_ERROR_STOP on
DO $$
DECLARE
	bad text;
BEGIN
	IF (SELECT array_agg(c.relname || ':' || c.relkind::text ORDER BY c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_promoted' AND c.relkind IN ('r', 'v'))
		IS DISTINCT FROM ARRAY['active_approval:v', 'approval:r', 'capability:r', 'current_promotion:v', 'evidence_grid:v', 'evidence_link:v',
			'evidence_meeting:v', 'evidence_requirement:v', 'evidence_section:v', 'promoted_course:r', 'promoted_meeting:r',
			'promoted_requirement:r', 'promoted_section:r', 'promotion:r', 'promotion_approval:r', 'promotion_blocker:v',
			'promotion_readiness:v', 'promotion_retraction:r', 'servable_course:v', 'servable_meeting:v', 'servable_requirement:v',
			'servable_section:v'] THEN
		RAISE EXCEPTION 'catalog_promoted must contain exactly 9 tables and 13 views';
	END IF;
	IF (SELECT array_agg(c.relname || ':' || c.relkind::text ORDER BY c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_live' AND c.relkind IN ('r', 'v'))
		IS DISTINCT FROM ARRAY['course_offering:v', 'offering_source:v', 'schedulable_course:v', 'section_meeting:v', 'section_offering:v', 'section_requirement:v'] THEN
		RAISE EXCEPTION 'catalog_live must contain exactly 6 views and no table';
	END IF;
	-- Every promoted/approval table is append-only (both triggers); capability is configuration and is not.
	IF (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_promoted' AND NOT t.tgisinternal AND t.tgname LIKE '%\_append\_only') <> 8
		OR (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_promoted' AND NOT t.tgisinternal AND t.tgname LIKE '%\_no\_truncate') <> 8 THEN
		RAISE EXCEPTION 'every promotion and approval table needs its append-only and no-truncate triggers';
	END IF;
	-- All capabilities are off until the application can enforce them.
	IF (SELECT array_agg(name || ':' || enabled ORDER BY name) FROM catalog_promoted.capability)
		IS DISTINCT FROM ARRAY['COREQUISITES_ENFORCED:false', 'CREDIT_EXCLUSION_ENFORCED:false', 'PREREQUISITES_ENFORCED:false', 'RESTRICTIONS_ENFORCED:false'] THEN
		RAISE EXCEPTION 'capabilities must all be false until the application enforces them';
	END IF;
	-- No view is writable.
	IF EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema IN ('catalog_promoted', 'catalog_live')
			AND (is_updatable <> 'NO' OR is_insertable_into <> 'NO')) THEN
		RAISE EXCEPTION 'a catalog_promoted or catalog_live view is writable';
	END IF;
	-- Approvals are written by a person only: no function except record_approval inserts into approval, and promote_course never does.
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'catalog_promoted'
			AND p.proname <> 'record_approval' AND p.prosrc ~* 'insert\s+into\s+catalog_promoted\.approval') THEN
		RAISE EXCEPTION 'only record_approval may insert an approval';
	END IF;
	-- This layer only READS the evidence and the verified catalog: nothing here writes them.
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'catalog_promoted'
			AND p.prosrc ~* '(insert\s+into|update|delete\s+from|truncate|drop|alter)\s+(table\s+)?(banner_evidence|catalog_api|cse007_snapshot)\.') THEN
		RAISE EXCEPTION 'a catalog_promoted function writes to evidence or the verified catalog';
	END IF;
	-- Nothing in the verified catalog or the evidence depends on the promotion layer (they stay independent of it).
	SELECT string_agg(vn.nspname || '.' || v.relname, ', ') INTO bad
	FROM pg_class v JOIN pg_namespace vn ON vn.oid = v.relnamespace
	JOIN pg_rewrite rw ON rw.ev_class = v.oid
	JOIN pg_depend dep ON dep.objid = rw.oid AND dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass
	JOIN pg_class d ON d.oid = dep.refobjid AND d.oid <> v.oid
	JOIN pg_namespace dn ON dn.oid = d.relnamespace
	WHERE v.relkind = 'v' AND vn.nspname IN ('catalog_api', 'cse007_snapshot', 'banner_evidence') AND dn.nspname IN ('catalog_promoted', 'catalog_live');
	IF bad IS NOT NULL THEN
		RAISE EXCEPTION 'a verified-catalog or evidence view depends on the promotion layer: %', bad;
	END IF;
	-- Promoted rows carry provenance: every source and approval column is NOT NULL.
	IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'catalog_promoted'
			AND ((table_name = 'promoted_section' AND column_name IN ('grid_source_id', 'details_source_id', 'linked_source_id', 'credits_approval_id'))
				OR (table_name IN ('promoted_meeting', 'promoted_requirement') AND column_name = 'source_id'))
			AND is_nullable = 'YES') THEN
		RAISE EXCEPTION 'every promoted row must carry non-null provenance';
	END IF;
	RAISE NOTICE 'PASS: catalog_promoted (9 tables, 13 views), catalog_live (6 views): append-only, read-only over evidence and the verified catalog, all capabilities off';
END $$;
