-- catalog_research v1: the persistent, complete store of the researched term catalog (offerings, sections, meetings, lecture/lab
-- links, requirements, restrictions, credit exclusions, aliases, administrative types and policy decisions), whether or not
-- an offering has been approved for serving.
--
-- THREE QUESTIONS, KEPT APART
--   1. Is it STORED?            -> a row in catalog_research (this schema). Researched data is stored with its evidence level;
--                                  missing registration certification never prevents storing it.
--   2. Is it APPROVED TO SERVE? -> catalog_promoted (approvals, promotion) and catalog_live (what the backend reads). Nothing in
--                                  this schema serves anything, approves anything or changes a serving gate.
--   3. Can a student REGISTER?  -> only the university decides. Nothing here says so; every row is "proposed scheduling data".
--
-- RULES BUILT IN
--   * Nothing is invented. A requirement is UNKNOWN (no source), NONE_LISTED (Banner lists nothing, and the designated reviewer decided to
--     treat that as none; user-supplied, NOT Registrar certification), USER_CONFIRMED_NONE, TEXT_ONLY (stated, but not turned into a rule)
--     or RULE_DEFINED.
--   * Every Banner-sourced row is cross-checked against banner_evidence at import time and on demand (evidence_mismatch). A
--     mismatch aborts the import; it is never "corrected" here.
--   * User-supplied facts (exactly one lab, lab credits 0, standalone) are labelled USER_CLARIFICATION. A person's policy decision
--     is TEAM_POLICY and is append-only. Neither is Registrar certification (CHECK constraints).
--   * This schema READS banner_evidence, cse007_snapshot, catalog_api, catalog_promoted and catalog_live. It writes none of them,
--     and none of them reads this schema (verify_structure.sql).
--
-- Rerunnable (IF NOT EXISTS / OR REPLACE). Remove: DROP SCHEMA catalog_research CASCADE;

DO $$
BEGIN
	IF to_regclass('catalog_promoted.promotion_readiness') IS NULL OR to_regclass('catalog_live.course_offering') IS NULL
		OR to_regclass('catalog_api.course_offering') IS NULL OR to_regclass('catalog_promoted.evidence_section') IS NULL THEN
		RAISE EXCEPTION 'cse007_snapshot, catalog_api, banner_evidence, catalog_promoted and catalog_live must be installed first';
	END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS catalog_research;
COMMENT ON SCHEMA catalog_research IS 'Complete stored term catalog with evidence levels. Storage only: serving and approval stay in catalog_promoted / catalog_live.';

CREATE OR REPLACE FUNCTION catalog_research.reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION 'catalog_research.% is append-only: % is not allowed (record a new row instead)', TG_TABLE_NAME, TG_OP
		USING ERRCODE = 'integrity_constraint_violation';
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS catalog_research.import_run (
	run_id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code      text NOT NULL,
	input_sha256   text NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
	input_label    text NOT NULL CHECK (length(input_label) BETWEEN 3 AND 300),
	offering_count integer NOT NULL CHECK (offering_count > 0),
	section_count  integer NOT NULL CHECK (section_count > 0),
	imported_at    timestamptz NOT NULL DEFAULT now(),
	imported_by    text NOT NULL DEFAULT session_user
);
COMMENT ON TABLE catalog_research.import_run IS 'One row per import: what was loaded (hash of the normalized input), when and by which database user.';

