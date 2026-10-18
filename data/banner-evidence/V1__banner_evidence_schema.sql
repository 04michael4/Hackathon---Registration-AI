-- banner_evidence v1: an append-only evidence layer for Lehigh Banner / course-page observations.
--
-- WHAT IT IS: a place to record what a source SAID (sources, observations), what we CLAIM from it (claims,
-- evidence links) and what a HUMAN decided (reviews). Nothing here is a catalog fact, and nothing here makes
-- a course schedulable: catalog_api never reads this schema and this schema never reads cse007_snapshot or
-- catalog_api. Promotion into the catalog is a separate, reviewed step.
--
-- RULES BUILT IN
--   * Append-only: evidence, observation, claim, link and review rows cannot be updated, deleted or truncated.
--     A newer or conflicting observation is a NEW row; both stay.
--   * Sanitized: free text must pass banner_evidence.is_safe_text (no HTML, cookies, session ids, tokens, long
--     digit runs such as student ids, e-mail addresses, or anything over 1000 characters). Source paths carry no
--     query string, fragment or ';' parameter. This is a tripwire, not proof of anonymity: sanitizing is done
--     before a row is written.
--   * Evidence is not approval: a status is DERIVED from evidence by the views; only a claim_review row,
--     written by a person, can confirm a claim, and even then the claim needs Banner or team evidence.
--
-- Rerunnable: CREATE ... IF NOT EXISTS / OR REPLACE. Changing an existing table needs a V2 migration.
-- Remove everything: DROP SCHEMA banner_evidence CASCADE;  (touches no other schema)

CREATE SCHEMA IF NOT EXISTS banner_evidence;
COMMENT ON SCHEMA banner_evidence IS 'Banner evidence layer v1: append-only; evidence is not approval; no catalog dependency.';

-- ---------------------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION banner_evidence.is_safe_text(value text) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
	SELECT value IS NULL OR (
		length(value) <= 1000
		AND value !~* '<\s*/?\s*(html|head|body|script|style|div|span|form|input|table|iframe|a)\y'
		AND value !~* '<!doctype'
		AND value !~* '(set-cookie|cookie\s*[:=]|authorization\s*[:=]|bearer\s+[a-z0-9._-]{8,}|jsessionid|sessionid|session_id|uniquesessionid|csrf|xsrf|api[_-]?key|passw(or)?d|access_token|refresh_token|id_token|token\s*[:=])'
		AND value !~ '[0-9]{8,}'
		AND value !~* '[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}'
		AND value !~ '[\x01-\x08\x0b\x0c\x0e-\x1f]'
	)
$$;
COMMENT ON FUNCTION banner_evidence.is_safe_text(text) IS 'Tripwire for free text: rejects page markup, credentials, session ids, 8+ digit runs, e-mail addresses and anything over 1000 characters.';

