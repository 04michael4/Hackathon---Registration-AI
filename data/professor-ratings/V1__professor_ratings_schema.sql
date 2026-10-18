-- professor_ratings: instructors (as Banner lists them), their sections, and student-opinion ratings collected from Rate My Professors.
-- Additive and separate from the catalog: nothing in catalog_api, catalog_promoted, catalog_live or catalog_research reads it, and it never
-- changes a tier, an approval, an eligibility rule or a schedule. A rating is opinion data (evidence level RMP_STUDENT_OPINION), not a fact
-- about the course.
-- Needs catalog_research installed (read-only, for the orphan check). Install with install.sql as a non-superuser owner.
-- Rows hold real instructor names: the database stays local/private and no repository file contains a real name (see ADR-0006).
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS professor_ratings;
COMMENT ON SCHEMA professor_ratings IS 'Instructors from Banner plus Rate My Professors aggregates. Opinion data; never read by eligibility, tiers, approvals or the solver.';

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Provenance: one row per import (append-only).
CREATE TABLE IF NOT EXISTS professor_ratings.import_run (
	run_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	kind         text NOT NULL CHECK (kind IN ('INSTRUCTORS', 'RMP_FETCH', 'RMP_RATINGS', 'MATCH')),
	label        text NOT NULL CHECK (length(btrim(label)) > 0),
	input_sha256 text NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
	row_count    integer NOT NULL CHECK (row_count >= 0),
	imported_at  timestamptz NOT NULL DEFAULT now(),
	imported_by  text NOT NULL DEFAULT session_user
);

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Instructors as Banner lists them. banner_name is the normalized display form "Last, First Middle"; last_key/first_key are lower-case ASCII
-- folds used only for matching. "Staff" and "TBA" placeholders are never stored: they are not people.
CREATE TABLE IF NOT EXISTS professor_ratings.instructor (
	instructor_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	banner_name   text NOT NULL UNIQUE CHECK (banner_name ~ '^[^,]+, .+$'),
	last_name     text NOT NULL CHECK (length(btrim(last_name)) > 0),
	first_name    text NOT NULL CHECK (length(btrim(first_name)) > 0),
	last_key      text NOT NULL CHECK (last_key ~ '^[a-z]+( [a-z]+)*$'),
	first_key     text NOT NULL CHECK (first_key ~ '^[a-z]+( [a-z]+)*$'),
	CHECK (lower(banner_name) !~ '(^|[ ,])(staff|tba|tbd)([ ,]|$)')
);

-- Which instructor teaches which section (soft reference to catalog_research.section by term and CRN, checked by instructor_section_orphan;
-- a hard foreign key would make a re-import of the catalog impossible).
CREATE TABLE IF NOT EXISTS professor_ratings.instructor_section (
	term_code     text NOT NULL CHECK (term_code ~ '^[0-9]{6}$'),
	crn           text NOT NULL CHECK (crn ~ '^[0-9]{5}$'),
	instructor_id bigint NOT NULL REFERENCES professor_ratings.instructor,
	role          text NOT NULL CHECK (role IN ('PRIMARY', 'ADDITIONAL', 'LISTED')),   -- LISTED: the source gives no primary/additional distinction
	source        text NOT NULL CHECK (source IN ('BANNER_GRID', 'BANNER_EVIDENCE', 'SNAPSHOT')),
	run_id        bigint NOT NULL REFERENCES professor_ratings.import_run,
	PRIMARY KEY (term_code, crn, instructor_id)
);
CREATE INDEX IF NOT EXISTS instructor_section_instructor_idx ON professor_ratings.instructor_section (instructor_id);

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Rate My Professors: the professor record and one aggregate observation per fetch. Individual reviews and their text are NOT stored.
CREATE TABLE IF NOT EXISTS professor_ratings.rmp_teacher (
	rmp_legacy_id    integer PRIMARY KEY CHECK (rmp_legacy_id > 0),
	rmp_node_id      text NOT NULL,
	school_legacy_id integer NOT NULL CHECK (school_legacy_id > 0),
	first_name       text NOT NULL,
	last_name        text NOT NULL,
	first_key        text NOT NULL CHECK (first_key ~ '^[a-z]*( [a-z]+)*$'),
	last_key         text NOT NULL CHECK (last_key ~ '^[a-z]+( [a-z]+)*$'),
	department       text
);

