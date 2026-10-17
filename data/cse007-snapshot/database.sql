-- Generated screenshot + SuY clarification bundle. Run once without cse007_snapshot.
BEGIN;
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

-- Generated from fixture.json by build_seed.py. Do not edit by hand.

-- Stable UUID upserts; apply in a transaction together with verify.sql.

SET LOCAL search_path TO cse007_snapshot;

INSERT INTO source_asset (source_id, file_path, sha256, source_kind, source_url, captured_at, verified_at, coverage_note)
SELECT source_id, file_path, sha256, source_kind, source_url, captured_at, verified_at, coverage_note FROM jsonb_populate_recordset(NULL::source_asset,
$fixture$[
  {
    "source_id": "00000007-0010-4000-8000-000000000001",
    "file_path": "Images for courses/KakaoTalk_20261015_214742198.png",
    "sha256": "82ab5c5e510a612494491df74ee6c49bdd73bdccd0cd3c2f6a1028a57c992bf8",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Search filters: CSE 007, 2027 Spring Semester."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000002",
    "file_path": "Images for courses/KakaoTalk_20261015_214757090.png",
    "sha256": "cde35620c69afc240187fb225a13273c5e65702d9648adc3b8fc827edeb035b3",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Three section rows: CRNs, section codes, Hours, highlighted meeting days, times, instructor, attributes, LINKED flags. Status text is truncated."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000003",
    "file_path": "Images for courses/KakaoTalk_20261015_214823618.png",
    "sha256": "808b265df0473bdbe6de0f1961050a28f23631794612effec4d4d4cb36b3c805",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060 details: term code 202710, CRN 13132, title, campus and grade-mode text. Credit Hours 4 conflicts with the results-row Hours 0."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000004",
    "file_path": "Images for courses/KakaoTalk_20261015_214850130.png",
    "sha256": "e4dddeff2f2034bc784d8ca6023d09e8b673b50bc09f3953c32c9c3a641c8b36",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060: Wednesday/Friday 09:20-10:35, 2027-01-25 through 2027-05-07, Packard Lab room 112, Brian Chen."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000005",
    "file_path": "Images for courses/KakaoTalk_20261015_214926225.png",
    "sha256": "ac566a235249b04908a4cdd1682368b59562e05fa38533d18b4cf8d500ae08fb",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Cropped detail panel: right-hand content is unreadable. Does not establish prerequisite or other requirement rules."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000006",
    "file_path": "Images for courses/KakaoTalk_20261015_214949427.png",
    "sha256": "dabb4450850594fd78e2619e4d0e6e2e4dd0a837a4042e04b43f751abba93539",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060 enrollment/waitlist snapshot: actual 0, maximum 38, available 38; waitlist values 0. Capture time and current availability unknown."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000007",
    "file_path": "Images for courses/KakaoTalk_20261015_215023730.png",
    "sha256": "5a8265eb9f0873ec01df2af7127779b7330d5697f141303774c374d0d427581c",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060 Linked Sections, Option 1: lecture section 010, CRN 12275. No lab 062 linked-section detail is provided."
  }
]$fixture$::jsonb)
ON CONFLICT (source_id) DO UPDATE SET file_path = EXCLUDED.file_path, sha256 = EXCLUDED.sha256, source_kind = EXCLUDED.source_kind, source_url = EXCLUDED.source_url, captured_at = EXCLUDED.captured_at, verified_at = EXCLUDED.verified_at, coverage_note = EXCLUDED.coverage_note;

INSERT INTO course (course_id, code, subject, course_number, title, description, credits, prerequisite_status, source_id)
SELECT course_id, code, subject, course_number, title, description, credits, prerequisite_status, source_id FROM jsonb_populate_recordset(NULL::course,
$fixture$[
  {
    "course_id": "00000007-0000-4000-8000-000000000001",
    "code": "CSE-007",
    "subject": "CSE",
    "course_number": "007",
    "title": "Introduction to Programming",
    "description": null,
    "credits": 4,
    "prerequisite_status": "UNKNOWN",
    "source_id": "00000007-0010-4000-8000-000000000003"
  }
]$fixture$::jsonb)
ON CONFLICT (course_id) DO UPDATE SET code = EXCLUDED.code, subject = EXCLUDED.subject, course_number = EXCLUDED.course_number, title = EXCLUDED.title, description = EXCLUDED.description, credits = EXCLUDED.credits, prerequisite_status = EXCLUDED.prerequisite_status, source_id = EXCLUDED.source_id;

INSERT INTO term (term_id, term_code, name, academic_year, source_id)
SELECT term_id, term_code, name, academic_year, source_id FROM jsonb_populate_recordset(NULL::term,
$fixture$[
  {
    "term_id": "00000007-0001-4000-8000-000000202710",
    "term_code": "202710",
    "name": "2027 Spring Semester",
    "academic_year": null,
    "source_id": "00000007-0010-4000-8000-000000000003"
  }
]$fixture$::jsonb)
ON CONFLICT (term_id) DO UPDATE SET term_code = EXCLUDED.term_code, name = EXCLUDED.name, academic_year = EXCLUDED.academic_year, source_id = EXCLUDED.source_id;

INSERT INTO section (section_id, course_id, term_id, crn, section_code, component_type, instructor, instructor_is_primary, credits, credits_verification_status, delivery_type, meeting_status, link_status, campus_text, grade_mode_text, search_status_text, source_status, source_id, detail_source_id)
SELECT section_id, course_id, term_id, crn, section_code, component_type, instructor, instructor_is_primary, credits, credits_verification_status, delivery_type, meeting_status, link_status, campus_text, grade_mode_text, search_status_text, source_status, source_id, detail_source_id FROM jsonb_populate_recordset(NULL::section,
$fixture$[
  {
    "section_id": "00000007-0002-4000-8000-000000012275",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "crn": "12275",
    "section_code": "010",
    "component_type": "LECTURE",
    "instructor": "Chen, Brian",
    "instructor_is_primary": true,
    "credits": 4,
    "credits_verification_status": "TRANSCRIBED",
    "delivery_type": "UNKNOWN",
    "meeting_status": "SCHEDULED",
    "link_status": "PAIR_CONFIRMED",
    "campus_text": null,
    "grade_mode_text": null,
    "search_status_text": "70 of 70 seats...",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "detail_source_id": null
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "crn": "13132",
    "section_code": "060",
    "component_type": "LAB",
    "instructor": "Chen, Brian",
    "instructor_is_primary": true,
    "credits": 0,
    "credits_verification_status": "NEEDS_REVIEW",
    "delivery_type": "IN_PERSON",
    "meeting_status": "SCHEDULED",
    "link_status": "PAIR_CONFIRMED",
    "campus_text": "Engineering&App Sci On Campus",
    "grade_mode_text": "No Section specified grade mode, please see Catalog link below for more information.",
    "search_status_text": "38 of 38 seats...",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "detail_source_id": "00000007-0010-4000-8000-000000000003"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "crn": "12638",
    "section_code": "062",
    "component_type": "LAB",
    "instructor": "Chen, Brian",
    "instructor_is_primary": true,
    "credits": 0,
    "credits_verification_status": "TRANSCRIBED",
    "delivery_type": "IN_PERSON",
    "meeting_status": "SCHEDULED",
    "link_status": "TARGET_UNKNOWN",
    "campus_text": null,
    "grade_mode_text": null,
    "search_status_text": "37 of 37 seats...",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "detail_source_id": null
  }
]$fixture$::jsonb)
ON CONFLICT (section_id) DO UPDATE SET course_id = EXCLUDED.course_id, term_id = EXCLUDED.term_id, crn = EXCLUDED.crn, section_code = EXCLUDED.section_code, component_type = EXCLUDED.component_type, instructor = EXCLUDED.instructor, instructor_is_primary = EXCLUDED.instructor_is_primary, credits = EXCLUDED.credits, credits_verification_status = EXCLUDED.credits_verification_status, delivery_type = EXCLUDED.delivery_type, meeting_status = EXCLUDED.meeting_status, link_status = EXCLUDED.link_status, campus_text = EXCLUDED.campus_text, grade_mode_text = EXCLUDED.grade_mode_text, search_status_text = EXCLUDED.search_status_text, source_status = EXCLUDED.source_status, source_id = EXCLUDED.source_id, detail_source_id = EXCLUDED.detail_source_id;