CREATE OR REPLACE FUNCTION banner_evidence.reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION 'banner_evidence.% is append-only: % is not allowed (record a new row instead)', TG_TABLE_NAME, TG_OP
		USING ERRCODE = 'integrity_constraint_violation';
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- Sources: one row per captured page / report. Never the page itself.
-- ---------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS banner_evidence.evidence_source (
	source_id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	source_kind         text NOT NULL CHECK (source_kind IN
	                      ('BANNER_LIVE', 'USER_SCREENSHOT', 'USER_CLARIFICATION', 'TEAM_CONFIRMATION', 'DEPARTMENT_PAGE')),
	page_kind           text NOT NULL CHECK (page_kind IN
	                      ('LINKED_SECTIONS', 'SECTION_DETAILS', 'SEARCH_RESULTS', 'COURSE_LISTING', 'OTHER')),
	term_code           text NOT NULL CHECK (term_code ~ '^[0-9]{6}$'),
	subject_crn         text CHECK (subject_crn ~ '^[0-9]{5}$'),
	captured_on         date NOT NULL,
	captured_at         timestamptz,
	captured_by_role    text NOT NULL CHECK (captured_by_role IN ('USER', 'CLAUDE_BROWSER', 'TEAM_MEMBER', 'SCRIPT')),
	inspected_by_claude boolean NOT NULL,
	-- Host and path only. No query string, fragment, ';' parameter, or anything that looks like a credential.
	source_host         text CHECK (source_host ~ '^([a-z0-9-]+\.)*lehigh\.edu$'),
	source_path         text CHECK (source_path ~ '^/[A-Za-z0-9._~/%-]{0,200}$' AND source_path !~* '(session|token|auth|cookie)'),
	-- A sanitized text extract kept next to the repo (never raw HTML).
	evidence_file       text CHECK (evidence_file ~ '^data/banner-evidence/captures/[A-Za-z0-9._/-]+$' AND evidence_file !~ '\.\.'),
	-- SHA-256 of the sanitized extract, or of the transcribed report text when no file exists.
	content_sha256      text NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
	sanitization_note   text NOT NULL CHECK (length(sanitization_note) > 0 AND banner_evidence.is_safe_text(sanitization_note)),
	provenance_note     text NOT NULL CHECK (length(provenance_note) > 0 AND banner_evidence.is_safe_text(provenance_note)),
	recorded_at         timestamptz NOT NULL DEFAULT now(),
	UNIQUE (source_id, term_code),
	CONSTRAINT source_kind_rules CHECK (CASE source_kind
		WHEN 'BANNER_LIVE' THEN captured_by_role = 'CLAUDE_BROWSER' AND inspected_by_claude
			AND source_host IS NOT NULL AND source_path IS NOT NULL AND captured_at IS NOT NULL AND evidence_file IS NOT NULL
		WHEN 'USER_SCREENSHOT' THEN captured_by_role = 'USER'
		WHEN 'USER_CLARIFICATION' THEN captured_by_role = 'USER'
		WHEN 'TEAM_CONFIRMATION' THEN captured_by_role = 'TEAM_MEMBER'
		WHEN 'DEPARTMENT_PAGE' THEN captured_by_role IN ('SCRIPT', 'USER', 'CLAUDE_BROWSER') AND inspected_by_claude
		ELSE false END)
);
-- The same capture is one row: re-importing it changes nothing; different content is a different row.
CREATE UNIQUE INDEX IF NOT EXISTS evidence_source_natural_key
	ON banner_evidence.evidence_source (source_kind, page_kind, term_code, COALESCE(subject_crn, ''), content_sha256);
CREATE INDEX IF NOT EXISTS evidence_source_term_crn ON banner_evidence.evidence_source (term_code, subject_crn);

-- ---------------------------------------------------------------------------------------------------------
-- Observations: what a source showed. No seats, enrollment counts, instructors or student data.
-- ---------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS banner_evidence.section_observation (
	source_id               uuid NOT NULL,
	term_code               text NOT NULL,
	crn                     text NOT NULL CHECK (crn ~ '^[0-9]{5}$'),
	course_id               text CHECK (course_id ~ '^[A-Z]{2,4}-[0-9]{3}[A-Z]?$'),
	section_code            text CHECK (section_code ~ '^[0-9A-Z]{1,4}$'),
	schedule_type           text CHECK (length(schedule_type) <= 60 AND banner_evidence.is_safe_text(schedule_type)),
	-- The text exactly as displayed ("0", "3.000"). Whether it equals academic credit is a claim, not a fact.
	credit_hours_text       text CHECK (credit_hours_text ~ '^[0-9]{1,2}(\.[0-9]{1,3})?( ?(-|to) ?[0-9]{1,2}(\.[0-9]{1,3})?)?$'),
	campus_text             text CHECK (length(campus_text) <= 60 AND banner_evidence.is_safe_text(campus_text)),
	-- Did the source show this lecture's Linked Sections? Absent rows alone cannot tell "none" from "not looked".
	linked_sections_outcome text NOT NULL DEFAULT 'NOT_CHECKED' CHECK (linked_sections_outcome IN ('NOT_CHECKED', 'LISTED', 'NONE_SHOWN')),
	PRIMARY KEY (source_id, crn),
	FOREIGN KEY (source_id, term_code) REFERENCES banner_evidence.evidence_source (source_id, term_code)
);
CREATE INDEX IF NOT EXISTS section_observation_term_crn ON banner_evidence.section_observation (term_code, crn);

