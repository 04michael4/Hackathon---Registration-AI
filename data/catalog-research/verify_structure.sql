-- Read-only structural assertions for catalog_research. Raises an exception on any problem.
\set ON_ERROR_STOP on
DO $$
DECLARE
	bad text;
BEGIN
	IF (SELECT array_agg(c.relname || ':' || c.relkind::text ORDER BY c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_research' AND c.relkind IN ('r', 'v'))
		IS DISTINCT FROM ARRAY['active_tier_policy_decision:v', 'candidate_course_offering:v', 'candidate_section_meeting:v', 'candidate_section_offering:v',
			'catalog_course:r', 'catalog_course_equivalence:r', 'catalog_course_exclusion:r', 'course_alias:r', 'course_term_status:v', 'credit_exclusion:r',
			'detail_inspection:r', 'detail_mismatch:v',			'eligibility_rule:r', 'evidence_mismatch:v', 'exam_meeting:v', 'grid_extra_meeting_observation:r', 'grid_mismatch:v', 'grid_observation:r',
			'grid_search_result:r', 'grid_source:r', 'import_run:r', 'offering:r', 'offering_status:v', 'offering_tier:v', 'owner_decision:r', 'prerequisite_banner_check:v',
			'program:r', 'program_course_link:v', 'program_requirement:r', 'program_structure_problem:v', 'requirement_course_option:r', 'requirement_group:r',
			'requirement_status:r', 'section:r', 'section_arranged_meeting:r', 'section_detail_observation:r', 'section_meeting:r', 'tier_policy_decision:r', 'unresolved_item:r',
			'unresolved_requirement:v'] THEN
		RAISE EXCEPTION 'catalog_research must contain exactly 25 tables and 15 views';
	END IF;
	-- Policy decisions and import runs are append-only (update/delete and truncate are refused).
	IF (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_research' AND NOT t.tgisinternal AND t.tgname LIKE '%\_append\_only') <> 3
		OR (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_research' AND NOT t.tgisinternal AND t.tgname LIKE '%\_no\_truncate') <> 3 THEN
		RAISE EXCEPTION 'tier_policy_decision and import_run need their append-only and no-truncate triggers';
	END IF;
	-- No view is writable.
	IF EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema = 'catalog_research' AND (is_updatable <> 'NO' OR is_insertable_into <> 'NO')) THEN
		RAISE EXCEPTION 'a catalog_research view is writable';
	END IF;
	-- A decision can never claim Registrar certification or a registration guarantee (CHECK constraints), and its evidence level is fixed.
	IF (SELECT count(*) FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'catalog_research' AND c.relname = 'tier_policy_decision' AND k.contype = 'c'
				AND (pg_get_constraintdef(k.oid) ~ 'NOT registrar_certified' OR pg_get_constraintdef(k.oid) ~ 'NOT guarantees_registration'
					OR pg_get_constraintdef(k.oid) ~ '''TEAM_POLICY''')) <> 3 THEN
		RAISE EXCEPTION 'tier_policy_decision lost a CHECK that forbids Registrar certification, a guarantee, or a non-TEAM_POLICY level';
	END IF;
	-- Only record_tier_policy_decision inserts a decision; nothing here approves or promotes.
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'catalog_research'
			AND p.proname <> 'record_tier_policy_decision' AND p.prosrc ~* 'insert\s+into\s+catalog_research\.tier_policy_decision') THEN
		RAISE EXCEPTION 'only record_tier_policy_decision may insert a policy decision';
	END IF;
	-- This layer only READS the evidence, the verified catalog, the promotion layer and the live views.
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'catalog_research'
			AND p.prosrc ~* '(insert\s+into|update|delete\s+from|truncate|drop|alter)\s+(table\s+)?(banner_evidence|catalog_api|cse007_snapshot|catalog_promoted|catalog_live)\.') THEN
		RAISE EXCEPTION 'a catalog_research function writes outside catalog_research';
	END IF;
	-- Nothing that serves reads this schema: the verified catalog, evidence, promotion and live layers do not depend on it.
	SELECT string_agg(vn.nspname || '.' || v.relname, ', ') INTO bad
	FROM pg_class v JOIN pg_namespace vn ON vn.oid = v.relnamespace
	JOIN pg_rewrite rw ON rw.ev_class = v.oid
	JOIN pg_depend dep ON dep.objid = rw.oid AND dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass
	JOIN pg_class d ON d.oid = dep.refobjid AND d.oid <> v.oid
	JOIN pg_namespace dn ON dn.oid = d.relnamespace
	WHERE v.relkind = 'v' AND vn.nspname IN ('catalog_api', 'cse007_snapshot', 'banner_evidence', 'catalog_promoted', 'catalog_live') AND dn.nspname = 'catalog_research';
	IF bad IS NOT NULL THEN
		RAISE EXCEPTION 'a serving or evidence view depends on catalog_research: %', bad;
	END IF;
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname IN ('catalog_api', 'catalog_promoted', 'catalog_live', 'banner_evidence')
			AND p.prosrc ~* 'catalog_research') THEN
		RAISE EXCEPTION 'a serving or evidence function mentions catalog_research';
	END IF;
	-- The writing functions are closed to PUBLIC.
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'catalog_research'
			AND p.proname IN ('import_term', 'record_tier_policy_decision', 'import_noncse_details') AND has_function_privilege('public', p.oid, 'EXECUTE')) THEN
		RAISE EXCEPTION 'import_term, record_tier_policy_decision and import_noncse_details must not be executable by PUBLIC';
	END IF;
	-- The candidate views have exactly the columns and types the adapter reads from catalog_live.
	IF (SELECT array_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod) ORDER BY a.attnum) FROM pg_attribute a
			WHERE a.attrelid = 'catalog_research.candidate_course_offering'::regclass AND a.attnum > 0 AND NOT a.attisdropped)
		IS DISTINCT FROM (SELECT array_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod) ORDER BY a.attnum) FROM pg_attribute a
			WHERE a.attrelid = 'catalog_live.course_offering'::regclass AND a.attnum > 0 AND NOT a.attisdropped)
		OR (SELECT array_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod) ORDER BY a.attnum) FROM pg_attribute a
			WHERE a.attrelid = 'catalog_research.candidate_section_offering'::regclass AND a.attnum > 0 AND NOT a.attisdropped)
		IS DISTINCT FROM (SELECT array_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod) ORDER BY a.attnum) FROM pg_attribute a
			WHERE a.attrelid = 'catalog_live.section_offering'::regclass AND a.attnum > 0 AND NOT a.attisdropped)
		OR (SELECT array_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod) ORDER BY a.attnum) FROM pg_attribute a
			WHERE a.attrelid = 'catalog_research.candidate_section_meeting'::regclass AND a.attnum > 0 AND NOT a.attisdropped)
		IS DISTINCT FROM (SELECT array_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod) ORDER BY a.attnum) FROM pg_attribute a
			WHERE a.attrelid = 'catalog_live.section_meeting'::regclass AND a.attnum > 0 AND NOT a.attisdropped) THEN
		RAISE EXCEPTION 'a candidate view no longer has the column names and types of its catalog_live counterpart';
	END IF;
	RAISE NOTICE 'PASS: catalog_research (25 tables, 15 views): append-only decisions, read-only over evidence and serving layers, not read by any serving view, candidate views match catalog_live';
END $$;
