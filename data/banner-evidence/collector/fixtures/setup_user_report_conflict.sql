-- TEST SETUP (synthetic, dry-run only): an earlier user report that lecture 99001 has labs 99002 and 99004.
-- The synthetic Banner capture lists 99002 and 99003, so 99004 must surface as a discrepancy and 99003 as Banner-only.
WITH s AS (
	INSERT INTO banner_evidence.evidence_source (source_kind, page_kind, term_code, subject_crn, captured_on, captured_by_role,
		inspected_by_claude, content_sha256, sanitization_note, provenance_note)
	VALUES ('USER_SCREENSHOT', 'LINKED_SECTIONS', '202710', '99001', DATE '2026-10-16', 'USER', false, repeat('7', 64), 'synthetic test setup', 'synthetic test setup')
	RETURNING source_id)
SELECT source_id AS rep FROM s \gset
INSERT INTO banner_evidence.section_observation (source_id, term_code, crn, course_id, linked_sections_outcome) VALUES
	(:'rep', '202710', '99001', 'CSE-099', 'LISTED'), (:'rep', '202710', '99002', 'CSE-099', 'NOT_CHECKED'), (:'rep', '202710', '99004', 'CSE-099', 'NOT_CHECKED');
INSERT INTO banner_evidence.link_observation (source_id, lecture_crn, linked_crn, link_label) VALUES
	(:'rep', '99001', '99002', 'Lab - Linked'), (:'rep', '99001', '99004', 'Lab - Linked');
INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, other_crn, asserted_value) VALUES
	('202710', 'LECTURE_LAB_LINK', 'CSE-099', '99001', '99002', 'LINKED'), ('202710', 'LECTURE_LAB_LINK', 'CSE-099', '99001', '99004', 'LINKED');
INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance)
SELECT claim_id, :'rep', 'SUPPORTS' FROM banner_evidence.claim WHERE course_id = 'CSE-099' AND claim_type = 'LECTURE_LAB_LINK';