INSERT INTO section_attribute (section_id, attribute_text, source_id)
SELECT section_id, attribute_text, source_id FROM jsonb_populate_recordset(NULL::section_attribute,
$fixture$[
  {
    "section_id": "00000007-0002-4000-8000-000000012275",
    "attribute_text": "FLEX - Classroom",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012275",
    "attribute_text": "Quantitative Reasoning",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "attribute_text": "On-Campus Required",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "attribute_text": "Lab - Linked",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "attribute_text": "Quantitative Reasoning",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "attribute_text": "On-Campus Required",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "attribute_text": "Lab - Linked",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "attribute_text": "Quantitative Reasoning",
    "source_id": "00000007-0010-4000-8000-000000000002"
  }
]$fixture$::jsonb)
ON CONFLICT (section_id, attribute_text) DO UPDATE SET source_id = EXCLUDED.source_id;

INSERT INTO meeting (meeting_id, section_id, day_of_week, start_time, end_time, start_date, end_date, campus, building, room, location, meeting_type_text, source_status, source_id)
SELECT meeting_id, section_id, day_of_week, start_time, end_time, start_date, end_date, campus, building, room, location, meeting_type_text, source_status, source_id FROM jsonb_populate_recordset(NULL::meeting,
$fixture$[
  {
    "meeting_id": "00000007-0011-4000-8000-000000000001",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "day_of_week": 1,
    "start_time": "09:20",
    "end_time": "10:35",
    "start_date": null,
    "end_date": null,
    "campus": null,
    "building": null,
    "room": null,
    "location": null,
    "meeting_type_text": null,
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000002",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "day_of_week": 3,
    "start_time": "09:20",
    "end_time": "10:35",
    "start_date": "2027-01-25",
    "end_date": "2027-05-07",
    "campus": "Asa Packer Campus",
    "building": "019 Packard Lab",
    "room": "112",
    "location": "Asa Packer Campus | 019 Packard Lab | Room 112",
    "meeting_type_text": "Lab On-Campus Only",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000004"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000003",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "day_of_week": 5,
    "start_time": "09:20",
    "end_time": "10:35",
    "start_date": "2027-01-25",
    "end_date": "2027-05-07",
    "campus": "Asa Packer Campus",
    "building": "019 Packard Lab",
    "room": "112",
    "location": "Asa Packer Campus | 019 Packard Lab | Room 112",
    "meeting_type_text": "Lab On-Campus Only",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000004"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000004",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "day_of_week": 3,
    "start_time": "10:45",
    "end_time": "12:00",
    "start_date": null,
    "end_date": null,
    "campus": null,
    "building": null,
    "room": null,
    "location": null,
    "meeting_type_text": null,
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000005",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "day_of_week": 5,
    "start_time": "10:45",
    "end_time": "12:00",
    "start_date": null,
    "end_date": null,
    "campus": null,
    "building": null,
    "room": null,
    "location": null,
    "meeting_type_text": null,
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002"
  }
]$fixture$::jsonb)
ON CONFLICT (meeting_id) DO UPDATE SET section_id = EXCLUDED.section_id, day_of_week = EXCLUDED.day_of_week, start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time, start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date, campus = EXCLUDED.campus, building = EXCLUDED.building, room = EXCLUDED.room, location = EXCLUDED.location, meeting_type_text = EXCLUDED.meeting_type_text, source_status = EXCLUDED.source_status, source_id = EXCLUDED.source_id;

INSERT INTO section_link (link_id, primary_section_id, required_component_group, compatible_section_id, course_id, term_id, source_id, verification_status, evidence_note)
SELECT link_id, primary_section_id, required_component_group, compatible_section_id, course_id, term_id, source_id, verification_status, evidence_note FROM jsonb_populate_recordset(NULL::section_link,
$fixture$[
  {
    "link_id": "00000007-0004-4000-8000-001227513132",
    "primary_section_id": "00000007-0002-4000-8000-000000012275",
    "required_component_group": "LAB",
    "compatible_section_id": "00000007-0002-4000-8000-000000013132",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "source_id": "00000007-0010-4000-8000-000000000007",
    "verification_status": "PAIR_CONFIRMED",
    "evidence_note": "Lab 060 details list Option 1: Lecture 010, CRN 12275. Stored lecture-first for the data model. This confirms the pair, not the complete lecture-side component group."
  }
]$fixture$::jsonb)
ON CONFLICT (link_id) DO UPDATE SET primary_section_id = EXCLUDED.primary_section_id, required_component_group = EXCLUDED.required_component_group, compatible_section_id = EXCLUDED.compatible_section_id, course_id = EXCLUDED.course_id, term_id = EXCLUDED.term_id, source_id = EXCLUDED.source_id, verification_status = EXCLUDED.verification_status, evidence_note = EXCLUDED.evidence_note;

INSERT INTO enrollment_snapshot (enrollment_snapshot_id, section_id, enrollment_actual, enrollment_maximum, enrollment_seats_available, waitlist_capacity, waitlist_actual, waitlist_seats_available, is_live, source_id)
SELECT enrollment_snapshot_id, section_id, enrollment_actual, enrollment_maximum, enrollment_seats_available, waitlist_capacity, waitlist_actual, waitlist_seats_available, is_live, source_id FROM jsonb_populate_recordset(NULL::enrollment_snapshot,
$fixture$[
  {
    "enrollment_snapshot_id": "00000007-0013-4000-8000-000000013132",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "enrollment_actual": 0,
    "enrollment_maximum": 38,
    "enrollment_seats_available": 38,
    "waitlist_capacity": 0,
    "waitlist_actual": 0,
    "waitlist_seats_available": 0,
    "is_live": false,
    "source_id": "00000007-0010-4000-8000-000000000006"
  }
]$fixture$::jsonb)
ON CONFLICT (enrollment_snapshot_id) DO UPDATE SET section_id = EXCLUDED.section_id, enrollment_actual = EXCLUDED.enrollment_actual, enrollment_maximum = EXCLUDED.enrollment_maximum, enrollment_seats_available = EXCLUDED.enrollment_seats_available, waitlist_capacity = EXCLUDED.waitlist_capacity, waitlist_actual = EXCLUDED.waitlist_actual, waitlist_seats_available = EXCLUDED.waitlist_seats_available, is_live = EXCLUDED.is_live, source_id = EXCLUDED.source_id;