CREATE TABLE IF NOT EXISTS banner_evidence.meeting_observation (
	source_id     uuid NOT NULL,
	crn           text NOT NULL,
	seq           smallint NOT NULL CHECK (seq > 0),
	days_iso      smallint[] NOT NULL CHECK (cardinality(days_iso) > 0 AND days_iso <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::smallint[]),
	start_time    time NOT NULL,
	end_time      time NOT NULL,
	location_text text CHECK (length(location_text) <= 120 AND banner_evidence.is_safe_text(location_text)),
	PRIMARY KEY (source_id, crn, seq),
	FOREIGN KEY (source_id, crn) REFERENCES banner_evidence.section_observation (source_id, crn),
	CHECK (end_time > start_time)
);

CREATE TABLE IF NOT EXISTS banner_evidence.link_observation (
	source_id   uuid NOT NULL,
	lecture_crn text NOT NULL,
	linked_crn  text NOT NULL,
	link_label  text NOT NULL CHECK (length(link_label) BETWEEN 1 AND 60 AND banner_evidence.is_safe_text(link_label)),
	PRIMARY KEY (source_id, lecture_crn, linked_crn),
	FOREIGN KEY (source_id, lecture_crn) REFERENCES banner_evidence.section_observation (source_id, crn),
	FOREIGN KEY (source_id, linked_crn) REFERENCES banner_evidence.section_observation (source_id, crn),
	CHECK (lecture_crn <> linked_crn)
);
CREATE INDEX IF NOT EXISTS link_observation_linked ON banner_evidence.link_observation (linked_crn);

CREATE TABLE IF NOT EXISTS banner_evidence.note_observation (
	note_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	source_id uuid NOT NULL REFERENCES banner_evidence.evidence_source (source_id),
	crn       text CHECK (crn ~ '^[0-9]{5}$'),
	note_kind text NOT NULL CHECK (note_kind IN ('DISPLAYED_HOURS_REPORT', 'REGISTRATION_RESTRICTION', 'COREQUISITE_TEXT',
	            'PREREQUISITE_TEXT', 'LINK_RULE_TEXT', 'OTHER')),
	note_text text NOT NULL CHECK (length(note_text) > 0 AND banner_evidence.is_safe_text(note_text)),
	FOREIGN KEY (source_id, crn) REFERENCES banner_evidence.section_observation (source_id, crn)
);
CREATE UNIQUE INDEX IF NOT EXISTS note_observation_natural_key
	ON banner_evidence.note_observation (source_id, COALESCE(crn, ''), note_kind, md5(note_text));

-- ---------------------------------------------------------------------------------------------------------
-- Claims: what we assert, one row per (subject, value). Conflicting values coexist; UNKNOWN is a NULL value.
-- ---------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS banner_evidence.claim_type_policy (
	claim_type        text PRIMARY KEY CHECK (claim_type IN
	                    ('LECTURE_LAB_LINK', 'REQUIRED_LAB_COUNT', 'LECTURE_CREDITS', 'LAB_CREDITS', 'STANDALONE_STATUS')),
	-- Can a live Banner capture alone reach VERIFIED_BANNER for this claim type?
	banner_can_verify boolean NOT NULL,
	rationale         text NOT NULL CHECK (banner_evidence.is_safe_text(rationale))
);
INSERT INTO banner_evidence.claim_type_policy (claim_type, banner_can_verify, rationale) VALUES
	('LECTURE_LAB_LINK',   true,  'Banner''s Linked Sections page states which sections are linked.'),
	('REQUIRED_LAB_COUNT', false, 'Banner lists candidate labs but never states how many must be taken.'),
	('LECTURE_CREDITS',    false, 'Banner shows an Hours value; that it equals the course credit is unconfirmed (calibrate on CSE 007).'),
	('LAB_CREDITS',        false, 'Banner shows an Hours value; that it equals academic credit is unconfirmed (calibrate on CSE 007).'),
	('STANDALONE_STATUS',  false, 'An empty or missing Linked Sections list does not prove a lecture has no labs.')
