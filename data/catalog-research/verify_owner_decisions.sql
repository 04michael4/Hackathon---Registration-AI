-- Read-only: asserts that the project owner's decisions are applied and recorded (data/catalog-research/apply_owner_decisions.sql). Raises on any problem.
--   psql -X -v ON_ERROR_STOP=1 -f data/catalog-research/verify_owner_decisions.sql
\set ON_ERROR_STOP on
SET default_transaction_read_only = on;
DO $$
BEGIN
	IF (SELECT count(*) FROM catalog_research.owner_decision) < 11 THEN RAISE EXCEPTION 'the 11 owner decisions are not all recorded'; END IF;
	IF NOT EXISTS (SELECT 1 FROM catalog_research.eligibility_rule WHERE offering_id = 'CSE-303' AND rule_kind = 'PREREQUISITE' AND parse_status = 'STRUCTURED'
			AND expression = '{"op": "AND", "args": [{"op": "OR", "args": [{"course": "ECE-201"}, {"course": "CSE-201"}, {"course": "CSE-202"}]}, {"course": "CSE-109"}]}'::jsonb) THEN
		RAISE EXCEPTION 'CSE 303 prerequisite is not structured as (ECE 201 or CSE 201 or CSE 202) and CSE 109';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM catalog_research.requirement_status WHERE offering_id = 'CSE-303' AND requirement_type = 'PREREQUISITE' AND status = 'RULE_DEFINED') THEN
		RAISE EXCEPTION 'CSE 303 prerequisite status is not RULE_DEFINED';
	END IF;
	IF (SELECT count(*) FROM catalog_research.offering WHERE offering_id IN ('CSE-398-042', 'CSE-398-052') AND identity_status = 'TOPIC_SEPARATE_OFFERING') <> 2
		OR (SELECT count(*) FROM catalog_research.offering_tier WHERE offering_id IN ('CSE-398-042', 'CSE-398-052') AND tier = 'CONDITIONAL') <> 2 THEN
		RAISE EXCEPTION 'the two CSE 398 topics are not separate, conditional offerings';
	END IF;
	IF (SELECT count(*) FROM catalog_research.credit_exclusion WHERE exclusion_kind = 'NO_DOUBLE_CREDIT' AND evidence_level = 'USER_CLARIFICATION'
			AND ((offering_id = 'ECO-325' AND excluded_course_id = 'MKT-325') OR (offering_id = 'MKT-325' AND excluded_course_id = 'ECO-325'))) <> 2 THEN
		RAISE EXCEPTION 'ECO 325 / MKT 325 are not recorded as one class both ways';
	END IF;
	IF EXISTS (SELECT 1 FROM catalog_research.unresolved_item WHERE subject = 'INSTRUCTORS' AND detail LIKE 'NOT_IMPORTED:%') THEN
		RAISE EXCEPTION 'the instructor note is still the old one';
	END IF;
	IF EXISTS (SELECT 1 FROM catalog_research.credit_exclusion WHERE offering_id IN ('CSE-127', 'CSE-327') AND exclusion_kind <> 'SOURCES_CONFLICT') THEN
		RAISE EXCEPTION 'CSE 127 / 327 must stay a SOURCES_CONFLICT (current catalog followed), not an exclusion';
	END IF;
END $$;
SELECT 'owner decisions applied and recorded' AS result;