INSERT INTO data_issue (issue_id, section_id, code, field_name, severity, details, observations)
SELECT issue_id, section_id, code, field_name, severity, details, observations FROM jsonb_populate_recordset(NULL::data_issue,
$fixture$[
  {
    "issue_id": "00000007-0012-4000-8000-000000000001",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "code": "CREDITS_CONFLICT",
    "field_name": "credits",
    "severity": "WARNING",
    "details": "Results Hours is 0; detail Credit Hours is 4. Use 0 provisionally for the lab and retain NEEDS_REVIEW.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "label": "Hours",
        "value": 0
      },
      {
        "source_id": "00000007-0010-4000-8000-000000000003",
        "label": "Credit Hours",
        "value": 4
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000002",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "code": "LINK_TARGET_UNKNOWN",
    "field_name": "section_link",
    "severity": "WARNING",
    "details": "Search results mark 062 as linked, but its partner is not visible. Do not invent a 010/062 link.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "label": "Status",
        "value": "LINKED"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000003",
    "section_id": null,
    "code": "PREREQUISITES_UNKNOWN",
    "field_name": "prerequisite_status",
    "severity": "WARNING",
    "details": "No readable prerequisite, corequisite or restriction detail is supplied. The cropped panel cannot prove no requirements.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000005",
        "value": "Right-hand content cropped"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000004",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "code": "DELIVERY_TYPE_UNKNOWN",
    "field_name": "delivery_type",
    "severity": "WARNING",
    "details": "FLEX - Classroom does not unambiguously establish IN_PERSON, ONLINE or HYBRID in these screenshots.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "label": "Attribute",
        "value": "FLEX - Classroom"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000005",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "code": "MEETING_DETAILS_INCOMPLETE",
    "field_name": "meeting.location/date_range",
    "severity": "WARNING",
    "details": "Lecture time/day is visible; room, campus and meeting date range are not. These fields stay NULL.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "value": "Monday 09:20-10:35"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000006",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "code": "MEETING_DETAILS_INCOMPLETE",
    "field_name": "meeting.location/date_range",
    "severity": "WARNING",
    "details": "Lab 062 time/days are visible; location and date range are not. Do not copy lab 060 details.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "value": "Wednesday/Friday 10:45-12:00"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000007",
    "section_id": null,
    "code": "AVAILABILITY_SNAPSHOT_ONLY",
    "field_name": "enrollment_snapshot",
    "severity": "INFO",
    "details": "Only lab 060 has a fully readable enrollment panel. Counts are static observations, not live availability. Other sections retain truncated raw labels only.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000006",
        "value": "0 enrolled; maximum 38; 38 available in screenshot"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000008",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "code": "COMPONENT_GROUP_INCOMPLETE",
    "field_name": "section_link",
    "severity": "WARNING",
    "details": "The 060 detail confirms the 010/060 pair; lecture-side required-component alternatives and the role of 062 are not fully shown.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000007",
        "value": "Lab 060 -> Option 1 -> Lecture 010 CRN 12275"
      }
    ]
  }
]$fixture$::jsonb)
ON CONFLICT (issue_id) DO UPDATE SET section_id = EXCLUDED.section_id, code = EXCLUDED.code, field_name = EXCLUDED.field_name, severity = EXCLUDED.severity, details = EXCLUDED.details, observations = EXCLUDED.observations;

-- Upgrade the existing screenshot schema while retaining original observations.
SET LOCAL search_path TO cse007_snapshot;

ALTER TABLE source_asset DROP CONSTRAINT source_asset_source_kind_check;
ALTER TABLE source_asset ADD CHECK (source_kind IN ('USER_SCREENSHOT','USER_CLARIFICATION'));
ALTER TABLE course DROP CONSTRAINT course_prerequisite_status_check;
ALTER TABLE course ADD CHECK (prerequisite_status IN ('UNKNOWN','VERIFIED','USER_CONFIRMED_NONE'));
ALTER TABLE course ADD COLUMN clarification_source_id UUID REFERENCES source_asset ON DELETE RESTRICT;

ALTER TABLE section DROP CONSTRAINT section_credits_verification_status_check;
ALTER TABLE section ADD CHECK (credits_verification_status IN ('TRANSCRIBED','NEEDS_REVIEW','USER_CONFIRMED'));
ALTER TABLE section DROP CONSTRAINT section_link_status_check;
ALTER TABLE section ADD CHECK (link_status IN ('PAIR_CONFIRMED','TARGET_UNKNOWN','GROUP_CONFIRMED'));
ALTER TABLE section ADD COLUMN clarification_source_id UUID REFERENCES source_asset ON DELETE RESTRICT;

ALTER TABLE meeting ADD COLUMN date_basis TEXT NOT NULL DEFAULT 'UNKNOWN'
	CHECK (date_basis IN ('UNKNOWN','SCREENSHOT','USER_CLARIFICATION','DEMO_TERM_DEFAULT'));
ALTER TABLE meeting ADD COLUMN clarification_source_id UUID REFERENCES source_asset ON DELETE RESTRICT;

CREATE TABLE course_requirement (
	requirement_id UUID PRIMARY KEY,
	course_id UUID NOT NULL REFERENCES course ON DELETE RESTRICT,
	requirement_type TEXT NOT NULL CHECK (requirement_type IN ('PREREQUISITE','COREQUISITE','ENROLLMENT_RESTRICTION')),
	requirement_expression JSONB,
	assessment_status TEXT NOT NULL CHECK (assessment_status IN ('UNKNOWN','USER_CONFIRMED_NONE','RULE_DEFINED')),
	source_id UUID REFERENCES source_asset ON DELETE RESTRICT,
	UNIQUE (course_id, requirement_type),
	CHECK ((assessment_status IN ('UNKNOWN','USER_CONFIRMED_NONE') AND requirement_expression IS NULL)
		OR (assessment_status = 'RULE_DEFINED' AND requirement_expression IS NOT NULL
			AND jsonb_typeof(requirement_expression) = 'object'
			AND requirement_expression ? 'op')),
	CHECK (assessment_status = 'UNKNOWN' OR source_id IS NOT NULL)
);

CREATE TABLE section_component_group (
	primary_section_id UUID NOT NULL REFERENCES section ON DELETE RESTRICT,
	required_component_group TEXT NOT NULL,
	required_count SMALLINT,
	assessment_status TEXT NOT NULL CHECK (assessment_status IN ('UNKNOWN','USER_CONFIRMED')),
	source_id UUID NOT NULL REFERENCES source_asset ON DELETE RESTRICT,
	PRIMARY KEY (primary_section_id, required_component_group),
	CHECK ((assessment_status = 'UNKNOWN' AND required_count IS NULL)
		OR (assessment_status = 'USER_CONFIRMED' AND required_count = 1 AND required_count IS NOT NULL))
);
-- Preserve existing pair evidence until V4 supplies the confirmed group rule.
INSERT INTO section_component_group
	(primary_section_id, required_component_group, required_count, assessment_status, source_id)
SELECT DISTINCT ON (primary_section_id, required_component_group)
	primary_section_id, required_component_group, NULL, 'UNKNOWN', source_id
FROM section_link ORDER BY primary_section_id, required_component_group, link_id;
ALTER TABLE section_link ADD FOREIGN KEY (primary_section_id, required_component_group)
	REFERENCES section_component_group ON DELETE RESTRICT;
ALTER TABLE section_link DROP CONSTRAINT section_link_verification_status_check;
ALTER TABLE section_link ADD CHECK (verification_status IN ('PAIR_CONFIRMED','USER_CONFIRMED'));

