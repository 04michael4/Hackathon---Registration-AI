-- catalog_csb v1: the researched Computer Science and Business Honors (CSB) data, stored ADDITIVELY next to catalog_research.
--
-- WHY A SEPARATE SCHEMA
--   catalog_research's grid importer replaces every grid-staged offering of the term, its program importer clears the cross-list and exclusion
--   tables, and its CHECK constraints (data_source, catalog_status = PROGRAM_TABLE_ONLY, prerequisite_status = NOT_COLLECTED) cannot hold
--   Class-Details-inspected sections or catalog prerequisites. Loading CSB data through them would either delete or alter the team's existing
--   243 sections, or require changing constraints another person is working under. This schema follows the same conventions (term_code,
--   SUBJ-NNN ids, CRN keys, component types, evidence levels, a hashed import_run, verbatim-grid cross-check views, owner/reader roles) but
--   references NOTHING in catalog_research, so installing, re-importing or dropping it can never change a row there.
--
-- RULES BUILT IN
--   * Stored is not approved is not registrable. Nothing here approves, promotes or serves a course; the optional V2 views only READ
--     catalog_research and catalog_serving to say what already exists there.
--   * Nothing is invented. A requirement is NONE_LISTED (Banner printed "none available"), RULE_DEFINED (parsed exactly) or TEXT_ONLY (the
--     words are kept). A link between sections is CONFIRMED only when both sides' Linked Sections panels list each other.
--   * Sources that disagree stay side by side (prerequisite_comparison, source_conflict); nothing is resolved silently.
--   * Banner-derived rows (sections, instructors, links, restrictions) come from an authenticated session and are LOCAL-ONLY: their source row
--     says NOT_VERIFIED_LOCAL_ONLY, and the committed tests use synthetic data only. Seat and waitlist counts are never stored.
--
-- Rerunnable (IF NOT EXISTS / OR REPLACE). Remove it all: DROP SCHEMA catalog_csb CASCADE;   (nothing outside this schema depends on it)
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS catalog_csb;
COMMENT ON SCHEMA catalog_csb IS 'Researched CSB degree structure, catalog course identities and Spring 2027 Banner sections. Storage only; additive to catalog_research.';

CREATE OR REPLACE FUNCTION catalog_csb.reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION 'catalog_csb.% is append-only: % is not allowed (record a new row instead)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'integrity_constraint_violation';
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- Provenance
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS catalog_csb.import_run (
	run_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code    text NOT NULL CHECK (term_code ~ '^[0-9]{6}$'),
	input_sha256 text NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
	input_label  text NOT NULL CHECK (length(input_label) BETWEEN 3 AND 300),
	counts       jsonb NOT NULL,
	imported_at  timestamptz NOT NULL DEFAULT now(),
	imported_by  text NOT NULL DEFAULT session_user
);
COMMENT ON TABLE catalog_csb.import_run IS 'One row per import: the hash of the normalized input, row counts, when and by which database user. Append-only.';

CREATE TABLE IF NOT EXISTS catalog_csb.source (
	source_key            text PRIMARY KEY CHECK (source_key ~ '^[A-Z0-9_]{3,60}$'),
	level                 text NOT NULL CHECK (level IN ('PUBLIC_CATALOG', 'PROGRAM_WEBSITE', 'DEPARTMENT_WEBSITE', 'BANNER_AUTHENTICATED', 'SYNTHETIC_FIXTURE')),
	locator               text NOT NULL CHECK (length(btrim(locator)) > 0),
	observed_on           date NOT NULL,
	authority             text NOT NULL,
	redistribution_status text NOT NULL CHECK (redistribution_status IN ('PUBLIC', 'NOT_VERIFIED_LOCAL_ONLY', 'SYNTHETIC')),
	CHECK ((level = 'BANNER_AUTHENTICATED') = (redistribution_status = 'NOT_VERIFIED_LOCAL_ONLY')),
	CHECK ((level = 'SYNTHETIC_FIXTURE') = (redistribution_status = 'SYNTHETIC'))
);
COMMENT ON TABLE catalog_csb.source IS 'Where rows came from. Authenticated-Banner rows are local-only: redistribution is not verified.';

