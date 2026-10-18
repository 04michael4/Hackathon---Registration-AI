-- catalog_research V6 (schema part): record the project owner's decisions as data, and let a special topic be a decided, separate offering.
-- The DATA changes that apply the decisions live in apply_owner_decisions.sql, which is run after the data import (they need the rows to exist).
--   * owner_decision: an append-only record of each decision (who, when, what, where it is implemented), so a rule in the data can be traced
--     to a person's statement. Never a Registrar statement.
--   * offering.identity_status gains TOPIC_SEPARATE_OFFERING: the owner decided (2026-10-17) that CSE 398's two topics are separate offerings
--     (CSE-398-042, CSE-398-052), each with its own title, credits and requirements. Only TOPIC_IDENTITY_UNDECIDED still blocks.
-- Run after V1-V5.
\set ON_ERROR_STOP on

CREATE TABLE IF NOT EXISTS catalog_research.owner_decision (
	decision_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	decision_key   text NOT NULL UNIQUE CHECK (decision_key ~ '^[A-Z0-9_]{6,80}$'),
	decided_on     date NOT NULL CHECK (decided_on <= current_date),
	decided_by     text NOT NULL CHECK (length(btrim(decided_by)) >= 2 AND decided_by !~* '(script|bot|claude|auto|collector|system|import)'),
	statement      text NOT NULL CHECK (length(btrim(statement)) >= 20),
	implemented_in text NOT NULL CHECK (implemented_in IN ('DATABASE', 'SERVING', 'BACKEND', 'BACKEND_AND_ENGINE_NOT_YET', 'RECORDED_ONLY')),
	effect         text NOT NULL CHECK (length(btrim(effect)) >= 10),
	evidence_level text NOT NULL DEFAULT 'USER_CLARIFICATION' CHECK (evidence_level = 'USER_CLARIFICATION'),
	recorded_at    timestamptz NOT NULL DEFAULT now(),
	recorded_by    text NOT NULL DEFAULT session_user
);

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'owner_decision_append_only' AND tgrelid = 'catalog_research.owner_decision'::regclass) THEN
		CREATE TRIGGER owner_decision_append_only BEFORE UPDATE OR DELETE ON catalog_research.owner_decision FOR EACH ROW EXECUTE FUNCTION catalog_research.reject_change();
	END IF;
	IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'owner_decision_no_truncate' AND tgrelid = 'catalog_research.owner_decision'::regclass) THEN
		CREATE TRIGGER owner_decision_no_truncate BEFORE TRUNCATE ON catalog_research.owner_decision FOR EACH STATEMENT EXECUTE FUNCTION catalog_research.reject_change();
	END IF;
END $$;

-- identity_status: widen the allowed values (the topic-implies-not-SINGLE rule stays).
DO $$
DECLARE
	cname text;
BEGIN
	SELECT conname INTO cname FROM pg_constraint WHERE conrelid = 'catalog_research.offering'::regclass AND contype = 'c'
		AND pg_get_constraintdef(oid) ~ 'identity_status = ANY';
	IF cname IS NOT NULL AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = cname AND conrelid = 'catalog_research.offering'::regclass) !~ 'TOPIC_SEPARATE_OFFERING' THEN
		EXECUTE format('ALTER TABLE catalog_research.offering DROP CONSTRAINT %I', cname);
		ALTER TABLE catalog_research.offering ADD CONSTRAINT offering_identity_status_check
			CHECK (identity_status IN ('SINGLE', 'TOPIC_IDENTITY_UNDECIDED', 'TOPIC_SEPARATE_OFFERING'));
	END IF;
END $$;

-- Tier: same rules as V4, except that only an UNDECIDED topic identity blocks.
CREATE OR REPLACE VIEW catalog_research.offering_tier AS
SELECT o.term_code, o.offering_id,
	CASE
		WHEN EXISTS (SELECT 1 FROM catalog_research.evidence_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'BLOCKED'
		WHEN EXISTS (SELECT 1 FROM catalog_research.detail_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'BLOCKED'
		WHEN o.data_source = 'BANNER_GRID_STAGING' AND NOT EXISTS (SELECT 1 FROM catalog_research.detail_inspection i WHERE i.term_code = o.term_code AND i.offering_id = o.offering_id) THEN 'BLOCKED'
		WHEN o.identity_status = 'TOPIC_IDENTITY_UNDECIDED' THEN 'BLOCKED'
		WHEN EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id
			AND s.component_type <> 'LECTURE' AND s.lecture_crn IS NULL) THEN 'BLOCKED'
		WHEN u.crns IS NOT NULL THEN 'BLOCKED'
		WHEN o.data_source = 'SNAPSHOT' AND COALESCE(a.schedulable, false) THEN 'READY'
		ELSE 'CONDITIONAL' END AS tier,
	CASE
		WHEN EXISTS (SELECT 1 FROM catalog_research.evidence_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'EVIDENCE_MISMATCH'
		WHEN EXISTS (SELECT 1 FROM catalog_research.detail_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'DETAIL_MISMATCH'
		WHEN o.data_source = 'BANNER_GRID_STAGING' AND NOT EXISTS (SELECT 1 FROM catalog_research.detail_inspection i WHERE i.term_code = o.term_code AND i.offering_id = o.offering_id) THEN 'GRID_ONLY_DETAILS_NOT_INSPECTED'
		WHEN o.identity_status = 'TOPIC_IDENTITY_UNDECIDED' THEN 'TOPIC_IDENTITY_UNDECIDED'
		WHEN EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id
			AND s.component_type <> 'LECTURE' AND s.lecture_crn IS NULL) THEN 'COMPONENT_LINKS_NOT_COLLECTED'
		WHEN u.crns IS NOT NULL THEN 'SECTION_TYPE_UNSUPPORTED:' || u.crns
		WHEN o.data_source = 'SNAPSHOT' AND COALESCE(a.schedulable, false) THEN 'SERVED_BY_VERIFIED_CATALOG'
		WHEN p.crns IS NOT NULL THEN 'POLICY_DECISION_ALLOWS_SECTION_TYPE:' || p.crns
		ELSE 'UNRESOLVED_CONDITIONS_REMAIN' END AS tier_reason
FROM catalog_research.offering o
LEFT JOIN catalog_api.course_offering a ON a.term_code = o.term_code AND a.course_id = o.course_id
LEFT JOIN LATERAL (SELECT string_agg(s.crn, ',' ORDER BY s.crn) AS crns FROM catalog_research.section s
	WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id AND s.schedule_type NOT IN ('Lecture', 'Class', 'Lab', 'Recitation')
		AND NOT EXISTS (SELECT 1 FROM catalog_research.active_tier_policy_decision d WHERE d.term_code = s.term_code AND d.crn = s.crn AND d.is_active)) u ON true
LEFT JOIN LATERAL (SELECT string_agg(s.crn, ',' ORDER BY s.crn) AS crns FROM catalog_research.section s
	WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id AND s.schedule_type NOT IN ('Lecture', 'Class', 'Lab', 'Recitation')
		AND EXISTS (SELECT 1 FROM catalog_research.active_tier_policy_decision d WHERE d.term_code = s.term_code AND d.crn = s.crn AND d.is_active)) p ON true;
COMMENT ON VIEW catalog_research.offering_tier IS 'Derived research tier (READY / CONDITIONAL / BLOCKED) with the reason. A classification, not a promotion.';
