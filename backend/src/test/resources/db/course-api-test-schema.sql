-- Isolated synthetic test schema matching the catalog's published table/column contract.
-- This is never a production migration and is never loaded by the application.
CREATE TABLE source_asset(source_id uuid PRIMARY KEY, captured_at timestamptz, verified_at timestamptz NOT NULL);
CREATE TABLE term(term_id uuid PRIMARY KEY, term_code varchar(32) NOT NULL UNIQUE, name text NOT NULL,
 source_id uuid NOT NULL REFERENCES source_asset);
CREATE TABLE course(course_id uuid PRIMARY KEY, code varchar(32) NOT NULL UNIQUE, subject text NOT NULL,
 course_number varchar(8) NOT NULL, title text NOT NULL, description text, credits numeric(4,1));
CREATE TABLE section(section_id uuid PRIMARY KEY, course_id uuid NOT NULL REFERENCES course,
 term_id uuid NOT NULL REFERENCES term, crn varchar(16) NOT NULL, section_code text NOT NULL,
 credits numeric(4,1), component_type text NOT NULL, instructor text, search_status_text text, meeting_status text NOT NULL,
 UNIQUE(term_id,crn));
CREATE TABLE meeting(meeting_id uuid PRIMARY KEY, section_id uuid NOT NULL REFERENCES section,
 day_of_week smallint NOT NULL CHECK(day_of_week BETWEEN 1 AND 7), start_time time NOT NULL,
 end_time time NOT NULL CHECK(end_time > start_time), location text);
CREATE TABLE section_component_group(primary_section_id uuid NOT NULL REFERENCES section,
 required_component_group text NOT NULL, required_count smallint, assessment_status text NOT NULL,
 PRIMARY KEY(primary_section_id,required_component_group));
CREATE TABLE section_link(primary_section_id uuid NOT NULL REFERENCES section,
 compatible_section_id uuid NOT NULL REFERENCES section, required_component_group text NOT NULL,
 UNIQUE(primary_section_id,compatible_section_id));
CREATE TABLE course_requirement(course_id uuid NOT NULL REFERENCES course, requirement_type text NOT NULL,
 requirement_expression jsonb, assessment_status text NOT NULL, PRIMARY KEY(course_id,requirement_type));