-- ---------------------------------------------------------------------------------------------------------
-- Catalog course identities (public 2026-27 catalog pages)
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS catalog_csb.catalog_course (
	course_id             text PRIMARY KEY,                    -- ACCT-151
	subject               text NOT NULL,
	course_number         text NOT NULL,
	title                 text,                                -- NULL only when no source gave one
	credits_text          text,                                -- as the catalog prints it ('3', '0,4' = lecture plus companion credit, '1-3')
	credits_min           numeric(5, 2) CHECK (credits_min >= 0),
	credits_max           numeric(5, 2) CHECK (credits_max >= credits_min),
	attributes            text[] NOT NULL DEFAULT '{}',        -- 'NS', 'NW', 'Q', ... exactly as printed
	prerequisite_text     text,
	prerequisite_status   text NOT NULL CHECK (prerequisite_status IN ('NOT_FOUND', 'NONE_LISTED', 'RULE_DEFINED', 'TEXT_ONLY')),
	prerequisite_expression jsonb,                             -- {"op":"AND"|"OR","args":[...]} or {"course":"CSE-017"}
	concurrent_text       text,                                -- the catalog's 'Can be taken Concurrently' line
	corequisite_text      text,
	description           text,                                -- Lehigh's prose: local database only, never committed
	catalog_aliases       text[] NOT NULL DEFAULT '{}',        -- cross-listed numbers printed beside the code; unverified, no CRN
	repeat_status         text,
	catalog_status        text NOT NULL CHECK (catalog_status IN ('CATALOG_PAGE_READ', 'NOT_FOUND_IN_2026_27_CATALOG')),
	catalog_year          text NOT NULL DEFAULT '2026-27',
	source_key            text NOT NULL REFERENCES catalog_csb.source,
	is_ns                 boolean GENERATED ALWAYS AS ('NS' = ANY (attributes)) STORED,
	CHECK (course_id = subject || '-' || course_number),
	CHECK ((prerequisite_status = 'NOT_FOUND') = (catalog_status = 'NOT_FOUND_IN_2026_27_CATALOG')),
	CHECK ((prerequisite_status = 'RULE_DEFINED') = (prerequisite_expression IS NOT NULL)),
	CHECK (prerequisite_status NOT IN ('TEXT_ONLY', 'RULE_DEFINED') OR length(btrim(prerequisite_text)) > 0)
);
COMMENT ON TABLE catalog_csb.catalog_course IS 'Public 2026-27 catalog identity of every course the CSB degree or its elective lists name. NOT_FOUND_IN_2026_27_CATALOG = the course appears in a program source but on no cached catalog page.';

