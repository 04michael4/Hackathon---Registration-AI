-- Probes for banner_evidence. Body only: test_banner_evidence.sql wraps it in a transaction that is always
-- rolled back, so nothing here persists. Every probe either holds or raises; a final count proves none was skipped.
\set ON_ERROR_STOP on

CREATE OR REPLACE FUNCTION pg_temp.bump() RETURNS void LANGUAGE sql AS $$
	SELECT set_config('t.passed', (COALESCE(NULLIF(current_setting('t.passed', true), ''), '0')::int + 1)::text, true)
$$;
CREATE OR REPLACE FUNCTION pg_temp.expect_true(label text, cond boolean) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
	IF cond IS TRUE THEN
		PERFORM pg_temp.bump();
	ELSE
		RAISE EXCEPTION 'probe failed: %', label;
	END IF;
END $$;
-- The statement must be refused with an integrity-constraint error (check, not-null, unique, foreign key or trigger).
CREATE OR REPLACE FUNCTION pg_temp.expect_rejected(label text, stmt text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
	BEGIN
		EXECUTE stmt;
	EXCEPTION WHEN integrity_constraint_violation THEN
		PERFORM pg_temp.bump();
		RETURN;
	END;
	RAISE EXCEPTION 'probe failed: % was accepted', label;
END $$;
CREATE OR REPLACE FUNCTION pg_temp.insert_live(
	p_path text DEFAULT '/StudentRegistrationSsb/ssb/classSearch/classSearch',
	p_host text DEFAULT 'reg-prod.ec.lehigh.edu',
	p_file text DEFAULT 'data/banner-evidence/captures/202710/probe.txt',
	p_sha text DEFAULT repeat('b', 64),
	p_note text DEFAULT 'Extracted section rows only.',
	p_role text DEFAULT 'CLAUDE_BROWSER',
	p_term text DEFAULT '202710',
	p_crn text DEFAULT '11041') RETURNS uuid LANGUAGE sql AS $$
	INSERT INTO banner_evidence.evidence_source (source_kind, page_kind, term_code, subject_crn, captured_on, captured_at,
		captured_by_role, inspected_by_claude, source_host, source_path, evidence_file, content_sha256, sanitization_note, provenance_note)
	VALUES ('BANNER_LIVE', 'LINKED_SECTIONS', p_term, p_crn, DATE '2026-10-17', now(), p_role, true, p_host, p_path, p_file,
		p_sha, p_note, 'probe')
	RETURNING source_id
$$;
CREATE OR REPLACE FUNCTION pg_temp.claim_id(p_type text, p_crn text, p_other text, p_value text) RETURNS bigint LANGUAGE sql AS $$
	SELECT claim_id FROM banner_evidence.claim WHERE term_code = '202710' AND claim_type = p_type AND crn = p_crn
		AND other_crn IS NOT DISTINCT FROM p_other AND asserted_value IS NOT DISTINCT FROM p_value
$$;

-- ===== 1. Idempotency: running the import again adds nothing =====
SELECT (SELECT count(*) FROM banner_evidence.evidence_source) AS n_src, (SELECT count(*) FROM banner_evidence.section_observation) AS n_sec,
	(SELECT count(*) FROM banner_evidence.meeting_observation) AS n_mtg, (SELECT count(*) FROM banner_evidence.link_observation) AS n_lnk,
	(SELECT count(*) FROM banner_evidence.note_observation) AS n_note, (SELECT count(*) FROM banner_evidence.claim) AS n_claim,
	(SELECT count(*) FROM banner_evidence.claim_evidence) AS n_ev \gset
\ir seed_user_reported_links.sql
SELECT pg_temp.expect_true('seed rerun leaves every table unchanged',
	(SELECT count(*) FROM banner_evidence.evidence_source) = :n_src AND (SELECT count(*) FROM banner_evidence.section_observation) = :n_sec
	AND (SELECT count(*) FROM banner_evidence.meeting_observation) = :n_mtg AND (SELECT count(*) FROM banner_evidence.link_observation) = :n_lnk
	AND (SELECT count(*) FROM banner_evidence.note_observation) = :n_note AND (SELECT count(*) FROM banner_evidence.claim) = :n_claim
	AND (SELECT count(*) FROM banner_evidence.claim_evidence) = :n_ev);
SELECT pg_temp.expect_true('the seven reported link claims exist exactly once each',
	(SELECT count(*) FROM banner_evidence.claim WHERE claim_type = 'LECTURE_LAB_LINK' AND asserted_value = 'LINKED'
		AND (crn, other_crn) IN (('13507', '14855'), ('13507', '14856'), ('14857', '13136'), ('14857', '13137'), ('11041', '14861'), ('13514', '13186'), ('13553', '13554'))) = 7);

-- ===== 2. Sources: paths, hosts, hashes, text and capture rules =====
SELECT pg_temp.expect_rejected('path with a query string', $q$ SELECT pg_temp.insert_live(p_path => '/ssb/classSearch?uniqueSessionId=abc123') $q$);
SELECT pg_temp.expect_rejected('path with a ; session parameter', $q$ SELECT pg_temp.insert_live(p_path => '/ssb/classSearch;jsessionid=ABC') $q$);
SELECT pg_temp.expect_rejected('path with a fragment', $q$ SELECT pg_temp.insert_live(p_path => '/ssb/classSearch#frag') $q$);
SELECT pg_temp.expect_rejected('path that names a token', $q$ SELECT pg_temp.insert_live(p_path => '/ssb/token/x') $q$);
SELECT pg_temp.expect_rejected('host that only starts like Lehigh', $q$ SELECT pg_temp.insert_live(p_host => 'reg-prod.ec.lehigh.edu.evil.com') $q$);
SELECT pg_temp.expect_rejected('host outside lehigh.edu', $q$ SELECT pg_temp.insert_live(p_host => 'evil.example.com') $q$);
SELECT pg_temp.expect_rejected('malformed content hash', $q$ SELECT pg_temp.insert_live(p_sha => 'abc') $q$);
SELECT pg_temp.expect_rejected('cookie text in a note', $q$ SELECT pg_temp.insert_live(p_note => 'Cookie: JSESSIONID=ABC123') $q$);
SELECT pg_temp.expect_rejected('authorization header in a note', $q$ SELECT pg_temp.insert_live(p_note => 'Authorization: Bearer abcdefgh12345') $q$);
SELECT pg_temp.expect_rejected('raw HTML in a note', $q$ SELECT pg_temp.insert_live(p_note => '<html><body>whole page</body></html>') $q$);
SELECT pg_temp.expect_rejected('a page-sized note', $q$ SELECT pg_temp.insert_live(p_note => repeat('x', 1001)) $q$);
SELECT pg_temp.expect_rejected('a student-id-like number in a note', $q$ SELECT pg_temp.insert_live(p_note => 'Student 123456789 registered') $q$);
SELECT pg_temp.expect_rejected('an e-mail address in a note', $q$ SELECT pg_temp.insert_live(p_note => 'contact someone@lehigh.edu') $q$);
SELECT pg_temp.expect_rejected('evidence file outside the captures folder', $q$ SELECT pg_temp.insert_live(p_file => '../../etc/passwd') $q$);
SELECT pg_temp.expect_rejected('evidence file path traversal', $q$ SELECT pg_temp.insert_live(p_file => 'data/banner-evidence/captures/../x') $q$);
SELECT pg_temp.expect_rejected('a live capture without an evidence file', $q$ SELECT pg_temp.insert_live(p_file => NULL) $q$);
SELECT pg_temp.expect_rejected('a live capture attributed to the user', $q$ SELECT pg_temp.insert_live(p_role => 'USER') $q$);
SELECT pg_temp.expect_rejected('a malformed CRN', $q$ SELECT pg_temp.insert_live(p_crn => '1234') $q$);

-- Two live captures used by the rest of the probes.
-- live1: lecture 11041's Linked Sections were read and show NO link to 14861.
SELECT pg_temp.insert_live(p_sha => repeat('1', 64), p_crn => '11041') AS live1 \gset
INSERT INTO banner_evidence.section_observation (source_id, term_code, crn, course_id, linked_sections_outcome)
VALUES (:'live1', '202710', '11041', 'CSE-202', 'NONE_SHOWN');
-- live2: lecture 14857's Linked Sections list 13136 and 13137, with displayed hours kept as text.
SELECT pg_temp.insert_live(p_sha => repeat('2', 64), p_crn => '14857') AS live2 \gset
INSERT INTO banner_evidence.section_observation (source_id, term_code, crn, course_id, section_code, credit_hours_text, linked_sections_outcome) VALUES
	(:'live2', '202710', '14857', 'CSE-109', '010', '4.000', 'LISTED'),
	(:'live2', '202710', '13136', 'CSE-109', '060', '0', 'NOT_CHECKED'),
	(:'live2', '202710', '13137', 'CSE-109', '061', '0', 'NOT_CHECKED');
INSERT INTO banner_evidence.link_observation (source_id, lecture_crn, linked_crn, link_label) VALUES
	(:'live2', '14857', '13136', 'Lab - Linked'), (:'live2', '14857', '13137', 'Lab - Linked');
SELECT pg_temp.expect_true('displayed hours are stored as text without becoming a claim',
	(SELECT credit_hours_text FROM banner_evidence.section_observation WHERE source_id = :'live2' AND crn = '13136') = '0'
	AND NOT EXISTS (SELECT 1 FROM banner_evidence.claim WHERE claim_type IN ('LECTURE_CREDITS', 'LAB_CREDITS') AND asserted_value IS NOT NULL));
-- A nonzero credit claim does not appear because of an observation.

-- ===== 3. Observation tables =====
SELECT pg_temp.expect_rejected('a malformed CRN in an observation', format($q$ INSERT INTO banner_evidence.section_observation (source_id, term_code, crn) VALUES (%L, '202710', '1') $q$, :'live1'));
SELECT pg_temp.expect_rejected('an observation whose term differs from its source', format($q$ INSERT INTO banner_evidence.section_observation (source_id, term_code, crn) VALUES (%L, '202720', '99999') $q$, :'live1'));
SELECT pg_temp.expect_rejected('non-numeric displayed hours', format($q$ INSERT INTO banner_evidence.section_observation (source_id, term_code, crn, credit_hours_text) VALUES (%L, '202710', '99999', 'abc') $q$, :'live1'));
SELECT pg_temp.expect_rejected('a meeting that ends before it starts', format($q$ INSERT INTO banner_evidence.meeting_observation (source_id, crn, seq, days_iso, start_time, end_time) VALUES (%L, '13136', 1, ARRAY[5]::smallint[], TIME '10:00', TIME '09:00') $q$, :'live2'));
SELECT pg_temp.expect_rejected('a day outside 1..7', format($q$ INSERT INTO banner_evidence.meeting_observation (source_id, crn, seq, days_iso, start_time, end_time) VALUES (%L, '13136', 1, ARRAY[8]::smallint[], TIME '09:00', TIME '10:00') $q$, :'live2'));
SELECT pg_temp.expect_rejected('a meeting for a section the source never showed', format($q$ INSERT INTO banner_evidence.meeting_observation (source_id, crn, seq, days_iso, start_time, end_time) VALUES (%L, '99999', 1, ARRAY[5]::smallint[], TIME '09:00', TIME '10:00') $q$, :'live2'));
SELECT pg_temp.expect_rejected('a section linked to itself', format($q$ INSERT INTO banner_evidence.link_observation (source_id, lecture_crn, linked_crn, link_label) VALUES (%L, '13136', '13136', 'Lab - Linked') $q$, :'live2'));
SELECT pg_temp.expect_rejected('a link to a section the source never showed', format($q$ INSERT INTO banner_evidence.link_observation (source_id, lecture_crn, linked_crn, link_label) VALUES (%L, '14857', '99999', 'Lab - Linked') $q$, :'live2'));
SELECT pg_temp.expect_rejected('HTML in an observation note', format($q$ INSERT INTO banner_evidence.note_observation (source_id, note_kind, note_text) VALUES (%L, 'OTHER', '<div>page</div>') $q$, :'live2'));
SELECT pg_temp.expect_rejected('the same observation recorded twice', format($q$ INSERT INTO banner_evidence.section_observation (source_id, term_code, crn) VALUES (%L, '202710', '13136') $q$, :'live2'));

-- ===== 4. Claims =====
SELECT pg_temp.expect_rejected('a link claim with an unknown value', $q$ INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, other_crn, asserted_value) VALUES ('202710', 'LECTURE_LAB_LINK', 'CSE-017', '13507', '14855', NULL) $q$);
SELECT pg_temp.expect_rejected('a link claim without a lab', $q$ INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, other_crn, asserted_value) VALUES ('202710', 'LECTURE_LAB_LINK', 'CSE-017', '13507', NULL, 'LINKED') $q$);
SELECT pg_temp.expect_rejected('a non-numeric required lab count', $q$ INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, asserted_value) VALUES ('202710', 'REQUIRED_LAB_COUNT', 'CSE-017', '13507', 'many') $q$);
SELECT pg_temp.expect_rejected('a lab-credit claim carrying a second CRN', $q$ INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, other_crn, asserted_value) VALUES ('202710', 'LAB_CREDITS', 'CSE-017', '14855', '13507', '0') $q$);
SELECT pg_temp.expect_rejected('an unknown claim type', $q$ INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, asserted_value) VALUES ('202710', 'SCHEDULE_READY', 'CSE-017', '13507', 'YES') $q$);
SELECT pg_temp.expect_rejected('a malformed course id', $q$ INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, asserted_value) VALUES ('202710', 'LECTURE_CREDITS', 'cse17', '13507', '3') $q$);
SELECT pg_temp.expect_rejected('a duplicate UNKNOWN claim (NULL counts as equal)', $q$ INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, asserted_value) VALUES ('202710', 'REQUIRED_LAB_COUNT', 'CSE-017', '13507', NULL) $q$);