CREATE TABLE IF NOT EXISTS professor_ratings.rmp_rating_observation (
	observation_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	rmp_legacy_id       integer NOT NULL REFERENCES professor_ratings.rmp_teacher,
	run_id              bigint NOT NULL REFERENCES professor_ratings.import_run,
	avg_rating          numeric(2,1) CHECK (avg_rating BETWEEN 1 AND 5),
	avg_difficulty      numeric(2,1) CHECK (avg_difficulty BETWEEN 1 AND 5),
	would_take_again_pct numeric(4,1) CHECK (would_take_again_pct BETWEEN 0 AND 100),   -- NULL when RMP reports none (-1)
	num_ratings         integer NOT NULL CHECK (num_ratings >= 0),
	fetched_at          timestamptz NOT NULL,
	UNIQUE (rmp_legacy_id, run_id),
	CHECK (num_ratings > 0 OR (avg_rating IS NULL AND avg_difficulty IS NULL AND would_take_again_pct IS NULL)),
	CHECK (num_ratings = 0 OR (avg_rating IS NOT NULL AND avg_difficulty IS NOT NULL))
);

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Which RMP professor is which instructor. Append-only: the latest row per instructor is the current decision. A name match is a guess, so only
-- an exact, unique name is accepted automatically; a near match waits for a person (NEEDS_REVIEW carries no RMP id: see match_candidate).
CREATE TABLE IF NOT EXISTS professor_ratings.instructor_rmp_match (
	match_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	instructor_id bigint NOT NULL REFERENCES professor_ratings.instructor,
	rmp_legacy_id integer REFERENCES professor_ratings.rmp_teacher,
	status        text NOT NULL CHECK (status IN ('AUTO_ACCEPTED', 'NEEDS_REVIEW', 'CONFIRMED', 'NO_MATCH')),
	method        text NOT NULL CHECK (method IN ('EXACT_NAME', 'FIRST_NAME_VARIANT', 'AMBIGUOUS', 'MANUAL', 'NONE')),
	reason        text NOT NULL CHECK (length(btrim(reason)) >= 5),
	run_id        bigint REFERENCES professor_ratings.import_run,
	decided_by    text,
	decided_at    timestamptz NOT NULL DEFAULT now(),
	recorded_by   text NOT NULL DEFAULT session_user,
	CHECK ((status, method) IN (('AUTO_ACCEPTED', 'EXACT_NAME'), ('NEEDS_REVIEW', 'FIRST_NAME_VARIANT'), ('NEEDS_REVIEW', 'AMBIGUOUS'),
		('CONFIRMED', 'MANUAL'), ('NO_MATCH', 'NONE'), ('NO_MATCH', 'MANUAL'))),
	CHECK ((status IN ('AUTO_ACCEPTED', 'CONFIRMED')) = (rmp_legacy_id IS NOT NULL)),   -- only an accepted match points at an RMP professor
	CHECK ((method = 'MANUAL') = (decided_by IS NOT NULL)),                              -- a person decides MANUAL rows and only those
	CHECK (decided_by IS NULL OR length(btrim(decided_by)) >= 2),
	CHECK (method = 'MANUAL' OR run_id IS NOT NULL),                                     -- automatic rows cite the run that made them
	CHECK (decided_by IS NULL OR decided_by !~* '(script|bot|claude|auto|collector|system|import)')   -- same tripwire as approval.approved_by
);
CREATE INDEX IF NOT EXISTS instructor_rmp_match_instructor_idx ON professor_ratings.instructor_rmp_match (instructor_id, match_id DESC);

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Append-only: runs, observations and match decisions cannot be updated, deleted or truncated.
CREATE OR REPLACE FUNCTION professor_ratings.refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END $$;

DO $$
DECLARE
	t text;
BEGIN
	FOREACH t IN ARRAY ARRAY['import_run', 'rmp_rating_observation', 'instructor_rmp_match'] LOOP
		IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = t || '_append_only' AND tgrelid = ('professor_ratings.' || t)::regclass) THEN
			EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON professor_ratings.%I FOR EACH ROW EXECUTE FUNCTION professor_ratings.refuse_change()', t || '_append_only', t);
		END IF;
		IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = t || '_no_truncate' AND tgrelid = ('professor_ratings.' || t)::regclass) THEN
			EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON professor_ratings.%I FOR EACH STATEMENT EXECUTE FUNCTION professor_ratings.refuse_change()', t || '_no_truncate', t);
		END IF;
	END LOOP;
END $$;