ALTER TABLE data_issue ADD COLUMN status TEXT NOT NULL DEFAULT 'OPEN'
	CHECK (status IN ('OPEN','RESOLVED','ACCEPTED'));
ALTER TABLE data_issue ADD COLUMN resolution TEXT;
ALTER TABLE data_issue ADD COLUMN resolution_source_id UUID REFERENCES source_asset ON DELETE RESTRICT;
ALTER TABLE data_issue ADD CHECK (status = 'OPEN' OR (resolution IS NOT NULL AND resolution_source_id IS NOT NULL));

COMMENT ON TABLE course_requirement IS 'NULL with USER_CONFIRMED_NONE means no requirement per user; NULL with UNKNOWN means not assessed. JSON expression trees can hold future course rules.';
COMMENT ON TABLE section_component_group IS 'Choose required_count alternatives from section_link for this primary section/group. Current demo requires exactly one LAB.';
COMMENT ON COLUMN meeting.date_basis IS 'Distinguishes observed dates from a user clarification or a demo term-date default.';

-- Generated from fixture.json by build_seed.py. Do not edit by hand.

-- Stable UUID upserts; apply in a transaction together with verify.sql.

SET LOCAL search_path TO cse007_snapshot;

INSERT INTO source_asset (source_id, file_path, sha256, source_kind, source_url, captured_at, verified_at, coverage_note)
SELECT source_id, file_path, sha256, source_kind, source_url, captured_at, verified_at, coverage_note FROM jsonb_populate_recordset(NULL::source_asset,
$fixture$[
  {
    "source_id": "00000007-0010-4000-8000-000000000001",
    "file_path": "Images for courses/KakaoTalk_20261015_214742198.png",
    "sha256": "82ab5c5e510a612494491df74ee6c49bdd73bdccd0cd3c2f6a1028a57c992bf8",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Search filters: CSE 007, 2027 Spring Semester."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000002",
    "file_path": "Images for courses/KakaoTalk_20261015_214757090.png",
    "sha256": "cde35620c69afc240187fb225a13273c5e65702d9648adc3b8fc827edeb035b3",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Three section rows: CRNs, section codes, Hours, highlighted meeting days, times, instructor, attributes, LINKED flags. Status text is truncated."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000003",
    "file_path": "Images for courses/KakaoTalk_20261015_214823618.png",
    "sha256": "808b265df0473bdbe6de0f1961050a28f23631794612effec4d4d4cb36b3c805",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060 details: term code 202710, CRN 13132, title, campus and grade-mode text. Credit Hours 4 conflicts with the results-row Hours 0."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000004",
    "file_path": "Images for courses/KakaoTalk_20261015_214850130.png",
    "sha256": "e4dddeff2f2034bc784d8ca6023d09e8b673b50bc09f3953c32c9c3a641c8b36",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060: Wednesday/Friday 09:20-10:35, 2027-01-25 through 2027-05-07, Packard Lab room 112, Brian Chen."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000005",
    "file_path": "Images for courses/KakaoTalk_20261015_214926225.png",
    "sha256": "ac566a235249b04908a4cdd1682368b59562e05fa38533d18b4cf8d500ae08fb",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Cropped detail panel: right-hand content is unreadable. Does not establish prerequisite or other requirement rules."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000006",
    "file_path": "Images for courses/KakaoTalk_20261015_214949427.png",
    "sha256": "dabb4450850594fd78e2619e4d0e6e2e4dd0a837a4042e04b43f751abba93539",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060 enrollment/waitlist snapshot: actual 0, maximum 38, available 38; waitlist values 0. Capture time and current availability unknown."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000007",
    "file_path": "Images for courses/KakaoTalk_20261015_215023730.png",
    "sha256": "5a8265eb9f0873ec01df2af7127779b7330d5697f141303774c374d0d427581c",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060 Linked Sections, Option 1: lecture section 010, CRN 12275. No lab 062 linked-section detail is provided."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000008",
    "file_path": "Hackathon Ideas - SuY.pdf",
    "sha256": "0672a3d1ac88d7f6b6fec8a6a9104f912de2a9ad9f0900cd9e0e4fa744b572c8",
    "source_kind": "USER_CLARIFICATION",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T02:44:35Z",
    "coverage_note": "User corrections, pages 1-3: labs 0 credits; no CSE 007 prerequisites/corequisites/restrictions; in-person demo; lecture location TBD; lab 062 Packard room 11 and dates; seats out of scope; description. Final bold statement on page 3 confirms lecture 010 pairs with either 060 or 062, superseding the earlier narrower statement."
  }
]$fixture$::jsonb)
ON CONFLICT (source_id) DO UPDATE SET file_path = EXCLUDED.file_path, sha256 = EXCLUDED.sha256, source_kind = EXCLUDED.source_kind, source_url = EXCLUDED.source_url, captured_at = EXCLUDED.captured_at, verified_at = EXCLUDED.verified_at, coverage_note = EXCLUDED.coverage_note;

INSERT INTO course (course_id, code, subject, course_number, title, description, credits, prerequisite_status, source_id, clarification_source_id)
SELECT course_id, code, subject, course_number, title, description, credits, prerequisite_status, source_id, clarification_source_id FROM jsonb_populate_recordset(NULL::course,
$fixture$[
  {
    "course_id": "00000007-0000-4000-8000-000000000001",
    "code": "CSE-007",
    "subject": "CSE",
    "course_number": "007",
    "title": "Introduction to Programming",
    "description": "Introduction to problem-solving and object-oriented programming (OOP) using the Java language. Covers data types, control flow, methods, arrays (including searching & sorting), basics of OOP including data encapsulation, inheritance and polymorphism and a breadth of computing. If credit is given for CSE 007 then no credit will be given for CSE 003 nor CSE 004.",
    "credits": 4,
    "prerequisite_status": "USER_CONFIRMED_NONE",
    "source_id": "00000007-0010-4000-8000-000000000003",
    "clarification_source_id": "00000007-0010-4000-8000-000000000008"
  }
]$fixture$::jsonb)
ON CONFLICT (course_id) DO UPDATE SET code = EXCLUDED.code, subject = EXCLUDED.subject, course_number = EXCLUDED.course_number, title = EXCLUDED.title, description = EXCLUDED.description, credits = EXCLUDED.credits, prerequisite_status = EXCLUDED.prerequisite_status, source_id = EXCLUDED.source_id, clarification_source_id = EXCLUDED.clarification_source_id;

INSERT INTO term (term_id, term_code, name, academic_year, source_id)
SELECT term_id, term_code, name, academic_year, source_id FROM jsonb_populate_recordset(NULL::term,
$fixture$[
  {
    "term_id": "00000007-0001-4000-8000-000000202710",
    "term_code": "202710",
    "name": "2027 Spring Semester",
    "academic_year": null,
    "source_id": "00000007-0010-4000-8000-000000000003"
  }
]$fixture$::jsonb)
ON CONFLICT (term_id) DO UPDATE SET term_code = EXCLUDED.term_code, name = EXCLUDED.name, academic_year = EXCLUDED.academic_year, source_id = EXCLUDED.source_id;