-- ---------------------------------------------------------------------------------------------------------
-- The degree: requirement tree, elective lists, natural-science sequences, source conflicts
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS catalog_csb.program (
	program_id     text PRIMARY KEY,
	name           text NOT NULL,
	degree_type    text NOT NULL CHECK (degree_type IN ('BS', 'BA', 'MINOR')),
	catalog_year   text NOT NULL,
	total_credits  numeric(6, 1) NOT NULL CHECK (total_credits > 0),
	no_pass_fail   boolean NOT NULL,
	source_key     text NOT NULL REFERENCES catalog_csb.source,
	notes          jsonb NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS catalog_csb.requirement_group (
	group_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	program_id      text NOT NULL REFERENCES catalog_csb.program,
	parent_group_id bigint REFERENCES catalog_csb.requirement_group,
	group_key       text NOT NULL,
	title           text,
	logic           text NOT NULL CHECK (logic IN ('ALL', 'ANY', 'CHOOSE_N', 'CREDITS')),
	n               integer CHECK (n >= 1),
	credits         numeric(5, 2) CHECK (credits >= 0),         -- the credits the catalog prints for this requirement
	rule_text       text,
	status          text NOT NULL CHECK (status IN ('EXACT_COURSES', 'ALTERNATIVES', 'CATEGORY_NOT_COURSE_SPECIFIC', 'LIST_SELECTION', 'NATURAL_WORLD_RULE', 'GROUP')),
	note            text,
	UNIQUE (program_id, group_key),
	CHECK ((logic = 'CHOOSE_N') = (n IS NOT NULL))
);
COMMENT ON TABLE catalog_csb.requirement_group IS 'ALL = every member; ANY = one of the alternatives; CHOOSE_N = n of a list; CREDITS = a category with a credit rule in words. A category is never turned into mandatory courses.';

CREATE TABLE IF NOT EXISTS catalog_csb.requirement_option (
	option_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	group_id     bigint NOT NULL REFERENCES catalog_csb.requirement_group,
	option_set   integer NOT NULL,           -- options sharing a set are taken together (AND, in the order of option_order); different sets are alternatives
	option_order integer NOT NULL,
	course_id    text REFERENCES catalog_csb.catalog_course,
	list_key     text,                       -- the elective list a CHOOSE_N group draws from
	rule_text    text,
	CHECK (course_id IS NOT NULL OR list_key IS NOT NULL OR rule_text IS NOT NULL),
	UNIQUE (group_id, option_set, option_order)
);

CREATE TABLE IF NOT EXISTS catalog_csb.elective_list (
	list_key  text PRIMARY KEY,
	title     text NOT NULL,
	authority text NOT NULL,
	status    text NOT NULL CHECK (status IN ('CATALOG_LISTED', 'APPROVAL_NOT_VERIFIED')),
	statement text
);
CREATE TABLE IF NOT EXISTS catalog_csb.elective_list_member (
	list_key            text NOT NULL REFERENCES catalog_csb.elective_list,
	course_id           text NOT NULL REFERENCES catalog_csb.catalog_course,
	member_group        text NOT NULL,                  -- CATALOG_LIST, CSB_SUBJECT_CATALOG_COURSES, CSE_APPROVED_LIST_COURSES, WEBSITE_TRACK_EXAMPLES
	track               text NOT NULL DEFAULT '',        -- the example track for WEBSITE_TRACK_EXAMPLES
	listed_credits_text text,
	member_status       text NOT NULL CHECK (member_status IN ('CATALOG_LISTED', 'APPROVAL_NOT_VERIFIED')),
	basis               text NOT NULL,
	PRIMARY KEY (list_key, course_id, member_group, track),
	-- the catalog's own approved CSE elective list is the only thing listed as approved; every professional-elective candidate is unverified
	CHECK ((member_status = 'CATALOG_LISTED') = (list_key = 'CS_ELECTIVE_LIST' AND member_group = 'CATALOG_LIST'))
);
COMMENT ON TABLE catalog_csb.elective_list_member IS 'APPROVAL_NOT_VERIFIED: relevant to CSB (listed in a catalog, a track example) but no source says the co-directors approved it. Never promoted to approved here.';

CREATE TABLE IF NOT EXISTS catalog_csb.ns_sequence (
	sequence_key        text PRIMARY KEY,
	family              text NOT NULL,
	listed_credits_text text NOT NULL,
	footnote_marks      text[] NOT NULL DEFAULT '{}',
	special_rule        text
);
CREATE TABLE IF NOT EXISTS catalog_csb.ns_sequence_course (
	sequence_key text NOT NULL REFERENCES catalog_csb.ns_sequence,
	ordinal      integer NOT NULL CHECK (ordinal >= 1),
	course_id    text NOT NULL REFERENCES catalog_csb.catalog_course,
	PRIMARY KEY (sequence_key, ordinal)
);
CREATE TABLE IF NOT EXISTS catalog_csb.ns_optional_lab (
	course_id          text PRIMARY KEY REFERENCES catalog_csb.catalog_course,
	credits_text       text NOT NULL,
	applies_to_footnote text NOT NULL
);
CREATE TABLE IF NOT EXISTS catalog_csb.ns_footnote (
	mark text PRIMARY KEY,
	text text NOT NULL
);
COMMENT ON TABLE catalog_csb.ns_sequence IS 'The catalog''s SUGGESTED natural-science sequences. They are examples, not required courses; which of them exist in a given term depends on the science departments.';

CREATE TABLE IF NOT EXISTS catalog_csb.source_conflict (
	conflict_key      text PRIMARY KEY,
	catalog_statement text NOT NULL,
	other_statement   text NOT NULL,
	status            text NOT NULL
);
CREATE TABLE IF NOT EXISTS catalog_csb.unresolved_item (
	item_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	source  text NOT NULL,
	subject text NOT NULL,
	detail  text NOT NULL
);

-- ---------------------------------------------------------------------------------------------------------
-- Spring 2027 (or any term) Banner data, local-only
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS catalog_csb.banner_search (
	search_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code     text NOT NULL,
	search_kind   text NOT NULL CHECK (search_kind IN ('EXACT_COURSE', 'SUBJECT_WIDE')),
	subject_label text NOT NULL,               -- Banner's subject description as searched
	course_number text,
	course_id     text,                        -- set for an exact search whose Banner subject maps to a catalog code
	class_count   integer NOT NULL CHECK (class_count >= 0),
	result        text NOT NULL CHECK (result IN ('OFFERED', 'NOT_OFFERED')),
	source_key    text NOT NULL REFERENCES catalog_csb.source,
	CHECK ((search_kind = 'EXACT_COURSE') = (course_number IS NOT NULL)),
	CHECK ((result = 'OFFERED') = (class_count > 0))
);
CREATE UNIQUE INDEX IF NOT EXISTS banner_search_identity_idx ON catalog_csb.banner_search (term_code, search_kind, subject_label, COALESCE(course_number, ''));
COMMENT ON TABLE catalog_csb.banner_search IS 'The exact searches that were run. NOT_OFFERED = Banner returned 0 classes for that exact subject and number (a cross-listing under another subject was not checked).';

CREATE TABLE IF NOT EXISTS catalog_csb.offering (
	term_code           text NOT NULL,
	offering_id         text NOT NULL,                          -- CSB-310
	course_id           text NOT NULL REFERENCES catalog_csb.catalog_course,
	subject             text NOT NULL,
	course_number       text NOT NULL,
	title               text NOT NULL CHECK (length(btrim(title)) > 0),     -- Banner's title
	credits_text        text,                                   -- NULL when the lectures differ
	credits_min         numeric(5, 2),
	credits_max         numeric(5, 2),
	credits_status      text NOT NULL CHECK (credits_status IN ('SINGLE_VALUE', 'VARIABLE_RANGE', 'VARIES_BY_SECTION')),
	administrative_type text NOT NULL,                          -- Banner schedule type of the first lecture (or first section)
	components_status   text NOT NULL CHECK (components_status IN ('SINGLE_COMPONENT', 'LINKED_COMPONENTS_LISTED', 'COMPONENT_ONLY_COURSE')),
	data_source         text NOT NULL CHECK (data_source = 'BANNER_CSB_DETAILS'),
	run_id              bigint NOT NULL REFERENCES catalog_csb.import_run,
	PRIMARY KEY (term_code, offering_id),
	CHECK (offering_id = course_id AND course_id = subject || '-' || course_number),
	CHECK ((credits_status = 'VARIES_BY_SECTION') = (credits_min IS NULL AND credits_max IS NULL AND credits_text IS NULL)),
	CHECK ((credits_status = 'VARIABLE_RANGE') = (credits_min < credits_max))
);

CREATE TABLE IF NOT EXISTS catalog_csb.section (
	term_code      text NOT NULL,
	crn            text NOT NULL CHECK (crn ~ '^[0-9]{5}$'),
	offering_id    text NOT NULL,
	section_code   text NOT NULL,
	component_type text NOT NULL CHECK (component_type IN ('LECTURE', 'LAB', 'RECITATION', 'INDEPENDENT_STUDY')),
	schedule_type  text NOT NULL,                                -- Banner's schedule type, verbatim
	credits_text   text NOT NULL,                                -- the grid's credit hours, verbatim ('3', '1.5', '0', '1 TO 3')
	credits_min    numeric(5, 2) NOT NULL CHECK (credits_min >= 0),
	credits_max    numeric(5, 2) NOT NULL CHECK (credits_max >= credits_min),
	attribute_text text,                                         -- the grid's attribute cell
	campus         text,
	grade_mode     text,
	details_credit_hours_text text,                              -- Class Details 'Credit Hours' (a linked component may show the bundled total)
	credits_display_status text NOT NULL CHECK (credits_display_status IN ('GRID_AND_DETAILS_AGREE', 'DETAILS_DIFFER_FROM_GRID')),
	link_status    text NOT NULL CHECK (link_status IN ('NO_LINKS_LISTED', 'CONFIRMED_BOTH_SIDES', 'LISTED_BY_ONE_SIDE_ONLY')),
	PRIMARY KEY (term_code, crn),
	FOREIGN KEY (term_code, offering_id) REFERENCES catalog_csb.offering,
	UNIQUE (term_code, offering_id, section_code)
);
COMMENT ON COLUMN catalog_csb.section.credits_text IS 'The Browse Classes grid value, kept verbatim. A linked lab or recitation shows 0 in the grid but may show the bundled total in Class Details (credits_display_status); that is NOT proof the component adds credits.';

CREATE TABLE IF NOT EXISTS catalog_csb.section_meeting (
	meeting_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code    text NOT NULL,
	crn          text NOT NULL,
	day_of_week  smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),     -- 1 = Monday, as in catalog_research
	start_time   time NOT NULL,
	end_time     time NOT NULL,
	location     text NOT NULL,
	meeting_type text NOT NULL,                                 -- Banner's label: Class, Lab, Recitation, Common Hour Exam
	meeting_kind text NOT NULL CHECK (meeting_kind IN ('WEEKLY', 'DATED_ONE_TIME')),
	start_date   date NOT NULL,
	end_date     date NOT NULL,
	FOREIGN KEY (term_code, crn) REFERENCES catalog_csb.section,
	CHECK (end_time > start_time),
	CHECK ((meeting_kind = 'WEEKLY' AND start_date <= end_date) OR (meeting_kind = 'DATED_ONE_TIME' AND end_date = start_date AND day_of_week = extract(isodow FROM start_date)))
);
CREATE UNIQUE INDEX IF NOT EXISTS csb_section_meeting_identity_idx ON catalog_csb.section_meeting (term_code, crn, meeting_kind, day_of_week, start_time, end_time, start_date);

