-- Applies the project owner's decisions of 2026-10-17 to the stored Spring 2027 data and records each one in catalog_research.owner_decision.
-- Run as the database owner AFTER the data import and V6, in one transaction; safe to rerun (a decision already applied changes nothing).
--   psql -X -v ON_ERROR_STOP=1 -f data/catalog-research/apply_owner_decisions.sql
-- Evidence level for every change is USER_CLARIFICATION: the owner's statement, not Banner and not the Registrar.
\set ON_ERROR_STOP on
BEGIN;

INSERT INTO catalog_research.owner_decision (decision_key, decided_on, decided_by, statement, implemented_in, effect) VALUES
('CSE303_PREREQ_GROUPING', '2026-10-17', 'Michael Kim',
 'Banner states CSE 303''s prerequisite as "ECE 201 Or ( CSE 201 Or CSE 202 ) And CSE 109", which is ambiguous. It means (ECE 201 or CSE 201 or CSE 202) and CSE 109: CSE 109 is always required.',
 'DATABASE', 'Stored as a structured prerequisite rule (evidence USER_CLARIFICATION); Banner''s wording stays as the source text.'),
('CSE127_327_FOLLOW_CURRENT_CATALOG', '2026-10-17', 'Michael Kim',
 'An older catalog excluded dual credit for CSE 127 and CSE 327; the current catalog omits it. Follow the current catalog: a student may take both.',
 'SERVING', 'The conflict stays stored as SOURCES_CONFLICT and is not shown to students as an exclusion (catalog_serving leaves it out).'),
('CSE398_TOPICS_SEPARATE_OFFERINGS', '2026-10-17', 'Michael Kim',
 'CSE 398 has two different topics this term (042 Advanced Operating Systems, 052 Accelerated Computing for Machine Learning). Treat them as separate courses CSE-398-042 and CSE-398-052.',
 'BACKEND', 'Both offerings leave TOPIC_IDENTITY_UNDECIDED (identity TOPIC_SEPARATE_OFFERING), become CONDITIONAL and are served; the backend accepts the topic course-id suffix.'),
('ECO_MKT_325_ONE_CLASS', '2026-10-17', 'Michael Kim',
 'ECO 325 and MKT 325 (Consumer Insights through Data Analysis) meet at identical times with the same instructor and were reported as cross-listed: treat them as one class under two codes, taken once under either code, never both in a schedule and never in conflict with each other.',
 'BACKEND_AND_ENGINE_NOT_YET', 'Stored as a no-double-credit pair in both directions, which students see as a condition. The scheduler does not yet refuse to schedule both or ignore their overlap: that needs a backend/engine change.'),
('NONCSE_REQUIREMENTS_COLLECTED_CONDITIONAL', '2026-10-17', 'Michael Kim',
 'Collect the 30 non-CSE courses'' prerequisites, corequisites and restrictions, and move them to CONDITIONAL like the CSE courses once collected.',
 'DATABASE', 'Collected from Banner Class Details (not the public catalog pages) on 2026-10-18 and imported; 28 of the 30 became CONDITIONAL (PRs 14 and 15).'),
('INSTRUCTOR_NOTE_UPDATED', '2026-10-17', 'Michael Kim',
 'Instructor names are stored in professor_ratings (ADR-0006), so the note that they are withheld from this database is out of date.',
 'DATABASE', 'The unresolved-item note for INSTRUCTORS now says where the names live.'),
('COMMON_EXAMS_WARN_ONLY', '2026-10-17', 'Michael Kim',
 'Common evening exams (ECO 001, MATH 022, 052, 205, 231; 16:25-17:40) warn the student but are not a hard schedule conflict.',
 'SERVING', 'Shown as a COMMON_EXAMS condition with the dates; the scheduler does not treat exams as meetings.'),
('CSE367_467_ARRANGED_MEETING_NOTE_ONLY', '2026-10-17', 'Michael Kim',
 'CSE 367 and 467 have a mandatory team meeting with no published time: schedule on the fixed class time and show a note.',
 'SERVING', 'Shown as an ARRANGED_MEETING condition; the unscheduled meeting is not part of the generated schedule.'),
('MATH312_334_CREDITS_PER_SECTION', '2026-10-17', 'Michael Kim',
 'MATH 312 and 334 show 4 credits for section 010 and 3 for section 011 in Banner''s grid: use credits per section as shown, labelled as the grid''s value.',
 'BACKEND_AND_ENGINE_NOT_YET', 'Stored per section but the courses are not served: the backend reads one credit value per course.'),