INSERT INTO section (section_id, course_id, term_id, crn, section_code, component_type, instructor, instructor_is_primary, credits, credits_verification_status, delivery_type, meeting_status, link_status, campus_text, grade_mode_text, search_status_text, source_status, source_id, detail_source_id, clarification_source_id)
SELECT section_id, course_id, term_id, crn, section_code, component_type, instructor, instructor_is_primary, credits, credits_verification_status, delivery_type, meeting_status, link_status, campus_text, grade_mode_text, search_status_text, source_status, source_id, detail_source_id, clarification_source_id FROM jsonb_populate_recordset(NULL::section,
$fixture$[
  {
    "section_id": "00000007-0002-4000-8000-000000012275",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "crn": "12275",
    "section_code": "010",
    "component_type": "LECTURE",
    "instructor": "Chen, Brian",
    "instructor_is_primary": true,
    "credits": 4,
    "credits_verification_status": "USER_CONFIRMED",
    "delivery_type": "IN_PERSON",
    "meeting_status": "SCHEDULED",
    "link_status": "GROUP_CONFIRMED",
    "campus_text": null,
    "grade_mode_text": null,
    "search_status_text": "70 of 70 seats...",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "detail_source_id": null,
    "clarification_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "crn": "13132",
    "section_code": "060",
    "component_type": "LAB",
    "instructor": "Chen, Brian",
    "instructor_is_primary": true,
    "credits": 0,
    "credits_verification_status": "USER_CONFIRMED",
    "delivery_type": "IN_PERSON",
    "meeting_status": "SCHEDULED",
    "link_status": "PAIR_CONFIRMED",
    "campus_text": "Engineering&App Sci On Campus",
    "grade_mode_text": "No Section specified grade mode, please see Catalog link below for more information.",
    "search_status_text": "38 of 38 seats...",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "detail_source_id": "00000007-0010-4000-8000-000000000003",
    "clarification_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "crn": "12638",
    "section_code": "062",
    "component_type": "LAB",
    "instructor": "Chen, Brian",
    "instructor_is_primary": true,
    "credits": 0,
    "credits_verification_status": "USER_CONFIRMED",
    "delivery_type": "IN_PERSON",
    "meeting_status": "SCHEDULED",
    "link_status": "PAIR_CONFIRMED",
    "campus_text": null,
    "grade_mode_text": null,
    "search_status_text": "37 of 37 seats...",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "detail_source_id": null,
    "clarification_source_id": "00000007-0010-4000-8000-000000000008"
  }
]$fixture$::jsonb)
ON CONFLICT (section_id) DO UPDATE SET course_id = EXCLUDED.course_id, term_id = EXCLUDED.term_id, crn = EXCLUDED.crn, section_code = EXCLUDED.section_code, component_type = EXCLUDED.component_type, instructor = EXCLUDED.instructor, instructor_is_primary = EXCLUDED.instructor_is_primary, credits = EXCLUDED.credits, credits_verification_status = EXCLUDED.credits_verification_status, delivery_type = EXCLUDED.delivery_type, meeting_status = EXCLUDED.meeting_status, link_status = EXCLUDED.link_status, campus_text = EXCLUDED.campus_text, grade_mode_text = EXCLUDED.grade_mode_text, search_status_text = EXCLUDED.search_status_text, source_status = EXCLUDED.source_status, source_id = EXCLUDED.source_id, detail_source_id = EXCLUDED.detail_source_id, clarification_source_id = EXCLUDED.clarification_source_id;

INSERT INTO section_attribute (section_id, attribute_text, source_id)
SELECT section_id, attribute_text, source_id FROM jsonb_populate_recordset(NULL::section_attribute,
$fixture$[
  {
    "section_id": "00000007-0002-4000-8000-000000012275",
    "attribute_text": "FLEX - Classroom",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012275",
    "attribute_text": "Quantitative Reasoning",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "attribute_text": "On-Campus Required",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "attribute_text": "Lab - Linked",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "attribute_text": "Quantitative Reasoning",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "attribute_text": "On-Campus Required",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "attribute_text": "Lab - Linked",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "attribute_text": "Quantitative Reasoning",
    "source_id": "00000007-0010-4000-8000-000000000002"
  }
]$fixture$::jsonb)
ON CONFLICT (section_id, attribute_text) DO UPDATE SET source_id = EXCLUDED.source_id;

INSERT INTO meeting (meeting_id, section_id, day_of_week, start_time, end_time, start_date, end_date, campus, building, room, location, meeting_type_text, source_status, source_id, date_basis, clarification_source_id)
SELECT meeting_id, section_id, day_of_week, start_time, end_time, start_date, end_date, campus, building, room, location, meeting_type_text, source_status, source_id, date_basis, clarification_source_id FROM jsonb_populate_recordset(NULL::meeting,
$fixture$[
  {
    "meeting_id": "00000007-0011-4000-8000-000000000001",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "day_of_week": 1,
    "start_time": "09:20",
    "end_time": "10:35",
    "start_date": "2027-01-25",
    "end_date": "2027-05-07",
    "campus": null,
    "building": null,
    "room": null,
    "location": "TBD",
    "meeting_type_text": null,
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "date_basis": "DEMO_TERM_DEFAULT",
    "clarification_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000002",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "day_of_week": 3,
    "start_time": "09:20",
    "end_time": "10:35",
    "start_date": "2027-01-25",
    "end_date": "2027-05-07",
    "campus": "Asa Packer Campus",
    "building": "019 Packard Lab",
    "room": "112",
    "location": "Asa Packer Campus | 019 Packard Lab | Room 112",
    "meeting_type_text": "Lab On-Campus Only",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000004",
    "date_basis": "SCREENSHOT",
    "clarification_source_id": null
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000003",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "day_of_week": 5,
    "start_time": "09:20",
    "end_time": "10:35",
    "start_date": "2027-01-25",
    "end_date": "2027-05-07",
    "campus": "Asa Packer Campus",
    "building": "019 Packard Lab",
    "room": "112",
    "location": "Asa Packer Campus | 019 Packard Lab | Room 112",
    "meeting_type_text": "Lab On-Campus Only",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000004",
    "date_basis": "SCREENSHOT",
    "clarification_source_id": null
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000004",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "day_of_week": 3,
    "start_time": "10:45",
    "end_time": "12:00",
    "start_date": "2027-01-25",
    "end_date": "2027-05-07",
    "campus": "Asa Packer Campus",
    "building": "019 Packard Lab",
    "room": "11",
    "location": "Asa Packer Campus | 019 Packard Lab | Room 11",
    "meeting_type_text": "Lab On-Campus Only",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "date_basis": "USER_CLARIFICATION",
    "clarification_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000005",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "day_of_week": 5,
    "start_time": "10:45",
    "end_time": "12:00",
    "start_date": "2027-01-25",
    "end_date": "2027-05-07",
    "campus": "Asa Packer Campus",
    "building": "019 Packard Lab",
    "room": "11",
    "location": "Asa Packer Campus | 019 Packard Lab | Room 11",
    "meeting_type_text": "Lab On-Campus Only",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "date_basis": "USER_CLARIFICATION",
    "clarification_source_id": "00000007-0010-4000-8000-000000000008"
  }
]$fixture$::jsonb)
ON CONFLICT (meeting_id) DO UPDATE SET section_id = EXCLUDED.section_id, day_of_week = EXCLUDED.day_of_week, start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time, start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date, campus = EXCLUDED.campus, building = EXCLUDED.building, room = EXCLUDED.room, location = EXCLUDED.location, meeting_type_text = EXCLUDED.meeting_type_text, source_status = EXCLUDED.source_status, source_id = EXCLUDED.source_id, date_basis = EXCLUDED.date_basis, clarification_source_id = EXCLUDED.clarification_source_id;

