-- catalog_research v2: the cross-check against evidence, the derived tier, the status views, the catalog-shaped candidate
-- views, and the two functions that write (import_term, record_tier_policy_decision). Needs V1.
-- Read-only views except for those two functions. Nothing here is read by catalog_api, catalog_promoted or catalog_live.

DO $$
BEGIN
	IF to_regclass('catalog_research.offering') IS NULL THEN
		RAISE EXCEPTION 'catalog_research V1 must be installed first';
	END IF;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- evidence_mismatch: every place where a stored row disagrees with the collected evidence. Empty = consistent.
-- Banner offerings are compared with catalog_promoted.evidence_*; snapshot offerings with the verified catalog_api views.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_research.evidence_mismatch AS
-- Banner: sections
SELECT s.term_code, s.crn, 'SECTION_NOT_IN_EVIDENCE'::text AS problem, s.offering_id AS stored, NULL::text AS evidence
FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id)
WHERE o.data_source = 'BANNER_EVIDENCE'
	AND NOT EXISTS (SELECT 1 FROM catalog_promoted.evidence_section e WHERE e.term_code = s.term_code AND e.crn = s.crn AND e.course_id = o.course_id)
UNION ALL
SELECT e.term_code, e.crn, 'EVIDENCE_SECTION_NOT_STORED', NULL, e.course_id
FROM catalog_promoted.evidence_section e
WHERE EXISTS (SELECT 1 FROM catalog_research.offering o WHERE o.term_code = e.term_code AND o.course_id = e.course_id AND o.data_source = 'BANNER_EVIDENCE')
	AND NOT EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = e.term_code AND s.crn = e.crn)
UNION ALL
SELECT s.term_code, s.crn, 'SECTION_FIELD_DIFFERS', s.section_code || '/' || s.schedule_type, e.section_code || '/' || e.schedule_type
FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id)
JOIN catalog_promoted.evidence_section e ON e.term_code = s.term_code AND e.crn = s.crn AND e.course_id = o.course_id
WHERE o.data_source = 'BANNER_EVIDENCE' AND (s.section_code <> e.section_code OR s.schedule_type <> e.schedule_type)
UNION ALL
SELECT s.term_code, s.crn, 'CREDITS_DIFFER_FROM_GRID', s.credits::text, e.grid_hours
FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id)
JOIN catalog_promoted.evidence_section e ON e.term_code = s.term_code AND e.crn = s.crn AND e.course_id = o.course_id
WHERE o.data_source = 'BANNER_EVIDENCE'
	AND (e.grid_hours IS NULL OR e.grid_hours !~ '^[0-9]+(\.0+)?$' OR s.credits::numeric <> e.grid_hours::numeric)
-- Banner: meetings (both directions; an untimed evidence meeting can never match a stored timed one)
UNION ALL
SELECT m.term_code, m.crn, 'MEETING_NOT_IN_EVIDENCE', m.day_of_week || ' ' || m.start_time || '-' || m.end_time || ' ' || m.location, NULL
FROM catalog_research.section_meeting m
JOIN catalog_research.section s USING (term_code, crn) JOIN catalog_research.offering o USING (term_code, offering_id)
WHERE o.data_source = 'BANNER_EVIDENCE'
	AND NOT EXISTS (SELECT 1 FROM catalog_promoted.evidence_meeting e WHERE e.term_code = m.term_code AND e.crn = m.crn
		AND e.day_of_week = m.day_of_week AND e.start_time = m.start_time AND e.end_time = m.end_time AND e.location = m.location)
UNION ALL
SELECT e.term_code, e.crn, 'EVIDENCE_MEETING_NOT_STORED', NULL, e.day_of_week || ' ' || COALESCE(e.start_time::text, '?') || '-' || COALESCE(e.end_time::text, '?') || ' ' || e.location
FROM catalog_promoted.evidence_meeting e
WHERE EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = e.term_code AND s.crn = e.crn)
	AND NOT EXISTS (SELECT 1 FROM catalog_research.section_meeting m WHERE m.term_code = e.term_code AND m.crn = e.crn
		AND m.day_of_week = e.day_of_week AND m.start_time = e.start_time AND m.end_time = e.end_time AND m.location = e.location)
