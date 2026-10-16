\set ON_ERROR_STOP on
SET search_path TO cse007_snapshot;

SELECT c.code, t.term_code, s.section_code, s.crn, s.component_type, s.instructor,
	s.credits, s.credits_verification_status, s.link_status, s.delivery_type,
	string_agg(
		(ARRAY['Mon','Tue','Wed','Thu','Fri','Sat','Sun'])[m.day_of_week] || ' ' ||
		to_char(m.start_time,'HH24:MI') || '-' || to_char(m.end_time,'HH24:MI'),
		', ' ORDER BY m.day_of_week
	) AS meetings
FROM section s JOIN course c USING (course_id) JOIN term t USING (term_id)
JOIN meeting m USING (section_id)
GROUP BY c.code, t.term_code, s.section_id
ORDER BY s.section_code;

-- User-confirmed demo options: choose exactly one lab. Seats are not considered.
SELECT a.section_code AS lecture, b.section_code AS lab,
	a.credits + b.credits AS total_credits,
	g.required_count AS required_lab_count, 'USER_CONFIRMED_DEMO' AS basis
FROM section_link l JOIN section a ON a.section_id = l.primary_section_id
JOIN section b ON b.section_id = l.compatible_section_id
JOIN section_component_group g USING (primary_section_id, required_component_group)
WHERE NOT EXISTS (SELECT 1 FROM meeting ma JOIN meeting mb ON ma.day_of_week = mb.day_of_week
	AND ma.start_time < mb.end_time AND mb.start_time < ma.end_time
	WHERE ma.section_id = a.section_id AND mb.section_id = b.section_id);

-- Static screenshot counts, with unknown capture time; never a live availability API.
SELECT s.crn, s.section_code, e.enrollment_actual, e.enrollment_maximum,
	e.enrollment_seats_available, e.is_live, a.captured_at, a.file_path
FROM enrollment_snapshot e JOIN section s USING (section_id)
JOIN source_asset a ON a.source_id = e.source_id;

SELECT requirement_type, requirement_expression, assessment_status FROM course_requirement
ORDER BY requirement_type;

SELECT s.section_code, i.code, i.status, i.resolution FROM data_issue i
LEFT JOIN section s USING (section_id) ORDER BY i.issue_id;