INSERT INTO course_requirement (requirement_id, course_id, requirement_type, requirement_expression, assessment_status, source_id)
SELECT requirement_id, course_id, requirement_type, requirement_expression, assessment_status, source_id FROM jsonb_populate_recordset(NULL::course_requirement,
$fixture$[
  {
    "requirement_id": "00000007-0014-4000-8000-000000000001",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "requirement_type": "PREREQUISITE",
    "requirement_expression": null,
    "assessment_status": "USER_CONFIRMED_NONE",
    "source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "requirement_id": "00000007-0014-4000-8000-000000000002",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "requirement_type": "COREQUISITE",
    "requirement_expression": null,
    "assessment_status": "USER_CONFIRMED_NONE",
    "source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "requirement_id": "00000007-0014-4000-8000-000000000003",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "requirement_type": "ENROLLMENT_RESTRICTION",
    "requirement_expression": null,
    "assessment_status": "USER_CONFIRMED_NONE",
    "source_id": "00000007-0010-4000-8000-000000000008"
  }
]$fixture$::jsonb)
ON CONFLICT (requirement_id) DO UPDATE SET course_id = EXCLUDED.course_id, requirement_type = EXCLUDED.requirement_type, requirement_expression = EXCLUDED.requirement_expression, assessment_status = EXCLUDED.assessment_status, source_id = EXCLUDED.source_id;

INSERT INTO section_component_group (primary_section_id, required_component_group, required_count, assessment_status, source_id)
SELECT primary_section_id, required_component_group, required_count, assessment_status, source_id FROM jsonb_populate_recordset(NULL::section_component_group,
$fixture$[
  {
    "primary_section_id": "00000007-0002-4000-8000-000000012275",
    "required_component_group": "LAB",
    "required_count": 1,
    "assessment_status": "USER_CONFIRMED",
    "source_id": "00000007-0010-4000-8000-000000000008"
  }
]$fixture$::jsonb)
ON CONFLICT (primary_section_id, required_component_group) DO UPDATE SET required_count = EXCLUDED.required_count, assessment_status = EXCLUDED.assessment_status, source_id = EXCLUDED.source_id;

INSERT INTO section_link (link_id, primary_section_id, required_component_group, compatible_section_id, course_id, term_id, source_id, verification_status, evidence_note)
SELECT link_id, primary_section_id, required_component_group, compatible_section_id, course_id, term_id, source_id, verification_status, evidence_note FROM jsonb_populate_recordset(NULL::section_link,
$fixture$[
  {
    "link_id": "00000007-0004-4000-8000-001227513132",
    "primary_section_id": "00000007-0002-4000-8000-000000012275",
    "required_component_group": "LAB",
    "compatible_section_id": "00000007-0002-4000-8000-000000013132",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "source_id": "00000007-0010-4000-8000-000000000008",
    "verification_status": "USER_CONFIRMED",
    "evidence_note": "Screenshot confirms the 010/060 pair; SuY page 3 final bold statement confirms the full group: choose either 060 or 062."
  },
  {
    "link_id": "00000007-0004-4000-8000-001227512638",
    "primary_section_id": "00000007-0002-4000-8000-000000012275",
    "required_component_group": "LAB",
    "compatible_section_id": "00000007-0002-4000-8000-000000012638",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "source_id": "00000007-0010-4000-8000-000000000008",
    "verification_status": "USER_CONFIRMED",
    "evidence_note": "SuY page 3 final bold statement: 010 can be paired either 060 or 062. Choose exactly one lab."
  }
]$fixture$::jsonb)
ON CONFLICT (link_id) DO UPDATE SET primary_section_id = EXCLUDED.primary_section_id, required_component_group = EXCLUDED.required_component_group, compatible_section_id = EXCLUDED.compatible_section_id, course_id = EXCLUDED.course_id, term_id = EXCLUDED.term_id, source_id = EXCLUDED.source_id, verification_status = EXCLUDED.verification_status, evidence_note = EXCLUDED.evidence_note;

INSERT INTO enrollment_snapshot (enrollment_snapshot_id, section_id, enrollment_actual, enrollment_maximum, enrollment_seats_available, waitlist_capacity, waitlist_actual, waitlist_seats_available, is_live, source_id)
SELECT enrollment_snapshot_id, section_id, enrollment_actual, enrollment_maximum, enrollment_seats_available, waitlist_capacity, waitlist_actual, waitlist_seats_available, is_live, source_id FROM jsonb_populate_recordset(NULL::enrollment_snapshot,
$fixture$[
  {
    "enrollment_snapshot_id": "00000007-0013-4000-8000-000000013132",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "enrollment_actual": 0,
    "enrollment_maximum": 38,
    "enrollment_seats_available": 38,
    "waitlist_capacity": 0,
    "waitlist_actual": 0,
    "waitlist_seats_available": 0,
    "is_live": false,
    "source_id": "00000007-0010-4000-8000-000000000006"
  }
]$fixture$::jsonb)
ON CONFLICT (enrollment_snapshot_id) DO UPDATE SET section_id = EXCLUDED.section_id, enrollment_actual = EXCLUDED.enrollment_actual, enrollment_maximum = EXCLUDED.enrollment_maximum, enrollment_seats_available = EXCLUDED.enrollment_seats_available, waitlist_capacity = EXCLUDED.waitlist_capacity, waitlist_actual = EXCLUDED.waitlist_actual, waitlist_seats_available = EXCLUDED.waitlist_seats_available, is_live = EXCLUDED.is_live, source_id = EXCLUDED.source_id;