('UNLINKED_RECITATIONS_ANY_PAIRING', '2026-10-17', 'Michael Kim',
 'For non-CSE labs and recitations that Banner links to no lecture, allow any pairing and flag it as not confirmed.',
 'RECORDED_ONLY', 'Superseded by the data: Banner lists each lecture''s recitations, so they are linked; a course whose components cannot be linked to one lecture (CHM 030, PHY 012) stays blocked.'),
('SHOW_ALL_PREDICTIONS', '2026-10-17', 'Michael Kim',
 'Show every rating prediction whatever its confidence: new professors have few reviews and that is fine.',
 'SERVING', 'catalog_serving returns all predictions with their confidence.')
ON CONFLICT (decision_key) DO NOTHING;

-- 1. CSE 303: the grouping is structured; Banner's wording stays as the source text.
DO $$
DECLARE
	n integer;
BEGIN
	UPDATE catalog_research.eligibility_rule
		SET expression = '{"op": "AND", "args": [{"op": "OR", "args": [{"course": "ECE-201"}, {"course": "CSE-201"}, {"course": "CSE-202"}]}, {"course": "CSE-109"}]}'::jsonb,
			parse_status = 'STRUCTURED', evidence_level = 'USER_CLARIFICATION'
		WHERE term_code = '202710' AND offering_id = 'CSE-303' AND rule_kind = 'PREREQUISITE' AND parse_status = 'TEXT_ONLY';
	GET DIAGNOSTICS n = ROW_COUNT;
	IF n = 0 AND NOT EXISTS (SELECT 1 FROM catalog_research.eligibility_rule WHERE term_code = '202710' AND offering_id = 'CSE-303' AND rule_kind = 'PREREQUISITE' AND parse_status = 'STRUCTURED') THEN
		RAISE EXCEPTION 'CSE-303 has no prerequisite rule to structure; run the data import first';
	END IF;
	UPDATE catalog_research.requirement_status SET status = 'RULE_DEFINED', evidence_level = 'USER_CLARIFICATION'
		WHERE term_code = '202710' AND offering_id = 'CSE-303' AND requirement_type = 'PREREQUISITE' AND status = 'TEXT_ONLY';
END $$;

-- 3. CSE 398: the two topics are separate offerings.
DO $$
BEGIN
	UPDATE catalog_research.offering SET identity_status = 'TOPIC_SEPARATE_OFFERING'
		WHERE term_code = '202710' AND offering_id IN ('CSE-398-042', 'CSE-398-052') AND identity_status = 'TOPIC_IDENTITY_UNDECIDED';
	IF (SELECT count(*) FROM catalog_research.offering WHERE term_code = '202710' AND offering_id IN ('CSE-398-042', 'CSE-398-052') AND identity_status = 'TOPIC_SEPARATE_OFFERING') <> 2 THEN
		RAISE EXCEPTION 'the two CSE 398 topic offerings are not stored; run the data import first';
	END IF;
END $$;

-- 4. ECO 325 / MKT 325: one class under two codes, recorded both ways as a no-double-credit pair.
INSERT INTO catalog_research.credit_exclusion (term_code, offering_id, excluded_course_id, excluded_text, exclusion_kind, source_text, evidence_level)
SELECT '202710', a, b, replace(b, '-', ' '), 'NO_DOUBLE_CREDIT',
	'ECO 325 and MKT 325 are one class under two codes (same times, same instructor, reported as cross-listed). The project owner decided on 2026-10-17 that a student takes it once, under either code.',
	'USER_CLARIFICATION'
FROM (VALUES ('ECO-325', 'MKT-325'), ('MKT-325', 'ECO-325')) AS p(a, b)
WHERE EXISTS (SELECT 1 FROM catalog_research.offering WHERE term_code = '202710' AND offering_id = p.a)
	AND NOT EXISTS (SELECT 1 FROM catalog_research.credit_exclusion x WHERE x.term_code = '202710' AND x.offering_id = p.a AND x.excluded_course_id = p.b);

-- 6. The note that instructor names are withheld is out of date.
UPDATE catalog_research.unresolved_item
	SET detail = 'Instructor names are not stored in catalog_research; they live in professor_ratings (ADR-0006), which is for the team''s own app only. Whether Banner names may be redistributed is still unverified.'
	WHERE subject = 'INSTRUCTORS' AND detail LIKE 'NOT_IMPORTED:%';

COMMIT;
