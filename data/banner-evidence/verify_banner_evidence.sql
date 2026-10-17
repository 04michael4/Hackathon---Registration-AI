-- Read-only assertions that the Step 1 content is INTACT: the seven user-reported lecture->lab links and what was
-- deliberately left unknown. Filtered to the user-report sources, so later Banner captures do not break it.
\set ON_ERROR_STOP on
\echo '--- the seven links with their evidence (reports first)'
SELECT course_id, crn AS lecture, other_crn AS lab, evidence_status, review_state, promotion_eligible
FROM banner_evidence.claim_status WHERE claim_type = 'LECTURE_LAB_LINK' ORDER BY course_id, other_crn;

DO $$
DECLARE
	n int;
BEGIN
	-- The five user-report sources and everything recorded from them.
	IF (SELECT count(*) FROM banner_evidence.evidence_source WHERE source_kind = 'USER_SCREENSHOT') <> 5
		OR (SELECT count(*) FROM banner_evidence.section_observation o JOIN banner_evidence.evidence_source s USING (source_id) WHERE s.source_kind = 'USER_SCREENSHOT') <> 12
		OR (SELECT count(*) FROM banner_evidence.meeting_observation m JOIN banner_evidence.evidence_source s USING (source_id) WHERE s.source_kind = 'USER_SCREENSHOT') <> 7
		OR (SELECT count(*) FROM banner_evidence.link_observation l JOIN banner_evidence.evidence_source s USING (source_id) WHERE s.source_kind = 'USER_SCREENSHOT') <> 7
		OR (SELECT count(*) FROM banner_evidence.note_observation x JOIN banner_evidence.evidence_source s USING (source_id) WHERE s.source_kind = 'USER_SCREENSHOT') <> 5 THEN
		RAISE EXCEPTION 'the user-report sources must hold sources 5, sections 12, meetings 7, links 7, notes 5';
	END IF;
	-- Exactly the seven reported relationships, each still backed by its user report.
	IF (SELECT array_agg(c.crn || '>' || c.other_crn ORDER BY c.crn, c.other_crn) FROM banner_evidence.claim c
			WHERE c.claim_type = 'LECTURE_LAB_LINK' AND c.asserted_value = 'LINKED'
			AND EXISTS (SELECT 1 FROM banner_evidence.claim_evidence ce JOIN banner_evidence.evidence_source s USING (source_id)
				WHERE ce.claim_id = c.claim_id AND ce.stance = 'SUPPORTS' AND s.source_kind = 'USER_SCREENSHOT'))
		IS DISTINCT FROM ARRAY['11041>14861', '13507>14855', '13507>14856', '13514>13186', '13553>13554', '14857>13136', '14857>13137'] THEN
		RAISE EXCEPTION 'the seven reported links, each with its user-report evidence, are not exactly as reported';
	END IF;
	-- The reports were never overwritten: all seven user-report evidence rows are still SUPPORTS.
	IF (SELECT count(*) FROM banner_evidence.claim_evidence ce JOIN banner_evidence.evidence_source s USING (source_id)
			WHERE s.source_kind = 'USER_SCREENSHOT' AND ce.stance = 'SUPPORTS') <> 7 THEN
		RAISE EXCEPTION 'user-report evidence rows changed';
	END IF;
	-- The reported lab meeting times, as recorded.
	IF (SELECT array_agg(m.crn || '|' || array_to_string(m.days_iso, ',') || '|' || m.start_time || '|' || m.end_time ORDER BY m.crn)
			FROM banner_evidence.meeting_observation m JOIN banner_evidence.evidence_source s USING (source_id) WHERE s.source_kind = 'USER_SCREENSHOT')
		IS DISTINCT FROM ARRAY['13136|5|09:20:00|10:35:00', '13137|5|10:45:00|12:00:00', '13186|5|13:35:00|16:15:00',
			'13554|5|13:35:00|14:50:00', '14855|4|10:45:00|12:00:00', '14856|4|13:35:00|14:50:00', '14861|3|12:10:00|13:25:00'] THEN
		RAISE EXCEPTION 'reported lab meeting times differ';
	END IF;
	-- User-report sources are uninspected reports with no URL, file or timestamp invented, and no hours value.
	IF EXISTS (SELECT 1 FROM banner_evidence.evidence_source
			WHERE source_kind = 'USER_SCREENSHOT' AND (inspected_by_claude OR captured_by_role <> 'USER' OR source_host IS NOT NULL
				OR source_path IS NOT NULL OR evidence_file IS NOT NULL OR captured_at IS NOT NULL OR captured_on <> DATE '2026-10-16')) THEN
		RAISE EXCEPTION 'user-report sources must stay uninspected reports with no URL, file or timestamp';
	END IF;
	IF EXISTS (SELECT 1 FROM banner_evidence.section_observation o JOIN banner_evidence.evidence_source s USING (source_id)
			WHERE s.source_kind = 'USER_SCREENSHOT' AND o.credit_hours_text IS NOT NULL) THEN
		RAISE EXCEPTION 'Hours must not be recorded as a section value from the report';
	END IF;
	-- Required lab count, lecture credits and lab credits are still explicit UNKNOWN for the five courses: no value, no evidence.
	SELECT count(*) INTO n FROM banner_evidence.claim_status
	WHERE course_id IN ('CSE-017', 'CSE-109', 'CSE-202', 'CSE-216', 'CSE-241')
		AND claim_type IN ('REQUIRED_LAB_COUNT', 'LECTURE_CREDITS', 'LAB_CREDITS') AND asserted_value IS NULL
		AND evidence_status = 'UNKNOWN' AND evidence_rows = 0 AND review_flag = 'UNKNOWN_FACT';
	IF n <> 17 THEN
		RAISE EXCEPTION 'expected 17 explicit UNKNOWN claims for the five courses (found %)', n;
	END IF;
	-- Evidence is not approval: no review exists, nothing is promotable, and only link claims can be Banner-verified.
	IF EXISTS (SELECT 1 FROM banner_evidence.claim_review) THEN
		RAISE EXCEPTION 'no review may exist: evidence is not approval';
	END IF;
	IF EXISTS (SELECT 1 FROM banner_evidence.claim_status WHERE promotion_eligible OR evidence_status = 'TEAM_CONFIRMED') THEN
		RAISE EXCEPTION 'nothing may be team-confirmed or promotable without a review';
	END IF;
	IF EXISTS (SELECT 1 FROM banner_evidence.claim_status WHERE evidence_status = 'VERIFIED_BANNER' AND claim_type <> 'LECTURE_LAB_LINK') THEN
		RAISE EXCEPTION 'only link claims may be VERIFIED_BANNER';
	END IF;
	IF EXISTS (SELECT 1 FROM banner_evidence.claim WHERE claim_type = 'STANDALONE_STATUS' AND asserted_value IS NOT NULL) THEN
		RAISE EXCEPTION 'standalone status must stay UNKNOWN';
	END IF;
	IF EXISTS (SELECT 1 FROM banner_evidence.evidence_source WHERE NOT banner_evidence.is_safe_text(sanitization_note)
			OR NOT banner_evidence.is_safe_text(provenance_note))
		OR EXISTS (SELECT 1 FROM banner_evidence.note_observation WHERE NOT banner_evidence.is_safe_text(note_text)) THEN
		RAISE EXCEPTION 'stored text failed the safety check';
	END IF;
	RAISE NOTICE 'PASS: banner_evidence Step 1 content intact: 7 reported links with their user-report evidence, 17 explicit UNKNOWN claims, no review, nothing promotable';
END $$;