CREATE TABLE IF NOT EXISTS catalog_research.offering (
	term_code            text NOT NULL,
	offering_id          text NOT NULL,                 -- CSE-017, or CSE-398-042 for a special topic (one identity per topic)
	course_id            text NOT NULL,                 -- the catalog course (CSE-398): what Banner evidence calls it
	subject              text NOT NULL,
	course_number        text NOT NULL,
	topic                text,
	title                text NOT NULL CHECK (length(btrim(title)) > 0),
	credits              smallint NOT NULL CHECK (credits >= 0),
	administrative_type  text NOT NULL,                 -- Banner schedule type of the main section, kept as metadata
	data_source          text NOT NULL CHECK (data_source IN ('BANNER_EVIDENCE', 'SNAPSHOT')),
	components_status    text NOT NULL CHECK (components_status IN ('COMPONENTS_UNVERIFIED', 'LABS_USER_SUPPLIED', 'STANDALONE_USER_SUPPLIED')),
	required_lab_count   smallint CHECK (required_lab_count >= 0),
	cross_listing_status text NOT NULL CHECK (cross_listing_status IN ('UNVERIFIED', 'NOT_CROSS_LISTED_USER_CONFIRMED')),
	identity_status      text NOT NULL CHECK (identity_status IN ('SINGLE', 'TOPIC_IDENTITY_UNDECIDED')),
	run_id               bigint NOT NULL REFERENCES catalog_research.import_run,
	PRIMARY KEY (term_code, offering_id),
	CHECK (course_id = subject || '-' || course_number),
	-- a plain course has offering_id = course_id; a special topic carries its section code, and only a topic does
	CHECK ((topic IS NULL AND offering_id = course_id) OR (topic IS NOT NULL AND offering_id LIKE course_id || '-%')),
	CHECK ((topic IS NULL) = (identity_status = 'SINGLE')),
	-- "exactly one lab" is only ever stored with its user-supplied label; unverified components carry no count at all
	CHECK ((components_status = 'LABS_USER_SUPPLIED' AND required_lab_count IS NOT DISTINCT FROM 1)
		OR (components_status = 'STANDALONE_USER_SUPPLIED' AND required_lab_count IS NOT DISTINCT FROM 0)
		OR (components_status = 'COMPONENTS_UNVERIFIED' AND required_lab_count IS NULL))
);
COMMENT ON TABLE catalog_research.offering IS 'One row per offering. credits = the published total on the lecture; labs carry 0. identity_status marks special topics whose course-id scheme is not yet approved.';

CREATE TABLE IF NOT EXISTS catalog_research.section (
	term_code      text NOT NULL,
	crn            text NOT NULL CHECK (crn ~ '^[0-9]{5}$'),
	offering_id    text NOT NULL,
	section_code   text NOT NULL,
	component_type text NOT NULL CHECK (component_type IN ('LECTURE', 'LAB')),
	credits        smallint NOT NULL CHECK (credits >= 0),
	lecture_crn    text,
	schedule_type  text NOT NULL,                       -- Banner's schedule type for this section
	lecture_component text GENERATED ALWAYS AS (CASE WHEN lecture_crn IS NULL THEN NULL ELSE 'LECTURE' END) STORED,
	PRIMARY KEY (term_code, crn),
	FOREIGN KEY (term_code, offering_id) REFERENCES catalog_research.offering,
	UNIQUE (term_code, offering_id, crn, component_type),
	UNIQUE (term_code, offering_id, section_code),
	-- a lab points at a LECTURE of the same offering (a NULL lecture_crn skips this foreign key)
	FOREIGN KEY (term_code, offering_id, lecture_crn, lecture_component)
		REFERENCES catalog_research.section (term_code, offering_id, crn, component_type),
	CHECK ((component_type = 'LECTURE') = (lecture_crn IS NULL)),
	CHECK (component_type = 'LECTURE' OR credits = 0)    -- a lab adds zero incremental credits
);

CREATE TABLE IF NOT EXISTS catalog_research.section_meeting (
	term_code   text NOT NULL,
	crn         text NOT NULL,
	day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),   -- 1 = Monday, as in catalog_api
	start_time  time NOT NULL,
	end_time    time NOT NULL,
	location    text NOT NULL,
	PRIMARY KEY (term_code, crn, day_of_week, start_time, end_time),
	FOREIGN KEY (term_code, crn) REFERENCES catalog_research.section,
	CHECK (end_time > start_time)
);

-- A mandatory meeting with no published time (CSE 367 / 467). It cannot be placed in a timetable; it is a warning.
CREATE TABLE IF NOT EXISTS catalog_research.section_arranged_meeting (
	term_code   text NOT NULL,
	crn         text NOT NULL,
	description text NOT NULL CHECK (length(btrim(description)) > 0),
	PRIMARY KEY (term_code, crn),
	FOREIGN KEY (term_code, crn) REFERENCES catalog_research.section
);

