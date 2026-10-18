-- Physical fields follow the supplied PDF, section 6.4.1.
-- Separate schema preserves the earlier text-ID demo in cse007_demo.
CREATE SCHEMA cse007_pdf_demo;
SET LOCAL search_path TO cse007_pdf_demo;

CREATE TABLE course (
	course_id UUID PRIMARY KEY,
	code VARCHAR(32) NOT NULL UNIQUE CHECK (code ~ '^[A-Z]+-[0-9]{3}$'),
	title TEXT NOT NULL,
	description TEXT,
	credits NUMERIC(4,1) CHECK (credits >= 0),
	source_url TEXT
);
CREATE TABLE term (
	term_id UUID PRIMARY KEY,
	name TEXT NOT NULL,
	academic_year VARCHAR(9) NOT NULL CHECK (academic_year ~ '^[0-9]{4}-[0-9]{4}$'),
	term_code VARCHAR(32) NOT NULL UNIQUE
);
CREATE TABLE section (
	section_id UUID PRIMARY KEY,
	course_id UUID NOT NULL REFERENCES course ON DELETE RESTRICT,
	term_id UUID NOT NULL REFERENCES term ON DELETE RESTRICT,
	section_code VARCHAR(32) NOT NULL,
	instructor TEXT,
	delivery_type VARCHAR(20) NOT NULL CHECK (delivery_type IN ('IN_PERSON','ONLINE','HYBRID','UNKNOWN')),
	component_type VARCHAR(20) NOT NULL CHECK (component_type IN ('LECTURE','LAB','RECITATION','SEMINAR','OTHER')),
	credits NUMERIC(4,1) CHECK (credits >= 0),
	source_status VARCHAR(20) NOT NULL CHECK (source_status IN ('REAL_VERIFIED','HISTORICAL','DEMO_SIMULATED')),
	source_url TEXT,
	verified_at TIMESTAMPTZ,
	meeting_status VARCHAR(20) NOT NULL CHECK (meeting_status IN ('SCHEDULED','ASYNCHRONOUS','TBA')),
	UNIQUE (term_id, course_id, section_code),
	UNIQUE (section_id, term_id),
	CHECK (source_status = 'DEMO_SIMULATED' OR (source_url IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE INDEX section_term_course_idx ON section(term_id, course_id);
CREATE TABLE meeting (
	meeting_id UUID PRIMARY KEY,
	section_id UUID NOT NULL REFERENCES section ON DELETE RESTRICT,
	day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
	start_time TIME NOT NULL,
	end_time TIME NOT NULL CHECK (end_time > start_time),
	location TEXT,
	source_status VARCHAR(20) NOT NULL CHECK (source_status IN ('REAL_VERIFIED','HISTORICAL','DEMO_SIMULATED')),
	source_url TEXT,
	verified_at TIMESTAMPTZ,
	UNIQUE (section_id, day_of_week, start_time, end_time),
	CHECK (source_status = 'DEMO_SIMULATED' OR (source_url IS NOT NULL AND verified_at IS NOT NULL))
);
-- The unique constraint above also indexes meeting(section_id).
CREATE TABLE section_link (
	link_id UUID PRIMARY KEY,
	primary_section_id UUID NOT NULL REFERENCES section ON DELETE RESTRICT,
	required_component_group VARCHAR(64) NOT NULL CHECK (length(trim(required_component_group)) > 0),
	compatible_section_id UUID NOT NULL REFERENCES section ON DELETE RESTRICT,
	UNIQUE (primary_section_id, required_component_group, compatible_section_id),
	CHECK (primary_section_id <> compatible_section_id)
);
CREATE INDEX section_link_component_idx ON section_link(compatible_section_id);
CREATE TABLE prerequisite_rule (
	rule_id UUID PRIMARY KEY,
	course_id UUID NOT NULL UNIQUE REFERENCES course ON DELETE RESTRICT,
	requirement_expression JSONB NOT NULL CHECK (jsonb_typeof(requirement_expression) = 'object'),
	source_url TEXT,
	verification_status VARCHAR(20) NOT NULL CHECK (verification_status IN ('VERIFIED','UNVERIFIED','NEEDS_REVIEW')),
	CHECK (verification_status <> 'VERIFIED' OR source_url IS NOT NULL)
);
-- Demo records only. These tables do not authorize public student persistence.
CREATE TABLE student_profile (
	profile_id UUID PRIMARY KEY,
	major TEXT,
	graduation_target VARCHAR(32),
	completed_course_codes TEXT[] NOT NULL DEFAULT '{}',
	credit_preferences JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(credit_preferences) = 'object'),
	is_demo BOOLEAN NOT NULL DEFAULT true CHECK (is_demo)
);
CREATE TABLE schedule_preference (
	preference_id UUID PRIMARY KEY,
	profile_id UUID NOT NULL REFERENCES student_profile ON DELETE RESTRICT,
	term_id UUID NOT NULL REFERENCES term ON DELETE RESTRICT,
	structured_constraints_json JSONB NOT NULL CHECK (jsonb_typeof(structured_constraints_json) = 'object'),
	original_request TEXT,
	schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (schema_version > 0),
	UNIQUE (preference_id, profile_id, term_id)
);
CREATE INDEX preference_profile_term_idx ON schedule_preference(profile_id, term_id);
CREATE TABLE generated_schedule (
	schedule_id UUID PRIMARY KEY,
	profile_id UUID NOT NULL REFERENCES student_profile ON DELETE RESTRICT,
	preference_id UUID NOT NULL,
	term_id UUID NOT NULL REFERENCES term ON DELETE RESTRICT,
	ranking_preset VARCHAR(32) NOT NULL,
	score NUMERIC(12,4) NOT NULL,
	score_breakdown JSONB NOT NULL CHECK (jsonb_typeof(score_breakdown) = 'object'),
	explanation TEXT,
	generated_at TIMESTAMPTZ NOT NULL,
	UNIQUE (schedule_id, term_id),
	FOREIGN KEY (preference_id, profile_id, term_id)
		REFERENCES schedule_preference(preference_id, profile_id, term_id) ON DELETE RESTRICT
);
CREATE INDEX schedule_profile_generated_idx ON generated_schedule(profile_id, generated_at);
CREATE TABLE generated_schedule_section (
	schedule_id UUID NOT NULL,
	section_id UUID NOT NULL,
	-- Additional consistency field: selected sections must share the schedule term.
	term_id UUID NOT NULL,
	PRIMARY KEY (schedule_id, section_id),
	FOREIGN KEY (schedule_id, term_id) REFERENCES generated_schedule(schedule_id, term_id) ON DELETE CASCADE,
	FOREIGN KEY (section_id, term_id) REFERENCES section(section_id, term_id) ON DELETE RESTRICT
);
CREATE INDEX schedule_section_section_idx ON generated_schedule_section(section_id);
