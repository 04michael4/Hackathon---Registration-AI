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