ON CONFLICT (claim_type) DO NOTHING;

CREATE TABLE IF NOT EXISTS banner_evidence.claim (
	claim_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code      text NOT NULL CHECK (term_code ~ '^[0-9]{6}$'),
	claim_type     text NOT NULL REFERENCES banner_evidence.claim_type_policy (claim_type),
	course_id      text NOT NULL CHECK (course_id ~ '^[A-Z]{2,4}-[0-9]{3}[A-Z]?$'),
	-- The section the claim is about: the lecture for links, counts and lecture credits; the lab for lab credits.
	crn            text NOT NULL CHECK (crn ~ '^[0-9]{5}$'),
	-- Links only: the lab. NULL for every other type.
	other_crn      text CHECK (other_crn ~ '^[0-9]{5}$'),
	-- NULL means UNKNOWN (a recorded state, not a missing row).
	asserted_value text CHECK (length(asserted_value) <= 40 AND banner_evidence.is_safe_text(asserted_value)),
	recorded_at    timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT claim_shape CHECK (CASE claim_type
		WHEN 'LECTURE_LAB_LINK'   THEN other_crn IS NOT NULL AND crn <> other_crn AND asserted_value IS NOT NULL AND asserted_value IN ('LINKED', 'NOT_LINKED')
		WHEN 'REQUIRED_LAB_COUNT' THEN other_crn IS NULL AND (asserted_value IS NULL OR asserted_value ~ '^[0-9]{1,2}$')
		WHEN 'LECTURE_CREDITS'    THEN other_crn IS NULL AND (asserted_value IS NULL OR asserted_value ~ '^[0-9]{1,2}(\.[0-9]{1,3})?$')
		WHEN 'LAB_CREDITS'        THEN other_crn IS NULL AND (asserted_value IS NULL OR asserted_value ~ '^[0-9]{1,2}(\.[0-9]{1,3})?$')
		WHEN 'STANDALONE_STATUS'  THEN other_crn IS NULL AND (asserted_value IS NULL OR asserted_value IN ('STANDALONE', 'HAS_LABS'))
		ELSE false END),
	CONSTRAINT claim_natural_key UNIQUE NULLS NOT DISTINCT (term_code, claim_type, course_id, crn, other_crn, asserted_value)
);
CREATE INDEX IF NOT EXISTS claim_subject ON banner_evidence.claim (term_code, claim_type, course_id, crn);

CREATE TABLE IF NOT EXISTS banner_evidence.claim_evidence (
	claim_id    bigint NOT NULL REFERENCES banner_evidence.claim (claim_id),
	source_id   uuid NOT NULL REFERENCES banner_evidence.evidence_source (source_id),
	stance      text NOT NULL CHECK (stance IN ('SUPPORTS', 'CONTRADICTS')),
	note        text CHECK (banner_evidence.is_safe_text(note)),
	recorded_at timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (claim_id, source_id)
);
CREATE INDEX IF NOT EXISTS claim_evidence_source ON banner_evidence.claim_evidence (source_id);

