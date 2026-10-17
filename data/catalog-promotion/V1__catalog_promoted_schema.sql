-- catalog_promoted v1: promotes sufficiently verified Banner evidence into scheduler-facing course records,
-- without bypassing a single readiness rule and without touching evidence or David's snapshot.
--
-- HOW IT WORKS
--   banner_evidence (append-only evidence)  --derive-->  evidence_* views  --gate-->  promotion_blocker / promotion_readiness
--   a PERSON records approvals (approval table)  -->  promote_course() writes an immutable, versioned, provenance-bearing
--   copy (promotion + promoted_* tables)  -->  servable_* views re-check every gate at READ time  -->  catalog_live.
--
-- RULES BUILT IN
--   * Nothing is inferred. Facts come only from evidence rows; the only values a person supplies are approvals.
--   * Evidence is not approval. Approval rows are inserted by a person (record_approval or a plain INSERT), never by
--     any function in this schema; promote_course only READS approvals. Approvals are bound to a fingerprint of the
--     evidence they reviewed, so ANY new capture invalidates them until they are re-reviewed.
--   * Standalone status, required lab counts, lab credits, requirements, cross-listing and credit exclusion are never
--     inferred from missing links or "Option" labels: each needs an approval AND a recorded supporting source
--     (a USER_CLARIFICATION or TEAM_CONFIRMATION in banner_evidence), and credit approvals for non-lecture sections
--     always need one (CSE 007's lab-credit convention is not generalized).
--   * Eligibility rules we cannot enforce fail closed. Any prerequisite, corequisite or registration restriction text other
--     than Banner's own "no information" sentences, and any credit exclusion, blocks the course until the
--     application can enforce it (catalog_promoted.capability).
--   * Append-only: approvals, promotions and promoted rows are never updated or deleted. A later REVOKE or retraction is a
--     new row.
--   * Fail closed at READ time: a promoted course is served only while its package still equals what the evidence and
--     approvals produce NOW and it has no blocker. New or conflicting evidence stops it being served until re-reviewed.
--   * This schema only READS banner_evidence, catalog_api and cse007_snapshot. It never writes them.
--
-- Rerunnable (IF NOT EXISTS / OR REPLACE). Remove: DROP SCHEMA catalog_live CASCADE; DROP SCHEMA catalog_promoted CASCADE;

DO $$
BEGIN
	IF to_regclass('banner_evidence.claim_status') IS NULL OR to_regclass('banner_evidence.section_field_observation') IS NULL THEN
		RAISE EXCEPTION 'banner_evidence (V1 + V2) is missing; install data/banner-evidence first';
	END IF;
	IF to_regclass('catalog_api.course_offering') IS NULL OR to_regclass('cse007_snapshot.course') IS NULL THEN
		RAISE EXCEPTION 'catalog_api and cse007_snapshot are missing; install data/cse007-snapshot and data/catalog-api first';
	END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS catalog_promoted;
COMMENT ON SCHEMA catalog_promoted IS 'Approved, provenance-bearing promotions of Banner evidence. Append-only; reads evidence, never writes it.';

-- ---------------------------------------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION catalog_promoted.reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION 'catalog_promoted.% is append-only: % is not allowed (record a new row instead)', TG_TABLE_NAME, TG_OP
		USING ERRCODE = 'integrity_constraint_violation';
END $$;

-- Banner's own "no information" sentences. They say Banner holds nothing, NOT that nothing applies; an approval is still needed.
CREATE OR REPLACE FUNCTION catalog_promoted.is_empty_requirement(p_kind text, p_text text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
	SELECT (p_kind = 'REGISTRATION_RESTRICTION' AND p_text = 'No course restriction information is available.')
		OR (p_kind = 'PREREQUISITE_TEXT' AND p_text = 'Catalog Prerequisites No prerequisite information available.')
		OR (p_kind = 'COREQUISITE_TEXT' AND p_text = 'Corequisites No corequisite course information available.')
$$;

CREATE OR REPLACE FUNCTION catalog_promoted.requirement_capability(p_kind text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
	SELECT CASE p_kind WHEN 'PREREQUISITE_TEXT' THEN 'PREREQUISITES_ENFORCED' WHEN 'COREQUISITE_TEXT' THEN 'COREQUISITES_ENFORCED'
		WHEN 'REGISTRATION_RESTRICTION' THEN 'RESTRICTIONS_ENFORCED' END
$$;

-- What the APPLICATION can enforce today. All false: the scheduler takes no student profile and has no mutual-exclusion
-- constraint. Turn one on only in the same change that adds the enforcement.
CREATE TABLE IF NOT EXISTS catalog_promoted.capability (
	name    text PRIMARY KEY CHECK (name IN ('PREREQUISITES_ENFORCED', 'COREQUISITES_ENFORCED', 'RESTRICTIONS_ENFORCED', 'CREDIT_EXCLUSION_ENFORCED')),
	enabled boolean NOT NULL DEFAULT false,
	note    text NOT NULL
);
INSERT INTO catalog_promoted.capability (name, enabled, note) VALUES
	('PREREQUISITES_ENFORCED', false, 'The scheduler takes no transcript, so prerequisites are not enforced.'),
	('COREQUISITES_ENFORCED', false, 'Corequisite text is not enforced.'),
	('RESTRICTIONS_ENFORCED', false, 'The scheduler takes no class standing, major or college, so registration restrictions are not enforced.'),
	('CREDIT_EXCLUSION_ENFORCED', false, 'The engine has no constraint that two courses cannot both count for credit.')
ON CONFLICT (name) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------
-- Evidence-derived views (latest Banner capture per course / section; nothing is typed in by hand)
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE VIEW catalog_promoted.evidence_grid AS
SELECT DISTINCT ON (s.term_code, o.course_id) s.term_code, o.course_id, s.source_id AS grid_source_id,
	s.content_sha256 AS grid_sha, s.recorded_at
FROM banner_evidence.evidence_source s
JOIN banner_evidence.section_observation o ON o.source_id = s.source_id
WHERE s.source_kind = 'BANNER_LIVE' AND s.page_kind = 'SEARCH_RESULTS' AND o.course_id IS NOT NULL
ORDER BY s.term_code, o.course_id, s.recorded_at DESC, s.source_id;

CREATE OR REPLACE VIEW catalog_promoted.evidence_section AS
SELECT g.term_code, g.course_id, o.crn, o.section_code, o.title, o.schedule_type,
	g.grid_source_id, g.grid_sha,
	(SELECT f.field_value FROM banner_evidence.section_field_observation f
		WHERE f.source_id = g.grid_source_id AND f.crn = o.crn AND f.view_name = 'SEARCH_GRID' AND f.field_label = 'Hours') AS grid_hours,
	d.source_id AS details_source_id, d.content_sha256 AS details_sha,
	(SELECT f.field_value FROM banner_evidence.section_field_observation f
		WHERE f.source_id = d.source_id AND f.crn = o.crn AND f.view_name = 'CLASS_DETAILS' AND f.field_label = 'Credit Hours') AS details_credit_hours,
	l.source_id AS linked_source_id, l.content_sha256 AS linked_sha,
	(SELECT lo.linked_sections_outcome FROM banner_evidence.section_observation lo WHERE lo.source_id = l.source_id AND lo.crn = o.crn) AS linked_outcome
FROM catalog_promoted.evidence_grid g
JOIN banner_evidence.section_observation o ON o.source_id = g.grid_source_id
LEFT JOIN LATERAL (SELECT s.source_id, s.content_sha256 FROM banner_evidence.evidence_source s
	WHERE s.source_kind = 'BANNER_LIVE' AND s.page_kind = 'SECTION_DETAILS' AND s.term_code = g.term_code AND s.subject_crn = o.crn
	ORDER BY s.recorded_at DESC, s.source_id LIMIT 1) d ON true
LEFT JOIN LATERAL (SELECT s.source_id, s.content_sha256 FROM banner_evidence.evidence_source s
	WHERE s.source_kind = 'BANNER_LIVE' AND s.page_kind = 'LINKED_SECTIONS' AND s.term_code = g.term_code AND s.subject_crn = o.crn
	ORDER BY s.recorded_at DESC, s.source_id LIMIT 1) l ON true;

-- One row per meeting day, from the search-grid capture the section came from.
CREATE OR REPLACE VIEW catalog_promoted.evidence_meeting AS
SELECT e.term_code, e.course_id, e.crn, d.day_of_week::int AS day_of_week, m.start_time, m.end_time,
	COALESCE(NULLIF(btrim(concat_ws(' ', NULLIF(m.building_text, 'None'), NULLIF(m.room_text, 'None'))), ''), 'TBA') AS location,
	m.start_date, m.end_date, e.grid_source_id AS source_id
FROM catalog_promoted.evidence_section e
JOIN banner_evidence.meeting_observation m ON m.source_id = e.grid_source_id AND m.crn = e.crn
CROSS JOIN LATERAL unnest(m.days_iso) AS d(day_of_week);

CREATE OR REPLACE VIEW catalog_promoted.evidence_requirement AS
SELECT e.term_code, e.course_id, e.crn, n.note_kind, n.note_text, e.details_source_id AS source_id
FROM catalog_promoted.evidence_section e
JOIN banner_evidence.note_observation n ON n.source_id = e.details_source_id AND n.crn = e.crn
WHERE n.note_kind IN ('PREREQUISITE_TEXT', 'COREQUISITE_TEXT', 'REGISTRATION_RESTRICTION');

-- Lecture -> lab pairs listed by the lecture's own Linked Sections capture, and whether the lab's capture agrees.
CREATE OR REPLACE VIEW catalog_promoted.evidence_link AS
SELECT e.term_code, e.course_id, e.crn AS lecture_crn, l.linked_crn, e.linked_source_id AS source_id,
	EXISTS (SELECT 1 FROM catalog_promoted.evidence_section lab
		JOIN banner_evidence.link_observation ll ON ll.source_id = lab.linked_source_id
		WHERE lab.term_code = e.term_code AND lab.crn = l.linked_crn AND ll.lecture_crn = e.crn AND ll.linked_crn = l.linked_crn) AS confirmed_from_lab_side
FROM catalog_promoted.evidence_section e
JOIN banner_evidence.link_observation l ON l.source_id = e.linked_source_id AND l.lecture_crn = e.crn
WHERE e.schedule_type = 'Lecture';

-- A hash over EVERY distinct capture of this course's grid, details and linked-sections pages. It changes whenever a new,
-- different capture arrives, so approvals bound to it go stale and must be re-reviewed.
CREATE OR REPLACE FUNCTION catalog_promoted.evidence_fingerprint(p_term text, p_course text) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
	WITH grid AS (
		SELECT DISTINCT s.source_id, s.content_sha256 FROM banner_evidence.evidence_source s
		JOIN banner_evidence.section_observation o ON o.source_id = s.source_id
		WHERE s.source_kind = 'BANNER_LIVE' AND s.page_kind = 'SEARCH_RESULTS' AND s.term_code = p_term AND o.course_id = p_course),
	crns AS (SELECT DISTINCT o.crn FROM banner_evidence.section_observation o JOIN grid g ON g.source_id = o.source_id),
	per_crn AS (
		SELECT DISTINCT s.source_id, s.content_sha256 FROM banner_evidence.evidence_source s JOIN crns c ON c.crn = s.subject_crn
		WHERE s.source_kind = 'BANNER_LIVE' AND s.page_kind IN ('SECTION_DETAILS', 'LINKED_SECTIONS') AND s.term_code = p_term)
	SELECT encode(sha256(convert_to(COALESCE(string_agg(h, E'\n' ORDER BY h), ''), 'UTF8')), 'hex')
	FROM (SELECT 'g|' || content_sha256 AS h FROM grid UNION ALL SELECT 'p|' || content_sha256 FROM per_crn) q
$$;

-- Other courses' descriptions in David's snapshot that mention this course next to "no credit" (e.g. CSE 007 names CSE 003).
CREATE OR REPLACE FUNCTION catalog_promoted.snapshot_exclusion_mentions(p_course text) RETURNS text[]
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
	SELECT COALESCE(array_agg(c.code::text ORDER BY c.code), '{}') FROM cse007_snapshot.course c
	WHERE c.code <> p_course AND c.description ~* 'no credit'
		AND c.description ~* (regexp_replace(split_part(p_course, '-', 1), '[^A-Za-z]', '', 'g') || '[ -]?' || split_part(p_course, '-', 2))
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Approvals: a PERSON's explicit, recorded decision. Append-only.
-- ---------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS catalog_promoted.approval (
	approval_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code            text NOT NULL CHECK (term_code ~ '^[0-9]{6}$'),
	course_id            text NOT NULL CHECK (course_id ~ '^[A-Z]{2,4}-[0-9]{3}[A-Z]?$'),
	approval_type        text NOT NULL CHECK (approval_type IN ('CREDITS', 'STANDALONE', 'LAB_LINKS', 'REQUIRED_LAB_COUNT',
	                       'REQUIREMENTS', 'CROSS_LISTING', 'CREDIT_EXCLUSION')),
	subject_crn          text CHECK (subject_crn ~ '^[0-9]{5}$'),
	decision             text NOT NULL CHECK (decision IN ('APPROVE', 'REVOKE')),
	approved_value       text CHECK (length(approved_value) <= 100 AND banner_evidence.is_safe_text(approved_value)),
	approved_by          text NOT NULL CHECK (length(approved_by) BETWEEN 1 AND 60 AND banner_evidence.is_safe_text(approved_by)
	                       AND approved_by !~* '(^|[^a-z])(script|bot|claude|auto|automatic|collector|system)([^a-z]|$)'),
	rationale            text NOT NULL CHECK (length(rationale) BETWEEN 10 AND 1000 AND banner_evidence.is_safe_text(rationale)),
	-- A banner_evidence source of kind USER_CLARIFICATION or TEAM_CONFIRMATION that supports the approval (soft reference).
	supporting_source_id uuid,
	-- Hash of the evidence this approval reviewed. Stale as soon as a different capture arrives.
	evidence_fingerprint text NOT NULL CHECK (evidence_fingerprint ~ '^[0-9a-f]{64}$'),
	decided_by           text NOT NULL DEFAULT session_user,
	approved_at          timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT approval_shape CHECK (CASE approval_type
		WHEN 'CREDITS' THEN subject_crn IS NOT NULL AND (decision = 'REVOKE' OR approved_value ~ '^[0-9]{1,2}$')
		WHEN 'STANDALONE' THEN subject_crn IS NOT NULL AND (decision = 'REVOKE' OR approved_value = 'STANDALONE')
		WHEN 'LAB_LINKS' THEN subject_crn IS NOT NULL AND (decision = 'REVOKE' OR approved_value ~ '^LABS:[0-9]{5}(,[0-9]{5})*$')
		WHEN 'REQUIRED_LAB_COUNT' THEN subject_crn IS NOT NULL AND (decision = 'REVOKE' OR approved_value = '1')
		WHEN 'REQUIREMENTS' THEN subject_crn IS NULL AND (decision = 'REVOKE' OR approved_value IN ('NONE_ENFORCEABLE', 'ENFORCED_BY_APP'))
		WHEN 'CROSS_LISTING' THEN subject_crn IS NULL AND (decision = 'REVOKE' OR approved_value = 'NOT_CROSS_LISTED'
			OR approved_value ~ '^CROSS_LISTED_WITH:[A-Z]{2,4}-[0-9]{3}[A-Z]?$')
		WHEN 'CREDIT_EXCLUSION' THEN subject_crn IS NULL AND (decision = 'REVOKE' OR approved_value = 'NONE'
			OR approved_value ~ '^EXCLUDES:[A-Z]{2,4}-[0-9]{3}[A-Z]?(,[A-Z]{2,4}-[0-9]{3}[A-Z]?)*$')
		ELSE false END)
);
CREATE INDEX IF NOT EXISTS approval_subject ON catalog_promoted.approval
	(term_code, course_id, approval_type, (COALESCE(subject_crn, '')), approval_id DESC);

-- The approval in force for each subject: the latest row, if it is an APPROVE; fresh = reviewed the evidence as it is now.
CREATE OR REPLACE VIEW catalog_promoted.active_approval AS
SELECT a.*, (a.evidence_fingerprint = catalog_promoted.evidence_fingerprint(a.term_code, a.course_id)) AS is_fresh
FROM (SELECT DISTINCT ON (term_code, course_id, approval_type, COALESCE(subject_crn, '')) *
	FROM catalog_promoted.approval
	ORDER BY term_code, course_id, approval_type, COALESCE(subject_crn, ''), approval_id DESC) a
WHERE a.decision = 'APPROVE';

-- Validation for every insert path. It never CHANGES what the person wrote; it only refuses an approval that the
-- evidence cannot support.
CREATE OR REPLACE FUNCTION catalog_promoted.check_approval() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
	sec catalog_promoted.evidence_section;
	support banner_evidence.evidence_source;
	listed text;
	empty_ok boolean;
	nonempty_kinds text[];
	mentions text[];
	bad text;
BEGIN
	IF NOT EXISTS (SELECT 1 FROM catalog_promoted.evidence_grid g WHERE g.term_code = NEW.term_code AND g.course_id = NEW.course_id) THEN
		RAISE EXCEPTION 'no Banner search-grid evidence exists for % in term %', NEW.course_id, NEW.term_code USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	IF NEW.subject_crn IS NOT NULL THEN
		SELECT * INTO sec FROM catalog_promoted.evidence_section s
		WHERE s.term_code = NEW.term_code AND s.course_id = NEW.course_id AND s.crn = NEW.subject_crn;
		IF sec.crn IS NULL THEN
			RAISE EXCEPTION 'section % is not part of %', NEW.subject_crn, NEW.course_id USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	END IF;
	IF NEW.decision = 'REVOKE' THEN
		RETURN NEW;
	END IF;
	-- The approval must review the evidence exactly as it is now.
	IF NEW.evidence_fingerprint <> catalog_promoted.evidence_fingerprint(NEW.term_code, NEW.course_id) THEN
		RAISE EXCEPTION 'the evidence for % changed since this approval was prepared; review the current evidence and approve again', NEW.course_id
			USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	IF NEW.supporting_source_id IS NOT NULL THEN
		SELECT * INTO support FROM banner_evidence.evidence_source WHERE source_id = NEW.supporting_source_id;
		IF support.source_id IS NULL OR support.source_kind NOT IN ('USER_CLARIFICATION', 'TEAM_CONFIRMATION') OR support.term_code <> NEW.term_code THEN
			RAISE EXCEPTION 'supporting evidence must be a USER_CLARIFICATION or TEAM_CONFIRMATION source of the same term' USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	END IF;

	IF NEW.approval_type = 'CREDITS' THEN
		IF sec.schedule_type NOT IN ('Lecture', 'Lab') THEN
			RAISE EXCEPTION 'only lecture and lab sections can be approved (section % is %)', sec.crn, sec.schedule_type USING ERRCODE = 'integrity_constraint_violation';
		END IF;
		-- A lecture whose two Banner displays agree and equal the approved value needs only the person's approval.
		-- Everything else (any lab, differing displays, a value that is not the display) needs supporting evidence.
		IF NEW.supporting_source_id IS NULL AND (sec.schedule_type <> 'Lecture' OR sec.grid_hours IS DISTINCT FROM sec.details_credit_hours
				OR sec.grid_hours IS DISTINCT FROM NEW.approved_value) THEN
			RAISE EXCEPTION 'credits for section % need supporting evidence: it is not a lecture whose displays agree with the approved value', sec.crn
				USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	ELSIF NEW.approval_type = 'STANDALONE' THEN
		IF sec.schedule_type <> 'Lecture' OR sec.linked_outcome IS DISTINCT FROM 'NONE_SHOWN' THEN
			RAISE EXCEPTION 'standalone needs a lecture whose Linked Sections showed nothing' USING ERRCODE = 'integrity_constraint_violation';
		END IF;
		IF NEW.supporting_source_id IS NULL THEN
			RAISE EXCEPTION 'standalone status cannot be inferred from missing links: it needs supporting evidence' USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	ELSIF NEW.approval_type = 'LAB_LINKS' THEN
		SELECT 'LABS:' || string_agg(l.linked_crn, ',' ORDER BY l.linked_crn) INTO listed
		FROM catalog_promoted.evidence_link l WHERE l.term_code = NEW.term_code AND l.lecture_crn = NEW.subject_crn;
		IF sec.schedule_type <> 'Lecture' OR listed IS DISTINCT FROM NEW.approved_value THEN
			RAISE EXCEPTION 'approved labs must be exactly the labs Banner lists for lecture % (%)', NEW.subject_crn, COALESCE(listed, 'none')
				USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	ELSIF NEW.approval_type = 'REQUIRED_LAB_COUNT' THEN
		IF sec.schedule_type <> 'Lecture' OR sec.linked_outcome IS DISTINCT FROM 'LISTED' THEN
			RAISE EXCEPTION 'a lab count applies to a lecture with listed labs' USING ERRCODE = 'integrity_constraint_violation';
		END IF;
		IF NEW.supporting_source_id IS NULL THEN
			RAISE EXCEPTION 'the required lab count is not stated by Banner and cannot be inferred from the Option labels: it needs supporting evidence'
				USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	ELSIF NEW.approval_type = 'REQUIREMENTS' THEN
		IF NEW.supporting_source_id IS NULL THEN
			RAISE EXCEPTION 'requirements need supporting evidence' USING ERRCODE = 'integrity_constraint_violation';
		END IF;
		SELECT array_agg(DISTINCT r.note_kind) FILTER (WHERE NOT catalog_promoted.is_empty_requirement(r.note_kind, r.note_text)) INTO nonempty_kinds
		FROM catalog_promoted.evidence_requirement r WHERE r.term_code = NEW.term_code AND r.course_id = NEW.course_id;
		IF NEW.approved_value = 'NONE_ENFORCEABLE' AND nonempty_kinds IS NOT NULL THEN
			RAISE EXCEPTION 'Banner states requirements (%) that the scheduler cannot enforce; they cannot be approved away', array_to_string(nonempty_kinds, ', ')
				USING ERRCODE = 'integrity_constraint_violation';
		END IF;
		IF NEW.approved_value = 'ENFORCED_BY_APP' AND (nonempty_kinds IS NULL OR EXISTS (SELECT 1 FROM unnest(nonempty_kinds) k
				WHERE NOT EXISTS (SELECT 1 FROM catalog_promoted.capability c WHERE c.name = catalog_promoted.requirement_capability(k) AND c.enabled))) THEN
			RAISE EXCEPTION 'the application does not enforce these requirements yet' USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	ELSIF NEW.approval_type = 'CROSS_LISTING' THEN
		IF NEW.supporting_source_id IS NULL THEN
			RAISE EXCEPTION 'cross-listing status needs supporting evidence' USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	ELSIF NEW.approval_type = 'CREDIT_EXCLUSION' THEN
		IF NEW.supporting_source_id IS NULL THEN
			RAISE EXCEPTION 'credit-exclusion status needs supporting evidence' USING ERRCODE = 'integrity_constraint_violation';
		END IF;
		mentions := catalog_promoted.snapshot_exclusion_mentions(NEW.course_id);
		IF NEW.approved_value = 'NONE' AND cardinality(mentions) > 0 THEN
			RAISE EXCEPTION 'the catalog already states a credit exclusion with % ; "NONE" would contradict it', array_to_string(mentions, ', ')
				USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	END IF;
	RETURN NEW;
END $$;
CREATE OR REPLACE TRIGGER approval_validate BEFORE INSERT ON catalog_promoted.approval
	FOR EACH ROW EXECUTE FUNCTION catalog_promoted.check_approval();

-- Convenience for a person: stamps the CURRENT evidence fingerprint and the given decision-maker. It is a plain insert
-- with the same validation; nothing in this schema calls it.
CREATE OR REPLACE FUNCTION catalog_promoted.record_approval(p_term text, p_course text, p_type text, p_subject text, p_value text,
	p_approved_by text, p_rationale text, p_supporting_source uuid DEFAULT NULL, p_decision text DEFAULT 'APPROVE') RETURNS bigint
LANGUAGE sql AS $$
	INSERT INTO catalog_promoted.approval (term_code, course_id, approval_type, subject_crn, decision, approved_value, approved_by,
		rationale, supporting_source_id, evidence_fingerprint)
	VALUES (p_term, p_course, p_type, p_subject, p_decision, p_value, p_approved_by, p_rationale, p_supporting_source,
		catalog_promoted.evidence_fingerprint(p_term, p_course))
	RETURNING approval_id
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Blockers: one SELECT per reason. A course is ready to promote only when this view has no row for it.
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE VIEW catalog_promoted.promotion_blocker AS
-- Evidence completeness and consistency
SELECT s.term_code, s.course_id, 'SECTION_EVIDENCE_INCOMPLETE'::text AS code, s.crn::text AS detail
FROM catalog_promoted.evidence_section s
WHERE s.details_source_id IS NULL OR s.linked_source_id IS NULL OR COALESCE(s.linked_outcome, 'NOT_CHECKED') = 'NOT_CHECKED'
	OR s.grid_hours IS NULL OR s.details_credit_hours IS NULL
UNION ALL
SELECT s.term_code, s.course_id, 'SECTION_TYPE_UNSUPPORTED', s.crn || ' ' || s.schedule_type
FROM catalog_promoted.evidence_section s WHERE s.schedule_type NOT IN ('Lecture', 'Lab')
UNION ALL
SELECT s.term_code, s.course_id, 'NO_MEETING_TIMES', s.crn
FROM catalog_promoted.evidence_section s
WHERE NOT EXISTS (SELECT 1 FROM catalog_promoted.evidence_meeting m WHERE m.term_code = s.term_code AND m.crn = s.crn)
UNION ALL
SELECT s.term_code, s.course_id, 'EVIDENCE_INCONSISTENT', s.crn
FROM catalog_promoted.evidence_section s
JOIN banner_evidence.section_observation d ON d.source_id = s.details_source_id AND d.crn = s.crn
WHERE d.section_code IS DISTINCT FROM s.section_code OR d.schedule_type IS DISTINCT FROM s.schedule_type OR d.title IS DISTINCT FROM s.title
UNION ALL
SELECT s.term_code, s.course_id, 'TITLE_INCONSISTENT', count(DISTINCT s.title)::text
FROM catalog_promoted.evidence_section s GROUP BY s.term_code, s.course_id HAVING count(DISTINCT s.title) > 1
UNION ALL
SELECT g.term_code, g.course_id, 'NO_LECTURE_SECTION', ''
FROM catalog_promoted.evidence_grid g
WHERE NOT EXISTS (SELECT 1 FROM catalog_promoted.evidence_section s WHERE s.term_code = g.term_code AND s.course_id = g.course_id AND s.schedule_type = 'Lecture')
-- Credits
UNION ALL
SELECT s.term_code, s.course_id, 'CREDITS_NOT_WHOLE', s.crn
FROM catalog_promoted.evidence_section s WHERE s.grid_hours !~ '^[0-9]{1,2}$' OR s.details_credit_hours !~ '^[0-9]{1,2}$'
UNION ALL
SELECT s.term_code, s.course_id, 'CREDITS_NOT_APPROVED', s.crn
FROM catalog_promoted.evidence_section s
WHERE NOT EXISTS (SELECT 1 FROM catalog_promoted.active_approval a WHERE a.term_code = s.term_code AND a.course_id = s.course_id
	AND a.approval_type = 'CREDITS' AND a.subject_crn = s.crn AND a.is_fresh)
UNION ALL
SELECT s.term_code, s.course_id, 'LECTURE_CREDITS_DIFFER', ''
FROM catalog_promoted.evidence_section s WHERE s.schedule_type = 'Lecture'
GROUP BY s.term_code, s.course_id HAVING count(DISTINCT s.grid_hours) > 1
-- Components: a lecture is standalone only by approval WITH supporting evidence; labs need approved links and a count
UNION ALL
SELECT s.term_code, s.course_id, 'STANDALONE_NOT_APPROVED', s.crn
FROM catalog_promoted.evidence_section s
WHERE s.schedule_type = 'Lecture' AND s.linked_outcome = 'NONE_SHOWN'
	AND NOT EXISTS (SELECT 1 FROM catalog_promoted.active_approval a WHERE a.term_code = s.term_code AND a.course_id = s.course_id
		AND a.approval_type = 'STANDALONE' AND a.subject_crn = s.crn AND a.is_fresh)
UNION ALL
SELECT s.term_code, s.course_id, 'LAB_LINKS_NOT_APPROVED', s.crn
FROM catalog_promoted.evidence_section s
WHERE s.schedule_type = 'Lecture' AND s.linked_outcome = 'LISTED'
	AND NOT EXISTS (SELECT 1 FROM catalog_promoted.active_approval a WHERE a.term_code = s.term_code AND a.course_id = s.course_id
		AND a.approval_type = 'LAB_LINKS' AND a.subject_crn = s.crn AND a.is_fresh
		AND a.approved_value = 'LABS:' || (SELECT string_agg(l.linked_crn, ',' ORDER BY l.linked_crn) FROM catalog_promoted.evidence_link l
			WHERE l.term_code = s.term_code AND l.lecture_crn = s.crn))
UNION ALL
SELECT s.term_code, s.course_id, 'LAB_COUNT_NOT_APPROVED', s.crn
FROM catalog_promoted.evidence_section s
WHERE s.schedule_type = 'Lecture' AND s.linked_outcome = 'LISTED'
	AND NOT EXISTS (SELECT 1 FROM catalog_promoted.active_approval a WHERE a.term_code = s.term_code AND a.course_id = s.course_id
		AND a.approval_type = 'REQUIRED_LAB_COUNT' AND a.subject_crn = s.crn AND a.is_fresh)
UNION ALL
SELECT s.term_code, s.course_id, 'LAB_WITHOUT_CONFIRMED_LECTURE', s.crn
FROM catalog_promoted.evidence_section s
WHERE s.schedule_type = 'Lab'
	AND ((SELECT count(*) FROM catalog_promoted.evidence_link l WHERE l.term_code = s.term_code AND l.linked_crn = s.crn) <> 1
		OR NOT EXISTS (SELECT 1 FROM catalog_promoted.evidence_link l WHERE l.term_code = s.term_code AND l.linked_crn = s.crn AND l.confirmed_from_lab_side))
-- Registration requirements: unknown or unenforceable ones fail closed
UNION ALL
SELECT s.term_code, s.course_id, 'REQUIREMENTS_UNKNOWN', s.crn
FROM catalog_promoted.evidence_section s
WHERE (SELECT count(DISTINCT r.note_kind) FROM catalog_promoted.evidence_requirement r WHERE r.term_code = s.term_code AND r.crn = s.crn) < 3
UNION ALL
SELECT r.term_code, r.course_id, 'REQUIREMENTS_UNENFORCED', r.crn || ' ' || r.note_kind
FROM catalog_promoted.evidence_requirement r
WHERE NOT catalog_promoted.is_empty_requirement(r.note_kind, r.note_text)
	AND NOT EXISTS (SELECT 1 FROM catalog_promoted.capability c WHERE c.name = catalog_promoted.requirement_capability(r.note_kind) AND c.enabled)
UNION ALL
SELECT g.term_code, g.course_id, 'REQUIREMENTS_NOT_APPROVED', ''
FROM catalog_promoted.evidence_grid g
WHERE NOT EXISTS (SELECT 1 FROM catalog_promoted.active_approval a WHERE a.term_code = g.term_code AND a.course_id = g.course_id
	AND a.approval_type = 'REQUIREMENTS' AND a.is_fresh)
-- Course identity and combinations
UNION ALL
SELECT g.term_code, g.course_id, 'CROSS_LISTING_NOT_APPROVED', ''
FROM catalog_promoted.evidence_grid g
WHERE NOT EXISTS (SELECT 1 FROM catalog_promoted.active_approval a WHERE a.term_code = g.term_code AND a.course_id = g.course_id
	AND a.approval_type = 'CROSS_LISTING' AND a.is_fresh)
UNION ALL
SELECT a.term_code, a.course_id, 'CROSS_LISTED', a.approved_value
FROM catalog_promoted.active_approval a
WHERE a.approval_type = 'CROSS_LISTING' AND a.is_fresh AND a.approved_value <> 'NOT_CROSS_LISTED'
UNION ALL
SELECT g.term_code, g.course_id, 'CREDIT_EXCLUSION_NOT_APPROVED', ''
FROM catalog_promoted.evidence_grid g
WHERE NOT EXISTS (SELECT 1 FROM catalog_promoted.active_approval a WHERE a.term_code = g.term_code AND a.course_id = g.course_id
	AND a.approval_type = 'CREDIT_EXCLUSION' AND a.is_fresh)
UNION ALL
SELECT x.term_code, x.course_id, 'CREDIT_EXCLUSION_UNENFORCED', x.detail FROM (
	SELECT g.term_code, g.course_id, m AS detail FROM catalog_promoted.evidence_grid g
		CROSS JOIN LATERAL unnest(catalog_promoted.snapshot_exclusion_mentions(g.course_id)) AS m
	UNION
	SELECT a.term_code, a.course_id, e FROM catalog_promoted.active_approval a
		CROSS JOIN LATERAL unnest(string_to_array(substr(a.approved_value, 10), ',')) AS e
		WHERE a.approval_type = 'CREDIT_EXCLUSION' AND a.is_fresh AND a.approved_value LIKE 'EXCLUDES:%') x
WHERE NOT EXISTS (SELECT 1 FROM catalog_promoted.capability c WHERE c.name = 'CREDIT_EXCLUSION_ENFORCED' AND c.enabled)
-- Evidence that failed, conflicts, or changed after it was reviewed
UNION ALL
SELECT g.term_code, g.course_id, 'EVIDENCE_CONFLICT', 'claim ' || c.claim_id
FROM catalog_promoted.evidence_grid g
JOIN banner_evidence.claim_status c ON c.term_code = g.term_code AND c.course_id = g.course_id
WHERE c.review_flag = 'CONFLICT'
UNION ALL
SELECT a.term_code, a.course_id, 'APPROVAL_STALE', a.approval_type || COALESCE(' ' || a.subject_crn, '')
FROM catalog_promoted.active_approval a
WHERE NOT a.is_fresh AND EXISTS (SELECT 1 FROM catalog_promoted.evidence_grid g WHERE g.term_code = a.term_code AND g.course_id = a.course_id)
-- Never duplicate what the verified snapshot already serves
UNION ALL
SELECT g.term_code, g.course_id, 'DUPLICATES_EXISTING_CATALOG', 'course'
FROM catalog_promoted.evidence_grid g
WHERE EXISTS (SELECT 1 FROM catalog_api.course_offering o WHERE o.term_code = g.term_code AND o.course_id = g.course_id)
UNION ALL
SELECT s.term_code, s.course_id, 'DUPLICATES_EXISTING_CATALOG', 'crn ' || s.crn
FROM catalog_promoted.evidence_section s
WHERE EXISTS (SELECT 1 FROM catalog_api.section_offering o WHERE o.term_code = s.term_code AND o.crn = s.crn);
COMMENT ON VIEW catalog_promoted.promotion_blocker IS 'One row per reason a candidate course cannot be promoted or served. No row = no blocker.';

-- ---------------------------------------------------------------------------------------------------------
-- The package: exactly what would be promoted, built only from evidence and fresh approvals
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION catalog_promoted.package_json(p_term text, p_course text) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
	WITH sec AS (SELECT * FROM catalog_promoted.evidence_section WHERE term_code = p_term AND course_id = p_course),
	cred AS (SELECT a.subject_crn, a.approval_id, a.approved_value::int AS credits FROM catalog_promoted.active_approval a
		WHERE a.term_code = p_term AND a.course_id = p_course AND a.approval_type = 'CREDITS' AND a.is_fresh),
	parent AS (SELECT l.linked_crn, l.lecture_crn FROM catalog_promoted.evidence_link l WHERE l.term_code = p_term AND l.course_id = p_course)
	SELECT jsonb_build_object(
		'term_code', p_term, 'course_id', p_course,
		'title', (SELECT min(title) FROM sec),
		'course_credits', (SELECT max(c.credits) FROM sec s JOIN cred c ON c.subject_crn = s.crn WHERE s.schedule_type = 'Lecture'),
		'sections', COALESCE((SELECT jsonb_agg(jsonb_build_object('crn', s.crn, 'section_code', s.section_code,
				'component_type', CASE s.schedule_type WHEN 'Lecture' THEN 'LECTURE' WHEN 'Lab' THEN 'LAB' END,
				'credits', c.credits, 'credits_approval_id', c.approval_id, 'parent_crn', p.lecture_crn,
				'grid_source_id', s.grid_source_id, 'details_source_id', s.details_source_id, 'linked_source_id', s.linked_source_id,
				'grid_sha', s.grid_sha, 'details_sha', s.details_sha, 'linked_sha', s.linked_sha) ORDER BY s.crn)
			FROM sec s LEFT JOIN cred c ON c.subject_crn = s.crn LEFT JOIN parent p ON p.linked_crn = s.crn), '[]'::jsonb),
		'meetings', COALESCE((SELECT jsonb_agg(jsonb_build_object('crn', m.crn, 'day_of_week', m.day_of_week, 'start_time', m.start_time,
				'end_time', m.end_time, 'location', m.location, 'start_date', m.start_date, 'end_date', m.end_date, 'source_id', m.source_id)
				ORDER BY m.crn, m.day_of_week, m.start_time)
			FROM catalog_promoted.evidence_meeting m WHERE m.term_code = p_term AND m.course_id = p_course), '[]'::jsonb),
		'requirements', COALESCE((SELECT jsonb_agg(jsonb_build_object('crn', r.crn, 'kind', r.note_kind, 'text', r.note_text, 'source_id', r.source_id)
				ORDER BY r.crn, r.note_kind)
			FROM catalog_promoted.evidence_requirement r WHERE r.term_code = p_term AND r.course_id = p_course), '[]'::jsonb),
		'approval_ids', COALESCE((SELECT jsonb_agg(a.approval_id ORDER BY a.approval_id) FROM catalog_promoted.active_approval a
			WHERE a.term_code = p_term AND a.course_id = p_course AND a.is_fresh), '[]'::jsonb))
$$;

CREATE OR REPLACE FUNCTION catalog_promoted.package_sha(p_term text, p_course text) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
	SELECT encode(sha256(convert_to(catalog_promoted.package_json(p_term, p_course)::text, 'UTF8')), 'hex')
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Promotions: immutable, versioned, with provenance on every row
-- ---------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS catalog_promoted.promotion (
	promotion_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code      text NOT NULL CHECK (term_code ~ '^[0-9]{6}$'),
	course_id      text NOT NULL CHECK (course_id ~ '^[A-Z]{2,4}-[0-9]{3}[A-Z]?$'),
	version        int NOT NULL CHECK (version > 0),
	package_sha256 text NOT NULL CHECK (package_sha256 ~ '^[0-9a-f]{64}$'),
	package        jsonb NOT NULL,
	promoted_by    text NOT NULL CHECK (length(promoted_by) BETWEEN 1 AND 60 AND banner_evidence.is_safe_text(promoted_by)
	                 AND promoted_by !~* '(^|[^a-z])(script|bot|claude|auto|automatic|collector|system)([^a-z]|$)'),
	decided_by     text NOT NULL DEFAULT session_user,
	promoted_at    timestamptz NOT NULL DEFAULT now(),
	UNIQUE (term_code, course_id, version),
	UNIQUE (promotion_id, term_code, course_id)
);
CREATE TABLE IF NOT EXISTS catalog_promoted.promotion_retraction (
	retraction_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	promotion_id  bigint NOT NULL UNIQUE REFERENCES catalog_promoted.promotion (promotion_id),
	retracted_by  text NOT NULL CHECK (length(retracted_by) BETWEEN 1 AND 60 AND banner_evidence.is_safe_text(retracted_by)),
	reason        text NOT NULL CHECK (length(reason) BETWEEN 10 AND 1000 AND banner_evidence.is_safe_text(reason)),
	retracted_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS catalog_promoted.promoted_course (
	promotion_id bigint PRIMARY KEY REFERENCES catalog_promoted.promotion (promotion_id),
	term_code    text NOT NULL,
	course_id    text NOT NULL,
	title        text NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
	credits      int NOT NULL CHECK (credits >= 0),
	FOREIGN KEY (promotion_id, term_code, course_id) REFERENCES catalog_promoted.promotion (promotion_id, term_code, course_id)
);
CREATE TABLE IF NOT EXISTS catalog_promoted.promoted_section (
	promotion_id        bigint NOT NULL REFERENCES catalog_promoted.promotion (promotion_id),
	crn                 text NOT NULL CHECK (crn ~ '^[0-9]{5}$'),
	section_code        text NOT NULL,
	component_type      text NOT NULL CHECK (component_type IN ('LECTURE', 'LAB')),
	credits             int NOT NULL CHECK (credits >= 0),
	parent_crn          text,
	-- Provenance: the exact Banner captures this row came from, and the approval that set its credits.
	grid_source_id      uuid NOT NULL,
	details_source_id   uuid NOT NULL,
	linked_source_id    uuid NOT NULL,
	credits_approval_id bigint NOT NULL REFERENCES catalog_promoted.approval (approval_id),
	PRIMARY KEY (promotion_id, crn),
	CHECK ((component_type = 'LAB') = (parent_crn IS NOT NULL)),
	FOREIGN KEY (promotion_id, parent_crn) REFERENCES catalog_promoted.promoted_section (promotion_id, crn)
);
CREATE TABLE IF NOT EXISTS catalog_promoted.promoted_meeting (
	promotion_id bigint NOT NULL,
	crn          text NOT NULL,
	day_of_week  int NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
	start_time   time NOT NULL,
	end_time     time NOT NULL,
	location     text NOT NULL,
	start_date   date,
	end_date     date,
	source_id    uuid NOT NULL,
	PRIMARY KEY (promotion_id, crn, day_of_week, start_time, end_time),
	FOREIGN KEY (promotion_id, crn) REFERENCES catalog_promoted.promoted_section (promotion_id, crn),
	CHECK (end_time > start_time)
);
-- Registration requirements travel with the section, verbatim, with the capture they came from.
CREATE TABLE IF NOT EXISTS catalog_promoted.promoted_requirement (
	promotion_id     bigint NOT NULL,
	crn              text NOT NULL,
	requirement_kind text NOT NULL CHECK (requirement_kind IN ('PREREQUISITE_TEXT', 'COREQUISITE_TEXT', 'REGISTRATION_RESTRICTION')),
	requirement_text text NOT NULL,
	source_id        uuid NOT NULL,
	PRIMARY KEY (promotion_id, crn, requirement_kind),
	FOREIGN KEY (promotion_id, crn) REFERENCES catalog_promoted.promoted_section (promotion_id, crn)
);
CREATE TABLE IF NOT EXISTS catalog_promoted.promotion_approval (
	promotion_id bigint NOT NULL REFERENCES catalog_promoted.promotion (promotion_id),
	approval_id  bigint NOT NULL REFERENCES catalog_promoted.approval (approval_id),
	PRIMARY KEY (promotion_id, approval_id)
);

DO $$
DECLARE
	t text;
BEGIN
	FOREACH t IN ARRAY ARRAY['approval', 'promotion', 'promotion_retraction', 'promoted_course', 'promoted_section', 'promoted_meeting',
			'promoted_requirement', 'promotion_approval'] LOOP
		EXECUTE format('CREATE OR REPLACE TRIGGER %I BEFORE UPDATE OR DELETE ON catalog_promoted.%I FOR EACH ROW EXECUTE FUNCTION catalog_promoted.reject_change()', t || '_append_only', t);
		EXECUTE format('CREATE OR REPLACE TRIGGER %I BEFORE TRUNCATE ON catalog_promoted.%I FOR EACH STATEMENT EXECUTE FUNCTION catalog_promoted.reject_change()', t || '_no_truncate', t);
	END LOOP;
END $$;

CREATE OR REPLACE VIEW catalog_promoted.current_promotion AS
SELECT DISTINCT p.promotion_id, p.term_code, p.course_id, p.version, p.package_sha256, p.promoted_by, p.promoted_at
FROM catalog_promoted.promotion p
WHERE p.version = (SELECT max(q.version) FROM catalog_promoted.promotion q WHERE q.term_code = p.term_code AND q.course_id = p.course_id)
	AND NOT EXISTS (SELECT 1 FROM catalog_promoted.promotion_retraction r WHERE r.promotion_id = p.promotion_id);

-- ---------------------------------------------------------------------------------------------------------
-- promotion_readiness: one row per candidate course, with every blocker
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE VIEW catalog_promoted.promotion_readiness AS
SELECT g.term_code, g.course_id,
	(SELECT count(*) FROM catalog_promoted.evidence_section s WHERE s.term_code = g.term_code AND s.course_id = g.course_id) AS section_count,
	COALESCE(b.blockers, '{}'::text[]) AS blockers,
	COALESCE(b.blocker_count, 0)::int AS blocker_count,
	cp.version AS promoted_version,
	CASE WHEN COALESCE(b.blocker_count, 0) = 0 AND cp.promotion_id IS NOT NULL AND cp.package_sha256 = catalog_promoted.package_sha(g.term_code, g.course_id) THEN 'PROMOTED_SERVING'
		WHEN COALESCE(b.blocker_count, 0) = 0 THEN 'READY_TO_PROMOTE'
		WHEN cp.promotion_id IS NOT NULL THEN 'PROMOTED_BLOCKED'
		ELSE 'BLOCKED' END AS state
FROM catalog_promoted.evidence_grid g
LEFT JOIN (SELECT term_code, course_id, count(*) AS blocker_count,
		array_agg(code || CASE WHEN detail <> '' THEN ':' || detail ELSE '' END ORDER BY code, detail) AS blockers
	FROM catalog_promoted.promotion_blocker GROUP BY term_code, course_id) b ON b.term_code = g.term_code AND b.course_id = g.course_id
LEFT JOIN catalog_promoted.current_promotion cp ON cp.term_code = g.term_code AND cp.course_id = g.course_id;
COMMENT ON VIEW catalog_promoted.promotion_readiness IS 'Candidate courses (those with a Banner search-grid capture) and every blocker. Read-only. state PROMOTED_SERVING means the promotion is still valid against current evidence.';

-- ---------------------------------------------------------------------------------------------------------
-- promote_course / retract_promotion: explicit, human-invoked, idempotent. Neither inserts an approval.
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION catalog_promoted.promote_course(p_term text, p_course text, p_promoted_by text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	r catalog_promoted.promotion_readiness;
	pkg jsonb;
	sha text;
	latest catalog_promoted.promotion;
	v int;
	pid bigint;
BEGIN
	-- A person promotes; an automated-sounding or empty name is refused on every call, including idempotent no-ops.
	IF p_promoted_by IS NULL OR length(p_promoted_by) NOT BETWEEN 1 AND 60
			OR p_promoted_by ~* '(^|[^a-z])(script|bot|claude|auto|automatic|collector|system)([^a-z]|$)' THEN
		RAISE EXCEPTION 'promotion must name the person who decided it' USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	PERFORM pg_advisory_xact_lock(hashtext('catalog_promoted:' || p_term || ':' || p_course));
	SELECT * INTO r FROM catalog_promoted.promotion_readiness WHERE term_code = p_term AND course_id = p_course;
	IF r.course_id IS NULL THEN
		RAISE EXCEPTION 'no Banner evidence for % in term %', p_course, p_term USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	IF r.blocker_count > 0 THEN
		RAISE EXCEPTION '% is not ready to promote: % blocker(s): %', p_course, r.blocker_count, array_to_string(r.blockers, '; ')
			USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	pkg := catalog_promoted.package_json(p_term, p_course);
	sha := encode(sha256(convert_to(pkg::text, 'UTF8')), 'hex');
	SELECT * INTO latest FROM catalog_promoted.promotion WHERE term_code = p_term AND course_id = p_course ORDER BY version DESC LIMIT 1;
	IF latest.promotion_id IS NOT NULL AND latest.package_sha256 = sha
			AND NOT EXISTS (SELECT 1 FROM catalog_promoted.promotion_retraction x WHERE x.promotion_id = latest.promotion_id) THEN
		RETURN latest.promotion_id;  -- idempotent: this exact package is already the current promotion
	END IF;
	v := COALESCE(latest.version, 0) + 1;
	INSERT INTO catalog_promoted.promotion (term_code, course_id, version, package_sha256, package, promoted_by)
	VALUES (p_term, p_course, v, sha, pkg, p_promoted_by) RETURNING promotion_id INTO pid;
	INSERT INTO catalog_promoted.promoted_course (promotion_id, term_code, course_id, title, credits)
	VALUES (pid, p_term, p_course, pkg->>'title', (pkg->>'course_credits')::int);
	-- lectures first, then labs (a lab references its lecture)
	INSERT INTO catalog_promoted.promoted_section (promotion_id, crn, section_code, component_type, credits, parent_crn,
		grid_source_id, details_source_id, linked_source_id, credits_approval_id)
	SELECT pid, x.crn, x.section_code, x.component_type, x.credits, x.parent_crn, x.grid_source_id, x.details_source_id, x.linked_source_id, x.credits_approval_id
	FROM jsonb_to_recordset(pkg->'sections') AS x(crn text, section_code text, component_type text, credits int, parent_crn text,
		grid_source_id uuid, details_source_id uuid, linked_source_id uuid, credits_approval_id bigint)
	ORDER BY (x.component_type = 'LAB'), x.crn;
	INSERT INTO catalog_promoted.promoted_meeting (promotion_id, crn, day_of_week, start_time, end_time, location, start_date, end_date, source_id)
	SELECT pid, x.crn, x.day_of_week, x.start_time, x.end_time, x.location, x.start_date, x.end_date, x.source_id
	FROM jsonb_to_recordset(pkg->'meetings') AS x(crn text, day_of_week int, start_time time, end_time time, location text, start_date date, end_date date, source_id uuid);
	INSERT INTO catalog_promoted.promoted_requirement (promotion_id, crn, requirement_kind, requirement_text, source_id)
	SELECT pid, x.crn, x.kind, x.text, x.source_id
	FROM jsonb_to_recordset(pkg->'requirements') AS x(crn text, kind text, text text, source_id uuid);
	INSERT INTO catalog_promoted.promotion_approval (promotion_id, approval_id)
	SELECT pid, (x.v)::bigint FROM jsonb_array_elements_text(pkg->'approval_ids') AS x(v);
	RETURN pid;
END $$;

CREATE OR REPLACE FUNCTION catalog_promoted.retract_promotion(p_promotion_id bigint, p_retracted_by text, p_reason text) RETURNS bigint
LANGUAGE sql AS $$
	INSERT INTO catalog_promoted.promotion_retraction (promotion_id, retracted_by, reason) VALUES (p_promotion_id, p_retracted_by, p_reason)
	ON CONFLICT (promotion_id) DO NOTHING RETURNING retraction_id
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Serving: a promotion is served only while it is still the current one, equals what the evidence and approvals produce
-- NOW, and has no blocker. Anything else (new evidence, a conflict, a revoked approval, a retraction) stops it at once.
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE VIEW catalog_promoted.servable_course AS
SELECT cp.promotion_id, cp.term_code, cp.course_id, pc.title, pc.credits
FROM catalog_promoted.current_promotion cp
JOIN catalog_promoted.promoted_course pc ON pc.promotion_id = cp.promotion_id
JOIN catalog_promoted.promotion_readiness r ON r.term_code = cp.term_code AND r.course_id = cp.course_id
WHERE r.blocker_count = 0 AND cp.package_sha256 = catalog_promoted.package_sha(cp.term_code, cp.course_id);

CREATE OR REPLACE VIEW catalog_promoted.servable_section AS
SELECT sc.term_code, sc.course_id, ps.promotion_id, ps.crn, ps.section_code, ps.component_type, ps.credits, ps.parent_crn
FROM catalog_promoted.servable_course sc JOIN catalog_promoted.promoted_section ps ON ps.promotion_id = sc.promotion_id;

CREATE OR REPLACE VIEW catalog_promoted.servable_meeting AS
SELECT sc.term_code, pm.crn, pm.day_of_week, pm.start_time, pm.end_time, pm.location
FROM catalog_promoted.servable_course sc JOIN catalog_promoted.promoted_meeting pm ON pm.promotion_id = sc.promotion_id;

CREATE OR REPLACE VIEW catalog_promoted.servable_requirement AS
SELECT sc.term_code, sc.course_id, pr.crn, pr.requirement_kind, pr.requirement_text
FROM catalog_promoted.servable_course sc JOIN catalog_promoted.promoted_requirement pr ON pr.promotion_id = sc.promotion_id;

-- ---------------------------------------------------------------------------------------------------------
-- Privileges. Views run with their owner's rights, but functions called from a view run as the CALLER. So the read-only
-- backend role needs schema USAGE and EXECUTE on the few functions the views call (they only return hashes, codes or
-- booleans; the ones that read evidence are SECURITY DEFINER with a pinned search_path), and NO table privilege.
-- Everything that records or changes state is closed to PUBLIC: only an explicitly granted person can approve or promote.
-- ---------------------------------------------------------------------------------------------------------
REVOKE ALL ON FUNCTION catalog_promoted.package_json(text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_promoted.promote_course(text, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_promoted.record_approval(text, text, text, text, text, text, text, uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_promoted.retract_promotion(bigint, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_promoted.check_approval() FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_promoted.reject_change() FROM PUBLIC;