CREATE TABLE IF NOT EXISTS catalog_csb.section_arranged_meeting (
	term_code    text NOT NULL,
	crn          text NOT NULL,
	meeting_type text NOT NULL,
	description  text NOT NULL CHECK (length(btrim(description)) > 0),
	start_date   date NOT NULL,
	end_date     date NOT NULL,
	PRIMARY KEY (term_code, crn),
	FOREIGN KEY (term_code, crn) REFERENCES catalog_csb.section
);
COMMENT ON TABLE catalog_csb.section_arranged_meeting IS 'A meeting with no published time (arranged with the instructor). It cannot be placed in a timetable.';

CREATE TABLE IF NOT EXISTS catalog_csb.section_instructor (
	term_code       text NOT NULL,
	crn             text NOT NULL,
	position        smallint NOT NULL CHECK (position >= 1),   -- the order Banner listed the names
	instructor_name text NOT NULL CHECK (length(btrim(instructor_name)) > 0),   -- exactly as displayed ('Last, First')
	role            text NOT NULL CHECK (role IN ('Primary', 'Additional')),
	is_placeholder  boolean NOT NULL,                           -- 'Staff, Teaching' and similar: nobody was assigned yet
	source_key      text NOT NULL REFERENCES catalog_csb.source,
	PRIMARY KEY (term_code, crn, position),
	FOREIGN KEY (term_code, crn) REFERENCES catalog_csb.section,
	CHECK (NOT is_placeholder OR instructor_name ~* '^\s*staff\y')
);
COMMENT ON TABLE catalog_csb.section_instructor IS 'LOCAL-ONLY: names read from an authenticated Banner session. Never exported, committed or served; no professor is guessed for an unassigned section.';

