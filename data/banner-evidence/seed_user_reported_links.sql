-- Records the seven lecture->lab relationships Michael reported from Lehigh Banner's Linked Sections pages
-- (Spring 2027, term 202710), plus what is NOT known about them.
--
-- Evidence class: USER_SCREENSHOT. Michael viewed the pages on 2026-10-16 and shared screenshots with ChatGPT.
-- Claude has NOT inspected those screenshots or Banner, so nothing here is VERIFIED_BANNER. There is no
-- screenshot file; the content hash is the SHA-256 of the transcribed report text below.
--
-- Not recorded as facts (explicit UNKNOWN claims instead): required lab count, lecture credits, lab credits.
-- The screenshots reportedly showed "Hours: 0"; that is kept as a NOTE, not as credits.
--
-- No transaction control here: import_user_reported_links.sql wraps it, and the tests rerun it inside theirs.
-- Idempotent: every insert is keyed on a natural key with ON CONFLICT DO NOTHING.

DROP TABLE IF EXISTS pg_temp.seed_link, pg_temp.seed_source, pg_temp.seed_source_id;

CREATE TEMP TABLE seed_link ON COMMIT DROP AS
SELECT * FROM (VALUES
	('202710', 'CSE-017', '13507', '14855', '060', ARRAY[4]::smallint[], TIME '10:45', TIME '12:00'),
	('202710', 'CSE-017', '13507', '14856', '061', ARRAY[4]::smallint[], TIME '13:35', TIME '14:50'),
	('202710', 'CSE-109', '14857', '13136', '060', ARRAY[5]::smallint[], TIME '09:20', TIME '10:35'),
	('202710', 'CSE-109', '14857', '13137', '061', ARRAY[5]::smallint[], TIME '10:45', TIME '12:00'),
	('202710', 'CSE-202', '11041', '14861', '060', ARRAY[3]::smallint[], TIME '12:10', TIME '13:25'),
	('202710', 'CSE-216', '13514', '13186', '060', ARRAY[5]::smallint[], TIME '13:35', TIME '16:15'),
	('202710', 'CSE-241', '13553', '13554', '060', ARRAY[5]::smallint[], TIME '13:35', TIME '14:50')
) AS v (term_code, course_id, lecture_crn, lab_crn, lab_section, days_iso, start_time, end_time);

-- One source per lecture's Linked Sections page. The hash covers the transcribed report for that page.
CREATE TEMP TABLE seed_source ON COMMIT DROP AS
SELECT term_code, course_id, lecture_crn,
	encode(sha256(convert_to('user-report|' || term_code || '|LINKED_SECTIONS|lecture=' || lecture_crn || '|labs='
		|| string_agg(lab_crn || ':' || lab_section || ':' || array_to_string(days_iso, ',') || ':' || start_time || '-' || end_time,
			';' ORDER BY lab_crn), 'UTF8')), 'hex') AS sha
FROM seed_link
GROUP BY term_code, course_id, lecture_crn;

INSERT INTO banner_evidence.evidence_source
	(source_kind, page_kind, term_code, subject_crn, captured_on, captured_by_role, inspected_by_claude,
	 content_sha256, sanitization_note, provenance_note)
SELECT 'USER_SCREENSHOT', 'LINKED_SECTIONS', term_code, lecture_crn, DATE '2026-10-16', 'USER', false, sha,
	'Transcribed relationships and times only. No screenshot file, URL, student data or page text is stored; the hash covers the transcribed report.',
	'Viewed by Michael on Banner Linked Sections for this lecture on 2026-10-16 and shared as screenshots with ChatGPT. Claude has not inspected the screenshots or Banner. Screenshot URLs were not recorded.'
FROM seed_source
ON CONFLICT DO NOTHING;

CREATE TEMP TABLE seed_source_id ON COMMIT DROP AS
SELECT s.source_id, t.term_code, t.course_id, t.lecture_crn
FROM seed_source t
JOIN banner_evidence.evidence_source s
	ON s.source_kind = 'USER_SCREENSHOT' AND s.page_kind = 'LINKED_SECTIONS' AND s.term_code = t.term_code
	AND s.subject_crn = t.lecture_crn AND s.content_sha256 = t.sha;

-- Sections seen on each page: the lecture (Linked Sections listed) and its labs (not separately checked).
INSERT INTO banner_evidence.section_observation (source_id, term_code, crn, course_id, section_code, linked_sections_outcome)
SELECT source_id, term_code, lecture_crn, course_id, NULL, 'LISTED' FROM seed_source_id
UNION ALL
SELECT i.source_id, l.term_code, l.lab_crn, l.course_id, l.lab_section, 'NOT_CHECKED'
FROM seed_link l JOIN seed_source_id i ON i.term_code = l.term_code AND i.lecture_crn = l.lecture_crn
ON CONFLICT DO NOTHING;

INSERT INTO banner_evidence.meeting_observation (source_id, crn, seq, days_iso, start_time, end_time)
SELECT i.source_id, l.lab_crn, 1, l.days_iso, l.start_time, l.end_time
FROM seed_link l JOIN seed_source_id i ON i.term_code = l.term_code AND i.lecture_crn = l.lecture_crn
ON CONFLICT DO NOTHING;

INSERT INTO banner_evidence.link_observation (source_id, lecture_crn, linked_crn, link_label)
SELECT i.source_id, l.lecture_crn, l.lab_crn, 'Lab - Linked'
FROM seed_link l JOIN seed_source_id i ON i.term_code = l.term_code AND i.lecture_crn = l.lecture_crn
ON CONFLICT DO NOTHING;

INSERT INTO banner_evidence.note_observation (source_id, crn, note_kind, note_text)
SELECT source_id, NULL, 'DISPLAYED_HOURS_REPORT',
	'Michael reported that the screenshots showed "Hours: 0". Which rows showed it was not recorded. Whether this is academic credit is unverified; no credit value is claimed.'
FROM seed_source_id
ON CONFLICT DO NOTHING;

-- Claims. Links are reported-linked; everything else is an explicit UNKNOWN (NULL value), with no evidence attached.
INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, other_crn, asserted_value)
SELECT term_code, 'LECTURE_LAB_LINK', course_id, lecture_crn, lab_crn, 'LINKED' FROM seed_link
UNION ALL
SELECT DISTINCT term_code, 'REQUIRED_LAB_COUNT', course_id, lecture_crn, NULL, NULL FROM seed_link
UNION ALL
SELECT DISTINCT term_code, 'LECTURE_CREDITS', course_id, lecture_crn, NULL, NULL FROM seed_link
UNION ALL
SELECT term_code, 'LAB_CREDITS', course_id, lab_crn, NULL, NULL FROM seed_link
ON CONFLICT DO NOTHING;

INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance, note)
SELECT c.claim_id, i.source_id, 'SUPPORTS', 'User-reported from the Linked Sections page; not inspected by Claude.'
FROM seed_link l
JOIN seed_source_id i ON i.term_code = l.term_code AND i.lecture_crn = l.lecture_crn
JOIN banner_evidence.claim c ON c.term_code = l.term_code AND c.claim_type = 'LECTURE_LAB_LINK'
	AND c.course_id = l.course_id AND c.crn = l.lecture_crn AND c.other_crn = l.lab_crn AND c.asserted_value = 'LINKED'
ON CONFLICT DO NOTHING;
