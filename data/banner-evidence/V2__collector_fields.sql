-- banner_evidence v2: what the Banner collector records, and how it is reconciled with earlier reports.
-- Additive and rerunnable. Requires V1. Touches nothing outside banner_evidence.
--
--   * section_observation gains the displayed course title.
--   * meeting_observation gains the displayed date range, meeting type, building and room (verbatim, "None" stays "None").
--   * section_field_observation (new, append-only): a displayed value under its ORIGINAL label and view, so the
--     search grid's "Hours" and Class Details' "Credit Hours" are stored side by side and never merged.
--   * note_observation gains the kind NO_COUNT_STATEMENT (a Linked Sections view that states no number to register for).
--   * link_reconciliation and meeting_reconciliation (read-only views): earlier user reports against Banner captures.

DO $$
BEGIN
	IF to_regclass('banner_evidence.section_observation') IS NULL OR to_regclass('banner_evidence.claim_status') IS NULL THEN
		RAISE EXCEPTION 'banner_evidence V1 is missing; run V1__banner_evidence_schema.sql first';
	END IF;
END $$;

ALTER TABLE banner_evidence.section_observation
	ADD COLUMN IF NOT EXISTS title text CHECK (length(title) <= 120 AND banner_evidence.is_safe_text(title));
COMMENT ON COLUMN banner_evidence.section_observation.credit_hours_text IS
	'Legacy single-value column; the collector does not use it. Displayed hours live in section_field_observation under their original labels.';

ALTER TABLE banner_evidence.meeting_observation
	ADD COLUMN IF NOT EXISTS start_date date,
	ADD COLUMN IF NOT EXISTS end_date date,
	ADD COLUMN IF NOT EXISTS meeting_type_text text CHECK (length(meeting_type_text) <= 60 AND banner_evidence.is_safe_text(meeting_type_text)),
	ADD COLUMN IF NOT EXISTS building_text text CHECK (length(building_text) <= 60 AND banner_evidence.is_safe_text(building_text)),
	ADD COLUMN IF NOT EXISTS room_text text CHECK (length(room_text) <= 60 AND banner_evidence.is_safe_text(room_text));
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'meeting_dates_ordered'
			AND conrelid = 'banner_evidence.meeting_observation'::regclass) THEN
		ALTER TABLE banner_evidence.meeting_observation
			ADD CONSTRAINT meeting_dates_ordered CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date);
	END IF;
END $$;

CREATE TABLE IF NOT EXISTS banner_evidence.section_field_observation (
	source_id   uuid NOT NULL,
	crn         text NOT NULL,
	view_name   text NOT NULL CHECK (view_name IN ('SEARCH_GRID', 'CLASS_DETAILS', 'LINKED_SECTIONS')),
	field_label text NOT NULL CHECK (length(field_label) BETWEEN 1 AND 60 AND banner_evidence.is_safe_text(field_label)),
	field_value text NOT NULL CHECK (length(field_value) BETWEEN 1 AND 200 AND banner_evidence.is_safe_text(field_value)),
	PRIMARY KEY (source_id, crn, view_name, field_label),
	FOREIGN KEY (source_id, crn) REFERENCES banner_evidence.section_observation (source_id, crn)
);
COMMENT ON TABLE banner_evidence.section_field_observation IS
	'A displayed value under its original label and view (e.g. search grid "Hours" vs Class Details "Credit Hours"). Never merged.';
CREATE INDEX IF NOT EXISTS section_field_observation_lookup
	ON banner_evidence.section_field_observation (crn, view_name, field_label);

DO $$
DECLARE
	c record;
BEGIN
	EXECUTE 'CREATE OR REPLACE TRIGGER section_field_observation_append_only BEFORE UPDATE OR DELETE ON banner_evidence.section_field_observation FOR EACH ROW EXECUTE FUNCTION banner_evidence.reject_change()';
	EXECUTE 'CREATE OR REPLACE TRIGGER section_field_observation_no_truncate BEFORE TRUNCATE ON banner_evidence.section_field_observation FOR EACH STATEMENT EXECUTE FUNCTION banner_evidence.reject_change()';
	-- Widen the note_kind check (a CHECK cannot be altered in place).
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'banner_evidence.note_observation'::regclass
			AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%NO_COUNT_STATEMENT%') THEN
		FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = 'banner_evidence.note_observation'::regclass
				AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%DISPLAYED_HOURS_REPORT%' LOOP
			EXECUTE format('ALTER TABLE banner_evidence.note_observation DROP CONSTRAINT %I', c.conname);
		END LOOP;
		ALTER TABLE banner_evidence.note_observation ADD CONSTRAINT note_observation_note_kind_check CHECK (note_kind IN
			('DISPLAYED_HOURS_REPORT', 'REGISTRATION_RESTRICTION', 'COREQUISITE_TEXT', 'PREREQUISITE_TEXT',
			 'LINK_RULE_TEXT', 'NO_COUNT_STATEMENT', 'OTHER'));
	END IF;
