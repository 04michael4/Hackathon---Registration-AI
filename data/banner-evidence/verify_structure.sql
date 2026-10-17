-- Read-only structural assertions for banner_evidence. Raises an exception (aborting the surrounding
-- transaction) if anything is off. Run by install.sql, and runnable on its own.
\set ON_ERROR_STOP on
DO $$
DECLARE
	bad text;
BEGIN
	-- Exactly these relations, nothing else.
	IF (SELECT array_agg(c.relname || ':' || c.relkind::text ORDER BY c.relname) FROM pg_class c
			JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'banner_evidence' AND c.relkind IN ('r', 'v'))
		IS DISTINCT FROM ARRAY['claim:r', 'claim_evidence:r', 'claim_review:r', 'claim_status:v', 'claim_type_policy:r',
			'course_evidence_summary:v', 'credit_display_comparison:v', 'evidence_source:r', 'link_observation:r',
			'link_reconciliation:v', 'meeting_observation:r', 'meeting_reconciliation:v', 'note_observation:r',
			'review_queue:v', 'section_field_observation:r', 'section_observation:r'] THEN
		RAISE EXCEPTION 'banner_evidence must contain exactly 10 tables and 6 views';
	END IF;
	IF (SELECT array_agg(p.proname::text ORDER BY p.proname) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
			WHERE n.nspname = 'banner_evidence')
		IS DISTINCT FROM ARRAY['check_claim_evidence', 'is_safe_text', 'reject_change'] THEN
		RAISE EXCEPTION 'banner_evidence must contain exactly 3 functions';
	END IF;
	-- Append-only: both triggers on each of the 9 evidence tables, and none on the policy table.
	IF (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'banner_evidence' AND NOT t.tgisinternal AND t.tgname LIKE '%\_append\_only') <> 9
		OR (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'banner_evidence' AND NOT t.tgisinternal AND t.tgname LIKE '%\_no\_truncate') <> 9 THEN
		RAISE EXCEPTION 'every evidence table needs its append-only and no-truncate triggers';
	END IF;
	-- Claim-type policy: only link claims can reach VERIFIED_BANNER from Banner alone.
	IF (SELECT array_agg(claim_type || ':' || banner_can_verify ORDER BY claim_type) FROM banner_evidence.claim_type_policy)
		IS DISTINCT FROM ARRAY['LAB_CREDITS:false', 'LECTURE_CREDITS:false', 'LECTURE_LAB_LINK:true', 'REQUIRED_LAB_COUNT:false', 'STANDALONE_STATUS:false'] THEN
		RAISE EXCEPTION 'claim_type_policy has unexpected content';
	END IF;
	-- Views accept no writes.
	IF EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema = 'banner_evidence'
			AND (is_updatable <> 'NO' OR is_insertable_into <> 'NO')) THEN
		RAISE EXCEPTION 'a banner_evidence view is writable';
	END IF;
	-- No column says a course is schedulable or ready.
	IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'banner_evidence'
			AND column_name ~* '(^schedulable$|ready|^is_schedul)') THEN
		RAISE EXCEPTION 'banner_evidence must not carry a schedule-ready column';
	END IF;
	-- No stored status columns: status is derived by claim_status only.
	IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'banner_evidence'
			AND table_name IN ('claim', 'claim_evidence') AND column_name ~* 'status') THEN
		RAISE EXCEPTION 'claim status must be derived, not stored';
	END IF;
	-- Isolation: foreign keys stay inside the schema ...
	SELECT string_agg(con.conrelid::regclass::text || ' -> ' || con.confrelid::regclass::text, ', ') INTO bad
	FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
	JOIN pg_class f ON f.oid = con.confrelid JOIN pg_namespace fn ON fn.oid = f.relnamespace
	WHERE con.contype = 'f' AND (n.nspname = 'banner_evidence') <> (fn.nspname = 'banner_evidence');
	IF bad IS NOT NULL THEN
		RAISE EXCEPTION 'a foreign key crosses the banner_evidence boundary: %', bad;
	END IF;
	-- ... views read only banner_evidence relations, and no other schema's view reads banner_evidence.
	SELECT string_agg(vn.nspname || '.' || v.relname || ' reads ' || dn.nspname || '.' || d.relname, ', ') INTO bad
	FROM pg_class v JOIN pg_namespace vn ON vn.oid = v.relnamespace
	JOIN pg_rewrite rw ON rw.ev_class = v.oid
	JOIN pg_depend dep ON dep.objid = rw.oid AND dep.classid = 'pg_rewrite'::regclass AND dep.refclassid = 'pg_class'::regclass
	JOIN pg_class d ON d.oid = dep.refobjid AND d.oid <> v.oid
	JOIN pg_namespace dn ON dn.oid = d.relnamespace
	WHERE v.relkind = 'v' AND dn.nspname NOT IN ('pg_catalog', 'information_schema')
		AND (vn.nspname = 'banner_evidence') <> (dn.nspname = 'banner_evidence');
	IF bad IS NOT NULL THEN
		RAISE EXCEPTION 'a view crosses the banner_evidence boundary: %', bad;
	END IF;
	-- Function bodies do not mention the catalog schemas, and theirs do not mention this one.
	IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
			WHERE (n.nspname = 'banner_evidence' AND p.prosrc ~* '(cse007_snapshot|catalog_api)')
			OR (n.nspname IN ('catalog_api', 'cse007_snapshot') AND p.prosrc ~* 'banner_evidence')) THEN
		RAISE EXCEPTION 'a function crosses the banner_evidence boundary';
	END IF;
	RAISE NOTICE 'PASS: banner_evidence structure: 10 tables, 6 read-only views, 3 functions, append-only triggers, isolated from catalog schemas';
END $$;
