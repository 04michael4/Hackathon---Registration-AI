-- Snapshot transcribed from user-provided Banner screenshots. Not a live feed.
CREATE SCHEMA cse007_snapshot;
SET LOCAL search_path TO cse007_snapshot;

CREATE TABLE source_asset (
	source_id UUID PRIMARY KEY,
	file_path TEXT NOT NULL UNIQUE,
	sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
	source_kind TEXT NOT NULL CHECK (source_kind = 'USER_SCREENSHOT'),
	source_url TEXT,
	captured_at TIMESTAMPTZ,
	verified_at TIMESTAMPTZ NOT NULL,
	coverage_note TEXT NOT NULL
);
CREATE TABLE course (
	course_id UUID PRIMARY KEY,
	code VARCHAR(32) NOT NULL UNIQUE,
	subject TEXT NOT NULL,
	course_number VARCHAR(8) NOT NULL,
	title TEXT NOT NULL,
	description TEXT,
	credits NUMERIC(4,1) CHECK (credits >= 0),
	prerequisite_status TEXT NOT NULL CHECK (prerequisite_status IN ('UNKNOWN','VERIFIED')),
	source_id UUID NOT NULL REFERENCES source_asset ON DELETE RESTRICT,
	CHECK (code = subject || '-' || course_number)
);
CREATE TABLE term (
	term_id UUID PRIMARY KEY,
	term_code VARCHAR(32) NOT NULL UNIQUE,
	name TEXT NOT NULL,
	academic_year VARCHAR(9),
	source_id UUID NOT NULL REFERENCES source_asset ON DELETE RESTRICT
);
CREATE TABLE section (
	section_id UUID PRIMARY KEY,
	course_id UUID NOT NULL REFERENCES course ON DELETE RESTRICT,
	term_id UUID NOT NULL REFERENCES term ON DELETE RESTRICT,
	crn VARCHAR(16) NOT NULL,
	section_code VARCHAR(32) NOT NULL,
	component_type TEXT NOT NULL CHECK (component_type IN ('LECTURE','LAB','RECITATION','OTHER')),
	instructor TEXT,
	instructor_is_primary BOOLEAN,
	credits NUMERIC(4,1) CHECK (credits >= 0),
	credits_verification_status TEXT NOT NULL CHECK (credits_verification_status IN ('TRANSCRIBED','NEEDS_REVIEW')),
	delivery_type TEXT NOT NULL CHECK (delivery_type IN ('IN_PERSON','ONLINE','HYBRID','UNKNOWN')),
	meeting_status TEXT NOT NULL CHECK (meeting_status IN ('SCHEDULED','TBA','ASYNCHRONOUS')),
	link_status TEXT NOT NULL CHECK (link_status IN ('PAIR_CONFIRMED','TARGET_UNKNOWN')),
	campus_text TEXT,
	grade_mode_text TEXT,
	search_status_text TEXT,
	source_status TEXT NOT NULL CHECK (source_status = 'HISTORICAL'),
	source_id UUID NOT NULL REFERENCES source_asset ON DELETE RESTRICT,
	detail_source_id UUID REFERENCES source_asset ON DELETE RESTRICT,
	UNIQUE (term_id, crn),
	UNIQUE (term_id, course_id, section_code),
	UNIQUE (section_id, course_id, term_id)
);
CREATE INDEX section_term_course_idx ON section(term_id, course_id);
CREATE TABLE section_attribute (
	section_id UUID NOT NULL REFERENCES section ON DELETE RESTRICT,
	attribute_text TEXT NOT NULL,
	source_id UUID NOT NULL REFERENCES source_asset ON DELETE RESTRICT,
	PRIMARY KEY (section_id, attribute_text)
);
CREATE TABLE meeting (
	meeting_id UUID PRIMARY KEY,
	section_id UUID NOT NULL REFERENCES section ON DELETE RESTRICT,
	day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
	start_time TIME NOT NULL,
	end_time TIME NOT NULL CHECK (end_time > start_time),
	start_date DATE,
	end_date DATE,
	campus TEXT,
	building TEXT,
	room TEXT,
	location TEXT,
	meeting_type_text TEXT,
	source_status TEXT NOT NULL CHECK (source_status = 'HISTORICAL'),
	source_id UUID NOT NULL REFERENCES source_asset ON DELETE RESTRICT,
	UNIQUE (section_id, day_of_week, start_time, end_time),
	CHECK ((start_date IS NULL AND end_date IS NULL)
		OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date))
);
CREATE TABLE section_link (
	link_id UUID PRIMARY KEY,
	primary_section_id UUID NOT NULL,
	required_component_group TEXT NOT NULL CHECK (required_component_group = 'LAB'),
	compatible_section_id UUID NOT NULL,
	course_id UUID NOT NULL,
	term_id UUID NOT NULL,
	source_id UUID NOT NULL REFERENCES source_asset ON DELETE RESTRICT,
	verification_status TEXT NOT NULL CHECK (verification_status = 'PAIR_CONFIRMED'),
	evidence_note TEXT NOT NULL,
	UNIQUE (primary_section_id, required_component_group, compatible_section_id),
	CHECK (primary_section_id <> compatible_section_id),
	FOREIGN KEY (primary_section_id, course_id, term_id) REFERENCES section(section_id, course_id, term_id) ON DELETE RESTRICT,
	FOREIGN KEY (compatible_section_id, course_id, term_id) REFERENCES section(section_id, course_id, term_id) ON DELETE RESTRICT
);
CREATE INDEX section_link_component_idx ON section_link(compatible_section_id);
CREATE TABLE enrollment_snapshot (
	enrollment_snapshot_id UUID PRIMARY KEY,
	section_id UUID NOT NULL UNIQUE REFERENCES section ON DELETE RESTRICT,
	enrollment_actual INTEGER NOT NULL CHECK (enrollment_actual >= 0),
	enrollment_maximum INTEGER NOT NULL CHECK (enrollment_maximum >= 0),
	enrollment_seats_available INTEGER NOT NULL CHECK (enrollment_seats_available >= 0),
	waitlist_capacity INTEGER NOT NULL CHECK (waitlist_capacity >= 0),
	waitlist_actual INTEGER NOT NULL CHECK (waitlist_actual >= 0),
	waitlist_seats_available INTEGER NOT NULL CHECK (waitlist_seats_available >= 0),
	is_live BOOLEAN NOT NULL DEFAULT false CHECK (NOT is_live),
	source_id UUID NOT NULL REFERENCES source_asset ON DELETE RESTRICT
);
CREATE TABLE data_issue (
	issue_id UUID PRIMARY KEY,
	section_id UUID REFERENCES section ON DELETE RESTRICT,
	code TEXT NOT NULL,
	field_name TEXT NOT NULL,
	severity TEXT NOT NULL CHECK (severity IN ('INFO','WARNING')),
	details TEXT NOT NULL,
	observations JSONB NOT NULL CHECK (jsonb_typeof(observations) = 'array')
);
CREATE INDEX data_issue_section_idx ON data_issue(section_id);

COMMENT ON SCHEMA cse007_snapshot IS 'User screenshot transcription; static evidence, not live Banner or enrollment integration.';
COMMENT ON COLUMN source_asset.verified_at IS 'Time the screenshot transcription was checked, not time of a live Banner query.';
COMMENT ON TABLE section_link IS 'Confirmed pair only; absence is unknown, and the full required-component group has not been verified.';
COMMENT ON COLUMN section.credits IS 'Section-list Hours value; consult credits_verification_status and data_issue before treating it as settled.';