CREATE TABLE IF NOT EXISTS catalog_research.requirement_status (
	term_code        text NOT NULL,
	offering_id      text NOT NULL,
	requirement_type text NOT NULL CHECK (requirement_type IN ('PREREQUISITE', 'COREQUISITE', 'ENROLLMENT_RESTRICTION')),
	status           text NOT NULL CHECK (status IN ('UNKNOWN', 'USER_CONFIRMED_NONE', 'NONE_LISTED', 'TEXT_ONLY', 'RULE_DEFINED')),
	evidence_level   text NOT NULL CHECK (evidence_level IN ('BANNER', 'RESEARCH_WORKBOOK', 'USER_CLARIFICATION', 'NONE')),
	source_text      text,
	PRIMARY KEY (term_code, offering_id, requirement_type),
	FOREIGN KEY (term_code, offering_id) REFERENCES catalog_research.offering,
	-- a rule or text always has its words; silence or "unknown" never pretends to
	CHECK ((status IN ('TEXT_ONLY', 'RULE_DEFINED')) = (source_text IS NOT NULL AND length(btrim(source_text)) > 0)),
	CHECK ((status = 'UNKNOWN' AND evidence_level = 'NONE')
		OR (status = 'NONE_LISTED' AND evidence_level = 'USER_CLARIFICATION')
		OR (status = 'USER_CONFIRMED_NONE' AND evidence_level = 'USER_CLARIFICATION')
		OR (status IN ('TEXT_ONLY', 'RULE_DEFINED') AND evidence_level IN ('BANNER', 'RESEARCH_WORKBOOK', 'USER_CLARIFICATION')))
);
COMMENT ON TABLE catalog_research.requirement_status IS 'For each offering and requirement type, what is known. NONE_LISTED means Banner lists nothing and the designated reviewer treats that as none (user-supplied, not Registrar certification). Verbatim Banner text is kept when it exists.';

CREATE TABLE IF NOT EXISTS catalog_research.eligibility_rule (
	rule_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code      text NOT NULL,
	offering_id    text NOT NULL,
	rule_kind      text NOT NULL CHECK (rule_kind IN ('CLASS_STANDING', 'LEVEL', 'COLLEGE', 'MAJOR', 'DEPARTMENT_APPROVAL', 'PREREQUISITE', 'COREQUISITE')),
	polarity       text NOT NULL CHECK (polarity IN ('MUST', 'CANNOT', 'NEEDS', 'REQUIRES')),
	rule_values    text[] NOT NULL DEFAULT '{}',
	expression     jsonb,                              -- {"op":"AND"|"OR","args":[...]} or {"course":"CSE-017"}; only when the text parsed exactly
	                                                   -- (evidence_level RESEARCH_WORKBOOK = stated in the research workbook, which cites the Banner / catalog text)
	source_text    text NOT NULL CHECK (length(btrim(source_text)) > 0),
	evidence_level text NOT NULL CHECK (evidence_level IN ('BANNER', 'RESEARCH_WORKBOOK', 'USER_CLARIFICATION')),
	parse_status   text NOT NULL CHECK (parse_status IN ('STRUCTURED', 'TEXT_ONLY')),
	FOREIGN KEY (term_code, offering_id) REFERENCES catalog_research.offering,
	CHECK (rule_kind <> 'CLASS_STANDING' OR rule_values <@ ARRAY['FR', 'SO', 'JR', 'SR', 'GR']),
	CHECK (rule_kind <> 'LEVEL' OR rule_values <@ ARRAY['UG', 'GR']),
	CHECK ((rule_kind IN ('PREREQUISITE', 'COREQUISITE')) = (polarity = 'REQUIRES')),
	-- a prerequisite is STRUCTURED only with a parsed expression; any other rule is STRUCTURED only with its values
	CHECK ((rule_kind IN ('PREREQUISITE', 'COREQUISITE') AND (parse_status = 'STRUCTURED') = (expression IS NOT NULL))
		OR (rule_kind NOT IN ('PREREQUISITE', 'COREQUISITE') AND expression IS NULL AND (parse_status = 'STRUCTURED') = (cardinality(rule_values) > 0)))
);
CREATE INDEX IF NOT EXISTS eligibility_rule_offering_idx ON catalog_research.eligibility_rule (term_code, offering_id);
COMMENT ON TABLE catalog_research.eligibility_rule IS 'Stored restrictions and prerequisites. Storing a rule does not enforce it: the application enforces none today (catalog_promoted.capability).';

