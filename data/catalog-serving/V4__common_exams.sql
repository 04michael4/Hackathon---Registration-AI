-- catalog_serving, part 4: show common evening exams as a warning (COMMON_EXAMS), per the project owner's decision of 2026-10-17: a student is warned,
-- the exam is not a hard schedule conflict and not a meeting of the generated schedule. Supersedes V3's course_condition (same columns). Run after V3.
\set ON_ERROR_STOP on

-- Recreated (not replaced) so a rerun can change its columns; nothing depends on it.
DROP VIEW IF EXISTS catalog_serving.course_condition;
CREATE VIEW catalog_serving.course_condition AS
SELECT c.term_code, c.course_id, c.ord AS seq, c.code, c.message, c.evidence_level, 'UNVERIFIED'::text AS outcome
FROM (
	-- Prerequisites that the app can read as a rule
	SELECT r.term_code, r.offering_id AS course_id, 1 AS ord, 'PREREQUISITE'::text AS code,
		'Prerequisite: ' || catalog_serving.expression_text(r.expression) AS message, 'BANNER'::text AS evidence_level
	FROM catalog_research.eligibility_rule r
	WHERE r.rule_kind = 'PREREQUISITE' AND r.parse_status = 'STRUCTURED' AND r.expression IS NOT NULL
	UNION ALL
	-- A prerequisite Banner states only in words: shown, never checked
	SELECT r.term_code, r.offering_id, 1, 'PREREQUISITE',
		'Prerequisite listed by Banner in words only; the app does not check it. See Banner for the exact wording.', 'BANNER'
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'PREREQUISITE' AND r.parse_status = 'TEXT_ONLY'
	UNION ALL
	SELECT r.term_code, r.offering_id, 1, 'COREQUISITE',
		'Corequisite: ' || catalog_serving.expression_text(r.expression), 'BANNER'::text
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'COREQUISITE' AND r.parse_status = 'STRUCTURED' AND r.expression IS NOT NULL
	UNION ALL
	SELECT r.term_code, r.offering_id, 1, 'COREQUISITE',
		'Corequisite listed by Banner in words only; the app does not check it. See Banner for the exact wording.', 'BANNER'
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'COREQUISITE' AND r.parse_status = 'TEXT_ONLY'
	UNION ALL
	SELECT q.term_code, q.offering_id, 2, 'ENROLLMENT_RESTRICTION',
		'Enrollment restrictions are listed by Banner in words only; the app does not check them. See Banner for the exact wording.', 'BANNER'
	FROM catalog_research.requirement_status q WHERE q.requirement_type = 'ENROLLMENT_RESTRICTION' AND q.status = 'TEXT_ONLY'
	UNION ALL
	SELECT q.term_code, q.offering_id, 12, 'NOT_CHECKED',
		'The ' || lower(replace(q.requirement_type, '_', ' ')) || ' of this course has not been checked.', 'BANNER'
	FROM catalog_research.requirement_status q WHERE q.status = 'UNKNOWN'
	UNION ALL
	SELECT r.term_code, r.offering_id, 2, 'CLASS_STANDING',
		CASE r.polarity WHEN 'MUST' THEN 'Open to these class standings only: ' ELSE 'Not open to these class standings: ' END
			|| (SELECT string_agg(catalog_serving.standing_label(v), ', ' ORDER BY v) FROM unnest(r.rule_values) v), r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'CLASS_STANDING'
	UNION ALL
	SELECT r.term_code, r.offering_id, 3, 'LEVEL',
		'Open to these levels only: ' || (SELECT string_agg(catalog_serving.level_label(v), ', ' ORDER BY v) FROM unnest(r.rule_values) v), r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'LEVEL'
	UNION ALL
	SELECT r.term_code, r.offering_id, 4, 'COLLEGE',
		'Open to students in these colleges only (Banner codes): ' || (SELECT string_agg(v, ', ' ORDER BY v) FROM unnest(r.rule_values) v), r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'COLLEGE'
	UNION ALL
	SELECT r.term_code, r.offering_id, 5, 'MAJOR',
		'Open to students with these majors only (Banner codes): ' || (SELECT string_agg(v, ', ' ORDER BY v) FROM unnest(r.rule_values) v), r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'MAJOR'
	UNION ALL
	SELECT r.term_code, r.offering_id, 6, 'DEPARTMENT_APPROVAL', 'Department permission is required.', r.evidence_level
	FROM catalog_research.eligibility_rule r WHERE r.rule_kind = 'DEPARTMENT_APPROVAL'
	UNION ALL
	-- Credit exclusions. SOURCES_CONFLICT is left out: the team follows the current catalog (decision of 2026-10-17), which omits that exclusion.
	SELECT x.term_code, x.offering_id, 7, 'CREDIT_EXCLUSION',
		CASE x.exclusion_kind
			WHEN 'CREDIT_AND_ENROLLMENT' THEN 'Cannot be taken with existing credit for ' || x.excluded_text || '.'
			ELSE 'No credit for both this course and ' || x.excluded_text || '.' END,
		CASE x.evidence_level WHEN 'RESEARCH_WORKBOOK' THEN 'CATALOG' ELSE x.evidence_level END
	FROM catalog_research.credit_exclusion x WHERE x.exclusion_kind <> 'SOURCES_CONFLICT'
	UNION ALL
	-- A mandatory meeting without a published time
	SELECT DISTINCT s.term_code, s.offering_id, 8, 'ARRANGED_MEETING',
		'Includes a required meeting at a time to be arranged (no published time); it is not part of the generated schedule.', 'BANNER'
	FROM catalog_research.section_arranged_meeting a JOIN catalog_research.section s ON s.term_code = a.term_code AND s.crn = a.crn
	UNION ALL
	-- Whether a lab or recitation is required
	SELECT o.term_code, o.offering_id, 9, 'LINKED_COMPONENTS',
		'Whether this course also requires a lab or recitation has not been verified.', 'BANNER'
	FROM catalog_research.offering o WHERE o.components_status = 'COMPONENTS_UNVERIFIED'
	UNION ALL
	SELECT o.term_code, o.offering_id, 9, 'LINKED_COMPONENTS',
		'The lab requirement was reported by the team and is not confirmed by Banner.', 'USER_CLARIFICATION'
	FROM catalog_research.offering o WHERE o.components_status IN ('LABS_USER_SUPPLIED', 'STANDALONE_USER_SUPPLIED')
	UNION ALL
	SELECT o.term_code, o.offering_id, 10, 'CROSS_LISTING',
		'Reported as cross-listed with another subject; this is not confirmed by Banner.', 'USER_CLARIFICATION'
	FROM catalog_research.offering o WHERE o.cross_listing_status = 'CROSS_LISTED_USER_ASSERTED_UNVERIFIED'
	UNION ALL
	-- An administrative section type Banner does not treat as an ordinary class, allowed by a recorded team decision
	SELECT o.term_code, o.offering_id, 11, 'SECTION_TYPE',
		'Banner lists this section as "' || o.administrative_type || '". The team includes it in proposed schedules; this does not mean registration is allowed.', 'TEAM_POLICY'
	FROM catalog_research.offering o WHERE o.administrative_type NOT IN ('Lecture', 'Class', 'Lab', 'Recitation')
	UNION ALL
	-- Common evening exams: a warning only (owner decision of 2026-10-17); they are not meetings of the generated schedule.
	SELECT e.term_code, e.offering_id, 12, 'COMMON_EXAMS',
		'Has common evening exams (' || min(e.start_time) || '-' || max(e.end_time) || ') on ' ||
			string_agg(to_char(e.meeting_date, 'Mon FMDD'), ', ' ORDER BY e.meeting_date) ||
			'. Make sure your other classes do not meet then; exams are not counted as schedule conflicts.', 'BANNER'
	FROM (SELECT DISTINCT term_code, offering_id, meeting_date, start_time, end_time FROM catalog_research.exam_meeting) e
	GROUP BY e.term_code, e.offering_id
) c
JOIN catalog_serving.served_offering v ON v.term_code = c.term_code AND v.offering_id = c.course_id
ORDER BY c.term_code, c.course_id, c.ord, c.message;