CREATE TABLE IF NOT EXISTS catalog_csb.grid_observation (
	term_code     text NOT NULL,
	crn           text NOT NULL CHECK (crn ~ '^[0-9]{5}$'),
	course_label  text NOT NULL,         -- 'Computer Science and Business 310' as subject description + number
	section_no    text NOT NULL,
	title         text NOT NULL,
	hours_grid    text NOT NULL,
	schedule_type text NOT NULL,
	attribute     text,
	meetings      text[] NOT NULL,       -- the grid's meeting strings, verbatim
	instructors   jsonb NOT NULL,        -- the grid's instructor cell: [{"name":..,"role":..}]
	source_key    text NOT NULL REFERENCES catalog_csb.source,
	PRIMARY KEY (term_code, crn)
);
COMMENT ON TABLE catalog_csb.grid_observation IS 'The verbatim grid row of every stored section; grid_mismatch re-derives the normalized rows from it in SQL.';

CREATE TABLE IF NOT EXISTS catalog_csb.section_detail (
	term_code           text NOT NULL,
	crn                 text NOT NULL,
	panels_inspected    text[] NOT NULL,
	panels_not_inspected text[] NOT NULL,
	restrictions_text   text,
	prerequisites_text  text,
	corequisites_text   text,
	cross_list_text     text,
	linked_text         text,
	restriction_status  text NOT NULL CHECK (restriction_status IN ('NONE_LISTED', 'RULE_DEFINED', 'TEXT_ONLY')),
	prerequisite_status text NOT NULL CHECK (prerequisite_status IN ('NONE_LISTED', 'RULE_DEFINED', 'TEXT_ONLY')),
	corequisite_status  text NOT NULL CHECK (corequisite_status IN ('NONE_LISTED', 'RULE_DEFINED', 'TEXT_ONLY')),
	source_key          text NOT NULL REFERENCES catalog_csb.source,
	PRIMARY KEY (term_code, crn),
	FOREIGN KEY (term_code, crn) REFERENCES catalog_csb.section,
	CHECK (panels_inspected && ARRAY['Class Details']),
	CHECK (NOT (panels_inspected && panels_not_inspected))
);
COMMENT ON TABLE catalog_csb.section_detail IS 'What each section''s Class Details panels said, verbatim, and which panels were and were not read. NONE_LISTED means Banner printed "no ... information available", which is not proof that no requirement exists.';