-- ===== 5. Evidence must actually be about its claim =====
-- Each rejection below can come from only one rule, so a weakened rule cannot hide behind another.
-- live1 observes lecture 11041, so only the UNKNOWN-value rule can refuse this one.
SELECT pg_temp.expect_rejected('evidence on an UNKNOWN claim',
	format($q$ INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance) VALUES (%s, %L, 'SUPPORTS') $q$, pg_temp.claim_id('LECTURE_CREDITS', '11041', NULL, NULL), :'live1'));
-- live3 is a capture of another term that does observe 11041, so only the term rule can refuse this one.
SELECT pg_temp.insert_live(p_term => '202720', p_sha => repeat('3', 64), p_crn => '11041') AS live3 \gset
INSERT INTO banner_evidence.section_observation (source_id, term_code, crn, course_id, linked_sections_outcome)
VALUES (:'live3', '202720', '11041', 'CSE-202', 'NONE_SHOWN');
SELECT pg_temp.expect_rejected('evidence from the wrong term',
	format($q$ INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance) VALUES (%s, %L, 'CONTRADICTS') $q$, pg_temp.claim_id('LECTURE_LAB_LINK', '11041', '14861', 'LINKED'), :'live3'));
-- A non-link claim about lecture 13507, backed by a source that never showed 13507: only the observation rule applies.
INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, asserted_value) VALUES ('202710', 'REQUIRED_LAB_COUNT', 'CSE-017', '13507', '1');
SELECT pg_temp.expect_rejected('evidence from a source that never showed the section',
	format($q$ INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance) VALUES (%s, %L, 'SUPPORTS') $q$, pg_temp.claim_id('REQUIRED_LAB_COUNT', '13507', NULL, '1'), :'live1'));