CREATE TABLE IF NOT EXISTS catalog_research.credit_exclusion (
	exclusion_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code          text NOT NULL,
	offering_id        text NOT NULL,
	excluded_course_id text,                           -- set when the partner is a single course id
	excluded_text      text NOT NULL,                  -- the partner(s) as the source states them
	exclusion_kind     text NOT NULL CHECK (exclusion_kind IN ('NO_DOUBLE_CREDIT', 'CREDIT_AND_ENROLLMENT', 'SOURCES_CONFLICT')),
	source_text        text NOT NULL CHECK (length(btrim(source_text)) > 0),
	evidence_level     text NOT NULL CHECK (evidence_level IN ('BANNER', 'RESEARCH_WORKBOOK', 'USER_CLARIFICATION')),
	FOREIGN KEY (term_code, offering_id) REFERENCES catalog_research.offering
);
COMMENT ON TABLE catalog_research.credit_exclusion IS 'One row per statement an offering makes. Two sources that disagree stay as two rows (SOURCES_CONFLICT is kept, not resolved).';

CREATE TABLE IF NOT EXISTS catalog_research.course_alias (
	term_code      text NOT NULL,
	offering_id    text NOT NULL,
	alias_subject  text NOT NULL,
	alias_number   text NOT NULL,
	alias_status   text NOT NULL CHECK (alias_status IN ('CATALOG_ALIAS_UNVERIFIED')),
	PRIMARY KEY (term_code, offering_id, alias_subject, alias_number),
	FOREIGN KEY (term_code, offering_id) REFERENCES catalog_research.offering
);
COMMENT ON TABLE catalog_research.course_alias IS 'A catalog alias (another department''s number for the same course). It is not a section cross-list and carries no CRN.';

-- A person's decision about how an offering is TREATED in proposed schedules. Append-only; one writer function.
CREATE TABLE IF NOT EXISTS catalog_research.tier_policy_decision (
	decision_id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code             text NOT NULL,
	crn                   text NOT NULL,
	offering_id           text NOT NULL,
	decision_type         text NOT NULL CHECK (decision_type IN ('ALLOW_SECTION_TYPE_AS_CONDITIONAL')),
	decision              text NOT NULL CHECK (decision IN ('ALLOW', 'REVOKE')),
	observed_value        text NOT NULL,               -- the Banner schedule type the decision is about
	evidence_level        text NOT NULL CHECK (evidence_level = 'TEAM_POLICY'),
	decided_by_role       text NOT NULL CHECK (decided_by_role = 'DEMO_ACADEMIC_EVIDENCE_REVIEWER'),
	decided_on            date NOT NULL CHECK (decided_on <= CURRENT_DATE),
	rationale             text NOT NULL CHECK (length(rationale) BETWEEN 10 AND 1000),
	limitations           text NOT NULL CHECK (length(limitations) BETWEEN 10 AND 1000),
	source_ref            text NOT NULL CHECK (length(btrim(source_ref)) > 0),
	registrar_certified   boolean NOT NULL CHECK (NOT registrar_certified),
	guarantees_registration boolean NOT NULL CHECK (NOT guarantees_registration),
	supersedes_decision_id bigint REFERENCES catalog_research.tier_policy_decision,
	recorded_at           timestamptz NOT NULL DEFAULT now(),
	recorded_by           text NOT NULL DEFAULT session_user
);
CREATE UNIQUE INDEX IF NOT EXISTS tier_policy_decision_chain_idx
	ON catalog_research.tier_policy_decision (supersedes_decision_id) WHERE supersedes_decision_id IS NOT NULL;   -- one linear chain, no forks
COMMENT ON TABLE catalog_research.tier_policy_decision IS 'TEAM_POLICY decisions (not Registrar certification, never a guarantee). Append-only; written only by record_tier_policy_decision.';

DO $$
DECLARE t text;
BEGIN
	FOREACH t IN ARRAY ARRAY['tier_policy_decision', 'import_run'] LOOP
		EXECUTE format('DROP TRIGGER IF EXISTS %I ON catalog_research.%I', t || '_append_only', t);
		EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON catalog_research.%I FOR EACH ROW EXECUTE FUNCTION catalog_research.reject_change()', t || '_append_only', t);
		EXECUTE format('DROP TRIGGER IF EXISTS %I ON catalog_research.%I', t || '_no_truncate', t);
		EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON catalog_research.%I FOR EACH STATEMENT EXECUTE FUNCTION catalog_research.reject_change()', t || '_no_truncate', t);
	END LOOP;
END $$;