-- Evidence must actually be about its claim, and an UNKNOWN claim carries no evidence.
CREATE OR REPLACE FUNCTION banner_evidence.check_claim_evidence() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
	c banner_evidence.claim;
	s banner_evidence.evidence_source;
	has_link boolean;
	checked boolean;
BEGIN
	SELECT * INTO c FROM banner_evidence.claim WHERE claim_id = NEW.claim_id;
	SELECT * INTO s FROM banner_evidence.evidence_source WHERE source_id = NEW.source_id;
	IF c.claim_id IS NULL OR s.source_id IS NULL THEN
		RETURN NEW; -- the foreign keys report the missing row
	END IF;
	IF c.asserted_value IS NULL THEN
		RAISE EXCEPTION 'a claim with an UNKNOWN value cannot carry evidence' USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	IF c.term_code <> s.term_code THEN
		RAISE EXCEPTION 'evidence term % does not match claim term %', s.term_code, c.term_code USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM banner_evidence.section_observation o WHERE o.source_id = NEW.source_id AND o.crn = c.crn) THEN
		RAISE EXCEPTION 'the source has no observation of section %', c.crn USING ERRCODE = 'integrity_constraint_violation';
	END IF;
	IF c.claim_type = 'LECTURE_LAB_LINK' THEN
		has_link := EXISTS (SELECT 1 FROM banner_evidence.link_observation l
			WHERE l.source_id = NEW.source_id AND l.lecture_crn = c.crn AND l.linked_crn = c.other_crn);
		checked := EXISTS (SELECT 1 FROM banner_evidence.section_observation o
			WHERE o.source_id = NEW.source_id AND o.crn = c.crn AND o.linked_sections_outcome IN ('LISTED', 'NONE_SHOWN'));
		IF (c.asserted_value = 'LINKED') = (NEW.stance = 'SUPPORTS') THEN
			IF NOT has_link THEN
				RAISE EXCEPTION 'the source shows no link %->% to back this evidence', c.crn, c.other_crn USING ERRCODE = 'integrity_constraint_violation';
			END IF;
		ELSIF has_link OR NOT checked THEN
			RAISE EXCEPTION 'the source must show the lecture''s linked sections without %->% to back this evidence', c.crn, c.other_crn USING ERRCODE = 'integrity_constraint_violation';
		END IF;
	END IF;
	RETURN NEW;
END $$;
CREATE OR REPLACE TRIGGER claim_evidence_relevant BEFORE INSERT ON banner_evidence.claim_evidence
	FOR EACH ROW EXECUTE FUNCTION banner_evidence.check_claim_evidence();

