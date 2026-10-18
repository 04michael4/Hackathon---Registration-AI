-- Standalone PostgreSQL demo schema; not yet a backend/Flyway integration.
BEGIN;
CREATE SCHEMA IF NOT EXISTS cse007_demo;
SET LOCAL search_path TO cse007_demo;
CREATE TABLE course (
 course_id text PRIMARY KEY,
 code text NOT NULL UNIQUE,
 title text NOT NULL,
 description text NOT NULL,
 credits numeric(3,1) NOT NULL CHECK (credits > 0),
 source_url text NOT NULL,
 verified_at timestamptz NOT NULL,
 catalog_credit_text text NOT NULL
);
CREATE TABLE term (
 term_id text PRIMARY KEY,
 name text NOT NULL,
 academic_year text NOT NULL,
 is_demo boolean NOT NULL DEFAULT true CHECK (is_demo)
);
CREATE TABLE section (
 section_id text PRIMARY KEY,
 course_id text NOT NULL REFERENCES course,
 term_id text NOT NULL REFERENCES term,
 section_code text NOT NULL,
 component_type text NOT NULL CHECK (component_type IN ('LECTURE','LAB')),
 instructor text,
 delivery_type text NOT NULL CHECK (delivery_type IN ('IN_PERSON','ONLINE')),
 source_status text NOT NULL CHECK (source_status IN ('REAL_VERIFIED','HISTORICAL','DEMO_SIMULATED')),
 source_url text,
 verified_at timestamptz,
 UNIQUE (course_id, term_id, section_code),
 UNIQUE (section_id, course_id, term_id),
 CHECK (source_status = 'DEMO_SIMULATED' OR (source_url IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE INDEX section_term_course_idx ON section(term_id, course_id);
CREATE TABLE meeting (
 meeting_id text PRIMARY KEY,
 section_id text NOT NULL REFERENCES section,
 day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
 start_time time NOT NULL,
 end_time time NOT NULL CHECK (end_time > start_time),
 location text NOT NULL,
 source_status text NOT NULL DEFAULT 'DEMO_SIMULATED' CHECK (source_status = 'DEMO_SIMULATED'),
 UNIQUE (section_id, day_of_week, start_time)
);
CREATE TABLE section_link (
 primary_section_id text NOT NULL,
 required_component_group text NOT NULL CHECK (required_component_group = 'LAB'),
 compatible_section_id text NOT NULL,
 course_id text NOT NULL,
 term_id text NOT NULL,
 PRIMARY KEY (primary_section_id, required_component_group, compatible_section_id),
 FOREIGN KEY (primary_section_id, course_id, term_id) REFERENCES section(section_id, course_id, term_id),
 FOREIGN KEY (compatible_section_id, course_id, term_id) REFERENCES section(section_id, course_id, term_id),
 CHECK (primary_section_id <> compatible_section_id)
);
CREATE INDEX section_link_component_idx ON section_link(compatible_section_id);
COMMIT;