-- Banner: lecture -> lab links (both directions)
UNION ALL
SELECT s.term_code, s.crn, 'LAB_LINK_NOT_IN_EVIDENCE', s.lecture_crn, NULL
FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id)
WHERE o.data_source = 'BANNER_EVIDENCE' AND s.lecture_crn IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM catalog_promoted.evidence_link l WHERE l.term_code = s.term_code AND l.lecture_crn = s.lecture_crn AND l.linked_crn = s.crn)
UNION ALL
SELECT l.term_code, l.linked_crn, 'EVIDENCE_LINK_NOT_STORED', NULL, l.lecture_crn
FROM catalog_promoted.evidence_link l
WHERE EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = l.term_code AND s.crn = l.lecture_crn)
	AND NOT EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = l.term_code AND s.crn = l.linked_crn AND s.lecture_crn = l.lecture_crn)
-- Snapshot (verified catalog_api) offerings
UNION ALL
SELECT s.term_code, s.crn, 'SECTION_NOT_IN_SNAPSHOT_OR_DIFFERS', s.offering_id || '/' || s.section_code || '/' || s.component_type || '/' || s.credits || '/' || COALESCE(s.lecture_crn, '-'),
	NULL
FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id)
WHERE o.data_source = 'SNAPSHOT'
	AND NOT EXISTS (SELECT 1 FROM catalog_api.section_offering a WHERE a.term_code = s.term_code AND a.crn = s.crn AND a.course_id = o.course_id
		AND a.section_code = s.section_code AND a.component_type = s.component_type AND a.credits = s.credits
		AND a.lecture_crn IS NOT DISTINCT FROM s.lecture_crn)
UNION ALL
SELECT a.term_code, a.crn, 'SNAPSHOT_SECTION_NOT_STORED', NULL, a.course_id
FROM catalog_api.section_offering a
WHERE EXISTS (SELECT 1 FROM catalog_research.offering o WHERE o.term_code = a.term_code AND o.course_id = a.course_id AND o.data_source = 'SNAPSHOT')
	AND NOT EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = a.term_code AND s.crn = a.crn)
UNION ALL
SELECT m.term_code, m.crn, 'SNAPSHOT_MEETING_DIFFERS', m.day_of_week || ' ' || m.start_time || '-' || m.end_time || ' ' || m.location, NULL
FROM catalog_research.section_meeting m JOIN catalog_research.section s USING (term_code, crn) JOIN catalog_research.offering o USING (term_code, offering_id)
WHERE o.data_source = 'SNAPSHOT'
	AND NOT EXISTS (SELECT 1 FROM catalog_api.section_meeting a WHERE a.term_code = m.term_code AND a.crn = m.crn AND a.day_of_week = m.day_of_week
		AND a.start_time = to_char(m.start_time, 'HH24:MI') AND a.end_time = to_char(m.end_time, 'HH24:MI') AND a.location = m.location)
UNION ALL
SELECT a.term_code, a.crn, 'SNAPSHOT_MEETING_NOT_STORED', NULL, a.day_of_week || ' ' || a.start_time || '-' || a.end_time
FROM catalog_api.section_meeting a
WHERE EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = a.term_code AND s.crn = a.crn)
	AND NOT EXISTS (SELECT 1 FROM catalog_research.section_meeting m WHERE m.term_code = a.term_code AND m.crn = a.crn AND m.day_of_week = a.day_of_week
		AND to_char(m.start_time, 'HH24:MI') = a.start_time AND to_char(m.end_time, 'HH24:MI') = a.end_time AND m.location = a.location);
COMMENT ON VIEW catalog_research.evidence_mismatch IS 'Rows where stored data disagrees with the collected evidence or the verified catalog. Must be empty; import_term refuses to commit otherwise.';

-- ---------------------------------------------------------------------------------------------------------
-- Policy decisions: the latest decision per (term, CRN, type), and whether it still applies to the current evidence.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_research.active_tier_policy_decision AS
SELECT d.decision_id, d.term_code, d.crn, d.offering_id, d.decision_type, d.decision, d.observed_value, d.evidence_level, d.decided_by_role,
	d.decided_on, d.rationale, d.limitations, d.source_ref, d.recorded_at,
	(s.crn IS NOT NULL AND s.schedule_type = d.observed_value) AS is_fresh,
	(d.decision = 'ALLOW' AND s.crn IS NOT NULL AND s.schedule_type = d.observed_value) AS is_active