INSERT INTO data_issue (issue_id, section_id, code, field_name, severity, details, observations, status, resolution, resolution_source_id)
SELECT issue_id, section_id, code, field_name, severity, details, observations, status, resolution, resolution_source_id FROM jsonb_populate_recordset(NULL::data_issue,
$fixture$[
  {
    "issue_id": "00000007-0012-4000-8000-000000000001",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "code": "CREDITS_CONFLICT",
    "field_name": "credits",
    "severity": "WARNING",
    "details": "Results Hours is 0; detail Credit Hours is 4. Use 0 provisionally for the lab and retain NEEDS_REVIEW.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "label": "Hours",
        "value": 0
      },
      {
        "source_id": "00000007-0010-4000-8000-000000000003",
        "label": "Credit Hours",
        "value": 4
      }
    ],
    "status": "RESOLVED",
    "resolution": "SuY page 1: use section-list Hours 0 for lab 060; detail 4 is the course credit value. Preserve both raw observations.",
    "resolution_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000002",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "code": "LINK_TARGET_UNKNOWN",
    "field_name": "section_link",
    "severity": "WARNING",
    "details": "Search results mark 062 as linked, but its partner is not visible. Do not invent a 010/062 link.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "label": "Status",
        "value": "LINKED"
      }
    ],
    "status": "RESOLVED",
    "resolution": "SuY page 3 final bold clarification explicitly permits 010 with 062.",
    "resolution_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000003",
    "section_id": null,
    "code": "PREREQUISITES_UNKNOWN",
    "field_name": "prerequisite_status",
    "severity": "WARNING",
    "details": "No readable prerequisite, corequisite or restriction detail is supplied. The cropped panel cannot prove no requirements.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000005",
        "value": "Right-hand content cropped"
      }
    ],
    "status": "RESOLVED",
    "resolution": "SuY page 1 confirms no prerequisite, corequisite or enrollment restriction for CSE 007. Three NULL requirement rows are marked USER_CONFIRMED_NONE.",
    "resolution_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000004",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "code": "DELIVERY_TYPE_UNKNOWN",
    "field_name": "delivery_type",
    "severity": "WARNING",
    "details": "FLEX - Classroom does not unambiguously establish IN_PERSON, ONLINE or HYBRID in these screenshots.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "label": "Attribute",
        "value": "FLEX - Classroom"
      }
    ],
    "status": "RESOLVED",
    "resolution": "SuY page 1 defines FLEX - Classroom as in-person for this demo. ONLINE and HYBRID remain supported enum values for future data.",
    "resolution_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000005",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "code": "MEETING_DETAILS_INCOMPLETE",
    "field_name": "meeting.location/date_range",
    "severity": "WARNING",
    "details": "Lecture time/day is visible; room, campus and meeting date range are not. These fields stay NULL.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "value": "Monday 09:20-10:35"
      }
    ],
    "status": "ACCEPTED",
    "resolution": "SuY page 2: lecture location remains TBD. Apply the supplied Jan 25-May 7 term range as an explicit demo default (DEMO_TERM_DEFAULT), not a lecture-specific observation.",
    "resolution_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000006",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "code": "MEETING_DETAILS_INCOMPLETE",
    "field_name": "meeting.location/date_range",
    "severity": "WARNING",
    "details": "Lab 062 time/days are visible; location and date range are not. Do not copy lab 060 details.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "value": "Wednesday/Friday 10:45-12:00"
      }
    ],
    "status": "RESOLVED",
    "resolution": "SuY page 2 supplies lab 062 at Asa Packer Campus, 019 Packard Lab, Room 11, Jan 25-May 7, 2027. Preserve room 11 exactly as supplied.",
    "resolution_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000007",
    "section_id": null,
    "code": "AVAILABILITY_SNAPSHOT_ONLY",
    "field_name": "enrollment_snapshot",
    "severity": "INFO",
    "details": "Only lab 060 has a fully readable enrollment panel. Counts are static observations, not live availability. Other sections retain truncated raw labels only.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000006",
        "value": "0 enrolled; maximum 38; 38 available in screenshot"
      }
    ],
    "status": "ACCEPTED",
    "resolution": "SuY page 3 excludes precise/current seat availability from demo scope. Keep non-live snapshot, but do not block scheduling on it.",
    "resolution_source_id": "00000007-0010-4000-8000-000000000008"
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000008",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "code": "COMPONENT_GROUP_INCOMPLETE",
    "field_name": "section_link",
    "severity": "WARNING",
    "details": "The 060 detail confirms the 010/060 pair; lecture-side required-component alternatives and the role of 062 are not fully shown.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000007",
        "value": "Lab 060 -> Option 1 -> Lecture 010 CRN 12275"
      }
    ],
    "status": "RESOLVED",
    "resolution": "Use SuY page 3 final bold statement over the earlier contradictory line: lecture 010 requires exactly one of labs 060 or 062.",
    "resolution_source_id": "00000007-0010-4000-8000-000000000008"
  }
]$fixture$::jsonb)
ON CONFLICT (issue_id) DO UPDATE SET section_id = EXCLUDED.section_id, code = EXCLUDED.code, field_name = EXCLUDED.field_name, severity = EXCLUDED.severity, details = EXCLUDED.details, observations = EXCLUDED.observations, status = EXCLUDED.status, resolution = EXCLUDED.resolution, resolution_source_id = EXCLUDED.resolution_source_id;

SET search_path TO cse007_snapshot;
DO $$
DECLARE
	total_credits numeric;
	confirmed_pairs integer;
	after_ten integer;
	friday_free integer;