END $$;

-- The two credit displays side by side, per section. Nothing here decides which one is "the" credit.
CREATE OR REPLACE VIEW banner_evidence.credit_display_comparison AS
SELECT q.*, q.grid_hours IS DISTINCT FROM q.class_details_credit_hours AS displays_differ
FROM (
	SELECT o.term_code, o.course_id, f.crn,
		string_agg(DISTINCT f.field_value, ',' ORDER BY f.field_value) FILTER (WHERE f.view_name = 'SEARCH_GRID' AND f.field_label = 'Hours') AS grid_hours,
		string_agg(DISTINCT f.field_value, ',' ORDER BY f.field_value) FILTER (WHERE f.view_name = 'CLASS_DETAILS' AND f.field_label = 'Credit Hours') AS class_details_credit_hours
	FROM banner_evidence.section_field_observation f
	JOIN banner_evidence.section_observation o ON o.source_id = f.source_id AND o.crn = f.crn
	GROUP BY o.term_code, o.course_id, f.crn
) q;

-- Earlier reports against Banner captures, per lecture->lab link claim.
CREATE OR REPLACE VIEW banner_evidence.link_reconciliation AS
SELECT q.*,
	CASE WHEN user_reported AND banner_lists AND NOT banner_denies THEN 'CONFIRMED_BY_BANNER'
		WHEN banner_denies THEN 'DISCREPANCY_BANNER_DOES_NOT_LIST'
		WHEN banner_lists AND NOT user_reported THEN 'BANNER_ONLY'
		WHEN user_reported THEN 'NOT_YET_CHECKED_IN_BANNER'
		ELSE 'NO_EVIDENCE' END AS outcome
FROM (
	SELECT c.term_code, c.course_id, c.crn AS lecture_crn, c.other_crn AS lab_crn,
		COALESCE(bool_or(s.source_kind = 'USER_SCREENSHOT' AND ce.stance = 'SUPPORTS'), false) AS user_reported,
		COALESCE(bool_or(s.source_kind = 'BANNER_LIVE' AND ce.stance = 'SUPPORTS'), false) AS banner_lists,
		COALESCE(bool_or(s.source_kind = 'BANNER_LIVE' AND ce.stance = 'CONTRADICTS'), false) AS banner_denies
	FROM banner_evidence.claim c
	LEFT JOIN banner_evidence.claim_evidence ce ON ce.claim_id = c.claim_id
	LEFT JOIN banner_evidence.evidence_source s ON s.source_id = ce.source_id
	WHERE c.claim_type = 'LECTURE_LAB_LINK' AND c.asserted_value = 'LINKED'
	GROUP BY c.term_code, c.course_id, c.crn, c.other_crn
) q;

-- Reported lab meeting times against Banner's search grid.
CREATE OR REPLACE VIEW banner_evidence.meeting_reconciliation AS
WITH reported AS (
	SELECT DISTINCT m.crn, m.days_iso, m.start_time, m.end_time
	FROM banner_evidence.meeting_observation m JOIN banner_evidence.evidence_source s ON s.source_id = m.source_id
	WHERE s.source_kind = 'USER_SCREENSHOT'
), banner AS (
	SELECT DISTINCT m.crn, m.days_iso, m.start_time, m.end_time
	FROM banner_evidence.meeting_observation m JOIN banner_evidence.evidence_source s ON s.source_id = m.source_id
	WHERE s.source_kind = 'BANNER_LIVE'
)
SELECT r.crn, r.days_iso AS reported_days, r.start_time AS reported_start, r.end_time AS reported_end,
	b.days_iso AS banner_days, b.start_time AS banner_start, b.end_time AS banner_end,
	CASE WHEN NOT EXISTS (SELECT 1 FROM banner b2 WHERE b2.crn = r.crn) THEN 'NOT_YET_OBSERVED_IN_BANNER'
		WHEN b.crn IS NOT NULL THEN 'MATCH' ELSE 'DISCREPANCY' END AS outcome
FROM reported r
LEFT JOIN banner b ON b.crn = r.crn AND b.days_iso = r.days_iso AND b.start_time = r.start_time AND b.end_time = r.end_time;