CREATE TABLE IF NOT EXISTS catalog_csb.section_rule (
	rule_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code      text NOT NULL,
	crn            text NOT NULL,
	rule_kind      text NOT NULL CHECK (rule_kind IN ('CLASS_STANDING', 'LEVEL', 'COLLEGE', 'MAJOR', 'DEGREE', 'COHORT', 'DEPARTMENT_APPROVAL', 'PREREQUISITE', 'COREQUISITE')),
	polarity       text NOT NULL CHECK (polarity IN ('MUST', 'CANNOT', 'NEEDS', 'REQUIRES')),
	rule_values    text[] NOT NULL DEFAULT '{}',
	expression     jsonb,
	min_grade_shown text[] NOT NULL DEFAULT '{}',               -- Banner's 'Grade' column (e.g. TR) as printed; its meaning is not interpreted
	source_text    text NOT NULL CHECK (length(btrim(source_text)) > 0),
	FOREIGN KEY (term_code, crn) REFERENCES catalog_csb.section,
	CHECK (rule_kind IN ('CLASS_STANDING', 'LEVEL', 'COLLEGE', 'MAJOR', 'DEGREE', 'COHORT', 'DEPARTMENT_APPROVAL') OR polarity = 'REQUIRES'),
	CHECK ((rule_kind IN ('PREREQUISITE', 'COREQUISITE')) = (expression IS NOT NULL)),
	CHECK (rule_kind IN ('PREREQUISITE', 'COREQUISITE') OR cardinality(rule_values) > 0)
);
CREATE INDEX IF NOT EXISTS csb_section_rule_idx ON catalog_csb.section_rule (term_code, crn);