BEGIN
	IF (SELECT count(*) FROM source_asset) <> 8
		OR (SELECT count(*) FROM course) <> 1
		OR (SELECT count(*) FROM term) <> 1
		OR (SELECT count(*) FROM section) <> 3
		OR (SELECT count(*) FROM meeting) <> 5
		OR (SELECT count(*) FROM section_link) <> 2
		OR (SELECT count(*) FROM section_component_group) <> 1
		OR (SELECT count(*) FROM course_requirement) <> 3
		OR (SELECT count(*) FROM section_attribute) <> 8
		OR (SELECT count(*) FROM enrollment_snapshot) <> 1
		OR (SELECT count(*) FROM data_issue) <> 8 THEN
		RAISE EXCEPTION 'Unexpected screenshot dataset counts';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM course WHERE code = 'CSE-007' AND course_number = '007'
		AND title = 'Introduction to Programming' AND credits = 4 AND prerequisite_status = 'USER_CONFIRMED_NONE'
		AND description LIKE 'Introduction to problem-solving%'
		AND description LIKE '%CSE 003 nor CSE 004.')
		OR NOT EXISTS (SELECT 1 FROM term WHERE term_code = '202710' AND name = '2027 Spring Semester') THEN
		RAISE EXCEPTION 'Course/term does not match screenshots';
	END IF;
	IF (SELECT array_agg(crn ORDER BY crn) FROM section) IS DISTINCT FROM ARRAY['12275','12638','13132']::varchar[]
		OR EXISTS (SELECT 1 FROM section WHERE instructor IS DISTINCT FROM 'Chen, Brian'
			OR source_status <> 'HISTORICAL' OR NOT instructor_is_primary) THEN
		RAISE EXCEPTION 'Section identifiers/instructor/provenance mismatch';
	END IF;
	IF (SELECT array_agg(s.section_code || ':' || m.day_of_week || ':' ||
		to_char(m.start_time,'HH24:MI') || '-' || to_char(m.end_time,'HH24:MI')
		ORDER BY s.section_code, m.day_of_week)
		FROM meeting m JOIN section s USING (section_id)) IS DISTINCT FROM
		ARRAY['010:1:09:20-10:35','060:3:09:20-10:35','060:5:09:20-10:35',
			'062:3:10:45-12:00','062:5:10:45-12:00'] THEN
		RAISE EXCEPTION 'Meeting transcription mismatch';
	END IF;
	IF EXISTS (SELECT 1 FROM meeting m JOIN section s USING (section_id)
		WHERE m.start_date IS DISTINCT FROM DATE '2027-01-25' OR m.end_date IS DISTINCT FROM DATE '2027-05-07'
		OR (s.section_code = '060' AND (m.building IS DISTINCT FROM '019 Packard Lab'
			OR m.room IS DISTINCT FROM '112' OR m.date_basis <> 'SCREENSHOT'))
		OR (s.section_code = '062' AND (m.building IS DISTINCT FROM '019 Packard Lab'
			OR m.room IS DISTINCT FROM '11' OR m.date_basis <> 'USER_CLARIFICATION'))
		OR (s.section_code = '010' AND (m.location IS DISTINCT FROM 'TBD' OR m.building IS NOT NULL
			OR m.room IS NOT NULL OR m.date_basis <> 'DEMO_TERM_DEFAULT'))) THEN
		RAISE EXCEPTION 'Clarified meeting details or date provenance mismatch';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM section WHERE section_code = '060' AND credits = 0
		AND credits_verification_status = 'USER_CONFIRMED')
		OR NOT EXISTS (SELECT 1 FROM data_issue WHERE code = 'CREDITS_CONFLICT'
			AND observations @> '[{"label":"Hours","value":0},{"label":"Credit Hours","value":4}]'::jsonb AND status = 'RESOLVED')
		OR NOT EXISTS (SELECT 1 FROM section WHERE section_code = '010' AND credits = 4 AND link_status = 'GROUP_CONFIRMED')
		OR NOT EXISTS (SELECT 1 FROM section WHERE section_code = '062' AND credits = 0 AND link_status = 'PAIR_CONFIRMED')
		OR EXISTS (SELECT 1 FROM section WHERE delivery_type <> 'IN_PERSON' OR clarification_source_id IS NULL) THEN
		RAISE EXCEPTION 'User corrections or original credit evidence were lost';
	END IF;
	IF EXISTS (SELECT 1 FROM data_issue WHERE status = 'OPEN' OR resolution_source_id IS NULL)
		OR (SELECT count(*) FROM data_issue WHERE status = 'RESOLVED') <> 6
		OR (SELECT count(*) FROM data_issue WHERE status = 'ACCEPTED') <> 2 THEN
		RAISE EXCEPTION 'Resolved/accepted issues are still presented as open';
	END IF;
	IF (SELECT array_agg(requirement_type ORDER BY requirement_type) FROM course_requirement)
		IS DISTINCT FROM ARRAY['COREQUISITE','ENROLLMENT_RESTRICTION','PREREQUISITE']
		OR EXISTS (SELECT 1 FROM course_requirement WHERE requirement_expression IS NOT NULL
			OR assessment_status <> 'USER_CONFIRMED_NONE' OR source_id IS NULL) THEN
		RAISE EXCEPTION 'No-requirement rows must be explicit NULLs with user-confirmed status';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM section_component_group g JOIN section s ON s.section_id = g.primary_section_id
		WHERE s.section_code = '010' AND g.required_component_group = 'LAB'
		AND g.required_count = 1 AND g.assessment_status = 'USER_CONFIRMED') THEN
		RAISE EXCEPTION 'Missing exactly-one LAB rule';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM section_link l JOIN section a ON a.section_id = l.primary_section_id
		JOIN section b ON b.section_id = l.compatible_section_id
		WHERE a.crn = '12275' AND b.crn = '13132' AND a.component_type = 'LECTURE'
		AND b.component_type = 'LAB' AND l.verification_status = 'USER_CONFIRMED') THEN
		RAISE EXCEPTION 'Linked-section screenshot was not preserved';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM section_link l JOIN section a ON a.section_id = l.primary_section_id
		JOIN section b ON b.section_id = l.compatible_section_id
		WHERE a.crn = '12275' AND b.crn = '12638' AND l.verification_status = 'USER_CONFIRMED') THEN
		RAISE EXCEPTION 'The final SuY clarification allowing 062 was not applied';
	END IF;
	SELECT count(*), min(a.credits+b.credits),
		count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM meeting m
			WHERE m.section_id IN (a.section_id,b.section_id) AND m.start_time < TIME '10:00')),
		count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM meeting m
			WHERE m.section_id IN (a.section_id,b.section_id) AND m.day_of_week = 5))
	INTO confirmed_pairs, total_credits, after_ten, friday_free
	FROM section_link l JOIN section a ON a.section_id = l.primary_section_id
	JOIN section b ON b.section_id = l.compatible_section_id
	WHERE NOT EXISTS (SELECT 1 FROM meeting ma JOIN meeting mb ON ma.day_of_week = mb.day_of_week
		AND ma.start_time < mb.end_time AND mb.start_time < ma.end_time
		WHERE ma.section_id = a.section_id AND mb.section_id = b.section_id);
	IF confirmed_pairs <> 2 OR total_credits IS DISTINCT FROM 4::numeric OR after_ten <> 0 OR friday_free <> 0 THEN
		RAISE EXCEPTION 'Known scenario mismatch';
	END IF;
	-- Fixed selections prove the required-group interpretation of the fixture.
	IF EXISTS (
		WITH cases(selected, expected) AS (VALUES
			(ARRAY['010','060'], true), (ARRAY['010','062'], true),
			(ARRAY['010'], false), (ARRAY['010','060','062'], false), (ARRAY['060'], false)
		), evaluated AS (
			SELECT expected,
				('010' = ANY(selected) AND
				 (SELECT count(*) FROM section_link l JOIN section b ON b.section_id = l.compatible_section_id
				  WHERE b.section_code = ANY(selected)) =
				 (SELECT required_count FROM section_component_group)) AS actual
			FROM cases
		) SELECT 1 FROM evaluated WHERE actual IS DISTINCT FROM expected
	) THEN
		RAISE EXCEPTION 'Required-component selection scenarios failed';
	END IF;
	IF EXISTS (SELECT 1 FROM meeting a JOIN meeting b ON a.day_of_week = b.day_of_week
		AND a.start_time < b.end_time AND b.start_time < a.end_time
		WHERE a.section_id <> b.section_id) THEN
		RAISE EXCEPTION 'Screenshotted sections unexpectedly overlap';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM meeting a JOIN meeting b ON a.day_of_week = b.day_of_week
		JOIN section sa ON sa.section_id = a.section_id JOIN section sb ON sb.section_id = b.section_id
		WHERE sa.section_code = '060' AND sb.section_code = '062'
		AND b.start_time - a.end_time = INTERVAL '10 minutes') THEN
		RAISE EXCEPTION 'Lab time gap differs from screenshot';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM enrollment_snapshot e JOIN section s USING (section_id)
		WHERE s.crn = '13132' AND e.enrollment_actual = 0 AND e.enrollment_maximum = 38
		AND e.enrollment_seats_available = 38 AND e.waitlist_capacity = 0
		AND e.waitlist_actual = 0 AND e.waitlist_seats_available = 0 AND NOT e.is_live)
		OR EXISTS (SELECT 1 FROM source_asset WHERE captured_at IS NOT NULL OR source_url IS NOT NULL) THEN
		RAISE EXCEPTION 'Snapshot was misrepresented as live or given an invented capture time/URL';
	END IF;
	-- Rejected writes roll back inside each exception block.
	BEGIN
		UPDATE meeting SET section_id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
		RAISE EXCEPTION 'Orphan meeting accepted';
	EXCEPTION WHEN foreign_key_violation THEN NULL; END;
	BEGIN
		UPDATE meeting SET day_of_week = 8;
		RAISE EXCEPTION 'Invalid weekday accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE meeting SET end_time = start_time;
		RAISE EXCEPTION 'Invalid meeting time accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE section SET source_id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
		RAISE EXCEPTION 'Missing provenance accepted';
	EXCEPTION WHEN foreign_key_violation THEN NULL; END;
	BEGIN
		UPDATE section_link SET term_id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
		RAISE EXCEPTION 'Wrong-term link accepted';
	EXCEPTION WHEN foreign_key_violation THEN NULL; END;
	BEGIN
		UPDATE enrollment_snapshot SET is_live = true;
		RAISE EXCEPTION 'Live availability claim accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE course_requirement SET assessment_status = 'RULE_DEFINED';
		RAISE EXCEPTION 'A defined rule without an expression was accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	BEGIN
		UPDATE data_issue SET resolution_source_id = NULL;
		RAISE EXCEPTION 'Resolved issue without provenance accepted';
	EXCEPTION WHEN check_violation THEN NULL; END;
	RAISE NOTICE 'PASS: SuY corrections: 2 valid lecture/lab choices at 4 credits; 3 nullable requirement rows; 0 open issues; 5 selection scenarios and 8 rejection checks passed';
END $$;
COMMIT;