FROM catalog_research.tier_policy_decision d
LEFT JOIN catalog_research.section s ON s.term_code = d.term_code AND s.crn = d.crn AND s.offering_id = d.offering_id
WHERE NOT EXISTS (SELECT 1 FROM catalog_research.tier_policy_decision x WHERE x.supersedes_decision_id = d.decision_id);
COMMENT ON VIEW catalog_research.active_tier_policy_decision IS 'Latest decision per subject. is_active = an ALLOW whose observed Banner schedule type still equals the stored one. A policy decision is not Registrar certification.';

-- ---------------------------------------------------------------------------------------------------------
-- prerequisite_banner_check: for every stored prerequisite expression, the course numbers in the expression against the course numbers in
-- Banner's own prerequisite text for the same CRNs. A row here means the two lists differ. It checks WHICH courses appear, not the AND/OR shape.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_research.prerequisite_banner_check AS
SELECT r.term_code, r.offering_id,
	COALESCE((SELECT array_agg(DISTINCT right(p #>> '{}', 3) ORDER BY right(p #>> '{}', 3)) FROM jsonb_path_query(r.expression, 'strict $.**.course') p), '{}') AS expression_numbers,
	COALESCE((SELECT array_agg(DISTINCT m[1] ORDER BY m[1]) FROM regexp_matches(rs.source_text, '(\d{3}) (?:Undergraduate|Graduate)', 'g') m), '{}') AS banner_numbers
FROM catalog_research.eligibility_rule r
JOIN catalog_research.requirement_status rs ON rs.term_code = r.term_code AND rs.offering_id = r.offering_id AND rs.requirement_type = 'PREREQUISITE'
WHERE r.rule_kind = 'PREREQUISITE' AND r.parse_status = 'STRUCTURED' AND rs.evidence_level = 'BANNER'
	AND COALESCE((SELECT array_agg(DISTINCT right(p #>> '{}', 3) ORDER BY right(p #>> '{}', 3)) FROM jsonb_path_query(r.expression, 'strict $.**.course') p), '{}')
		IS DISTINCT FROM COALESCE((SELECT array_agg(DISTINCT m[1] ORDER BY m[1]) FROM regexp_matches(rs.source_text, '(\d{3}) (?:Undergraduate|Graduate)', 'g') m), '{}');
COMMENT ON VIEW catalog_research.prerequisite_banner_check IS 'Structured prerequisites whose course numbers differ from Banner''s own prerequisite text. Should be empty.';

-- ---------------------------------------------------------------------------------------------------------
-- Research tier, derived (never stored, so it cannot drift):
--   READY        served by catalog_api today (the verified snapshot);
--   BLOCKED      stored data disagrees with evidence, or the data is grid-staged (section details never inspected), or the topic-identity
--                scheme is undecided, or a section type other than lecture/lab has no active policy decision;
--   CONDITIONAL  everything else: stored and consistent, shown with its unresolved conditions.
-- It is a research classification. It is not a promotion and does not serve anything.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_research.offering_tier AS
SELECT o.term_code, o.offering_id,
	CASE
		WHEN EXISTS (SELECT 1 FROM catalog_research.evidence_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'BLOCKED'
		WHEN o.data_source = 'BANNER_GRID_STAGING' THEN 'BLOCKED'
		WHEN o.identity_status <> 'SINGLE' THEN 'BLOCKED'
		WHEN u.crns IS NOT NULL THEN 'BLOCKED'
		WHEN o.data_source = 'SNAPSHOT' AND COALESCE(a.schedulable, false) THEN 'READY'
		ELSE 'CONDITIONAL' END AS tier,
	CASE
		WHEN EXISTS (SELECT 1 FROM catalog_research.evidence_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'EVIDENCE_MISMATCH'
		WHEN o.data_source = 'BANNER_GRID_STAGING' THEN 'GRID_ONLY_DETAILS_NOT_INSPECTED'
		WHEN o.identity_status <> 'SINGLE' THEN 'TOPIC_IDENTITY_UNDECIDED'
		WHEN u.crns IS NOT NULL THEN 'SECTION_TYPE_UNSUPPORTED:' || u.crns
		WHEN o.data_source = 'SNAPSHOT' AND COALESCE(a.schedulable, false) THEN 'SERVED_BY_VERIFIED_CATALOG'
		WHEN p.crns IS NOT NULL THEN 'POLICY_DECISION_ALLOWS_SECTION_TYPE:' || p.crns
		ELSE 'UNRESOLVED_CONDITIONS_REMAIN' END AS tier_reason
FROM catalog_research.offering o
LEFT JOIN catalog_api.course_offering a ON a.term_code = o.term_code AND a.course_id = o.course_id
LEFT JOIN LATERAL (SELECT string_agg(s.crn, ',' ORDER BY s.crn) AS crns FROM catalog_research.section s
	WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id AND s.schedule_type NOT IN ('Lecture', 'Lab')
		AND NOT EXISTS (SELECT 1 FROM catalog_research.active_tier_policy_decision d WHERE d.term_code = s.term_code AND d.crn = s.crn AND d.is_active)) u ON true
LEFT JOIN LATERAL (SELECT string_agg(s.crn, ',' ORDER BY s.crn) AS crns FROM catalog_research.section s
	WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id AND s.schedule_type NOT IN ('Lecture', 'Lab')
		AND EXISTS (SELECT 1 FROM catalog_research.active_tier_policy_decision d WHERE d.term_code = s.term_code AND d.crn = s.crn AND d.is_active)) p ON true;
COMMENT ON VIEW catalog_research.offering_tier IS 'Derived research tier (READY / CONDITIONAL / BLOCKED) with the reason. A classification, not a promotion.';

-- ---------------------------------------------------------------------------------------------------------
-- offering_status: stored vs approved-to-serve vs (never) eligible, side by side. serving_status is what the backend would read today.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_research.offering_status AS
SELECT o.term_code, o.offering_id, o.course_id, o.title, o.credits, o.administrative_type, t.tier AS research_tier, t.tier_reason,
	(SELECT count(*) FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id)::int AS stored_sections,
	CASE o.data_source WHEN 'SNAPSHOT' THEN 'VERIFIED_CATALOG' WHEN 'BANNER_GRID_STAGING' THEN 'GRID_STAGING_ONLY' ELSE COALESCE(r.state, 'NO_EVIDENCE_GRID') END AS promotion_state,
	COALESCE(r.blockers, '{}'::text[]) AS promotion_blockers,
	live.course_id IS NOT NULL AS served_by_catalog_live,
	CASE WHEN live.course_id IS NOT NULL THEN 'SERVED' ELSE 'STORED_NOT_SERVED' END AS serving_status,
	false AS registration_eligibility_guaranteed
FROM catalog_research.offering o
JOIN catalog_research.offering_tier t ON t.term_code = o.term_code AND t.offering_id = o.offering_id
LEFT JOIN catalog_promoted.promotion_readiness r ON o.data_source = 'BANNER_EVIDENCE' AND r.term_code = o.term_code AND r.course_id = o.course_id
LEFT JOIN catalog_live.course_offering live ON live.term_code = o.term_code AND live.course_id = o.offering_id AND live.schedulable;
COMMENT ON VIEW catalog_research.offering_status IS 'Stored / approved-to-serve / eligibility, side by side. registration_eligibility_guaranteed is always false.';

-- What is still unresolved, per offering: the registration information we could not turn into a checkable fact. (NONE_LISTED and
-- USER_CONFIRMED_NONE are the reviewer's decision that nothing applies, so they are not listed.)
CREATE OR REPLACE VIEW catalog_research.unresolved_requirement AS
SELECT DISTINCT rs.term_code, rs.offering_id, rs.requirement_type, rs.status,
	CASE rs.status WHEN 'UNKNOWN' THEN 'no source states it'
		WHEN 'TEXT_ONLY' THEN 'stated in words only; not a rule the application can check'
		ELSE 'stated as a rule; the application does not enforce it today' END AS why
FROM catalog_research.requirement_status rs
WHERE rs.status NOT IN ('USER_CONFIRMED_NONE', 'NONE_LISTED');

-- ---------------------------------------------------------------------------------------------------------
-- Catalog-shaped candidate views: the same columns and types the adapter reads from catalog_api / catalog_live, over every
-- research-sourced offering (Banner evidence or the verified snapshot). Grid-staged offerings are deliberately absent: their lab and
-- recitation pairings are not collected, so they have nothing safe to hand to a scheduler. schedulable is true only for what catalog_live serves today. The adapter's schema allow-list does not include
-- catalog_research, so nothing here is served; these views let the stored data be compared with, and later promoted into, the
-- serving path without a second catalog architecture. A special topic's course_id is its offering_id (CSE-398-042).
-- (DISTINCT keeps the views from being auto-updatable; the rows are already unique.)
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_research.candidate_course_offering AS
SELECT DISTINCT o.term_code::varchar AS term_code, o.offering_id::varchar AS course_id, o.title, o.credits::int AS credits,
	EXISTS (SELECT 1 FROM catalog_live.course_offering l WHERE l.term_code = o.term_code AND l.course_id = o.offering_id AND l.schedulable) AS schedulable
FROM catalog_research.offering o
WHERE o.data_source IN ('BANNER_EVIDENCE', 'SNAPSHOT');

CREATE OR REPLACE VIEW catalog_research.candidate_section_offering AS
SELECT DISTINCT s.term_code::varchar AS term_code, s.offering_id::varchar AS course_id, s.crn::varchar AS crn, s.section_code::varchar AS section_code,
	s.credits::int AS credits, s.component_type, s.lecture_crn, 'UNKNOWN'::text AS status
FROM catalog_research.section s JOIN catalog_research.offering o USING (term_code, offering_id)
WHERE o.data_source IN ('BANNER_EVIDENCE', 'SNAPSHOT');

CREATE OR REPLACE VIEW catalog_research.candidate_section_meeting AS
SELECT DISTINCT m.term_code::varchar AS term_code, m.crn::varchar AS crn, m.day_of_week, to_char(m.start_time, 'HH24:MI') AS start_time,
	to_char(m.end_time, 'HH24:MI') AS end_time, m.location
FROM catalog_research.section_meeting m JOIN catalog_research.section s USING (term_code, crn) JOIN catalog_research.offering o USING (term_code, offering_id)
WHERE o.data_source IN ('BANNER_EVIDENCE', 'SNAPSHOT') AND m.meeting_kind = 'WEEKLY';
COMMENT ON VIEW catalog_research.candidate_course_offering IS 'Every research-sourced offering in the shape of catalog_api.course_offering. schedulable = served by catalog_live today. Not read by the backend.';

-- ---------------------------------------------------------------------------------------------------------
-- record_tier_policy_decision: the only writer of tier_policy_decision. Records a person's decision; never an approval.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION catalog_research.record_tier_policy_decision(p_term text, p_crn text, p_type text, p_decision text,
	p_rationale text, p_limitations text, p_decided_on date, p_source_ref text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	sec catalog_research.section%ROWTYPE;
	prev catalog_research.tier_policy_decision%ROWTYPE;
	new_id bigint;
BEGIN
	SELECT * INTO sec FROM catalog_research.section WHERE term_code = p_term AND crn = p_crn;
	IF NOT FOUND THEN
		RAISE EXCEPTION 'no stored section % in term %', p_crn, p_term USING ERRCODE = 'foreign_key_violation';
	END IF;
	IF p_type = 'ALLOW_SECTION_TYPE_AS_CONDITIONAL' AND sec.schedule_type IN ('Lecture', 'Lab') THEN
		RAISE EXCEPTION 'a % section needs no section-type decision', sec.schedule_type USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	SELECT d.* INTO prev FROM catalog_research.tier_policy_decision d
	WHERE d.term_code = p_term AND d.crn = p_crn AND d.decision_type = p_type
		AND NOT EXISTS (SELECT 1 FROM catalog_research.tier_policy_decision x WHERE x.supersedes_decision_id = d.decision_id);
	IF FOUND AND prev.decision = p_decision AND prev.observed_value = sec.schedule_type AND prev.rationale = p_rationale
		AND prev.limitations = p_limitations AND prev.decided_on = p_decided_on AND prev.source_ref = p_source_ref THEN
		RETURN prev.decision_id;      -- the identical current decision: nothing to record
	END IF;
	INSERT INTO catalog_research.tier_policy_decision (term_code, crn, offering_id, decision_type, decision, observed_value, evidence_level,
		decided_by_role, decided_on, rationale, limitations, source_ref, registrar_certified, guarantees_registration, supersedes_decision_id)
	VALUES (p_term, p_crn, sec.offering_id, p_type, p_decision, sec.schedule_type, 'TEAM_POLICY', 'DEMO_ACADEMIC_EVIDENCE_REVIEWER', p_decided_on,
		p_rationale, p_limitations, p_source_ref, false, false, prev.decision_id)
	RETURNING decision_id INTO new_id;
	RETURN new_id;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- import_term: replaces the stored catalog of ONE term from a normalized JSON payload, in the caller's transaction, and refuses to
-- finish if the result disagrees with the evidence. Policy decisions are never touched. Requirement status is derived here from the
-- evidence (verbatim Banner text) and the verified snapshot, never typed in.
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION catalog_research.import_term(p_payload jsonb, p_label text, p_sha256 text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	v_term text := p_payload ->> 'termCode';
	v_run bigint;
	v_offerings int;
	v_sections int;
	v_bad text;
	v_off text[];
	v_crns text[];
BEGIN
	IF v_term IS NULL OR v_term !~ '^[0-9]{6}$' THEN
		RAISE EXCEPTION 'payload.termCode must be a six-digit Banner term code' USING ERRCODE = 'invalid_parameter_value';
	END IF;
	IF jsonb_typeof(p_payload -> 'offerings') IS DISTINCT FROM 'array' OR jsonb_array_length(p_payload -> 'offerings') = 0 THEN
		RAISE EXCEPTION 'payload.offerings must be a non-empty array' USING ERRCODE = 'invalid_parameter_value';
	END IF;

	-- Replace only the research-sourced offerings of this term (Banner evidence and the verified snapshot). Offerings imported by
	-- import_noncse_term (BANNER_GRID_STAGING) and policy decisions are never touched.
	SELECT COALESCE(array_agg(offering_id), '{}') INTO v_off FROM catalog_research.offering WHERE term_code = v_term AND data_source IN ('BANNER_EVIDENCE', 'SNAPSHOT');
	SELECT COALESCE(array_agg(crn), '{}') INTO v_crns FROM catalog_research.section WHERE term_code = v_term AND offering_id = ANY (v_off);
	DELETE FROM catalog_research.section_arranged_meeting WHERE term_code = v_term AND crn = ANY (v_crns);
	DELETE FROM catalog_research.section_meeting WHERE term_code = v_term AND crn = ANY (v_crns);
	DELETE FROM catalog_research.credit_exclusion WHERE term_code = v_term AND offering_id = ANY (v_off);
	DELETE FROM catalog_research.course_alias WHERE term_code = v_term AND offering_id = ANY (v_off);
	DELETE FROM catalog_research.eligibility_rule WHERE term_code = v_term AND offering_id = ANY (v_off);
	DELETE FROM catalog_research.requirement_status WHERE term_code = v_term AND offering_id = ANY (v_off);
	DELETE FROM catalog_research.section WHERE term_code = v_term AND crn = ANY (v_crns) AND component_type <> 'LECTURE';
	DELETE FROM catalog_research.section WHERE term_code = v_term AND crn = ANY (v_crns);
	DELETE FROM catalog_research.offering WHERE term_code = v_term AND offering_id = ANY (v_off);

	IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_payload -> 'offerings') o(j) WHERE o.j ->> 'dataSource' NOT IN ('BANNER_EVIDENCE', 'SNAPSHOT')) THEN
		RAISE EXCEPTION 'import_term takes only BANNER_EVIDENCE and SNAPSHOT offerings; grid-staged data uses import_noncse_term' USING ERRCODE = 'invalid_parameter_value';
	END IF;
	IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'sections') s
			WHERE s ->> 'component' <> 'LECTURE' AND (s ->> 'lectureCrn') IS NULL) THEN
		RAISE EXCEPTION 'a research-sourced lab must link to its lecture (the link is never left uncollected here)' USING ERRCODE = 'integrity_constraint_violation';
	END IF;

	SELECT count(*) INTO v_offerings FROM jsonb_array_elements(p_payload -> 'offerings');
	SELECT count(*) INTO v_sections FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'sections');
	INSERT INTO catalog_research.import_run (term_code, input_sha256, input_label, offering_count, section_count)
	VALUES (v_term, p_sha256, p_label, v_offerings, v_sections) RETURNING run_id INTO v_run;

	INSERT INTO catalog_research.offering (term_code, offering_id, course_id, subject, course_number, topic, title, credits, administrative_type,
		data_source, components_status, required_lab_count, cross_listing_status, identity_status, run_id)
	SELECT v_term, j ->> 'offeringId', j ->> 'subject' || '-' || (j ->> 'number'), j ->> 'subject', j ->> 'number', j ->> 'topic', j ->> 'title',
		(j ->> 'credits')::smallint, j ->> 'administrativeType', j ->> 'dataSource', j ->> 'componentsStatus', (j ->> 'requiredLabCount')::smallint,
		j ->> 'crossListingStatus', j ->> 'identityStatus', v_run
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j);

	INSERT INTO catalog_research.section (term_code, crn, offering_id, section_code, component_type, credits, lecture_crn, link_status, schedule_type)
	SELECT v_term, s ->> 'crn', o.j ->> 'offeringId', s ->> 'sectionCode', s ->> 'component', (s ->> 'credits')::smallint, s ->> 'lectureCrn',
		CASE WHEN s ->> 'component' = 'LECTURE' THEN 'NOT_APPLICABLE' ELSE 'CONFIRMED' END, s ->> 'scheduleType'
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'sections') s;

	INSERT INTO catalog_research.section_meeting (term_code, crn, day_of_week, start_time, end_time, location)
	SELECT v_term, s ->> 'crn', (m ->> 'day')::smallint, (m ->> 'start')::time, (m ->> 'end')::time, m ->> 'location'
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'sections') s, jsonb_array_elements(s -> 'meetings') m;

	INSERT INTO catalog_research.section_arranged_meeting (term_code, crn, description)
	SELECT v_term, s ->> 'crn', s ->> 'arrangedMeeting'
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'sections') s WHERE s ->> 'arrangedMeeting' IS NOT NULL;

	-- Restrictions: the verbatim Banner restriction text is the source text of every restriction rule.
	INSERT INTO catalog_research.eligibility_rule (term_code, offering_id, rule_kind, polarity, rule_values, source_text, evidence_level, parse_status)
	SELECT v_term, o.j ->> 'offeringId', r ->> 'kind', r ->> 'polarity',
		COALESCE(ARRAY(SELECT jsonb_array_elements_text(r -> 'values')), '{}'),
		COALESCE(NULLIF((SELECT string_agg(DISTINCT e.note_text, E'\n' ORDER BY e.note_text) FROM catalog_promoted.evidence_requirement e
			WHERE e.term_code = v_term AND e.crn IN (SELECT x ->> 'crn' FROM jsonb_array_elements(o.j -> 'sections') x) AND e.note_kind = 'REGISTRATION_RESTRICTION'
				AND NOT catalog_promoted.is_empty_requirement(e.note_kind, e.note_text)), ''), r ->> 'sourceText'),
		COALESCE(r ->> 'evidenceLevel', 'BANNER'), CASE WHEN jsonb_array_length(COALESCE(r -> 'values', '[]'::jsonb)) > 0 THEN 'STRUCTURED' ELSE 'TEXT_ONLY' END
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'rules') r
	WHERE r ->> 'kind' NOT IN ('PREREQUISITE', 'COREQUISITE');

	-- Prerequisites: the research workbook's statement (its source text names Banner / the catalog). The verbatim Banner text is kept in requirement_status.
	INSERT INTO catalog_research.eligibility_rule (term_code, offering_id, rule_kind, polarity, expression, source_text, evidence_level, parse_status)
	SELECT v_term, o.j ->> 'offeringId', 'PREREQUISITE', 'REQUIRES', NULLIF(o.j -> 'prerequisiteExpression', 'null'::jsonb), o.j ->> 'prerequisiteText',
		'RESEARCH_WORKBOOK',
		CASE WHEN jsonb_typeof(o.j -> 'prerequisiteExpression') = 'object' THEN 'STRUCTURED' ELSE 'TEXT_ONLY' END
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j) WHERE o.j ->> 'prerequisiteText' IS NOT NULL;

	INSERT INTO catalog_research.credit_exclusion (term_code, offering_id, excluded_course_id, excluded_text, exclusion_kind, source_text, evidence_level)
	SELECT v_term, o.j ->> 'offeringId', x ->> 'withCourseId', x ->> 'withText', x ->> 'kind', x ->> 'text', 'RESEARCH_WORKBOOK'
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'creditExclusions') x;

	INSERT INTO catalog_research.course_alias (term_code, offering_id, alias_subject, alias_number, alias_status)
	SELECT v_term, o.j ->> 'offeringId', a ->> 'subject', a ->> 'number', 'CATALOG_ALIAS_UNVERIFIED'
	FROM jsonb_array_elements(p_payload -> 'offerings') o(j), jsonb_array_elements(o.j -> 'aliases') a;

	-- Requirement status: what is known for each of the three kinds, offering by offering.
	INSERT INTO catalog_research.requirement_status (term_code, offering_id, requirement_type, status, evidence_level, source_text)
	SELECT o.term_code, o.offering_id, k.requirement_type,
		CASE
			WHEN rl.n > 0 THEN CASE WHEN rl.structured THEN 'RULE_DEFINED' ELSE 'TEXT_ONLY' END
			WHEN bn.n > 0 THEN 'TEXT_ONLY'
			WHEN o.data_source = 'SNAPSHOT' AND sn.assessment_status = 'USER_CONFIRMED_NONE' THEN 'USER_CONFIRMED_NONE'
			WHEN o.data_source = 'BANNER_EVIDENCE' AND be.n > 0 THEN 'NONE_LISTED'
			ELSE 'UNKNOWN' END,
		CASE
			WHEN bn.n > 0 THEN 'BANNER'
			WHEN rl.n > 0 THEN rl.evidence_level
			WHEN o.data_source = 'SNAPSHOT' AND sn.assessment_status = 'USER_CONFIRMED_NONE' THEN 'USER_CLARIFICATION'
			WHEN o.data_source = 'BANNER_EVIDENCE' AND be.n > 0 THEN 'USER_CLARIFICATION'
			ELSE 'NONE' END,
		CASE WHEN rl.n > 0 OR bn.n > 0 THEN COALESCE(bn.txt, rl.txt) END
	FROM catalog_research.offering o
	CROSS JOIN (VALUES ('PREREQUISITE', 'PREREQUISITE_TEXT'), ('COREQUISITE', 'COREQUISITE_TEXT'), ('ENROLLMENT_RESTRICTION', 'REGISTRATION_RESTRICTION')) k(requirement_type, note_kind)
	LEFT JOIN LATERAL (SELECT count(*) AS n, bool_and(r.parse_status = 'STRUCTURED') AS structured, min(r.evidence_level) AS evidence_level,
			string_agg(r.source_text, E'\n' ORDER BY r.rule_id) AS txt
		FROM catalog_research.eligibility_rule r WHERE r.term_code = o.term_code AND r.offering_id = o.offering_id
			AND (r.rule_kind = k.requirement_type
				OR (k.requirement_type = 'ENROLLMENT_RESTRICTION' AND r.rule_kind NOT IN ('PREREQUISITE', 'COREQUISITE')))) rl ON true
	LEFT JOIN LATERAL (SELECT count(*) AS n, string_agg(DISTINCT e.note_text, E'\n' ORDER BY e.note_text) AS txt FROM catalog_promoted.evidence_requirement e
		WHERE e.term_code = o.term_code AND e.crn IN (SELECT s.crn FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id)
			AND e.note_kind = k.note_kind AND NOT catalog_promoted.is_empty_requirement(e.note_kind, e.note_text)) bn ON o.data_source = 'BANNER_EVIDENCE'
	LEFT JOIN LATERAL (SELECT count(*) AS n FROM catalog_promoted.evidence_requirement e
		WHERE e.term_code = o.term_code AND e.crn IN (SELECT s.crn FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id)
			AND e.note_kind = k.note_kind) be ON true
	LEFT JOIN LATERAL (SELECT cr.assessment_status FROM cse007_snapshot.course_requirement cr JOIN cse007_snapshot.course c ON c.course_id = cr.course_id
		WHERE c.code = o.course_id AND cr.requirement_type = k.requirement_type) sn ON true
	WHERE o.term_code = v_term AND o.offering_id IN (SELECT j ->> 'offeringId' FROM jsonb_array_elements(p_payload -> 'offerings') j);

	-- The result must agree with the evidence, or nothing is stored.
	SELECT string_agg(DISTINCT m.problem || ':' || m.crn, ', ') INTO v_bad FROM catalog_research.evidence_mismatch m WHERE m.term_code = v_term;
	IF v_bad IS NOT NULL THEN
		RAISE EXCEPTION 'imported data disagrees with the collected evidence (%); nothing is stored', v_bad USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	RETURN v_run;
END $$;
COMMENT ON FUNCTION catalog_research.import_term(jsonb, text, text) IS 'Replaces one term of the stored catalog from a normalized payload; aborts if the result disagrees with the evidence.';

REVOKE ALL ON FUNCTION catalog_research.import_term(jsonb, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_research.record_tier_policy_decision(text, text, text, text, text, text, date, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_research.reject_change() FROM PUBLIC;