-- one row per (panel, option, member): the Linked Sections panel of a section, option by option. Options are alternatives; the members of
-- one option are taken together.
CREATE TABLE IF NOT EXISTS catalog_csb.section_link_option (
	term_code          text NOT NULL,
	panel_crn          text NOT NULL,
	option_no          smallint NOT NULL CHECK (option_no >= 1),
	member_crn         text NOT NULL,
	member_schedule_type text NOT NULL,
	member_section     text NOT NULL,
	PRIMARY KEY (term_code, panel_crn, option_no, member_crn),
	FOREIGN KEY (term_code, panel_crn) REFERENCES catalog_csb.section,
	FOREIGN KEY (term_code, member_crn) REFERENCES catalog_csb.section,
	CHECK (panel_crn <> member_crn)
);
COMMENT ON TABLE catalog_csb.section_link_option IS 'Verbatim Linked Sections panels. Never inferred: a pair is CONFIRMED_BOTH_SIDES only when each side''s panel lists the other.';

CREATE TABLE IF NOT EXISTS catalog_csb.section_cross_list (
	term_code        text NOT NULL,
	crn              text NOT NULL,
	cross_crn        text NOT NULL CHECK (cross_crn ~ '^[0-9]{5}$'),
	cross_subject    text NOT NULL,
	cross_course_number text NOT NULL,
	cross_title      text NOT NULL,
	cross_section    text NOT NULL,
	PRIMARY KEY (term_code, crn, cross_crn),
	FOREIGN KEY (term_code, crn) REFERENCES catalog_csb.section
);
COMMENT ON TABLE catalog_csb.section_cross_list IS 'The Cross Listed Courses panel rows (the seat figures printed above them are never stored). cross_crn need not be a stored section.';

-- catalog statement vs Banner statement for each prerequisite variant of an offering; both texts are kept
CREATE TABLE IF NOT EXISTS catalog_csb.prerequisite_comparison (
	comparison_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code     text NOT NULL,
	course_id     text NOT NULL REFERENCES catalog_csb.catalog_course,
	status        text NOT NULL CHECK (status IN ('MATCH', 'MATCH_BOTH_SILENT', 'SAME_COURSES_DIFFERENT_LOGIC', 'CONFLICT', 'CATALOG_ONLY', 'BANNER_ONLY', 'NOT_COMPARABLE_TEXT_ONLY')),
	crns          text[] NOT NULL,
	catalog_text  text,
	banner_text   text,
	catalog_expression jsonb,
	banner_expression  jsonb
);

CREATE TABLE IF NOT EXISTS catalog_csb.detail_discrepancy (
	discrepancy_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	term_code      text NOT NULL,
	crn            text NOT NULL,
	field          text NOT NULL CHECK (field IN ('CREDIT_HOURS', 'SCHEDULE_TYPE', 'SECTION_NUMBER')),
	grid_value     text,
	details_value  text,
	FOREIGN KEY (term_code, crn) REFERENCES catalog_csb.section
);

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'import_run_append_only' AND tgrelid = 'catalog_csb.import_run'::regclass) THEN
		CREATE TRIGGER import_run_append_only BEFORE UPDATE OR DELETE ON catalog_csb.import_run FOR EACH ROW EXECUTE FUNCTION catalog_csb.reject_change();
		CREATE TRIGGER import_run_no_truncate BEFORE TRUNCATE ON catalog_csb.import_run FOR EACH STATEMENT EXECUTE FUNCTION catalog_csb.reject_change();
	END IF;
END $$;