-- A person's decision. Automatic collection never writes here.
CREATE TABLE IF NOT EXISTS banner_evidence.claim_review (
	review_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	claim_id   bigint NOT NULL REFERENCES banner_evidence.claim (claim_id),
	reviewer   text NOT NULL CHECK (length(reviewer) BETWEEN 1 AND 60 AND banner_evidence.is_safe_text(reviewer)),
	decision   text NOT NULL CHECK (decision IN ('CONFIRMED', 'REJECTED', 'NEEDS_MORE_EVIDENCE')),
	rationale  text NOT NULL CHECK (length(rationale) > 0 AND banner_evidence.is_safe_text(rationale)),
	decided_by text NOT NULL DEFAULT session_user,
	decided_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS claim_review_latest ON banner_evidence.claim_review (claim_id, review_id DESC);

-- Append-only enforcement.
DO $$
DECLARE
	t text;
BEGIN
	FOREACH t IN ARRAY ARRAY['evidence_source', 'section_observation', 'meeting_observation', 'link_observation',
			'note_observation', 'claim', 'claim_evidence', 'claim_review'] LOOP
		EXECUTE format('CREATE OR REPLACE TRIGGER %I BEFORE UPDATE OR DELETE ON banner_evidence.%I FOR EACH ROW EXECUTE FUNCTION banner_evidence.reject_change()',
			t || '_append_only', t);
		EXECUTE format('CREATE OR REPLACE TRIGGER %I BEFORE TRUNCATE ON banner_evidence.%I FOR EACH STATEMENT EXECUTE FUNCTION banner_evidence.reject_change()',
			t || '_no_truncate', t);
	END LOOP;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- Derived status (never stored). Highest supporting source wins; any contradiction wins over support.
--   VERIFIED_BANNER  a live Banner capture supports it (only for claim types Banner can state)
--   TEAM_CONFIRMED   a team confirmation or the user's own clarification supports it
--   USER_REPORTED    only a user's screenshot report supports it
--   PROVISIONAL      only a department page, or Banner for a type Banner cannot settle, supports it
--   CONTRADICTED     some evidence contradicts it
--   UNKNOWN          NULL value, or no supporting evidence
-- ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE VIEW banner_evidence.claim_status AS
WITH evidence AS (
	SELECT ce.claim_id,
		count(*) AS evidence_rows,
		bool_or(ce.stance = 'CONTRADICTS') AS contradicted,
		max(CASE WHEN ce.stance = 'SUPPORTS' THEN
			CASE WHEN s.source_kind = 'BANNER_LIVE' THEN CASE WHEN p.banner_can_verify THEN 5 ELSE 2 END
				WHEN s.source_kind IN ('TEAM_CONFIRMATION', 'USER_CLARIFICATION') THEN 4
				WHEN s.source_kind = 'USER_SCREENSHOT' THEN 3
				WHEN s.source_kind = 'DEPARTMENT_PAGE' THEN 2
			END
		END) AS best_rank
	FROM banner_evidence.claim_evidence ce
	JOIN banner_evidence.evidence_source s ON s.source_id = ce.source_id
	JOIN banner_evidence.claim c ON c.claim_id = ce.claim_id
	JOIN banner_evidence.claim_type_policy p ON p.claim_type = c.claim_type
	GROUP BY ce.claim_id
), latest_review AS (
	SELECT DISTINCT ON (claim_id) claim_id, decision, reviewer, decided_at
	FROM banner_evidence.claim_review
	ORDER BY claim_id, review_id DESC
), base AS (
	SELECT c.claim_id, c.term_code, c.claim_type, c.course_id, c.crn, c.other_crn, c.asserted_value,
		COALESCE(e.evidence_rows, 0) AS evidence_rows,
		COALESCE(e.contradicted, false) AS contradicted,
		e.best_rank,
		COALESCE(r.decision, 'UNREVIEWED') AS review_state,
		r.reviewer, r.decided_at AS reviewed_at
	FROM banner_evidence.claim c
	LEFT JOIN evidence e ON e.claim_id = c.claim_id
	LEFT JOIN latest_review r ON r.claim_id = c.claim_id
), flagged AS (
	SELECT b.*,
		CASE WHEN b.asserted_value IS NULL THEN 'UNKNOWN'
			WHEN b.contradicted THEN 'CONTRADICTED'
			WHEN b.best_rank = 5 THEN 'VERIFIED_BANNER'
			WHEN b.best_rank = 4 THEN 'TEAM_CONFIRMED'
			WHEN b.best_rank = 3 THEN 'USER_REPORTED'
			WHEN b.best_rank = 2 THEN 'PROVISIONAL'
			ELSE 'UNKNOWN' END AS evidence_status,
		-- Another live (not rejected) claim about the same subject with a different value.
		EXISTS (SELECT 1 FROM banner_evidence.claim o
			LEFT JOIN latest_review lo ON lo.claim_id = o.claim_id
			WHERE o.claim_id <> b.claim_id AND o.term_code = b.term_code AND o.claim_type = b.claim_type
				AND o.course_id = b.course_id AND o.crn = b.crn AND o.other_crn IS NOT DISTINCT FROM b.other_crn
				AND o.asserted_value IS NOT NULL AND b.asserted_value IS NOT NULL
				AND o.asserted_value <> b.asserted_value AND COALESCE(lo.decision, '') <> 'REJECTED') AS in_conflict,
		-- An UNKNOWN placeholder that a claim with a value has since replaced.
		(b.asserted_value IS NULL AND EXISTS (SELECT 1 FROM banner_evidence.claim o
			LEFT JOIN latest_review lo ON lo.claim_id = o.claim_id
			WHERE o.term_code = b.term_code AND o.claim_type = b.claim_type AND o.course_id = b.course_id
				AND o.crn = b.crn AND o.other_crn IS NOT DISTINCT FROM b.other_crn
				AND o.asserted_value IS NOT NULL AND COALESCE(lo.decision, '') <> 'REJECTED')) AS superseded
	FROM base b
)
SELECT claim_id, term_code, claim_type, course_id, crn, other_crn, asserted_value,
	evidence_rows, evidence_status, review_state, reviewer, reviewed_at, in_conflict, contradicted, superseded,
	CASE WHEN superseded THEN 'SUPERSEDED'
		WHEN review_state = 'REJECTED' THEN 'REJECTED'
		WHEN in_conflict OR contradicted THEN 'CONFLICT'
		WHEN asserted_value IS NULL THEN 'UNKNOWN_FACT'
		WHEN review_state = 'NEEDS_MORE_EVIDENCE' THEN 'NEEDS_MORE_EVIDENCE'
		WHEN review_state = 'UNREVIEWED' THEN 'AWAITING_REVIEW'
		ELSE 'REVIEWED' END AS review_flag,
	-- Eligible to be PROPOSED for promotion: Banner or team evidence, a person's CONFIRMED decision, no conflict.
	-- This is not "schedule-ready"; the catalog_api readiness rules still decide that.
	(asserted_value IS NOT NULL AND evidence_status IN ('VERIFIED_BANNER', 'TEAM_CONFIRMED')
		AND review_state = 'CONFIRMED' AND NOT in_conflict AND NOT contradicted AND NOT superseded) AS promotion_eligible
FROM flagged;
COMMENT ON VIEW banner_evidence.claim_status IS 'Derived status of every claim. promotion_eligible means "may be proposed", never "schedule-ready".';

CREATE OR REPLACE VIEW banner_evidence.review_queue AS
SELECT * FROM banner_evidence.claim_status
WHERE review_flag IN ('CONFLICT', 'AWAITING_REVIEW', 'NEEDS_MORE_EVIDENCE')
ORDER BY CASE review_flag WHEN 'CONFLICT' THEN 0 WHEN 'NEEDS_MORE_EVIDENCE' THEN 1 ELSE 2 END, claim_id;

CREATE OR REPLACE VIEW banner_evidence.course_evidence_summary AS
SELECT term_code, course_id,
	count(*) FILTER (WHERE review_flag <> 'SUPERSEDED') AS live_claims,
	count(*) FILTER (WHERE evidence_status = 'VERIFIED_BANNER') AS verified_banner,
	count(*) FILTER (WHERE evidence_status = 'TEAM_CONFIRMED') AS team_confirmed,
	count(*) FILTER (WHERE evidence_status = 'USER_REPORTED') AS user_reported,
	count(*) FILTER (WHERE evidence_status = 'PROVISIONAL') AS provisional,
	count(*) FILTER (WHERE review_flag = 'UNKNOWN_FACT') AS unknown_facts,
	count(*) FILTER (WHERE review_flag = 'CONFLICT') AS conflicts,
	count(*) FILTER (WHERE review_flag = 'AWAITING_REVIEW') AS awaiting_review,
	count(*) FILTER (WHERE promotion_eligible) AS promotion_eligible_claims
FROM banner_evidence.claim_status
GROUP BY term_code, course_id;
COMMENT ON VIEW banner_evidence.course_evidence_summary IS 'Counts per course. Deliberately has no schedule-ready column.';