SELECT pg_temp.expect_rejected('support for a link from a source that never saw the lecture',
	format($q$ INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance) VALUES (%s, %L, 'SUPPORTS') $q$, pg_temp.claim_id('LECTURE_LAB_LINK', '13507', '14855', 'LINKED'), :'live1'));
SELECT pg_temp.expect_rejected('support for a link the source does not show',
	format($q$ INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance) VALUES (%s, %L, 'SUPPORTS') $q$, pg_temp.claim_id('LECTURE_LAB_LINK', '11041', '14861', 'LINKED'), :'live1'));
-- Whatever evidence the original claim already has (the reports, and any Banner captures), it must be unchanged afterwards.
SELECT evidence_status || ':' || evidence_rows AS orig_state FROM banner_evidence.claim_status
WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13136', 'LINKED') \gset
INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, other_crn, asserted_value)
VALUES ('202710', 'LECTURE_LAB_LINK', 'CSE-109', '14857', '13136', 'NOT_LINKED');
-- live4 observed lecture 14857 but never looked at its Linked Sections: it cannot contradict a link.
SELECT pg_temp.insert_live(p_sha => repeat('4', 64), p_crn => '14857') AS live4 \gset
INSERT INTO banner_evidence.section_observation (source_id, term_code, crn, course_id, linked_sections_outcome)
VALUES (:'live4', '202710', '14857', 'CSE-109', 'NOT_CHECKED');
SELECT pg_temp.expect_rejected('a contradiction from a source that did not read the linked sections',
	format($q$ INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance) VALUES (%s, %L, 'CONTRADICTS') $q$, pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13136', 'LINKED'), :'live4'));
SELECT pg_temp.expect_rejected('a contradiction from a source that shows the link',
	format($q$ INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance) VALUES (%s, %L, 'CONTRADICTS') $q$, pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13136', 'LINKED'), :'live2'));

-- ===== 6. Reviews are human decisions with a reason =====
SELECT pg_temp.expect_rejected('an automatic-sounding decision',
	format($q$ INSERT INTO banner_evidence.claim_review (claim_id, reviewer, decision, rationale) VALUES (%s, 'script', 'AUTO_APPROVED', 'x') $q$, pg_temp.claim_id('LECTURE_LAB_LINK', '13507', '14855', 'LINKED')));
SELECT pg_temp.expect_rejected('a review without a rationale',
	format($q$ INSERT INTO banner_evidence.claim_review (claim_id, reviewer, decision, rationale) VALUES (%s, 'michael', 'CONFIRMED', '') $q$, pg_temp.claim_id('LECTURE_LAB_LINK', '13507', '14855', 'LINKED')));
SELECT pg_temp.expect_rejected('a reviewer given as an e-mail address',
	format($q$ INSERT INTO banner_evidence.claim_review (claim_id, reviewer, decision, rationale) VALUES (%s, 'someone@example.com', 'CONFIRMED', 'x') $q$, pg_temp.claim_id('LECTURE_LAB_LINK', '13507', '14855', 'LINKED')));

-- ===== 7. Conflicting claims coexist and are flagged =====
-- Step 5 already added a NOT_LINKED claim for 14857>13136 next to the LINKED one.
SELECT pg_temp.expect_true('both conflicting claims exist',
	(SELECT count(*) FROM banner_evidence.claim WHERE claim_type = 'LECTURE_LAB_LINK' AND crn = '14857' AND other_crn = '13136') = 2);
SELECT pg_temp.expect_true('both are flagged CONFLICT and queued for review',
	(SELECT count(*) FROM banner_evidence.review_queue WHERE claim_type = 'LECTURE_LAB_LINK' AND crn = '14857' AND other_crn = '13136' AND review_flag = 'CONFLICT' AND in_conflict) = 2);
SELECT pg_temp.expect_true('the original claim and all its evidence are untouched by the conflicting claim',
	(SELECT evidence_status || ':' || evidence_rows FROM banner_evidence.claim_status WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13136', 'LINKED')) = :'orig_state');
SELECT pg_temp.expect_true('the course summary counts both as conflicts',
	(SELECT conflicts FROM banner_evidence.course_evidence_summary WHERE course_id = 'CSE-109') = 2);
SELECT pg_temp.expect_true('a claim with a value and no evidence is not support',
	(SELECT evidence_status FROM banner_evidence.claim_status WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13136', 'NOT_LINKED')) = 'UNKNOWN');

-- ===== 8. Automatic Banner evidence is evidence, not approval =====
INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance)
VALUES (pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13137', 'LINKED'), :'live2', 'SUPPORTS');
SELECT pg_temp.expect_true('live Banner support makes a link VERIFIED_BANNER but unreviewed and not promotable',
	(SELECT evidence_status || ':' || review_state || ':' || review_flag || ':' || promotion_eligible FROM banner_evidence.claim_status
		WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13137', 'LINKED')) = 'VERIFIED_BANNER:UNREVIEWED:AWAITING_REVIEW:false');
SELECT pg_temp.expect_true('collecting Banner evidence wrote no review', (SELECT count(*) FROM banner_evidence.claim_review) = 0);
INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, asserted_value) VALUES
	('202710', 'REQUIRED_LAB_COUNT', 'CSE-109', '14857', '1'),
	('202710', 'LECTURE_CREDITS', 'CSE-109', '14857', '4.000'),
	('202710', 'LAB_CREDITS', 'CSE-109', '13136', '0');
INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance) VALUES
	(pg_temp.claim_id('REQUIRED_LAB_COUNT', '14857', NULL, '1'), :'live2', 'SUPPORTS'),
	(pg_temp.claim_id('LECTURE_CREDITS', '14857', NULL, '4.000'), :'live2', 'SUPPORTS'),
	(pg_temp.claim_id('LAB_CREDITS', '13136', NULL, '0'), :'live2', 'SUPPORTS');
SELECT pg_temp.expect_true('Banner alone cannot verify a required lab count, lecture credits or lab credits',
	(SELECT array_agg(claim_type || ':' || evidence_status ORDER BY claim_type) FROM banner_evidence.claim_status
		WHERE claim_id IN (pg_temp.claim_id('REQUIRED_LAB_COUNT', '14857', NULL, '1'), pg_temp.claim_id('LECTURE_CREDITS', '14857', NULL, '4.000'),
			pg_temp.claim_id('LAB_CREDITS', '13136', NULL, '0')))
	= ARRAY['LAB_CREDITS:PROVISIONAL', 'LECTURE_CREDITS:PROVISIONAL', 'REQUIRED_LAB_COUNT:PROVISIONAL']);
SELECT pg_temp.expect_true('a placeholder UNKNOWN is marked superseded once a value exists',
	(SELECT review_flag FROM banner_evidence.claim_status WHERE claim_id = pg_temp.claim_id('REQUIRED_LAB_COUNT', '14857', NULL, NULL)) = 'SUPERSEDED');

-- ===== 9. Only a person's review, plus Banner or team evidence, makes a claim promotable =====
-- A synthetic link that ONLY a user report supports (real links may already have Banner evidence).
WITH s AS (
	INSERT INTO banner_evidence.evidence_source (source_kind, page_kind, term_code, subject_crn, captured_on, captured_by_role,
		inspected_by_claude, content_sha256, sanitization_note, provenance_note)
	VALUES ('USER_SCREENSHOT', 'LINKED_SECTIONS', '202710', '99001', DATE '2026-10-17', 'USER', false, repeat('5', 64), 'probe', 'probe')
	RETURNING source_id)
SELECT source_id AS rep FROM s \gset
INSERT INTO banner_evidence.section_observation (source_id, term_code, crn, course_id, linked_sections_outcome) VALUES
	(:'rep', '202710', '99001', 'CSE-099', 'LISTED'), (:'rep', '202710', '99002', 'CSE-099', 'NOT_CHECKED');
INSERT INTO banner_evidence.link_observation (source_id, lecture_crn, linked_crn, link_label) VALUES (:'rep', '99001', '99002', 'Lab - Linked');
INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, other_crn, asserted_value)
VALUES ('202710', 'LECTURE_LAB_LINK', 'CSE-099', '99001', '99002', 'LINKED');
INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance)
VALUES (pg_temp.claim_id('LECTURE_LAB_LINK', '99001', '99002', 'LINKED'), :'rep', 'SUPPORTS');
INSERT INTO banner_evidence.claim_review (claim_id, reviewer, decision, rationale) VALUES
	(pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13137', 'LINKED'), 'probe-reviewer', 'CONFIRMED', 'probe: matches Banner'),
	(pg_temp.claim_id('LECTURE_LAB_LINK', '99001', '99002', 'LINKED'), 'probe-reviewer', 'CONFIRMED', 'probe: user report only'),
	(pg_temp.claim_id('REQUIRED_LAB_COUNT', '14857', NULL, '1'), 'probe-reviewer', 'CONFIRMED', 'probe: Banner cannot state this');
SELECT pg_temp.expect_true('a confirmed, Banner-supported link is promotable',
	(SELECT promotion_eligible FROM banner_evidence.claim_status WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13137', 'LINKED')));
SELECT pg_temp.expect_true('a confirmed user-report-only link is NOT promotable',
	NOT (SELECT promotion_eligible FROM banner_evidence.claim_status WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '99001', '99002', 'LINKED'))
	AND (SELECT evidence_status FROM banner_evidence.claim_status WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '99001', '99002', 'LINKED')) = 'USER_REPORTED');
SELECT pg_temp.expect_true('a confirmed but only-provisional lab count is NOT promotable',
	NOT (SELECT promotion_eligible FROM banner_evidence.claim_status WHERE claim_id = pg_temp.claim_id('REQUIRED_LAB_COUNT', '14857', NULL, '1')));

-- ===== 10. Contradicting evidence overrides support and review =====
INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance)
VALUES (pg_temp.claim_id('LECTURE_LAB_LINK', '11041', '14861', 'LINKED'), :'live1', 'CONTRADICTS');
INSERT INTO banner_evidence.claim_review (claim_id, reviewer, decision, rationale)
VALUES (pg_temp.claim_id('LECTURE_LAB_LINK', '11041', '14861', 'LINKED'), 'probe-reviewer', 'CONFIRMED', 'probe: confirmed despite contradiction');
SELECT pg_temp.expect_true('a contradicted claim is CONTRADICTED, flagged CONFLICT and not promotable even if confirmed',
	(SELECT evidence_status || ':' || review_flag || ':' || promotion_eligible FROM banner_evidence.claim_status
		WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '11041', '14861', 'LINKED')) = 'CONTRADICTED:CONFLICT:false');

-- ===== 11. A person can resolve a conflict by rejecting one side =====
INSERT INTO banner_evidence.claim_review (claim_id, reviewer, decision, rationale)
VALUES (pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13136', 'NOT_LINKED'), 'probe-reviewer', 'REJECTED', 'probe: Banner lists the lab');
SELECT pg_temp.expect_true('after rejecting one side the other is no longer in conflict',
	(SELECT review_flag || ':' || in_conflict FROM banner_evidence.claim_status WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13136', 'LINKED')) = 'AWAITING_REVIEW:false');
SELECT pg_temp.expect_true('the rejected claim leaves the review queue',
	NOT EXISTS (SELECT 1 FROM banner_evidence.review_queue WHERE claim_id = pg_temp.claim_id('LECTURE_LAB_LINK', '14857', '13136', 'NOT_LINKED')));

-- ===== 12. No course becomes schedule-ready because of evidence =====
SELECT pg_temp.expect_true('catalog_api still offers only CSE-007, and it is the only schedulable course',
	(SELECT array_agg(course_id::text ORDER BY course_id) FROM catalog_api.course_offering) = ARRAY['CSE-007']
	AND (SELECT array_agg(course_id::text ORDER BY course_id) FROM catalog_api.course_offering WHERE schedulable) = ARRAY['CSE-007']);
SELECT pg_temp.expect_true('catalog_api sections are still exactly the three CSE 007 sections',
	(SELECT array_agg(crn::text ORDER BY crn) FROM catalog_api.section_offering) = ARRAY['12275', '12638', '13132']);
SELECT pg_temp.expect_true('even a fully confirmed, Banner-verified link does not appear in the catalog',
	NOT EXISTS (SELECT 1 FROM catalog_api.section_offering WHERE crn IN ('14857', '13137')));

-- ===== 13. Append-only: nothing recorded can be changed or removed =====
SELECT pg_temp.expect_rejected('UPDATE evidence_source', $q$ UPDATE banner_evidence.evidence_source SET provenance_note = 'edited' $q$);
SELECT pg_temp.expect_rejected('DELETE evidence_source', $q$ DELETE FROM banner_evidence.evidence_source $q$);
SELECT pg_temp.expect_rejected('UPDATE section_observation', $q$ UPDATE banner_evidence.section_observation SET credit_hours_text = '9' $q$);
SELECT pg_temp.expect_rejected('DELETE meeting_observation', $q$ DELETE FROM banner_evidence.meeting_observation $q$);
SELECT pg_temp.expect_rejected('UPDATE link_observation', $q$ UPDATE banner_evidence.link_observation SET link_label = 'x' $q$);
SELECT pg_temp.expect_rejected('DELETE note_observation', $q$ DELETE FROM banner_evidence.note_observation $q$);
SELECT pg_temp.expect_rejected('UPDATE claim', $q$ UPDATE banner_evidence.claim SET asserted_value = 'LINKED' $q$);
SELECT pg_temp.expect_rejected('DELETE claim_evidence', $q$ DELETE FROM banner_evidence.claim_evidence $q$);
SELECT pg_temp.expect_rejected('UPDATE claim_review', $q$ UPDATE banner_evidence.claim_review SET decision = 'CONFIRMED' $q$);
SELECT pg_temp.expect_rejected('DELETE claim_review', $q$ DELETE FROM banner_evidence.claim_review $q$);
SELECT pg_temp.expect_rejected('TRUNCATE claim', $q$ TRUNCATE banner_evidence.claim CASCADE $q$);
SELECT pg_temp.expect_rejected('TRUNCATE evidence_source', $q$ TRUNCATE banner_evidence.evidence_source CASCADE $q$);

-- ===== The count proves no probe was skipped =====
SELECT current_setting('t.passed')::int AS passed \gset
SELECT :passed = 78 AS ok \gset
\if :ok
	\echo 'PASS: banner_evidence probes:' :passed 'probes held (rejections, conflicts, review gate, catalog untouched, append-only)'
\else
	\echo 'FAIL: expected 78 probes, recorded' :passed
	SELECT 1 / 0;
\endif
